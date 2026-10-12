package com.trainrush.lite;

import android.content.SharedPreferences;

import java.util.Random;

/**
 * Game rules and state. update() runs on the GL thread; input arrives from the UI thread
 * through a small queue. Everything is pre-allocated, so nothing is created while playing
 * (no garbage collector pauses on a slow TV).
 */
final class Game {
    // states
    static final int MENU = 0, PLAYING = 1, PAUSED = 2, OVER = 3;
    // input actions
    static final int A_LEFT = 1, A_RIGHT = 2, A_UP = 3, A_DOWN = 4, A_OK = 5, A_BACK = 6, A_PAUSE = 7;
    // entity types
    static final int T_COIN = 0, T_BOOST = 1, T_BARRIER = 2, T_ROCK = 3, T_WAGON = 4, T_TRAIN = 5;

    static final float LANE_W = 2.6f;
    static final float PLAYER_FRONT_Z = -4.6f;   // nose of our locomotive
    static final float PLAYER_BACK_Z = 6.2f;
    static final float SPAWN_Z = -105f;
    static final float DESPAWN_Z = 16f;

    static final int MAX_E = 56;
    static final int N_SC = 36;
    static final int N_PUFF = 10;

    interface Host {
        void hudChanged();

        void qualityChanged();

        void quit();
    }

    // --------------------------------------------------------------- shared state (read by renderer / HUD)
    volatile int state = MENU;
    volatile int score, coins, lives = 3, best, kmh;
    volatile float boostLeft;
    volatile int quality = 1;          // 0 = low (540p, 30 fps), 1 = medium (720p), 2 = native
    volatile boolean music = true;
    volatile Theme theme;
    volatile int themeIndex;
    volatile int menuRow;              // 0 theme, 1 quality, 2 music
    volatile boolean holdUp, holdDown;

    float px;                          // player x
    float scroll;                      // total distance scrolled (animates sleepers and wheels)
    float time;
    float invuln, shake;

    // entities
    final int[] eType = new int[MAX_E];
    final float[] eX = new float[MAX_E];
    final float[] eZ = new float[MAX_E];
    final boolean[] eOn = new boolean[MAX_E];

    // scenery
    final float[] scX = new float[N_SC];
    final float[] scZ = new float[N_SC];
    final float[] scS = new float[N_SC];
    final int[] scV = new int[N_SC];

    // smoke
    final float[] pX = new float[N_PUFF], pY = new float[N_PUFF], pZ = new float[N_PUFF], pAge = new float[N_PUFF];
    private static final float PUFF_LIFE = 1.2f;
    private float puffTimer;
    private int puffNext;

    private final ThemeManager themes;
    private final SoundEngine sound;
    private final SharedPreferences prefs;
    private final Host host;
    private final Random rnd = new Random();

    private int lane = 1;              // 0..2
    private float dist;
    private float speed;
    private float slowT;
    private float spawnLeft;
    private int lastFree = 1;
    private float hudTimer;
    private volatile boolean themesDirty;

    // input queue
    private final int[] inQ = new int[16];
    private int inHead, inTail;

    Game(ThemeManager themes, SoundEngine sound, SharedPreferences prefs, Host host) {
        this.themes = themes;
        this.sound = sound;
        this.prefs = prefs;
        this.host = host;
        best = prefs.getInt("best", 0);
        quality = prefs.getInt("quality", 1);
        music = prefs.getBoolean("music", true);
        themeIndex = themes.indexOf(prefs.getString("theme", "sunny"));
        theme = themes.get(themeIndex);
        for (int i = 0; i < N_SC; i++) {
            respawnScenery(i, -150f + 160f * i / N_SC);
        }
        for (int i = 0; i < N_PUFF; i++) pAge[i] = PUFF_LIFE;
        speed = 6f;
        sound.setMusic(music);
        sound.setTune(theme.bpm, theme.root);
    }

    // ------------------------------------------------------------------ input

    synchronized void push(int action) {
        int next = (inTail + 1) & 15;
        if (next != inHead) {
            inQ[inTail] = action;
            inTail = next;
        }
    }

    private synchronized int pop() {
        if (inHead == inTail) return 0;
        int a = inQ[inHead];
        inHead = (inHead + 1) & 15;
        return a;
    }

    /** Called from the theme downloader thread when new themes arrived. */
    void onThemesChanged() {
        themesDirty = true;
    }

