package com.dan.timmendorf.data;

import com.dan.store.Db;

import com.dan.store.Einrichter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Das Schema TIMMENDORF (Werkbuch Abschnitt 12, F30 A): Ort von {@code db/}, Zugang aus {@code db/db.properties}. Schema
 * und Benutzer legt {@code db/USER.sql} an ({@code manage_user('Timmendorf', …)} auf PDBORCL, Rolle DB_CONNECT). Phase 1
 * verbindet nur und meldet, wer man ist; die Netze ({@code TD_NET_…}, Teil 1 in {@code db/netz}) kommen mit Phase 3,
 * seit Phase 17 Teil 2 in {@code db/sql} (F37–F49 „alle A“): 31 Tabellen mit Sequenz und Trigger, nie IDENTITY,
 * Geometrien in UTM 32N (SRID 25832), zehn Sichten, eingerichtet vom {@link #einrichter()} aus {@code com.dan.store}.
 */
public final class Verbindung {
    private Verbindung() { }

    public static final String PRAEFIX = "TD_";
    /** Umgebungsvariable für ein leeres Passwort (wie in db.properties.example beschrieben). */
    public static final String ENV = "Timmendorf_DB_PASSWORD";

    /** Die Ausbaustufe von Teil 2 (TD_SCHEMA.VERSION). */
    public static final int VERSION = 1;
    /** Die Koordinaten der Tabellen: ETRS89 / UTM 32N. */
    public static final int SRID = 25832;
    public static final String[] SICHTEN = {"TD_V_ANSICHT", "TD_V_SITZUNG_ORT", "TD_V_REGATTA_BAHN", "TD_V_ZUENDPLAN", "TD_V_KLIFFKANTE", "TD_V_ABBRUCH",
            "TD_V_FAHRT", "TD_V_LETZTE_SITZUNG", "TD_V_BEFUND", "TD_V_REGIELAUF"};
    /** Die Geometriespalten der Tabellen (Metadaten und je ein räumlicher Index). */
    public static final String[] METADATEN = {"TD_SITZUNG.KAMERA", "TD_ANSICHT.GEOMETRY", "TD_FAHRT.GEOMETRY", "TD_REGATTA.GEOMETRY", "TD_REGATTA_TONNE.GEOMETRY",
            "TD_ZUENDPLAN.GEOMETRY", "TD_KLIFFKANTE.GEOMETRY", "TD_ABBRUCH.GEOMETRY", "TD_LEUCHTFEUER.GEOMETRY"};
    /** Die Geometrien der Sichten (Metadaten ohne Index). */
    public static final String[] SICHT_METADATEN = {"TD_V_ANSICHT.GEOMETRY", "TD_V_SITZUNG_ORT.GEOMETRY", "TD_V_REGATTA_BAHN.GEOMETRY", "TD_V_ZUENDPLAN.GEOMETRY",
            "TD_V_KLIFFKANTE.GEOMETRY", "TD_V_ABBRUCH.GEOMETRY", "TD_V_FAHRT.GEOMETRY"};

    /** Der Ordner der Skripte von Teil 2. */
    public static File skripte() { return new File(ordner(), "sql"); }

    /** Die räumlichen Indizes (je Geometriespalte einer: TD_ANSICHT_GEOMETRY_SX …). */
    public static String[] raeumlich() {
        String[] r = new String[METADATEN.length];
        for (int i = 0; i < r.length; i++) r[i] = METADATEN[i].replace('.', '_') + "_SX";
        return r;
    }

    /** Die Zahlen der Startdaten aus der Zeile {@code -- ZAHLEN: Art=n; …} von 04_startdaten.sql. */
    public static Map<String, Integer> zahlen(File dir) {
        Map<String, Integer> m = new LinkedHashMap<>();
        try (var lines = Files.lines(new File(dir, "04_startdaten.sql").toPath(), StandardCharsets.UTF_8)) {
            String z = lines.filter(l -> l.startsWith("-- ZAHLEN:")).findFirst().orElse(null);
            if (z != null)
                for (String p : z.substring(10).split(";")) {
                    String[] kv = p.strip().split("=");
                    if (kv.length == 2) m.put(kv[0].strip(), Integer.parseInt(kv[1].strip()));
                }
        } catch (IOException | RuntimeException e) { /* dann prüft der Einrichter ohne Zahlen */ }
        return m;
    }

    /** Die SQL-Abfrage je Zahl der Startdaten (Schlüssel wie in {@link StartdatenSql#zahlen}). */
    static final Map<String, String> ZAEHLEN = new LinkedHashMap<>();
    static {
        String m = " WHERE HERKUNFT = 'MITGELIEFERT'";
        ZAEHLEN.put("Befunde", "SELECT COUNT(*) FROM TD_BEFUND" + m);
        ZAEHLEN.put("Belege", "SELECT COUNT(*) FROM TD_BEFUND_QUELLE x JOIN TD_BEFUND b ON b.BEFUND_ID = x.BEFUND_ID WHERE b.HERKUNFT = 'MITGELIEFERT'");
        ZAEHLEN.put("Regiebücher", "SELECT COUNT(*) FROM TD_REGIEBUCH" + m);
        ZAEHLEN.put("Stichworte", "SELECT COUNT(*) FROM TD_STICHWORT x JOIN TD_REGIEBUCH r ON r.REGIEBUCH_ID = x.REGIEBUCH_ID WHERE r.HERKUNFT = 'MITGELIEFERT'");
        ZAEHLEN.put("Ansichten", "SELECT COUNT(*) FROM TD_ANSICHT" + m);
        ZAEHLEN.put("Fahrten", "SELECT COUNT(*) FROM TD_FAHRT" + m);
        ZAEHLEN.put("Presets", "SELECT COUNT(*) FROM TD_PAKET_PRESET" + m);
        ZAEHLEN.put("Presetwerte", "SELECT COUNT(*) FROM TD_PAKET_WERT x JOIN TD_PAKET_PRESET p ON p.PAKET_PRESET_ID = x.PAKET_PRESET_ID WHERE p.HERKUNFT = 'MITGELIEFERT'");
        ZAEHLEN.put("Bootsklassen", "SELECT COUNT(*) FROM TD_BOOT_KLASSE" + m);
        ZAEHLEN.put("Polaren", "SELECT COUNT(*) FROM TD_POLARE x JOIN TD_BOOT_KLASSE k ON k.BOOT_KLASSE_ID = x.BOOT_KLASSE_ID WHERE k.HERKUNFT = 'MITGELIEFERT' AND x.ART = 'GERECHNET'");
        ZAEHLEN.put("Boote", "SELECT COUNT(*) FROM TD_BOOT" + m);
        ZAEHLEN.put("Regatten", "SELECT COUNT(*) FROM TD_REGATTA" + m);
        ZAEHLEN.put("Tonnen", "SELECT COUNT(*) FROM TD_REGATTA_TONNE x JOIN TD_REGATTA r ON r.REGATTA_ID = x.REGATTA_ID WHERE r.HERKUNFT = 'MITGELIEFERT'");
        ZAEHLEN.put("Effekte", "SELECT COUNT(*) FROM TD_EFFEKT" + m);
        ZAEHLEN.put("Zündpläne", "SELECT COUNT(*) FROM TD_ZUENDPLAN" + m);
        ZAEHLEN.put("Schüsse", "SELECT COUNT(*) FROM TD_ZUENDPLAN_SCHRITT x JOIN TD_ZUENDPLAN z ON z.ZUENDPLAN_ID = x.ZUENDPLAN_ID WHERE z.HERKUNFT = 'MITGELIEFERT'");
        ZAEHLEN.put("Sturmfluten", "SELECT COUNT(*) FROM TD_STURMFLUT" + m);
        ZAEHLEN.put("Pegel", "SELECT COUNT(*) FROM TD_STURMFLUT_PEGEL x JOIN TD_STURMFLUT f ON f.STURMFLUT_ID = x.STURMFLUT_ID WHERE f.HERKUNFT = 'MITGELIEFERT'");
        ZAEHLEN.put("Kanten", "SELECT COUNT(*) FROM TD_KLIFFKANTE" + m + " AND ART = 'DGM1'");
        ZAEHLEN.put("Leuchtfeuer", "SELECT COUNT(*) FROM TD_LEUCHTFEUER" + m);
        ZAEHLEN.put("Sektoren", "SELECT COUNT(*) FROM TD_SEKTOR x JOIN TD_LEUCHTFEUER l ON l.LEUCHTFEUER_ID = x.LEUCHTFEUER_ID WHERE l.HERKUNFT = 'MITGELIEFERT'");
        ZAEHLEN.put("Linien", "SELECT COUNT(*) FROM TD_LINIE" + m);
        ZAEHLEN.put("Abfahrten", "SELECT COUNT(*) FROM TD_ABFAHRT x JOIN TD_LINIE l ON l.LINIE_ID = x.LINIE_ID WHERE l.HERKUNFT = 'MITGELIEFERT'");
        ZAEHLEN.put("Sichten mit Geometrie", "SELECT COUNT(*) FROM USER_SDO_GEOM_METADATA WHERE TABLE_NAME LIKE 'TD\\_V\\_%' ESCAPE '\\' AND SRID = 25832 "
                + "AND TABLE_NAME NOT LIKE 'TD\\_V\\_NET\\_%' ESCAPE '\\' AND TABLE_NAME NOT IN ('TD_V_HALTESTELLE', 'TD_V_SEEZEICHEN')");
    }

    /** Der Einrichter für Teil 2 (Präfix TD_, Stufe 1, 25832). Teil 1 richtet {@code ant netzdb} ein. */
    public static Einrichter einrichter() {
        Einrichter e = new Einrichter();
        e.praefix = PRAEFIX;
        e.version = VERSION;
        e.srid = SRID;
        e.sichten = SICHTEN.clone();
        String[] meta = new String[METADATEN.length + SICHT_METADATEN.length];
        System.arraycopy(METADATEN, 0, meta, 0, METADATEN.length);
        System.arraycopy(SICHT_METADATEN, 0, meta, METADATEN.length, SICHT_METADATEN.length);
        e.metadaten = meta;
        e.raeumlich = raeumlich();
        e.startdaten = () -> {
            List<Einrichter.Soll> l = new ArrayList<>();
            Map<String, Integer> z = zahlen(skripte());
            for (Map.Entry<String, String> q : ZAEHLEN.entrySet()) {
                if (z.containsKey(q.getKey())) l.add(new Einrichter.Soll(q.getKey(), q.getValue(), z.get(q.getKey())));
                else if (q.getKey().equals("Sichten mit Geometrie")) l.add(new Einrichter.Soll(q.getKey(), q.getValue(), SICHT_METADATEN.length));
            }
            return l;
        };
        e.rechnet = () -> List.of(
                new Einrichter.Soll("Seebrücke bis Schleife (m, aus der Fahrt in 25832)",
                        "SELECT TO_CHAR(ROUND(MDSYS.SDO_GEOM.SDO_LENGTH(GEOMETRY, 0.005))) FROM TD_FAHRT WHERE NAME = 'Seebrücke'", -1),
                new Einrichter.Soll("Regattabahn: Startschiff bis Luvtonne (m)",
                        "SELECT TO_CHAR(ROUND(MDSYS.SDO_GEOM.SDO_LENGTH(GEOMETRY, 0.005))) FROM TD_REGATTA WHERE ROWNUM = 1", -1),
                new Einrichter.Soll("Ponton bis Landende der Seebrücke (m)",
                        "SELECT TO_CHAR(ROUND(MDSYS.SDO_GEOM.SDO_DISTANCE(z.GEOMETRY, a.GEOMETRY, 0.005))) FROM TD_ZUENDPLAN z, TD_ANSICHT a "
                                + "WHERE z.NAME = 'Großes Feuerwerk' AND a.NAME = 'Seebrücke'", -1),
                new Einrichter.Soll("Sturmflut 2023: Scheitel, Stunden über 1,50 m",
                        "SELECT TO_CHAR(MAX(PEGEL_M), 'FM0.00') || ' m, ' || TO_CHAR(SUM(CASE WHEN PEGEL_M >= 1.5 THEN 1 ELSE 0 END)) || ' h' FROM TD_STURMFLUT_PEGEL", -1),
                new Einrichter.Soll("Chronik: Sitzungen, Läufe, Ergebnisse, Abbrüche",
                        "SELECT (SELECT COUNT(*) FROM TD_SITZUNG) || ', ' || (SELECT COUNT(*) FROM TD_REGIELAUF) || ', ' || (SELECT COUNT(*) FROM TD_REGATTA_ERGEBNIS) "
                                + "|| ', ' || (SELECT COUNT(*) FROM TD_ABBRUCH) FROM DUAL", -1));
        return e;
    }

    /** Der Stand der Verbindung (für Startbild, Bedienfeld und Statuszeile). */
    private static volatile String stand = "ohne Datenbank";

    /** Der Ordner db: -Dtimmendorf.db.dir, sonst ./db, sonst db neben dem Jar. */
    public static File ordner() { return Db.ordner("timmendorf.db.dir", Verbindung.class); }

    /**
     * Der Zugang aus db/db.properties. Fehlt die Datei, wird sie aus der Vorlage db.properties.example angelegt (ohne
     * Passwort); das Startbild fragt dann danach und trägt es nach dem Verbinden ein.
     */
    public static Db.Konfig konfig() throws IOException {
        File f = new File(ordner(), "db.properties"), vorlage = new File(ordner(), "db.properties.example");
        if (!f.isFile() && vorlage.isFile()) Files.copy(vorlage.toPath(), f.toPath());
        Db.Konfig k = Db.konfig(f, "Timmendorf", ENV);
        if (!k.hatPasswort()) {
            // auch in Großbuchstaben (Windows kennt beide, andere Systeme nicht)
            String e = System.getenv(ENV.toUpperCase(java.util.Locale.ROOT));
            if (e != null && !e.isEmpty()) k = k.mitPasswort(e.toCharArray());
        }
        return k;
    }

    /** Der Zugang, mit dem das Startbild verbunden hat (null ohne Datenbank); die Netze lesen damit aus Oracle (Phase 3b). */
    private static volatile Db.Konfig verbunden;

    public static Db.Konfig verbunden() { return verbunden; }

    public static void verbunden(Db.Konfig k) { verbunden = k; }

    /** „TIMMENDORF auf PDBORCL · Oracle 21“ oder „ohne Datenbank“. */
    public static String stand() { return stand; }

    public static void stand(String s) { stand = s; }
}
