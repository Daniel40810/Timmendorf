package com.dan.timmendorf.scene;

import com.dan.route.Route;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ein Wegenetz der Szene als Datei-Abbild (Werkbuch, Abschnitt 8, F20 A): {@code daten/netz/<name>_knoten.tsv} und
 * {@code <name>_kanten.tsv}, geschrieben vom Schritt {@code netz} der Aufbereitung aus den Overpass-Ebenen
 * ({@code com.dan.timmendorf.tools.NetzImport}). Dieselben Knoten und Kanten mit denselben Nummern liegen ab Phase 3b im
 * Oracle Network Data Model (Schema TIMMENDORF, Netze {@code TD_NET_…}); ohne Datenbank liest die App sie von hier.
 * <p>
 * Lage in UTM 32 N (EPSG:25832) und Höhe über NHN, Längen in m im Raum (über die Stützpunkte der Kante), Zeiten in s.
 * Eine Einbahnkante gilt nur von {@code von} nach {@code nach}; gegenläufige Einbahnen sind beim Schreiben umgedreht.
 */
public final class Netz {
    /** Die vier Netze aus Abschnitt 8: Datei-Name, Name in Oracle, was darin ist. */
    public enum Art {
        FUSS("fuss", "TD_NET_FUSS", "Fußwege, Promenaden, Strandzugänge, Seebrücken"),
        STRASSE("strasse", "TD_NET_STRASSE", "Straßen mit Geschwindigkeit und Einbahn"),
        BUS("bus", "TD_NET_BUS", "Straßen für Busse mit den Haltestellen als Knoten"),
        SEE("see", "TD_NET_SEE", "Fahrwasser zwischen Seezeichen, Häfen, Seebrücken und der Regattabahn");

        public final String datei, oracle, inhalt;

        Art(String datei, String oracle, String inhalt) { this.datei = datei; this.oracle = oracle; this.inhalt = inhalt; }
    }

    public static final String KNOTEN = "_knoten.tsv", KANTEN = "_kanten.tsv";
    public static final String[] KNOTEN_SPALTEN = {"id", "ost", "nord", "hoehe", "art", "name", "osm"};
    public static final String[] KANTEN_SPALTEN = {"id", "von", "nach", "laenge", "zeit", "art", "name", "einbahn", "belag", "licht", "breite",
            "zugang", "ebene", "bruecke", "tempo", "osm", "punkte"};

    /** Ein Knoten: Nummer, Lage (UTM, m), Höhe über NHN, Art (kreuzung, ende, haltestelle, seezeichen, hafen, …), Name. */
    public record Knoten(int id, double e, double n, double h, String art, String name, String osm) {
        public double x() { return e - Site.E0; }
        public double z() { return Site.N0 - n; }
    }

    /**
     * Eine Kante: Länge (m im Raum), Zeit (s), Art (OSM highway, pier, fahrwasser), Name, Einbahn, Belag, Licht, Breite (m),
     * Zugang, Ebene (OSM layer), Brücke oder Steg, Tempo (km/h, Straßen), OSM-Weg, Stützpunkte {Ost, Nord, Höhe, …}.
     */
    public record Kante(int id, int von, int nach, double laenge, double zeit, String art, String name, boolean einbahn, String belag,
                        String licht, double breite, String zugang, int ebene, boolean bruecke, double tempo, String osm, double[] punkte) {
        public int punktzahl() { return punkte.length / 3; }
    }

    public final Art art;
    public final List<Knoten> knoten;
    public final List<Kante> kanten;
    private final Map<Integer, Integer> knotenIndex = new HashMap<>();

    public Netz(Art art, List<Knoten> knoten, List<Kante> kanten) {
        this.art = art;
        this.knoten = Collections.unmodifiableList(knoten);
        this.kanten = Collections.unmodifiableList(kanten);
        for (int i = 0; i < knoten.size(); i++) knotenIndex.put(knoten.get(i).id(), i);
    }

    public Knoten knoten(int id) { Integer i = knotenIndex.get(id); return i == null ? null : knoten.get(i); }

    /** Ordner {@code daten/netz}. */
    public static File ordner() { return new File(Daten.ordner(), "netz"); }

    /** Ob das Netz als Datei da ist. */
    public static boolean da(File dir, Art a) { return new File(dir, a.datei + KNOTEN).isFile() && new File(dir, a.datei + KANTEN).isFile(); }

