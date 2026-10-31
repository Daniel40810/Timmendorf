package com.dan.timmendorf.tools;

import java.util.Arrays;
import java.util.Locale;

/**
 * Hilfen für Raster der Werkzeuge (Phase 2): Lücken füllen mit dem Versatz zur BAW, Kennzahlen, Schummerung.
 */
final class Raster {
    private Raster() { }

    /**
     * Füllt die Lücken (NaN) eines Rasters mit der Grundfläche {@code base} (BAW) und dem Versatz, den das Messraster am
     * nächsten Rand gegen die Grundfläche hat; der Versatz klingt mit e^(−d/L) ab. So schließt die See ohne Stufe an den
     * Strand an. Wo {@code deckel[i]} gesetzt ist (Wasserfläche zur Zeit der Befliegung), bleibt der Grund unter
     * {@code max}. Der Versatz am Rand ist über (2r+1)² Nachbarn gemittelt, damit ein einzelner Punkt (Gischt, Möwe) nicht
     * durchschlägt.
     *
     * @return Zahl der gefüllten Zellen
     */
    static int fuellen(float[] mess, float[] base, boolean[] deckel, int n, double cell, double laenge, int r, float max, float[] out) {
        int nn = n * n;
        int[] src = new int[nn];
        float[] dist = new float[nn];
        Arrays.fill(src, -1);
        Arrays.fill(dist, Float.MAX_VALUE);
        int luecken = 0;
        // Ränder: gültige Zellen neben einer Lücke
        for (int j = 0; j < n; j++)
            for (int i = 0; i < n; i++) {
                int k = j * n + i;
                if (Float.isNaN(mess[k])) { luecken++; continue; }
                boolean rand = (i > 0 && Float.isNaN(mess[k - 1])) || (i < n - 1 && Float.isNaN(mess[k + 1]))
                        || (j > 0 && Float.isNaN(mess[k - n])) || (j < n - 1 && Float.isNaN(mess[k + n]));
                if (rand) { src[k] = k; dist[k] = 0; }
            }
        if (luecken == 0) { System.arraycopy(mess, 0, out, 0, nn); return 0; }
        // Versatz am Rand, gemittelt
        float[] vers = new float[nn];
        for (int k = 0; k < nn; k++) {
            if (src[k] != k) continue;
            int i = k % n, j = k / n;
            double s = 0;
            int c = 0;
            for (int b = Math.max(0, j - r); b <= Math.min(n - 1, j + r); b++)
                for (int a = Math.max(0, i - r); a <= Math.min(n - 1, i + r); a++) {
                    int q = b * n + a;
                    if (!Float.isNaN(mess[q]) && !Float.isNaN(base[q])) { s += mess[q] - base[q]; c++; }
                }
            vers[k] = c > 0 ? (float) (s / c) : 0f;
        }
        // nächster Rand: zwei Durchgänge mit acht Nachbarn (genähert euklidisch)
        int[][] vor = {{-1, -1}, {0, -1}, {1, -1}, {-1, 0}}, rueck = {{1, 0}, {-1, 1}, {0, 1}, {1, 1}};
        for (int j = 0; j < n; j++)
            for (int i = 0; i < n; i++) schritt(i, j, n, vor, src, dist);
        for (int j = n - 1; j >= 0; j--)
            for (int i = n - 1; i >= 0; i--) schritt(i, j, n, rueck, src, dist);
        for (int k = 0; k < nn; k++) {
            if (!Float.isNaN(mess[k])) { out[k] = mess[k]; continue; }
            float b = Float.isNaN(base[k]) ? 0f : base[k];
            double v = b;
            if (src[k] >= 0) v += vers[src[k]] * Math.exp(-dist[k] * cell / laenge);
            if (deckel != null && deckel[k]) v = Math.min(v, max);
            out[k] = (float) v;
        }
        return luecken;
    }

