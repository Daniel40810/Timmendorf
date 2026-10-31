package com.dan.timmendorf.data;

import com.dan.store.DateiAblage;
import com.dan.store.Felder;
import com.dan.store.Zahl;
import com.dan.timmendorf.scene.Regie;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Speichern ohne Datenbank (F37 A): Textdateien unter {@code %APPDATA%\Timmendorf} (sonst {@code ~/.timmendorf}, mit
 * {@code -Dtimmendorf.ablage=…} anderswo). Die mitgelieferten Einträge kommen aus {@link Startdaten} und stehen nicht in
 * Dateien; eigene liegen je in einer Datei. Die Chronik liegt als CSV mit Semikolon, Geometrie als Spalte {@code WKT} in
 * UTM 32N (QGIS: „Getrennte Texte“, EPSG:25832):
 * <pre>
 * chronik/regielaeufe.csv  ergebnisse.csv  abbrueche.csv  ereignisse.csv
 * regiebuecher/*.txt   ansichten/N.txt + N.zustand + N.png   presets/paket/Klasse/*.txt
 * sitzungen/N.txt + N.zustand + N.png   protokoll.txt
 * </pre>
 * Beim nächsten Verbinden übernimmt {@link Uebernahme} die Chronik und die eigenen Einträge in die Datenbank und
 * merkt sich je Datei die Prüfsumme in TD_UEBERNAHME, damit nichts zweimal kommt.
 */
public final class DateiSpeicher extends DateiAblage implements Speicher {

    public DateiSpeicher(File dir) { super(dir); }

    /** Der Ordner der Ablage: -Dtimmendorf.ablage, sonst %APPDATA%\Timmendorf, sonst ~/.timmendorf. */
    public static File ordner() {
        String p = System.getProperty("timmendorf.ablage");
        if (p != null && !p.isEmpty()) return new File(p);
        String a = System.getenv("APPDATA");
        if (a != null && !a.isEmpty()) return new File(a, "Timmendorf");
        return new File(System.getProperty("user.home", "."), ".timmendorf");
    }

    private static void eigen(boolean mitgeliefert, String was) throws Fehler {
        if (mitgeliefert) throw new Fehler("„" + was + "“ ist mitgeliefert und wird nicht überschrieben; bitte unter einem eigenen Namen speichern");
    }

    // ------------------------------------------------------------------ CSV

    /** Eine CSV-Datei: Semikolon, Text in Anführungszeichen, erste Zeile die Spalten; nur anhängen. */
    static final class Csv {
        final File f;
        final String[] spalten;

        Csv(File f, String... spalten) { this.f = f; this.spalten = spalten; }

        synchronized List<LinkedHashMap<String, String>> lesen() throws Fehler {
            List<LinkedHashMap<String, String>> zeilen = new ArrayList<>();
            if (!f.isFile()) return zeilen;
            try {
                List<String> l = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
                if (l.isEmpty()) return zeilen;
                String[] kopf = teile(l.get(0));
                for (int i = 1; i < l.size(); i++) {
                    if (l.get(i).isBlank()) continue;
                    String[] v = teile(l.get(i));
                    LinkedHashMap<String, String> m = new LinkedHashMap<>();
                    for (int k = 0; k < kopf.length; k++) m.put(kopf[k], k < v.length ? v[k] : "");
                    zeilen.add(m);
                }
                return zeilen;
            } catch (IOException e) {
                throw new Fehler(f + " nicht lesbar: " + e.getMessage(), e);
            }
        }

        synchronized void anhaengen(Map<String, String> m) throws Fehler {
            try {
                Files.createDirectories(f.getAbsoluteFile().getParentFile().toPath());
                StringBuilder b = new StringBuilder();
                if (!f.isFile()) b.append(String.join(";", spalten)).append('\n');
                for (int k = 0; k < spalten.length; k++) b.append(k > 0 ? ";" : "").append(feld(m.getOrDefault(spalten[k], "")));
                b.append('\n');
                Files.writeString(f.toPath(), b.toString(), StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new Fehler(f + " nicht geschrieben: " + e.getMessage(), e);
            }
        }

        static String feld(String v) {
            if (v == null) return "";
            if (v.indexOf(';') < 0 && v.indexOf('"') < 0 && v.indexOf('\n') < 0 && v.indexOf(',') < 0) return v;
            return "\"" + v.replace("\"", "\"\"").replace("\n", " ") + "\"";
        }

        static String[] teile(String z) {
            List<String> l = new ArrayList<>();
            StringBuilder b = new StringBuilder();
            boolean q = false;
            for (int i = 0; i < z.length(); i++) {
                char c = z.charAt(i);
                if (q) {
                    if (c == '"' && i + 1 < z.length() && z.charAt(i + 1) == '"') { b.append('"'); i++; }
                    else if (c == '"') q = false;
                    else b.append(c);
                } else if (c == '"') q = true;
                else if (c == ';') { l.add(b.toString()); b.setLength(0); }
                else b.append(c);
            }
            l.add(b.toString());
            return l.toArray(new String[0]);
        }
    }

