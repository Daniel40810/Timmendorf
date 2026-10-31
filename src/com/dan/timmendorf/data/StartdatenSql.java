package com.dan.timmendorf.data;

import com.dan.store.Zahl;
import com.dan.timmendorf.scene.Regie;
import com.dan.timmendorf.scene.Terrain;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Schreibt die mitgelieferten Daten als SQL: {@code db/sql/04_startdaten.sql} (erzeugt von {@code ant startdaten}, nicht
 * von Hand ändern). MERGE je Eintrag mit seinem Namen; die Teile mitgelieferter Einträge (Belege, Stichworte, Werte,
 * Polaren, Tonnen, Schüsse, Pegel, Sektoren, Abfahrten) und die Kante aus dem DGM1 werden gelöscht und neu angelegt.
 * Eigene Zeilen (HERKUNFT = 'EIGEN') bleiben unberührt. Beliebig oft wiederholbar.
 * <p>
 * Geometrien in UTM 32N (SRID 25832): Punkte als {@code SDO_POINT_TYPE}, Linien und Flächen als WKT über
 * {@code SDO_GEOMETRY(wkt, 25832)}; lange Texte in Stücken zu höchstens 1 000 Zeichen als {@code TO_CLOB(…) || …}.
 * <p>
 * Aufruf: {@code java … StartdatenSql [db/sql]}. Mit {@code daten/gelaende} kommt die Kante dazu, mit
 * {@code daten/bauwerke} die Leuchtfeuer aus OpenStreetMap.
 */
public final class StartdatenSql {
    private StartdatenSql() { }

    static final String M = "'MITGELIEFERT'";

    static String s(String v) {
        if (v == null) return "NULL";
        return "'" + v.replace("'", "''").replace("\r", "").replace("\n", "' || CHR(10) || '") + "'";
    }

