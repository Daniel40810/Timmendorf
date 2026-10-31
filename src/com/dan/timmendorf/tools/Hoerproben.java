package com.dan.timmendorf.tools;

import com.dan.soundscape.Soundscape;
import com.dan.soundscape.Wav;
import com.dan.soundscape.demo.Analysis;
import com.dan.timmendorf.camera.Ansichten;
import com.dan.timmendorf.gl.Klang;
import com.dan.timmendorf.gl.Renderer;
import com.dan.timmendorf.scene.Flotte;
import com.dan.timmendorf.scene.SceneClock;
import com.dan.timmendorf.scene.Site;
import com.dan.timmendorf.wiring.Wetterregie;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Phase 15: die Hörproben als WAV mit Spektrogramm nach {@code docs/hoerproben}, ohne Grafikkarte (Fertig-Bedingung:
 * „die Hörproben sind abgenommen“). Die Szene läuft wie im Bildlauf ({@code Renderer.step} und die Physik in Schritten
 * von 1/60 s Echtzeit), der Klang hört an der Kamera zu ({@link Klang} offline, ohne Anpassung des Ohrs, 44,1 kHz). Zum
 * Anhören wird jede Datei auf −1 dBFS Spitze angehoben (höchstens +30 dB); der Bericht {@code hoerproben.txt} nennt die
 * Anhebung, den Pegel am Ohr in dB SPL, die Teile und was gezählt wurde.
 * <p>
 * Aufruf: {@code java … com.dan.timmendorf.tools.Hoerproben [Ordner] [nur]}, etwa {@code docs/hoerproben feuerwerk}.
 */
public final class Hoerproben {
    static final double DT = 1 / 60.0;
    static final List<String> report = new ArrayList<>();
    static final List<String[]> sheet = new ArrayList<>();

    private Hoerproben() { }

    public static void main(String[] a) throws Exception {
        File dir = new File(a.length > 0 ? a[0] : "docs/hoerproben");
        String only = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
        dir.mkdirs();
        SceneClock clock = new SceneClock(LocalDate.of(2026, 7, 25), 10);
        clock.lapse = 1;
        Renderer r = new Renderer(clock);
        Klang k = r.klang;
        k.offline = true;
        k.s.autoGain = false;
        k.s.volume = 1;
        k.s.quality = Soundscape.Quality.HIGH;
        r.wiring.feuerwerkAbends = false;
        long t0 = System.nanoTime();
        if (only.isEmpty() || only.contains("strand")) strand(r, clock, dir);
        if (only.isEmpty() || only.contains("schleife")) schleife(r, clock, dir);
        if (only.isEmpty() || only.contains("hafen")) hafen(r, clock, dir);
        if (only.isEmpty() || only.contains("regatta")) regatta(r, clock, dir);
        if (only.isEmpty() || only.contains("schule") || only.contains("opti")) optimist(r, clock, dir);
        if (only.isEmpty() || only.contains("faehre")) faehre(r, clock, dir);
        if (only.isEmpty() || only.contains("promenade")) promenade(r, clock, dir);
        if (only.isEmpty() || only.contains("feuerwerk")) feuerwerk(r, clock, dir);
        if (only.isEmpty() || only.contains("sturmflut")) sturmflut(r, clock, dir);
        Files.writeString(new File(dir, "hoerproben.txt").toPath(), String.join(System.lineSeparator(), report) + System.lineSeparator(), StandardCharsets.UTF_8);
        if (only.isEmpty()) uebersicht(dir);
        System.out.printf(Locale.GERMAN, "FERTIG in %.0f s%n", (System.nanoTime() - t0) / 1e9);
        System.exit(0);
    }

    // ------------------------------------------------------------------ die Proben

    /** Ein Julimorgen am Spülsaum: die Ostsee läuft auf den Sand, die Strandflagge, Möwen. */
    static void strand(Renderer r, SceneClock c, File dir) throws Exception {
        c.set(LocalDate.of(2026, 7, 25), 9.5);
        r.wiring.regie.lage(Wetterregie.Lage.SOMMERABEND);
        r.placeView(Ansichten.byName("Spülsaum"));
        record(r, dir, "1_Morgen_am_Spuelsaum", "Julimorgen am Spülsaum vor der Seebrücke: 25.7.2026 9:30, Seewind 5,5 m/s aus Nordost, See um 0,5 m; die Ostsee läuft auf den Sand, Strandflagge, Möwen", 40);
    }

