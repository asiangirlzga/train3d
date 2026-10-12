package com.trainrush.lite;

import android.opengl.GLES20;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Tiny static meshes (unit size, centred on the origin) stored in one GPU buffer each.
 * Vertex layout: position(3 floats) + normal(3 floats). No textures anywhere.
 * Winding is fixed up automatically so back-face culling is always correct.
 */
final class Mesh {
    static final int AXIS_X = 0, AXIS_Y = 1, AXIS_Z = 2;

    int vbo;
    int count;

    private final float[] data;
    private int n;   // floats written so far

    private Mesh(int maxVerts) {
        data = new float[maxVerts * 6];
    }

    // ---------------------------------------------------------------- shapes

    static Mesh cube() {
        Mesh m = new Mesh(36);
        float h = 0.5f;
        // each face as a quad given by 4 corners
        float[][] c = {
                {-h, -h, h, h, -h, h, h, h, h, -h, h, h},        // +Z
                {h, -h, -h, -h, -h, -h, -h, h, -h, h, h, -h},    // -Z
                {h, -h, h, h, -h, -h, h, h, -h, h, h, h},        // +X
                {-h, -h, -h, -h, -h, h, -h, h, h, -h, h, -h},    // -X
                {-h, h, h, h, h, h, h, h, -h, -h, h, -h},        // +Y
                {-h, -h, -h, h, -h, -h, h, -h, h, -h, -h, h}     // -Y
        };
        for (float[] f : c) {
            m.tri(f[0], f[1], f[2], f[3], f[4], f[5], f[6], f[7], f[8], 0, 0, 0);
            m.tri(f[0], f[1], f[2], f[6], f[7], f[8], f[9], f[10], f[11], 0, 0, 0);
        }
        return m;
    }

    /** Four-sided pyramid: base on y=-0.5, apex at y=+0.5. */
    static Mesh pyramid() {
        Mesh m = new Mesh(18);
        float h = 0.5f;
        float cy = -0.1f;
        m.tri(-h, -h, -h, h, -h, -h, 0, h, 0, 0, cy, 0);
        m.tri(h, -h, -h, h, -h, h, 0, h, 0, 0, cy, 0);
        m.tri(h, -h, h, -h, -h, h, 0, h, 0, 0, cy, 0);
        m.tri(-h, -h, h, -h, -h, -h, 0, h, 0, 0, cy, 0);
        m.tri(-h, -h, -h, h, -h, -h, h, -h, h, 0, cy, 0);
        m.tri(-h, -h, -h, h, -h, h, -h, -h, h, 0, cy, 0);
        return m;
    }

    /** Eight-sided prism (a low-poly cylinder) with its length along the given axis. */
    static Mesh prism(int axis) {
        final int sides = 8;
        Mesh m = new Mesh(sides * 12);
        float[] ringA = new float[sides + 1];
        float[] ringB = new float[sides + 1];
        for (int i = 0; i <= sides; i++) {
            double a = Math.PI * 2.0 * i / sides;
            ringA[i] = (float) Math.cos(a) * 0.5f;
            ringB[i] = (float) Math.sin(a) * 0.5f;
        }
        for (int i = 0; i < sides; i++) {
            float[] p0 = pt(axis, ringA[i], ringB[i], -0.5f);
            float[] p1 = pt(axis, ringA[i + 1], ringB[i + 1], -0.5f);
            float[] p2 = pt(axis, ringA[i + 1], ringB[i + 1], 0.5f);
            float[] p3 = pt(axis, ringA[i], ringB[i], 0.5f);
            m.tri(p0[0], p0[1], p0[2], p1[0], p1[1], p1[2], p2[0], p2[1], p2[2], 0, 0, 0);
            m.tri(p0[0], p0[1], p0[2], p2[0], p2[1], p2[2], p3[0], p3[1], p3[2], 0, 0, 0);
            // caps
            float[] c0 = pt(axis, 0, 0, -0.5f);
            float[] c1 = pt(axis, 0, 0, 0.5f);
            m.tri(c0[0], c0[1], c0[2], p0[0], p0[1], p0[2], p1[0], p1[1], p1[2], 0, 0, 0);
            m.tri(c1[0], c1[1], c1[2], p3[0], p3[1], p3[2], p2[0], p2[1], p2[2], 0, 0, 0);
        }
        return m;
    }

    /** Maps (a, b, along) onto the right axes for a prism whose length runs along `axis`. */
    private static float[] pt(int axis, float a, float b, float along) {
        switch (axis) {
            case AXIS_X:
                return new float[]{along, a, b};
            case AXIS_Y:
                return new float[]{a, along, b};
            default:
                return new float[]{a, b, along};
        }
    }

    // --------------------------------------------------------------- building

    /** Adds one triangle; (cx,cy,cz) is a point inside the solid, used to point the normal outwards. */
    private void tri(float ax, float ay, float az, float bx, float by, float bz,
                     float cx, float cy, float cz, float ox, float oy, float oz) {
        float ux = bx - ax, uy = by - ay, uz = bz - az;
        float vx = cx - ax, vy = cy - ay, vz = cz - az;
        float nx = uy * vz - uz * vy;
        float ny = uz * vx - ux * vz;
        float nz = ux * vy - uy * vx;
        float mx = (ax + bx + cx) / 3f - ox;
        float my = (ay + by + cy) / 3f - oy;
        float mz = (az + bz + cz) / 3f - oz;
        if (nx * mx + ny * my + nz * mz < 0f) {
            // flip winding so the face looks outwards
            float tx = bx, ty = by, tz = bz;
            bx = cx; by = cy; bz = cz;
            cx = tx; cy = ty; cz = tz;
            nx = -nx; ny = -ny; nz = -nz;
        }
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len > 0f) {
            nx /= len; ny /= len; nz /= len;
        }
        put(ax, ay, az, nx, ny, nz);
        put(bx, by, bz, nx, ny, nz);
        put(cx, cy, cz, nx, ny, nz);
    }

    private void put(float x, float y, float z, float nx, float ny, float nz) {
        data[n++] = x; data[n++] = y; data[n++] = z;
        data[n++] = nx; data[n++] = ny; data[n++] = nz;
        count++;
    }

    /** Uploads the vertices to the GPU. Call on the GL thread. */
    void upload() {
        int[] ids = new int[1];
        GLES20.glGenBuffers(1, ids, 0);
        vbo = ids[0];
        FloatBuffer fb = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(data, 0, n).position(0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, n * 4, fb, GLES20.GL_STATIC_DRAW);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
    }
}
