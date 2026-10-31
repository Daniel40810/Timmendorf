package com.dan.timmendorf.scene;

import java.util.Arrays;

/**
 * Form einer Ostseefähre (seit Phase 8a) als Dreiecke im Format von {@code com.dan.sail.BoatShape} (je Ecke Lage,
 * Normale, Farbe linear): Rumpf mit spitzem Bug und Spiegelheck, unten in der Farbe der Reederei, darüber weiß;
 * Aufbauten mit Fensterbändern, Brücke, Schornstein [G nach Ro-Pax-Fähren von rund 200 m]. Für {@code gl.BooteGl}.
 */
public final class FaehrenForm {
    private float[] buf = new float[9 * 2048];
    private int n;
    private double cx, cy, cz, sh, ch;
    static final float[] WHITE = {0.80f, 0.80f, 0.78f}, WINDOW = {0.04f, 0.05f, 0.06f}, DECK = {0.30f, 0.32f, 0.30f}, BLACK = {0.02f, 0.02f, 0.02f};

    /** Dreiecke der Fähre bei Wasserhöhe y0. */
    public float[] build(Flotte.Faehre f, double y0) {
        n = 0;
        double h = Math.toRadians(f.headingDeg);
        sh = Math.sin(h);
        ch = Math.cos(h);
        cx = f.x;
        cy = y0;
        cz = f.z;
        double L = f.length, B = f.beam, T = f.draft, fb = 10;
        // Grundriss: Spiegelheck, gerade Seiten, ab 25 % vor der Mitte spitz zum Bug
        int m = 12;
        double[][] fp = new double[2 * m + 2][];
        for (int i = 0; i <= m; i++) {
            double s = -L / 2 + L * i / m, w = s < 0.25 * L ? B / 2 : B / 2 * Math.sqrt(Math.max(0, 1 - Math.pow((s - 0.25 * L) / (0.25 * L), 2)));
            fp[i] = new double[]{s, w};
            fp[2 * m + 1 - i] = new double[]{s, -w};
        }
        prism(fp, -T, 3, f.farbe);
        prism(fp, 3, fb, WHITE);
        cap(fp, fb, DECK);
        // Aufbauten
        box(-0.42 * L, 0.30 * L, -0.45 * B, 0.45 * B, fb, fb + 16, WHITE);
        box(-0.42 * L - 0.1, 0.30 * L + 0.1, -0.45 * B - 0.1, 0.45 * B + 0.1, fb + 4, fb + 5.4, WINDOW);
        box(-0.42 * L - 0.1, 0.30 * L + 0.1, -0.45 * B - 0.1, 0.45 * B + 0.1, fb + 9, fb + 10.4, WINDOW);
        box(0.22 * L, 0.30 * L, -0.5 * B, 0.5 * B, fb + 16, fb + 19, WHITE);
        box(0.295 * L, 0.302 * L, -0.48 * B, 0.48 * B, fb + 16.6, fb + 18.4, WINDOW);
        box(-0.26 * L, -0.18 * L, -0.12 * B, 0.12 * B, fb + 16, fb + 28, f.farbe);
        box(-0.26 * L, -0.18 * L, -0.12 * B, 0.12 * B, fb + 28, fb + 29.5, BLACK);
        return Arrays.copyOf(buf, n * 9);
    }

    /** Senkrechte Wände über dem Grundriss von y0 bis y1. */
    private void prism(double[][] fp, double y0, double y1, float[] c) {
        for (int i = 0; i < fp.length; i++) {
            double[] a = fp[i], b = fp[(i + 1) % fp.length];
            quad(new double[]{a[0], a[1], y0}, new double[]{b[0], b[1], y0}, new double[]{b[0], b[1], y1}, new double[]{a[0], a[1], y1}, c);
        }
    }

    /** Deck als Fächer über dem Grundriss. */
    private void cap(double[][] fp, double y, float[] c) {
        double[] o = {0, 0, y};
        for (int i = 0; i < fp.length; i++) {
            double[] a = fp[i], b = fp[(i + 1) % fp.length];
            tri(o, new double[]{b[0], b[1], y}, new double[]{a[0], a[1], y}, c);
        }
    }

    private void box(double s0, double s1, double q0, double q1, double y0, double y1, float[] c) {
        double[][] v = {{s0, q0, y0}, {s1, q0, y0}, {s1, q1, y0}, {s0, q1, y0}, {s0, q0, y1}, {s1, q0, y1}, {s1, q1, y1}, {s0, q1, y1}};
        int[][] f = {{0, 1, 2, 3}, {4, 7, 6, 5}, {0, 4, 5, 1}, {1, 5, 6, 2}, {2, 6, 7, 3}, {3, 7, 4, 0}};
        for (int[] q : f) quad(v[q[0]], v[q[1]], v[q[2]], v[q[3]], c);
    }

    private void quad(double[] a, double[] b, double[] c, double[] d, float[] col) {
        tri(a, b, c, col);
        tri(a, c, d, col);
    }

    private void tri(double[] a, double[] b, double[] c, float[] col) {
        double[] wa = world(a), wb = world(b), wc = world(c);
        double ux = wb[0] - wa[0], uy = wb[1] - wa[1], uz = wb[2] - wa[2], vx = wc[0] - wa[0], vy = wc[1] - wa[1], vz = wc[2] - wa[2];
        double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx, l = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (l < 1e-12) { nx = 0; ny = 1; nz = 0; l = 1; }
        if (buf.length < (n + 3) * 9) buf = Arrays.copyOf(buf, buf.length * 2);
        for (double[] w : new double[][]{wa, wb, wc}) {
            int o = n * 9;
            buf[o] = (float) w[0]; buf[o + 1] = (float) w[1]; buf[o + 2] = (float) w[2];
            buf[o + 3] = (float) (nx / l); buf[o + 4] = (float) (ny / l); buf[o + 5] = (float) (nz / l);
            buf[o + 6] = col[0]; buf[o + 7] = col[1]; buf[o + 8] = col[2];
            n++;
        }
    }

    /** Körper (s vorn, q Steuerbord, y oben) → Welt. */
    private double[] world(double[] p) {
        double s = p[0], q = p[1], y = p[2];
        return new double[]{cx + s * sh + q * ch, cy + y, cz - s * ch + q * sh};
    }
}
