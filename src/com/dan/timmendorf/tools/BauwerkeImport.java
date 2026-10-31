package com.dan.timmendorf.tools;

import com.dan.timmendorf.scene.Site;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Die Bauwerke am Wasser (Phase 9, Schritt {@code bauwerke}): aus dem Overpass-Export die Seebrücken und Stege
 * ({@code 12.Seebrücken}: {@code man_made=pier} als Fläche oder Linie), die Wachen und Türme der DLRG ({@code 03.Buildings}
 * mit {@code emergency=lifeguard}, {@code 14.Beach} mit {@code lifeguard=tower|yes}) und die Leuchtfeuer mit ihren
 * Kennungen ({@code 18.Lighthouses}, Seezeichen-Schlüssel {@code seamark:light:*}). Geschrieben nach
 * {@code daten/bauwerke/bauwerke.tsv} in Koordinaten der Szene, nur was im Fernfeld liegt.
 * <p>
 * Zeilen: {@code art \t name \t merkmale \t punkte}; Arten {@code bruecke} (Fläche, Umriss geschlossen), {@code steg}
 * (Linie), {@code dlrg} (Punkt; Merkmal {@code turm} oder {@code wache}), {@code feuer} (Punkt; Merkmale je Licht
 * {@code licht=Kennung Farbe Wiederkehr Höhe Tragweite von–bis}). Merkmale als {@code schluessel=wert} mit {@code |}
 * getrennt; Punkte {@code x,z} mit Leerzeichen.
 */
public final class BauwerkeImport {
    public static final String ORDNER = "bauwerke", DATEI = "bauwerke.tsv";

    private BauwerkeImport() { }

    public static int[] importieren(Rohdaten r, File daten, Consumer<String> log) throws IOException {
        File ov = r.overpass();
        if (!ov.isDirectory()) throw new IOException("Overpass: Ordner " + ov + " fehlt");
        double[] ff = Site.FERNFELD.scene();
        List<String> zeilen = new ArrayList<>();
        int[] n = new int[4];
        // Seebrücken und Stege
        File f12 = NetzImport.ebene(ov, "12.");
        if (f12 != null) for (Map<String, Object> ft : Json.features(f12)) {
            Map<String, String> t = NetzImport.tags(ft);
            if (!"pier".equals(t.get("man_made"))) continue;
            Map<String, Object> g = NetzImport.map(ft.get("geometry"));
            String typ = String.valueOf(g.get("type"));
            List<?> c = (List<?>) g.get("coordinates");
            List<?> ring = switch (typ) {
                case "Polygon" -> (List<?>) c.get(0);
                case "LineString" -> c;
                default -> null;
            };
            if (ring == null || ring.size() < 2) continue;
            String pts = punkte(ring);
            if (!drin(ring, ff)) continue;
            String m = merkmale(t, "surface", "width", "floating", "layer");
            zeilen.add((typ.equals("Polygon") ? "bruecke" : "steg") + "\t" + t.getOrDefault("name", "") + "\t" + m + "\t" + pts);
            n[typ.equals("Polygon") ? 0 : 1]++;
        }
        // DLRG: Häuser mit emergency=lifeguard, Strände mit lifeguard=tower|yes (ohne Doppelte im Umkreis von 40 m)
        List<double[]> dlrg = new ArrayList<>();
        File f03 = NetzImport.ebene(ov, "03."), f14 = NetzImport.ebene(ov, "14.");
        for (File f : new File[]{f03, f14}) {
            if (f == null) continue;
            for (Map<String, Object> ft : Json.features(f)) {
                Map<String, String> t = NetzImport.tags(ft);
                String lg = t.getOrDefault("lifeguard", "");
                boolean haus = "lifeguard".equals(t.get("emergency"));
                if (!haus && !(lg.equals("tower") || lg.equals("yes"))) continue;
                if (t.getOrDefault("name", "").contains("Schwimmplattform")) continue;
                double[] p = mitte(NetzImport.map(ft.get("geometry")));
                if (p == null) continue;
                double[] s = Site.fromUtm(p[0], p[1]);
                if (s[0] < ff[0] || s[0] > ff[2] || s[1] < ff[1] || s[1] > ff[3]) continue;
                boolean doppelt = false;
                for (double[] q : dlrg) doppelt |= Math.hypot(q[0] - s[0], q[1] - s[1]) < 40;
                if (doppelt) continue;
                dlrg.add(s);
                String art = lg.equals("base") ? "wache" : "turm";
                zeilen.add("dlrg\t" + t.getOrDefault("name", "DLRG") + "\t" + art + "\t" + xz(s));
                n[2]++;
            }
        }
        // Leuchtfeuer
        File f18 = NetzImport.ebene(ov, "18.");
        if (f18 != null) for (Map<String, Object> ft : Json.features(f18)) {
            Map<String, String> t = NetzImport.tags(ft);
            double[] p = mitte(NetzImport.map(ft.get("geometry")));
            if (p == null) continue;
            double[] s = Site.fromUtm(p[0], p[1]);
            if (s[0] < ff[0] || s[0] > ff[2] || s[1] < ff[1] || s[1] > ff[3]) continue;
            // Lichter: seamark:light:N:… oder ohne Nummer
            Map<String, Map<String, String>> l = new TreeMap<>();
            for (var e : t.entrySet()) {
                if (!e.getKey().startsWith("seamark:light:")) continue;
                String rest = e.getKey().substring(14);
                int k = rest.indexOf(':');
                String nr = k > 0 && Character.isDigit(rest.charAt(0)) ? rest.substring(0, k) : "1";
                String key = k > 0 && Character.isDigit(rest.charAt(0)) ? rest.substring(k + 1) : rest;
                l.computeIfAbsent(nr, x -> new TreeMap<>()).put(key, e.getValue());
            }
            StringBuilder m = new StringBuilder();
            for (Map<String, String> li : l.values()) {
                if (!li.containsKey("character") || "floodlight".equals(li.get("category")) || "fog".equals(li.get("exhibition"))) continue;
                if (m.length() > 0) m.append('|');
                m.append("licht=").append(li.get("character")).append(' ').append(li.getOrDefault("colour", "white")).append(' ')
                        .append(li.getOrDefault("period", "-")).append(' ').append(li.getOrDefault("height", "-")).append(' ')
                        .append(li.getOrDefault("range", "-")).append(' ').append(li.getOrDefault("sector_start", "-")).append('-').append(li.getOrDefault("sector_end", "-"));
            }
            String name = t.getOrDefault("name", t.getOrDefault("seamark:name", t.getOrDefault("seamark:light:reference", "")));
            zeilen.add("feuer\t" + name + "\t" + m + "\t" + xz(s));
            n[3]++;
        }
        File dir = new File(daten, ORDNER);
        dir.mkdirs();
        try (PrintWriter w = new PrintWriter(new File(dir, DATEI), StandardCharsets.UTF_8)) {
            w.println("# Bauwerke am Wasser (Timmendorf Phase 9, tools.BauwerkeImport) aus OpenStreetMap, Overpass-Export vom 30.10.2026 (ODbL)");
            w.println("# art\tname\tmerkmale\tpunkte (x,z in m der Szene)");
            for (String z : zeilen) w.println(z);
        }
        log.accept(String.format(Locale.GERMAN, "  Bauwerke: %d Seebrücken als Fläche, %d Stege, %d DLRG, %d Leuchtfeuer → daten/%s/%s", n[0], n[1], n[2], n[3], ORDNER, DATEI));
        return n;
    }

