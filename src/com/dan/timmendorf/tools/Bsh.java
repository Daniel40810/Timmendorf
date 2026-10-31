package com.dan.timmendorf.tools;

import com.dan.timmendorf.scene.Geo;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Das Multibeam-Raster des BSH „Lübeck approach“ (EMODnet HRDTM 1/256′, CC BY 4.0) aus der ZIP des Downloads (Phase 2):
 * die Textdatei {@code …emo} mit „Länge;Breite;;;Tiefe;…“ je Zelle (rund 4 × 7 m), Tiefen positiv nach unten.
 * Befund Phase 2 (30.10.): das Raster reicht nur von 10,876° O nach Osten, deckt also die Travemündung und die Bucht
 * östlich davon, nicht Timmendorf und Niendorf. Gelesen werden nur die Zellen im Fenster; sie gehen als Mittel je Zelle
 * eines Rasters in UTM hinaus (Höhe = −Tiefe).
 */
public final class Bsh {
    private Bsh() { }

    /**
     * Mittelt die BSH-Zellen in ein Raster n × n mit Nordwestecke (e0, n0) und Zellgröße cell (UTM); NaN, wo keine liegt.
     * Gibt {Zahl der gelesenen Zeilen, Zahl im Raster} in {@code zahl} zurück.
     */
    public static float[] raster(File zip, double e0, double n0, double cell, int n, long[] zahl) throws IOException {
        double[] sum = new double[n * n];
        int[] cnt = new int[n * n];
        double[] llNW = Geo.UTM32N.inverse(e0, n0), llSE = Geo.UTM32N.inverse(e0 + n * cell, n0 - n * cell);
        double latMin = Math.min(llNW[0], llSE[0]) - 0.02, latMax = Math.max(llNW[0], llSE[0]) + 0.02;
        double lonMin = Math.min(llNW[1], llSE[1]) - 0.03, lonMax = Math.max(llNW[1], llSE[1]) + 0.03;
        try (ZipFile z = new ZipFile(zip)) {
            ZipEntry emo = null;
            for (ZipEntry e : java.util.Collections.list(z.entries())) if (e.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".emo")) emo = e;
            if (emo == null) throw new IOException("BSH: keine .emo in " + zip);
            try (BufferedReader r = new BufferedReader(new InputStreamReader(z.getInputStream(emo), StandardCharsets.US_ASCII), 1 << 20)) {
                for (String l; (l = r.readLine()) != null; ) {
                    zahl[0]++;
                    int a = l.indexOf(';'), b = l.indexOf(';', a + 1);
                    if (a < 0 || b < 0) continue;
                    double lon = Double.parseDouble(l.substring(0, a));
                    if (lon < lonMin || lon > lonMax) continue;
                    double lat = Double.parseDouble(l.substring(a + 1, b));
                    if (lat < latMin || lat > latMax) continue;
                    // fünftes Feld: Tiefe
                    int c = b;
                    for (int k = 0; k < 2; k++) c = l.indexOf(';', c + 1);
                    int d = l.indexOf(';', c + 1);
                    if (c < 0 || d < 0 || d == c + 1) continue;
                    double tiefe = Double.parseDouble(l.substring(c + 1, d));
                    double[] en = Geo.UTM32N.forward(lat, lon);
                    int i = (int) Math.floor((en[0] - e0) / cell), j = (int) Math.floor((n0 - en[1]) / cell);
                    if (i < 0 || j < 0 || i >= n || j >= n) continue;
                    sum[j * n + i] += -tiefe;
                    cnt[j * n + i]++;
                    zahl[1]++;
                }
            }
        }
        float[] out = new float[n * n];
        for (int k = 0; k < out.length; k++) out[k] = cnt[k] > 0 ? (float) (sum[k] / cnt[k]) : Float.NaN;
        return out;
    }
}
