package com.dan.timmendorf.scene;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.function.DoubleBinaryOperator;

/**
 * Die Bauwerke am Wasser (seit Phase 9), von Hand gebaut über den Häusern aus LOD2 (F8 A): die Seebrücken und Stege aus
 * OpenStreetMap ({@code daten/bauwerke/bauwerke.tsv}, Schritt {@code bauwerke} der Aufbereitung) mit Deck, Pfählen,
 * Geländer und Laternen; die Maritim Seebrücke mit ihrer Schleife, die Seeschlösschenbrücke mit dem Teehaus Wolkenlos,
 * die Seebrücke Niendorf (fehlt in OSM, nach dem Luftbild), die Türme der DLRG, die Laternen der Promenade, die Kutter im
 * Niendorfer Hafen, der Alte Leuchtturm Travemünde und die Plätze der Strandkörbe. Alles als {@link Baunetz} für {@code bauten.vert/frag};
 * die Körbe selbst zeichnet seit Phase 20 {@code com.dan.beachchair} ({@link #korb()});
 * die Häuser aus LOD2 an denselben Stellen (Wolkenlos, Alter Leuchtturm, Hütten der DLRG) nimmt {@link #ersetzt} heraus.
 * <p>
 * Maße [G nach den Fotos Seebruecke1–4, Wolkenlos1–2, Strandkorb1–2, Hafen1–2 und dem DOP20 2025]: Deck 2,6 m NHN (wie
 * das Fußnetz), Geländer 1,05 m, Pfähle alle 4 m, Laternen alle 24 m, 4,2 m hoch.
 */
public final class Bauwerke {
    /** Eine Brücke oder ein Steg: Art {@code bruecke} (Fläche) oder {@code steg} (Linie), Name, Merkmale, Punkte x, z. */
    public record Bauwerk(String art, String name, Map<String, String> merkmale, double[][] p) {
        public boolean flaeche() { return art.equals("bruecke"); }
        public boolean schwimmt() { return "yes".equals(merkmale.get("floating")); }
        public String m(String k, String v) { return merkmale.getOrDefault(k, v); }
        /** Umfang (Fläche) oder Länge (Steg) in m. */
        public double laenge() {
            double l = 0;
            for (int i = 0; i + 1 < p.length; i++) l += Math.hypot(p[i + 1][0] - p[i][0], p[i + 1][1] - p[i][1]);
            return l;
        }
    }

    /** Eine Wache oder ein Turm der DLRG. */
    public record Wache(String name, boolean turm, double x, double z) { }

    /** Ein Leuchtfeuer aus OSM: Name, Lichter („Oc red 4 12.3 5.5 123-201“), Lage. */
    public record Feuer(String name, List<String> lichter, double x, double z) { }

    /** Deck der Seebrücken (m NHN), aus dem Fußnetz (Kanten 3288 ff. der Maritim Seebrücke). */
    public static final double DECK = 2.6;
    /** Der Alte Leuchtturm Travemünde (LOD2-Mitte), Höhe 31 m (OSM); Brennpunkt der Laterne. */
    public static final double ALT_X = 6472, ALT_Z = 4304, ALT_FEUER = 23.9, ALT_LINSE = 0.8;
    /** Wolkenlos: Mitte des Hauses aus LOD2 und Richtung der Seeschlösschenbrücke nach See (DOP20). */
    public static final double WOLKENLOS_X = 752, WOLKENLOS_Z = 544.5, WOLKENLOS_RICHTUNG = 30.5;
    /** Kutter im Niendorfer Hafen: 4 m vor der Nordkaje des Ostarms (DOP20, Grund dort −0,9 bis −3,4 m). */
    public static final double[][] KUTTER = {{1991, 791, 11}, {2013, 798, 13}, {2039, 805, 12}, {2065, 813, 14}, {2095, 822, 11}, {2119, 829, 12}, {2145, 837, 10}};

    public final List<Bauwerk> bauwerke = new ArrayList<>();
    public final List<Wache> wachen = new ArrayList<>();
    public final List<Feuer> feuer = new ArrayList<>();
    public final String quelle;

    private Bauwerke(String q) { quelle = q; }

    public static Bauwerke leer() {
        Bauwerke b = new Bauwerke("ohne daten/bauwerke");
        b.vonHand();
        return b;
    }