    final Csv laeufe = new Csv(sub("chronik", "regielaeufe.csv"), "SITZUNG", "NAME", "BEGINN_MS", "ENDE_MS", "VOLLSTAENDIG", "KINO", "SCHRITTE", "GEWARTET_S",
            "AUSGEWICHEN", "FOTOS", "BILDER_S", "GPU_MS", "QUALITAET", "BLATT");
    final Csv ergebnisse = new Csv(sub("chronik", "ergebnisse.csv"), "SITZUNG", "REGATTA", "LAUF", "SEGELNUMMER", "PLATZ", "ZEIT_S", "WENDEN", "HALSEN", "FRUEHSTART",
            "WIND_MS", "WIND_AUS_GRAD", "SZENE_MS", "SZENE_TEXT");
    final Csv abbrueche = new Csv(sub("chronik", "abbrueche.csv"), "SITZUNG", "NR", "STUNDE", "SZENE_MS", "SZENE_TEXT", "X_M", "Z_M", "LAENGE_M", "RUECKGANG_M",
            "VOLUMEN_M3", "KANTE_WKT", "WKT");
    final Csv ereignisse = new Csv(sub("chronik", "ereignisse.csv"), "SITZUNG", "ZEIT_MS", "ART", "QUELLE", "TEXT", "WERT", "WERT2");

    /** Die Dateien der Chronik (für die Übernahme). */
    public List<File> chronikDateien() { return List.of(laeufe.f, ergebnisse.f, abbrueche.f, ereignisse.f); }

    static String n(double v) { return Double.isNaN(v) ? "" : Zahl.text(Math.round(v * 1000) / 1000.0); }
    static String n(Double v) { return v == null ? "" : n((double) v); }
    static double d(String s) { return s == null || s.isEmpty() ? Double.NaN : Zahl.lies(s); }
    static Double dd(String s) { return s == null || s.isEmpty() ? null : Zahl.lies(s); }
    static Long ln(String s) { return s == null || s.isEmpty() ? null : (long) Zahl.lies(s); }
    static String s(Object v) { return v == null ? "" : v.toString(); }
    static String leer(String s) { return s == null || s.isEmpty() ? null : s; }

    // ------------------------------------------------------------------ Stand

    @Override public Daten.Stand stand() throws Fehler {
        int gemerkt = 0, eigenePresets = 0;
        for (Daten.Ansicht a : ansichten()) if ("GEMERKT".equals(a.art())) gemerkt++;
        List<Daten.PaketPreset> ps = presets(null, null);
        for (Daten.PaketPreset p : ps) if (Daten.EIGEN.equals(p.herkunft())) eigenePresets++;
        return new Daten.Stand(befunde().size(), regiebuecher().size(), laeufe.lesen().size(), ansichten().size(), gemerkt, fahrten().size(), ps.size(), eigenePresets,
                bootKlassen().size(), boote().size(), ergebnisse.lesen().size(), zuendplaene().size(), sturmfluten().size(), abbrueche.lesen().size(), kanten().size(),
                leuchtfeuer().size(), linien().size(), dateien(sub("sitzungen")).size(), letzteSitzung(-1));
    }

    @Override public List<Daten.Befund> befunde() { return Startdaten.befunde(); }

    // ------------------------------------------------------------------ Regie

    @Override public List<Daten.Regiebuch> regiebuecher() {
        List<Daten.Regiebuch> l = new ArrayList<>(Startdaten.regiebuecher());
        for (File f : dateien(sub("regiebuecher"))) {
            try {
                String t = Files.readString(f.toPath(), StandardCharsets.UTF_8);
                Regie.Book b = Regie.parse(t);
                l.add(new Daten.Regiebuch(b.name(), t, b.about(), null, Daten.EIGEN));
            } catch (IOException | RuntimeException e) {
                com.dan.store.Protokoll.warnung("Dateien", "Regiebuch " + f.getName() + " nicht lesbar: " + e.getMessage());
            }
        }
        return l;
    }