    /** Auf der Schleife der Seebrücke am Nachmittag: Wellen an den Pfählen, Schritte auf dem Holzdeck, Leute, Möwen. */
    static void schleife(Renderer r, SceneClock c, File dir) throws Exception {
        c.set(LocalDate.of(2026, 7, 25), 15.5);
        r.wiring.regie.lage(Wetterregie.Lage.SOMMERABEND);
        r.placeView(Ansichten.byName("Schleife"));
        r.camera.place(r.camera.eye[0], com.dan.timmendorf.scene.Bauwerke.DECK + 1.7, r.camera.eye[2], 230, 4);
        vorlauf(r, 30);
        record(r, dir, "2_Auf_der_Schleife", "Auf der Schleife der Maritim Seebrücke: 25.7.2026 15:30, Blick zurück zum Strand, Wellen glucksen an den Pfählen, Schritte auf dem Holzdeck, Leute, Möwen", 40);
    }

    /** Niendorfer Hafen bei Westwind: die Fallen schlagen an den Masten, Möwen über den Kuttern. */
    static void hafen(Renderer r, SceneClock c, File dir) throws Exception {
        c.set(LocalDate.of(2026, 9, 12), 11);
        r.wiring.regie.lage(Wetterregie.Lage.WESTWIND);
        r.placeView(Ansichten.byName("Niendorfer Hafen"));
        record(r, dir, "3_Niendorfer_Hafen_bei_Westwind", String.format(Locale.GERMAN,
                "Niendorfer Hafen bei Westwind: 12.9.2026 11 Uhr, Wind %.0f m/s aus 250°; %d Masten im Yachthafen, die Fallen schlagen, Möwen über den Kuttern",
                r.wiring.windSpeed, Klang.MASTEN_NIENDORF), 40);
    }

    /** Der Start der Regatta vom Wasser neben dem Startschiff: das Signal „Eine Minute“, Startschuss, Wenden, Winschen. */
    static void regatta(Renderer r, SceneClock c, File dir) throws Exception {
        c.set(LocalDate.of(2026, 7, 25), 14);
        r.wiring.regie.lage(Wetterregie.Lage.SOMMERABEND);
        Flotte f = r.wiring.flotte;
        Klang k = r.klang;
        f.neuerStart();
        // still bis 70 s vor dem Schuss (die Signale davor fallen in die Stille)
        boolean an = k.booteAn;
        k.booteAn = false;
        for (int i = 0; i < 6000; i++) {
            r.wiring.step(0.1);
            double tg = f.regatta.toGun();
            if (!Double.isNaN(tg) && tg < 72) break;
        }
        k.booteAn = an;
        double[] st = Site.fromUtm(Flotte.STARTSCHIFF[0], Flotte.STARTSCHIFF[1]);
        double[] pin = f.regatta.course.pin();
        // 40 m hinter dem Startschiff auf dem Wasser (Begleitboot), Blick die Linie entlang zum Pin
        double dx = pin[0] - st[0], dz = pin[1] - st[1], l = Math.hypot(dx, dz);
        double ex = st[0] - dx / l * 40, ez = st[1] - dz / l * 40;
        r.camera.place(ex, r.wiring.pegel + 1.8, ez, Math.toDegrees(Math.atan2(dx, -dz)), 2);
        record(r, dir, "4_Regattastart", String.format(Locale.GERMAN,
                "Der Start der Regatta, 40 m hinter dem Startschiff im Begleitboot: noch %.0f s bis zum Schuss; Signal „Eine Minute“, Startschuss mit Horn, dann die Kreuz (20 J/70)",
                f.regatta.toGun()), 80);
    }

    /** Im Begleitboot bei der Segelschule vor Niendorf: acht Optimisten wenden, Segel schlagen, ab und zu kentert eines. */
    static void optimist(Renderer r, SceneClock c, File dir) throws Exception {
        c.set(LocalDate.of(2026, 7, 25), 11);
        r.wiring.regie.lage(Wetterregie.Lage.SOMMERABEND);
        double cx = 0, cz = 0;
        for (double[] p : Flotte.SCHULE) { cx += p[0] / Flotte.SCHULE.length; cz += p[1] / Flotte.SCHULE.length; }
        r.camera.place(cx, r.wiring.pegel + 1.6, cz + 40, 0, 2);
        vorlauf(r, 90);   // die Optimisten kommen an ihr Dreieck
        record(r, dir, "5_Segelschule_vor_Niendorf", "Im Begleitboot bei der Segelschule vor Niendorf: 25.7.2026 11 Uhr, Seewind 5,5 m/s; acht Optimisten am Dreieck, Wenden, das Segel schlägt über", 50);
    }

