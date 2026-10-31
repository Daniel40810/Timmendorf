package com.dan.timmendorf.tools;

import com.dan.timmendorf.camera.Ansichten;
import com.dan.timmendorf.gl.Quality;
import com.dan.timmendorf.gl.Renderer;
import com.dan.timmendorf.scene.SceneClock;
import com.dan.timmendorf.scene.Sturmflut;
import com.jogamp.opengl.GLCapabilities;
import com.jogamp.opengl.GLDrawableFactory;
import com.jogamp.opengl.GLOffscreenAutoDrawable;
import com.jogamp.opengl.GLProfile;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Seit Phase 14: die Sturmflut vom 19. bis 21. Oktober 2023 als Bildfolge (Fertig-Bedingung: „die Bildfolge passt zu den
 * belegten Pegeln“). Neun Bilder vom Anstieg über den Scheitel (1,77 m NHN am späten Abend des 20.) bis zum Tag danach;
 * darunter die Pegelkurve des Fahrplans mit den Ankern aus den Berichten und den Zeiten der Bilder.
 * <pre>
 * Sturmflutbilder [breite] [höhe] [MITTEL|HOCH|…] [ordner]
 * </pre>
 * Schreibt die Bilder in den Ordner und die Übersicht {@code docs/status/Sturmflut_Phase14.png}.
 */
public final class Sturmflutbilder {
    private Sturmflutbilder() { }

    /** Ein Bild: Zeit, Ort (Ansicht, „strand:s“, „promenade“), Blick und Neigung (NaN = wie der Ort), Titel. */
    record Bild(LocalDateTime zeit, String ort, double blick, double neigung, String titel) { }