    /** Liest {@code daten/bauwerke/bauwerke.tsv}; dazu die Bauwerke, die in OSM fehlen. */
    public static Bauwerke laden(File daten) {
        File f = new File(daten, "bauwerke/bauwerke.tsv");
        if (!f.isFile()) return leer();
        Bauwerke b = new Bauwerke(f.getPath());
        try (BufferedReader r = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) {
            for (String s; (s = r.readLine()) != null; ) {
                if (s.isBlank() || s.startsWith("#")) continue;
                String[] c = s.split("\t", -1);
                if (c.length < 4) continue;
                double[][] p = punkte(c[3]);
                switch (c[0]) {
                    case "bruecke", "steg" -> {
                        Map<String, String> m = new HashMap<>();
                        for (String kv : c[2].split("\\|")) { int k = kv.indexOf('='); if (k > 0) m.put(kv.substring(0, k), kv.substring(k + 1)); }
                        b.bauwerke.add(new Bauwerk(c[0], c[1], m, p));
                    }
                    case "dlrg" -> b.wachen.add(new Wache(c[1], c[2].equals("turm"), p[0][0], p[0][1]));
                    case "feuer" -> {
                        List<String> l = new ArrayList<>();
                        for (String kv : c[2].split("\\|")) if (kv.startsWith("licht=")) l.add(kv.substring(6));
                        b.feuer.add(new Feuer(c[1], l, p[0][0], p[0][1]));
                    }
                    default -> { }
                }
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("Timmendorf: Bauwerke nicht lesbar: " + e.getMessage());
        }
        b.vonHand();
        return b;
    }

    /** Was OSM nicht hat: die Seebrücke Niendorf [G nach DOP20 2025]: Steg 4,5 m breit, runder Kopf mit 10 m Halbmesser. */
    private void vonHand() {
        if (bauwerke.stream().noneMatch(w -> w.name().equals("Seebrücke Niendorf"))) {
            bauwerke.add(new Bauwerk("steg", "Seebrücke Niendorf", Map.of("width", "4.5", "surface", "wood"),
                    new double[][]{{3248, 856}, {3270, 790}, {3292, 724}}));
            double[][] kopf = new double[25][];
            for (int i = 0; i <= 24; i++) {
                double a = 2 * Math.PI * i / 24;
                kopf[i] = new double[]{3299 + 9.5 * Math.sin(a), 702 - 11 * Math.cos(a)};
            }
            bauwerke.add(new Bauwerk("bruecke", "Seebrücke Niendorf", Map.of("surface", "wood"), kopf));
        }
    }

    private static double[][] punkte(String s) {
        String[] t = s.trim().split(" ");
        double[][] p = new double[t.length][];
        for (int i = 0; i < t.length; i++) {
            int k = t[i].indexOf(',');
            p[i] = new double[]{Double.parseDouble(t[i].substring(0, k)), Double.parseDouble(t[i].substring(k + 1))};
        }
        return p;
    }

    // ------------------------------------------------------------------ was LOD2 nicht zeigen soll

    /** Ob ein Haus aus LOD2 durch ein Bauwerk von hier ersetzt ist: Wolkenlos, Alter Leuchtturm, Hütten der DLRG. */
    public boolean ersetzt(Bauten.Haus h) {
        if (Math.hypot(h.x() - WOLKENLOS_X, h.z() - WOLKENLOS_Z) < 14) return true;
        if (Math.hypot(h.x() - ALT_X, h.z() - ALT_Z) < 5) return true;
        if (h.hoeheUeberGrund() < 7) for (Wache w : wachen) if (w.turm() && Math.hypot(h.x() - w.x(), h.z() - w.z()) < 5) return true;
        return false;
    }

    // ------------------------------------------------------------------ Netz

    /** Zähler nach dem Bau: Brücken, Stege, Pfähle, Laternen der Brücken, Laternen der Promenade, Türme, Kutter. */
    public final int[] zahl = new int[7];

    /**
     * Das feste Netz: Brücken und Stege, Wolkenlos, Seebrücke Niendorf, DLRG, Laternen der Promenade (aus dem Fußnetz,
     * {@code null}: keine), Kutter, Alter Leuchtturm. {@code grund}: Höhe des Geländes (m NHN).
     */
    public Baunetz netz(DoubleBinaryOperator grund, Netz fuss) {
        java.util.Arrays.fill(zahl, 0);
        Baunetz n = new Baunetz();
        for (Bauwerk w : bauwerke) {
            if (w.flaeche()) bruecke(n, w, grund);
            else steg(n, w, grund);
            zahl[w.flaeche() ? 0 : 1]++;
        }
        wolkenlos(n, grund.applyAsDouble(WOLKENLOS_X, WOLKENLOS_Z));
        for (Wache w : wachen) if (w.turm()) { dlrgTurm(n, w.x(), w.z(), grund); zahl[5]++; }
        if (fuss != null) promenade(n, fuss, grund);
        for (double[] k : KUTTER) { kutter(n, k[0], k[1], 107 + (k[2] % 3 - 1) * 2, k[2], (int) k[2]); zahl[6]++; }
        alterLeuchtturm(n, grund.applyAsDouble(ALT_X, ALT_Z));
        return n;
    }

    private static int m(int mat, double grund, int kennung) { return Baunetz.merkmal(mat, false, kennung, grund); }

    private static boolean benannt(Bauwerk w) {
        String s = w.name().toLowerCase(Locale.ROOT);
        return s.contains("seebrücke") || s.contains("seeschlösschen") || s.contains("brücke");
    }

    /** Seebrücken tragen ihr Deck auf 2,6 m, Stege in Häfen und an der Trave auf 1,4 m [G]; schwimmende Stege auf 0,45 m. */
    static double deckHoehe(Bauwerk w) {
        String s = w.name().toLowerCase(Locale.ROOT);
        return s.contains("seebrücke") || s.contains("seeschlösschen") ? DECK : STEG;
    }

    public static final double STEG = 1.4;

    private static double deck(Bauwerk w, double g) { return w.schwimmt() ? 0.45 : Math.max(deckHoehe(w), g + 0.25); }

    /** Eine Brücke als Fläche: Deck (Ohrenschnitt), Stirnbrett, Pfähle am Rand, Geländer über Wasser und Strand, Laternen. */
    private void bruecke(Baunetz n, Bauwerk w, DoubleBinaryOperator grund) {
        double[][] p = offen(w.p());
        if (p.length < 3) return;
        double flaeche = flaeche(p);
        int[] tri = ohren(p, flaeche > 0);
        double[] y = new double[p.length];
        for (int i = 0; i < p.length; i++) y[i] = deck(w, grund.applyAsDouble(p[i][0], p[i][1]));
        int holz = m(Baunetz.HOLZ, DECK, 17), stahl = m(w.name().equals("Neue Seebrücke") ? Baunetz.EISEN : Baunetz.HOLZ, -3, 23);
        int lack = m(benannt(w) ? Baunetz.LACK : Baunetz.HOLZ, DECK, 5);
        for (int t = 0; t + 2 < tri.length; t += 3) {
            int a = tri[t], b = tri[t + 1], c = tri[t + 2];
            n.dreieck(Baunetz.p(p[a][0], y[a], p[a][1]), Baunetz.p(p[b][0], y[b], p[b][1]), Baunetz.p(p[c][0], y[c], p[c][1]), new double[]{0, 1, 0}, holz);
        }
        double s = Math.signum(flaeche), lauf = 0, naechste = 12;
        for (int i = 0; i < p.length; i++) {
            int j = (i + 1) % p.length;
            double ax = p[i][0], az = p[i][1], bx = p[j][0], bz = p[j][1], l = Math.hypot(bx - ax, bz - az);
            if (l < 1e-3) continue;
            double dx = (bx - ax) / l, dz = (bz - az) / l, ix = -dz * s, iz = dx * s;   // nach innen
            // Stirnbrett
            n.viereck(Baunetz.p(ax, y[i] - 0.35, az), Baunetz.p(bx, y[j] - 0.35, bz), Baunetz.p(bx, y[j], bz), Baunetz.p(ax, y[i], az), holz);
            double gm = grund.applyAsDouble((ax + bx) / 2, (az + bz) / 2);
            boolean wasser = gm < Math.min(y[i], y[j]) - 0.6;
            // Pfähle alle 4 m, 0,3 m nach innen
            for (double u = 2; u < l && !w.schwimmt(); u += 4) {
                double x = ax + dx * u + ix * 0.3, z = az + dz * u + iz * 0.3, g = grund.applyAsDouble(x, z), yd = y[i] + (y[j] - y[i]) * u / l;
                if (g < yd - 0.8 && g > -9) { n.prisma(x, z, g - 0.5, yd - 0.35, 0.17, 0.17, 6, 0, true, false, false, stahl); zahl[2]++; n.pfaehle.add(new double[]{x, z, g, yd, 0.17}); }
            }
            if (!wasser || w.schwimmt()) continue;
            gelaender(n, ax + ix * 0.1, az + iz * 0.1, y[i], bx + ix * 0.1, bz + iz * 0.1, y[j], lack);
            // Laternen auf den Seebrücken alle 24 m
            if (benannt(w) && w.laenge() > 60) {
                while (lauf + l > naechste) {
                    double u = naechste - lauf;
                    laterne(n, ax + dx * u + ix * 0.4, az + dz * u + iz * 0.4, y[i] + (y[j] - y[i]) * u / l);
                    zahl[3]++;
                    naechste += 24;
                }
            }
            lauf += l;
        }
    }

    /** Ein Steg als Linie: Deck mit Breite (OSM width, sonst 2,5 m), Pfähle an beiden Seiten, Geländer bei Brücken. */
    private void steg(Baunetz n, Bauwerk w, DoubleBinaryOperator grund) {
        double b = 2.5;
        try { b = Double.parseDouble(w.m("width", "2.5").replace(',', '.').replaceAll("[^0-9.]", "")); } catch (NumberFormatException ignored) { }
        b = Math.max(1.2, Math.min(8, b));
        double[][] p = w.p();
        int holz = m(Baunetz.HOLZ, DECK, 41), pf = m(Baunetz.HOLZ, -3, 29), lack = m(Baunetz.LACK, DECK, 5);
        boolean rand = benannt(w);
        for (int i = 0; i + 1 < p.length; i++) {
            double ax = p[i][0], az = p[i][1], bx = p[i + 1][0], bz = p[i + 1][1], l = Math.hypot(bx - ax, bz - az);
            if (l < 1e-3) continue;
            double dx = (bx - ax) / l, dz = (bz - az) / l, qx = -dz * b / 2, qz = dx * b / 2;
            // Stücke ganz an Land (Kaimauern, Wege, die OSM als Steg führt) bleiben weg
            double ga = grund.applyAsDouble(ax, az), gb = grund.applyAsDouble(bx, bz), gm = grund.applyAsDouble((ax + bx) / 2, (az + bz) / 2);
            if (!w.schwimmt() && Math.min(ga, Math.min(gb, gm)) > deckHoehe(w) - 0.5) continue;
            double ya = deck(w, ga), yb = deck(w, gb);
            double[] a0 = Baunetz.p(ax - qx, ya, az - qz), a1 = Baunetz.p(ax + qx, ya, az + qz), b0 = Baunetz.p(bx - qx, yb, bz - qz), b1 = Baunetz.p(bx + qx, yb, bz + qz);
            n.dreieck(a0, b0, b1, new double[]{0, 1, 0}, holz);
            n.dreieck(a0, b1, a1, new double[]{0, 1, 0}, holz);
            n.viereck(Baunetz.p(ax - qx, ya - 0.3, az - qz), Baunetz.p(bx - qx, yb - 0.3, bz - qz), b0, a0, holz);
            n.viereck(Baunetz.p(ax + qx, ya - 0.3, az + qz), Baunetz.p(bx + qx, yb - 0.3, bz + qz), b1, a1, holz);
            for (double u = 1.5; u < l && !w.schwimmt(); u += 4) {
                double yd = ya + (yb - ya) * u / l;
                for (int sd = -1; sd <= 1; sd += 2) {
                    double x = ax + dx * u + sd * qx * 0.9, z = az + dz * u + sd * qz * 0.9, g = grund.applyAsDouble(x, z);
                    if (g < yd - 0.8 && g > -9) { n.prisma(x, z, g - 0.5, yd - 0.3, 0.14, 0.14, 6, 0, true, false, false, pf); zahl[2]++; n.pfaehle.add(new double[]{x, z, g, yd, 0.14}); }
                }
            }
            if (rand && !w.schwimmt() && grund.applyAsDouble((ax + bx) / 2, (az + bz) / 2) < Math.min(ya, yb) - 0.6) {
                gelaender(n, ax - qx, az - qz, ya, bx - qx, bz - qz, yb, lack);
                gelaender(n, ax + qx, az + qz, ya, bx + qx, bz + qz, yb, lack);
            }
        }
    }

    /** Geländer von a nach b: Pfosten alle 2 m, Handlauf in 1,05 m, Zwischenholm in 0,5 m. */
    private static void gelaender(Baunetz n, double ax, double az, double ya, double bx, double bz, double yb, int mat) {
        double l = Math.hypot(bx - ax, bz - az);
        int k = Math.max(1, (int) Math.round(l / 2));
        for (int i = 0; i <= k; i++) {
            double t = (double) i / k, x = ax + (bx - ax) * t, z = az + (bz - az) * t, y = ya + (yb - ya) * t;
            n.balken(Baunetz.p(x, y, z), Baunetz.p(x, y + 1.05, z), 0.06, 0.06, mat);
        }
        n.balken(Baunetz.p(ax, ya + 1.05, az), Baunetz.p(bx, yb + 1.05, bz), 0.07, 0.05, mat);
        n.balken(Baunetz.p(ax, ya + 0.5, az), Baunetz.p(bx, yb + 0.5, bz), 0.04, 0.04, mat);
    }

    /** Eine Laterne: Mast 4,2 m, Kopf mit Leuchte ({@link Baunetz#LAMPE}; seit Phase 12 LED 4 000 K, als Licht in {@link Lichter}). */
    private static void laterne(Baunetz n, double x, double z, double y) {
        n.prisma(x, z, y, y + 4.0, 0.07, 0.05, 6, 0, true, false, false, m(Baunetz.LACK_SCHWARZ, y, 3));
        n.prisma(x, z, y + 3.75, y + 4.05, 0.17, 0.17, 8, 0, true, false, true, m(Baunetz.LAMPE, y, 3));
        n.lichter.add(new double[]{x, y + 3.9, z});
        n.prisma(x, z, y + 4.05, y + 4.2, 0.25, 0.05, 8, 0, true, false, false, m(Baunetz.LACK_SCHWARZ, y, 3));
    }

    /**
     * Laternen der Promenade: an beleuchteten Fußwegen ({@code licht=yes}) bis 70 m hinter der Küste, alle 25 m auf einer
     * Seite.
     */
    private void promenade(Baunetz n, Netz fuss, DoubleBinaryOperator grund) {
        for (Netz.Kante k : fuss.kanten) {
            if (!"yes".equals(k.licht()) || k.bruecke()) continue;
            String a = k.art();
            if (!(a.equals("footway") || a.equals("pedestrian") || a.equals("path") || a.equals("cycleway"))) continue;
            double[] pt = k.punkte();
            int np = pt.length / 3;
            double[] mid = Site.fromUtm(pt[(np / 2) * 3], pt[(np / 2) * 3 + 1]);
            double[] mu = Site.toUtm(mid[0], mid[1]);
            double d = Site.kuestenAbstand(mu[0], mu[1]);
            if (d < 0 || d > 70) continue;
            double lauf = 0, naechste = 8;
            for (int i = 0; i + 1 < np; i++) {
                double[] p0 = Site.fromUtm(pt[i * 3], pt[i * 3 + 1]), p1 = Site.fromUtm(pt[i * 3 + 3], pt[i * 3 + 4]);
                double l = Math.hypot(p1[0] - p0[0], p1[1] - p0[1]);
                if (l < 1e-3) continue;
                double dx = (p1[0] - p0[0]) / l, dz = (p1[1] - p0[1]) / l;
                while (lauf + l > naechste) {
                    double u = naechste - lauf, x = p0[0] + dx * u - dz * 1.6, z = p0[1] + dz * u + dx * 1.6;
                    laterne(n, x, z, grund.applyAsDouble(x, z));
                    zahl[4]++;
                    naechste += 25;
                }
                lauf += l;
            }
        }
    }

    /** Teehaus Wolkenlos auf dem Kopf der Seeschlösschenbrücke [G nach Wolkenlos1–2 und DOP20]: weiß, Glasband, Pagodendach in zwei Stufen. */
    private static void wolkenlos(Baunetz n, double g) {
        double y0 = Math.max(DECK, g + 0.25), r = WOLKENLOS_RICHTUNG, x = WOLKENLOS_X, z = WOLKENLOS_Z;
        int weiss = Baunetz.merkmal(Baunetz.LACK, false, 77, y0), glas = Baunetz.merkmal(Baunetz.FENSTER, false, 77, y0);
        double L = 18, B = 13;
        // Sockel und Pfosten, dazwischen Glas
        n.quader(x, y0, z, B, L, 0.5, r, weiss);
        n.quader(x, y0 + 0.5, z, B - 0.2, L - 0.2, 2.4, r, glas);
        double a = Math.toRadians(r), fx = Math.sin(a), fz = -Math.cos(a), rx = -fz, rz = fx;
        for (int i = 0; i <= 6; i++)
            for (int sd = -1; sd <= 1; sd += 2) {
                double u = -L / 2 + L * i / 6;
                n.quader(x + fx * u + rx * sd * B / 2, y0 + 0.5, z + fz * u + rz * sd * B / 2, 0.3, 0.3, 2.4, r, weiss);
                double v = -B / 2 + B * i / 6;
                n.quader(x + rx * v + fx * sd * L / 2, y0 + 0.5, z + rz * v + fz * sd * L / 2, 0.3, 0.3, 2.4, r, weiss);
            }
        n.quader(x, y0 + 2.9, z, B, L, 0.5, r, weiss);
        // Pagodendach: untere Stufe mit Aufschwung an der Traufe, Laterne, obere Stufe, Spitze
        walm(n, x, z, y0 + 3.4, L + 2.6, B + 2.6, 2.6, r, weiss);
        n.quader(x, y0 + 5.2, z, B * 0.45, L * 0.55, 1.2, r, glas);
        walm(n, x, z, y0 + 6.4, L * 0.55 + 1.6, B * 0.45 + 1.6, 1.9, r, weiss);
        n.prisma(x, z, y0 + 8.3, y0 + 9.6, 0.12, 0.02, 6, 0, true, false, false, weiss);
    }

    /**
     * Walmdach mit Aufschwung (Pagode): Traufe L × B in y0, ein Knick auf einem Drittel der Höhe (flach an der Traufe,
     * steil darüber), First L − B lang in y0 + h; Richtung = Längsachse.
     */
    static void walm(Baunetz n, double x, double z, double y0, double L, double B, double h, double richtung, int mat) {
        double a = Math.toRadians(richtung), fx = Math.sin(a), fz = -Math.cos(a), rx = -fz, rz = fx;
        double[][] ring0 = rechteck(x, z, L, B, fx, fz, rx, rz, y0);
        double k = 0.32;   // Knick: 32 % der Breite einwärts auf 22 % der Höhe
        double[][] ring1 = rechteck(x, z, L - B * k, B * (1 - k), fx, fz, rx, rz, y0 + h * 0.22);
        double fl = Math.max(0.01, L - B);
        double[] f0 = Baunetz.p(x - fx * fl / 2, y0 + h, z - fz * fl / 2), f1 = Baunetz.p(x + fx * fl / 2, y0 + h, z + fz * fl / 2);
        double[][] top = {f0, f1, f1, f0};
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            n.viereck(ring0[i], ring0[j], ring1[j], ring1[i], mat);
            if (i % 2 == 0) n.viereck(ring1[i], ring1[j], top[j], top[i], mat);
            else n.dreieck(ring1[i], ring1[j], top[i], mat);
        }
        // Untersicht der Traufe
        n.viereck(ring0[0], ring0[3], ring0[2], ring0[1], mat);
    }

