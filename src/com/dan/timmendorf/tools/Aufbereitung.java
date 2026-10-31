package com.dan.timmendorf.tools;

import com.dan.timmendorf.scene.Terrain;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Die Aufbereitung von Phase 2 ({@code ant aufbereitung}, aus Sylt): liest die Rohdaten einmal und schreibt alles, was die
 * App braucht, nach {@code daten/} (Werkbuch, Abschnitt 5). Schritte in dieser Folge, einzeln mit
 * {@code -Dschritt=gelaende+bewuchs} (Plus statt Komma: die Windows-Eingabe trennt Argumente am Komma):
 * <ol>
 * <li>{@code alkis}: ALKIS zu Nutzung je Meter und je 10 m und zu Objekten ({@link AlkisImport});</li>
 * <li>{@code gelaende}: DGM1, ALKIS, GEBCO und BSH zum Gelände ({@link GelaendeImport});</li>
 * <li>{@code bewuchs}: bDOM zur Höhe über dem Gelände ({@link BewuchsImport});</li>
 * <li>{@code luftbild}: DOP20 zu Farben, Klassen und Arten ({@link LuftbildImport});</li>
 * <li>{@code bauten}: LOD2 zu Baukörpern ({@link BautenImport});</li>
 * <li>{@code netz}: Overpass zu den Netzen Fuß, Straße, Bus und See, Datei-Abbild des Oracle-Netzes ({@link NetzImport}, Phase 3);</li>
 * <li>{@code bauwerke}: Overpass zu Seebrücken, Stegen, DLRG und Leuchtfeuern ({@link BauwerkeImport}, Phase 9);</li>
 * <li>{@code inhalt}: {@code daten/INHALT.txt} mit Prüfsummen ({@link Inhalt}).</li>
 * </ol>
 * Protokoll in {@code daten/aufbereitung.log}, Prüfbilder in {@code docs/status/}. Nur die Netze neu:
 * {@code ant aufbereitung -Dschritt=netz+inhalt}.
 */
public final class Aufbereitung {
    public static final List<String> SCHRITTE = List.of("alkis", "gelaende", "bewuchs", "luftbild", "bauten", "netz", "bauwerke", "inhalt");

    private Aufbereitung() { }

    public static void main(String[] a) throws Exception {
        String sel = a.length > 0 ? String.join(",", a) : System.getProperty("schritt", "alle");
        if (sel.isBlank() || sel.startsWith("$")) sel = "alle";
        List<String> wahl = sel.equals("alle") ? SCHRITTE : Arrays.asList(sel.toLowerCase(Locale.ROOT).split("[,;+ ]+"));
        for (String s : wahl) if (!SCHRITTE.contains(s)) throw new IllegalArgumentException("Schritt " + s + " gibt es nicht (" + String.join(", ", SCHRITTE) + ")");
        File daten = new File("daten");
        daten.mkdirs();
        long t0 = System.nanoTime();
        try (PrintWriter logf = new PrintWriter(new OutputStreamWriter(new FileOutputStream(new File(daten, "aufbereitung.log"), true), StandardCharsets.UTF_8), true)) {
            Consumer<String> log = s -> { System.out.println(s); logf.println(s); };
            log.accept("=== Timmendorf Aufbereitung " + java.time.LocalDateTime.now().withNano(0) + " · Schritte " + String.join(", ", wahl)
                    + " · Java " + System.getProperty("java.version") + ", " + Runtime.getRuntime().maxMemory() / (1 << 20) + " MB");
            Rohdaten r = Rohdaten.laden();
            log.accept(r.toString());
            List<String> fehlt = r.fehlt(wahl);
            if (!fehlt.isEmpty() && wahl.stream().anyMatch(s -> !s.equals("inhalt"))) {
                fehlt.forEach(f -> log.accept("FEHLT: " + f));
                throw new IOException("Rohdaten fehlen; Pfad in " + Rohdaten.DATEI + " prüfen");
            }
            for (String s : SCHRITTE) {
                if (!wahl.contains(s)) continue;
                long t1 = System.nanoTime();
                log.accept("--- " + s);
                switch (s) {
                    case "alkis" -> AlkisImport.importieren(r, daten, log);
                    case "gelaende" -> GelaendeImport.bild(GelaendeImport.importieren(r, Terrain.defaultDir(), log), GelaendeImport.BILD);
                    case "bewuchs" -> BewuchsImport.importieren(r, daten, log);
                    case "luftbild" -> LuftbildImport.importieren(r, daten, log);
                    case "bauten" -> BautenImport.importieren(r, daten, log);
                    case "netz" -> NetzImport.importieren(r, daten, log);
                    case "bauwerke" -> BauwerkeImport.importieren(r, daten, log);
                    case "inhalt" -> Inhalt.schreiben(daten, log);
                    default -> { }
                }
                log.accept(String.format(Locale.GERMAN, "    %s fertig in %.1f s", s, (System.nanoTime() - t1) / 1e9));
            }
            log.accept(String.format(Locale.GERMAN, "=== fertig in %.1f s", (System.nanoTime() - t0) / 1e9));
        }
    }
}