    private void handle(int a) {
        switch (state) {
            case MENU:
                if (a == A_UP) {
                    menuRow = (menuRow + 2) % 3;
                    sound.play(SoundEngine.CLICK);
                } else if (a == A_DOWN) {
                    menuRow = (menuRow + 1) % 3;
                    sound.play(SoundEngine.CLICK);
                } else if (a == A_LEFT) {
                    menuChange(-1);
                } else if (a == A_RIGHT) {
                    menuChange(1);
                } else if (a == A_OK) {
                    startGame();
                } else if (a == A_BACK) {
                    host.quit();
                }
                break;
            case PLAYING:
                if (a == A_LEFT && lane > 0) {
                    lane--;
                } else if (a == A_RIGHT && lane < 2) {
                    lane++;
                } else if (a == A_OK) {
                    sound.play(SoundEngine.HORN);
                } else if (a == A_BACK || a == A_PAUSE) {
                    state = PAUSED;
                    sound.setEngine(false, 0f);
                }
                break;
            case PAUSED:
                if (a == A_OK || a == A_PAUSE) {
                    state = PLAYING;
                } else if (a == A_BACK) {
                    state = MENU;
                    speed = 6f;
                }
                break;
            case OVER:
                if (a == A_OK) {
                    startGame();
                } else if (a == A_BACK) {
                    state = MENU;
                    speed = 6f;
                }
                break;
            default:
                break;
        }
        host.hudChanged();
    }

    private void menuChange(int dir) {
        sound.play(SoundEngine.CLICK);
        SharedPreferences.Editor ed = prefs.edit();
        if (menuRow == 0) {
            int n = themes.count();
            themeIndex = (themeIndex + dir + n) % n;
            applyTheme();
            ed.putString("theme", theme.id);
        } else if (menuRow == 1) {
            quality = (quality + dir + 3) % 3;
            ed.putInt("quality", quality);
            host.qualityChanged();
        } else {
            music = !music;
            sound.setMusic(music);
            ed.putBoolean("music", music);
        }
        ed.apply();
    }

    private void applyTheme() {
        theme = themes.get(themeIndex);
        sound.setTune(theme.bpm, theme.root);
    }

    // --------------------------------------------------------------- lifecycle

    private void startGame() {
        dist = 0f;
        score = 0;
        coins = 0;
        lives = 3;
        kmh = 0;
        boostLeft = 0f;
        invuln = 0f;
        slowT = 0f;
        shake = 0f;
        lane = 1;
        px = 0f;
        lastFree = 1;
        speed = 12f;
        spawnLeft = 28f;
        for (int i = 0; i < MAX_E; i++) eOn[i] = false;
        state = PLAYING;
        sound.play(SoundEngine.START);
    }

    private void gameOver() {
        state = OVER;
        if (score > best) {
            best = score;
            prefs.edit().putInt("best", best).apply();
        }
        sound.setEngine(false, 0f);
        sound.play(SoundEngine.OVER);
        host.hudChanged();
    }

    // ------------------------------------------------------------------ update

    void update(float dt) {
        time += dt;
        int a;
        while ((a = pop()) != 0) handle(a);

        if (themesDirty) {
            themesDirty = false;
            themeIndex = themes.indexOf(theme.id);
            applyTheme();
        }

        int st = state;
        if (st == PAUSED) return;

        if (st == PLAYING) {
            updatePlaying(dt);
        } else {
            // menu and game-over screens: gentle scrolling, no obstacles
            float target = st == MENU ? 6f : 3f;
            speed += (target - speed) * Math.min(1f, dt * 2f);
            scroll += speed * dt;
            px += (0f - px) * Math.min(1f, dt * 6f);
            moveScenery(speed * dt);
            sound.setEngine(false, 0f);
        }
        updateSmoke(dt, speed * dt);

        hudTimer += dt;
        if (hudTimer >= 0.1f) {
            hudTimer = 0f;
            host.hudChanged();
        }
    }

    private static float baseSpeed(float dist) {
        return 14f + 24f * (1f - (float) Math.exp(-dist / 900f));
    }

    private void updatePlaying(float dt) {
        float mult = 1f;
        if (holdUp) mult *= 1.25f;
        if (holdDown) mult *= 0.7f;
        if (boostLeft > 0f) {
            mult *= 1.5f;
            boostLeft -= dt;
            if (boostLeft < 0f) boostLeft = 0f;
        }
        if (slowT > 0f) {
            mult *= 1f - 0.5f * Math.min(1f, slowT / 0.8f);
            slowT -= dt;
        }
        float want = baseSpeed(dist) * mult;
        speed += (want - speed) * Math.min(1f, dt * 3f);

        float ds = speed * dt;
        dist += ds;
        scroll += ds;
        score = (int) (dist * 0.5f) + coins * 10;
        kmh = (int) (speed * 4.5f);

        if (invuln > 0f) invuln -= dt;
        if (shake > 0f) shake -= dt;

        float tx = (lane - 1) * LANE_W;
        px += (tx - px) * Math.min(1f, dt * 12f);

        moveScenery(ds);

        spawnLeft -= ds;
        if (spawnLeft <= 0f) {
            spawnPattern();
            spawnLeft = 14f + speed * 0.65f + rnd.nextFloat() * 8f;
        }

        for (int i = 0; i < MAX_E; i++) {
            if (!eOn[i]) continue;
            float vz = ds;
            if (eType[i] == T_TRAIN) vz += 9f * dt;    // oncoming trains are faster
            eZ[i] += vz;
            if (eZ[i] > DESPAWN_Z) {
                eOn[i] = false;
                continue;
            }
            checkHit(i);
            if (state != PLAYING) break;
        }

        sound.setEngine(true, speed / 42f);
    }