    /** Ecken eines Rechtecks: hinten links, vorn links, vorn rechts, hinten rechts (Umlauf), auf Höhe y. */
    private static double[][] rechteck(double x, double z, double L, double B, double fx, double fz, double rx, double rz, double y) {
        double[][] c = new double[4][];
        double[][] s = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
        for (int i = 0; i < 4; i++) c[i] = Baunetz.p(x + fx * s[i][0] * L / 2 + rx * s[i][1] * B / 2, y, z + fz * s[i][0] * L / 2 + rz * s[i][1] * B / 2);
        return c;
    }

    /** Turm der DLRG [G nach den Fotos]: vier Stelzen, Plattform, weiße Hütte mit rotem Zeltdach, Mast mit gelb-roter Flagge. */
    private static void dlrgTurm(Baunetz n, double x, double z, DoubleBinaryOperator grund) {
        double g = grund.applyAsDouble(x, z), y = g + 2.6;
        int holz = Baunetz.merkmal(Baunetz.HOLZ_WEISS, false, 11, g), weiss = Baunetz.merkmal(Baunetz.LACK, false, 11, y),
                rot = Baunetz.merkmal(Baunetz.ROT, false, 11, y), gelb = Baunetz.merkmal(Baunetz.GELB, false, 11, y),
                fenster = Baunetz.merkmal(Baunetz.FENSTER, false, 11, y), eisen = Baunetz.merkmal(Baunetz.EISEN, false, 11, g);
        for (int i = 0; i < 4; i++) {
            double sx = (i & 1) == 0 ? -1.2 : 1.2, sz = (i & 2) == 0 ? -1.2 : 1.2;
            n.balken(Baunetz.p(x + sx, g - 0.3, z + sz), Baunetz.p(x + sx * 0.9, y, z + sz * 0.9), 0.16, 0.16, holz);
        }
        n.quader(x, y - 0.15, z, 3.2, 3.2, 0.15, 0, holz);
        n.quader(x, y, z, 2.4, 2.4, 2.1, 0, weiss);
        n.quader(x, y + 1.0, z - 1.21, 2.0, 0.02, 0.8, 0, fenster);   // Fenster nach See (Norden)
        n.prisma(x, z, y + 2.1, y + 3.1, 2.1, 0, 4, 45, false, false, true, rot);
        n.balken(Baunetz.p(x + 1.4, y, z + 1.4), Baunetz.p(x + 1.4, y + 5.5, z + 1.4), 0.06, 0.06, eisen);
        n.quader(x + 1.4 + 0.6, y + 4.8, z + 1.4, 1.2, 0.02, 0.35, 90, gelb);
        n.quader(x + 1.4 + 0.6, y + 4.45, z + 1.4, 1.2, 0.02, 0.35, 90, rot);
    }

