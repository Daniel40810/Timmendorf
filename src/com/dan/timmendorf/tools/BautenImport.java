package com.dan.timmendorf.tools;

import com.dan.timmendorf.scene.Site;
import com.dan.timmendorf.scene.Terrain;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Die Baukörper aus LOD2 Schleswig-Holstein (Phase 2, Schritt {@code bauten}, F8 A): CityGML 1.0, je Kachel eine Datei
 * {@code LoD2_32_E_N_1_SH.xml} (111 Kacheln, 34 080 Gebäude). Gelesen werden Dach-, Wand- und Bodenflächen (nur die
 * äußeren Ringe), in Dreiecke zerlegt, dazu je Gebäude Dachform ({@code roofType}), Funktion ({@code function}),
 * gemessene Höhe und Geschosse; nur Gebäude mit der Mitte im Fernfeld, doppelte gml:id einmal (aus Sylt).
 * <p>
 * Schreibt {@code daten/bauten/bauten.bin} (DataOutputStream, Big-Endian: „TDBAU1“, Zahl der Gebäude, Zahl der Dreiecke;
 * je Gebäude id, Dachform, Funktion, Geschosse, Mitte x z, Grund, First, gemessene Höhe, Dreiecke mit Art 0 Dach, 1 Wand,
 * 2 Boden und neun Koordinaten in der Szene) und {@code bauten.properties} mit den Zahlen je Dachform.
 */
public final class BautenImport {
    /** Dachformen nach dem AdV-Katalog (roofType). */
    public static final java.util.Map<String, String> DACH = java.util.Map.ofEntries(java.util.Map.entry("1000", "Flachdach"),
            java.util.Map.entry("2100", "Pultdach"), java.util.Map.entry("2200", "versetztes Pultdach"), java.util.Map.entry("3100", "Satteldach"),
            java.util.Map.entry("3200", "Walmdach"), java.util.Map.entry("3300", "Krüppelwalmdach"), java.util.Map.entry("3400", "Mansarddach"),
            java.util.Map.entry("3500", "Zeltdach"), java.util.Map.entry("3600", "Kegeldach"), java.util.Map.entry("3700", "Kuppeldach"),
            java.util.Map.entry("3800", "Sheddach"), java.util.Map.entry("3900", "Bogendach"), java.util.Map.entry("4000", "Turmdach"),
            java.util.Map.entry("5000", "Mischform"), java.util.Map.entry("9999", "sonstige Dachform"));

    private BautenImport() { }

    public static void main(String[] a) throws Exception { importieren(Rohdaten.laden(), new File("daten"), System.out::println); }

    /** Ein Gebäude: id, Merkmale, Flächen als Dreiecke (Art, 9 Koordinaten in UTM und NHN). */
    static final class Gebaeude {
        String id, dach = "", funktion = "";
        int geschosse;
        double hoehe = Double.NaN;
        double sx, sy, minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        int n;
        final List<double[]> tri = new ArrayList<>();
    }

