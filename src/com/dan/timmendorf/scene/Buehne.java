package com.dan.timmendorf.scene;

import java.util.Locale;

/**
 * Die Bühne von Phase 1: ein gerechneter Platzhalter für das Gelände, bis Phase 2 DGM1, bDOM, DOP20 und den Grund aus
 * BSH, EMODnet und GEBCO aufbereitet ({@code daten/gelaende}). Sie folgt der Küstenlinie aus OpenStreetMap
 * ({@link Site#KUESTE}) und ist sonst gestaltet [G], damit Ansichten, Marken und Kamera in Phase 1 schon am richtigen
 * Ort stehen. Quer zur Küste, gemessen als Abstand s von der Küstenlinie (landwärts positiv):
 * <ul>
 * <li><b>Bucht</b> (s &lt; 0): h = −H∞·(1 − e^(s/L)) mit H∞ = 19 m und L = 2,4 km [G], so dass die Bucht 3 km draußen rund
 * −13 m und weit draußen −19 m erreicht (GEBCO: bis −22 m, im Mittel −12 m); vor dem Strand ein flacher Vorstrand (1:40)
 * und zwei Sandriffe rund 70 und 160 m vor der Küste (im DOP20 bis etwa 3 m Tiefe sichtbar, Lage [G]).</li>
 * <li><b>Strand</b> (0 bis 40 m): steigt auf rund NHN +2,2 m; am Brodtener Ufer nur 12 m schmal.</li>
 * <li><b>Ort und Hinterland</b>: die Promenade auf rund +3 m, dahinter flach ansteigend auf rund +12 m nach 2 km, mit
 * Hügeln der Grundmoräne (bis rund 40 m im Hinterland) [G].</li>
 * <li><b>Brodtener Ufer</b> zwischen Niendorf und Travemünde: eine Wand von rund 25 m Breite bis zur Oberkante von bis zu
 * +20 m [G, Höhe zu prüfen].</li>
 * <li><b>Hemmelsdorfer See</b> hinter Timmendorf und die <b>Trave</b> mit der Pötenitzer Wiek als Wasser im Pegel 0, Umriss
 * nach dem Höhenbild der Rohdaten geschätzt [G].</li>
 * </ul>
 * Daraus entsteht mit {@link Terrain#ausBuehne()} das Gelände, solange {@code daten/gelaende} fehlt. Damit der Abstand zur
 * Küste nicht für jeden Punkt neu über 270 Strecken gesucht wird, liegt er in zwei Rastern vor ({@link #FELD}).
 */
public final class Buehne {
    private Buehne() { }

    /** Tiefe der Bucht weit draußen (m) und Länge, auf der der Grund abfällt (m) [G]. */
    public static final double SEE_TIEFE = 19, SEE_L = 2400;
    /** Vorstrand: Neigung und Breite (m) [G]. */
    public static final double VORSTRAND = 1 / 40.0, VORSTRAND_B = 60;
    /** Sandriffe: Abstand vor der Küste (m), Höhe über dem Profil (m), halbe Breite (m) [G]. */
    public static final double[][] RIFFE = {{-70, 0.6, 18}, {-160, 0.8, 30}};
    /** Strand: Breite (m) und Höhe an der Promenade (m NHN) [G]. */
    public static final double STRAND_B = 40, STRAND_H = 2.2, PROMENADE_H = 3.0;
    /** Brodtener Ufer: Ostwerte, zwischen denen das Kliff steht, und seine größte Höhe (m NHN) [G]. */
    public static final double KLIFF_E0 = 620_500, KLIFF_E1 = 623_300, KLIFF_H = 20, KLIFF_B = 25;