    /**
     * Ein Kutter [G nach Hafen1–2 und dem DOP20]: Rumpf mit Spiegelheck und spitzem Bug, Schanzkleid, Ruderhaus achtern,
     * Mast mit Baum; Wasserlinie bei 0 m NHN.
     */
    static void kutter(Baunetz n, double x, double z, double richtung, double L, int k) {
        int[] farben = {Baunetz.LACK, Baunetz.ROT, Baunetz.LACK, Baunetz.LACK_SCHWARZ, Baunetz.GELB};
        int rumpf = Baunetz.merkmal(farben[k % farben.length], false, k, -1), weiss = Baunetz.merkmal(Baunetz.LACK, false, k, 0),
                deck = Baunetz.merkmal(Baunetz.HOLZ, false, k, 0), glas = Baunetz.merkmal(Baunetz.FENSTER, false, k, 0),
                eisen = Baunetz.merkmal(Baunetz.EISEN, false, k, 0), rot = Baunetz.merkmal(Baunetz.ROT, false, k, -1);
        double B = L * 0.32, a = Math.toRadians(richtung), fx = Math.sin(a), fz = -Math.cos(a), rx = -fz, rz = fx;
        int m = 8;
        double[][] fp = new double[2 * m + 2][];
        for (int i = 0; i <= m; i++) {
            double s = -L / 2 + L * i / m, w = s < 0.1 * L ? B / 2 * (0.85 + 0.15 * Math.min(1, (s + L / 2) / (0.3 * L))) : B / 2 * Math.sqrt(Math.max(0, 1 - Math.pow((s - 0.1 * L) / (0.4 * L), 2)));
            fp[i] = new double[]{s, w};
            fp[2 * m + 1 - i] = new double[]{s, -w};
        }
        double[] yb = {-0.9, 0.15, 1.1, 1.45};
        int[] mats = {rot, rumpf, rumpf};
        for (int st = 0; st < 3; st++)
            for (int i = 0; i < fp.length; i++) {
                double[] p0 = fp[i], p1 = fp[(i + 1) % fp.length];
                double sh = st == 0 ? 0.8 : 1.0;
                n.viereck(welt(x, z, fx, fz, rx, rz, p0[0], p0[1] * sh, yb[st]), welt(x, z, fx, fz, rx, rz, p1[0], p1[1] * sh, yb[st]),
                        welt(x, z, fx, fz, rx, rz, p1[0], p1[1], yb[st + 1]), welt(x, z, fx, fz, rx, rz, p0[0], p0[1], yb[st + 1]), mats[st]);
            }
        double[] c = welt(x, z, fx, fz, rx, rz, 0, 0, 1.1);
        for (int i = 0; i < fp.length; i++) {
            double[] p0 = fp[i], p1 = fp[(i + 1) % fp.length];
            n.dreieck(c, welt(x, z, fx, fz, rx, rz, p1[0], p1[1], 1.1), welt(x, z, fx, fz, rx, rz, p0[0], p0[1], 1.1), new double[]{0, 1, 0}, deck);
        }
        // Ruderhaus achtern, Fenster nach vorn
        double hs = -L * 0.18;
        double[] h = welt(x, z, fx, fz, rx, rz, hs, 0, 1.1);
        n.quader(h[0], 1.1, h[2], B * 0.55, L * 0.2, 2.1, richtung, weiss);
        double[] fw = welt(x, z, fx, fz, rx, rz, hs + L * 0.1 + 0.01, 0, 2.3);
        n.quader(fw[0], 2.3, fw[2], B * 0.5, 0.02, 0.6, richtung, glas);
        n.quader(h[0], 3.2, h[2], B * 0.62, L * 0.24, 0.12, richtung, weiss);
        // Mast mit Baum
        double[] ms = welt(x, z, fx, fz, rx, rz, L * 0.15, 0, 1.1), me = welt(x, z, fx, fz, rx, rz, L * 0.15, 0, 1.1 + L * 0.55);
        n.balken(ms, me, 0.14, 0.14, eisen);
        n.balken(welt(x, z, fx, fz, rx, rz, L * 0.15, 0, 2.6), welt(x, z, fx, fz, rx, rz, L * 0.45, 0, 2.3), 0.09, 0.09, eisen);
    }