    public static List<String> importieren(Rohdaten r, File daten, Consumer<String> log) throws IOException {
        long t0 = System.nanoTime();
        List<String> bericht = new ArrayList<>();
        File[] fs = r.lod2().listFiles((dd, n) -> n.toLowerCase(Locale.ROOT).endsWith(".xml") || n.toLowerCase(Locale.ROOT).endsWith(".gml"));
        if (fs == null || fs.length == 0) throw new IOException("LOD2: keine Dateien in " + r.lod2());
        java.util.Arrays.sort(fs);
        Set<String> ids = new HashSet<>();
        TreeSet<String> kacheln = new TreeSet<>();
        List<Gebaeude> geb = new ArrayList<>();
        Site.Fenster ff = Site.FERNFELD;
        int dateien = 0;
        for (File z : fs) {
            try (InputStream in = new java.io.BufferedInputStream(Files.newInputStream(z.toPath()), 1 << 16)) { lesen(in, ff, geb, ids, kacheln); }
            if (++dateien % 20 == 0) log.accept(Raster.f("  LOD2 %d von %d Dateien gelesen …", dateien, fs.length));
        }
        long tris = 0;
        java.util.Map<String, Integer> dach = new java.util.TreeMap<>();
        Gebaeude hoch = null;
        for (Gebaeude g : geb) {
            tris += g.tri.size();
            dach.merge(DACH.getOrDefault(g.dach, g.dach.isEmpty() ? "ohne Angabe" : g.dach), 1, Integer::sum);
            if (hoch == null || g.maxZ > hoch.maxZ) hoch = g;
        }
        bericht.add(Raster.f("LOD2: %d Dateien, %d Kacheln, %d Gebäude mit der Mitte im Fernfeld, %,d Dreiecke", fs.length, kacheln.size(), geb.size(), tris));
        StringBuilder sb = new StringBuilder();
        for (var e : dach.entrySet()) sb.append(sb.length() == 0 ? "" : ", ").append(e.getKey()).append(' ').append(e.getValue());
        bericht.add("Dächer: " + sb);
        if (hoch != null) bericht.add(Raster.f("Höchstes Gebäude: First %.1f m NHN, Grund %.1f m, bei O %.0f, N %.0f (%s)", hoch.maxZ, hoch.minZ, hoch.sx / hoch.n, hoch.sy / hoch.n, hoch.id));

        File bd = new File(daten, "bauten");
        bd.mkdirs();
        try (DataOutputStream o = new DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(new File(bd, "bauten.bin").toPath()), 1 << 16))) {
            o.write("TDBAU1".getBytes(StandardCharsets.US_ASCII));
            o.writeInt(geb.size());
            o.writeLong(tris);
            for (Gebaeude g : geb) {
                o.writeUTF(g.id);
                o.writeUTF(g.dach);
                o.writeUTF(g.funktion);
                o.writeShort(g.geschosse);
                double[] c = Site.fromUtm(g.sx / g.n, g.sy / g.n);
                o.writeFloat((float) c[0]);
                o.writeFloat((float) c[1]);
                o.writeFloat((float) g.minZ);
                o.writeFloat((float) g.maxZ);
                o.writeFloat((float) g.hoehe);
                o.writeInt(g.tri.size());
                for (double[] t : g.tri) {
                    o.writeByte((int) t[0]);
                    for (int v = 0; v < 3; v++) {
                        double[] s2 = Site.fromUtm(t[1 + v * 3], t[2 + v * 3]);
                        o.writeFloat((float) s2[0]);
                        o.writeFloat((float) t[3 + v * 3]);
                        o.writeFloat((float) s2[1]);
                    }
                }
            }
        }
        Properties p = new Properties();
        p.setProperty("gebaeude", "" + geb.size());
        p.setProperty("dreiecke", "" + tris);
        p.setProperty("kacheln", String.join(",", kacheln));
        for (var e : dach.entrySet()) p.setProperty("dach." + e.getKey(), "" + e.getValue());
        p.setProperty("format", "DataOutputStream (Big-Endian): TDBAU1, int Gebaeude, long Dreiecke; je Gebaeude UTF id, UTF Dachform, UTF Funktion, short Geschosse, "
                + "float x z grund first gemesseneHoehe, int n, n x (byte art 0 Dach 1 Wand 2 Boden, 9 float x y z der Szene)");
        p.setProperty("quelle", "LOD2 Schleswig-Holstein (CityGML 1.0); © GeoBasis-DE/LVermGeo SH, Datenlizenz Deutschland – Namensnennung – 2.0");
        p.setProperty("werkzeug", "com.dan.timmendorf.tools.BautenImport");
        try (var w = new java.io.OutputStreamWriter(new java.io.FileOutputStream(new File(bd, "bauten.properties")), StandardCharsets.UTF_8)) { p.store(w, "Baukoerper aus LOD2"); }
        for (String s : bericht) log.accept("  " + s);
        log.accept(Raster.f("Bauten: daten/bauten · %.1f s", (System.nanoTime() - t0) / 1e9));
        return bericht;
    }

    /** Liest eine CityGML-Datei: Envelope für die Kachel, Gebäude mit Flächen. */
    static void lesen(InputStream in, Site.Fenster ff, List<Gebaeude> out, Set<String> ids, Set<String> kacheln) throws IOException {
        try {
            XMLInputFactory f = XMLInputFactory.newInstance();
            f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            XMLStreamReader x = f.createXMLStreamReader(in, "UTF-8");
            Gebaeude g = null;
            int tiefe = 0, gebTiefe = -1, typ = -1, innen = 0;
            boolean envelope = false, lower = false, pos = false;
            String merk = null;
            StringBuilder text = new StringBuilder();
            while (x.hasNext()) {
                int ev = x.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    tiefe++;
                    String n = x.getLocalName();
                    switch (n) {
                        case "Envelope" -> envelope = true;
                        case "lowerCorner" -> { lower = envelope; text.setLength(0); }
                        case "Building" -> {
                            if (g == null) {
                                g = new Gebaeude();
                                gebTiefe = tiefe;
                                String id = x.getAttributeValue("http://www.opengis.net/gml", "id");
                                g.id = id == null ? "ohne-id-" + out.size() : id;
                            }
                        }
                        case "roofType", "function", "measuredHeight", "storeysAboveGround" -> { if (g != null && typ < 0) { merk = n; text.setLength(0); } }
                        case "RoofSurface" -> typ = 0;
                        case "WallSurface" -> typ = 1;
                        case "GroundSurface" -> typ = 2;
                        case "interior" -> innen++;
                        case "posList", "pos" -> { pos = g != null && typ >= 0 && innen == 0; text.setLength(0); }
                        default -> { }
                    }
                } else if (ev == XMLStreamConstants.CHARACTERS || ev == XMLStreamConstants.CDATA) {
                    if (lower || pos || merk != null) text.append(x.getText());
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    String n = x.getLocalName();
                    switch (n) {
                        case "lowerCorner" -> {
                            if (lower) {
                                String[] a = text.toString().trim().split("\\s+");
                                if (a.length >= 2) kacheln.add((int) Math.floor(Double.parseDouble(a[0]) / 1000) + "_" + (int) Math.floor(Double.parseDouble(a[1]) / 1000));
                            }
                            lower = false;
                        }
                        case "Envelope" -> envelope = false;
                        case "posList" -> {
                            if (pos) ring(g, typ, text.toString());
                            pos = false;
                        }
                        case "pos" -> {
                            if (pos) punkt(text.toString());   // einzelne Punkte, der Ring endet mit LinearRing
                            pos = false;
                        }
                        case "LinearRing" -> { if (g != null && innen == 0) punkteRing(g, typ); }
                        case "interior" -> innen--;
                        case "RoofSurface", "WallSurface", "GroundSurface" -> typ = -1;
                        case "roofType", "function", "measuredHeight", "storeysAboveGround" -> {
                            if (merk != null && g != null) {
                                String v = text.toString().trim();
                                try {
                                    switch (merk) {
                                        case "roofType" -> g.dach = v;
                                        case "function" -> g.funktion = v;
                                        case "measuredHeight" -> g.hoehe = Double.parseDouble(v);
                                        default -> g.geschosse = Integer.parseInt(v);
                                    }
                                } catch (NumberFormatException ignored) { }
                            }
                            merk = null;
                        }
                        default -> { }
                    }
                    if (n.equals("Building") && tiefe == gebTiefe && g != null) {
                        if (g.n > 0 && ids.add(g.id)) {
                            double e = g.sx / g.n, nn = g.sy / g.n;
                            if (e >= ff.eMin() && e <= ff.eMax() && nn >= ff.nMin() && nn <= ff.nMax()) out.add(g);
                        }
                        g = null;
                        gebTiefe = -1;
                    }
                    tiefe--;
                }
            }
            x.close();
        } catch (XMLStreamException e) {
            throw new IOException("LOD2: " + e.getMessage(), e);
        }
    }

    // einzelne gml:pos sammeln, bis der Ring endet
    private static final ThreadLocal<List<double[]>> PUNKTE = ThreadLocal.withInitial(ArrayList::new);

    static void punkt(String s) {
        String[] a = s.trim().split("\\s+");
        if (a.length >= 3) PUNKTE.get().add(new double[]{Double.parseDouble(a[0]), Double.parseDouble(a[1]), Double.parseDouble(a[2])});
    }

    static void punkteRing(Gebaeude g, int typ) {
        List<double[]> p = PUNKTE.get();
        if (p.size() >= 3 && typ >= 0) flaeche(g, typ, p);
        p.clear();
    }

    static void ring(Gebaeude g, int typ, String s) {
        String[] a = s.trim().split("\\s+");
        List<double[]> p = new ArrayList<>();
        for (int i = 0; i + 2 < a.length; i += 3) p.add(new double[]{Double.parseDouble(a[i]), Double.parseDouble(a[i + 1]), Double.parseDouble(a[i + 2])});
        flaeche(g, typ, p);
    }

    /** Ein ebenes Vieleck (geschlossen, letzter Punkt = erster) in Dreiecke (Ohrenschneiden in der Ebene der Fläche). */
    static void flaeche(Gebaeude g, int typ, List<double[]> p) {
        if (p.size() > 1 && java.util.Arrays.equals(p.get(0), p.get(p.size() - 1))) p = new ArrayList<>(p.subList(0, p.size() - 1));
        int n = p.size();
        if (n < 3) return;
        double nx = 0, ny = 0, nz = 0;
        for (int i = 0; i < n; i++) {
            double[] a = p.get(i), b = p.get((i + 1) % n);
            nx += (a[1] - b[1]) * (a[2] + b[2]);
            ny += (a[2] - b[2]) * (a[0] + b[0]);
            nz += (a[0] - b[0]) * (a[1] + b[1]);
        }
        int drop = Math.abs(nx) > Math.abs(ny) ? (Math.abs(nx) > Math.abs(nz) ? 0 : 2) : (Math.abs(ny) > Math.abs(nz) ? 1 : 2);
        int u = drop == 0 ? 1 : 0, v = drop == 2 ? 1 : 2;
        double area = 0;
        for (int i = 0; i < n; i++) { double[] a = p.get(i), b = p.get((i + 1) % n); area += a[u] * b[v] - b[u] * a[v]; }
        double sgn = area >= 0 ? 1 : -1;
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < n; i++) idx.add(i);
        int guard = 0;
        while (idx.size() > 3 && guard++ < 10 * n) {
            boolean cut = false;
            for (int k = 0; k < idx.size(); k++) {
                int ia = idx.get((k + idx.size() - 1) % idx.size()), ib = idx.get(k), ic = idx.get((k + 1) % idx.size());
                double[] a = p.get(ia), b = p.get(ib), c = p.get(ic);
                double cr = ((b[u] - a[u]) * (c[v] - a[v]) - (b[v] - a[v]) * (c[u] - a[u])) * sgn;
                if (cr <= 0) continue;
                boolean frei = true;
                for (int q : idx) {
                    if (q == ia || q == ib || q == ic) continue;
                    if (innen(p.get(q), a, b, c, u, v, sgn)) { frei = false; break; }
                }
                if (!frei) continue;
                dreieck(g, typ, a, b, c);
                idx.remove(k);
                cut = true;
                break;
            }
            if (!cut) break;
        }
        if (idx.size() >= 3)
            for (int k = 1; k + 1 < idx.size(); k++) dreieck(g, typ, p.get(idx.get(0)), p.get(idx.get(k)), p.get(idx.get(k + 1)));
        for (double[] q : p) {
            g.minZ = Math.min(g.minZ, q[2]);
            g.maxZ = Math.max(g.maxZ, q[2]);
            g.sx += q[0];
            g.sy += q[1];
            g.n++;
        }
    }

    static boolean innen(double[] p, double[] a, double[] b, double[] c, int u, int v, double sgn) {
        double d1 = ((b[u] - a[u]) * (p[v] - a[v]) - (b[v] - a[v]) * (p[u] - a[u])) * sgn;
        double d2 = ((c[u] - b[u]) * (p[v] - b[v]) - (c[v] - b[v]) * (p[u] - b[u])) * sgn;
        double d3 = ((a[u] - c[u]) * (p[v] - c[v]) - (a[v] - c[v]) * (p[u] - c[u])) * sgn;
        return d1 >= 0 && d2 >= 0 && d3 >= 0;
    }

    static void dreieck(Gebaeude g, int typ, double[] a, double[] b, double[] c) {
        g.tri.add(new double[]{typ, a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2]});
    }
}