    /** Hemmelsdorfer See: Umriss in UTM [G nach dem Höhenbild der Rohdaten, Phase 2 misst]. */
    static final double[][] HEMMELSDORF = {{616_700, 5_983_400}, {617_300, 5_983_550}, {617_650, 5_983_150}, {618_300, 5_982_950},
            {618_500, 5_982_300}, {618_000, 5_981_800}, {617_450, 5_981_200}, {616_950, 5_980_500}, {616_980, 5_979_500},
            {617_000, 5_978_600}, {616_850, 5_978_050}, {616_550, 5_978_500}, {616_300, 5_979_500}, {616_250, 5_980_800},
            {616_400, 5_982_000}, {616_500, 5_982_900}};
    /** Pötenitzer Wiek [G]. */
    static final double[][] WIEK = {{624_000, 5_980_550}, {625_200, 5_980_500}, {626_100, 5_980_200}, {626_000, 5_979_500},
            {625_300, 5_979_350}, {624_400, 5_979_600}, {623_900, 5_980_000}};
    /** Die Trave von der Mündung landeinwärts als Mittellinie, Breite rund 220 m [G]. */
    public static final double[][] TRAVE = {{623_650, 5_980_750}, {623_450, 5_980_300}, {623_250, 5_979_800}, {622_900, 5_979_200},
            {622_500, 5_978_500}, {622_000, 5_977_700}, {621_300, 5_976_800}};
    static final double TRAVE_B = 110;

    /** Tiefe (m, positiv) im Abstand d vor der Küste, ohne Riffe; die ersten 60 m mit dem Vorstrand. */
    public static double seeTiefe(double d) {
        d = Math.max(0, d);
        double vor = VORSTRAND * VORSTRAND_B * (1 - Math.exp(-d / VORSTRAND_B));
        return SEE_TIEFE * (1 - Math.exp(-d / SEE_L)) + vor;
    }

    /** Anteil des Brodtener Ufers am Ort (0 sonst, 1 mitten im Kliff). */
    public static double kliff(double e, double n) {
        return smooth(KLIFF_E0, KLIFF_E0 + 500, e) * (1 - smooth(KLIFF_E1 - 400, KLIFF_E1, e)) * smooth(5_981_000, 5_981_800, n);
    }

    /** Höhe der Bühne über NHN (m) am Ort (x, z). */
    public static double height(double x, double z) {
        double e = x + Site.E0, n = Site.N0 - z;
        double s = FELD.abstand(e, n);
        if (s < 0) {
            double d = -s, see = -seeTiefe(d);
            for (double[] r : RIFFE) {
                double q = (s - r[0]) / r[2];
                see += r[1] * Math.exp(-q * q);
            }
            return Math.max(see, -30);
        }
        double k = kliff(e, n);
        double strandB = STRAND_B + (12 - STRAND_B) * k;
        double land;
        if (s < strandB) land = STRAND_H * s / strandB;
        else {
            double q = s - strandB;
            double inland = PROMENADE_H + 9 * smooth(0, 2000, q) + 18 * smooth(1500, 7000, q);
            double huegel = (6 + 12 * smooth(500, 5000, q)) * (noise(e / 650, n / 650) - 0.45) + 3 * (noise(e / 170 + 9, n / 170) - 0.5);
            land = inland + huegel * smooth(80, 600, q);
            if (k > 0) {
                double wand = smooth(0, KLIFF_B, q), top = KLIFF_H * (0.75 + 0.25 * noise(e / 400 + 3, 1.7)) + 4 * smooth(KLIFF_B, 900, q);
                land = land + (STRAND_H + (top - STRAND_H) * Math.pow(wand, 0.8) - land) * k;
            }
        }
        double w = wasser(e, n);
        if (w > 0) land = land + (-3 * smooth(0, 120, w) - land) * smooth(0, 40, w + 20);
        return land;
    }

    /** Wie tief (m) ein Ort im Hemmelsdorfer See, in der Wiek oder in der Trave liegt (negativ: so weit am Ufer draußen). */
    public static double wasser(double e, double n) {
        double a = polyAbstand(HEMMELSDORF, e, n), b = polyAbstand(WIEK, e, n);
        double t = TRAVE_B - linienAbstand(TRAVE, e, n);
        return Math.max(a, Math.max(b, t));
    }