    private static String merkmale(Map<String, String> t, String... keys) {
        StringBuilder b = new StringBuilder();
        for (String k : keys) if (t.containsKey(k)) b.append(b.length() > 0 ? "|" : "").append(k).append('=').append(t.get(k));
        return b.toString();
    }

    private static boolean drin(List<?> ring, double[] ff) {
        for (Object o : ring) {
            double[] u = NetzImport.utm((List<?>) o), s = Site.fromUtm(u[0], u[1]);
            if (s[0] >= ff[0] && s[0] <= ff[2] && s[1] >= ff[1] && s[1] <= ff[3]) return true;
        }
        return false;
    }

    private static String punkte(List<?> ring) {
        StringBuilder b = new StringBuilder();
        for (Object o : ring) {
            double[] u = NetzImport.utm((List<?>) o);
            if (b.length() > 0) b.append(' ');
            b.append(xz(Site.fromUtm(u[0], u[1])));
        }
        return b.toString();
    }

    private static String xz(double[] s) { return String.format(Locale.ROOT, "%.2f,%.2f", s[0], s[1]); }

    /** Mitte (UTM) eines Punkts oder des äußeren Rings einer Fläche. */
    private static double[] mitte(Map<String, Object> g) {
        Object c = g.get("coordinates");
        String typ = String.valueOf(g.get("type"));
        if (typ.equals("Point")) return NetzImport.utm((List<?>) c);
        List<?> ring = typ.equals("Polygon") ? (List<?>) ((List<?>) c).get(0)
                : typ.equals("MultiPolygon") ? (List<?>) ((List<?>) ((List<?>) c).get(0)).get(0) : typ.equals("LineString") ? (List<?>) c : null;
        if (ring == null || ring.isEmpty()) return null;
        double e = 0, nn = 0;
        int k = 0;
        for (Object o : ring) { double[] u = NetzImport.utm((List<?>) o); e += u[0]; nn += u[1]; k++; }
        return new double[]{e / k, nn / k};
    }
}