    private static boolean mitgeliefertesBuch(String name) {
        for (Daten.Regiebuch r : Startdaten.regiebuecher()) if (r.name().equals(name)) return true;
        return false;
    }

    @Override public void speichern(Daten.Regiebuch r) throws Fehler {
        eigen(mitgeliefertesBuch(r.name()), r.name());
        try {
            Regie.Book b = Regie.parse(r.text());
            if (!b.name().equals(r.name())) throw new Fehler("Der Name im Text („" + b.name() + "“) passt nicht zu „" + r.name() + "“");
        } catch (RuntimeException e) {
            throw new Fehler("Regiebuch nicht lesbar: " + e.getMessage(), e);
        }
        schreibe(sub("regiebuecher", dateiname(r.name())), r.text());
    }

    @Override public void loeschenRegiebuch(String name) throws Fehler { eigen(mitgeliefertesBuch(name), name); loeschen("regiebuecher", name); }

    @Override public void regielauf(Daten.Regielauf l, Long sitzung) throws Fehler {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        m.put("SITZUNG", s(sitzung));
        m.put("NAME", l.name());
        m.put("BEGINN_MS", Long.toString(l.beginnMs()));
        m.put("ENDE_MS", s(l.endeMs()));
        m.put("VOLLSTAENDIG", l.vollstaendig() ? "1" : "0");
        m.put("KINO", l.kino() ? "1" : "0");
        m.put("SCHRITTE", s(l.schritte()));
        m.put("GEWARTET_S", n(l.gewartetS()));
        m.put("AUSGEWICHEN", Integer.toString(l.ausgewichen()));
        m.put("FOTOS", Integer.toString(l.fotos()));
        m.put("BILDER_S", n(l.bilderS()));
        m.put("GPU_MS", n(l.gpuMs()));
        m.put("QUALITAET", s(l.qualitaet()));
        m.put("BLATT", s(l.blatt()));
        laeufe.anhaengen(m);
    }

    @Override public List<Daten.Regielauf> regielaeufe(int n) throws Fehler {
        List<Daten.Regielauf> l = new ArrayList<>();
        for (Map<String, String> m : laeufe.lesen())
            l.add(0, new Daten.Regielauf(m.get("NAME"), (long) d(m.get("BEGINN_MS")), ln(m.get("ENDE_MS")), "1".equals(m.get("VOLLSTAENDIG")), "1".equals(m.get("KINO")),
                    m.get("SCHRITTE").isEmpty() ? null : (int) d(m.get("SCHRITTE")), d(m.get("GEWARTET_S")), (int) Math.max(0, d(m.get("AUSGEWICHEN"))),
                    (int) Math.max(0, d(m.get("FOTOS"))), d(m.get("BILDER_S")), d(m.get("GPU_MS")), leer(m.get("QUALITAET")), leer(m.get("BLATT"))));
        return l.subList(0, Math.min(n, l.size()));
    }

    // ------------------------------------------------------------------ Ansichten

    private static final String[] KAM = {"x", "y", "z", "gier", "neigung"};

    private static Daten.Ansicht ansichtAus(Map<String, String> m, String zustand, byte[] png) {
        double[] k = new double[5];
        for (int i = 0; i < 5; i++) k[i] = Zahl.lies(m.get("kamera." + KAM[i]));
        String sm = m.get("szeneMs");
        return new Daten.Ansicht(Long.parseLong(m.get("id")), m.get("name"), null, "GEMERKT", k, null, dd(m.get("fov")), "true".equals(m.get("mitUhr")),
                sm == null || sm.isEmpty() ? null : Long.parseLong(sm), leer(m.get("lage")), zustand, png, Daten.EIGEN);
    }

    @Override public List<Daten.Ansicht> ansichten() throws Fehler {
        List<Daten.Ansicht> l = new ArrayList<>(Startdaten.ansichten());
        List<Daten.Ansicht> g = new ArrayList<>();
        for (File f : dateien(sub("ansichten"))) {
            LinkedHashMap<String, String> m = lies(f);
            if (m.get("id") != null) g.add(ansichtAus(m, null, null));
        }
        g.sort((a, b) -> Long.compare(b.id(), a.id()));
        l.addAll(g);
        return l;
    }