    /** Liest ein Netz aus {@code dir}. */
    public static Netz laden(File dir, Art a) throws IOException {
        List<Knoten> kn = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(new File(dir, a.datei + KNOTEN).toPath(), StandardCharsets.UTF_8)) {
            r.readLine();
            for (String l; (l = r.readLine()) != null; ) {
                if (l.isEmpty()) continue;
                String[] s = l.split("\t", -1);
                kn.add(new Knoten(Integer.parseInt(s[0]), d(s[1]), d(s[2]), d(s[3]), s[4], s[5], s[6]));
            }
        }
        List<Kante> ka = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(new File(dir, a.datei + KANTEN).toPath(), StandardCharsets.UTF_8)) {
            r.readLine();
            for (String l; (l = r.readLine()) != null; ) {
                if (l.isEmpty()) continue;
                String[] s = l.split("\t", -1);
                String[] pp = s[16].split(" ");
                double[] pts = new double[pp.length * 3];
                for (int i = 0; i < pp.length; i++) {
                    String[] c = pp[i].split(",");
                    pts[i * 3] = d(c[0]);
                    pts[i * 3 + 1] = d(c[1]);
                    pts[i * 3 + 2] = d(c[2]);
                }
                ka.add(new Kante(Integer.parseInt(s[0]), Integer.parseInt(s[1]), Integer.parseInt(s[2]), d(s[3]), d(s[4]), s[5], s[6], s[7].equals("1"),
                        s[8], s[9], d(s[10]), s[11], Integer.parseInt(s[12]), s[13].equals("1"), d(s[14]), s[15], pts));
            }
        }
        return new Netz(a, kn, ka);
    }

    private static double d(String s) { return s.isEmpty() ? 0 : Double.parseDouble(s); }

    /** SHA-256 über Knoten- und Kantendatei eines Netzes (so weiß die App, ob Oracle und {@code daten/netz} gleich sind). */
    public static String pruefsumme(File dir, Art a) throws IOException {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            md.update(Files.readAllBytes(new File(dir, a.datei + KNOTEN).toPath()));
            md.update(Files.readAllBytes(new File(dir, a.datei + KANTEN).toPath()));
            return java.util.HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /**
     * Kürzester Weg von Knoten {@code von} nach {@code nach} über die Längen der Kanten (Dijkstra, Einbahnen nur vorwärts):
     * dieselben Kosten wie {@code COST} in Oracle. Gibt {Länge, Knotenfolge…} oder null.
     */
    public long[] kuerzester(int von, int nach, double[] laenge) {
        int n = knoten.size();
        List<List<int[]>> adj = new ArrayList<>();
        for (int i = 0; i < n; i++) adj.add(new ArrayList<>());
        for (int e = 0; e < kanten.size(); e++) {
            Kante k = kanten.get(e);
            int a = knotenIndex.get(k.von()), b = knotenIndex.get(k.nach());
            adj.get(a).add(new int[]{b, e});
            if (!k.einbahn()) adj.get(b).add(new int[]{a, e});
        }
        double[] d = new double[n];
        int[] prev = new int[n];
        java.util.Arrays.fill(d, Double.POSITIVE_INFINITY);
        java.util.Arrays.fill(prev, -1);
        Integer s0 = knotenIndex.get(von), z0 = knotenIndex.get(nach);
        if (s0 == null || z0 == null) return null;
        d[s0] = 0;
        java.util.PriorityQueue<double[]> q = new java.util.PriorityQueue<>((x, y) -> Double.compare(x[0], y[0]));
        q.add(new double[]{0, s0});
        while (!q.isEmpty()) {
            double[] c = q.poll();
            int u = (int) c[1];
            if (c[0] > d[u]) continue;
            if (u == z0) break;
            for (int[] e : adj.get(u)) {
                double nd = d[u] + kanten.get(e[1]).laenge();
                if (nd < d[e[0]]) { d[e[0]] = nd; prev[e[0]] = u; q.add(new double[]{nd, e[0]}); }
            }
        }
        if (Double.isInfinite(d[z0])) return null;
        List<Long> weg = new ArrayList<>();
        for (int v = z0; v >= 0; v = prev[v]) weg.add(0, (long) knoten.get(v).id());
        laenge[0] = d[z0];
        long[] r = new long[weg.size()];
        for (int i = 0; i < r.length; i++) r[i] = weg.get(i);
        return r;
    }

    /** Gesamtlänge (m). */
    public double laenge() { return kanten.stream().mapToDouble(Kante::laenge).sum(); }

    /**
     * Der Graph für das Package {@code route}: jeder Stützpunkt wird ein Knoten (die Knoten des Netzes mit ihrer Nummer,
     * die Stützpunkte mit negativen Nummern), damit die Längen in {@code route} genau die des Netzes sind. Lage im Raum der
     * Szene (x Ost, y Höhe, z Süd).
     */
    public Route route() {
        Route g = new Route();
        Map<Integer, Integer> idx = new HashMap<>();
        for (Knoten k : knoten) idx.put(k.id(), g.node(k.id(), k.x(), k.h(), k.z()));
        for (Kante k : kanten) {
            Route.Kind kind = kind(k);
            int prev = idx.get(k.von());
            int n = k.punktzahl();
            for (int i = 1; i < n; i++) {
                int cur;
                if (i == n - 1) cur = idx.get(k.nach());
                else {
                    double[] p = k.punkte();
                    cur = g.node(-((long) k.id() * 100_000 + i), p[i * 3] - Site.E0, p[i * 3 + 2], Site.N0 - p[i * 3 + 1]);
                }
                g.edge(prev, cur, kind, k.breite(), k.einbahn(), k.tempo() > 0 ? k.tempo() / 3.6 : 0);   // seit Phase 10 mit Tempo
                prev = cur;
            }
        }
        return g;
    }

    /** Art der Kante für {@code route}. */
    public static Route.Kind kind(Kante k) {
        return switch (k.art()) {
            case "pier" -> Route.Kind.PIER;
            case "steps" -> Route.Kind.STEPS;
            case "pedestrian", "living_street" -> Route.Kind.PROMENADE;
            case "fahrwasser", "regatta" -> Route.Kind.FAIRWAY;
            case "footway", "path", "track", "cycleway", "bridleway", "corridor" -> Route.Kind.FOOTWAY;
            default -> Route.Kind.ROAD;
        };
    }

    /** Index im Graphen von {@link #route()} zu einer Knotennummer des Netzes. */
    public static int index(Route g, int knotenId) { return g.indexOf(knotenId); }

    /** Der Knoten des Netzes am nächsten zu einem Punkt (UTM). */
    public Knoten naechster(double e, double n) {
        Knoten best = null;
        double bd = Double.MAX_VALUE;
        for (Knoten k : knoten) {
            double d = Math.hypot(k.e() - e, k.n() - n);
            if (d < bd) { bd = d; best = k; }
        }
        return best;
    }

    @Override public String toString() {
        return String.format(Locale.GERMAN, "%s: %,d Knoten, %,d Kanten, %.1f km", art.oracle, knoten.size(), kanten.size(), laenge() / 1000);
    }
}
