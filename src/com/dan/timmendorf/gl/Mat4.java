package com.dan.timmendorf.gl;

/**
 * 4×4-Matrizen als float[16] in Spaltenreihenfolge, so wie OpenGL sie erwartet (aus Kīlauea über Lighthouse übernommen).
 * Nur das, was die Szene braucht: Blick, Projektion (auch mit umgekehrter Tiefe), Produkt, Inverse, Transformation.
 */
public final class Mat4 {
    private Mat4() { }

    public static float[] identity() {
        float[] m = new float[16];
        m[0] = m[5] = m[10] = m[15] = 1;
        return m;
    }

    /** r = a · b */
    public static float[] mul(float[] a, float[] b) {
        float[] r = new float[16];
        for (int c = 0; c < 4; c++)
            for (int row = 0; row < 4; row++) {
                float s = 0;
                for (int k = 0; k < 4; k++) s += a[k * 4 + row] * b[c * 4 + k];
                r[c * 4 + row] = s;
            }
        return r;
    }

    /** m · (x, y, z, 1); Ergebnis x, y, z, w. */
    public static double[] apply(float[] m, double x, double y, double z) {
        return new double[]{
                m[0] * x + m[4] * y + m[8] * z + m[12],
                m[1] * x + m[5] * y + m[9] * z + m[13],
                m[2] * x + m[6] * y + m[10] * z + m[14],
                m[3] * x + m[7] * y + m[11] * z + m[15]};
    }

    /** Blickmatrix vom Auge (ex,ey,ez) auf den Punkt (cx,cy,cz), oben ist +y. */
    public static float[] lookAt(double ex, double ey, double ez, double cx, double cy, double cz) {
        double fx = cx - ex, fy = cy - ey, fz = cz - ez;
        double fl = Math.sqrt(fx * fx + fy * fy + fz * fz);
        fx /= fl; fy /= fl; fz /= fl;
        // s = f × up(0,1,0)
        double sx = -fz, sy = 0, sz = fx;
        double sl = Math.sqrt(sx * sx + sz * sz);
        if (sl < 1e-9) { sx = 1; sz = 0; sl = 1; }
        sx /= sl; sz /= sl;
        // u = s × f
        double ux = sy * fz - sz * fy, uy = sz * fx - sx * fz, uz = sx * fy - sy * fx;
        float[] m = identity();
        m[0] = (float) sx; m[4] = (float) sy; m[8] = (float) sz;
        m[1] = (float) ux; m[5] = (float) uy; m[9] = (float) uz;
        m[2] = (float) -fx; m[6] = (float) -fy; m[10] = (float) -fz;
        m[12] = (float) -(sx * ex + sy * ey + sz * ez);
        m[13] = (float) -(ux * ex + uy * ey + uz * ez);
        m[14] = (float) (fx * ex + fy * ey + fz * ez);
        return m;
    }

    /** Blickmatrix mit eigenem Oben (Rollen des Schiffs in der Fahrt „Einlaufen“). */
    public static float[] lookAt(double ex, double ey, double ez, double cx, double cy, double cz, double upx, double upy, double upz) {
        double fx = cx - ex, fy = cy - ey, fz = cz - ez;
        double fl = Math.sqrt(fx * fx + fy * fy + fz * fz);
        fx /= fl; fy /= fl; fz /= fl;
        double sx = fy * upz - fz * upy, sy = fz * upx - fx * upz, sz = fx * upy - fy * upx;
        double sl = Math.sqrt(sx * sx + sy * sy + sz * sz);
        if (sl < 1e-9) return lookAt(ex, ey, ez, cx, cy, cz);
        sx /= sl; sy /= sl; sz /= sl;
        double ux = sy * fz - sz * fy, uy = sz * fx - sx * fz, uz = sx * fy - sy * fx;
        float[] m = identity();
        m[0] = (float) sx; m[4] = (float) sy; m[8] = (float) sz;
        m[1] = (float) ux; m[5] = (float) uy; m[9] = (float) uz;
        m[2] = (float) -fx; m[6] = (float) -fy; m[10] = (float) -fz;
        m[12] = (float) -(sx * ex + sy * ey + sz * ez);
        m[13] = (float) -(ux * ex + uy * ey + uz * ez);
        m[14] = (float) (fx * ex + fy * ey + fz * ez);
        return m;
    }

    /**
     * Perspektive. Mit reversed = true die umgekehrte Tiefe für Tiefenbereich 0..1 (glClipControl):
     * nah = 1, fern geht gegen 0, unendlich weit. Mit einem Gleitkomma-Tiefenpuffer ist die Genauigkeit
     * vom Laternenraum (Zentimeter) bis zur Kimm (16 km) gleichmäßig.
     */
    public static float[] perspective(double fovyDeg, double aspect, double near, double far, boolean reversed) {
        double f = 1.0 / Math.tan(Math.toRadians(fovyDeg) / 2);
        float[] m = new float[16];
        m[0] = (float) (f / aspect);
        m[5] = (float) f;
        m[11] = -1;
        if (reversed) {
            m[10] = 0;
            m[14] = (float) near;
        } else {
            m[10] = (float) ((far + near) / (near - far));
            m[14] = (float) (2 * far * near / (near - far));
        }
        return m;
    }

