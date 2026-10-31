package com.dan.timmendorf.data;

import com.dan.store.Db;
import com.dan.store.Einrichter;

import java.sql.Connection;

/**
 * {@code ant dbeinrichten} (Phase 17, F48 A): richtet Teil 2 des Schemas TIMMENDORF aus {@code db/sql} ein, ohne
 * Startbild. Legt an, was fehlt, trägt fehlende Spalten nach, löscht nie etwas, und meldet „alles in Ordnung“ oder
 * genau, was fehlt. Den Abbau macht nur {@code db/sql/99_abbau.sql} von Hand.
 */
public final class Einrichten {
    private Einrichten() { }

    public static void main(String[] args) throws Exception {
        Db.Konfig k = Verbindung.konfig();
        if (!k.hatPasswort()) {
            System.out.println("FEHLER Kein Passwort in db/db.properties und keine Umgebungsvariable " + Verbindung.ENV);
            System.exit(1);
        }
        try (Connection c = Db.oeffnen(k)) {
            Einrichter e = Verbindung.einrichter();
            System.out.println("Einrichten " + k.kurz() + " aus " + Verbindung.skripte().getPath() + " · Stufe vorher " + e.stufe(c));
            long t0 = System.nanoTime();
            Einrichter.Ergebnis r = e.einrichten(c, Verbindung.skripte(), (t, a) -> { if (t.contains("TRIGGER") || a >= 1) System.out.printf("  %3.0f %% · %s%n", a * 100, t); });
            for (String z : r.zeilen()) System.out.println("  " + z);
            System.out.printf("%s · %d Anweisungen ausgeführt, %d schon vorhanden · %.1f s%n", r.kurz(), r.ausgefuehrt(), r.vorhanden(), (System.nanoTime() - t0) / 1e9);
            System.exit(r.ok() ? 0 : 1);
        }
    }
}
