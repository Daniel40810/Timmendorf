package com.dan.timmendorf.tools;

import com.dan.timmendorf.scene.Site;
import com.dan.timmendorf.scene.Terrain;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bewuchs und Bauten aus dem bDOM (Phase 2, Schritt {@code bewuchs}): das bildbasierte Oberflächenmodell 2023 zu 20 cm
 * ({@code bdom20nc_32_E_N_1_sh_2023.tif}, float32). Braucht {@code daten/gelaende}. Schreibt:
 * <ul>
 * <li>{@code daten/kern/bewuchs.u8}: je Meter im Kern die Höhe über dem Gelände, die das bDOM zeigt (größter der 25
 * Werte minus Gelände, Stufe {@link #STUFE} m, bis 51 m): Bäume im Kurpark, Hecken, Häuser, das Maritim Seehotel;</li>
 * <li>{@code daten/gelaende/bewuchs_haupt.u8}: dasselbe je 10-m-Zelle des Hauptrasters (der höchste Meter), für Wälder
 * und Bäume außerhalb des Kerns.</li>
 * </ul>
 * Befund aus dem Werkbuch: das bDOM 2023 kennt die neue Maritim Seebrücke noch nicht (im Bau); über Wasser misst es nur
 * den Spiegel. Höhen über dem Wasser zählen darum nicht (Gelände unter NHN 0 gibt 0). Prüfbild
 * {@code docs/status/Bewuchs_Phase2.png}.
 */
public final class BewuchsImport {
    public static final double STUFE = 0.2;
    public static final String KERN = "bewuchs.u8", HAUPT = "bewuchs_haupt.u8";
    public static final File BILD = new File("docs/status/Bewuchs_Phase2.png");
    static final Pattern NAME = Pattern.compile("bdom\\w*_32_(\\d{3})_(\\d{4})_1_sh_(\\d{4})\\.tif", Pattern.CASE_INSENSITIVE);

    private BewuchsImport() { }

    public static void main(String[] a) throws Exception { importieren(Rohdaten.laden(), new File("daten"), System.out::println); }

    public static List<String> importieren(Rohdaten r, File daten, Consumer<String> log) throws IOException {
        long t0 = System.nanoTime();
        List<String> bericht = new ArrayList<>();
        File gd = new File(daten, "gelaende"), kd = new File(daten, "kern");
        Terrain t = Terrain.load(gd);
        if (!t.measured() || !t.hasKern()) throw new IOException("daten/gelaende fehlt: erst Schritt gelaende");
        int KN = Terrain.KN, N = Terrain.N;
        int kE = (int) Math.round(Terrain.KX0 + Site.E0), kN = (int) Math.round(Site.N0 - Terrain.KZ0);
        int hE = (int) Math.round(Terrain.X0 + Site.E0), hN = (int) Math.round(Site.N0 - Terrain.Z0);
        TreeMap<Integer, File> kacheln = new TreeMap<>();
        File[] fs = r.bdom().listFiles();
        if (fs == null) throw new IOException("bDOM: Ordner " + r.bdom() + " fehlt");
        for (File f : fs) {
            Matcher m = NAME.matcher(f.getName());
            if (m.matches()) kacheln.put(Integer.parseInt(m.group(1)) * 10000 + Integer.parseInt(m.group(2)), f);
        }
        byte[] kern = new byte[KN * KN], haupt = new byte[N * N];
        int gelesen = 0;
        double maxH = 0;
        double[] hoechste = new double[3];
        for (var en : kacheln.entrySet()) {
            int ek = en.getKey() / 10000, nkm = en.getKey() % 10000;
            boolean imHaupt = (ek + 1) * 1000 > hE && ek * 1000 < hE + N * 10 && (nkm + 1) * 1000 > hN - N * 10 && nkm * 1000 < hN;
            if (!imHaupt) continue;
            float[] v;
            int w, h;
            try (GeoTiff g = GeoTiff.open(en.getValue())) {
                w = g.width;
                h = g.height;
                v = g.floats(0, 0, w, h);
            }
            gelesen++;
            int px = w / 1000;   // Bildpunkte je Meter (5)
            for (int y = 0; y < 1000; y++)
                for (int x = 0; x < 1000; x++) {
                    float mx = Float.NaN;
                    for (int b = 0; b < px; b++)
                        for (int a = 0; a < px; a++) {
                            float q = v[(y * px + b) * w + x * px + a];
                            if (!Float.isNaN(q) && (Float.isNaN(mx) || q > mx)) mx = q;
                        }
                    if (Float.isNaN(mx)) continue;
                    double e = ek * 1000 + x + 0.5, n = (nkm + 1) * 1000 - y - 0.5;
                    double[] xz = Site.fromUtm(e, n);
                    double g = t.roh(xz[0], xz[1]);
                    if (g < 0) continue;   // über Wasser misst das bDOM nur den Spiegel
                    double ueber = Math.max(0, mx - g);
                    int s = (int) Math.min(255, Math.round(ueber / STUFE));
                    if (ueber > maxH) { maxH = ueber; hoechste[0] = e; hoechste[1] = n; hoechste[2] = mx; }
                    int ki = (int) Math.floor(e) - kE, kj = kN - (int) Math.ceil(n);
                    if (ki >= 0 && kj >= 0 && ki < KN && kj < KN) kern[kj * KN + ki] = (byte) s;
                    int hi = (int) Math.floor((e - hE) / 10), hj = (int) Math.floor((hN - n) / 10);
                    if (hi >= 0 && hj >= 0 && hi < N && hj < N && s > (haupt[hj * N + hi] & 0xff)) haupt[hj * N + hi] = (byte) s;
                }
            if (gelesen % 20 == 0) log.accept(Raster.f("  bDOM %d Kacheln gelesen …", gelesen));
        }
        kd.mkdirs();
        Files.write(new File(kd, KERN).toPath(), kern);
        Files.write(new File(gd, HAUPT).toPath(), haupt);
        long[] klasse = new long[4];   // unter 0,5 m, bis 3 m, bis 15 m, darüber
        for (byte b : kern) {
            double u = (b & 0xff) * STUFE;
            klasse[u < 0.5 ? 0 : u < 3 ? 1 : u < 15 ? 2 : 3]++;
        }
        bericht.add(Raster.f("bDOM: %d Kacheln im Hauptraster gelesen; im Kern %.0f %% flach (unter 0,5 m), %.1f %% bis 3 m, %.1f %% bis 15 m, %.2f %% höher",
                gelesen, 100.0 * klasse[0] / kern.length, 100.0 * klasse[1] / kern.length, 100.0 * klasse[2] / kern.length, 100.0 * klasse[3] / kern.length));
        bericht.add(Raster.f("Höchster Punkt über Grund: %.1f m bei O %.0f, N %.0f (Oberfläche %.1f m NHN)", maxH, hoechste[0], hoechste[1], hoechste[2]));
        double[] see = Site.fromUtm(Site.SEEHOTEL.e(), Site.SEEHOTEL.n());
        bericht.add(Raster.f("Maritim Seehotel: höchster Punkt im Umkreis 60 m %.1f m über Grund", umkreis(kern, KN, see[0], see[1], 60)));
        double[] club = Site.fromUtm(Site.CLUBHOTEL.e(), Site.CLUBHOTEL.n());
        bericht.add(Raster.f("Maritim ClubHotel: höchster Punkt im Umkreis 40 m %.1f m über Grund", umkreis(kern, KN, club[0], club[1], 40)));
        for (String s : bericht) log.accept("  " + s);
        bild(kern, bericht, BILD);
        log.accept(Raster.f("Bewuchs: daten/kern/bewuchs.u8, daten/gelaende/bewuchs_haupt.u8 · %.1f s", (System.nanoTime() - t0) / 1e9));
        return bericht;
    }

    /** Höchster Bewuchs (m über Grund) im Umkreis rad um (x, z) im Kern. */
    static double umkreis(byte[] kern, int KN, double x, double z, double rad) {
        double m = 0;
        for (double dz = -rad; dz <= rad; dz++)
            for (double dx = -rad; dx <= rad; dx++) {
                if (dx * dx + dz * dz > rad * rad) continue;
                int i = (int) Math.floor(x + dx - Terrain.KX0), j = (int) Math.floor(z + dz - Terrain.KZ0);
                if (i < 0 || j < 0 || i >= KN || j >= KN) continue;
                m = Math.max(m, (kern[j * KN + i] & 0xff) * STUFE);
            }
        return m;
    }

    static void bild(byte[] kern, List<String> bericht, File png) throws IOException {
        int S = 1152, KN = Terrain.KN;
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(S + 20, S + 120, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new java.awt.Color(0x1d2328));
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        int st = KN / S;
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++) {
                int m = 0;
                for (int b = 0; b < st; b++) for (int a = 0; a < st; a++) m = Math.max(m, kern[(y * st + b) * KN + x * st + a] & 0xff);
                double u = m * STUFE;
                int c = u < 0.5 ? 0x22282c : u < 3 ? 0x6f9f4f : u < 15 ? 0x2f7f2f : u < 30 ? 0xd0a040 : 0xf04030;
                img.setRGB(10 + x, 10 + y, c);
            }
        g.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 13));
        g.setColor(new java.awt.Color(0xee, 0xee, 0xee));
        int y = S + 30;
        g.drawString("Timmendorf Phase 2 · Bewuchs aus bDOM 2023 im Kern · grün hell bis 3 m, dunkel bis 15 m, gelb bis 30 m, rot darüber", 12, y);
        for (String z : bericht) for (String l : Raster.wrap(z, 150)) { y += 18; g.drawString(l, 12, y); }
        g.dispose();
        File d = png.getAbsoluteFile().getParentFile();
        if (d != null) d.mkdirs();
        javax.imageio.ImageIO.write(img, "png", png);
    }
}
