package com.trainrush.lite;

import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.SystemClock;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Draws the whole 3D world with one tiny shader and five unit meshes (boxes, pyramid, prisms).
 * No textures, flat colours + fog, so it needs almost no video memory.
 */
final class GameRenderer implements GLSurfaceView.Renderer {
    private static final String TAG = "TrainRush";
    private static final float SLEEPER_GAP = 2.2f;
    private static final int SLEEPERS = 50;

    private static final String VS =
            "uniform mat4 uVP;\n" +
            "uniform vec4 uB[3];\n" +               // [0]=pos.xyz+emit  [1]=scale.xyz  [2]=colour.rgb
            "uniform vec3 uLight;\n" +
            "uniform float uAmb;\n" +
            "attribute vec3 aPos;\n" +
            "attribute vec3 aNrm;\n" +
            "varying vec3 vCol;\n" +
            "varying float vDepth;\n" +
            "void main() {\n" +
            "  vec3 w = aPos * uB[1].xyz + uB[0].xyz;\n" +
            "  gl_Position = uVP * vec4(w, 1.0);\n" +
            "  float l = uAmb + (1.0 - uAmb) * max(dot(aNrm, uLight), 0.0);\n" +
            "  vCol = mix(uB[2].rgb * l, uB[2].rgb, uB[0].w);\n" +
            "  vDepth = gl_Position.w;\n" +
            "}\n";

    private static final String FS =
            "precision mediump float;\n" +
            "uniform vec3 uFogCol;\n" +
            "uniform vec2 uFog;\n" +
            "varying vec3 vCol;\n" +
            "varying float vDepth;\n" +
            "void main() {\n" +
            "  float f = clamp((vDepth - uFog.x) / (uFog.y - uFog.x), 0.0, 1.0);\n" +
            "  gl_FragColor = vec4(mix(vCol, uFogCol, f), 1.0);\n" +
            "}\n";

    private static final String SKY_VS =
            "attribute vec2 aP;\n" +
            "varying float vY;\n" +
            "void main() {\n" +
            "  vY = aP.y;\n" +
            "  gl_Position = vec4(aP, 1.0, 1.0);\n" +
            "}\n";

    private static final String SKY_FS =
            "precision mediump float;\n" +
            "uniform vec3 uTop;\n" +
            "uniform vec3 uBot;\n" +
            "varying float vY;\n" +
            "void main() {\n" +
            "  float t = clamp((vY - 0.15) / 0.85, 0.0, 1.0);\n" +
            "  gl_FragColor = vec4(mix(uBot, uTop, t), 1.0);\n" +
            "}\n";

    private final Game g;

    private int prog, aPos, aNrm, uVP, uB, uLight, uAmb, uFogCol, uFog;
    private int skyProg, skyP, skyTop, skyBot, skyVbo;
    private Mesh cube, pyr, prX, prY, prZ, bound;

    private final float[] proj = new float[16];
    private final float[] view = new float[16];
    private final float[] vp = new float[16];
    private final float[] u = new float[12];
    private float aspect = 16f / 9f;
    private long lastNs;

    GameRenderer(Game game) {
        g = game;
    }

    // ------------------------------------------------------------------ GL setup

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        prog = link(VS, FS);
        aPos = GLES20.glGetAttribLocation(prog, "aPos");
        aNrm = GLES20.glGetAttribLocation(prog, "aNrm");
        uVP = GLES20.glGetUniformLocation(prog, "uVP");
        uB = GLES20.glGetUniformLocation(prog, "uB");
        uLight = GLES20.glGetUniformLocation(prog, "uLight");
        uAmb = GLES20.glGetUniformLocation(prog, "uAmb");
        uFogCol = GLES20.glGetUniformLocation(prog, "uFogCol");
        uFog = GLES20.glGetUniformLocation(prog, "uFog");

        skyProg = link(SKY_VS, SKY_FS);
        skyP = GLES20.glGetAttribLocation(skyProg, "aP");
        skyTop = GLES20.glGetUniformLocation(skyProg, "uTop");
        skyBot = GLES20.glGetUniformLocation(skyProg, "uBot");

