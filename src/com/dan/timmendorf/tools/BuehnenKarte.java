package com.dan.timmendorf.tools;

import com.dan.timmendorf.camera.Ansichten;
import com.dan.timmendorf.scene.Buehne;
import com.dan.timmendorf.scene.Site;
import com.dan.timmendorf.scene.Terrain;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Die Bühne von Phase 1 als Karte ohne Grafikkarte ({@code ant karte}): das Fernfeld von oben mit Schummerung, Farben
 * nach der Art des Grunds, Tiefe der Bucht, Küste aus OSM, Kern (gold), Orte und die neun Ansichten mit Blickrichtung.
 * Phase 2 vergleicht sie mit dem Höhenbild der Rohdaten ({@code docs/status/Daten_DGM1_Uebersicht.png}).
 * <pre>
 * BuehnenKarte [png] [Meter je Bildpunkt]
 * BuehnenKarte docs/status/Buehne_Phase1.png 20
 * </pre>
 */
public final class BuehnenKarte {
    private BuehnenKarte() { }

    /** Farben je Art (Terrain.ART), wie terrain.frag ungefähr. */
    static final int[][] FARBE = {{0, 0, 0}, {60, 110, 140}, {125, 140, 85}, {226, 212, 176}, {172, 152, 112}, {160, 132, 100}, {176, 170, 104},
            {52, 88, 50}, {82, 108, 62}, {122, 158, 92}, {168, 150, 140}, {205, 200, 190}};

    public static void main(String[] a) throws Exception {
        File png = new File(a.length > 0 ? a[0] : "docs/status/Buehne_Phase1.png");
        double cell = a.length > 1 ? Double.parseDouble(a[1]) : 20;
        Site.Fenster f = Site.FERNFELD;
        int w = (int) (f.breite() / cell), h = (int) (f.hoehe() / cell);
        BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        java.util.stream.IntStream.range(0, h).parallel().forEach(j -> {
            for (int i = 0; i < w; i++) {
                double e = f.eMin() + (i + .5) * cell, n = f.nMax() - (j + .5) * cell;
                double[] xz = Site.fromUtm(e, n);
                double y = Buehne.height(xz[0], xz[1]);
                int art = Buehne.art(xz[0], xz[1], y);
                double yx = Buehne.height(xz[0] + cell, xz[1]) - y, yz = Buehne.height(xz[0], xz[1] - cell) - y;
                double sh = Math.max(0.4, Math.min(1.4, 1 - (yx - yz) * 0.15));
                int[] c = FARBE[art];
                if (art == Terrain.SEE && y < 0) {
                    double d = Math.min(1, -y / 20);
                    c = new int[]{(int) (120 - 90 * d), (int) (170 - 110 * d), (int) (190 - 90 * d)};
                }
                im.setRGB(i, j, (Math.min(255, (int) (c[0] * sh)) << 16) | (Math.min(255, (int) (c[1] * sh)) << 8) | Math.min(255, (int) (c[2] * sh)));
            }
        });
        Graphics2D g = im.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font("SansSerif", Font.PLAIN, 11));
        g.setColor(new Color(255, 210, 90));
        g.setStroke(new BasicStroke(1.5f));
        g.drawRect((int) ((Site.KERN.eMin() - f.eMin()) / cell), (int) ((f.nMax() - Site.KERN.nMax()) / cell), (int) (Site.KERN.breite() / cell), (int) (Site.KERN.hoehe() / cell));
        g.setColor(Color.WHITE);
        for (Site.Ort o : Site.ORTE) {
            int x = (int) ((o.e() - f.eMin()) / cell), y = (int) ((f.nMax() - o.n()) / cell);
            g.fillOval(x - 3, y - 3, 6, 6);
            g.drawString(o.name(), x + 5, y - 3);
        }
        g.setColor(new Color(240, 160, 75));
        for (Ansichten.Ansicht v : Ansichten.ALLE) {
            double[] u = Site.toUtm(v.x(), v.z());
            int x = (int) ((u[0] - f.eMin()) / cell), y = (int) ((f.nMax() - u[1]) / cell);
            double hd = Math.toRadians(v.heading());
            g.drawLine(x, y, (int) (x + Math.sin(hd) * 28), (int) (y - Math.cos(hd) * 28));
            g.fillOval(x - 2, y - 2, 5, 5);
            g.drawString(String.valueOf(v.key()), x - 11, y + 12);
        }
        g.setColor(new Color(230, 234, 238));
        g.drawString(String.format(java.util.Locale.GERMAN, "Bühne Phase 1 · Fernfeld %,.0f × %,.0f m · %.0f m je Bildpunkt · gold: Kern · orange: Ansichten 1–9",
                f.breite(), f.hoehe(), cell), 8, h - 8);
        g.dispose();
        File dir = png.getAbsoluteFile().getParentFile();
        if (dir != null) dir.mkdirs();
        javax.imageio.ImageIO.write(im, "png", png);
        System.out.println("Bild: " + png.getPath());
    }
}