    static String clob(String v) {
        if (v == null) return "NULL";
        String t = v.replace("\r", "");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < t.length(); ) {
            int e = Math.min(t.length(), i + 1000);
            if (e < t.length() && Character.isHighSurrogate(t.charAt(e - 1))) e--;
            b.append(i > 0 ? "\n    || " : "").append("TO_CLOB(").append(s(t.substring(i, e))).append(")");
            i = e;
        }
        return b.length() == 0 ? "TO_CLOB('')" : b.toString();
    }

    static String n(Double v) { return v == null || Double.isNaN(v) || Double.isInfinite(v) ? "NULL" : Zahl.text(Math.round(v * 1e6) / 1e6); }
    static String n(double v) { return n((Double) v); }
    static String i(Integer v) { return v == null ? "NULL" : v.toString(); }
    static String l(Long v) { return v == null ? "NULL" : v.toString(); }
    static String d(LocalDate d) { return d == null ? "NULL" : "DATE '" + d + "'"; }

    /** Szenenzeit als TIMESTAMP WITH TIME ZONE in Europe/Berlin aus ms seit 1970. */
    public static String tstz(Long ms) {
        if (ms == null) return "NULL";
        return "FROM_TZ(TIMESTAMP '1970-01-01 00:00:00' + NUMTODSINTERVAL(" + ms + " / 1000, 'SECOND'), 'UTC') AT TIME ZONE 'Europe/Berlin'";
    }

    static String punkt(double x, double z) {
        if (Double.isNaN(x) || Double.isNaN(z)) return "NULL";
        double[] u = Daten.utm(x, z);
        return "MDSYS.SDO_GEOMETRY(2001, 25832, MDSYS.SDO_POINT_TYPE(" + Daten.n2(u[0]) + ", " + Daten.n2(u[1]) + ", NULL), NULL, NULL)";
    }

    static String geom(Daten.Geom g) {
        if (g == null) return "NULL";
        if (g.art().equals("PUNKT")) return punkt(g.p()[0][0], g.p()[0][1]);
        String w = g.wkt();
        return "MDSYS.SDO_GEOMETRY(" + (w.length() <= 1000 ? s(w) : clob(w)) + ", 25832)";
    }

    static String ost(double x, double z) { return Double.isNaN(x) ? "NULL" : Daten.n2(Daten.utm(x, z)[0]); }
    static String nord(double x, double z) { return Double.isNaN(z) ? "NULL" : Daten.n2(Daten.utm(x, z)[1]); }

    static String id(String tabelle, String key, String where) { return "(SELECT MAX(" + key + ") FROM " + tabelle + " WHERE " + where + ")"; }

    private static void kopf(StringBuilder b, String t) { b.append("\n-- ").append("-".repeat(Math.max(4, 100 - t.length()))).append(' ').append(t).append('\n'); }

    /** MERGE INTO t USING (SELECT key AS col …) q ON (…) WHEN MATCHED THEN UPDATE … WHERE HERKUNFT … WHEN NOT MATCHED THEN INSERT …. */
    static void merge(StringBuilder b, String tabelle, String[] schluessel, String[] schluesselWerte, String[] spalten, String[] werte, boolean herkunft) {
        StringBuilder using = new StringBuilder(), on = new StringBuilder(), upd = new StringBuilder(), cols = new StringBuilder(), vals = new StringBuilder();
        for (int k = 0; k < schluessel.length; k++) {
            using.append(k > 0 ? ", " : "").append(schluesselWerte[k]).append(" AS ").append(schluessel[k]);
            on.append(k > 0 ? " AND " : "").append("t.").append(schluessel[k]).append(" = q.").append(schluessel[k]);
            cols.append(k > 0 ? ", " : "").append(schluessel[k]);
            vals.append(k > 0 ? ", " : "").append(schluesselWerte[k]);
        }
        for (int k = 0; k < spalten.length; k++) {
            upd.append(k > 0 ? ", " : "").append("t.").append(spalten[k]).append(" = ").append(werte[k]);
            cols.append(", ").append(spalten[k]);
            vals.append(", ").append(werte[k]);
        }
        b.append("MERGE INTO ").append(tabelle).append(" t USING (SELECT ").append(using).append(" FROM DUAL) q ON (").append(on).append(")\n");
        if (spalten.length > 0) b.append("WHEN MATCHED THEN UPDATE SET ").append(upd).append(herkunft ? " WHERE t.HERKUNFT = " + M : "").append('\n');
        b.append("WHEN NOT MATCHED THEN INSERT (").append(cols).append(herkunft ? ", HERKUNFT" : "").append(")\n  VALUES (").append(vals)
                .append(herkunft ? ", " + M : "").append(");\n");
    }

    static void insert(StringBuilder b, String tabelle, String[] spalten, String... werte) {
        b.append("INSERT INTO ").append(tabelle).append(" (").append(String.join(", ", spalten)).append(") VALUES (").append(String.join(", ", werte)).append(");\n");
    }

    /** Was mitkommt (die Kante braucht daten/gelaende). */
    public record Alles(List<Daten.Kante> kanten) {
        public static Alles aus(Terrain t) { return new Alles(Startdaten.kanten(t)); }
    }

    /** Die Zahlen der mitgelieferten Zeilen je Art (Zeile „-- ZAHLEN:“, der Einrichter prüft sie). */
    public static Map<String, Integer> zahlen(Alles a) {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("Befunde", Startdaten.befunde().size());
        m.put("Belege", Startdaten.befunde().stream().mapToInt(f -> f.belege().size()).sum());
        m.put("Regiebücher", Startdaten.regiebuecher().size());
        m.put("Stichworte", Startdaten.regiebuecher().stream().mapToInt(r -> Regie.parse(r.text()).zeilen().size()).sum());
        m.put("Ansichten", Startdaten.ansichten().size());
        m.put("Fahrten", Startdaten.fahrten().size());
        m.put("Presets", Startdaten.presets().size());
        m.put("Presetwerte", Startdaten.presets().stream().mapToInt(p -> p.felder().size()).sum());
        m.put("Bootsklassen", Startdaten.bootKlassen().size());
        m.put("Polaren", Startdaten.bootKlassen().stream().mapToInt(k -> k.polare().size()).sum());
        m.put("Boote", Startdaten.boote().size());
        m.put("Regatten", Startdaten.regatten().size());
        m.put("Tonnen", Startdaten.regatten().stream().mapToInt(r -> r.tonnen().size()).sum());
        m.put("Effekte", Startdaten.effekte().size());
        m.put("Zündpläne", Startdaten.zuendplaene().size());
        m.put("Schüsse", Startdaten.zuendplaene().stream().mapToInt(z -> z.schuesse().size()).sum());
        m.put("Sturmfluten", Startdaten.sturmfluten().size());
        m.put("Pegel", Startdaten.sturmfluten().stream().mapToInt(z -> z.pegel().size()).sum());
        m.put("Kanten", a.kanten().size());
        m.put("Leuchtfeuer", Startdaten.leuchtfeuer().size());
        m.put("Sektoren", Startdaten.leuchtfeuer().stream().mapToInt(f -> f.sektoren().size()).sum());
        m.put("Linien", Startdaten.linien().size());
        m.put("Abfahrten", Startdaten.linien().stream().mapToInt(x -> x.abfahrten().size()).sum());
        return m;
    }

    /** Das ganze Skript. */
    public static String skript(Alles a) {
        StringBuilder b = new StringBuilder();
        Map<String, Integer> z = zahlen(a);
        b.append("""
                -- =====================================================================================================
                -- Timmendorf · Datenbank Teil 2 · 04_startdaten.sql
                -- ERZEUGT aus com.dan.timmendorf.data.Startdaten (ant startdaten) – nicht von Hand ändern.
                -- Mitgelieferte Zeilen (HERKUNFT = 'MITGELIEFERT') werden angelegt oder wiederhergestellt; eigene Zeilen
                -- bleiben unberührt. Beliebig oft wiederholbar. Befunde, Regiebücher, Ansichten, Fahrten, Presets,
                -- Bootsklassen mit Polaren, Boote, die Regatta vor Timmendorf, Effekte und Zündpläne, die Sturmflut vom
                -- Oktober 2023 mit Fahrplan, die Kante aus dem DGM1 (mit daten/gelaende), Leuchtfeuer und Buslinien [G].
                -- Geometrien in UTM 32N (SRID 25832).
                -- =====================================================================================================
                """);
        b.append("-- ZAHLEN: ");
        for (Map.Entry<String, Integer> e : z.entrySet()) b.append(e.getKey()).append('=').append(e.getValue()).append("; ");
        b.append("\n\nSET DEFINE OFF\n");

        kopf(b, "Ausbaustufe");
        b.append("MERGE INTO TD_SCHEMA t USING (SELECT ").append(Verbindung.VERSION).append(" AS VERSION FROM DUAL) q ON (t.VERSION = q.VERSION)\n")
                .append("WHEN NOT MATCHED THEN INSERT (VERSION, APP_VERSION, PHASE, BEMERKUNG) VALUES (").append(Verbindung.VERSION)
                .append(", 'Phase 17', 17, 'Teil 2: Sitzungen, Protokoll, Regie, Ansichten, Presets, Fachdaten und Ergebnisse (31 Tabellen)');\n");

        kopf(b, "Befunde");
        for (Daten.Befund f : Startdaten.befunde())
            merge(b, "TD_BEFUND", new String[]{"NR"}, new String[]{i(f.nr())}, new String[]{"PHASE", "THEMA", "TEXT", "ART", "STATUS", "BEHOBEN_IN", "COMMIT_ID"},
                    new String[]{s(f.phase()), s(f.thema()), s(f.text()), s(f.art()), s(f.status()), s(f.behobenIn()), s(f.commit())}, true);
        b.append("DELETE FROM TD_BEFUND_QUELLE WHERE BEFUND_ID IN (SELECT BEFUND_ID FROM TD_BEFUND WHERE HERKUNFT = ").append(M).append(");\n");
        for (Daten.Befund f : Startdaten.befunde())
            for (String q : f.belege())
                insert(b, "TD_BEFUND_QUELLE", new String[]{"BEFUND_ID", "QUELLE"}, id("TD_BEFUND", "BEFUND_ID", "NR = " + f.nr() + " AND HERKUNFT = " + M), s(q));

        kopf(b, "Regiebücher");
        for (Daten.Regiebuch r : Startdaten.regiebuecher()) {
            Regie.Book bk = Regie.parse(r.text());
            merge(b, "TD_REGIEBUCH", new String[]{"NAME"}, new String[]{s(r.name())}, new String[]{"TITEL", "TEXT", "DATEI", "PRUEFSUMME", "STICHWORTE", "FOTOS", "DAUER_S"},
                    new String[]{s(kurz(r.titel(), 200)), clob(r.text()), s(r.datei()), s(Startdaten.sha256(r.text())), i(bk.zeilen().size()), i(bk.fotos()), n(bk.length())}, true);
        }
        b.append("DELETE FROM TD_STICHWORT WHERE REGIEBUCH_ID IN (SELECT REGIEBUCH_ID FROM TD_REGIEBUCH WHERE HERKUNFT = ").append(M).append(");\n");
        for (Daten.Regiebuch r : Startdaten.regiebuecher()) {
            int nr = 0;
            for (Regie.Zeile zl : Regie.parse(r.text()).zeilen())
                insert(b, "TD_STICHWORT", new String[]{"REGIEBUCH_ID", "NR", "SEKUNDE", "BEFEHL", "ARGUMENTE", "TEXT"},
                        id("TD_REGIEBUCH", "REGIEBUCH_ID", "NAME = " + s(r.name()) + " AND HERKUNFT = " + M), i(++nr), n(zl.sekunde()), s(zl.befehl()),
                        s(kurz(zl.argumente(), 400)), s(kurz(zl.text(), 400)));
        }

        kopf(b, "Ansichten");
        for (Daten.Ansicht x : Startdaten.ansichten()) {
            double[] k = x.kamera();
            merge(b, "TD_ANSICHT", new String[]{"NAME"}, new String[]{s(x.name())}, new String[]{"TASTE", "ART", "OST", "NORD", "HOEHE", "AUGE_UEBER_GRUND", "GIER_GRAD",
                    "NEIGUNG_GRAD", "FOV_GRAD", "GEOMETRY"}, new String[]{i(x.taste()), s(x.art()), ost(k[0], k[2]), nord(k[0], k[2]), n(k[1]), n(x.augeUeberGrund()),
                    n(k[3]), n(k[4]), n(x.fov()), punkt(k[0], k[2])}, true);
        }

        kopf(b, "Fahrten");
        for (Daten.Fahrt f : Startdaten.fahrten())
            merge(b, "TD_FAHRT", new String[]{"NAME"}, new String[]{s(f.name())}, new String[]{"ART", "DAUER_S", "LAENGE_M", "BESCHREIBUNG", "GEOMETRY"},
                    new String[]{s(f.art()), n(f.dauerS()), n(f.laengeM()), s(f.beschreibung()), geom(f.weg())}, true);

        kopf(b, "Presets der Packages");
        for (Daten.PaketPreset p : Startdaten.presets())
            merge(b, "TD_PAKET_PRESET", new String[]{"PAKET", "KLASSE", "NAME"}, new String[]{s(p.paket()), s(p.klasse()), s(p.name())}, new String[]{"BESCHREIBUNG"},
                    new String[]{s(p.beschreibung())}, true);
        b.append("DELETE FROM TD_PAKET_WERT WHERE PAKET_PRESET_ID IN (SELECT PAKET_PRESET_ID FROM TD_PAKET_PRESET WHERE HERKUNFT = ").append(M).append(");\n");
        for (Daten.PaketPreset p : Startdaten.presets()) {
            Map<String, String> typ = Startdaten.typen(p);
            String pid = id("TD_PAKET_PRESET", "PAKET_PRESET_ID", "PAKET = " + s(p.paket()) + " AND KLASSE = " + s(p.klasse()) + " AND NAME = " + s(p.name()) + " AND HERKUNFT = " + M);
            for (Map.Entry<String, String> e : p.felder().entrySet())
                insert(b, "TD_PAKET_WERT", new String[]{"PAKET_PRESET_ID", "FELD", "TYP", "WERT"}, pid, s(e.getKey()), s(typ.getOrDefault(e.getKey(), "STRING")), s(e.getValue()));
        }

        kopf(b, "Bootsklassen und Polaren");
        for (Daten.BootKlasse k : Startdaten.bootKlassen())
            merge(b, "TD_BOOT_KLASSE", new String[]{"NAME"}, new String[]{s(k.name())}, new String[]{"ART", "RIGG", "LUEA_M", "LWL_M", "BREITE_M", "MASSE_KG",
                    "SEGELFLAECHE_M2", "VORWIND_M2", "MAST_M", "CREW", "QUELLE"}, new String[]{s(k.art()), s(k.rigg()), n(k.luea()), n(k.lwl()), n(k.breite()), n(k.masse()),
                    n(k.segel()), n(k.vorwind()), n(k.mast()), i(k.crew()), s(k.quelle())}, true);
        b.append("DELETE FROM TD_POLARE WHERE ART = 'GERECHNET' AND BOOT_KLASSE_ID IN (SELECT BOOT_KLASSE_ID FROM TD_BOOT_KLASSE WHERE HERKUNFT = ").append(M).append(");\n");
        for (Daten.BootKlasse k : Startdaten.bootKlassen()) {
            String kid = id("TD_BOOT_KLASSE", "BOOT_KLASSE_ID", "NAME = " + s(k.name()));
            for (Daten.Polarpunkt p : k.polare())
                insert(b, "TD_POLARE", new String[]{"BOOT_KLASSE_ID", "ART", "TWS_KN", "TWA_GRAD", "FAHRT_KN", "KRAENGUNG_GRAD"}, kid, s(p.art()), n(p.twsKn()), n(p.twa()),
                        n(p.fahrtKn()), n(p.kraengung()));
        }
        kopf(b, "Boote");
        for (Daten.Boot x : Startdaten.boote())
            merge(b, "TD_BOOT", new String[]{"NAME"}, new String[]{s(x.name())}, new String[]{"BOOT_KLASSE_ID", "SEGELNUMMER", "ROLLE", "FARBE"},
                    new String[]{id("TD_BOOT_KLASSE", "BOOT_KLASSE_ID", "NAME = " + s(x.klasse())), s(x.segelnummer()), s(x.rolle()), s(x.farbe())}, true);

        kopf(b, "Regatta");
        for (Daten.Regatta r : Startdaten.regatten()) {
            Daten.Tonne st = r.tonnen().get(0), lv = r.tonnen().get(2);
            merge(b, "TD_REGATTA", new String[]{"NAME"}, new String[]{s(r.name())}, new String[]{"DATUM", "BOOT_KLASSE_ID", "RICHTUNG_GRAD", "LAENGE_M", "LINIE_M", "RUNDEN",
                    "GEOMETRY"}, new String[]{d(r.datum()), id("TD_BOOT_KLASSE", "BOOT_KLASSE_ID", "NAME = " + s(r.klasse())), n(r.richtung()), n(r.laenge()), n(r.linie()),
                    i(r.runden()), geom(Daten.Geom.linie(new double[][]{{st.x(), st.z()}, {lv.x(), lv.z()}}))}, true);
            b.append("DELETE FROM TD_REGATTA_TONNE WHERE REGATTA_ID IN (SELECT REGATTA_ID FROM TD_REGATTA WHERE NAME = ").append(s(r.name())).append(" AND HERKUNFT = ").append(M).append(");\n");
            for (Daten.Tonne t : r.tonnen())
                insert(b, "TD_REGATTA_TONNE", new String[]{"REGATTA_ID", "NR", "ROLLE", "OST", "NORD", "GEOMETRY"}, id("TD_REGATTA", "REGATTA_ID", "NAME = " + s(r.name())),
                        i(t.nr()), s(t.rolle()), ost(t.x(), t.z()), nord(t.x(), t.z()), punkt(t.x(), t.z()));
        }

        kopf(b, "Feuerwerk");
        for (Daten.Effekt e : Startdaten.effekte())
            merge(b, "TD_EFFEKT", new String[]{"NAME"}, new String[]{s(e.name())}, new String[]{"BILD", "SALZ", "FARBE", "WELLENLAENGE_NM", "KALIBER_MM", "HALBMESSER_M",
                    "BRENNDAUER_S"}, new String[]{s(e.bild()), s(e.salz()), s(e.farbe()), n(e.nm()), i(e.kaliber()), n(e.halbmesser()), n(e.brenndauer())}, true);
        for (Daten.Zuendplan zp : Startdaten.zuendplaene()) {
            merge(b, "TD_ZUENDPLAN", new String[]{"NAME"}, new String[]{s(zp.name())}, new String[]{"DATUM", "BEGINN", "DAUER_S", "BOMBEN", "TEXT", "GEOMETRY"},
                    new String[]{d(zp.datum()), s(zp.beginn()), n(zp.dauerS()), i(zp.schuesse().size()), clob(zp.text()), punkt(zp.x(), zp.z())}, true);
            b.append("DELETE FROM TD_ZUENDPLAN_SCHRITT WHERE ZUENDPLAN_ID IN (SELECT ZUENDPLAN_ID FROM TD_ZUENDPLAN WHERE NAME = ").append(s(zp.name()))
                    .append(" AND HERKUNFT = ").append(M).append(");\n");
            String pid = id("TD_ZUENDPLAN", "ZUENDPLAN_ID", "NAME = " + s(zp.name()));
            for (Daten.Schuss x : zp.schuesse())
                insert(b, "TD_ZUENDPLAN_SCHRITT", new String[]{"ZUENDPLAN_ID", "NR", "T_S", "EFFEKT_ID", "KALIBER_MM", "NEIGUNG_GRAD", "AZIMUT_GRAD"}, pid, i(x.nr()),
                        n(x.t()), id("TD_EFFEKT", "EFFEKT_ID", "NAME = " + s(x.effekt())), i(x.kaliber()), n(x.neigung()), n(x.azimut()));
        }

        kopf(b, "Sturmflut");
        for (Daten.Sturmflut f : Startdaten.sturmfluten()) {
            merge(b, "TD_STURMFLUT", new String[]{"NAME"}, new String[]{s(f.name())}, new String[]{"BEGINN", "SCHEITEL_ZEIT", "SCHEITEL_M", "KLASSE", "QUELLE", "BEMERKUNG"},
                    new String[]{tstz(f.beginnMs()), tstz(f.scheitelMs()), n(f.scheitelM()), s(f.klasse()), s(f.quelle()), s(f.bemerkung())}, true);
            b.append("DELETE FROM TD_STURMFLUT_PEGEL WHERE STURMFLUT_ID IN (SELECT STURMFLUT_ID FROM TD_STURMFLUT WHERE NAME = ").append(s(f.name()))
                    .append(" AND HERKUNFT = ").append(M).append(");\n");
            String fid = id("TD_STURMFLUT", "STURMFLUT_ID", "NAME = " + s(f.name()));
            for (Daten.Pegel p : f.pegel())
                insert(b, "TD_STURMFLUT_PEGEL", new String[]{"STURMFLUT_ID", "STUNDE", "ZEIT", "PEGEL_M", "WIND_MS", "WIND_AUS_GRAD", "HS_M"}, fid, n(p.stunde()), tstz(p.ms()),
                        n(p.pegel()), n(p.wind()), n(p.windAus()), n(p.hs()));
        }
        kopf(b, "Kante des Brodtener Ufers (DGM1)");
        b.append("DELETE FROM TD_KLIFFKANTE WHERE ART = 'DGM1' AND HERKUNFT = ").append(M).append(";\n");
        for (Daten.Kante k : a.kanten())
            insert(b, "TD_KLIFFKANTE", new String[]{"STAND", "ART", "LAENGE_M", "HERKUNFT", "GEOMETRY"}, s(k.stand()), s(k.art()), n(k.laenge()), M, geom(k.linie()));

        kopf(b, "Leuchtfeuer");
        for (Daten.Leuchtfeuer f : Startdaten.leuchtfeuer()) {
            merge(b, "TD_LEUCHTFEUER", new String[]{"NAME"}, new String[]{s(f.name())}, new String[]{"KENNUNG", "FEUERHOEHE_M", "TRAGWEITE_SM", "HEUTE_AN", "OST", "NORD",
                    "QUELLE", "GEOMETRY"}, new String[]{s(kurz(f.kennung(), 30)), n(f.feuerhoehe()), n(f.tragweite()), f.heuteAn() ? "1" : "0", ost(f.x(), f.z()),
                    nord(f.x(), f.z()), s(f.quelle()), punkt(f.x(), f.z())}, true);
            b.append("DELETE FROM TD_SEKTOR WHERE LEUCHTFEUER_ID IN (SELECT LEUCHTFEUER_ID FROM TD_LEUCHTFEUER WHERE NAME = ").append(s(f.name()))
                    .append(" AND HERKUNFT = ").append(M).append(");\n");
            for (Daten.Sektor x : f.sektoren())
                insert(b, "TD_SEKTOR", new String[]{"LEUCHTFEUER_ID", "NR", "VON_GRAD", "BIS_GRAD", "FARBE", "TRAGWEITE_SM"},
                        id("TD_LEUCHTFEUER", "LEUCHTFEUER_ID", "NAME = " + s(f.name())), i(x.nr()), n(x.von()), n(x.bis()), s(x.farbe()), n(x.tragweite()));
        }

        kopf(b, "Buslinien [G]");
        for (Daten.Linie x : Startdaten.linien()) {
            merge(b, "TD_LINIE", new String[]{"NAME"}, new String[]{s(x.name())}, new String[]{"NR", "BETREIBER", "TAKT_MIN"}, new String[]{s(x.nr()), s(x.betreiber()),
                    i(x.takt())}, true);
            b.append("DELETE FROM TD_ABFAHRT WHERE LINIE_ID IN (SELECT LINIE_ID FROM TD_LINIE WHERE NAME = ").append(s(x.name())).append(" AND HERKUNFT = ").append(M).append(");\n");
            for (Daten.Abfahrt f : x.abfahrten())
                insert(b, "TD_ABFAHRT", new String[]{"LINIE_ID", "HALTESTELLE", "KNOTEN", "TAGE", "ZEIT", "RICHTUNG"}, id("TD_LINIE", "LINIE_ID", "NAME = " + s(x.name())),
                        s(f.haltestelle()), l(f.knoten()), s(f.tage()), s(f.zeit()), s(f.richtung()));
        }
        b.append("\nCOMMIT;\n\n-- Ende 04_startdaten.sql\n");
        return b.toString();
    }

    static String kurz(String s, int n) { return s == null || s.length() <= n ? s : s.substring(0, n - 1) + "…"; }

    /** Schreibt das Skript; Argument: Ordner db/sql (Vorgabe). */
    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : "db/sql");
        Terrain t = null;
        try { Terrain g = Terrain.load(Terrain.defaultDir()); if (g.measured()) t = g; } catch (RuntimeException e) { System.out.println("Ohne Gelände: " + e.getMessage()); }
        Alles a = Alles.aus(t);
        String sql = skript(a);
        File f = new File(dir, "04_startdaten.sql");
        Files.writeString(f.toPath(), sql, StandardCharsets.UTF_8);
        System.out.println(String.format(Locale.GERMAN, "%s: %,d Zeichen · %s", f.getPath(), sql.length(), zahlen(a)));
        System.out.println(t == null ? "Ohne daten/gelaende: keine Kante aus dem DGM1." : "Kante aus dem DGM1: " + a.kanten().get(0).laenge() + " m");
    }
}