    private static double[] welt(double x, double z, double fx, double fz, double rx, double rz, double s, double q, double y) {
        return Baunetz.p(x + fx * s + rx * q, y, z + fz * s + rz * q);
    }

    /**
     * Der Alte Leuchtturm Travemünde (1539) [G nach Fotos; OSM: 31 m]: bauchiger Backsteinturm, Umgang auf 21,6 m,
     * Laterne mit Sprossen (Glas wie die Quermarke, Baustoff {@link Baunetz#GLAS_Q}), Kupferhaube, Spitze.
     */
    private static void alterLeuchtturm(Baunetz n, double g) {
        int ziegel = Baunetz.merkmal(Baunetz.BACKSTEIN, false, 101, g), weiss = Baunetz.merkmal(Baunetz.LACK, false, 101, 21),
                glas = Baunetz.merkmal(Baunetz.GLAS_Q, false, 101, 22), kupfer = Baunetz.merkmal(Baunetz.KUPFER, false, 101, 25),
                eisen = Baunetz.merkmal(Baunetz.LACK_SCHWARZ, false, 101, 21);
        double x = ALT_X, z = ALT_Z;
        double[][] prof = {{g - 0.5, 3.6}, {g + 3, 3.8}, {g + 9, 4.0}, {14, 3.8}, {19, 3.3}, {21.4, 3.1}};
        for (int i = 0; i + 1 < prof.length; i++)
            n.prisma(x, z, prof[i][0], prof[i + 1][0], prof[i][1], prof[i + 1][1], 20, 0, true, false, false, ziegel);
        n.scheibe(x, z, 21.6, 0, 3.6, 0.2, 20, 0, ziegel);
        n.gelaender(x, z, 21.6, 3.5, 1.0, 16, 0, 0.04, eisen);
        n.prisma(x, z, 21.6, 22.6, 1.7, 1.7, 12, 0, true, false, false, weiss);
        n.prisma(x, z, 22.6, 25.4, 1.7, 1.7, 12, 0, true, false, false, glas);
        n.prisma(x, z, 25.4, 27.6, 1.95, 0.35, 12, 0, true, false, false, kupfer);
        n.prisma(x, z, 27.6, 31.0, 0.12, 0.02, 6, 0, true, false, false, kupfer);
    }