    /** Eine Fähre legt am Skandinavienkai ab: der lange Ton aus dem Typhon, der Diesel. */
    static void faehre(Renderer r, SceneClock c, File dir) throws Exception {
        c.set(LocalDate.of(2026, 7, 25), 13);
        r.wiring.regie.lage(Wetterregie.Lage.SOMMERABEND);
        Flotte.Faehre f = r.wiring.flotte.faehren.get(0);
        double[] kai = Flotte.ROUTE[0];
        double[] mole = Site.fromUtm(622_520, 5_978_700);   // gegenüber am Ufer des Priwalls [G]
        double dx = kai[0] - mole[0], dz = kai[1] - mole[1];
        r.camera.place(mole[0], Math.max(r.groundHeight(mole[0], mole[1]), r.wiring.pegel) + 1.7, mole[1], Math.toDegrees(Math.atan2(dx, -dz)), 0);
        f.ablegenIn(14);   // 8 s Vorlauf, dann legt sie nach 6 s ab
        record(r, dir, "6_Faehre_legt_ab", String.format(Locale.GERMAN,
                "%s legt am Skandinavienkai ab (Ohr am Ufer gegenüber, %.0f m): ein langer Ton aus dem Typhon (110 Hz), der Diesel läuft an", f.name,
                Math.hypot(f.x - r.camera.eye[0], f.z - r.camera.eye[2])), 35);
    }

    /** Die Promenade am Nachmittag: Stimmen, Schritte auf Pflaster, die Brandung dahinter. */
    static void promenade(Renderer r, SceneClock c, File dir) throws Exception {
        c.set(LocalDate.of(2026, 7, 25), 16);
        r.wiring.regie.lage(Wetterregie.Lage.SOMMERABEND);
        r.placeView(Ansichten.byName("Promenade"));
        vorlauf(r, 60);
        record(r, dir, "7_Promenade_am_Nachmittag", "Die Promenade am Nachmittag: 25.7.2026 16 Uhr, Seewind; Leute reden und gehen auf dem Pflaster, dahinter die Brandung", 40);
    }

    /** Das Feuerwerk vom Strand: erst das Bild, gut eine Sekunde später der Knall. */
    static void feuerwerk(Renderer r, SceneClock c, File dir) throws Exception {
        LocalDate tag = r.wiring.feuerwerkTag;
        double uhr = r.wiring.feuerwerkUhr;
        // die Zuschauer kommen: von 22:00 bis kurz vor dem Beginn in Schritten von 2 s (wie in ant bildfolge)
        Klang k = r.klang;
        boolean an = k.menschenAn;
        c.set(tag, uhr - 0.75);
        r.wiring.regie.lage(Wetterregie.Lage.SOMMERABEND);
        var m = r.wiring.menschen;
        double bis = c.epochMillis() + (0.75 * 3600 - 10) * 1000;
        r.wiring.feuerwerkAbends = true;
        for (double t = c.epochMillis(); t < bis; t += 2000) {
            c.setEpochMillis(t);
            if (m != null) { m.zuschauer = r.wiring.andrang(); m.step(2.0); }
        }
        c.setEpochMillis(bis);
        k.menschenAn = an;
        r.zumFeuerwerk();
        double[] p = r.wiring.ponton;
        double d = Math.hypot(p[0] - r.camera.eye[0], p[1] - r.camera.eye[2]);
        record(r, dir, "8_Feuerwerk_vom_Strand", String.format(Locale.GERMAN,
                "Das Feuerwerk zur Travemünder Woche vom Strand: 1.8.2026 ab 22:45, Ponton %.0f m entfernt; Abschuss, Zerlegung nach %.1f s Laufzeit, Knistern, Echos, die Zuschauer",
                d, Math.hypot(d, 90) / r.klang.s.soundSpeed()), 75);
        r.wiring.feuerwerkAbends = false;
    }

    /** Die Sturmflut am Scheitel: Ostwind, Brecher an den Pfählen, Regen, Wasser bis an die Promenade. */
    static void sturmflut(Renderer r, SceneClock c, File dir) throws Exception {
        c.set(LocalDate.of(2023, 10, 20), 23);
        r.wiring.sturmflutSchritt();
        r.zurSturmflut(true);
        vorlauf(r, 6);
        record(r, dir, "9_Sturmflut_am_Scheitel", String.format(Locale.GERMAN,
                "Sturmflut am Scheitel: 20.10.2023 23 Uhr, Pegel %.2f m NHN, Wind %.0f m/s aus %03.0f°, See %.1f m; an der Promenade, Brecher an der Seebrücke",
                r.wiring.pegel, r.wiring.windSpeed, r.wiring.windFromDeg, r.wiring.seeHs), 40);
    }