    @Override public Daten.Ansicht ansicht(long id) throws Fehler {
        File f = sub("ansichten", id + ".txt");
        if (!f.isFile()) throw new Fehler("Ansicht " + id + " gibt es nicht");
        LinkedHashMap<String, String> m = lies(f);
        File z = sub("ansichten", id + ".zustand"), p = sub("ansichten", id + ".png");
        try {
            return ansichtAus(m, z.isFile() ? Files.readString(z.toPath(), StandardCharsets.UTF_8) : null, p.isFile() ? Files.readAllBytes(p.toPath()) : null);
        } catch (IOException e) {
            throw new Fehler("Ansicht " + id + " nicht lesbar: " + e.getMessage(), e);
        }
    }

    @Override public long speichern(Daten.Ansicht a, Long sitzung) throws Fehler {
        long id = naechsteNummer(sub("ansichten"));
        java.util.Set<String> namen = new java.util.HashSet<>();
        for (Daten.Ansicht x : ansichten()) namen.add(x.name());
        String name = a.name();
        for (int i = 2; namen.contains(name); i++) name = a.name() + " (" + i + ")";
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        m.put("id", Long.toString(id));
        m.put("name", name);
        for (int i = 0; i < 5; i++) m.put("kamera." + KAM[i], Zahl.text(a.kamera()[i]));
        if (a.fov() != null) m.put("fov", Zahl.text(a.fov()));
        m.put("mitUhr", Boolean.toString(a.mitUhr()));
        if (a.szeneMs() != null) { m.put("szeneMs", a.szeneMs().toString()); m.put("szeneText", Daten.text(a.szeneMs())); }
        if (a.lage() != null) m.put("lage", a.lage());
        if (sitzung != null) m.put("sitzung", sitzung.toString());
        schreibe(sub("ansichten", id + ".txt"), Felder.alsText(m, "Ansicht " + name));
        if (a.zustand() != null) schreibe(sub("ansichten", id + ".zustand"), a.zustand());
        if (a.vorschau() != null) schreibe(sub("ansichten", id + ".png"), a.vorschau());
        return id;
    }

    @Override public void loeschenAnsicht(long id) throws Fehler {
        if (!sub("ansichten", id + ".txt").isFile()) throw new Fehler("Ansicht " + id + " ist mitgeliefert oder gibt es nicht");
        loesche(sub("ansichten", id + ".txt"), "Ansicht " + id);
        loesche(sub("ansichten", id + ".zustand"), "Ansicht " + id);
        loesche(sub("ansichten", id + ".png"), "Ansicht " + id);
    }

    @Override public List<Daten.Fahrt> fahrten() { return Startdaten.fahrten(); }

    // ------------------------------------------------------------------ Presets

    @Override public List<Daten.PaketPreset> presets(String paket, String klasse) throws Fehler {
        List<Daten.PaketPreset> l = new ArrayList<>();
        for (Daten.PaketPreset p : Startdaten.presets())
            if ((paket == null || p.paket().equals(paket)) && (klasse == null || p.klasse().equals(klasse))) l.add(p);
        File[] ps = sub("presets").listFiles(File::isDirectory);
        if (ps == null) return l;
        for (File pd : ps) {
            if (paket != null && !pd.getName().equals(paket)) continue;
            File[] ks = pd.listFiles(File::isDirectory);
            if (ks == null) continue;
            for (File kd : ks) {
                if (klasse != null && !kd.getName().equals(klasse)) continue;
                for (File f : dateien(kd)) {
                    LinkedHashMap<String, String> m = lies(f);
                    String name = m.remove("@name"), about = m.remove("@beschreibung");
                    l.add(new Daten.PaketPreset(pd.getName(), kd.getName(), name == null ? f.getName().replace(".txt", "") : name, about, Daten.EIGEN, m));
                }
            }
        }
        return l;
    }

    private static boolean presetMitgeliefert(String paket, String klasse, String name) {
        for (Daten.PaketPreset p : Startdaten.presets()) if (p.paket().equals(paket) && p.klasse().equals(klasse) && p.name().equals(name)) return true;
        return false;
    }

    @Override public void speichern(Daten.PaketPreset p) throws Fehler {
        eigen(presetMitgeliefert(p.paket(), p.klasse(), p.name()), p.name());
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        m.put("@name", p.name());   // nicht „#“: das liest Felder als Kommentar
        m.put("@beschreibung", p.beschreibung() == null ? "" : p.beschreibung());
        m.putAll(p.felder());
        schreibe(sub("presets", p.paket(), p.klasse(), dateiname(p.name())), Felder.alsText(m, "Preset " + p.paket() + "." + p.klasse() + " · " + p.name()));
    }