    // ------------------------------------------------------------------ Strandkörbe

    /** Strandkörbe nur westlich davon (bis zum Priwall; die Strände im Klützer Winkel bleiben frei) und im Fernfeld. */
    static final double KOERBE_OST = 7800;
    private static final Site.Fenster FERN = Site.FERNFELD;

    /** Ein Strandkorb: Lage, Blickrichtung (Grad von Nord), Kennzahl (Farbe der Haube). */
    public record Korb(double x, double y, double z, double richtung, int kennzahl) { }

    /**
     * Strandkörbe in Reihen parallel zur Küste [G nach Strandkorb1–2 und DOP20]: Reihen 16, 20,5 und 25 m hinter der
     * Küstenlinie, in der Reihe alle 3,2 m, in Feldern von 45 m mit Gang dazwischen, nur auf trockenem Strand (Art Strand,
     * 0,5 bis 3,2 m NHN), nicht unter Brücken und Stegen; Blick zur See ± 12°. Je Feld steht der Korb mit
     * 85 % Wahrscheinlichkeit (Lücken).
     */
    public List<Korb> koerbe(DoubleBinaryOperator grund, java.util.function.BiPredicate<Double, Double> strand) {
        List<Korb> l = new ArrayList<>();
        Random r = new Random(1923);
        double[][] k = Site.KUESTE;
        double s = 0;
        for (int i = 0; i + 1 < k.length; i++) {
            double[] a = Site.fromUtm(k[i][0], k[i][1]), b = Site.fromUtm(k[i + 1][0], k[i + 1][1]);
            double len = Math.hypot(b[0] - a[0], b[1] - a[1]);
            if (len < 1e-3) continue;
            double dx = (b[0] - a[0]) / len, dz = (b[1] - a[1]) / len;
            // das Land liegt links in Laufrichtung (UTM); in der Szene (z nach Süden) ist links (dz, −dx)
            double lx = dz, lz = -dx;
            for (double u = (3.2 - s % 3.2) % 3.2; u < len; u += 3.2) {
                double sa = s + u;
                if (sa % 45 > 37) continue;
                for (double d : new double[]{16, 20.5, 25}) {
                    double x = a[0] + dx * u + lx * d, z = a[1] + dz * u + lz * d;
                    if (x > KOERBE_OST || !FERN.contains(x, z)) continue;
                    double g = grund.applyAsDouble(x, z);
                    boolean ok = g > 0.5 && g < 3.2 && strand.test(x, z) && r.nextDouble() < 0.85 && !unterBruecke(x, z);
                    if (!ok) continue;
                    double blick = Math.toDegrees(Math.atan2(-lx, lz)) + (r.nextDouble() - 0.5) * 24;
                    l.add(new Korb(x, g, z, blick, r.nextInt(512)));
                }
            }
            s += len;
        }
        return l;
    }