    // ------------------------------------------------------------------ Hilfen

    /** sec Sekunden Szene ohne Ton (Menschen, Tiere und Boote stellen sich ein). */
    static void vorlauf(Renderer r, double sec) {
        Klang k = r.klang;
        boolean[] was = {k.booteAn, k.tiereAn, k.feuerwerkAn, k.wetterAn, k.brandungAn};
        k.booteAn = k.tiereAn = k.feuerwerkAn = k.wetterAn = k.brandungAn = false;
        for (double t = 0; t < sec; t += 0.1) { r.step(0.1); r.physik.drain(); }
        k.booteAn = was[0]; k.tiereAn = was[1]; k.feuerwerkAn = was[2]; k.wetterAn = was[3]; k.brandungAn = was[4];
    }

    /**
     * Eine Probe: 8 s Vorlauf mit Ton (verworfen; Wellen, Fallen, Flaggen stellen sich ein), dann sec Sekunden; WAV,
     * Spektrogramm, Zeile im Bericht.
     */
    static void record(Renderer r, File dir, String name, String what, double sec) throws Exception {
        Klang k = r.klang;
        Soundscape s = k.s;
        int sr = s.sampleRate();
        double[] lv = new double[Soundscape.PARTS.length];
        int nLv = 0;
        float[] all = new float[(int) Math.round(sec * sr) * 2 + 8192];
        int pos = 0;
        long t0 = System.nanoTime();
        int pre = (int) Math.round(8 / DT), frames = pre + (int) Math.round(sec / DT);
        int[] c0 = counts(r);
        for (int i = 0; i < frames; i++) {
            r.step(DT);
            r.physik.drain();
            if (i == pre) c0 = counts(r);
            float[] o = s.render(DT);
            if (i >= pre) {
                int n = Math.min(o.length, all.length - pos);
                System.arraycopy(o, 0, all, pos, n);
                pos += n;
                double[] l = s.levels();
                for (int q = 0; q < lv.length; q++) lv[q] += Math.pow(10, l[q] / 10);
                nLv++;
            }
        }
        float[] out = java.util.Arrays.copyOf(all, pos);
        float peak = 1e-9f;
        for (float v : out) peak = Math.max(peak, Math.abs(v));
        double gainDb = Math.min(30, -1 - 20 * Math.log10(peak));
        float gl = (float) Math.pow(10, gainDb / 20);
        float[] loud = out.clone();
        for (int q = 0; q < loud.length; q++) loud[q] *= gl;
        Wav.write(new File(dir, name + ".wav"), loud, sr);
        double rms = Analysis.rms(out, sr, 0, pos / 2.0 / sr);
        StringBuilder parts = new StringBuilder();
        for (int q = 0; q < lv.length - 1; q++) {
            double db = 10 * Math.log10(lv[q] / Math.max(1, nLv) + 1e-30);
            if (db > 25) parts.append(String.format(Locale.GERMAN, "%s %.0f · ", Soundscape.PARTS[q], db));
        }
        int[] c1 = counts(r);
        String line = String.format(Locale.GERMAN, "%s (zum Anhören um %+.0f dB angehoben)%n  %s%n  am Ohr %.0f dB SPL (Mittel) · %s%n"
                        + "  Boote: %d Segel, %d Winschen, %d Halsen, %d Signale, %d Schüsse, %d Fährhorn · Feuerwerk: %d Abschüsse, %d Zerlegungen, %d Knistern%n"
                        + "  Wasser: %d Schwälle, %d Brecher · Fallen %d, Flaggen %d, Schritte %d · Rufe: %d Möwen, %d Schwäne, %d Kormorane, %d Enten%n"
                        + "  Brandung fern %.0f dB (Linie %d Stücke, nächstes %.0f m, %.0f W/m vergehen gegenüber) · Leute in 40 m %d, gehen in 15 m %d · gerechnet in %.1f s",
                name + ".wav", gainDb, what, 20 * Math.log10(rms + 1e-12) + Soundscape.REF_DB, parts.length() > 3 ? parts.substring(0, parts.length() - 3) : "—",
                c1[0] - c0[0], c1[1] - c0[1], c1[2] - c0[2], c1[3] - c0[3], c1[4] - c0[4], c1[5] - c0[5], c1[6] - c0[6], c1[7] - c0[7], c1[8] - c0[8],
                c1[9] - c0[9], c1[10] - c0[10], c1[11] - c0[11], c1[12] - c0[12], c1[13] - c0[13], c1[14] - c0[14], c1[15] - c0[15], c1[16] - c0[16], c1[17] - c0[17],
                s.surfLineDb(), k.stuecke, s.surfLineNearest(), k.wattsHier, k.leute, k.gehende, (System.nanoTime() - t0) / 1e9);
        System.out.println(line);
        report.add(line);
        spectrogram(new File(dir, name + ".png"), out, sr, what);
        sheet.add(new String[]{name, what});
    }