    @Override public void loeschenPreset(String paket, String klasse, String name) throws Fehler {
        eigen(presetMitgeliefert(paket, klasse, name), name);
        loesche(sub("presets", paket, klasse, dateiname(name)), name);
    }

    // ------------------------------------------------------------------ Fachdaten

    @Override public List<Daten.BootKlasse> bootKlassen() { return Startdaten.bootKlassen(); }
    @Override public List<Daten.Boot> boote() { return Startdaten.boote(); }
    @Override public List<Daten.Regatta> regatten() { return Startdaten.regatten(); }
    @Override public List<Daten.Effekt> effekte() { return Startdaten.effekte(); }
    @Override public List<Daten.Zuendplan> zuendplaene() { return Startdaten.zuendplaene(); }
    @Override public List<Daten.Sturmflut> sturmfluten() { return Startdaten.sturmfluten(); }
    @Override public List<Daten.Leuchtfeuer> leuchtfeuer() { return Startdaten.leuchtfeuer(); }
    @Override public List<Daten.Linie> linien() { return Startdaten.linien(); }

    @Override public void ergebnisse(List<Daten.Ergebnis> e, Long sitzung) throws Fehler {
        for (Daten.Ergebnis x : e) {
            LinkedHashMap<String, String> m = new LinkedHashMap<>();
            m.put("SITZUNG", s(sitzung));
            m.put("REGATTA", x.regatta());
            m.put("LAUF", Integer.toString(x.lauf()));
            m.put("SEGELNUMMER", s(x.segelnummer()));
            m.put("PLATZ", Integer.toString(x.platz()));
            m.put("ZEIT_S", n(x.zeitS()));
            m.put("WENDEN", Integer.toString(x.wenden()));
            m.put("HALSEN", Integer.toString(x.halsen()));
            m.put("FRUEHSTART", x.fruehstart() ? "1" : "0");
            m.put("WIND_MS", n(x.wind()));
            m.put("WIND_AUS_GRAD", n(x.windAus()));
            m.put("SZENE_MS", Long.toString(x.szeneMs()));
            m.put("SZENE_TEXT", Daten.text(x.szeneMs()));
            ergebnisse.anhaengen(m);
        }
    }

    @Override public List<Daten.Ergebnis> ergebnisse(int n) throws Fehler {
        List<Daten.Ergebnis> l = new ArrayList<>();
        for (Map<String, String> m : ergebnisse.lesen())
            l.add(0, new Daten.Ergebnis(m.get("REGATTA"), (int) d(m.get("LAUF")), leer(m.get("SEGELNUMMER")), (int) d(m.get("PLATZ")), d(m.get("ZEIT_S")),
                    (int) d(m.get("WENDEN")), (int) d(m.get("HALSEN")), "1".equals(m.get("FRUEHSTART")), d(m.get("WIND_MS")), d(m.get("WIND_AUS_GRAD")),
                    (long) d(m.get("SZENE_MS"))));
        return l.subList(0, Math.min(n, l.size()));
    }

    @Override public void abbruch(Daten.Abbruch a, Long sitzung) throws Fehler {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        m.put("SITZUNG", s(sitzung));
        m.put("NR", Integer.toString(a.nr()));
        m.put("STUNDE", n(a.stunde()));
        m.put("SZENE_MS", Long.toString(a.szeneMs()));
        m.put("SZENE_TEXT", Daten.text(a.szeneMs()));
        m.put("X_M", n(Math.round(a.x() * 10) / 10.0));
        m.put("Z_M", n(Math.round(a.z() * 10) / 10.0));
        m.put("LAENGE_M", n(a.laenge()));
        m.put("RUECKGANG_M", n(a.rueck()));
        m.put("VOLUMEN_M3", n(a.volumen()));
        m.put("KANTE_WKT", a.kante() == null || a.kante().linie() == null ? "" : a.kante().linie().wkt());
        m.put("WKT", a.flaeche() == null ? "" : a.flaeche().wkt());
        abbrueche.anhaengen(m);
    }