    private boolean unterBruecke(double x, double z) {
        for (Bauwerk w : bauwerke) {
            double[][] p = w.p();
            for (int i = 0; i + 1 < p.length; i++) {
                double ax = p[i][0], az = p[i][1], bx = p[i + 1][0], bz = p[i + 1][1], vx = bx - ax, vz = bz - az, l2 = vx * vx + vz * vz;
                double t = l2 < 1e-9 ? 0 : Math.max(0, Math.min(1, ((x - ax) * vx + (z - az) * vz) / l2));
                if (Math.hypot(ax + vx * t - x, az + vz * t - z) < 8) return true;
            }
        }
        return false;
    }

    /**
     * Seit Phase 20: der Korb selbst aus {@code com.dan.beachchair} (Ostseekorb nach dem Foto Strandkorb.jpg: weißes
     * Geflecht, Holz in Honig, Haube rund, Streifen meist blau-weiß), statt der Kästen aus Phase 9. Die Qualität setzt der
     * Renderer.
     */
    public static com.dan.beachchair.BeachChair korb() { return com.dan.beachchair.BeachChair.timmendorf(); }

    /** Die Körbe für {@code com.dan.beachchair}: Ort, Blick und Kennzahl wie {@link #koerbe}, Farbe aus der Palette des Korbs. */
    public static List<com.dan.beachchair.BeachChair.Chair> stuehle(List<Korb> ks, com.dan.beachchair.BeachChair korb) {
        List<com.dan.beachchair.BeachChair.Chair> l = new ArrayList<>(ks.size());
        for (Korb k : ks) l.add(korb.chair(k.x(), k.y(), k.z(), k.richtung(), k.kennzahl() * 7919L + Math.round(k.x() * 13 + k.z() * 7)));
        return l;
    }