    static int[] counts(Renderer r) {
        Klang k = r.klang;
        Soundscape s = k.s;
        return new int[]{k.segel, k.winschen, k.halsen, k.signale, k.schuesse, k.hoerner, k.abschuesse, k.zerlegungen, k.knistern,
                k.schwaelle, k.brecher, (int) s.rigSlaps(), (int) s.flagSlaps(), (int) s.footsteps(), k.moewen, k.schwaene, k.kormorane, k.enten};
    }

    /** Alle Spektrogramme untereinander: {@code docs/status/Klang_Phase15.png}. */
    static void uebersicht(File dir) throws Exception {
        List<BufferedImage> im = new ArrayList<>();
        for (String[] s : sheet) {
            File f = new File(dir, s[0] + ".png");
            if (f.exists()) im.add(ImageIO.read(f));
        }
        if (im.isEmpty()) return;
        int w = 1600, h = 0;
        for (BufferedImage b : im) h += b.getHeight() / 2;
        BufferedImage all = new BufferedImage(w, h + 40, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = all.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setColor(new Color(14, 18, 24));
        g.fillRect(0, 0, w, h + 40);
        g.setColor(new Color(225, 230, 235));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        g.drawString("Die Hörproben · Timmendorfer Strand · Timmendorf Phase 15 (Spektrogramme 30 Hz bis 16 kHz, Pegel darunter)", 14, 27);
        int y = 40;
        for (BufferedImage b : im) {
            g.drawImage(b, 0, y, w, b.getHeight() / 2, null);
            y += b.getHeight() / 2;
        }
        g.dispose();
        File out = new File("docs/status/Klang_Phase15.png");
        out.getParentFile().mkdirs();
        ImageIO.write(all, "png", out);
        System.out.println("Übersicht: " + out.getPath());
    }

    static void spectrogram(File png, float[] st, int sr, String what) throws Exception {
        int W = 1600, H = 520, top = 56, left = 70;
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(14, 18, 24));
        g.fillRect(0, 0, W, H);
        double lvl = 20 * Math.log10(Analysis.rms(st, sr, 0, st.length / 2.0 / sr) + 1e-9);
        Analysis.spectrogram(g, st, sr, left, top, W - left - 20, H - top - 70, lvl - 30);
        g.setColor(new Color(225, 230, 235));
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
        g.drawString(what.length() > 175 ? what.substring(0, 172) + " …" : what, left, 24);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        double fMin = 30, fMax = Math.min(16_000, sr / 2.0);
        int ph = H - top - 70;
        for (double f : new double[]{50, 100, 200, 500, 1000, 2000, 5000, 10000}) {
            int y = top + (int) ((ph - 1) * (1 - Math.log(f / fMin) / Math.log(fMax / fMin)));
            g.drawString(f >= 1000 ? (int) (f / 1000) + " kHz" : (int) f + " Hz", 8, y + 4);
        }
        double dur = st.length / 2.0 / sr;
        for (int s = 0; s <= (int) dur; s += 5) {
            int x = left + (int) ((W - left - 20) * s / dur);
            g.drawString(s + " s", x - 8, H - 50);
        }
        g.setColor(new Color(255, 210, 90));
        int px = -1, py = 0;
        for (double t = 0; t < dur - 0.25; t += 0.25) {
            double db = 20 * Math.log10(Analysis.rms(st, sr, t, 0.25) + 1e-9);
            int x = left + (int) ((W - left - 20) * t / dur), y = H - 8 - (int) Math.max(0, Math.min(30, (db + 70) / 70 * 30));
            if (px >= 0) g.drawLine(px, py, x, y);
            px = x; py = y;
        }
        g.dispose();
        ImageIO.write(img, "png", png);
    }
}
