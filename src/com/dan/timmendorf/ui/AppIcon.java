package com.dan.timmendorf.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Das Programmsymbol (F31 A): die Maritim Seebrücke mit ihrer Schleife, die vom Strand in die Bucht läuft, rechts ein
 * Segel, dahinter der Abendhimmel über der Ostsee mit dem aufgehenden Mond, Goldkante wie {@code heidelberg.ico}. Phase 1
 * malt es, Phase 19 legt die PNGs ins Jar ({@code com/dan/timmendorf/ui/icon/timmendorf_NN.png}) und schreibt
 * {@code Timmendorf.ico} für die Verknüpfung. Fehlt eine Größe, malt die App sie selbst.
 */
public final class AppIcon {
    public static final int[] SIZES = {16, 24, 32, 48, 64, 128, 256};

    private AppIcon() { }

    public static List<Image> images() {
        List<Image> l = new ArrayList<>();
        for (int s : SIZES) l.add(load(s));
        return l;
    }

    public static BufferedImage load(int size) {
        try (InputStream in = AppIcon.class.getResourceAsStream("icon/timmendorf_" + size + ".png")) {
            if (in != null) {
                BufferedImage b = javax.imageio.ImageIO.read(in);
                if (b != null) return b;
            }
        } catch (java.io.IOException e) { /* gemaltes Symbol */ }
        return paint(size);
    }

    /** Das Symbol, in jeder Größe neu gemalt. */
    public static BufferedImage paint(int s) {
        BufferedImage im = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = im.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        double r = s * 0.18, inset = Math.max(1, s * 0.035);
        // Goldkante
        g.setPaint(new GradientPaint(0, 0, new Color(240, 210, 120), s, s, new Color(150, 105, 30)));
        g.fill(new RoundRectangle2D.Double(0, 0, s, s, r * 2, r * 2));
        RoundRectangle2D inner = new RoundRectangle2D.Double(inset, inset, s - 2 * inset, s - 2 * inset, r * 1.7, r * 1.7);
        g.setClip(inner);
        // Abendhimmel über der Bucht: oben Nachtblau, an der Kimm Dämmerungsviolett und Gold
        double kimm = s * 0.56;
        g.setPaint(new GradientPaint(0, 0, new Color(18, 32, 62), 0, (float) kimm, new Color(214, 150, 120)));
        g.fill(new Rectangle2D.Double(0, 0, s, kimm + 1));
        // der Mond geht über der See auf (der Strand schaut nach Nordosten)
        if (s >= 24) {
            double mr = s * 0.06;
            g.setColor(new Color(250, 236, 200, 235));
            g.fill(new Ellipse2D.Double(s * 0.80 - mr, kimm - mr * 2.6, mr * 2, mr * 2));
        }
        // Ostsee
        g.setPaint(new GradientPaint(0, (float) kimm, new Color(58, 96, 126), 0, s, new Color(18, 40, 58)));
        g.fill(new Rectangle2D.Double(0, kimm, s, s - kimm));
        if (s >= 32) {
            g.setColor(new Color(250, 230, 190, 90));
            for (int i = 0; i < 5; i++) {
                double y = kimm + s * 0.03 + i * s * 0.035, w = s * (0.05 + 0.025 * i);
                g.fill(new Rectangle2D.Double(s * 0.80 - w / 2, y, w, Math.max(1, s * 0.012)));
            }
        }
        // Strand vorn links
        Path2D strand = new Path2D.Double();
        strand.moveTo(-1, s * 0.80);
        strand.curveTo(s * 0.25, s * 0.84, s * 0.45, s * 0.93, s * 0.60, s + 1);
        strand.lineTo(-1, s + 1);
        strand.closePath();
        g.setColor(new Color(217, 199, 158));
        g.fill(strand);
        // die Seebrücke: vom Strand schräg hinaus, am Kopf die Schleife
        double x0 = s * 0.10, y0 = s * 0.86, x1 = s * 0.50, y1 = s * 0.64;
        float bw = (float) Math.max(1.2, s * 0.045);
        g.setStroke(new BasicStroke(bw, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(201, 164, 124));
        g.draw(new java.awt.geom.Line2D.Double(x0, y0, x1, y1));
        g.setStroke(new BasicStroke((float) Math.max(1, s * 0.032)));
        double lw = s * 0.20, lh = s * 0.075;
        g.draw(new Ellipse2D.Double(x1 - lw * 0.15, y1 - lh * 0.75, lw, lh));
        if (s >= 48) {
            g.setColor(new Color(90, 72, 54));
            g.setStroke(new BasicStroke((float) Math.max(1, s * 0.01)));
            for (int i = 1; i < 8; i++) {
                double f = i / 8.0, px = x0 + (x1 - x0) * f, py = y0 + (y1 - y0) * f;
                g.draw(new java.awt.geom.Line2D.Double(px, py + bw / 2, px, py + bw / 2 + s * 0.04 * (1 - f * 0.6)));
            }
        }
        // ein Segel rechts: Rumpf, Großsegel und Fock
        double mx = s * 0.74, my = s * 0.76;
        Path2D gross = new Path2D.Double();
        gross.moveTo(mx, my - s * 0.40);
        gross.curveTo(mx + s * 0.10, my - s * 0.26, mx + s * 0.15, my - s * 0.12, mx + s * 0.16, my - s * 0.03);
        gross.lineTo(mx, my - s * 0.03);
        gross.closePath();
        g.setColor(new Color(244, 244, 238));
        g.fill(gross);
        Path2D fock = new Path2D.Double();
        fock.moveTo(mx - s * 0.015, my - s * 0.34);
        fock.lineTo(mx - s * 0.015, my - s * 0.03);
        fock.lineTo(mx - s * 0.13, my - s * 0.03);
        fock.closePath();
        g.setColor(new Color(225, 230, 236));
        g.fill(fock);
        Path2D rumpf = new Path2D.Double();
        rumpf.moveTo(mx - s * 0.16, my - s * 0.015);
        rumpf.lineTo(mx + s * 0.19, my - s * 0.015);
        rumpf.lineTo(mx + s * 0.14, my + s * 0.035);
        rumpf.lineTo(mx - s * 0.12, my + s * 0.035);
        rumpf.closePath();
        g.setColor(new Color(123, 166, 220));
        g.fill(rumpf);
        g.setClip(null);
        g.setColor(new Color(255, 236, 180, 120));
        g.setStroke(new BasicStroke((float) Math.max(0.6, s * 0.012)));
        g.draw(inner);
        g.dispose();
        return im;
    }

    /** Setzt das Symbol am Fenster und, wo unterstützt, in der Taskleiste. */
    public static void install(java.awt.Window w) {
        List<Image> l = images();
        w.setIconImages(l);
        try {
            if (java.awt.Taskbar.isTaskbarSupported()) {
                java.awt.Taskbar tb = java.awt.Taskbar.getTaskbar();
                if (tb.isSupported(java.awt.Taskbar.Feature.ICON_IMAGE)) tb.setIconImage(l.get(l.size() - 1));
            }
        } catch (Exception | Error ignored) {
            // Windows nimmt ohnehin die Fenstersymbole
        }
    }
}
