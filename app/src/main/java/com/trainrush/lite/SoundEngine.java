package com.trainrush.lite;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Process;

/**
 * All sound is generated in code (no audio files in the APK, so storage stays tiny).
 * One mixer thread streams 22 kHz mono 16-bit audio: sound effects, an engine/track
 * rumble that follows the train speed, and a small background tune.
 */
final class SoundEngine implements Runnable {
    static final int HORN = 0, COIN = 1, BOOST = 2, CRASH = 3, CLICK = 4, OVER = 5, START = 6;
    private static final int N_SFX = 7;
    private static final int RATE = 22050;
    private static final int VOICES = 6;
    private static final int CHUNK = 512;

    private static final int[] SCALE = {0, 3, 5, 7, 10};                 // minor pentatonic
    private static final int[] BASS = {0, 0, 3, 3, 2, 2, 4, 4};           // 8 steps, scale degrees
    private static final int[] LEAD = {0, 2, 4, 2, 5, 4, 2, 4, 3, 2, 0, 2, 4, 2, 1, 0};

    private final short[][] bank = new short[N_SFX][];
    private final short[][] vBuf = new short[VOICES][];
    private final int[] vPos = new int[VOICES];

    private volatile boolean running;
    private Thread thread;

    private volatile boolean engineOn;
    private volatile float engineSpeed;   // 0..1
    private volatile boolean musicOn = true;
    private volatile int bpm = 128;
    private volatile int root = 57;

    SoundEngine() {
        buildBank();
    }

    // ------------------------------------------------------------ public API

