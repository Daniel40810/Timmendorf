package com.dan.timmendorf.tools;

import com.dan.timmendorf.scene.Nutzung;
import com.dan.timmendorf.scene.Site;
import com.dan.timmendorf.scene.Terrain;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

/**
 * ALKIS zu Nutzung und Objekten (Phase 2, Schritt {@code alkis}, F6 A). Liest die NAS-Dateien ({@code ALKIS/*.xml.gz},
 * je Gemarkung ein Bestandsdatenauszug, GML 3.2 in UTM 32 N) einmal mit StAX und schreibt nach {@code daten/alkis/}:
 * <ul>
 * <li>{@code nutzung_kern.u8} (je Meter im Kern, {@link Terrain#KN}²) und {@code nutzung_haupt.u8} (je 10-m-Zelle des
 * Hauptrasters, {@link Terrain#N}²): die tatsächliche Nutzung als Codes aus {@link Nutzung}, gerastert in den
 * Zellmitten (gerade-ungerade-Regel über alle Ringe, also mit Löchern);</li>
 * <li>{@code objekte.txt}: Bauwerke im Gewässerbereich (Molen, Buhnen, Uferbefestigung, Stege), Bauwerke im
 * Verkehrsbereich (Brücken), sonstige Bauwerke (Mauern, Zäune, Überdachungen) und Gewässerachsen als Linien oder Flächen
 * in der Szene, mit Objektart, Funktion und Name;</li>
 * <li>{@code alkis.properties}: Zahlen je Objektart.</li>
 * </ul>
 * Befund Phase 2: die gz-Dateien tragen am Ende ein überzähliges Zeilenende (gzip meldet „unexpected end of file“), das
 * XML darin ist vollständig; das Werkzeug liest bis zum Ende des Dokuments und übergeht den Rest. Die Gemarkungen enden
 * an der Küste: die offene Ostsee hat keinen Code (0). Prüfbild {@code docs/status/Nutzung_Phase2.png}.
 */
public final class AlkisImport {
    public static final File BILD = new File("docs/status/Nutzung_Phase2.png");
    public static final String KERN = "nutzung_kern.u8", HAUPT = "nutzung_haupt.u8", OBJEKTE = "objekte.txt";

    /** Objektarten, die als Linie oder Fläche nach {@code objekte.txt} gehen. */
    static final Set<String> OBJEKTE_ARTEN = Set.of("AX_BauwerkImGewaesserbereich", "AX_BauwerkImVerkehrsbereich", "AX_SonstigesBauwerkOderSonstigeEinrichtung",
            "AX_Gewaesserachse", "AX_HistorischesBauwerkOderHistorischeEinrichtung", "AX_BauwerkOderAnlageFuerSportFreizeitUndErholung", "AX_Turm",
            "AX_Leitung", "AX_DammWallDeich");
    /** Merkmale, die gelesen werden. */
    static final Set<String> MERKMALE = Set.of("vegetationsmerkmal", "funktion", "bauwerksfunktion", "name", "oberflaechenmaterial", "art", "zustand", "nutzung");

    private AlkisImport() { }

    public static void main(String[] a) throws Exception { importieren(Rohdaten.laden(), new File("daten"), System.out::println); }

    /** Ein gelesenes Objekt: Art, Merkmale, Ringe (Flächen) oder Linien, in UTM. */
    static final class Objekt {
        String art;
        final Map<String, String> merkmal = new HashMap<>();
        final List<double[]> ringe = new ArrayList<>(), linien = new ArrayList<>();
    }

