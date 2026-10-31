package com.dan.timmendorf.tools;

import com.dan.route.Route;
import com.dan.timmendorf.scene.Geo;
import com.dan.timmendorf.scene.Netz;
import com.dan.timmendorf.scene.Site;
import com.dan.timmendorf.scene.Terrain;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Die Netze aus Overpass (Phase 3, Schritt {@code netz}, Werkbuch Abschnitt 8, F20 A, F21 A). Braucht {@code daten/gelaende}
 * für die Höhen und die Wassertiefe. Schreibt {@code daten/netz/} ({@link Netz}): je Netz Knoten und Kanten, dazu
 * {@code poi.tsv} (Orte aus den Punkt-Ebenen), {@code pruefwege.tsv} (kürzeste Wege, gegen die Oracle in Phase 3b
 * dieselben Längen liefern muss), {@code netz.properties} und das Prüfbild {@code docs/status/Netz_Phase3.png}.
 * <ol>
 * <li><b>Knoten</b>: Wege, die sich einen Punkt teilen, haben genau dieselbe Koordinate (F21 A); ein Weg wird an jedem
 * Punkt geteilt, den ein zweiter Weg desselben Netzes benutzt, und an seinen Enden. Brücken und Stege kreuzen ohne
 * gemeinsamen Punkt, also ohne Knoten.</li>
 * <li><b>Höhen</b> aus {@code daten/gelaende} (DGM1), alle {@link #DICHTE} m ein Stützpunkt; Brücken gerade zwischen ihren
 * Enden, Seebrücken und Stege auf dem Deck ({@link #DECK} m NHN [G]).</li>
 * <li><b>Fuß</b>: alle Wege außer Autobahn und Kraftfahrstraße, ohne {@code foot=no} und private Wege; Seebrücken als
 * {@code pier}. <b>Straße</b>: Autobahn bis Wohnstraße und Zufahrten, Einbahn aus {@code oneway} und Kreisverkehr, Tempo
 * aus {@code maxspeed} oder nach der Art [G]. <b>Bus</b>: die Straßen ab Wohnstraße, jede Haltestelle aus
 * {@code 09.BusStops} als Knoten auf der nächsten Kante (bis {@link #HALT_FANG} m). <b>See</b>: Tonnen, Baken und
 * Leuchtfeuer im Wasser, Liegeplätze der Häfen, die Köpfe der Seebrücken und die Regattabahn vor Timmendorf [G];
 * Kanten zu den {@link #SEE_NACHBARN} nächsten Knoten, wenn die gerade Fahrt überall mindestens {@link #TIEFGANG} m tief
 * ist (an den Enden {@link #TIEFE_ENDE} m).</li>
 * </ol>
 */
public final class NetzImport {
    /** Abstand der Stützpunkte für die Höhe (m). */
    public static final double DICHTE = 10;
    /** Höhe des Decks der Seebrücken und Stege über NHN (m) [G, Phase 9 misst an den Fotos]. */
    public static final double DECK = 2.6;
    /** Haltestellen: größter Abstand zur Straße (m). */
    public static final double HALT_FANG = 40;
    /** Fahrwasser: Tiefgang (m), an den Enden (Hafen, Steg) mindestens, Reichweite einer Kante (m), Zahl der Nachbarn. */
    public static final double TIEFGANG = 1.8, TIEFE_ENDE = 0.8, ENDE = 120, SEE_REICHWEITE = 5000;
    public static final int SEE_NACHBARN = 8;
    /** Regattabahn vor der Seebrücke (Luv-Lee bei Nordostwind) [G]: Lee 1,2 km, Luv 2,9 km auf 045° vom Landende. */
    public static final double REGATTA_LEE = 1200, REGATTA_LUV = 2900, REGATTA_KURS = 45;
    public static final File BILD = new File("docs/status/Netz_Phase3.png");

    static final Set<String> NIE_ZU_FUSS = Set.of("motorway", "motorway_link", "trunk", "trunk_link", "construction", "proposed", "raceway",
            "bus_guideway", "escape", "abandoned", "platform", "rest_area", "services");
    static final Set<String> STRASSEN = Set.of("motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link", "secondary",
            "secondary_link", "tertiary", "tertiary_link", "unclassified", "residential", "living_street", "service");
    static final Set<String> BUSSTRASSEN = Set.of("motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link", "secondary",
            "secondary_link", "tertiary", "tertiary_link", "unclassified", "residential");

    private NetzImport() { }

    public static void main(String[] a) throws Exception { importieren(Rohdaten.laden(), new File("daten"), System.out::println); }

    // ------------------------------------------------------------------ Bausteine

    /** Ein Weg aus OSM: Nummer, Merkmale, Punkte (Schlüssel aus Länge und Breite, UTM). */
    static final class Weg {
        final String osm;
        final Map<String, String> tag;
        final long[] key;
        final double[] e, n;

        Weg(String osm, Map<String, String> tag, List<?> coords) {
            this.osm = osm;
            this.tag = tag;
            int c = coords.size();
            key = new long[c];
            e = new double[c];
            n = new double[c];
            for (int i = 0; i < c; i++) {
                List<?> p = (List<?>) coords.get(i);
                double lon = ((Number) p.get(0)).doubleValue(), lat = ((Number) p.get(1)).doubleValue();
                key[i] = schluessel(lon, lat);
                double[] u = Geo.UTM32N.forward(lat, lon);
                e[i] = u[0];
                n[i] = u[1];
            }
        }

        String t(String k) { return tag.getOrDefault(k, ""); }
        boolean ja(String k) { String v = t(k); return v.equals("yes") || v.equals("true") || v.equals("1"); }
    }

    /** Gleiche Koordinate, gleicher Knoten (F21 A): Länge und Breite auf 1e-7 Grad. */
    static long schluessel(double lon, double lat) { return Math.round(lon * 1e7) << 32 | (Math.round(lat * 1e7) & 0xffffffffL); }

    /** Eine Kante im Bau, vor den Nummern. */
    static final class Roh {
        long von, nach;
        final List<double[]> pts = new ArrayList<>();   // {e, n}
        final Weg weg;
        String art;
        boolean einbahn, bruecke, deck;
        double minTiefe;

        Roh(Weg w) { weg = w; }
    }

    /** Ein Netz im Bau. */
    static final class Bau {
        final Netz.Art art;
        final Map<Long, double[]> knoten = new LinkedHashMap<>();   // Schlüssel → {e, n}
        final Map<Long, String[]> knotenInfo = new HashMap<>();     // Schlüssel → {art, name, osm}
        final List<Roh> kanten = new ArrayList<>();
        Netz netz;

        Bau(Netz.Art a) { art = a; }
    }

    // ------------------------------------------------------------------ Ablauf

    public static Netz[] importieren(Rohdaten r, File daten, Consumer<String> log) throws IOException {
        long t0 = System.nanoTime();
        File ov = r.overpass();
        if (!ov.isDirectory()) throw new IOException("Overpass: Ordner " + ov + " fehlt");
        Terrain t = Terrain.load(new File(daten, "gelaende"));
        if (!t.measured()) log.accept("  daten/gelaende fehlt: Höhen und Tiefen aus der Bühne (besser erst Schritt gelaende)");

        // Wege aus den Linien-Ebenen, jeder OSM-Weg einmal
        Map<String, Weg> wege = new LinkedHashMap<>();
        List<Map<String, Object>> pierFlaechen = new ArrayList<>();
        for (String name : new String[]{"01.", "02.", "12."}) {
            File f = ebene(ov, name);
            if (f == null) { log.accept("  Overpass: " + name + "….geojson fehlt"); continue; }
            for (Map<String, Object> ft : Json.features(f)) {
                Map<String, Object> g = map(ft.get("geometry"));
                Map<String, String> tag = tags(ft);
                String id = String.valueOf(ft.get("id"));
                if ("Polygon".equals(g.get("type")) && "pier".equals(tag.get("man_made"))) { pierFlaechen.add(ft); continue; }
                if (!"LineString".equals(g.get("type")) || wege.containsKey(id)) continue;
                if (!tag.containsKey("highway") && !"pier".equals(tag.get("man_made"))) continue;
                List<?> c = (List<?>) g.get("coordinates");
                if (c.size() < 2) continue;
                wege.put(id, new Weg(id, tag, c));
            }
        }
        log.accept(Raster.f("  Overpass: %,d Wege mit highway oder man_made=pier, %d Seebrücken als Fläche", wege.size(), pierFlaechen.size()));

        Bau fuss = new Bau(Netz.Art.FUSS), strasse = new Bau(Netz.Art.STRASSE), bus = new Bau(Netz.Art.BUS);
        List<Weg> wf = new ArrayList<>(), ws = new ArrayList<>(), wb = new ArrayList<>();
        for (Weg w : wege.values()) {
            String hw = w.t("highway");
            boolean pier = w.t("man_made").equals("pier");
            boolean privat = (w.t("access").equals("private") || w.t("access").equals("no"));
            String foot = w.t("foot");
            boolean fussErlaubt = foot.equals("yes") || foot.equals("designated") || foot.equals("permissive");
            if ((pier || (!hw.isEmpty() && !NIE_ZU_FUSS.contains(hw))) && !foot.equals("no") && (!privat || fussErlaubt)) wf.add(w);
            String mv = w.t("motor_vehicle").isEmpty() ? w.t("motorcar") : w.t("motor_vehicle");
            boolean autoVerboten = mv.equals("no") || (privat && !mv.equals("yes") && !mv.equals("designated"));
            if (STRASSEN.contains(hw) && !autoVerboten && !w.t("area").equals("yes")) ws.add(w);
            if ((BUSSTRASSEN.contains(hw) || (hw.equals("service") && (w.ja("bus") || w.ja("psv")))) && !autoVerboten) wb.add(w);
        }
        bauen(fuss, wf, t);
        bauen(strasse, ws, t);
        bauen(bus, wb, t);
        int[] halte = haltestellen(bus, ebene(ov, "09."), log);
        Bau see = see(ov, pierFlaechen, t, log);

        File dir = new File(daten, "netz");
        dir.mkdirs();
        Netz[] netze = new Netz[4];
        Bau[] bauten = {fuss, strasse, bus, see};
        List<String> bericht = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            netze[i] = schreiben(bauten[i], dir, t);
            Route g = netze[i].route();
            int[] komp = komponenten(netze[i]);
            String z = Raster.f("%s: %,d Knoten, %,d Kanten, %.1f km; größtes zusammenhängendes Stück %.1f %% der Knoten, %d Stücke, %,d lose Enden",
                    netze[i].art.oracle, netze[i].knoten.size(), netze[i].kanten.size(), netze[i].laenge() / 1000, 100.0 * komp[0] / Math.max(1, netze[i].knoten.size()),
                    komp[1], lose(netze[i]));
            bericht.add(z);
            log.accept("  " + z);
            if (g.nodeCount() == 0) continue;
        }
        bericht.add(Raster.f("Haltestellen: %d von %d auf eine Busstraße gesetzt (bis %.0f m), %d zu weit weg", halte[0], halte[0] + halte[1], HALT_FANG, halte[1]));
        log.accept("  " + bericht.get(bericht.size() - 1));
        int poi = poi(ov, dir, log);
        bericht.add(Raster.f("Orte (POI) aus den Punkt-Ebenen: %,d nach daten/netz/poi.tsv", poi));

        List<String[]> wegeBericht = pruefwege(netze, dir);
        for (String[] w : wegeBericht) {
            String z = Raster.f("Weg %s (%s): %s", w[0], w[1], w[2]);
            bericht.add(z);
            log.accept("  " + z);
        }
        Properties p = new Properties();
        p.setProperty("quelle", "OpenStreetMap, Overpass-Export vom 30.10.2026 (01.Roads, 02.Footways, 09.BusStops, 11, 12, 16–20)");
        p.setProperty("lizenz", "© OpenStreetMap-Mitwirkende, ODbL; Höhen © GeoBasis-DE/LVermGeo SH (dl-de/by-2-0)");
        p.setProperty("raum", "UTM 32 N (EPSG:25832), Höhe über NHN (DHHN2016)");
        p.setProperty("werkzeug", "com.dan.timmendorf.tools.NetzImport");
        p.setProperty("datum", java.time.LocalDate.now().toString());
        p.setProperty("gestaltet", "Deck der Seebrücken " + DECK + " m NHN, Tempo ohne maxspeed nach der Art, Gehzeit 1,34 m/s, Regattabahn, Fahrwasser ab " + TIEFGANG + " m [G]");
        for (Netz n : netze) p.setProperty(n.art.datei, n.toString());
        try (var o = new java.io.OutputStreamWriter(new java.io.FileOutputStream(new File(dir, "netz.properties")), StandardCharsets.UTF_8)) {
            p.store(o, "Netze der Szene Timmendorf (Phase 3, Datei-Abbild des Oracle-Netzes)");
        }
        bild(netze, wegeBericht, bericht, daten, BILD);
        log.accept(Raster.f("Netze: daten/netz · %.1f s", (System.nanoTime() - t0) / 1e9));
        return netze;
    }

    /** Die Ebene mit der Nummer (z. B. „12.“) im Overpass-Ordner, unabhängig von Umlauten im Namen; null, wenn sie fehlt. */
    static File ebene(File ov, String nr) {
        File[] fs = ov.listFiles((d, n) -> n.startsWith(nr) && n.endsWith(".geojson"));
        return fs == null || fs.length == 0 ? null : fs[0];
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) { return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of(); }

    static Map<String, String> tags(Map<String, Object> ft) {
        Map<String, String> m = new HashMap<>();
        for (var en : map(ft.get("properties")).entrySet()) if (en.getValue() != null) m.put(en.getKey(), String.valueOf(en.getValue()));
        return m;
    }

    // ------------------------------------------------------------------ Teilen an gemeinsamen Punkten

    static void bauen(Bau b, List<Weg> wege, Terrain t) {
        Map<Long, Integer> nutzung = new HashMap<>();
        for (Weg w : wege) for (long k : w.key) nutzung.merge(k, 1, Integer::sum);
        for (Weg w : wege) {
            int c = w.key.length;
            // Einbahn: nur Straßen und Busse (zu Fuß in beide Richtungen)
            int ein = 0;
            if (b.art != Netz.Art.FUSS) {
                String ow = w.t("oneway");
                if (ow.equals("yes") || ow.equals("true") || ow.equals("1")) ein = 1;
                else if (ow.equals("-1") || ow.equals("reverse")) ein = -1;
                else if (!ow.equals("no") && (w.t("junction").equals("roundabout") || w.t("junction").equals("circular")
                        || w.t("highway").equals("motorway") || w.t("highway").equals("motorway_link"))) ein = 1;
                if (b.art == Netz.Art.BUS && (w.t("oneway:bus").equals("no") || w.t("oneway:psv").equals("no"))) ein = 0;
            }
            boolean pier = w.t("man_made").equals("pier");
            boolean bruecke = !w.t("bridge").isEmpty() && !w.t("bridge").equals("no") || pier;
            // über Wasser: mehr als die Hälfte der Punkte über Grund unter NHN
            int nass = 0;
            for (int i = 0; i < c; i++) { double[] xz = Site.fromUtm(w.e[i], w.n[i]); if (t.roh(xz[0], xz[1]) < 0) nass++; }
            boolean deck = pier || (bruecke && nass * 2 > c);
            Roh cur = new Roh(w);
            cur.von = w.key[0];
            cur.pts.add(new double[]{w.e[0], w.n[0]});
            for (int i = 1; i < c; i++) {
                cur.pts.add(new double[]{w.e[i], w.n[i]});
                if (i < c - 1 && nutzung.get(w.key[i]) < 2) continue;
                cur.nach = w.key[i];
                if (cur.von != cur.nach || cur.pts.size() > 2) {
                    if (ein < 0) {
                        java.util.Collections.reverse(cur.pts);
                        long x = cur.von; cur.von = cur.nach; cur.nach = x;
                    }
                    cur.einbahn = ein != 0;
                    cur.bruecke = bruecke;
                    cur.deck = deck;
                    cur.art = deck || w.t("highway").isEmpty() ? "pier" : w.t("highway");
                    b.kanten.add(cur);
                    b.knoten.putIfAbsent(cur.von, cur.pts.get(0));
                    b.knoten.putIfAbsent(cur.nach, cur.pts.get(cur.pts.size() - 1));
                }
                cur = new Roh(w);
                cur.von = w.key[i];
                cur.pts.add(new double[]{w.e[i], w.n[i]});
            }
        }
    }

    // ------------------------------------------------------------------ Haltestellen

    /** Setzt jede Haltestelle als Knoten auf die nächste Kante des Busnetzes (teilt die Kante). {gesetzt, zu weit}. */
    static int[] haltestellen(Bau b, File f, Consumer<String> log) throws IOException {
        if (f == null) { log.accept("  Overpass: 09.BusStops.geojson fehlt"); return new int[2]; }
        int ok = 0, weit = 0;
        for (Map<String, Object> ft : Json.features(f)) {
            Map<String, Object> g = map(ft.get("geometry"));
            if (!"Point".equals(g.get("type"))) continue;
            Map<String, String> tag = tags(ft);
            if (!tag.getOrDefault("highway", "").equals("bus_stop") && !tag.getOrDefault("public_transport", "").equals("platform")) continue;
            List<?> c = (List<?>) g.get("coordinates");
            double lon = ((Number) c.get(0)).doubleValue(), lat = ((Number) c.get(1)).doubleValue();
            double[] u = Geo.UTM32N.forward(lat, lon);
            // nächste Strecke
            Roh best = null;
            int bi = -1;
            double bd = HALT_FANG, bt = 0;
            for (Roh k : b.kanten)
                for (int i = 0; i + 1 < k.pts.size(); i++) {
                    double[] p = k.pts.get(i), q = k.pts.get(i + 1);
                    double dx = q[0] - p[0], dy = q[1] - p[1], l2 = dx * dx + dy * dy;
                    if (l2 < 1e-9) continue;
                    double tt = Math.max(0, Math.min(1, ((u[0] - p[0]) * dx + (u[1] - p[1]) * dy) / l2));
                    double d = Math.hypot(p[0] + tt * dx - u[0], p[1] + tt * dy - u[1]);
                    if (d < bd) { bd = d; best = k; bi = i; bt = tt; }
                }
            if (best == null) { weit++; continue; }
            double[] p = best.pts.get(bi), q = best.pts.get(bi + 1);
            double[] s = {p[0] + bt * (q[0] - p[0]), p[1] + bt * (q[1] - p[1])};
            long key = schluessel(lon, lat);
            String name = tag.getOrDefault("name", "Haltestelle");
            String osm = String.valueOf(ft.get("id"));
            // auf einen vorhandenen Endpunkt fällt die Haltestelle, wenn er näher als 3 m liegt
            double[] a = best.pts.get(0), z = best.pts.get(best.pts.size() - 1);
            if (Math.hypot(a[0] - s[0], a[1] - s[1]) < 3) { b.knotenInfo.put(best.von, new String[]{"haltestelle", name, osm}); ok++; continue; }
            if (Math.hypot(z[0] - s[0], z[1] - s[1]) < 3) { b.knotenInfo.put(best.nach, new String[]{"haltestelle", name, osm}); ok++; continue; }
            if (b.knoten.containsKey(key)) key ^= 0x5bd1e995L;   // zwei Haltestellen am selben Punkt
            Roh zweite = new Roh(best.weg);
            zweite.von = key;
            zweite.nach = best.nach;
            zweite.art = best.art;
            zweite.einbahn = best.einbahn;
            zweite.bruecke = best.bruecke;
            zweite.deck = best.deck;
            zweite.pts.add(s);
            for (int i = bi + 1; i < best.pts.size(); i++) zweite.pts.add(best.pts.get(i));
            while (best.pts.size() > bi + 1) best.pts.remove(best.pts.size() - 1);
            best.pts.add(s);
            best.nach = key;
            b.kanten.add(zweite);
            b.knoten.put(key, s);
            b.knotenInfo.put(key, new String[]{"haltestelle", name, osm});
            ok++;
        }
        return new int[]{ok, weit};
    }

    // ------------------------------------------------------------------ See

    static Bau see(File ov, List<Map<String, Object>> pierFlaechen, Terrain t, Consumer<String> log) throws IOException {
        Bau b = new Bau(Netz.Art.SEE);
        record P(double e, double n, String art, String name, String osm) { }
        List<P> pts = new ArrayList<>();
        double hE0 = Terrain.X0 + Site.E0, hN0 = Site.N0 - Terrain.Z0, span = Terrain.N * Terrain.CELL;
        // Seezeichen im Wasser
        for (String name : new String[]{"17.", "16.", "18."}) {
            File f = ebene(ov, name);
            if (f == null) continue;
            for (Map<String, Object> ft : Json.features(f)) {
                Map<String, Object> g = map(ft.get("geometry"));
                if (!"Point".equals(g.get("type"))) continue;
                Map<String, String> tag = tags(ft);
                String st = tag.getOrDefault("seamark:type", "");
                if (!(st.startsWith("buoy_") || st.startsWith("beacon_") || st.startsWith("light"))) continue;
                double[] u = utm((List<?>) g.get("coordinates"));
                if (u[0] < hE0 || u[0] > hE0 + span || u[1] > hN0 || u[1] < hN0 - span) continue;
                if (tiefe(t, u[0], u[1]) < TIEFE_ENDE) continue;
                String nm = tag.getOrDefault("seamark:name", tag.getOrDefault("name", st));
                pts.add(new P(u[0], u[1], "seezeichen", nm, String.valueOf(ft.get("id"))));
            }
        }
        // Häfen und Liegeplätze: der nächste Punkt mit Wasser darunter (bis 400 m)
        for (String name : new String[]{"11.", "20.", "19."}) {
            File f = ebene(ov, name);
            if (f == null) continue;
            for (Map<String, Object> ft : Json.features(f)) {
                Map<String, Object> g = map(ft.get("geometry"));
                if (!"Point".equals(g.get("type"))) continue;
                Map<String, String> tag = tags(ft);
                String nm = tag.getOrDefault("name", tag.getOrDefault("seamark:name", ""));
                if (nm.isEmpty() && !name.startsWith("19")) continue;
                double[] u = utm((List<?>) g.get("coordinates"));
                if (u[0] < hE0 || u[0] > hE0 + span || u[1] > hN0 || u[1] < hN0 - span) continue;
                double[] w = wasserNahe(t, u[0], u[1], 400, 1.0);
                if (w == null) continue;
                pts.add(new P(w[0], w[1], "hafen", nm.isEmpty() ? "Liegeplatz" : nm, String.valueOf(ft.get("id"))));
            }
        }
        // Köpfe der Seebrücken: der Punkt des Umrisses am weitesten seewärts
        for (Map<String, Object> ft : pierFlaechen) {
            Map<String, String> tag = tags(ft);
            List<?> ring = (List<?>) ((List<?>) map(ft.get("geometry")).get("coordinates")).get(0);
            double[] best = null;
            double bs = 0;
            for (Object o : ring) {
                double[] u = utm((List<?>) o);
                double s = Site.kuestenAbstand(u[0], u[1]);
                if (s < bs) { bs = s; best = u; }
            }
            if (best == null || bs > -40) continue;
            double[] w = wasserNahe(t, best[0], best[1], 60, TIEFE_ENDE);
            if (w == null) continue;
            pts.add(new P(w[0], w[1], "anleger", tag.getOrDefault("name", "Seebrücke"), String.valueOf(ft.get("id"))));
        }
        // Regattabahn [G]
        double a = Math.toRadians(REGATTA_KURS);
        pts.add(new P(Site.E0 + REGATTA_LEE * Math.sin(a), Site.N0 + REGATTA_LEE * Math.cos(a), "regatta", "Regatta Lee [G]", ""));
        pts.add(new P(Site.E0 + REGATTA_LUV * Math.sin(a), Site.N0 + REGATTA_LUV * Math.cos(a), "regatta", "Regatta Luv [G]", ""));
        pts.add(new P(Site.E0 + (REGATTA_LEE - 150) * Math.sin(a) - 120 * Math.cos(a), Site.N0 + (REGATTA_LEE - 150) * Math.cos(a) + 120 * Math.sin(a),
                "regatta", "Regatta Start, Startschiff [G]", ""));
        // doppelte Punkte (unter 15 m) zusammenlegen
        List<P> uniq = new ArrayList<>();
        for (P p : pts) {
            boolean dup = false;
            for (P q : uniq) if (Math.hypot(p.e - q.e, p.n - q.n) < 15) { dup = true; break; }
            if (!dup) uniq.add(p);
        }
        for (P p : uniq) {
            long k = Double.doubleToLongBits(p.e) * 31 + Double.doubleToLongBits(p.n);
            b.knoten.put(k, new double[]{p.e, p.n});
            b.knotenInfo.put(k, new String[]{p.art, p.name, p.osm});
        }
        // Kanten: je Knoten die nächsten Nachbarn, zu denen die gerade Fahrt tief genug ist
        List<Long> keys = new ArrayList<>(b.knoten.keySet());
        Map<String, Boolean> fertig = new HashMap<>();
        int geprueft = 0;
        for (int i = 0; i < keys.size(); i++) {
            double[] p = b.knoten.get(keys.get(i));
            List<Integer> nb = new ArrayList<>();
            for (int j = 0; j < keys.size(); j++) if (j != i) nb.add(j);
            nb.sort(Comparator.comparingDouble(j -> Math.hypot(b.knoten.get(keys.get(j))[0] - p[0], b.knoten.get(keys.get(j))[1] - p[1])));
            int found = 0;
            for (int j : nb) {
                if (found >= SEE_NACHBARN) break;
                double[] q = b.knoten.get(keys.get(j));
                double d = Math.hypot(q[0] - p[0], q[1] - p[1]);
                if (d > SEE_REICHWEITE) break;
                String pk = Math.min(i, j) + "_" + Math.max(i, j);
                Boolean schon = fertig.get(pk);
                if (schon != null) { if (schon) found++; continue; }
                geprueft++;
                double min = fahrbar(t, p, q, d);
                boolean ok = min >= TIEFGANG;
                fertig.put(pk, ok);
                if (!ok) continue;
                found++;
                Roh r = new Roh(null);
                r.von = keys.get(i);
                r.nach = keys.get(j);
                r.art = b.knotenInfo.get(r.von)[0].equals("regatta") && b.knotenInfo.get(r.nach)[0].equals("regatta") ? "regatta" : "fahrwasser";
                r.pts.add(p);
                r.pts.add(q);
                r.minTiefe = min;
                b.kanten.add(r);
            }
        }
        log.accept(Raster.f("  See: %d Knoten (Seezeichen, Häfen, Seebrücken, Regattabahn), %,d Fahrten geprüft, %d Kanten ab %.1f m Tiefe",
                b.knoten.size(), geprueft, b.kanten.size(), TIEFGANG));
        return b;
    }

    static double[] utm(List<?> c) {
        return Geo.UTM32N.forward(((Number) c.get(1)).doubleValue(), ((Number) c.get(0)).doubleValue());
    }

    /** Wassertiefe (m, positiv) am Punkt UTM. */
    static double tiefe(Terrain t, double e, double n) {
        double[] xz = Site.fromUtm(e, n);
        return -t.roh(xz[0], xz[1]);
    }

    /** Der nächste Punkt bis r m mit mindestens min m Wasser (spiralförmig in 10-m-Schritten), sonst null. */
    static double[] wasserNahe(Terrain t, double e, double n, double r, double min) {
        if (tiefe(t, e, n) >= min) return new double[]{e, n};
        for (double rr = 10; rr <= r; rr += 10)
            for (int k = 0; k < 24; k++) {
                double a = k * Math.PI / 12, x = e + rr * Math.cos(a), y = n + rr * Math.sin(a);
                if (tiefe(t, x, y) >= min) return new double[]{x, y};
            }
        return null;
    }

    /** Kleinste Tiefe entlang der geraden Fahrt (die ersten und letzten {@link #ENDE} m zählen nur unter {@link #TIEFE_ENDE}). */
    static double fahrbar(Terrain t, double[] p, double[] q, double d) {
        int steps = Math.max(2, (int) Math.ceil(d / 10));
        double min = Double.MAX_VALUE;
        for (int s = 0; s <= steps; s++) {
            double f = s / (double) steps, x = p[0] + f * (q[0] - p[0]), y = p[1] + f * (q[1] - p[1]);
            double tf = tiefe(t, x, y);
            double rand = Math.min(f, 1 - f) * d;
            if (rand < ENDE) { if (tf < TIEFE_ENDE) return tf; continue; }
            min = Math.min(min, tf);
        }
        return min == Double.MAX_VALUE ? TIEFGANG : min;
    }

    // ------------------------------------------------------------------ Schreiben

    static Netz schreiben(Bau b, File dir, Terrain t) throws IOException {
        // Knoten nummerieren (nach Lage, damit die Nummern bei gleichem Export gleich bleiben)
        List<Long> keys = new ArrayList<>(b.knoten.keySet());
        keys.sort(Comparator.comparingDouble((Long k) -> b.knoten.get(k)[1]).reversed().thenComparingDouble(k -> b.knoten.get(k)[0]));
        Map<Long, Integer> id = new HashMap<>();
        for (int i = 0; i < keys.size(); i++) id.put(keys.get(i), i + 1);
        // Höhe je Knoten: Deck, wenn eine Kante dort Deck ist
        Map<Long, Boolean> amDeck = new HashMap<>();
        Map<Long, Integer> grad = new HashMap<>();
        for (Roh r : b.kanten) {
            if (r.deck) { amDeck.put(r.von, true); amDeck.put(r.nach, true); }
            grad.merge(r.von, 1, Integer::sum);
            grad.merge(r.nach, 1, Integer::sum);
        }
        List<Netz.Knoten> kn = new ArrayList<>();
        Map<Long, Double> hoehe = new HashMap<>();
        for (Long k : keys) {
            double[] p = b.knoten.get(k);
            double g = boden(t, p[0], p[1]);
            double h = b.art == Netz.Art.SEE ? 0 : amDeck.getOrDefault(k, false) ? Math.max(g, DECK) : g;
            hoehe.put(k, h);
            String[] info = b.knotenInfo.get(k);
            int gr = grad.getOrDefault(k, 0);
            String art = info != null ? info[0] : gr <= 1 ? "ende" : gr == 2 ? "punkt" : "kreuzung";
            kn.add(new Netz.Knoten(id.get(k), p[0], p[1], h, art, info != null ? clean(info[1]) : "", info != null ? info[2] : ""));
        }
        // Kanten: Stützpunkte alle DICHTE m, Höhen, Länge im Raum, Zeit
        List<Netz.Kante> ka = new ArrayList<>();
        b.kanten.sort(Comparator.comparingInt((Roh r) -> id.get(r.von)).thenComparingInt(r -> id.get(r.nach)));
        int kid = 0;
        for (Roh r : b.kanten) {
            List<double[]> dicht = new ArrayList<>();
            for (int i = 0; i < r.pts.size(); i++) {
                double[] p = r.pts.get(i);
                if (i > 0) {
                    double[] q = r.pts.get(i - 1);
                    double d = Math.hypot(p[0] - q[0], p[1] - q[1]);
                    int s = r.bruecke || b.art == Netz.Art.SEE ? 1 : (int) Math.ceil(d / DICHTE);
                    for (int k = 1; k < s; k++) dicht.add(new double[]{q[0] + (p[0] - q[0]) * k / s, q[1] + (p[1] - q[1]) * k / s});
                }
                dicht.add(p);
            }
            int c = dicht.size();
            double[] pts = new double[c * 3];
            double[] s = new double[c];
            for (int i = 1; i < c; i++) s[i] = s[i - 1] + Math.hypot(dicht.get(i)[0] - dicht.get(i - 1)[0], dicht.get(i)[1] - dicht.get(i - 1)[1]);
            double h0 = hoehe.get(r.von), h1 = hoehe.get(r.nach);
            for (int i = 0; i < c; i++) {
                double[] p = dicht.get(i);
                double h;
                if (i == 0) h = h0;
                else if (i == c - 1) h = h1;
                else if (b.art == Netz.Art.SEE) h = 0;
                else if (r.deck) h = Math.max(DECK, boden(t, p[0], p[1]));
                else if (r.bruecke || "yes".equals(r.weg == null ? "" : r.weg.t("tunnel"))) h = h0 + (h1 - h0) * s[i] / Math.max(1e-6, s[c - 1]);
                else h = boden(t, p[0], p[1]);
                pts[i * 3] = p[0];
                pts[i * 3 + 1] = p[1];
                pts[i * 3 + 2] = h;
            }
            // Länge über die gerundeten Stützpunkte (so wie sie in der Datei und in Oracle stehen)
            for (int i = 0; i < pts.length; i++) pts[i] = Math.round(pts[i] * 100) / 100.0;
            double len = 0;
            for (int i = 1; i < c; i++)
                len += Math.sqrt(sq(pts[i * 3] - pts[i * 3 - 3]) + sq(pts[i * 3 + 1] - pts[i * 3 - 2]) + sq(pts[i * 3 + 2] - pts[i * 3 - 1]));
            if (len < 0.01) continue;
            Weg w = r.weg;
            double tempo = b.art == Netz.Art.STRASSE || b.art == Netz.Art.BUS ? tempo(w) : 0;
            double zeit = switch (b.art) {
                case FUSS -> len / (1.34 * (r.art.equals("steps") ? 0.5 : 1));
                case SEE -> len / (5 * 0.5144);   // 5 kn [G]
                default -> len / (tempo / 3.6);
            };
            String zugang = b.art == Netz.Art.SEE ? Raster.f("tiefe %.1f", r.minTiefe).replace(',', '.') : w == null ? "" : first(w.t("access"), w.t("foot"));
            ka.add(new Netz.Kante(++kid, id.get(r.von), id.get(r.nach), round2(len), round2(zeit), r.art, w == null ? "" : clean(first(w.t("name"), w.t("ref"))),
                    r.einbahn, w == null ? "wasser" : clean(w.t("surface")), w == null ? "" : clean(w.t("lit")), breite(r, w),
                    clean(zugang), w == null ? 0 : ebene(w.t("layer")), r.bruecke, tempo, w == null ? "" : w.osm, pts));
        }
        Netz n = new Netz(b.art, kn, ka);
        try (PrintWriter o = new PrintWriter(new File(dir, b.art.datei + Netz.KNOTEN), StandardCharsets.UTF_8)) {
            o.println(String.join("\t", Netz.KNOTEN_SPALTEN));
            for (Netz.Knoten k : kn)
                o.println(k.id() + "\t" + f2(k.e()) + "\t" + f2(k.n()) + "\t" + f2(k.h()) + "\t" + k.art() + "\t" + k.name() + "\t" + k.osm());
        }
        try (PrintWriter o = new PrintWriter(new File(dir, b.art.datei + Netz.KANTEN), StandardCharsets.UTF_8)) {
            o.println(String.join("\t", Netz.KANTEN_SPALTEN));
            StringBuilder sb = new StringBuilder();
            for (Netz.Kante k : ka) {
                sb.setLength(0);
                sb.append(k.id()).append('\t').append(k.von()).append('\t').append(k.nach()).append('\t').append(f2(k.laenge())).append('\t').append(f2(k.zeit()))
                        .append('\t').append(k.art()).append('\t').append(k.name()).append('\t').append(k.einbahn() ? 1 : 0).append('\t').append(k.belag())
                        .append('\t').append(k.licht()).append('\t').append(f2(k.breite())).append('\t').append(k.zugang()).append('\t').append(k.ebene())
                        .append('\t').append(k.bruecke() ? 1 : 0).append('\t').append(f2(k.tempo())).append('\t').append(k.osm()).append('\t');
                double[] p = k.punkte();
                for (int i = 0; i < p.length; i += 3) {
                    if (i > 0) sb.append(' ');
                    sb.append(f2(p[i])).append(',').append(f2(p[i + 1])).append(',').append(f2(p[i + 2]));
                }
                o.println(sb);
            }
        }
        return n;
    }

    static double boden(Terrain t, double e, double n) {
        double[] xz = Site.fromUtm(e, n);
        return t.roh(xz[0], xz[1]);
    }

    static double sq(double v) { return v * v; }
    static double round2(double v) { return Math.round(v * 100) / 100.0; }
    static String f2(double v) { return String.format(Locale.ROOT, "%.2f", v); }
    static String first(String a, String b) { return a.isEmpty() ? b : a; }
    static String clean(String s) { return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').strip(); }

    static int ebene(String s) {
        try { return s.isEmpty() ? 0 : (int) Math.round(Double.parseDouble(s.split(";")[0])); } catch (NumberFormatException e) { return 0; }
    }

    /** Tempo (km/h) aus maxspeed, sonst nach der Art [G]. */
    static double tempo(Weg w) {
        String m = w.t("maxspeed");
        if (!m.isEmpty()) {
            String v = m.toLowerCase(Locale.ROOT);
            if (v.contains("walk") || v.contains("living_street")) return 7;
            if (v.contains("urban")) return 50;
            if (v.contains("rural")) return 100;
            if (v.equals("none") || v.contains("motorway")) return 130;
            try {
                double d = Double.parseDouble(v.replaceAll("[^0-9.].*", ""));
                return v.contains("mph") ? d * 1.609 : d;
            } catch (NumberFormatException e) { /* nach der Art */ }
        }
        return switch (w.t("highway")) {
            case "motorway" -> 130;
            case "motorway_link", "trunk_link" -> 60;
            case "trunk" -> 100;
            case "primary", "primary_link" -> 70;
            case "secondary", "secondary_link" -> 60;
            case "tertiary", "tertiary_link", "unclassified" -> 50;
            case "residential" -> 30;
            case "living_street" -> 7;
            default -> 20;
        };
    }

    /** Breite (m) aus width, sonst nach der Art [G]. */
    static double breite(Roh r, Weg w) {
        if (w == null) return 50;
        String s = w.t("width").replace(',', '.').replaceAll("[^0-9.].*", "");
        try { if (!s.isEmpty()) return Double.parseDouble(s); } catch (NumberFormatException e) { /* nach der Art */ }
        return switch (r.art) {
            case "pier" -> 5;
            case "footway", "cycleway", "steps" -> 2;
            case "path", "bridleway" -> 1.5;
            case "track" -> 3;
            case "pedestrian" -> 6;
            case "service" -> 4;
            case "living_street", "residential", "unclassified" -> 6;
            case "tertiary", "tertiary_link", "secondary", "secondary_link" -> 7.5;
            case "primary", "primary_link", "trunk", "trunk_link" -> 8;
            case "motorway", "motorway_link" -> 11;
            default -> 3;
        };
    }

    // ------------------------------------------------------------------ Kennzahlen

    /** {Knoten im größten Stück, Zahl der Stücke} (ungerichtet). */
    static int[] komponenten(Netz n) {
        Map<Integer, Integer> idx = new HashMap<>();
        for (int i = 0; i < n.knoten.size(); i++) idx.put(n.knoten.get(i).id(), i);
        int[] par = new int[n.knoten.size()];
        for (int i = 0; i < par.length; i++) par[i] = i;
        for (Netz.Kante k : n.kanten) {
            int a = find(par, idx.get(k.von())), b = find(par, idx.get(k.nach()));
            if (a != b) par[a] = b;
        }
        Map<Integer, Integer> size = new HashMap<>();
        for (int i = 0; i < par.length; i++) size.merge(find(par, i), 1, Integer::sum);
        int mx = 0;
        for (int v : size.values()) mx = Math.max(mx, v);
        return new int[]{mx, size.size()};
    }

    static int find(int[] p, int i) { while (p[i] != i) { p[i] = p[p[i]]; i = p[i]; } return i; }

    static int lose(Netz n) {
        Map<Integer, Integer> g = new HashMap<>();
        for (Netz.Kante k : n.kanten) { g.merge(k.von(), 1, Integer::sum); g.merge(k.nach(), 1, Integer::sum); }
        int c = 0;
        for (int v : g.values()) if (v == 1) c++;
        return c;
    }

    // ------------------------------------------------------------------ Orte (POI)

    static int poi(File ov, File dir, Consumer<String> log) throws IOException {
        String[] ebenen = {"04.", "05.", "06.", "07.", "08.", "09.", "11.", "13.", "14.", "15.", "17.", "18.", "19.", "20."};
        Map<String, String[]> rows = new TreeMap<>();
        for (String nr : ebenen) {
            File f = ebene(ov, nr);
            if (f == null) continue;
            String eb = f.getName().replaceAll("\\.geojson$", "");
            for (Map<String, Object> ft : Json.features(f)) {
                Map<String, Object> g = map(ft.get("geometry"));
                if (!"Point".equals(g.get("type"))) continue;
                String id = String.valueOf(ft.get("id"));
                if (rows.containsKey(id)) continue;
                Map<String, String> tag = tags(ft);
                double[] u = utm((List<?>) g.get("coordinates"));
                String art = "";
                for (String k : new String[]{"seamark:type", "amenity", "tourism", "leisure", "shop", "highway", "natural", "man_made", "harbour", "sport"})
                    if (tag.containsKey(k)) { art = k + "=" + tag.get(k); break; }
                rows.put(id, new String[]{id, eb.substring(3), f2(u[0]), f2(u[1]), clean(art), clean(tag.getOrDefault("name", tag.getOrDefault("seamark:name", "")))});
            }
        }
        try (PrintWriter o = new PrintWriter(new File(dir, "poi.tsv"), StandardCharsets.UTF_8)) {
            o.println("osm\tebene\tost\tnord\tart\tname");
            for (String[] r : rows.values()) o.println(String.join("\t", r));
        }
        log.accept(Raster.f("  POI: %,d Punkte aus %d Ebenen", rows.size(), ebenen.length));
        return rows.size();
    }

    // ------------------------------------------------------------------ Prüfwege

    /** Die Prüfwege: Netz, Name, von, nach (Knotennummern), Länge (m), Kosten. */
    record Pruefweg(Netz.Art netz, String name, double[] von, double[] nach) { }

    static final Pruefweg[] PRUEFWEGE = {
            new Pruefweg(Netz.Art.FUSS, "Seebrücke Landende – Kopf der Seebrücke", new double[]{Site.E0, Site.N0}, new double[]{Site.SCHLEIFE.e(), Site.SCHLEIFE.n()}),
            new Pruefweg(Netz.Art.FUSS, "Seebrücke – Wolkenlos", new double[]{Site.E0, Site.N0}, new double[]{Site.WOLKENLOS.e(), Site.WOLKENLOS.n()}),
            new Pruefweg(Netz.Art.FUSS, "Seebrücke – Niendorfer Hafen", new double[]{Site.E0, Site.N0}, new double[]{Site.NIENDORF.e(), Site.NIENDORF.n()}),
            new Pruefweg(Netz.Art.FUSS, "Seebrücke – Seebrücke Niendorf", new double[]{Site.E0, Site.N0}, new double[]{Site.SEEBR_NIENDORF.e(), Site.SEEBR_NIENDORF.n()}),
            new Pruefweg(Netz.Art.FUSS, "Seebrücke – Brodtener Ufer", new double[]{Site.E0, Site.N0}, new double[]{Site.BRODTEN.e(), Site.BRODTEN.n()}),
            new Pruefweg(Netz.Art.STRASSE, "Maritim Seehotel – Niendorfer Hafen", new double[]{Site.SEEHOTEL.e(), Site.SEEHOTEL.n()}, new double[]{Site.NIENDORF.e(), Site.NIENDORF.n()}),
            new Pruefweg(Netz.Art.STRASSE, "Maritim Seehotel – Travemünde, Maritim", new double[]{Site.SEEHOTEL.e(), Site.SEEHOTEL.n()}, new double[]{Site.TRAVEMUENDE.e(), Site.TRAVEMUENDE.n()}),
            new Pruefweg(Netz.Art.STRASSE, "Maritim Seehotel – Seebrücke Haffkrug", new double[]{Site.SEEHOTEL.e(), Site.SEEHOTEL.n()}, new double[]{Site.HAFFKRUG.e(), Site.HAFFKRUG.n()}),
            new Pruefweg(Netz.Art.BUS, "Bus: Maritim Seehotel – Niendorfer Hafen", new double[]{Site.SEEHOTEL.e(), Site.SEEHOTEL.n()}, new double[]{Site.NIENDORF.e(), Site.NIENDORF.n()}),
            new Pruefweg(Netz.Art.SEE, "See: Niendorfer Hafen – Passat", new double[]{Site.NIENDORF.e(), Site.NIENDORF.n()}, new double[]{Site.PASSAT.e(), Site.PASSAT.n()}),
            new Pruefweg(Netz.Art.SEE, "See: Seebrücke – Regatta Luv", new double[]{Site.SCHLEIFE.e(), Site.SCHLEIFE.n()},
                    new double[]{Site.E0 + REGATTA_LUV * Math.sin(Math.toRadians(REGATTA_KURS)), Site.N0 + REGATTA_LUV * Math.cos(Math.toRadians(REGATTA_KURS))}),
    };

    /** Rechnet die Prüfwege mit {@code route} und schreibt {@code pruefwege.tsv}; gibt {Name, Netz, Text, …Knotenfolge} zurück. */
    static List<String[]> pruefwege(Netz[] netze, File dir) throws IOException {
        List<String[]> out = new ArrayList<>();
        Map<Netz.Art, Route> graphen = new HashMap<>();
        Map<Netz.Art, Set<Integer>> haupt = new HashMap<>();
        try (PrintWriter o = new PrintWriter(new File(dir, "pruefwege.tsv"), StandardCharsets.UTF_8)) {
            o.println("netz\tname\tvon\tnach\tlaenge\tzeit\tknoten");
            for (Pruefweg w : PRUEFWEGE) {
                Netz n = netze[w.netz().ordinal()];
                if (n.knoten.isEmpty()) continue;
                Route g = graphen.computeIfAbsent(w.netz(), k -> n.route());
                Set<Integer> hs = haupt.computeIfAbsent(w.netz(), k -> hauptstueck(n));
                Netz.Knoten a = naechster(n, hs, w.von()), b = naechster(n, hs, w.nach());
                Route.Path p = g.shortest(g.indexOf(a.id()), g.indexOf(b.id()), Route.distance());
                double zeit = zeitAuf(n, g, p);
                StringBuilder ids = new StringBuilder();
                for (int i : p.nodes()) { long id = g.node(i).id(); if (id > 0) { if (ids.length() > 0) ids.append(' '); ids.append(id); } }
                o.println(w.netz().oracle + "\t" + w.name() + "\t" + a.id() + "\t" + b.id() + "\t" + f2(p.found() ? p.length() : -1) + "\t" + f2(zeit) + "\t" + ids);
                String txt = p.found()
                        ? Raster.f("%.0f m, %s; Knoten %d → %d (%.0f m und %.0f m neben dem Ziel)", p.length(), dauer(zeit), a.id(), b.id(),
                        Math.hypot(a.e() - w.von()[0], a.n() - w.von()[1]), Math.hypot(b.e() - w.nach()[0], b.n() - w.nach()[1]))
                        : Raster.f("kein Weg von Knoten %d nach %d", a.id(), b.id());
                out.add(new String[]{w.name(), w.netz().oracle, txt, ids.toString()});
            }
        }
        return out;
    }

    /** Die Knotennummern des größten zusammenhängenden Stücks. */
    static Set<Integer> hauptstueck(Netz n) {
        Map<Integer, Integer> idx = new HashMap<>();
        for (int i = 0; i < n.knoten.size(); i++) idx.put(n.knoten.get(i).id(), i);
        int[] par = new int[n.knoten.size()];
        for (int i = 0; i < par.length; i++) par[i] = i;
        for (Netz.Kante k : n.kanten) {
            int a = find(par, idx.get(k.von())), b = find(par, idx.get(k.nach()));
            if (a != b) par[a] = b;
        }
        Map<Integer, Integer> size = new HashMap<>();
        for (int i = 0; i < par.length; i++) size.merge(find(par, i), 1, Integer::sum);
        int best = size.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(-1);
        Set<Integer> s = new java.util.HashSet<>();
        for (int i = 0; i < par.length; i++) if (find(par, i) == best) s.add(n.knoten.get(i).id());
        return s;
    }

    /** Der nächste Knoten im größten Stück (Prüfwege sollen nicht an einem abgerissenen Steg beginnen). */
    static Netz.Knoten naechster(Netz n, Set<Integer> stueck, double[] p) {
        Netz.Knoten best = null;
        double bd = Double.MAX_VALUE;
        for (Netz.Knoten k : n.knoten) {
            if (!stueck.contains(k.id())) continue;
            double d = Math.hypot(k.e() - p[0], k.n() - p[1]);
            if (d < bd) { bd = d; best = k; }
        }
        return best;
    }

    /** Zeit des Weges über die Zeiten der Kanten (anteilig je Stützpunkt). */
    static double zeitAuf(Netz n, Route g, Route.Path p) {
        if (!p.found()) return -1;
        Map<Long, Netz.Kante> kanteZu = new HashMap<>();
        for (Netz.Kante k : n.kanten) kanteZu.put((long) k.id(), k);
        double s = 0;
        int[] v = p.nodes();
        for (int i = 1; i < v.length; i++) {
            Route.Node a = g.node(v[i - 1]), b = g.node(v[i]);
            double l = Math.sqrt(sq(a.x() - b.x()) + sq(a.y() - b.y()) + sq(a.z() - b.z()));
            // Kante aus der Nummer des Stützpunkts, sonst die Kante zwischen zwei Knoten
            Netz.Kante k = null;
            long id = a.id() < 0 ? a.id() : b.id();
            if (id < 0) k = kanteZu.get(-id / 100_000);
            else for (Netz.Kante c : n.kanten) if ((c.von() == a.id() && c.nach() == b.id()) || (c.von() == b.id() && c.nach() == a.id())) { k = c; break; }
            if (k != null && k.laenge() > 0) s += k.zeit() * l / k.laenge();
        }
        return s;
    }

    static String dauer(double s) {
        if (s < 0) return "–";
        if (s < 3600) return Raster.f("%d:%02d min", (int) (s / 60), (int) (s % 60));
        return Raster.f("%d h %02d min", (int) (s / 3600), (int) (s % 3600 / 60));
    }

    // ------------------------------------------------------------------ Prüfbild

    /** Prüfbild: links der Kern über dem Luftbild mit allen Netzen und den Prüfwegen, rechts das Hauptraster. */
    static void bild(Netz[] netze, List<String[]> wege, List<String> bericht, File daten, File png) throws IOException {
        bild(netze, wege, bericht, daten, png, "Timmendorf Phase 3 · Netze aus Overpass (OSM, 30.10.2026) · Höhen aus DGM1");
    }

    static void bild(Netz[] netze, List<String[]> wege, List<String> bericht, File daten, File png, String titel) throws IOException {
        int S = 1536, R = 1024, W = S + R + 30, H = S + 20 + 22 * (bericht.size() + 4);
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(W, H, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new java.awt.Color(0x1d2328));
        g.fillRect(0, 0, W, H);
        int KN = Terrain.KN, N = Terrain.N;
        // Kern: Luftbild (abgedunkelt), sonst Höhe
        File lb = new File(daten, "kern/luftbild.rgbi");
        Terrain t = Terrain.load(new File(daten, "gelaende"));
        double ks = KN / (double) S;
        byte[] rgbi = lb.isFile() && lb.length() == (long) KN * KN * 4 ? java.nio.file.Files.readAllBytes(lb.toPath()) : null;
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++) {
                int i = (int) (x * ks), j = (int) (y * ks), k = j * KN + i;
                int c;
                if (rgbi != null && (rgbi[k * 4] | rgbi[k * 4 + 1] | rgbi[k * 4 + 2]) != 0)
                    c = dunkel((rgbi[k * 4] & 0xff) << 16 | (rgbi[k * 4 + 1] & 0xff) << 8 | (rgbi[k * 4 + 2] & 0xff), 0.55);
                else c = dunkel(Raster.hoehenfarbe((float) t.roh(Terrain.kcx(i), Terrain.kcz(j)), 1), 0.5);
                img.setRGB(10 + x, 10 + y, c);
            }
        // Hauptraster: Luftbild des Hauptrasters, sonst Höhe
        File lh = new File(daten, "gelaende/luftbild_haupt.rgb");
        byte[] rgb = lh.isFile() && lh.length() == (long) N * N * 3 ? java.nio.file.Files.readAllBytes(lh.toPath()) : null;
        double hs = N / (double) R;
        for (int y = 0; y < R; y++)
            for (int x = 0; x < R; x++) {
                int i = (int) (x * hs), j = (int) (y * hs), q = j * N + i;
                int c;
                if (rgb != null && (rgb[q * 3] | rgb[q * 3 + 1] | rgb[q * 3 + 2]) != 0) c = dunkel((rgb[q * 3] & 0xff) << 16 | (rgb[q * 3 + 1] & 0xff) << 8 | (rgb[q * 3 + 2] & 0xff), 0.5);
                else c = dunkel(Raster.hoehenfarbe((float) Terrain.decode(t.h[q]), 1), 0.5);
                img.setRGB(20 + S + x, 10 + y, c);
            }
        java.awt.Color[] farbe = {new java.awt.Color(0xffb04a), new java.awt.Color(0xf4f4f4), new java.awt.Color(0xff5a8c), new java.awt.Color(0x5ad2ff)};
        float[] dicke = {1.0f, 1.6f, 2.2f, 1.4f};
        int[] reihenfolge = {0, 1, 2, 3};
        for (int pass = 0; pass < 2; pass++) {
            boolean kern = pass == 0;
            g.setClip(kern ? new java.awt.Rectangle(10, 10, S, S) : new java.awt.Rectangle(20 + S, 10, R, R));
            double sc = kern ? ks : hs * Terrain.CELL, ox = kern ? 10 : 20 + S, x0 = kern ? Terrain.KX0 : Terrain.X0, z0 = kern ? Terrain.KZ0 : Terrain.Z0;
            for (int ni : reihenfolge) {
                Netz n = netze[ni];
                if (ni == 2 && kern) continue;   // Bus liegt auf den Straßen; im Kern nur die Haltestellen
                g.setColor(farbe[ni]);
                g.setStroke(new java.awt.BasicStroke(kern ? dicke[ni] : dicke[ni] * 0.7f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND,
                        10, ni == 3 ? new float[]{6, 4} : null, 0));
                for (Netz.Kante k : n.kanten) {
                    java.awt.geom.Path2D.Double p = new java.awt.geom.Path2D.Double();
                    double[] pt = k.punkte();
                    for (int i = 0; i < pt.length; i += 3) {
                        double px = ox + (pt[i] - Site.E0 - x0) / sc, py = 10 + (Site.N0 - pt[i + 1] - z0) / sc;
                        if (i == 0) p.moveTo(px, py); else p.lineTo(px, py);
                    }
                    if (k.art().equals("pier") || k.art().equals("regatta")) g.setColor(ni == 3 ? new java.awt.Color(0xffe14a) : new java.awt.Color(0x7dff7a));
                    else g.setColor(farbe[ni]);
                    g.draw(p);
                }
            }
            // Haltestellen und Seeknoten
            for (Netz.Knoten k : netze[2].knoten) {
                if (!k.art().equals("haltestelle")) continue;
                double px = ox + (k.x() - x0) / sc, py = 10 + (k.z() - z0) / sc;
                g.setColor(farbe[2]);
                g.fill(new java.awt.geom.Ellipse2D.Double(px - 3, py - 3, 6, 6));
            }
            for (Netz.Knoten k : netze[3].knoten) {
                double px = ox + (k.x() - x0) / sc, py = 10 + (k.z() - z0) / sc;
                g.setColor(k.art().equals("regatta") ? new java.awt.Color(0xffe14a) : farbe[3]);
                g.fill(new java.awt.geom.Ellipse2D.Double(px - 3.5, py - 3.5, 7, 7));
            }
            // Prüfwege
            g.setStroke(new java.awt.BasicStroke(kern ? 4f : 3f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
            for (String[] w : wege) {
                if (w[3].isEmpty()) continue;
                Netz n = netze[netzIndex(w[1])];
                g.setColor(new java.awt.Color(0x00ff9c));
                java.awt.geom.Path2D.Double p = new java.awt.geom.Path2D.Double();
                boolean first = true;
                for (String s : w[3].split(" ")) {
                    Netz.Knoten k = n.knoten(Integer.parseInt(s));
                    double px = ox + (k.x() - x0) / sc, py = 10 + (k.z() - z0) / sc;
                    if (first) { p.moveTo(px, py); first = false; } else p.lineTo(px, py);
                }
                g.draw(p);
            }
            g.setClip(null);
        }
        g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 15));
        g.setColor(new java.awt.Color(0xee, 0xee, 0xee));
        g.drawString("Kern 4,6 km über dem DOP20: Fuß (orange), Straße (weiß), Seebrücken (grün), Haltestellen (rosa), See (blau), Regatta (gelb), Prüfwege (hellgrün)", 16, 30);
        g.drawString("Hauptraster 20,5 km: alle Netze, Bus rosa", 26 + S, 30);
        g.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 13));
        int y = S + 34;
        g.drawString(titel + " · " + java.time.LocalDate.now(), 12, y);
        for (String z : bericht) for (String l : Raster.wrap(z, 190)) { y += 18; g.drawString(l, 12, y); }
        g.dispose();
        File d = png.getAbsoluteFile().getParentFile();
        if (d != null) d.mkdirs();
        javax.imageio.ImageIO.write(img, "png", png);
    }

    static int netzIndex(String oracle) {
        for (Netz.Art a : Netz.Art.values()) if (a.oracle.equals(oracle)) return a.ordinal();
        return 0;
    }

    static int dunkel(int rgb, double f) {
        int r = (int) (((rgb >> 16) & 0xff) * f), gg = (int) (((rgb >> 8) & 0xff) * f), b = (int) ((rgb & 0xff) * f);
        return r << 16 | gg << 8 | b;
    }

    static double[] arr(double... v) { return Arrays.copyOf(v, v.length); }
}