    @Override public List<Daten.Abbruch> abbrueche(int n) throws Fehler {
        List<Daten.Abbruch> l = new ArrayList<>();
        for (Map<String, String> m : abbrueche.lesen()) {
            Daten.Geom k = Daten.Geom.ausWkt(m.get("KANTE_WKT"));
            long ms = (long) d(m.get("SZENE_MS"));
            l.add(0, new Daten.Abbruch((int) d(m.get("NR")), d(m.get("STUNDE")), ms, d(m.get("X_M")), d(m.get("Z_M")), d(m.get("LAENGE_M")), d(m.get("RUECKGANG_M")),
                    d(m.get("VOLUMEN_M3")), Daten.Geom.ausWkt(m.get("WKT")), k == null ? null : new Daten.Kante(Daten.text(ms), "LAUF", (int) d(m.get("NR")), k.laenge(), k)));
        }
        return l.subList(0, Math.min(n, l.size()));
    }

    /** Die Kanten: ohne Datenbank nur die der Abbrüche (die aus dem DGM1 braucht das Gelände und steht in der Datenbank). */
    @Override public List<Daten.Kante> kanten() throws Fehler {
        List<Daten.Kante> l = new ArrayList<>();
        for (Daten.Abbruch a : abbrueche(Integer.MAX_VALUE)) if (a.kante() != null) l.add(a.kante());
        return l;
    }

    // ------------------------------------------------------------------ Sitzung und Protokoll

    @Override public void vorschau(long sitzung, byte[] png) throws Fehler { schreibe(sub("sitzungen", sitzung + ".png"), png); }

    private Daten.Sitzung sitzungAus(LinkedHashMap<String, String> m, boolean mitBild) {
        String ende = m.get("ende"), zz = m.get("zustandZeit");
        double rs = Zahl.lies(m.get(REGIE_SEKUNDE)), ms = Zahl.lies(m.get(SZENE_MS)), pg = Zahl.lies(m.get(PEGEL));
        long id = Long.parseLong(m.get("id"));
        byte[] bild = null;
        File png = sub("sitzungen", id + ".png");
        try { if (mitBild && png.isFile()) bild = Files.readAllBytes(png.toPath()); } catch (IOException e) { /* ohne Bild */ }
        return new Daten.Sitzung(id, Long.parseLong(m.get("beginn")), ende == null || ende.isEmpty() ? null : Long.parseLong(ende), "true".equals(m.get("sauber")),
                m.get("zustand"), zz == null || zz.isEmpty() ? null : Long.parseLong(zz), leer(m.get(REGIEBUCH)), Double.isNaN(rs) ? null : rs,
                Double.isNaN(ms) ? null : (long) ms, leer(m.get(LAGE)), Double.isNaN(pg) ? null : pg, bild);
    }

    @Override public Daten.Sitzung letzteSitzung(long ausser) throws Fehler {
        LinkedHashMap<String, String> m = super.letzteSitzungText(ausser);
        return m == null ? null : sitzungAus(m, true);
    }

    @Override public List<Daten.Sitzung> sitzungen(int n) throws Fehler {
        List<Daten.Sitzung> l = new ArrayList<>();
        for (File f : dateien(sub("sitzungen"))) {
            LinkedHashMap<String, String> m = lies(f);
            if (m.get("id") != null && m.get("beginn") != null) l.add(sitzungAus(m, false));
        }
        l.sort((a, b) -> Long.compare(b.id(), a.id()));
        return l.subList(0, Math.min(n, l.size()));
    }

    @Override public void ereignis(String art, String quelle, String text, Double wert, Double wert2, Long sitzung) throws Fehler {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        m.put("SITZUNG", s(sitzung));
        m.put("ZEIT_MS", Long.toString(System.currentTimeMillis()));
        m.put("ART", art);
        m.put("QUELLE", s(quelle));
        m.put("TEXT", s(text));
        m.put("WERT", n(wert));
        m.put("WERT2", n(wert2));
        ereignisse.anhaengen(m);
    }

    /** Alle Ereignisse (für die Übernahme). */
    public List<LinkedHashMap<String, String>> ereignisseAlle() throws Fehler { return ereignisse.lesen(); }

    /** Die Dateien der eigenen Einträge (für die Übernahme). */
    public List<File> eigeneDateien() {
        List<File> l = new ArrayList<>(dateien(sub("regiebuecher")));
        l.addAll(dateien(sub("ansichten")));
        File[] ps = sub("presets").listFiles(File::isDirectory);
        if (ps != null) for (File pd : ps) { File[] ks = pd.listFiles(File::isDirectory); if (ks != null) for (File kd : ks) l.addAll(dateien(kd)); }
        return l;
    }
}