    public static List<String> importieren(Rohdaten r, File daten, Consumer<String> log) throws IOException {
        long t0 = System.nanoTime();
        List<String> bericht = new ArrayList<>();
        File[] fs = r.alkis().listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".xml.gz") || n.toLowerCase(Locale.ROOT).endsWith(".xml"));
        if (fs == null || fs.length == 0) throw new IOException("ALKIS: keine NAS-Dateien in " + r.alkis());
        java.util.Arrays.sort(fs);
        int KN = Terrain.KN, N = Terrain.N;
        double kE0 = Terrain.KX0 + Site.E0, kN0 = Site.N0 - Terrain.KZ0, hE0 = Terrain.X0 + Site.E0, hN0 = Site.N0 - Terrain.Z0;
        byte[] kern = new byte[KN * KN], haupt = new byte[N * N];
        Map<String, Integer> zahl = new TreeMap<>();
        Set<String> ids = new HashSet<>();
        File ad = new File(daten, "alkis");
        ad.mkdirs();
        int dateien = 0, flaechen = 0, objekte = 0, doppelt = 0;
        long fehlerEnde = 0;
        try (PrintWriter ow = new PrintWriter(Files.newBufferedWriter(new File(ad, OBJEKTE).toPath(), StandardCharsets.UTF_8))) {
            ow.println("# Timmendorf: Objekte aus ALKIS (AlkisImport, " + java.time.LocalDate.now() + "). Szene: x Ost, z Süd (m), Ursprung E " + (long) Site.E0 + " N " + (long) Site.N0);
            ow.println("# Zeile: Objektart;Funktion;Name;L (Linie) oder F (Fläche, Außenring);x z x z …   © GeoBasis-DE/LVermGeo SH (dl-de/by-2-0)");
            for (File f : fs) {
                dateien++;
                final int[] z = new int[3];
                try (InputStream in = oeffnen(f)) {
                    lesen(in, o -> {
                        zahl.merge(o.art, 1, Integer::sum);
                        int c = Nutzung.code(o.art, o.merkmal.get("vegetationsmerkmal"));
                        if (c >= 0 && !o.ringe.isEmpty()) {
                            rastern(o.ringe, (byte) c, kern, KN, kE0, kN0, 1.0);
                            rastern(o.ringe, (byte) c, haupt, N, hE0, hN0, Terrain.CELL);
                            z[0]++;
                        }
                        if (OBJEKTE_ARTEN.contains(o.art)) {
                            String fn = o.merkmal.getOrDefault("bauwerksfunktion", o.merkmal.getOrDefault("funktion", o.merkmal.getOrDefault("art", "")));
                            String name = o.merkmal.getOrDefault("name", "").replace(';', ',');
                            for (double[] l : o.linien) { ow.println(zeile(o.art, fn, name, 'L', l)); z[1]++; }
                            if (!o.ringe.isEmpty()) { ow.println(zeile(o.art, fn, name, 'F', o.ringe.get(0))); z[1]++; }
                        }
                    }, ids, z);
                } catch (IOException e) {
                    fehlerEnde++;
                    log.accept("  ALKIS " + f.getName() + ": " + e.getMessage());
                }
                flaechen += z[0];
                objekte += z[1];
                doppelt += z[2];
                if (dateien % 10 == 0) log.accept(Raster.f("  ALKIS %d von %d Dateien gelesen …", dateien, fs.length));
            }
        }
        Files.write(new File(ad, KERN).toPath(), kern);
        Files.write(new File(ad, HAUPT).toPath(), haupt);
        long[] anteil = new long[Nutzung.NAME.length];
        for (byte b : kern) anteil[b & 0xff]++;
        StringBuilder ak = new StringBuilder();
        Integer[] idx = new Integer[anteil.length];
        for (int k = 0; k < idx.length; k++) idx[k] = k;
        java.util.Arrays.sort(idx, (a, b) -> Long.compare(anteil[b], anteil[a]));
        for (int k = 0; k < 8; k++) if (anteil[idx[k]] > 0) ak.append(k == 0 ? "" : ", ").append(Nutzung.NAME[idx[k]]).append(Raster.f(" %.1f %%", 100.0 * anteil[idx[k]] / kern.length));
        java.util.Properties p = new java.util.Properties();
        for (var e : zahl.entrySet()) p.setProperty("anzahl." + e.getKey(), "" + e.getValue());
        p.setProperty("dateien", "" + dateien);
        p.setProperty("flaechen", "" + flaechen);
        p.setProperty("objekte", "" + objekte);
        p.setProperty("format", "nutzung_kern.u8 KN x KN (Zeilen Nord nach Sued, Kernraster), nutzung_haupt.u8 N x N (Hauptraster); Codes aus scene.Nutzung");
        p.setProperty("quelle", "ALKIS Schleswig-Holstein, NAS-Bestandsdatenauszug je Gemarkung; © GeoBasis-DE/LVermGeo SH, Datenlizenz Deutschland – Namensnennung – 2.0");
        p.setProperty("werkzeug", "com.dan.timmendorf.tools.AlkisImport");
        try (var w = new java.io.OutputStreamWriter(new java.io.FileOutputStream(new File(ad, "alkis.properties")), StandardCharsets.UTF_8)) { p.store(w, "ALKIS (AlkisImport)"); }
        bericht.add(Raster.f("ALKIS: %d Dateien, %,d Flächen der tatsächlichen Nutzung gerastert, %,d Linien und Flächen nach objekte.txt, %,d doppelte Objekte übergangen%s",
                dateien, flaechen, objekte, doppelt, fehlerEnde > 0 ? Raster.f(", %d Dateien mit Fehler", fehlerEnde) : ""));
        bericht.add("Nutzung im Kern: " + ak);
        StringBuilder za = new StringBuilder();
        for (String k : new String[]{"AX_Wohnbauflaeche", "AX_Strassenverkehr", "AX_Landwirtschaft", "AX_Wald", "AX_StehendesGewaesser", "AX_Fliessgewaesser",
                "AX_Hafenbecken", "AX_Meer", "AX_UnlandVegetationsloseFlaeche", "AX_BauwerkImGewaesserbereich", "AX_Gebaeude"})
            za.append(za.length() == 0 ? "" : ", ").append(k.substring(3)).append(' ').append(zahl.getOrDefault(k, 0));
        bericht.add("Objekte: " + za);
        for (String s : bericht) log.accept("  " + s);
        bild(haupt, kern, bericht, BILD);
        log.accept(Raster.f("ALKIS: daten/alkis · %.1f s", (System.nanoTime() - t0) / 1e9));
        return bericht;
    }

    static String zeile(String art, String fn, String name, char typ, double[] l) {
        StringBuilder sb = new StringBuilder(art).append(';').append(fn).append(';').append(name).append(';').append(typ).append(';');
        for (int i = 0; i + 1 < l.length; i += 2) {
            if (i > 0) sb.append(' ');
            sb.append(String.format(Locale.ROOT, "%.2f %.2f", l[i] - Site.E0, -(l[i + 1] - Site.N0)));
        }
        return sb.toString();
    }

    /** Öffnet eine NAS-Datei (gz oder xml). */
    static InputStream oeffnen(File f) throws IOException {
        InputStream in = new BufferedInputStream(Files.newInputStream(f.toPath()), 1 << 16);
        if (f.getName().toLowerCase(Locale.ROOT).endsWith(".gz")) in = new BufferedInputStream(new GZIPInputStream(in, 1 << 16), 1 << 16);
        return in;
    }

    /**
     * Liest einen NAS-Bestandsdatenauszug: jedes Objekt (Kind von {@code wfs:member}) mit seinen Merkmalen und seiner
     * Geometrie aus {@code position}: Ringe (gml:exterior/interior, aus allen Segmenten der Kurven zusammengesetzt; Bögen als
     * ihre Stützpunkte) und Linien (gml:LineString, gml:Curve außerhalb eines Rings). z[2] zählt doppelte gml:id.
     */
    static void lesen(InputStream in, Consumer<Objekt> aus, Set<String> ids, int[] z) throws IOException {
        XMLStreamReader x = null;
        try {
            XMLInputFactory f = XMLInputFactory.newInstance();
            f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            f.setProperty(XMLInputFactory.IS_COALESCING, true);
            x = f.createXMLStreamReader(in, "UTF-8");
            Objekt o = null;
            int tiefe = 0, objTiefe = -1, posTiefe = -1, ringTiefe = -1, compTiefe = -1;
            boolean member = false, inPos = false;
            String merk = null;
            StringBuilder text = new StringBuilder();
            double[] ring = null;
            int ringN = 0;
            List<double[]> kurve = null;
            while (x.hasNext()) {
                int ev = x.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    tiefe++;
                    String n = x.getLocalName();
                    if (n.equals("member")) { member = true; continue; }
                    if (member && o == null && n.startsWith("AX_")) {
                        String id = x.getAttributeValue("http://www.opengis.net/gml/3.2", "id");
                        o = new Objekt();
                        o.art = n;
                        objTiefe = tiefe;
                        if (id != null && !ids.add(id)) { z[2]++; o.art = null; }
                        continue;
                    }
                    if (o == null) continue;
                    if (n.equals("position") && tiefe == objTiefe + 1) { posTiefe = tiefe; continue; }
                    if (posTiefe > 0) {
                        switch (n) {
                            case "Ring", "LinearRing" -> { ringTiefe = tiefe; ring = new double[64]; ringN = 0; }
                            case "CompositeCurve" -> { if (ringTiefe < 0 && compTiefe < 0) { compTiefe = tiefe; kurve = new ArrayList<>(); } }
                            case "LineString", "Curve" -> { if (ringTiefe < 0 && kurve == null) kurve = new ArrayList<>(); }
                            case "posList", "pos" -> { inPos = true; text.setLength(0); }
                            default -> { }
                        }
                    } else if (tiefe == objTiefe + 1 && MERKMALE.contains(n)) { merk = n; text.setLength(0); }
                } else if (ev == XMLStreamConstants.CHARACTERS || ev == XMLStreamConstants.CDATA) {
                    if (inPos || merk != null) text.append(x.getText());
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    String n = x.getLocalName();
                    if (o != null) {
                        if (inPos && (n.equals("posList") || n.equals("pos"))) {
                            double[] v = zahlen(text);
                            if (ringTiefe > 0) {
                                // aneinanderhängen; doppelten Anschlusspunkt weglassen
                                int s = ringN >= 2 && v.length >= 2 && v[0] == ring[ringN - 2] && v[1] == ring[ringN - 1] ? 2 : 0;
                                if (ringN + v.length - s > ring.length) ring = java.util.Arrays.copyOf(ring, Math.max(ring.length * 2, ringN + v.length));
                                System.arraycopy(v, s, ring, ringN, v.length - s);
                                ringN += v.length - s;
                            } else if (kurve != null) kurve.add(v);
                            inPos = false;
                        } else if ((n.equals("Ring") || n.equals("LinearRing")) && tiefe == ringTiefe) {
                            if (ringN >= 6 && o.art != null) o.ringe.add(java.util.Arrays.copyOf(ring, ringN));
                            ringTiefe = -1;
                        } else if ((n.equals("LineString") || n.equals("Curve") || n.equals("CompositeCurve")) && kurve != null && ringTiefe < 0
                                && (compTiefe < 0 || (n.equals("CompositeCurve") && tiefe == compTiefe))) {
                            double[] l = verbinden(kurve);
                            if (l.length >= 4 && o.art != null) o.linien.add(l);
                            kurve = null;
                            compTiefe = -1;
                        } else if (n.equals("position") && tiefe == posTiefe) {
                            posTiefe = -1;
                            compTiefe = -1;
                            if (kurve != null) {
                                double[] l = verbinden(kurve);
                                if (l.length >= 4 && o.art != null) o.linien.add(l);
                                kurve = null;
                            }
                        } else if (merk != null && n.equals(merk)) {
                            o.merkmal.putIfAbsent(merk, text.toString().trim());
                            merk = null;
                        }
                        if (tiefe == objTiefe) {
                            if (o.art != null) aus.accept(o);
                            o = null;
                            objTiefe = -1;
                        }
                    }
                    if (n.equals("member")) member = false;
                    tiefe--;
                } else if (ev == XMLStreamConstants.END_DOCUMENT) break;
            }
        } catch (XMLStreamException e) {
            // am Ende der gz-Datei (überzähliges Zeilenende) ist das Dokument schon vollständig gelesen
            if (!(e.getNestedException() instanceof java.io.EOFException) && !String.valueOf(e.getMessage()).contains("Unexpected end"))
                throw new IOException(e.getMessage(), e);
        } finally {
            if (x != null) try { x.close(); } catch (XMLStreamException ignored) { }
        }
    }

    static double[] verbinden(List<double[]> teile) {
        int len = 0;
        for (double[] t : teile) len += t.length;
        double[] l = new double[len];
        int k = 0;
        for (double[] t : teile) {
            int s = k >= 2 && t.length >= 2 && t[0] == l[k - 2] && t[1] == l[k - 1] ? 2 : 0;
            System.arraycopy(t, s, l, k, t.length - s);
            k += t.length - s;
        }
        return java.util.Arrays.copyOf(l, k);
    }

    static double[] zahlen(CharSequence s) {
        double[] v = new double[16];
        int n = 0, i = 0, len = s.length();
        while (i < len) {
            while (i < len && Character.isWhitespace(s.charAt(i))) i++;
            int a = i;
            while (i < len && !Character.isWhitespace(s.charAt(i))) i++;
            if (i > a) {
                if (n == v.length) v = java.util.Arrays.copyOf(v, n * 2);
                v[n++] = Double.parseDouble(s.subSequence(a, i).toString());
            }
        }
        return java.util.Arrays.copyOf(v, n);
    }

    /**
     * Rastert die Ringe eines Objekts (gerade-ungerade-Regel) in ein Raster n × n mit der Nordwestecke (e0, n0) und der
     * Zellgröße cell: jede Zelle, deren Mitte innen liegt, bekommt den Code.
     */
    static void rastern(List<double[]> ringe, byte code, byte[] r, int n, double e0, double n0, double cell) {
        double minE = Double.MAX_VALUE, maxE = -Double.MAX_VALUE, minN = Double.MAX_VALUE, maxN = -Double.MAX_VALUE;
        for (double[] g : ringe)
            for (int i = 0; i + 1 < g.length; i += 2) {
                minE = Math.min(minE, g[i]); maxE = Math.max(maxE, g[i]);
                minN = Math.min(minN, g[i + 1]); maxN = Math.max(maxN, g[i + 1]);
            }
        int j0 = Math.max(0, (int) Math.floor((n0 - maxN) / cell)), j1 = Math.min(n - 1, (int) Math.ceil((n0 - minN) / cell));
        int i0 = Math.max(0, (int) Math.floor((minE - e0) / cell)), i1 = Math.min(n - 1, (int) Math.ceil((maxE - e0) / cell));
        if (j0 > j1 || i0 > i1) return;
        double[] xs = new double[64];
        for (int j = j0; j <= j1; j++) {
            double y = n0 - (j + 0.5) * cell;
            int c = 0;
            for (double[] g : ringe) {
                int m = g.length / 2;
                for (int k = 0; k < m; k++) {
                    double ax = g[2 * k], ay = g[2 * k + 1], bx = g[2 * ((k + 1) % m)], by = g[2 * ((k + 1) % m) + 1];
                    if ((ay > y) == (by > y)) continue;
                    if (c == xs.length) xs = java.util.Arrays.copyOf(xs, c * 2);
                    xs[c++] = ax + (y - ay) * (bx - ax) / (by - ay);
                }
            }
            if (c < 2) continue;
            java.util.Arrays.sort(xs, 0, c);
            for (int k = 0; k + 1 < c; k += 2) {
                int a = (int) Math.ceil((xs[k] - e0) / cell - 0.5), b = (int) Math.floor((xs[k + 1] - e0) / cell - 0.5);
                a = Math.max(a, i0);
                b = Math.min(b, i1);
                for (int i = a; i <= b; i++) r[j * n + i] = code;
            }
        }
    }

    /** Prüfbild: links das Hauptraster, rechts der Kern, unten die Zahlen und die Legende. */
    static void bild(byte[] haupt, byte[] kern, List<String> bericht, File png) throws IOException {
        int S = 1024, W = 2 * S + 30, H = S + 260;
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0x1d2328));
        g.fillRect(0, 0, W, H);
        int N = Terrain.N, KN = Terrain.KN;
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++) img.setRGB(10 + x, 10 + y, Nutzung.FARBE[haupt[(y * 2) * N + x * 2] & 0xff]);
        double sk = KN / (double) S;
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++) img.setRGB(20 + S + x, 10 + y, Nutzung.FARBE[kern[(int) (y * sk) * KN + (int) (x * sk)] & 0xff]);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
        g.setColor(new Color(0xee, 0xee, 0xee));
        g.drawString("ALKIS tatsächliche Nutzung · Hauptraster 20,5 km zu 10 m", 16, 30);
        g.drawString("Kern 4,6 km zu 1 m", 26 + S, 30);
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        int y = S + 34;
        g.drawString("Timmendorf Phase 2 · AlkisImport · " + java.time.LocalDate.now(), 12, y);
        y += 20;
        for (String z : bericht) for (String l : Raster.wrap(z, 160)) { g.drawString(l, 12, y); y += 17; }
        y += 6;
        int x = 12;
        for (int c = 1; c < Nutzung.NAME.length; c++) {
            String t = Nutzung.NAME[c];
            int w = g.getFontMetrics().stringWidth(t) + 30;
            if (x + w > W - 10) { x = 12; y += 18; }
            g.setColor(new Color(Nutzung.FARBE[c]));
            g.fillRect(x, y - 11, 14, 12);
            g.setColor(new Color(0xcc, 0xcc, 0xcc));
            g.drawString(t, x + 18, y);
            x += w;
        }
        g.dispose();
        File d = png.getAbsoluteFile().getParentFile();
        if (d != null) d.mkdirs();
        javax.imageio.ImageIO.write(img, "png", png);
    }
}
