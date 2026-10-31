package com.dan.timmendorf.test;

import com.dan.store.Ablage;
import com.dan.store.Db;
import com.dan.store.Einrichter;
import com.dan.store.Felder;
import com.dan.timmendorf.data.Chronik;
import com.dan.timmendorf.data.DateiSpeicher;
import com.dan.timmendorf.data.Daten;
import com.dan.timmendorf.data.DbSpeicher;
import com.dan.timmendorf.data.Speicher;
import com.dan.timmendorf.data.Startdaten;
import com.dan.timmendorf.data.Uebernahme;
import com.dan.timmendorf.data.Verbindung;
import com.dan.timmendorf.scene.Abbruch;
import com.dan.timmendorf.scene.Regie;
import com.dan.timmendorf.scene.Site;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Phase 17 (F48 A): die Prüfung gegen die echte Datenbank (PDBORCL, Schema TIMMENDORF aus {@code db/db.properties}).
 * {@code ant dbpruefung}. Sie richtet Teil 2 ein (wiederholbar), läuft den Einrichter ein zweites Mal (er muss nichts
 * ändern), prüft Objekte, Metadaten, räumliche Indizes, Sichten und die Zahl der Startdaten, rechnet Seebrücke, Bahn und
 * Ponton in 25832 nach und prüft alle Geometrien auf Gültigkeit. Dann je Speicherart einmal hin und zurück, in Oracle und
 * im Datei-Rückfall gleich: Zustand einer Sitzung mit Vorschau, Ansicht mit Bild, Preset, Regiebuch mit Stichworten,
 * Lauf, Ergebnisse der Regatta, Abbruch mit Kante, Ereignis. Mitgelieferte lassen sich nicht überschreiben. Die
 * Übernahme aus Dateien kommt genau einmal. Teil 1 bleibt unberührt (Prüfsummen der Netze). Hinterher wird
 * aufgeräumt. Ende: „Alles in Ordnung.“ – jede Zeile mit FEHLER sagt, was nicht ging.
 */
public final class DbPruefung {
    private static int ok, bad;
    private static final String P = "~Prüfung ";

    static void check(boolean c, String was) {
        if (c) ok++; else bad++;
        System.out.println((c ? "  ok     " : "  FEHLER ") + was);
    }

    static double zahl(Connection c, String sql) throws Exception {
        try (Statement st = c.createStatement(); ResultSet r = st.executeQuery(sql)) { return r.next() ? r.getDouble(1) : Double.NaN; }
    }

    static String text(Connection c, String sql) throws Exception {
        try (Statement st = c.createStatement(); ResultSet r = st.executeQuery(sql)) { return r.next() ? r.getString(1) : null; }
    }

    static void sql(Connection c, String sql) throws Exception { try (Statement st = c.createStatement()) { st.executeUpdate(sql); } }

    /** Ein kleines PNG zum Prüfen. */
    static byte[] png() throws Exception {
        java.awt.image.BufferedImage im = new java.awt.image.BufferedImage(32, 18, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 18; y++) for (int x = 0; x < 32; x++) im.setRGB(x, y, (x * 8) << 16 | (y * 14) << 8 | 120);
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(im, "png", bo);
        return bo.toByteArray();
    }

    /** Ein Abbruch zum Prüfen: 30 m Kante 2,5 m zurück am Brodtener Ufer. */
    static Daten.Abbruch abbruch(int nr) {
        Abbruch.Ereignis e = new Abbruch.Ereignis(70 + nr, Site.BRODTEN.x(), Site.BRODTEN.z(), 0.6, -0.8, 30, 2.5);
        return Chronik.abbruchFuer(nr, e, 1_697_580_000_000L, 412.5);
    }

