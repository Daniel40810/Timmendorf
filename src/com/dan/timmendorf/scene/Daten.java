package com.dan.timmendorf.scene;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Was die App an aufbereiteten Daten hat (Werkbuch, Abschnitt 5, F5 A). Sie liest nur {@code daten/} im Arbeitsverzeichnis
 * ({@code -Dtimmendorf.daten=…} setzt es anders), nie die Rohdaten: die liest einmal {@code ant aufbereitung}
 * ({@code com.dan.timmendorf.tools.Aufbereitung}) und schreibt dabei {@code daten/INHALT.txt}. Beim Start prüft die App nur, ob
 * jede Datei aus INHALT.txt da ist und ihre Größe stimmt; die Prüfsummen rechnet {@code ant pruefung} nach.
 */
public final class Daten {
    public static final String INHALT = "INHALT.txt";

    private Daten() { }

    public static File ordner() { return new File(System.getProperty("timmendorf.daten", "daten")); }

    /** Zustand von daten/ in einem Satz; {@link #fehlt()} sagt, was fehlt. */
    public static String zustand() {
        File inh = new File(ordner(), INHALT);
        if (!inh.isFile()) return "Keine aufbereiteten Daten (" + inh.getPath() + " fehlt): die App zeigt die Bühne von Phase 1. "
                + "Einmal ant aufbereitung laufen lassen (liest die Rohdaten aus dem Pfad in daten/rohdaten.properties, Vorgabe C:\\Daten\\Timmendorf).";
        List<String> f = fehlt();
        int[] n = new int[1];
        long[] sum = new long[1];
        String stand = "";
        try {
            for (String l : Files.readAllLines(inh.toPath())) {
                if (l.startsWith("# Timmendorf")) stand = l.replaceAll(".* am ", "").replace('T', ' ');
                if (l.isBlank() || l.startsWith("#")) continue;
                n[0]++;
                sum[0] += Long.parseLong(l.split("  ", 3)[1]);
            }
        } catch (IOException | RuntimeException e) { return "daten/INHALT.txt ist nicht lesbar: " + e.getMessage(); }
        if (!f.isEmpty()) return "daten/ unvollständig: " + String.join(", ", f.subList(0, Math.min(4, f.size()))) + (f.size() > 4 ? " …" : "")
                + " – ant aufbereitung neu laufen lassen.";
        return String.format(Locale.GERMAN, "daten/ vollständig: %d Dateien, %.0f MB, aufbereitet %s.", n[0], sum[0] / 1e6, stand);
    }

    /** Dateien aus INHALT.txt, die fehlen oder deren Größe nicht stimmt. */
    public static List<String> fehlt() {
        List<String> f = new ArrayList<>();
        File inh = new File(ordner(), INHALT);
        if (!inh.isFile()) { f.add(INHALT); return f; }
        try {
            for (String l : Files.readAllLines(inh.toPath())) {
                if (l.isBlank() || l.startsWith("#")) continue;
                String[] a = l.split("  ", 3);
                File d = new File(ordner(), a[2]);
                if (!d.isFile()) f.add(a[2] + " fehlt");
                else if (d.length() != Long.parseLong(a[1])) f.add(a[2] + " hat die falsche Größe");
            }
        } catch (IOException | RuntimeException e) { f.add(INHALT + ": " + e.getMessage()); }
        return f;
    }
}