    /** Allgemeine Inverse (Cramer); für Projektion·Blick, um Sichtstrahlen zu rekonstruieren. */
    public static float[] invert(float[] m) {
        double[] inv = new double[16];
        inv[0] = m[5] * m[10] * m[15] - m[5] * m[11] * m[14] - m[9] * m[6] * m[15] + m[9] * m[7] * m[14] + m[13] * m[6] * m[11] - m[13] * m[7] * m[10];
        inv[4] = -m[4] * m[10] * m[15] + m[4] * m[11] * m[14] + m[8] * m[6] * m[15] - m[8] * m[7] * m[14] - m[12] * m[6] * m[11] + m[12] * m[7] * m[10];
        inv[8] = m[4] * m[9] * m[15] - m[4] * m[11] * m[13] - m[8] * m[5] * m[15] + m[8] * m[7] * m[13] + m[12] * m[5] * m[11] - m[12] * m[7] * m[9];
        inv[12] = -m[4] * m[9] * m[14] + m[4] * m[10] * m[13] + m[8] * m[5] * m[14] - m[8] * m[6] * m[13] - m[12] * m[5] * m[10] + m[12] * m[6] * m[9];
        inv[1] = -m[1] * m[10] * m[15] + m[1] * m[11] * m[14] + m[9] * m[2] * m[15] - m[9] * m[3] * m[14] - m[13] * m[2] * m[11] + m[13] * m[3] * m[10];
        inv[5] = m[0] * m[10] * m[15] - m[0] * m[11] * m[14] - m[8] * m[2] * m[15] + m[8] * m[3] * m[14] + m[12] * m[2] * m[11] - m[12] * m[3] * m[10];
        inv[9] = -m[0] * m[9] * m[15] + m[0] * m[11] * m[13] + m[8] * m[1] * m[15] - m[8] * m[3] * m[13] - m[12] * m[1] * m[11] + m[12] * m[3] * m[9];
        inv[13] = m[0] * m[9] * m[14] - m[0] * m[10] * m[13] - m[8] * m[1] * m[14] + m[8] * m[2] * m[13] + m[12] * m[1] * m[10] - m[12] * m[2] * m[9];
        inv[2] = m[1] * m[6] * m[15] - m[1] * m[7] * m[14] - m[5] * m[2] * m[15] + m[5] * m[3] * m[14] + m[13] * m[2] * m[7] - m[13] * m[3] * m[6];
        inv[6] = -m[0] * m[6] * m[15] + m[0] * m[7] * m[14] + m[4] * m[2] * m[15] - m[4] * m[3] * m[14] - m[12] * m[2] * m[7] + m[12] * m[3] * m[6];
        inv[10] = m[0] * m[5] * m[15] - m[0] * m[7] * m[13] - m[4] * m[1] * m[15] + m[4] * m[3] * m[13] + m[12] * m[1] * m[7] - m[12] * m[3] * m[5];
        inv[14] = -m[0] * m[5] * m[14] + m[0] * m[6] * m[13] + m[4] * m[1] * m[14] - m[4] * m[2] * m[13] - m[12] * m[1] * m[6] + m[12] * m[2] * m[5];
        inv[3] = -m[1] * m[6] * m[11] + m[1] * m[7] * m[10] + m[5] * m[2] * m[11] - m[5] * m[3] * m[10] - m[9] * m[2] * m[7] + m[9] * m[3] * m[6];
        inv[7] = m[0] * m[6] * m[11] - m[0] * m[7] * m[10] - m[4] * m[2] * m[11] + m[4] * m[3] * m[10] + m[8] * m[2] * m[7] - m[8] * m[3] * m[6];
        inv[11] = -m[0] * m[5] * m[11] + m[0] * m[7] * m[9] + m[4] * m[1] * m[11] - m[4] * m[3] * m[9] - m[8] * m[1] * m[7] + m[8] * m[3] * m[5];
        inv[15] = m[0] * m[5] * m[10] - m[0] * m[6] * m[9] - m[4] * m[1] * m[10] + m[4] * m[2] * m[9] + m[8] * m[1] * m[6] - m[8] * m[2] * m[5];
        double det = m[0] * inv[0] + m[1] * inv[4] + m[2] * inv[8] + m[3] * inv[12];
        float[] r = new float[16];
        if (Math.abs(det) < 1e-30) return identity();
        for (int i = 0; i < 16; i++) r[i] = (float) (inv[i] / det);
        return r;
    }
}