    /**
     * Abstand jeder Zelle zur nächsten Zelle mit {@code ziel[k]} (m; 0 auf dem Ziel), genähert euklidisch über die nächste
     * Quelle aus zwei Durchgängen mit acht Nachbarn (Danielsson); ohne Ziel überall unendlich.
     */
    static float[] abstand(boolean[] ziel, int n, double cell) {
        int nn = n * n;
        int[] src = new int[nn];
        float[] dist = new float[nn];
        java.util.Arrays.fill(src, -1);
        java.util.Arrays.fill(dist, Float.MAX_VALUE);
        for (int k = 0; k < nn; k++) if (ziel[k]) { src[k] = k; dist[k] = 0; }
        int[][] vor = {{-1, -1}, {0, -1}, {1, -1}, {-1, 0}}, rueck = {{1, 0}, {-1, 1}, {0, 1}, {1, 1}};
        for (int pass = 0; pass < 2; pass++) {
            for (int j = 0; j < n; j++)
                for (int i = 0; i < n; i++) schritt(i, j, n, vor, src, dist);
            for (int j = n - 1; j >= 0; j--)
                for (int i = n - 1; i >= 0; i--) schritt(i, j, n, rueck, src, dist);
        }
        for (int k = 0; k < nn; k++) dist[k] = src[k] < 0 ? Float.POSITIVE_INFINITY : (float) (dist[k] * cell);
        return dist;
    }

    private static void schritt(int i, int j, int n, int[][] nb, int[] src, float[] dist) {
        int k = j * n + i;
        for (int[] d : nb) {
            int a = i + d[0], b = j + d[1];
            if (a < 0 || b < 0 || a >= n || b >= n) continue;
            int s = src[b * n + a];
            if (s < 0) continue;
            int si = s % n, sj = s / n;
            float dd = (float) Math.hypot(i - si, j - sj);
            if (dd < dist[k]) { dist[k] = dd; src[k] = s; }
        }
    }

    /** Wert am Anteil p (0..1) einer Liste (sortiert eine Kopie). */
    static double quantil(double[] v, int len, double p) {
        if (len == 0) return 0;
        double[] c = Arrays.copyOf(v, len);
        Arrays.sort(c);
        return c[Math.min(len - 1, (int) Math.floor(p * (len - 1) + 0.5))];
    }

    /** Schummerung 0..1 (Licht aus Nordwest, 45° hoch) an Zelle (i, j) eines Höhenrasters mit Zellgröße cell. */
    static double schummer(float[] h, int n, int i, int j, double cell, double ueberhoehung) {
        int i0 = Math.max(0, i - 1), i1 = Math.min(n - 1, i + 1), j0 = Math.max(0, j - 1), j1 = Math.min(n - 1, j + 1);
        double dx = (h[j * n + i1] - h[j * n + i0]) / ((i1 - i0) * cell) * ueberhoehung;
        double dz = (h[j1 * n + i] - h[j0 * n + i]) / ((j1 - j0) * cell) * ueberhoehung;
        double nx = -dx, ny = 1, nz = -dz, l = Math.sqrt(nx * nx + ny * ny + nz * nz);
        double lx = -0.5, ly = 0.7071, lz = -0.5;   // aus Nordwest (x Ost, z Süd)
        return Math.max(0, (nx * lx + ny * ly + nz * lz) / l);
    }

    /** Farbe für eine Höhe über NHN (See blau, Watt grau, Land grün bis braun). */
    static int hoehenfarbe(double h, double licht) {
        double r, g, b;
        if (h < 0) {
            double t = Math.min(1, -h / 20);
            r = 0.55 - 0.40 * t; g = 0.70 - 0.40 * t; b = 0.85 - 0.30 * t;
        } else if (h < 3) {
            double t = h / 3;
            r = 0.86 - 0.20 * t; g = 0.82 - 0.10 * t; b = 0.66 - 0.20 * t;
        } else {
            double t = Math.min(1, (h - 3) / 45);
            r = 0.58 + 0.25 * t; g = 0.66 - 0.18 * t; b = 0.40 - 0.10 * t;
        }
        double s = 0.35 + 0.75 * licht;
        int R = (int) Math.max(0, Math.min(255, r * s * 255)), G = (int) Math.max(0, Math.min(255, g * s * 255)), B = (int) Math.max(0, Math.min(255, b * s * 255));
        return 0xff000000 | R << 16 | G << 8 | B;
    }

    static String f(String fmt, Object... a) { return String.format(Locale.GERMAN, fmt, a); }

    /** Bricht einen Text für Prüfbilder um (höchstens w Zeichen je Zeile, Folgezeilen eingerückt). */
    static java.util.List<String> wrap(String s, int w) {
        java.util.List<String> out = new java.util.ArrayList<>();
        while (s.length() > w) {
            int k = s.lastIndexOf(' ', w);
            if (k < 20) k = w;
            out.add(s.substring(0, k));
            s = "    " + s.substring(k).trim();
        }
        out.add(s);
        return out;
    }
}