    /** Hin und zurück mit einem Speicher (Oracle oder Dateien). */
    static void hinUndZurueck(Speicher s, String wo) throws Exception {
        System.out.println("Hin und zurück · " + wo);
        // Sitzung mit Zustand und Vorschau
        long sid = s.sitzungBeginnen(P.strip(), "Prüfung", "MITTEL");
        LinkedHashMap<String, String> z = new LinkedHashMap<>();
        z.put("app.version", "Prüfung");
        z.put("uhr.ms", "1785618300000");
        z.put("uhr.lapse", "2.5");
        z.put("regie.lageText", P + "Lage");
        z.put("kamera.x", "100.5");
        z.put("kamera.y", "4.2");
        z.put("kamera.z", "-40.25");
        z.put("pegel.jetzt", "0.123");
        z.put("wiring.windSpeed", "5.5");
        z.put("wiring.windFromDeg", "45");
        z.put("regatta.boot.0", "10.5 -20.25 45 3.2 BEAT 0 0 false - 3 0");
        z.put("text", "mit Umlauten äöü und „Anführung“; Semikolon");
        String zt = Felder.alsText(z, "Prüfung");
        s.sitzungSichern(sid, new Ablage.Sicherung(zt, com.dan.timmendorf.data.Zustand.spalten(z)));
        byte[] png = png();
        s.vorschau(sid, png);
        Daten.Sitzung l = s.letzteSitzung(-1);
        check(l != null && l.id() == sid && Felder.ausText(l.zustand()).equals(Felder.ausText(zt)) && Arrays.equals(l.vorschau(), png)
                        && l.szeneMs() != null && l.szeneMs() == 1_785_618_300_000L && (P + "Lage").equals(l.lage()),
                "Sitzung " + sid + ": Zustand mit " + z.size() + " Werten, Vorschau " + png.length + " Byte, Szenenzeit " + (l == null ? "?" : l.szeneText()) + " zurück");
        s.sitzungBeenden(sid);
        check(s.sitzungen(5).stream().anyMatch(x -> x.id() == sid && x.sauber()), "Sitzung sauber beendet und in der Liste der letzten");
        // Ansicht mit Bild
        double[] k = {Site.SCHLEIFE.x(), 4.3, Site.SCHLEIFE.z(), 230, 4};
        long aid = s.speichern(new Daten.Ansicht(0, P + "Ansicht", null, "GEMERKT", k, null, 55.0, true, 1_785_618_300_000L, P + "Lage", zt, png, Daten.EIGEN), sid);
        Daten.Ansicht a = s.ansicht(aid);
        check(a.name().startsWith(P + "Ansicht") && Math.abs(a.kamera()[0] - k[0]) < 0.01 && Math.abs(a.kamera()[2] - k[2]) < 0.01 && Math.abs(a.kamera()[1] - 4.3) < 0.01
                && a.mitUhr() && Arrays.equals(a.vorschau(), png) && Felder.ausText(a.zustand()).equals(z), "Ansicht " + aid + " mit Uhr, Lage, Zustand und Bild zurück");
        s.loeschenAnsicht(aid);
        check(s.ansichten().stream().noneMatch(x -> x.id() == aid && "GEMERKT".equals(x.art()) && x.name().startsWith(P)), "Ansicht gelöscht");
        // Preset
        com.dan.ocean.Ocean o = com.dan.ocean.Ocean.gale();
        Map<String, String> f = Felder.lesen(o);
        s.speichern(new Daten.PaketPreset("com.dan.ocean", "Ocean", P + "Preset", "Prüfung", Daten.EIGEN, f));
        Daten.PaketPreset pr = s.presets("com.dan.ocean", "Ocean").stream().filter(x -> x.name().equals(P + "Preset")).findFirst().orElse(null);
        check(pr != null && pr.felder().equals(f) && Daten.EIGEN.equals(pr.herkunft()), "Preset Ocean mit " + f.size() + " Feldern zurück");
        s.loeschenPreset("com.dan.ocean", "Ocean", P + "Preset");
        boolean geschuetzt = false;
        try { s.loeschenPreset("com.dan.ocean", "Ocean", "gale"); } catch (Ablage.Fehler e) { geschuetzt = true; }
        check(geschuetzt, "Mitgeliefertes Preset „gale“ lässt sich nicht löschen");
        // Regiebuch
        String text = Regie.text("weg.txt").replaceFirst("(?m)^Regiebuch:.*$", "Regiebuch: " + P + "Weg");
        s.speichern(new Daten.Regiebuch(P + "Weg", text, null, null, Daten.EIGEN));
        Daten.Regiebuch rb = s.regiebuecher().stream().filter(x -> x.name().equals(P + "Weg")).findFirst().orElse(null);
        check(rb != null && rb.text().replace("\r", "").equals(text.replace("\r", "")), "Regiebuch „" + P + "Weg“ mit " + Regie.parse(text).zeilen().size() + " Stichworten zurück");
        geschuetzt = false;
        try { s.speichern(Startdaten.regiebuecher().get(0)); } catch (Ablage.Fehler e) { geschuetzt = true; }
        check(geschuetzt, "Mitgeliefertes Regiebuch „" + Startdaten.regiebuecher().get(0).name() + "“ wird nicht überschrieben");
        s.loeschenRegiebuch(P + "Weg");
        // Lauf, Ergebnisse, Abbruch, Ereignis
        s.regielauf(new Daten.Regielauf(P + "Weg", 1_000_000L, 1_060_000L, true, false, 1024, 72.5, 0, 3, 3.5, 12.25, "MITTEL", "docs/status/Prüfung.png"), sid);
        Daten.Regielauf rl = s.regielaeufe(1).get(0);
        check(rl.name().equals(P + "Weg") && rl.vollstaendig() && rl.fotos() == 3 && Math.abs(rl.gewartetS() - 72.5) < 0.05, "Lauf eines Regiebuchs zurück");
        List<Daten.Ergebnis> er = List.of(new Daten.Ergebnis(Startdaten.REGATTA, 99, "~P1", 1, 1234.5, 12, 2, false, 5.5, 45, 1_785_618_300_000L),
                new Daten.Ergebnis(Startdaten.REGATTA, 99, "~P2", 2, 1250.0, 14, 2, true, 5.5, 45, 1_785_618_310_000L));
        s.ergebnisse(er, sid);
        List<Daten.Ergebnis> erz = s.ergebnisse(2);
        check(erz.size() == 2 && erz.stream().anyMatch(x -> "~P2".equals(x.segelnummer()) && x.fruehstart() && x.platz() == 2), "Zwei Ergebnisse der Regatta zurück");
        Daten.Abbruch ab = abbruch(1);
        s.abbruch(ab, sid);
        Daten.Abbruch abz = s.abbrueche(1).get(0);
        check(abz.nr() == 1 && Math.abs(abz.rueck() - 2.5) < 0.01 && abz.flaeche() != null && Math.abs(abz.flaeche().flaecheM2() - ab.flaeche().flaecheM2()) < 1,
                String.format(Locale.GERMAN, "Abbruch mit Fläche %.1f m² zurück (gerechnet %.1f m²)", abz.flaeche() == null ? 0 : abz.flaeche().flaecheM2(), ab.flaeche().flaecheM2()));
        s.ereignis("MESSUNG", "Prüfung", P + "Messung", 60.0, 12.5, sid);
        check(s.letzteZeilen(20).stream().anyMatch(x -> x.contains(P + "Messung")) || !s.datenbank(), "Ereignis im Protokoll");
    }

