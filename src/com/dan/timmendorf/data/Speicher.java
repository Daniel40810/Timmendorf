package com.dan.timmendorf.data;

import com.dan.store.Ablage;

import java.util.List;

/**
 * Wohin die App speichert (F37 A): in das Schema TIMMENDORF ({@link DbSpeicher}) oder, ohne Datenbank, in Dateien
 * unter {@code %APPDATA%\Timmendorf} ({@link DateiSpeicher}). Beide können dasselbe; die Oberfläche kennt nur diese
 * Schnittstelle. Sitzung und Protokoll kommen aus {@link Ablage} (com.dan.store). Mitgelieferte Einträge werden nie
 * überschrieben, sondern abgelehnt.
 */
public interface Speicher extends Ablage {

    /** Schlüssel der Werte, die eine {@link Ablage.Sicherung} über die Sitzung mitbringt (Spalten von TD_SITZUNG). */
    String REGIEBUCH = "regiebuch", REGIE_SEKUNDE = "regieSekunde", SZENE_MS = "szeneMs", ZEITRAFFER = "zeitraffer", LAGE = "lage", PEGEL = "pegel",
            WIND = "wind", WIND_AUS = "windAus", STURMFLUT = "sturmflut", KAMERA_X = "kameraX", KAMERA_Y = "kameraY", KAMERA_Z = "kameraZ";

    Daten.Stand stand() throws Fehler;

    // ------------------------------------------------------------------ Quellen und Befunde
    List<Daten.Befund> befunde() throws Fehler;

    // ------------------------------------------------------------------ Regie (F41, F43)
    List<Daten.Regiebuch> regiebuecher() throws Fehler;
    void speichern(Daten.Regiebuch r) throws Fehler;
    void loeschenRegiebuch(String name) throws Fehler;
    void regielauf(Daten.Regielauf l, Long sitzung) throws Fehler;
    List<Daten.Regielauf> regielaeufe(int n) throws Fehler;

    /** Ansichten ohne Zustand und Vorschau (für Listen): feste, Blicke, dann gemerkte (neueste zuerst). */
    List<Daten.Ansicht> ansichten() throws Fehler;
    /** Eine gemerkte Ansicht ganz, mit Zustand und Vorschau. */
    Daten.Ansicht ansicht(long id) throws Fehler;
    /** Speichert eine neue gemerkte Ansicht und gibt ihre Nummer zurück. */
    long speichern(Daten.Ansicht a, Long sitzung) throws Fehler;
    void loeschenAnsicht(long id) throws Fehler;

    List<Daten.Fahrt> fahrten() throws Fehler;

    // ------------------------------------------------------------------ Packages (F42)
    /** Presets eines Package (paket = „com.dan.ocean“, klasse = „Ocean“); null für alle. */
    List<Daten.PaketPreset> presets(String paket, String klasse) throws Fehler;
    void speichern(Daten.PaketPreset p) throws Fehler;
    void loeschenPreset(String paket, String klasse, String name) throws Fehler;

    // ------------------------------------------------------------------ Fachdaten und Ergebnisse (F44)
    List<Daten.BootKlasse> bootKlassen() throws Fehler;
    List<Daten.Boot> boote() throws Fehler;
    List<Daten.Regatta> regatten() throws Fehler;
    void ergebnisse(List<Daten.Ergebnis> e, Long sitzung) throws Fehler;
    List<Daten.Ergebnis> ergebnisse(int n) throws Fehler;
    List<Daten.Effekt> effekte() throws Fehler;
    List<Daten.Zuendplan> zuendplaene() throws Fehler;
    List<Daten.Sturmflut> sturmfluten() throws Fehler;
    void abbruch(Daten.Abbruch a, Long sitzung) throws Fehler;
    List<Daten.Abbruch> abbrueche(int n) throws Fehler;
    List<Daten.Kante> kanten() throws Fehler;
    List<Daten.Leuchtfeuer> leuchtfeuer() throws Fehler;
    List<Daten.Linie> linien() throws Fehler;

    // ------------------------------------------------------------------ Betrieb (F38, F47)
    /** Das Vorschaubild der laufenden Sitzung (PNG, alle 2 min mit dem Zustand). */
    void vorschau(long sitzung, byte[] png) throws Fehler;
    /** Die letzte Sitzung mit Zustand vor der laufenden (oder null), mit Vorschau. */
    Daten.Sitzung letzteSitzung(long ausser) throws Fehler;
    /** Die letzten n Sitzungen (ohne Zustand und Vorschau), neueste zuerst. */
    List<Daten.Sitzung> sitzungen(int n) throws Fehler;
    /** Die letzten n Zeilen des Protokolls zum Anzeigen. */
    List<String> letzteZeilen(int n) throws Fehler;
    /** Eine Messung oder ein Ereignis der Szene ins Protokoll (ART MESSUNG, FEUERWERK, FOTO, REGIE …). */
    void ereignis(String art, String quelle, String text, Double wert, Double wert2, Long sitzung) throws Fehler;
}