    /**
     * Seit Phase 20: Anteil offener Körbe zur Stunde [G]: die Vermieter schließen ab 9 Uhr auf und ab 18 Uhr zu, nachts
     * ist alles verschlossen; bei Regen bleibt die Hälfte zu, ab Bft 7 fast alle. Körbe mit Gästen sind immer offen.
     */
    public static double offen(double h, boolean regen, double wind) {
        double t = h < 8.5 || h > 20.5 ? 0 : h < 10 ? (h - 8.5) / 1.5 : h > 18 ? (20.5 - h) / 2.5 : 1;
        double k = 0.9 * t;
        if (regen) k *= 0.5;
        if (wind > 13.9) k *= 0.15;
        return k;
    }

    /** Wie bequem es sich die Gäste machen (Lehne hinten, Fußstützen heraus): am Nachmittag am meisten [G]. */
    public static double bequem(double h) { return Math.max(0, Math.min(1, 0.3 + 0.6 * Math.exp(-Math.pow((h - 15) / 3, 2)))); }

    /** Höhe des Sitzes im Strandkorb (m), wie {@code BeachChair.seatHeight}. */
    public static final double KORB_SITZ = 0.42;

    /** Ob die Körbe zur Jahreszeit stehen: von Mitte April bis Ende September. */
    public static boolean saison(java.time.LocalDate d) {
        int m = d.getMonthValue();
        return (m > 4 && m < 10) || (m == 4 && d.getDayOfMonth() >= 15);
    }

    // ------------------------------------------------------------------ Geometrie

    /** Ohne den doppelten Schlusspunkt. */
    static double[][] offen(double[][] p) {
        int n = p.length;
        if (n > 1 && Math.hypot(p[0][0] - p[n - 1][0], p[0][1] - p[n - 1][1]) < 1e-6) n--;
        return java.util.Arrays.copyOf(p, n);
    }

    /** Vorzeichenbehaftete Fläche in der Ebene (x, z). */
    static double flaeche(double[][] p) {
        double a = 0;
        for (int i = 0; i < p.length; i++) { int j = (i + 1) % p.length; a += p[i][0] * p[j][1] - p[j][0] * p[i][1]; }
        return a / 2;
    }

    /** Ohrenschnitt eines einfachen Vielecks: Indizes der Dreiecke. */
    static int[] ohren(double[][] p, boolean ccw) {
        int n = p.length;
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < n; i++) idx.add(ccw ? i : n - 1 - i);
        int[] out = new int[Math.max(0, (n - 2) * 3)];
        int o = 0, guard = 0;
        while (idx.size() > 3 && guard++ < n * n) {
            boolean cut = false;
            for (int k = 0; k < idx.size(); k++) {
                int ia = idx.get((k + idx.size() - 1) % idx.size()), ib = idx.get(k), ic = idx.get((k + 1) % idx.size());
                double[] a = p[ia], b = p[ib], c = p[ic];
                double cr = (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
                if (cr <= 1e-9) continue;
                boolean leer = true;
                for (int q : idx) {
                    if (q == ia || q == ib || q == ic) continue;
                    if (innen(p[q], a, b, c)) { leer = false; break; }
                }
                if (!leer) continue;
                out[o++] = ia; out[o++] = ib; out[o++] = ic;
                idx.remove(k);
                cut = true;
                break;
            }
            if (!cut) break;   // kein Ohr (Selbstschnitt): Rest weglassen
        }
        if (idx.size() == 3) { out[o++] = idx.get(0); out[o++] = idx.get(1); out[o++] = idx.get(2); }
        return java.util.Arrays.copyOf(out, o);
    }

    private static boolean innen(double[] p, double[] a, double[] b, double[] c) {
        double d1 = (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0]);
        double d2 = (c[0] - b[0]) * (p[1] - b[1]) - (c[1] - b[1]) * (p[0] - b[0]);
        double d3 = (a[0] - c[0]) * (p[1] - c[1]) - (a[1] - c[1]) * (p[0] - c[0]);
        return d1 >= 0 && d2 >= 0 && d3 >= 0;
    }

    /** „86 Brücken, 70 Stege, 15 DLRG, 28 Feuer (daten/bauwerke)“ */
    public String describe() {
        long b = bauwerke.stream().filter(Bauwerk::flaeche).count();
        return String.format(Locale.GERMAN, "%d Brücken, %d Stege, %d DLRG, %d Feuer (%s)", b, bauwerke.size() - b, wachen.size(), feuer.size(), quelle);
    }
}