    public static void main(String[] args) throws Exception {
        long t0 = System.nanoTime();
        System.out.println("Timmendorf · Prüfung der Datenbank Teil 2 (Phase 17)");
        Db.Konfig k = Verbindung.konfig();
        System.out.println("Verbindung " + k.kurz());
        if (!k.hatPasswort()) {
            System.out.println("  FEHLER Kein Passwort in db/db.properties und keine Umgebungsvariable " + Verbindung.ENV
                    + ". Einmal mit dem Startbild verbinden (es trägt das Passwort ein) oder es von Hand eintragen.");
            System.exit(1);
        }
        DbSpeicher db = new DbSpeicher(k);
        System.out.println(db.wo());
        Connection c = db.verbindung();
        Einrichter ein = Verbindung.einrichter();
        String teil1 = null;
        try { teil1 = text(c, "SELECT LISTAGG(NETZ || '=' || PRUEFSUMME, ';') WITHIN GROUP (ORDER BY NETZ) FROM TD_NETZ_STAND"); } catch (Exception e) { /* ohne Teil 1 */ }

        System.out.println("Einrichter");
        Einrichter.Ergebnis e1 = ein.einrichten(c, Verbindung.skripte(), (t, a) -> { });
        for (String z : e1.zeilen()) if (!z.startsWith("ok")) System.out.println("    " + z);
        check(e1.ok(), "Erster Lauf: " + e1.kurz() + " (" + e1.ausgefuehrt() + " ausgeführt, " + e1.vorhanden() + " schon da)");
        Einrichter.Ergebnis e2 = ein.einrichten(c, Verbindung.skripte(), (t, a) -> { });
        check(e2.ok() && e2.fehler() == 0, "Zweiter Lauf geht ohne Fehler durch (wiederholbar): " + e2.kurz());
        Einrichter.Ergebnis pr = ein.pruefen(c, Verbindung.skripte());
        for (String z : pr.zeilen()) System.out.println("    " + z);
        check(pr.ok(), "Einrichter prüft: " + pr.kurz());
        check(ein.stufe(c) == Verbindung.VERSION, "Ausbaustufe " + ein.stufe(c));

        System.out.println("Trigger, Geometrien, Sichten");
        sql(c, "INSERT INTO TD_BOOT_KLASSE (NAME) VALUES ('" + P + "Trigger')");
        try (Statement st = c.createStatement(); ResultSet r = st.executeQuery("SELECT BOOT_KLASSE_ID, ANGELEGT, GEAENDERT, HERKUNFT FROM TD_BOOT_KLASSE WHERE NAME = '" + P + "Trigger'")) {
            check(r.next() && r.getLong(1) > 0 && r.getTimestamp(2) != null && r.getTimestamp(3) != null && "EIGEN".equals(r.getString(4)),
                    "Trigger setzt Schlüssel, ANGELEGT und GEAENDERT; HERKUNFT ist EIGEN");
        }
        sql(c, "DELETE FROM TD_BOOT_KLASSE WHERE NAME = '" + P + "Trigger'");
        double ungueltig = 0;
        for (String m : Verbindung.METADATEN) {
            String[] tc = m.split("\\.");
            double u = zahl(c, "SELECT COUNT(*) FROM " + tc[0] + " WHERE " + tc[1] + " IS NOT NULL AND MDSYS.SDO_GEOM.VALIDATE_GEOMETRY_WITH_CONTEXT(" + tc[1] + ", 0.005) <> 'TRUE'");
            if (u > 0) System.out.println("    ungültig in " + m + ": " + (int) u);
            ungueltig += u;
        }
        check(ungueltig == 0, "Alle Geometrien gültig (VALIDATE_GEOMETRY_WITH_CONTEXT) in " + Verbindung.METADATEN.length + " Spalten");
        double bruecke = zahl(c, "SELECT MDSYS.SDO_GEOM.SDO_LENGTH(GEOMETRY, 0.005) FROM TD_FAHRT WHERE NAME = 'Seebrücke'"),
                brueckeSzene = Math.hypot(Site.SCHLEIFE.x(), Site.SCHLEIFE.z());
        double bahn = zahl(c, "SELECT MDSYS.SDO_GEOM.SDO_LENGTH(GEOMETRY, 0.005) FROM TD_REGATTA WHERE NAME = '" + Startdaten.REGATTA + "'");
        var rg = Startdaten.regatten().get(0);
        double bahnSzene = Math.hypot(rg.tonnen().get(2).x() - rg.tonnen().get(0).x(), rg.tonnen().get(2).z() - rg.tonnen().get(0).z());
        double ponton = zahl(c, "SELECT MDSYS.SDO_GEOM.SDO_DISTANCE(z.GEOMETRY, MDSYS.SDO_GEOMETRY(2001, 25832, MDSYS.SDO_POINT_TYPE(" + Site.E0 + ", " + Site.N0
                + ", NULL), NULL, NULL), 0.005) FROM TD_ZUENDPLAN z WHERE z.NAME = '" + Startdaten.FEUERWERK + "'");
        double[] pp = Startdaten.ponton();
        check(Math.abs(bruecke / brueckeSzene - 1) < 0.002 && Math.abs(bahn / bahnSzene - 1) < 0.002 && Math.abs(ponton - Math.hypot(pp[0], pp[1])) < 0.05,
                String.format(Locale.GERMAN, "In 25832 nachgerechnet: Seebrücke %.1f m (Szene %.1f), Bahn %.1f m (Szene %.1f), Ponton %.1f m vor dem Landende", bruecke,
                        brueckeSzene, bahn, bahnSzene, ponton));
        int lesbar = 0;
        for (String v : Verbindung.SICHTEN) { zahl(c, "SELECT COUNT(*) FROM " + v); lesbar++; }
        check(lesbar == Verbindung.SICHTEN.length, lesbar + " Sichten lesbar");
        double pegel = zahl(c, "SELECT MAX(PEGEL_M) FROM TD_STURMFLUT_PEGEL p JOIN TD_STURMFLUT f ON f.STURMFLUT_ID = p.STURMFLUT_ID WHERE f.NAME = '" + Startdaten.STURMFLUT + "'");
        String zeit = text(c, "SELECT TO_CHAR(SCHEITEL_ZEIT, 'DD.MM.YYYY HH24:MI TZH:TZM') FROM TD_STURMFLUT WHERE NAME = '" + Startdaten.STURMFLUT + "'");
        check(Math.abs(pegel - 1.77) < 0.011 && zeit != null && zeit.startsWith("20.10.2023 23:00"), "Sturmflut 2023: Scheitel " + pegel + " m NHN um " + zeit);
        double sterne = zahl(c, "SELECT COUNT(*) FROM TD_ZUENDPLAN_SCHRITT s JOIN TD_ZUENDPLAN z ON z.ZUENDPLAN_ID = s.ZUENDPLAN_ID WHERE z.NAME = '" + Startdaten.FEUERWERK
                + "' AND s.EFFEKT_ID IS NOT NULL");
        check(sterne == Startdaten.zuendplaene().get(0).schuesse().size(), String.format(Locale.GERMAN, "Großes Feuerwerk: %.0f Schüsse, jeder mit seinem Effekt", sterne));

        hinUndZurueck(db, db.wo());
        File tmp = Files.createTempDirectory("timmendorf_pruefung").toFile();
        DateiSpeicher datei = new DateiSpeicher(tmp);
        hinUndZurueck(datei, datei.wo());

        System.out.println("Übernahme aus Dateien");
        String u1 = Uebernahme.uebernehmen(datei, db);
        check(u1.startsWith("1 Läufe, 2 Ergebnisse, 1 Abbrüche, 1 Ereignisse"), "Erste Übernahme: " + u1);
        File uebernommen = new File(new File(tmp, "chronik"), "regielaeufe.csv.uebernommen");
        check(uebernommen.isFile(), "Die CSV heißen danach *.uebernommen");
        datei.speichern(new Daten.Regiebuch(P + "Datei", Regie.text("weg.txt").replaceFirst("(?m)^Regiebuch:.*$", "Regiebuch: " + P + "Datei"), null, null, Daten.EIGEN));
        String u2 = Uebernahme.uebernehmen(datei, db);
        String u3 = Uebernahme.uebernehmen(datei, db);
        check(u2.contains("1 Regiebücher") && u3.contains("0 Regiebücher") && u3.startsWith("0 Läufe"), "Zweite Übernahme bringt nur das Neue (" + u2 + "), die dritte nichts (" + u3 + ")");

        if (teil1 != null) {
            String nach = text(c, "SELECT LISTAGG(NETZ || '=' || PRUEFSUMME, ';') WITHIN GROUP (ORDER BY NETZ) FROM TD_NETZ_STAND");
            check(teil1.equals(nach), "Teil 1 unberührt: TD_NETZ_STAND mit denselben Prüfsummen");
        } else System.out.println("    (Teil 1 nicht eingerichtet: ant netzdb)");

        System.out.println("Aufräumen");
        sql(c, "DELETE FROM TD_SITZUNG WHERE APP_VERSION = '" + P.strip() + "'");
        sql(c, "DELETE FROM TD_REGIELAUF WHERE NAME LIKE '~Prüfung%'");
        sql(c, "DELETE FROM TD_REGATTA_ERGEBNIS WHERE SEGELNUMMER LIKE '~P%'");
        sql(c, "DELETE FROM TD_ABBRUCH WHERE SITZUNG_ID IS NULL AND NR = 1 AND STUNDE = 71");
        sql(c, "DELETE FROM TD_KLIFFKANTE WHERE ART = 'LAUF' AND ABBRUCH_ID IS NOT NULL AND ABBRUCH_ID NOT IN (SELECT ABBRUCH_ID FROM TD_ABBRUCH)");
        sql(c, "DELETE FROM TD_LOG WHERE TEXT LIKE '~Prüfung%' OR QUELLE = 'Prüfung'");
        sql(c, "DELETE FROM TD_ANSICHT WHERE NAME LIKE '~Prüfung%'");
        sql(c, "DELETE FROM TD_PAKET_PRESET WHERE NAME LIKE '~Prüfung%'");
        sql(c, "DELETE FROM TD_REGIEBUCH WHERE NAME LIKE '~Prüfung%'");
        sql(c, "DELETE FROM TD_UEBERNAHME WHERE DATEI LIKE '%timmendorf_pruefung%' OR DATEI LIKE '%~Prüfung%'");
        double rest = zahl(c, "SELECT (SELECT COUNT(*) FROM TD_SITZUNG WHERE APP_VERSION = '" + P.strip() + "') + (SELECT COUNT(*) FROM TD_REGIEBUCH WHERE NAME LIKE '~Prüfung%') "
                + "+ (SELECT COUNT(*) FROM TD_REGATTA_ERGEBNIS WHERE SEGELNUMMER LIKE '~P%') FROM DUAL");
        check(rest == 0, "Alles aus der Prüfung wieder weg");
        db.schliessen();
        System.out.println(String.format(Locale.GERMAN, "%d Prüfungen in %.1f s", ok + bad, (System.nanoTime() - t0) / 1e9));
        System.out.println(bad == 0 ? "Alles in Ordnung." : "ERGEBNIS: " + bad + " Fehler");
        System.exit(bad == 0 ? 0 : 1);
    }

    private DbPruefung() { }
}