    private static float halfLen(int type) {
        switch (type) {
            case T_BARRIER:
                return 0.4f;
            case T_ROCK:
                return 0.9f;
            case T_WAGON:
                return 3.4f;
            case T_TRAIN:
                return 2.7f;
            default:
                return 0.4f;
        }
    }

    private void checkHit(int i) {
        float dx = Math.abs(eX[i] - px);
        int t = eType[i];
        if (t == T_COIN || t == T_BOOST) {
            if (dx < 1.3f && eZ[i] > PLAYER_FRONT_Z - 0.5f && eZ[i] < 1.5f) {
                eOn[i] = false;
                if (t == T_COIN) {
                    coins++;
                    sound.play(SoundEngine.COIN);
                } else {
                    boostLeft = 4.5f;
                    sound.play(SoundEngine.BOOST);
                }
            }
            return;
        }
        if (invuln > 0f) return;                 // ghost mode right after a hit
        if (dx > 1.15f) return;
        float h = halfLen(t);
        if (eZ[i] + h < PLAYER_FRONT_Z || eZ[i] - h > PLAYER_BACK_Z) return;

        eOn[i] = false;
        sound.play(SoundEngine.CRASH);
        if (boostLeft > 0f) {
            coins += 1;                          // plough through while boosting
            return;
        }
        lives--;
        invuln = 1.8f;
        shake = 0.5f;
        slowT = 1.5f;
        if (lives <= 0) {
            lives = 0;
            gameOver();
        }
    }

    // ------------------------------------------------------------------ spawning

    private void spawnPattern() {
        float diff = Math.min(1f, dist / 1500f);

        // the free lane may move at most one lane from the previous one (always reachable)
        int free = lastFree + rnd.nextInt(3) - 1;
        if (free < 0) free = 0;
        if (free > 2) free = 2;
        lastFree = free;

        for (int l = 0; l < 3; l++) {
            if (l == free) continue;
            if (rnd.nextFloat() < 0.5f + 0.35f * diff) {
                float q = rnd.nextFloat();
                int type;
                if (q < 0.30f) type = T_BARRIER;
                else if (q < 0.55f) type = T_ROCK;
                else if (q < 0.80f || diff < 0.1f) type = T_WAGON;
                else type = T_TRAIN;
                add(type, (l - 1) * LANE_W, SPAWN_Z);
            }
        }
        float x = (free - 1) * LANE_W;
        if (rnd.nextFloat() < 0.75f) {
            for (int k = 0; k < 5; k++) add(T_COIN, x, SPAWN_Z - 3f - k * 2.6f);
        }
        if (rnd.nextFloat() < 0.07f) add(T_BOOST, x, SPAWN_Z - 18f);
    }

    private void add(int type, float x, float z) {
        for (int i = 0; i < MAX_E; i++) {
            if (!eOn[i]) {
                eOn[i] = true;
                eType[i] = type;
                eX[i] = x;
                eZ[i] = z;
                return;
            }
        }
    }

    // ------------------------------------------------------------------ scenery & smoke

    private void moveScenery(float ds) {
        for (int i = 0; i < N_SC; i++) {
            scZ[i] += ds;
            if (scZ[i] > 20f) respawnScenery(i, -150f - rnd.nextFloat() * 12f);
        }
    }

    private void respawnScenery(int i, float z) {
        float side = (i & 1) == 0 ? -1f : 1f;
        scX[i] = side * (9f + rnd.nextFloat() * 30f);
        scZ[i] = z;
        scS[i] = 0.8f + rnd.nextFloat() * 0.7f;
        scV[i] = rnd.nextInt(8);
    }

    private void updateSmoke(float dt, float ds) {
        for (int i = 0; i < N_PUFF; i++) {
            if (pAge[i] < PUFF_LIFE) {
                pAge[i] += dt;
                pY[i] += 1.7f * dt;
                pZ[i] += ds;
                pX[i] += 0.25f * dt;
            }
        }
        puffTimer -= dt;
        if (puffTimer <= 0f) {
            puffTimer = state == PLAYING ? 0.11f : 0.2f;
            int i = puffNext;
            puffNext = (puffNext + 1) % N_PUFF;
            pAge[i] = 0f;
            pX[i] = px;
            pY[i] = 3.1f;
            pZ[i] = -3.7f;
        }
    }
}