    /** Art des Grunds der Bühne am Ort mit der Höhe y (für die Farben bis Phase 2) [G]. */
    public static byte art(double x, double z, double y) {
        double e = x + Site.E0, n = Site.N0 - z, s = FELD.abstand(e, n);
        if (s < 0) return y > -0.4 ? Terrain.NASS : Terrain.SEE;
        if (wasser(e, n) > 0) return y < -0.5 ? Terrain.SEE : Terrain.SCHILF;
        if (wasser(e, n) > -25) return Terrain.SCHILF;
        double k = kliff(e, n), strandB = STRAND_B + (12 - STRAND_B) * k;
        if (s < strandB * 0.18) return Terrain.NASS;
        if (s < strandB) return Terrain.STRAND;
        double q = s - strandB;
        if (k > 0.3 && q < KLIFF_B) return Terrain.KLIFF;
        if (k > 0.3 && q < 160) return Terrain.WALD;
        if (q < 14) return Terrain.WEG;
        // Orte an der Küste von Haffkrug bis Niendorf und in Travemünde, in Flecken [G bis DOP20 und ALKIS in Phase 2]
        boolean ort = (q < 900 && n > 5_983_300 && e < 620_300) || (e > 622_600 && e < 624_000 && n > 5_979_400 && n < 5_981_600 && q < 1600);
        if (ort) return noise(e / 60 + 3, n / 60) > 0.38 ? Terrain.SIEDLUNG : Terrain.WIESE;
        double f = noise(e / 400 + 11, n / 400);
        if (f > 0.80) return Terrain.WALD;
        if (f > 0.74) return Terrain.GEBUESCH;
        return noise(e / 250 + 5, n / 250 + 7) > 0.35 ? Terrain.ACKER : Terrain.WIESE;
    }

    // ------------------------------------------------------------------ Abstand zur Küste als Raster

    /** Der Abstand zur Küste in zwei Rastern: 10 m über dem Fernfeld (mit Rand), 250 m bis über den Fernring. */
    public static final KuestenFeld FELD = new KuestenFeld();

    /**
     * Abstand zur Küste ({@link Site#kuestenAbstand}) vorgerechnet und bilinear gelesen. Auf einer geraden Küste ist das
     * genau; an Knicken weicht es um Bruchteile einer Zelle ab. Das feine Raster entsteht beim ersten Gebrauch.
     */
    public static final class KuestenFeld {
        static final double F_CELL = 10, G_CELL = 250, RAND = 3000;
        final double fe0 = Site.FERNFELD.eMin() - RAND, fn0 = Site.FERNFELD.nMin() - RAND;
        final int fw = (int) Math.ceil((Site.FERNFELD.breite() + 2 * RAND) / F_CELL) + 1, fh = (int) Math.ceil((Site.FERNFELD.hoehe() + 2 * RAND) / F_CELL) + 1;
        final double ge0 = Site.E0 - 60_000, gn0 = Site.N0 - 60_000;
        final int gn = (int) (120_000 / G_CELL) + 1;
        private volatile float[] fein, grob;

        /** Vorzeichenbehafteter Abstand (m): positiv an Land. */
        public double abstand(double e, double n) {
            double u = (e - fe0) / F_CELL, v = (n - fn0) / F_CELL;
            if (u >= 0 && v >= 0 && u < fw - 1 && v < fh - 1) return bilinear(fein(), fw, u, v);
            u = (e - ge0) / G_CELL;
            v = (n - gn0) / G_CELL;
            if (u >= 0 && v >= 0 && u < gn - 1 && v < gn - 1) return bilinear(grob(), gn, u, v);
            return Site.kuestenAbstand(e, n);
        }

        static double bilinear(float[] a, int w, double u, double v) {
            int i = (int) u, j = (int) v;
            double fx = u - i, fy = v - j;
            int o = j * w + i;
            return (a[o] * (1 - fx) + a[o + 1] * fx) * (1 - fy) + (a[o + w] * (1 - fx) + a[o + w + 1] * fx) * fy;
        }

        private float[] fein() {
            float[] f = fein;
            if (f != null) return f;
            synchronized (this) {
                if (fein == null) fein = raster(fe0, fn0, F_CELL, fw, fh);
                return fein;
            }
        }

