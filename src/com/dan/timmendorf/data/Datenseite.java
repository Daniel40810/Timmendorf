package com.dan.timmendorf.data;

import com.dan.store.Ablage;
import com.dan.store.Db;
import com.dan.store.Dienst;
import com.dan.store.Protokoll;

/**
 * Die Datenseite der App (Phase 17, F37 A): welcher {@link Speicher} gilt und die Brücke zu {@link Dienst} (Sitzung,
 * Protokoll) aus {@code com.dan.store}. Ohne Datenbank gilt {@link DateiSpeicher}; beides kann dasselbe. Kommt die
 * Datenbank, übernimmt {@link #setzen} aus dem Rückfall, was dort liegt.
 */
public final class Datenseite {
    private Datenseite() { }

    private static volatile Speicher speicher;
    /** Das Ergebnis des letzten Einrichtens („alles in Ordnung“ oder der erste Befund), für Startbild und DATEN. */
    public static volatile String einrichterStand = "";
    /** Was die letzte Übernahme brachte (leer: nichts). */
    public static volatile String uebernahmeStand = "";

    static {
        Protokoll.app = "Timmendorf";
        Dienst.faden = "Timmendorf-Daten";
        Dienst.ersatz = () -> new DateiSpeicher(DateiSpeicher.ordner());
    }

    /** Der gültige Speicher (ohne Datenbank die Dateien in der Ablage). */
    public static Speicher speicher() {
        Speicher s = speicher;
        if (s == null) { s = new DateiSpeicher(DateiSpeicher.ordner()); speicher = s; Dienst.setzen(s); }
        return s;
    }

    public static boolean gesetzt() { return speicher != null; }

    /** Den Speicher wechseln (nach Verbinden, Einrichten oder „Ohne Datenbank“); die Sitzung zieht mit um. */
    public static void setzen(Speicher s) {
        speicher = s;
        Dienst.setzen(s);
        Verbindung.stand(s.datenbank() ? s.wo().replace("Datenbank ", "") : "ohne Datenbank");
        Protokoll.info("Daten", "Speicher: " + s.wo());
        if (s instanceof DbSpeicher d) uebernehmen(d);
    }

    /** Was im Datei-Rückfall liegt, in die Datenbank (im Faden der Daten, das Bild wartet nicht). */
    public static void uebernehmen(DbSpeicher d) {
        DateiSpeicher f = new DateiSpeicher(DateiSpeicher.ordner());
        if (!Uebernahme.etwasDa(f)) return;
        Dienst.spaeter(() -> {
            try {
                uebernahmeStand = Uebernahme.uebernehmen(f, d);
            } catch (Ablage.Fehler | RuntimeException e) {
                uebernahmeStand = "Übernahme: " + e.getMessage();
                Protokoll.warnung("Übernahme", e.getMessage());
            }
        });
    }

    /** Verbinden, wenn db/db.properties ein Passwort hat; sonst null (und warum in grund[0]). */
    public static DbSpeicher verbinden(Db.Konfig k, String[] grund) {
        try {
            if (!k.hatPasswort()) { if (grund != null) grund[0] = "kein Passwort"; return null; }
            return new DbSpeicher(k);
        } catch (Ablage.Fehler e) {
            if (grund != null) grund[0] = e.getMessage();
            return null;
        }
    }

    /** „Datenbank TIMMENDORF auf PDBORCL“ oder „Dateien in …\Timmendorf“ */
    public static String wo() { return speicher().wo(); }
}
