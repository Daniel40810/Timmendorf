package com.dan.timmendorf.ui;

import com.dan.fstyle.FTheme;

import java.awt.Color;

/**
 * Die Farben der Oberfläche aus FTheme (Bedienelemente in FTheme-Farben, die Szene hat ihren eigenen Look; aus
 * Sylt und Brocken). Liefert das Thema keine dunklen Farben, nimmt die App den Abend über der Lübecker Bucht, damit der
 * Rand um die Szene nicht hell aufleuchtet. Dazu die Farben des Werkbuchs: Ostsee, Sand, Segeltuch, Strandkorbblau,
 * Feuerwerk, Kiefer, Dämmerung, Seebrückenholz und Abendgold.
 */
public final class Theme {
    private Theme() { }

    public static final Color NACHT = new Color(13, 18, 25), SEE = new Color(127, 182, 198), SAND = new Color(217, 199, 158),
            SEGEL = new Color(238, 240, 234), KORB = new Color(123, 166, 220), FEUER = new Color(240, 160, 75), KIEFER = new Color(147, 180, 142),
            ABEND = new Color(181, 155, 216), HOLZ = new Color(201, 164, 124), GOLD = new Color(232, 178, 92);

    public static Color bg() { return pick(FTheme::getBackground, NACHT, Role.DARK); }
    public static Color surface() { return pick(FTheme::getSurface, new Color(20, 27, 36), Role.DARK); }
    public static Color ink() { return pick(FTheme::getText, new Color(233, 236, 234), Role.LIGHT); }
    public static Color muted() { return pick(FTheme::getTextMuted, new Color(147, 160, 162), Role.LIGHT); }
    public static Color accent() { return pick(FTheme::getAccent, SEE, Role.ANY); }
    public static Color status() { return new Color(9, 12, 17); }
    public static Color line() { return new Color(37, 49, 64); }

    private enum Role { DARK, LIGHT, ANY }

    private static Color pick(java.util.function.Function<FTheme, Color> f, Color fallback, Role role) {
        try {
            Color c = f.apply(FTheme.getInstance());
            if (c == null) return fallback;
            int lum = (c.getRed() * 3 + c.getGreen() * 6 + c.getBlue()) / 10;
            if (role == Role.DARK && lum > 70) return fallback;
            if (role == Role.LIGHT && lum < 120) return fallback;
            return c;
        } catch (RuntimeException | Error e) {
            return fallback;
        }
    }

    /** Die Farbleiste des Werkbuchs (tiefe Bucht bis Feuerwerk) mit ihren Anteilen, für Startbild und Symbol. */
    public static final Color[] BAND = {new Color(31, 77, 102), SEE, SEGEL, SAND, KORB, SEGEL, HOLZ, KIEFER, ABEND, FEUER};
    public static final double[] BAND_AT = {0, 0.18, 0.30, 0.36, 0.50, 0.58, 0.62, 0.72, 0.82, 0.92, 1};
}