        private float[] grob() {
            float[] g = grob;
            if (g != null) return g;
            synchronized (this) {
                if (grob == null) grob = raster(ge0, gn0, G_CELL, gn, gn);
                return grob;
            }
        }

        static float[] raster(double e0, double n0, double cell, int w, int h) {
            float[] a = new float[w * h];
            java.util.stream.IntStream.range(0, h).parallel().forEach(j -> {
                for (int i = 0; i < w; i++) a[j * w + i] = (float) Site.kuestenAbstand(e0 + i * cell, n0 + j * cell);
            });
            return a;
        }
    }

    // ------------------------------------------------------------------ Hilfen

    /** Abstand zum Rand eines Polygons (m): positiv innen, negativ außen. */
    static double polyAbstand(double[][] p, double e, double n) {
        boolean in = false;
        double best = Double.MAX_VALUE;
        for (int i = 0, j = p.length - 1; i < p.length; j = i++) {
            if ((p[i][1] > n) != (p[j][1] > n) && e < (p[j][0] - p[i][0]) * (n - p[i][1]) / (p[j][1] - p[i][1]) + p[i][0]) in = !in;
            best = Math.min(best, strecke(p[j], p[i], e, n));
        }
        return in ? best : -best;
    }

    /** Abstand zu einer Linie (m). */
    public static double linienAbstand(double[][] p, double e, double n) {
        double best = Double.MAX_VALUE;
        for (int i = 0; i + 1 < p.length; i++) best = Math.min(best, strecke(p[i], p[i + 1], e, n));
        return best;
    }

    static double strecke(double[] a, double[] b, double e, double n) {
        double dx = b[0] - a[0], dy = b[1] - a[1], l2 = dx * dx + dy * dy;
        double t = l2 == 0 ? 0 : Math.max(0, Math.min(1, ((e - a[0]) * dx + (n - a[1]) * dy) / l2));
        return Math.hypot(a[0] + t * dx - e, a[1] + t * dy - n);
    }

    static double smooth(double a, double b, double x) {
        double t = Math.max(0, Math.min(1, (x - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }

    /** Wertrauschen 0..1, fest (seed 2026). */
    static double noise(double x, double y) {
        long ix = (long) Math.floor(x), iy = (long) Math.floor(y);
        double fx = x - ix, fy = y - iy;
        fx = fx * fx * (3 - 2 * fx);
        fy = fy * fy * (3 - 2 * fy);
        double a = hash(ix, iy), b = hash(ix + 1, iy), c = hash(ix, iy + 1), d = hash(ix + 1, iy + 1);
        return a + (b - a) * fx + (c - a) * fy + (a - b - c + d) * fx * fy;
    }

    static double hash(long x, long y) {
        long h = 2026 * 0x9E3779B97F4A7C15L + x * 0xC2B2AE3D27D4EB4FL + y * 0x165667B19E3779F9L;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }

    /** „Kern“, „Fernfeld“, „Fernring“ oder „außerhalb“ am Ort (x, z). */
    public static String zone(double x, double z) {
        if (Site.KERN.contains(x, z)) return Site.KERN.name();
        if (Site.FERNFELD.contains(x, z)) return Site.FERNFELD.name();
        if (Math.hypot(x - Terrain.MX, z - Terrain.MZ) <= Site.FERNRING) return "Fernring";
        return "außerhalb";
    }

    /** Der nächste Ort aus {@link Site#ORTE} und sein Abstand in der Ebene: „240 m bis Wolkenlos“. */
    public static String naechsterOrt(double x, double z) {
        Site.Ort best = null;
        double bd = Double.MAX_VALUE;
        for (Site.Ort o : Site.ORTE) {
            double d = Math.hypot(x - o.x(), z - o.z());
            if (d < bd) { bd = d; best = o; }
        }
        if (best == null) return "—";
        return bd < 1000 ? String.format(Locale.GERMAN, "%.0f m bis %s", bd, best.name()) : String.format(Locale.GERMAN, "%.1f km bis %s", bd / 1000, best.name());
    }
}