    void start() {
        if (running) return;
        running = true;
        thread = new Thread(this, "audio");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null) {
            try {
                t.join(600);
            } catch (InterruptedException ignored) {
            }
        }
    }

    synchronized void play(int id) {
        if (id < 0 || id >= N_SFX) return;
        int slot = -1;
        for (int i = 0; i < VOICES; i++) {
            if (vBuf[i] == null) {
                slot = i;
                break;
            }
        }
        if (slot < 0) slot = 0; // all busy: replace the first one
        vBuf[slot] = bank[id];
        vPos[slot] = 0;
    }

    void setEngine(boolean on, float speed01) {
        engineOn = on;
        engineSpeed = speed01 < 0f ? 0f : (speed01 > 1f ? 1f : speed01);
    }

    void setMusic(boolean on) {
        musicOn = on;
    }

    void setTune(int bpm, int rootNote) {
        this.bpm = bpm;
        this.root = rootNote;
    }

    // ------------------------------------------------------------ mixer thread

    @SuppressWarnings("deprecation")
    @Override
    public void run() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        } catch (Throwable ignored) {
        }
        int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) min = 4096;
        AudioTrack track;
        try {
            track = new AudioTrack(AudioManager.STREAM_MUSIC, RATE, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 4096), AudioTrack.MODE_STREAM);
            track.play();
        } catch (Throwable t) {
            running = false;   // no audio on this device: the game simply stays silent
            return;
        }

        short[] out = new short[CHUNK];
        int noiseState = 0x1234567;
        float lp = 0f, hp = 0f, prevNoise = 0f;
        float chPhase = 0f, chEnv = 0f, clPhase = 0f, clEnv = 0f, rumble = 0f;
        float engineLevel = 0f;

        // music state
        int step = 0, stepLeft = 0;
        float bassF = 0f, leadF = 0f, bassPh = 0f, leadPh = 0f, bassEnv = 0f, leadEnv = 0f;

        while (running) {
            float spd = engineSpeed;
            float chuffRate = 2.2f + spd * 9f;          // chuffs per second
            float clackRate = 4f + spd * 24f;           // rail joints per second
            float rumbleInc = (38f + spd * 40f) / RATE;
            float targetLevel = engineOn ? 1f : 0f;
            boolean music = musicOn;
            int stepLen = RATE * 30 / Math.max(60, bpm); // one eighth note

            for (int i = 0; i < CHUNK; i++) {
                float s = 0f;

                // --- sound effects
                synchronized (this) {
                    for (int v = 0; v < VOICES; v++) {
                        short[] b = vBuf[v];
                        if (b != null) {
                            s += b[vPos[v]++] * (1f / 32768f);
                            if (vPos[v] >= b.length) vBuf[v] = null;
                        }
                    }
                }

                // --- engine: noise
                noiseState ^= noiseState << 13;
                noiseState ^= noiseState >>> 17;
                noiseState ^= noiseState << 5;
                float noise = (noiseState & 0xFFFF) / 32768f - 1f;

                engineLevel += (targetLevel - engineLevel) * 0.0004f;
                if (engineLevel > 0.002f) {
                    chPhase += chuffRate / RATE;
                    if (chPhase >= 1f) {
                        chPhase -= 1f;
                        chEnv = 1f;
                    }
                    chEnv *= 0.9985f;
                    lp += (noise - lp) * 0.12f;
                    rumble += rumbleInc;
                    if (rumble >= 1f) rumble -= 1f;
                    float tri = rumble < 0.5f ? rumble * 4f - 1f : 3f - rumble * 4f;

                    clPhase += clackRate / RATE;
                    if (clPhase >= 1f) {
                        clPhase -= 1f;
                        clEnv = 1f;
                    }
                    clEnv *= 0.992f;
                    hp = noise - prevNoise;
                    prevNoise = noise;

                    float eng = lp * chEnv * 0.9f + tri * 0.10f + hp * clEnv * 0.10f * spd;
                    s += eng * 0.45f * engineLevel;
                }

                // --- music
                if (music) {
                    if (--stepLeft <= 0) {
                        stepLeft = stepLen;
                        int r = root;
                        if ((step & 1) == 0) {
                            bassF = midi(r - 12 + SCALE[BASS[(step >> 1) & 7]]);
                            bassEnv = 1f;
                        }
                        int li = LEAD[step & 15];
                        leadF = midi(r + 12 + SCALE[li % 5] + 12 * (li / 5));
                        leadEnv = 1f;
                        step = (step + 1) & 15;
                    }
                    bassPh += bassF / RATE;
                    if (bassPh >= 1f) bassPh -= 1f;
                    leadPh += leadF / RATE;
                    if (leadPh >= 1f) leadPh -= 1f;
                    bassEnv *= 0.99985f;
                    leadEnv *= 0.9996f;
                    float b = (bassPh < 0.5f ? 1f : -1f) * bassEnv * 0.06f;
                    float l = (leadPh < 0.25f ? 1f : -1f) * leadEnv * 0.035f;
                    s += b + l;
                }

                if (s > 1f) s = 1f;
                else if (s < -1f) s = -1f;
                out[i] = (short) (s * 32000f);
            }
            track.write(out, 0, CHUNK);
        }

        try {
            track.stop();
        } catch (Throwable ignored) {
        }
        track.release();
    }

    private static float midi(int note) {
        return (float) (440.0 * Math.pow(2.0, (note - 69) / 12.0));
    }

    // ------------------------------------------------------------ sound design

    private void buildBank() {
        bank[HORN] = horn();
        bank[COIN] = notes(new int[]{83, 88}, new float[]{0.07f, 0.22f}, 0.35f, true);
        bank[BOOST] = sweep(280f, 1300f, 0.45f);
        bank[CRASH] = crash();
        bank[CLICK] = notes(new int[]{79}, new float[]{0.035f}, 0.25f, true);
        bank[OVER] = notes(new int[]{67, 64, 60, 55}, new float[]{0.22f, 0.22f, 0.22f, 0.5f}, 0.4f, false);
        bank[START] = notes(new int[]{60, 64, 67, 72}, new float[]{0.09f, 0.09f, 0.09f, 0.3f}, 0.35f, true);
    }

    /** Train horn: three tones with harmonics. */
    private static short[] horn() {
        int len = (int) (RATE * 0.9f);
        short[] b = new short[len];
        float[] f = {233f, 277f, 349f};
        for (int i = 0; i < len; i++) {
            float t = i / (float) RATE;
            float env = Math.min(1f, t / 0.04f) * (t > 0.7f ? Math.max(0f, (0.9f - t) / 0.2f) : 1f);
            float s = 0f;
            for (float fr : f) {
                double w = 2.0 * Math.PI * fr * t;
                s += (float) (Math.sin(w) + 0.5 * Math.sin(2 * w) + 0.25 * Math.sin(3 * w));
            }
            b[i] = (short) (s * env * 0.16f * 32767f);
        }
        return b;
    }

    /** A short melody of square/triangle notes; each note has its own length in seconds. */
    private static short[] notes(int[] midiNotes, float[] secs, float vol, boolean square) {
        int total = 0;
        for (float s : secs) total += (int) (s * RATE);
        short[] b = new short[total];
        int pos = 0;
        for (int n = 0; n < midiNotes.length; n++) {
            int len = (int) (secs[n] * RATE);
            float fr = midi(midiNotes[n]);
            float ph = 0f;
            for (int i = 0; i < len; i++) {
                ph += fr / RATE;
                if (ph >= 1f) ph -= 1f;
                float w = square ? (ph < 0.5f ? 1f : -1f) : (ph < 0.5f ? ph * 4f - 1f : 3f - ph * 4f);
                float env = (float) Math.exp(-3.0 * i / len);
                b[pos + i] = (short) (w * env * vol * 32767f);
            }
            pos += len;
        }
        return b;
    }

    private static short[] sweep(float f0, float f1, float secs) {
        int len = (int) (RATE * secs);
        short[] b = new short[len];
        float ph = 0f;
        for (int i = 0; i < len; i++) {
            float k = i / (float) len;
            float fr = f0 + (f1 - f0) * k * k;
            ph += fr / RATE;
            if (ph >= 1f) ph -= 1f;
            float w = ph < 0.5f ? 1f : -1f;
            float env = (1f - k) * Math.min(1f, i / 300f);
            b[i] = (short) (w * env * 0.3f * 32767f);
        }
        return b;
    }

    /** Crash: decaying noise plus a falling thump. */
    private static short[] crash() {
        int len = (int) (RATE * 0.55f);
        short[] b = new short[len];
        int ns = 0x2468ACE;
        float lp = 0f, ph = 0f;
        for (int i = 0; i < len; i++) {
            ns ^= ns << 13;
            ns ^= ns >>> 17;
            ns ^= ns << 5;
            float noise = (ns & 0xFFFF) / 32768f - 1f;
            float k = i / (float) len;
            lp += (noise - lp) * (0.55f - 0.4f * k);
            ph += (130f - 90f * k) / RATE;
            if (ph >= 1f) ph -= 1f;
            float thump = (float) Math.sin(2.0 * Math.PI * ph);
            float env = (float) Math.exp(-5.0 * k);
            b[i] = (short) ((lp * 0.8f + thump * 0.6f) * env * 0.7f * 32767f);
        }
        return b;
    }
}