        float[] quad = {-1, -1, 1, -1, -1, 1, 1, 1};
        FloatBuffer fb = ByteBuffer.allocateDirect(quad.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(quad).position(0);
        int[] ids = new int[1];
        GLES20.glGenBuffers(1, ids, 0);
        skyVbo = ids[0];
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, skyVbo);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, quad.length * 4, fb, GLES20.GL_STATIC_DRAW);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);

        cube = Mesh.cube();
        pyr = Mesh.pyramid();
        prX = Mesh.prism(Mesh.AXIS_X);
        prY = Mesh.prism(Mesh.AXIS_Y);
        prZ = Mesh.prism(Mesh.AXIS_Z);
        cube.upload();
        pyr.upload();
        prX.upload();
        prY.upload();
        prZ.upload();
        bound = null;

        GLES20.glEnable(GLES20.GL_CULL_FACE);
        GLES20.glCullFace(GLES20.GL_BACK);
        GLES20.glDisable(GLES20.GL_DITHER);
        lastNs = System.nanoTime();
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int w, int h) {
        GLES20.glViewport(0, 0, w, h);
        aspect = w / (float) Math.max(1, h);
        Matrix.perspectiveM(proj, 0, 58f, aspect, 1f, 170f);
    }

    private static int link(String vsSrc, String fsSrc) {
        int vs = compile(GLES20.GL_VERTEX_SHADER, vsSrc);
        int fs = compile(GLES20.GL_FRAGMENT_SHADER, fsSrc);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, vs);
        GLES20.glAttachShader(p, fs);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) Log.e(TAG, "link failed: " + GLES20.glGetProgramInfoLog(p));
        return p;
    }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) Log.e(TAG, "shader failed: " + GLES20.glGetShaderInfoLog(s));
        return s;
    }

    // ------------------------------------------------------------------ frame

    @Override
    public void onDrawFrame(GL10 unused) {
        long now = System.nanoTime();
        float dt = (now - lastNs) / 1e9f;
        lastNs = now;
        if (dt > 0.05f) dt = 0.05f;
        if (dt < 0f) dt = 0f;

        g.update(dt);
        draw(g.theme);

        // 30 fps cap in the lowest quality level: cooler, quieter TV box, less battery on phones
        if (g.quality == 0) {
            long spent = System.nanoTime() - now;
            long wait = 33_000_000L - spent;
            if (wait > 1_000_000L) SystemClock.sleep(wait / 1_000_000L);
        }
    }

    private void draw(Theme t) {
        // ---- sky
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        GLES20.glUseProgram(skyProg);
        GLES20.glUniform3fv(skyTop, 1, t.skyTop, 0);
        GLES20.glUniform3fv(skyBot, 1, t.skyBottom, 0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, skyVbo);
        GLES20.glEnableVertexAttribArray(skyP);
        GLES20.glVertexAttribPointer(skyP, 2, GLES20.GL_FLOAT, false, 8, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(skyP);

        // ---- world
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glUseProgram(prog);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glEnableVertexAttribArray(aNrm);
        bound = null;

        float shake = g.shake > 0f ? g.shake : 0f;
        float sx = (float) Math.sin(g.time * 70f) * shake * 0.35f;
        float sy = (float) Math.cos(g.time * 83f) * shake * 0.25f;
        float ex = g.px * 0.55f + sx, ey = 5.0f + sy, ez = 12.5f;
        Matrix.setLookAtM(view, 0, ex, ey, ez, g.px * 0.3f, 1.2f, -25f, 0f, 1f, 0f);
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0);
        GLES20.glUniformMatrix4fv(uVP, 1, false, vp, 0);
        GLES20.glUniform3f(uLight, 0.42f, 0.84f, 0.34f);
        GLES20.glUniform1f(uAmb, t.ambient);
        GLES20.glUniform3fv(uFogCol, 1, t.skyBottom, 0);
        GLES20.glUniform2f(uFog, t.fogStart, t.fogEnd);

        drawGround(t);
        drawScenery(t);
        drawEntities(t);
        drawPlayer(t);
        drawSmoke(t);
    }

    private void bind(Mesh m) {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, m.vbo);
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 24, 0);
        GLES20.glVertexAttribPointer(aNrm, 3, GLES20.GL_FLOAT, false, 24, 12);
        bound = m;
    }

    private void box(Mesh m, float x, float y, float z, float sx, float sy, float sz, float[] c, float emit) {
        if (m != bound) bind(m);
        u[0] = x; u[1] = y; u[2] = z; u[3] = emit;
        u[4] = sx; u[5] = sy; u[6] = sz; u[7] = 0f;
        u[8] = c[0]; u[9] = c[1]; u[10] = c[2]; u[11] = 0f;
        GLES20.glUniform4fv(uB, 3, u, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, m.count);
    }

    // ------------------------------------------------------------------ ground & track

    private void drawGround(Theme t) {
        box(cube, 0f, -0.6f, -50f, 260f, 1f, 260f, t.ground, 0f);              // grass, top at y=-0.1
        box(cube, 0f, 0f, -50f, 3f * Game.LANE_W + 1.6f, 0.32f, 260f, t.ballast, 0f);

        float off = g.scroll % SLEEPER_GAP;
        float w = 3f * Game.LANE_W + 0.9f;
        for (int i = 0; i < SLEEPERS; i++) {
            float z = 16f - i * SLEEPER_GAP + off - SLEEPER_GAP;
            box(cube, 0f, 0.20f, z, w, 0.08f, 0.45f, t.sleeper, 0f);
        }
        for (int l = -1; l <= 1; l++) {
            float cx = l * Game.LANE_W;
            box(cube, cx - 0.55f, 0.30f, -50f, 0.12f, 0.12f, 260f, t.rail, 0f);
            box(cube, cx + 0.55f, 0.30f, -50f, 0.12f, 0.12f, 260f, t.rail, 0f);
        }
    }

    // ------------------------------------------------------------------ scenery

    private void drawScenery(Theme t) {
        for (int i = 0; i < Game.N_SC; i++) {
            float x = g.scX[i], z = g.scZ[i], s = g.scS[i];
            int v = g.scV[i];
            switch (t.scenery) {
                case Theme.SC_CACTUS:
                    if (v == 0) {   // flat-topped rock mesa
                        box(cube, x, 2.2f * s, z, 7f * s, 4.4f * s, 5f * s, t.sceneryB, 0f);
                        box(cube, x, 4.6f * s, z, 7.2f * s, 0.4f * s, 5.2f * s, t.ground, 0f);
                    } else {
                        box(cube, x, 1.6f * s, z, 0.6f * s, 3.2f * s, 0.6f * s, t.sceneryA, 0f);
                        box(cube, x + 0.65f * s, 1.9f * s, z, 0.9f * s, 0.35f * s, 0.35f * s, t.sceneryA, 0f);
                        box(cube, x + 1.05f * s, 2.5f * s, z, 0.35f * s, 1.1f * s, 0.35f * s, t.sceneryA, 0f);
                    }
                    break;
                case Theme.SC_CITY: {
                    float bw = (2.4f + (v & 3) * 0.9f) * s;
                    float bh = (5f + v * 2.2f) * s;
                    float[] c = (v & 1) == 0 ? t.sceneryA : t.sceneryB;
                    box(cube, x, bh * 0.5f, z, bw, bh, bw, c, 0f);
                    box(cube, x, bh + 0.15f, z, bw * 1.04f, 0.3f, bw * 1.04f, t.accent, 0.8f);
                    break;
                }
                case Theme.SC_ROUND:
                    box(cube, x, 0.9f * s, z, 0.5f * s, 1.8f * s, 0.5f * s, t.sceneryB, 0f);
                    box(prY, x, 2.8f * s, z, 2.6f * s, 2.2f * s, 2.6f * s, t.sceneryA, 0f);
                    break;
                default:    // pine
                    box(cube, x, 0.9f * s, z, 0.5f * s, 1.8f * s, 0.5f * s, t.sceneryB, 0f);
                    box(pyr, x, 2.7f * s, z, 2.6f * s, 2.4f * s, 2.6f * s, t.sceneryA, 0f);
                    box(pyr, x, 4.2f * s, z, 1.9f * s, 2.0f * s, 1.9f * s, t.sceneryA, 0f);
                    break;
            }
        }
    }

    // ------------------------------------------------------------------ obstacles & pickups

    private void drawEntities(Theme t) {
        float tm = g.time;
        for (int i = 0; i < Game.MAX_E; i++) {
            if (!g.eOn[i]) continue;
            float x = g.eX[i], z = g.eZ[i];
            switch (g.eType[i]) {
                case Game.T_COIN: {
                    float spin = Math.abs((float) Math.cos(tm * 5f + z * 0.3f)) * 0.8f + 0.08f;
                    float bob = 1.3f + 0.15f * (float) Math.sin(tm * 3f + z);
                    box(prZ, x, bob, z, spin, 0.8f, 0.14f, t.coin, 0.6f);
                    break;
                }
                case Game.T_BOOST: {
                    float bob = 1.2f + 0.2f * (float) Math.sin(tm * 3f);
                    box(prY, x, bob, z, 0.9f, 1.1f, 0.9f, BOOST_COL, 0.8f);
                    box(cube, x, bob + 0.75f, z, 0.45f, 0.25f, 0.45f, WHITE, 0.8f);
                    break;
                }
                case Game.T_BARRIER:
                    box(cube, x - 1.0f, 0.8f, z, 0.2f, 1.6f, 0.2f, WHITE, 0f);
                    box(cube, x + 1.0f, 0.8f, z, 0.2f, 1.6f, 0.2f, WHITE, 0f);
                    box(cube, x, 1.25f, z, 2.3f, 0.4f, 0.2f, t.barrier, 0f);
                    box(cube, x, 0.65f, z, 2.3f, 0.4f, 0.2f, WHITE, 0f);
                    break;
                case Game.T_ROCK:
                    box(pyr, x, 0.8f, z, 2.2f, 1.7f, 1.9f, t.rock, 0f);
                    box(cube, x + 0.5f, 0.35f, z + 0.4f, 0.9f, 0.7f, 0.8f, t.rock, 0f);
                    break;
                case Game.T_WAGON:
                    box(cube, x, 0.55f, z, 1.6f, 0.4f, 6.0f, t.enemy, 0f);
                    box(cube, x, 1.95f, z, 2.2f, 2.4f, 6.6f, t.car, 0f);
                    box(cube, x, 2.0f, z, 2.24f, 0.4f, 6.4f, t.accent, 0f);
                    box(cube, x, 3.25f, z, 2.3f, 0.18f, 6.8f, t.enemy, 0f);
                    break;
                default:
                    drawLoco(t, x, z, 1f, t.enemy, t.accent, false);
                    break;
            }
        }
    }

    private static final float[] WHITE = {0.95f, 0.95f, 0.95f};
    private static final float[] BOOST_COL = {0.2f, 0.85f, 1f};
    private static final float[] DARK = {0.14f, 0.14f, 0.16f};
    private static final float[] SMOKE = {0.92f, 0.92f, 0.92f};
    private static final float[] LAMP = {1f, 0.95f, 0.6f};

    /**
     * Steam locomotive. fz = +1 when its front points toward +Z (oncoming trains), -1 for the player.
     */
    private void drawLoco(Theme t, float x, float zc, float fz, float[] body, float[] accent, boolean detail) {
        box(cube, x, 0.65f, zc, 1.6f, 0.35f, 4.8f, DARK, 0f);                         // frame
        box(prZ, x, 1.45f, zc + fz * 0.8f, 1.55f, 1.55f, 2.6f, body, 0f);               // boiler
        box(prZ, x, 1.45f, zc + fz * 2.15f, 1.4f, 1.4f, 0.3f, DARK, 0f);               // smoke box
        box(cube, x, 2.45f, zc + fz * 1.9f, 0.34f, 0.7f, 0.34f, DARK, 0f);             // chimney
        box(prY, x, 2.35f, zc + fz * 0.5f, 0.55f, 0.5f, 0.55f, accent, 0f);            // dome
        box(cube, x, 1.8f, zc - fz * 1.35f, 1.8f, 2.3f, 1.5f, body, 0f);               // cab
        box(cube, x, 3.0f, zc - fz * 1.35f, 2.0f, 0.12f, 1.7f, DARK, 0f);              // cab roof
        box(cube, x, 0.55f, zc + fz * 2.5f, 1.7f, 0.5f, 0.35f, accent, 0f);            // buffer beam
        box(cube, x, 1.65f, zc + fz * 2.35f, 0.4f, 0.4f, 0.15f, LAMP, 1f);             // headlight
        float ang = g.scroll / 0.45f;
        for (int k = 0; k < (detail ? 3 : 2); k++) {
            float wz = zc + fz * (1.5f - k * 1.4f);
            box(prX, x - 0.9f, 0.5f, wz, 0.22f, 1.0f, 1.0f, DARK, 0f);
            box(prX, x + 0.9f, 0.5f, wz, 0.22f, 1.0f, 1.0f, DARK, 0f);
            if (detail) {
                float py = 0.5f + (float) Math.cos(ang) * 0.3f;
                float pz = wz + (float) Math.sin(ang) * 0.3f;
                box(cube, x - 1.05f, py, pz, 0.1f, 0.14f, 0.14f, accent, 0f);
                box(cube, x + 1.05f, py, pz, 0.1f, 0.14f, 0.14f, accent, 0f);
            }
        }
    }

    // ------------------------------------------------------------------ player train

    private void drawPlayer(Theme t) {
        if (g.invuln > 0f && ((int) (g.time * 14f) & 1) == 0) return;   // blink after a hit
        float x = g.px;
        drawLoco(t, x, -2.0f, -1f, t.train, t.accent, true);
        // tender
        box(cube, x, 0.55f, 3.4f, 1.5f, 0.3f, 5.4f, DARK, 0f);
        box(cube, x, 1.35f, 1.7f, 1.7f, 1.5f, 2.2f, DARK, 0f);
        box(cube, x, 2.2f, 1.7f, 1.4f, 0.35f, 1.9f, t.accent, 0f);
        // passenger car
        box(cube, x, 2.0f, 4.6f, 1.9f, 2.4f, 2.8f, t.car, 0f);
        box(cube, x, 2.35f, 4.6f, 1.96f, 0.55f, 2.6f, WHITE, 0f);
        box(cube, x, 3.3f, 4.6f, 2.1f, 0.14f, 3.0f, DARK, 0f);
    }

    // ------------------------------------------------------------------ smoke

    private void drawSmoke(Theme t) {
        float[] fogc = t.skyBottom;
        for (int i = 0; i < Game.N_PUFF; i++) {
            float age = g.pAge[i];
            if (age >= 1.2f) continue;
            float k = age / 1.2f;
            float size = 0.35f + 1.1f * k;
            float c = 1f - 0.55f * k;
            SMOKE_TMP[0] = (SMOKE[0] * c) + fogc[0] * (1f - c) * 0.4f;
            SMOKE_TMP[1] = (SMOKE[1] * c) + fogc[1] * (1f - c) * 0.4f;
            SMOKE_TMP[2] = (SMOKE[2] * c) + fogc[2] * (1f - c) * 0.4f;
            box(cube, g.pX[i], g.pY[i], g.pZ[i], size, size, size, SMOKE_TMP, 0.3f);
        }
    }

    private static final float[] SMOKE_TMP = new float[3];
}
