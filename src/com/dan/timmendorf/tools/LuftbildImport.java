package com.dan.timmendorf.tools;

import com.dan.timmendorf.scene.Nutzung;
import com.dan.timmendorf.scene.Site;
import com.dan.timmendorf.scene.Terrain;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Das Luftbild DOP20 zu Klassen (Phase 2, Schritt {@code luftbild}, F6 A: Klassen aus RGB, Infrarot und ALKIS, die Farben
 * daran geeicht; gezeichnet wird in der Nähe prozedural, in der Ferne ab Phase 4 mit dem Luftbild als Textur). Braucht
 * {@code daten/gelaende}, {@code daten/alkis} und {@code daten/kern/bewuchs.u8}. Schreibt:
 * <ul>
 * <li>{@code daten/kern/luftbild.rgbi}: das DOP20 2025 je Meter gemittelt ({@link Terrain#KN}² × R, G, B, Infrarot);</li>
 * <li>{@code daten/kern/klasse.u8}: die Klasse je Meter (Codes wie {@link Terrain#ART}) aus Farbe, NDVI, ALKIS-Nutzung,
 * Bewuchshöhe, Höhe, Neigung und Abstand zum Wasser ({@link #klasse});</li>
 * <li>{@code daten/gelaende/luftbild_haupt.rgb}: das DOP20 je 10-m-Zelle des Hauptrasters (R, G, B) für die Ferne;</li>
 * <li>{@code daten/gelaende/art.u8}: die Art je 10-m-Zelle als häufigste Klasse ihrer Meter (im Kern) oder ihrer
 * 2-m-Flecken (außerhalb); wo kein Luftbild ist (offene See), bleibt die Art aus {@code gelaende};</li>
 * <li>{@code daten/kern/farben.properties}: mittlere Farbe je Klasse im Kern (sRGB 0..1), zum Eichen der Farben.</li>
 * </ul>
 * Prüfbild {@code docs/status/Luftbild_Phase2.png}: links das Luftbild, in der Mitte die Klassen, rechts die Arten.
 */
public final class LuftbildImport {
    public static final File BILD = new File("docs/status/Luftbild_Phase2.png");
    static final Pattern NAME = Pattern.compile("dop20\\w*_32_(\\d{3})_(\\d{4})_1_sh_(\\d{4})\\.tif", Pattern.CASE_INSENSITIVE);
    /** Farben der Klassen im Prüfbild (Terrain.ART). */
    static final int[] FARBE = {0x000000, 0x2f5f8f, 0x8f9f5f, 0xeadfb8, 0xa69a78, 0xb08a60, 0xd8cc80, 0x2f5f2f, 0x5f8f4f, 0x8fcf6f,
            0xc07a6a, 0x9a9a9a};

    private LuftbildImport() { }

    public static void main(String[] a) throws Exception { importieren(Rohdaten.laden(), new File("daten"), System.out::println); }

    /** Was über einen Ort bekannt ist, wenn er klassiert wird. */
    record Ort(double h, double neigung, double bewuchs, int nutzung, double wasserAbstand, boolean wasser) { }

    /**
     * Die Klasse eines Flecks aus R, G, B, Infrarot (0..255) und dem Ort [Schwellen G, am Kern geeicht in Phase 2]: Wasser
     * (unter NHN oder dunkel im Infrarot und tief) → Wasser; Pflanzen (NDVI über 0,15) nach Höhe und ALKIS (über 3 m oder
     * Wald → Wald, über 1,5 m → Gebüsch, Sumpf und Moor → Schilf, Acker → Acker, sonst Wiese); kahl: über 2,5 m → Siedlung
     * (Dach), steil → Steilufer, hell nahe am Wasser → Strand, tief nahe am Wasser → nasser Sand, Acker bleibt Acker,
     * Straße, Weg, Platz und graue Flächen → Weg, bebaute Nutzung → Siedlung, sonst Wiese.
     */
    static byte klasse(int r, int g, int b, int ir, Ort o) {
        double nd = (ir - r) / (double) Math.max(1, ir + r), br = (r + g + b) / 3.0;
        boolean wasserFarbe = ir < 55 && nd < 0.05 && br < 110;
        if (o.wasser || (wasserFarbe && o.h < 0.4)) return Terrain.SEE;
        int nu = o.nutzung;
        if (nd > 0.15) {
            if (o.bewuchs > 3.0 || (Nutzung.wald(nu) && o.bewuchs > 1.0)) return Terrain.WALD;
            if (o.bewuchs > 1.5) return Terrain.GEBUESCH;
            if (nu == Nutzung.SUMPF || nu == Nutzung.MOOR) return Terrain.SCHILF;
            if (nu == Nutzung.ACKER) return Terrain.ACKER;
            return Terrain.WIESE;
        }
        if (o.bewuchs > 2.5) return Terrain.SIEDLUNG;
        if (o.neigung > 0.6) return Terrain.KLIFF;
        if (o.wasserAbstand < 120 && o.h < 4) {
            if (o.h < 0.6 && o.wasserAbstand < 25) return Terrain.NASS;
            if (br > 140) return Terrain.STRAND;
        }
        if (nu == Nutzung.ACKER) return Terrain.ACKER;
        if (nu == Nutzung.UNLAND && br > 140) return Terrain.STRAND;
        double sat = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
        if (nu == Nutzung.STRASSE || nu == Nutzung.WEG || nu == Nutzung.PLATZ || nu == Nutzung.BAHN || (sat < 18 && br > 60)) return Terrain.WEG;
        if (Nutzung.siedlung(nu)) return Terrain.SIEDLUNG;
        return br > 150 ? Terrain.STRAND : Terrain.WIESE;
    }

    public static List<String> importieren(Rohdaten r, File daten, Consumer<String> log) throws IOException {
        long t0 = System.nanoTime();
        List<String> bericht = new ArrayList<>();
        File gd = new File(daten, "gelaende"), kd = new File(daten, "kern"), ad = new File(daten, "alkis");
        Terrain t = Terrain.load(gd);
        if (!t.measured() || !t.hasKern()) throw new IOException("daten/gelaende fehlt: erst Schritt gelaende");
        File bf = new File(kd, BewuchsImport.KERN);
        if (!bf.isFile()) throw new IOException("daten/kern/bewuchs.u8 fehlt: erst Schritt bewuchs");
        byte[] bew = Files.readAllBytes(bf.toPath());
        File bh = new File(gd, BewuchsImport.HAUPT);
        byte[] bewH = bh.isFile() ? Files.readAllBytes(bh.toPath()) : new byte[Terrain.N * Terrain.N];
        int KN = Terrain.KN, N = Terrain.N;
        byte[] nk = GelaendeImport.lies(new File(ad, AlkisImport.KERN), KN * KN), nh = GelaendeImport.lies(new File(ad, AlkisImport.HAUPT), N * N);
        if (nk == null || nh == null) { log.accept("  ALKIS fehlt (daten/alkis): Klassen ohne Nutzung"); nk = new byte[KN * KN]; nh = new byte[N * N]; }
        byte[] quelle = Files.readAllBytes(new File(gd, GelaendeImport.QUELLE).toPath());
        int kE = (int) Math.round(Terrain.KX0 + Site.E0), kN = (int) Math.round(Site.N0 - Terrain.KZ0);
        int hE = (int) Math.round(Terrain.X0 + Site.E0), hN = (int) Math.round(Site.N0 - Terrain.Z0);
        float[] kern = new float[KN * KN];
        boolean[] kW = new boolean[KN * KN];
        for (int k = 0; k < kern.length; k++) { kern[k] = (float) Terrain.decode(t.kern[k]); kW[k] = kern[k] < 0 && quelle[k] != 1; }
        float[] kAbst = Raster.abstand(kW, KN, 1);
        float[] haupt = new float[N * N];
        boolean[] hW = new boolean[N * N];
        for (int k = 0; k < haupt.length; k++) { haupt[k] = (float) Terrain.decode(t.h[k]); hW[k] = haupt[k] < 0; }
        float[] hAbst = Raster.abstand(hW, N, Terrain.CELL);

        TreeMap<Integer, File> kacheln = new TreeMap<>();
        File[] fs = r.dop20().listFiles();
        if (fs == null) throw new IOException("DOP20: Ordner " + r.dop20() + " fehlt");
        for (File f : fs) {
            Matcher m = NAME.matcher(f.getName());
            if (m.matches()) kacheln.put(Integer.parseInt(m.group(1)) * 10000 + Integer.parseInt(m.group(2)), f);
        }
        byte[] rgbi = new byte[KN * KN * 4];
        byte[] klasse = new byte[KN * KN];
        byte[] fern = new byte[N * N * 3];
        int nkl = Terrain.ART.length;
        short[] stimmen = new short[N * N * nkl];
        int gelesen = 0, ohneFarbe = 0;
        for (var en : kacheln.entrySet()) {
            int ek = en.getKey() / 10000, nkm = en.getKey() % 10000;
            boolean imHaupt = (ek + 1) * 1000 > hE && ek * 1000 < hE + N * 10 && (nkm + 1) * 1000 > hN - N * 10 && nkm * 1000 < hN;
            if (!imHaupt) continue;
            byte[] px;
            int w, h, spp;
            try (GeoTiff g = GeoTiff.open(en.getValue())) {
                w = g.width;
                h = g.height;
                spp = g.samples;
                if (spp < 4) { ohneFarbe++; continue; }
                px = g.bytes(0, 0, w, h);
            }
            gelesen++;
            int p = w / 1000;
            // Kern: je Meter
            for (int y = 0; y < 1000; y++) {
                int kj = kN - ((nkm + 1) * 1000 - y);
                if (kj < 0 || kj >= KN) continue;
                for (int x = 0; x < 1000; x++) {
                    int ki = ek * 1000 + x - kE;
                    if (ki < 0 || ki >= KN) continue;
                    int[] s = mittel(px, w, spp, x * p, y * p, p);
                    int k = kj * KN + ki;
                    for (int c = 0; c < 4; c++) rgbi[k * 4 + c] = (byte) s[c];
                    Ort o = new Ort(kern[k], GelaendeImport.neigung(kern, KN, ki, kj, 1), (bew[k] & 0xff) * BewuchsImport.STUFE, nk[k] & 0xff, kAbst[k], kW[k]);
                    klasse[k] = klasse(s[0], s[1], s[2], s[3], o);
                }
            }
            // Hauptraster: je 10-m-Zelle die Farbe, außerhalb des Kerns je 2 m eine Stimme
            for (int y = 0; y < 100; y++)
                for (int x = 0; x < 100; x++) {
                    double e = ek * 1000 + x * 10 + 5, n = (nkm + 1) * 1000 - y * 10 - 5;
                    int hi = (int) Math.floor((e - hE) / 10), hj = (int) Math.floor((hN - n) / 10);
                    if (hi < 0 || hj < 0 || hi >= N || hj >= N) continue;
                    int q = hj * N + hi;
                    int[] s = mittel(px, w, spp, x * 10 * p, y * 10 * p, 10 * p);
                    for (int c = 0; c < 3; c++) fern[q * 3 + c] = (byte) s[c];
                    boolean imKern = e >= kE && e < kE + KN && n <= kN && n > kN - KN;
                    if (imKern) continue;
                    for (int b = 0; b < 5; b++)
                        for (int a = 0; a < 5; a++) {
                            int[] s2 = mittel(px, w, spp, (x * 10 + a * 2) * p, (y * 10 + b * 2) * p, 2 * p);
                            if (s2[0] + s2[1] + s2[2] + s2[3] == 0) continue;
                            Ort o = new Ort(haupt[q], GelaendeImport.neigung(haupt, N, hi, hj, 10), (bewH[q] & 0xff) * BewuchsImport.STUFE * 0.6,
                                    nh[q] & 0xff, hAbst[q], hW[q]);
                            stimmen[q * nkl + klasse(s2[0], s2[1], s2[2], s2[3], o)]++;
                        }
                }
            if (gelesen % 25 == 0) log.accept(Raster.f("  DOP20 %d Kacheln gelesen …", gelesen));
        }
        // Kernzellen ohne Luftbild (offene See ohne DOP20-Kachel): Wasser nach dem Gelände, sonst die Art des Hauptrasters
        int ohneBild = 0;
        for (int kj = 0; kj < KN; kj++)
            for (int ki = 0; ki < KN; ki++) {
                int k = kj * KN + ki;
                if (klasse[k] != 0) continue;
                ohneBild++;
                double e = kE + ki + 0.5, n = kN - kj - 0.5;
                int hi = Math.max(0, Math.min(N - 1, (int) Math.floor((e - hE) / 10))), hj = Math.max(0, Math.min(N - 1, (int) Math.floor((hN - n) / 10)));
                klasse[k] = kW[k] ? Terrain.SEE : t.art[hj * N + hi] != 0 ? t.art[hj * N + hi] : Terrain.WIESE;
            }
        if (ohneBild > 0) log.accept(Raster.f("  Kern ohne Luftbild: %.1f %% (Klasse aus Gelände und Hauptraster)", 100.0 * ohneBild / klasse.length));
        for (int kj = 0; kj < KN; kj++)
            for (int ki = 0; ki < KN; ki++) {
                int k = kj * KN + ki;
                if (klasse[k] == 0 || (rgbi[k * 4] | rgbi[k * 4 + 1] | rgbi[k * 4 + 2]) == 0) continue;
                double e = kE + ki + 0.5, n = kN - kj - 0.5;
                int hi = (int) Math.floor((e - hE) / 10), hj = (int) Math.floor((hN - n) / 10);
                if (hi < 0 || hj < 0 || hi >= N || hj >= N) continue;
                stimmen[(hj * N + hi) * nkl + klasse[k]]++;
            }
        long geaendert = 0, mitBild = 0;
        for (int q = 0; q < N * N; q++) {
            int best = 0, bc = 0, sum = 0;
            for (int c = 1; c < nkl; c++) { sum += stimmen[q * nkl + c]; if (stimmen[q * nkl + c] > bc) { bc = stimmen[q * nkl + c]; best = c; } }
            if (bc == 0) continue;
            mitBild++;
            // Steilufer und Strand sind schmal: schon ein Viertel der Stimmen genügt
            for (int c : new int[]{Terrain.KLIFF, Terrain.STRAND}) if (stimmen[q * nkl + c] * 4 >= sum && c != best) { best = c; break; }
            if (t.art[q] != best) geaendert++;
            t.art[q] = (byte) best;
        }
        kd.mkdirs();
        Files.write(new File(kd, "luftbild.rgbi").toPath(), rgbi);
        Files.write(new File(kd, "klasse.u8").toPath(), klasse);
        Files.write(new File(gd, "luftbild_haupt.rgb").toPath(), fern);
        Files.write(new File(gd, Terrain.ARTEN).toPath(), t.art);
        Properties gp = new Properties();
        try (var in = new java.io.InputStreamReader(new java.io.FileInputStream(new File(gd, Terrain.INFO)), java.nio.charset.StandardCharsets.UTF_8)) { gp.load(in); }
        gp.setProperty("arten", "DOP20 2025 (RGBI 20 cm) mit ALKIS, bDOM und DGM1, LuftbildImport; offene See ohne Luftbild aus der Höhe");
        try (var o = new java.io.OutputStreamWriter(new java.io.FileOutputStream(new File(gd, Terrain.INFO)), java.nio.charset.StandardCharsets.UTF_8)) {
            gp.store(o, "Gelaende der Szene Timmendorf (tools.GelaendeImport, Arten tools.LuftbildImport)");
        }
        double[][] sum = new double[nkl][4];
        long[] cnt = new long[nkl];
        for (int k = 0; k < klasse.length; k++) {
            int c = klasse[k];
            if (c == 0 || (rgbi[k * 4] | rgbi[k * 4 + 1] | rgbi[k * 4 + 2]) == 0) continue;   // ohne Luftbild
            cnt[c]++;
            for (int b = 0; b < 4; b++) sum[c][b] += rgbi[k * 4 + b] & 0xff;
        }
        Properties fp = new Properties();
        StringBuilder anteile = new StringBuilder();
        for (int c = 1; c < nkl; c++) {
            if (cnt[c] == 0) continue;
            fp.setProperty("farbe." + c, String.format(Locale.ROOT, "%.3f %.3f %.3f", sum[c][0] / cnt[c] / 255, sum[c][1] / cnt[c] / 255, sum[c][2] / cnt[c] / 255));
            fp.setProperty("infrarot." + c, String.format(Locale.ROOT, "%.3f", sum[c][3] / cnt[c] / 255));
            fp.setProperty("anteil." + c, String.format(Locale.ROOT, "%.4f", cnt[c] / (double) klasse.length));
            fp.setProperty("name." + c, Terrain.ART[c]);
            anteile.append(anteile.length() == 0 ? "" : ", ").append(Terrain.ART[c]).append(String.format(Locale.GERMAN, " %.1f %%", 100.0 * cnt[c] / klasse.length));
        }
        try (var o = new java.io.OutputStreamWriter(new java.io.FileOutputStream(new File(kd, "farben.properties")), java.nio.charset.StandardCharsets.UTF_8)) {
            fp.store(o, "Mittlere Farbe je Klasse im Kern aus DOP20 2025 (sRGB 0..1), LuftbildImport");
        }
        bericht.add(Raster.f("DOP20: %d Kacheln gelesen (%d ohne Infrarot übergangen)", gelesen, ohneFarbe));
        bericht.add("Klassen im Kern: " + anteile);
        bericht.add(Raster.f("Hauptraster: %.0f %% der Zellen mit Luftbild, %,d Arten gegenüber gelaende geändert; %s", 100.0 * mitBild / (N * N), geaendert, t.describe()));
        for (String s : bericht) log.accept("  " + s);
        bild(rgbi, klasse, t, bericht, BILD);
        log.accept(Raster.f("Luftbild: daten/kern/luftbild.rgbi, klasse.u8, farben.properties, daten/gelaende/art.u8, luftbild_haupt.rgb · %.1f s", (System.nanoTime() - t0) / 1e9));
        return bericht;
    }

    static int[] mittel(byte[] px, int w, int spp, int x, int y, int n) {
        int[] s = new int[4];
        for (int b = 0; b < n; b++)
            for (int a = 0; a < n; a++) {
                int p = ((y + b) * w + x + a) * spp;
                for (int c = 0; c < 4; c++) s[c] += px[p + c] & 0xff;
            }
        for (int c = 0; c < 4; c++) s[c] /= n * n;
        return s;
    }

    static void bild(byte[] rgbi, byte[] klasse, Terrain t, List<String> bericht, File png) throws IOException {
        int S = 1152, KN = Terrain.KN, N = Terrain.N;
        int W = 3 * S + 40, H = S + 60 + 18 * (bericht.size() + 3);
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0x1d2328));
        g.fillRect(0, 0, W, H);
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++) {
                int rr = 0, gg = 0, bb = 0;
                int[] votes = new int[Terrain.ART.length];
                for (int b = 0; b < 4; b++)
                    for (int a = 0; a < 4; a++) {
                        int k = (y * 4 + b) * KN + x * 4 + a;
                        rr += rgbi[k * 4] & 0xff;
                        gg += rgbi[k * 4 + 1] & 0xff;
                        bb += rgbi[k * 4 + 2] & 0xff;
                        votes[klasse[k]]++;
                    }
                img.setRGB(10 + x, 10 + y, 0xff000000 | (rr / 16) << 16 | (gg / 16) << 8 | bb / 16);
                int best = 0;
                for (int c = 1; c < votes.length; c++) if (votes[c] > votes[best]) best = c;
                img.setRGB(20 + S + x, 10 + y, 0xff000000 | FARBE[best]);
            }
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++) img.setRGB(30 + 2 * S + x, 10 + y, 0xff000000 | FARBE[t.art[(int) (y * (N / (double) S)) * N + (int) (x * (N / (double) S))]]);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
        g.setColor(Color.WHITE);
        g.drawString("DOP20 2025 im Kern, je Meter", 16, 30);
        g.drawString("Klassen im Kern (1 m)", 26 + S, 30);
        g.drawString("Arten im Hauptraster (10 m)", 36 + 2 * S, 30);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        int lx = 16, ly = S + 34;
        for (int c = 1; c < Terrain.ART.length; c++) {
            g.setColor(new Color(FARBE[c]));
            g.fillRect(lx, ly - 11, 14, 14);
            g.setColor(Color.WHITE);
            g.drawString(Terrain.ART[c], lx + 18, ly);
            lx += 18 + g.getFontMetrics().stringWidth(Terrain.ART[c]) + 22;
        }
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        int y = ly + 24;
        g.drawString("Timmendorf Phase 2 · Klassen aus DOP20, ALKIS, bDOM und DGM1 · " + java.time.LocalDate.now(), 12, y);
        for (String s : bericht) for (String l : Raster.wrap(s, 200)) { y += 18; g.drawString(l, 12, y); }
        g.dispose();
        png.getAbsoluteFile().getParentFile().mkdirs();
        javax.imageio.ImageIO.write(img, "png", png);
    }
}