    public static void main(String[] a) throws Exception {
        int w = a.length > 0 ? Integer.parseInt(a[0]) : 1920, h = a.length > 1 ? Integer.parseInt(a[1]) : 1080;
        Quality q = a.length > 2 ? Quality.valueOf(a[2]) : Quality.HOCH;
        File dir = new File(a.length > 3 ? a[3] : "docs/status/Sturmflut");
        dir.mkdirs();
        List<Bild> folge = List.of(
                new Bild(LocalDateTime.of(2023, 10, 19, 14, 0), "strand:220", Double.NaN, Double.NaN, "19.10. 14:00 · Ostwind, die Bucht füllt sich"),
                new Bild(LocalDateTime.of(2023, 10, 20, 8, 0), "strand:220", Double.NaN, Double.NaN, "20.10. 8:00 · über 1,25 m"),
                new Bild(LocalDateTime.of(2023, 10, 20, 15, 0), "promenade", Double.NaN, Double.NaN, "20.10. 15:00 · über 1,50 m, der Strand ist fort"),
                new Bild(LocalDateTime.of(2023, 10, 20, 20, 0), "strand:220", Double.NaN, Double.NaN, "20.10. 20:00 · Brecher an der Seebrücke"),
                new Bild(LocalDateTime.of(2023, 10, 20, 23, 0), "promenade", Double.NaN, Double.NaN, "20.10. 23:00 · Scheitel: Wasser auf der Promenade"),
                new Bild(LocalDateTime.of(2023, 10, 21, 1, 0), "Niendorfer Hafen", Double.NaN, Double.NaN, "21.10. 1:00 · Niendorfer Hafen"),
                new Bild(LocalDateTime.of(2023, 10, 21, 10, 0), "Brodtener Ufer", Double.NaN, Double.NaN, "21.10. 10:00 · Abbrüche am Brodtener Ufer"),
                new Bild(LocalDateTime.of(2023, 10, 21, 15, 0), "strand:220", Double.NaN, Double.NaN, "21.10. 15:00 · das Wasser fällt"),
                new Bild(LocalDateTime.of(2023, 10, 23, 12, 0), "Brodtener Ufer", Double.NaN, Double.NaN, "23.10. 12:00 · danach: Sturzkegel am Fuß"));

        GLProfile prof = GLProfile.get(GLProfile.GL4);
        GLCapabilities caps = new GLCapabilities(prof);
        caps.setOnscreen(false);
        caps.setFBO(true);
        caps.setDepthBits(24);
        GLOffscreenAutoDrawable d = GLDrawableFactory.getFactory(prof).createOffscreenAutoDrawable(null, caps, null, w, h);
        d.setAutoSwapBufferMode(false);
        SceneClock clock = new SceneClock(folge.get(0).zeit().toLocalDate(), 14.0);
        clock.lapse = 0;
        Renderer r = new Renderer(clock);
        r.frozen = true;
        r.setQuality(q);
        r.autoScale = false;
        r.showHud = false;
        r.wiring.feuerwerkAbends = false;
        d.addGLEventListener(r);
        d.display();
        r.physik.stop();
        List<BufferedImage> bilder = new ArrayList<>();
        List<double[]> marken = new ArrayList<>();
        int nr = 0;
        for (Bild b : folge) {
            nr++;
            long t0 = System.nanoTime();
            LocalDateTime z = b.zeit();
            clock.set(z.toLocalDate(), z.getHour() + z.getMinute() / 60.0);
            r.wiring.sturmflutSchritt();
            r.wiring.jahr.step();
            double lux = com.dan.timmendorf.wiring.Wiring.umgebungsLux(clock.sun()[0]);
            r.wiring.lichter.step(0, lux, r.wiring.stunde());
            r.wiring.leuchtfeuer.step(0, lux);
            stelle(r, b);
            clock.lapse = 1;
            for (int i = 0; i < 200; i++) r.step(0.1);   // Wolken, Regen, Brecher einschwingen (20 s)
            clock.lapse = 0;
            r.wiring.sturmflutSchritt();
            for (int i = 0; i < 120; i++) {   // die Maske der See folgt dem Pegel in einem eigenen Faden
                r.step(1 / 30.0);
                d.display();
                if (i > 40 && r.seeMaskeFertig()) break;
                if (!r.seeMaskeFertig()) Thread.sleep(30);
            }
            final BufferedImage[] img = new BufferedImage[1];
            d.invoke(true, x -> { img[0] = r.grab(x); return true; });
            File f = new File(dir, String.format(Locale.ROOT, "%02d_%02d%02d_%02d00.png", nr, z.getDayOfMonth(), z.getMonthValue(), z.getHour()));
            ImageIO.write(img[0], "png", f);
            double sh = Sturmflut.stunde(clock.epochMillis());
            marken.add(new double[]{sh, r.wiring.pegel});
            String titel = String.format(Locale.GERMAN, "%s · Pegel %.2f m NHN", b.titel(), r.wiring.pegel);
            bilder.add(Bildfolge.beschriften(img[0], titel));
            System.out.printf(Locale.GERMAN, "%s  %s  (%.1f s)%n    %s%n    %s%n", f.getPath(), titel, (System.nanoTime() - t0) / 1e9, r.wiring.wetterZeile(), r.sturmflutZeile());
            System.out.println("    Durchgänge (ms): " + r.passTimes());
        }
        BufferedImage oben = Bildfolge.uebersicht(bilder, "Die Sturmflut vom 19. bis 21. Oktober 2023 · Timmendorfer Strand · Timmendorf Phase 14");
        BufferedImage kurve = pegelKurve(oben.getWidth(), 300, marken);
        BufferedImage all = new BufferedImage(oben.getWidth(), oben.getHeight() + kurve.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = all.createGraphics();
        g.drawImage(oben, 0, 0, null);
        g.drawImage(kurve, 0, oben.getHeight(), null);
        g.dispose();
        File sheet = new File("docs/status/Sturmflut_Phase14.png");
        ImageIO.write(all, "png", sheet);
        System.out.println("Übersicht: " + sheet.getPath());
        if (r.error() != null) System.err.println("FEHLER: " + r.error());
        d.destroy();
        System.exit(r.error() == null ? 0 : 1);
    }

    static void stelle(Renderer r, Bild b) {
        if (b.ort().equals("promenade")) { r.zurSturmflut(true); return; }
        if (b.ort().startsWith("strand:")) { r.zurSturmflut(false); return; }
        Ansichten.Ansicht v = Ansichten.byName(b.ort());
        if (v != null) r.placeView(v);
    }

    /**
     * Die Pegelkurve des Fahrplans (Stunde 0 = 18.10. 0:00) mit den belegten Ankern (Scheitel 1,77 m; Dauer über 1,25 / 1,50 /
     * 1,75 m wie in Lübeck-Bauhof: 26 / 15 / 3 h) und den Zeitpunkten der Bilder. Ohne Grafikkarte, für die Übersicht und den
     * Statusbericht.
     */
    public static BufferedImage pegelKurve(int w, int h, List<double[]> marken) {
        BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = im.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Color bg = new Color(11, 17, 24), ink = new Color(230, 234, 238), dim = new Color(120, 136, 150), grid = new Color(36, 46, 58);
        g.setColor(bg);
        g.fillRect(0, 0, w, h);
        int x0 = 70, x1 = w - 30, y0 = 40, y1 = h - 40;
        double hmax = Sturmflut.STUNDEN, pmin = -0.2, pmax = 2.0;
        java.util.function.DoubleUnaryOperator X = s -> x0 + (x1 - x0) * s / hmax, Y = p -> y1 - (y1 - y0) * (p - pmin) / (pmax - pmin);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        for (double p = 0; p <= 2.0; p += 0.25) {
            int y = (int) Y.applyAsDouble(p);
            g.setColor(p == 1.0 || p == 1.5 ? new Color(70, 60, 40) : grid);
            g.drawLine(x0, y, x1, y);
            g.setColor(dim);
            g.drawString(String.format(Locale.GERMAN, "%.2f", p), 20, y + 4);
        }
        for (int d = 0; d <= 5; d++) {
            int x = (int) X.applyAsDouble(d * 24);
            g.setColor(grid);
            g.drawLine(x, y0, x, y1);
            g.setColor(dim);
            g.drawString((18 + d) + ".10.", x + 4, y1 + 16);
        }
        g.setStroke(new BasicStroke(2f));
        g.setColor(new Color(90, 170, 255));
        int px = -1, py = -1;
        for (double s = 0; s <= hmax; s += 0.5) {
            int x = (int) X.applyAsDouble(s), y = (int) Y.applyAsDouble(Sturmflut.pegel(s));
            if (px >= 0) g.drawLine(px, py, x, y);
            px = x; py = y;
        }
        // belegt: Scheitel und die Dauern über den Schwellen
        g.setColor(new Color(240, 160, 75));
        int sx = (int) X.applyAsDouble(Sturmflut.SCHEITEL_H), sy = (int) Y.applyAsDouble(Sturmflut.SCHEITEL);
        g.fillOval(sx - 5, sy - 5, 10, 10);
        g.drawString("Scheitel Travemünde 1,77 m NHN (belegt)", sx + 10, sy - 6);
        double[][] dauer = {{1.25, 26}, {1.50, 15}, {1.75, 3}};
        for (double[] dd : dauer) {
            int y = (int) Y.applyAsDouble(dd[0]);
            int xa = (int) X.applyAsDouble(Sturmflut.SCHEITEL_H - dd[1] / 2), xb = (int) X.applyAsDouble(Sturmflut.SCHEITEL_H + dd[1] / 2);
            g.drawLine(xa, y + 3, xb, y + 3);
            g.drawString(String.format(Locale.GERMAN, "%.0f h über %.2f m (Lübeck-Bauhof, belegt) · Fahrplan %.0f h", dd[1], dd[0], Sturmflut.stundenUeber(dd[0])), xb + 8, y + 7);
        }
        g.setColor(ink);
        for (int i = 0; i < marken.size(); i++) {
            double[] m = marken.get(i);
            if (Double.isNaN(m[0])) continue;
            int x = (int) X.applyAsDouble(m[0]), y = (int) Y.applyAsDouble(m[1]);
            g.drawLine(x, y - 8, x, y + 8);
            g.drawString(String.valueOf(i + 1), x - 4, y - 12);
        }
        g.setColor(dim);
        g.drawString("Pegel Travemünde im Fahrplan (blau), belegte Anker (orange), Bilder (Nummern) · Stunden gestaltet [G], Anker nach BAW (Die Küste 94) und BSH", x0, 22);
        g.dispose();
        return im;
    }
}
