package com.dan.timmendorf.scene;

/**
 * Die tatsächliche Nutzung aus ALKIS (Phase 2, Schritt {@code alkis}) als Codes eines Rasters: {@code daten/alkis/nutzung_kern.u8}
 * (je Meter im Kern) und {@code nutzung_haupt.u8} (je 10-m-Zelle des Hauptraster, Wert in der Zellmitte). Die Codes sind
 * eigene, kurze Nummern; welche ALKIS-Objektart und welches Merkmal dahinterstehen, sagt {@link #NAME}. 0 heißt: keine
 * Fläche (außerhalb der Gemarkungen, also die Ostsee und Mecklenburg).
 */
public final class Nutzung {
    private Nutzung() { }

    public static final byte KEINE = 0, MEER = 1, HAFENBECKEN = 2, FLIESSGEWAESSER = 3, STEHENDES_GEWAESSER = 4, WOHNBAU = 5, GEWERBE = 6,
            GEMISCHT = 7, FUNKTIONAL = 8, ERHOLUNG = 9, FRIEDHOF = 10, STRASSE = 11, WEG = 12, PLATZ = 13, BAHN = 14, SCHIFFSVERKEHR = 15,
            ACKER = 16, GRUENLAND = 17, GARTEN = 18, LAUBWALD = 19, NADELWALD = 20, MISCHWALD = 21, GEHOELZ = 22, HEIDE = 23, MOOR = 24,
            SUMPF = 25, UNLAND = 26, TAGEBAU = 27, SONSTIGE = 28;

    public static final String[] NAME = {"keine Fläche", "Meer", "Hafenbecken", "Fließgewässer", "stehendes Gewässer", "Wohnbaufläche",
            "Industrie und Gewerbe", "gemischte Nutzung", "besondere funktionale Prägung", "Sport, Freizeit, Erholung", "Friedhof",
            "Straßenverkehr", "Weg", "Platz", "Bahnverkehr", "Schiffsverkehr", "Ackerland", "Grünland", "Garten und sonstige Landwirtschaft",
            "Laubwald", "Nadelwald", "Mischwald", "Gehölz", "Heide", "Moor", "Sumpf", "Unland, vegetationslos (Strand)", "Tagebau, Grube",
            "sonstige Fläche"};

    /** Farbe je Code für Prüfbilder (sRGB). */
    public static final int[] FARBE = {0x10161c, 0x2f5f8f, 0x3a6a9a, 0x4a86b8, 0x5a9ac8, 0xc9907a, 0x9a8aa0, 0xc0a08a, 0xd8b070, 0x8fcf6f,
            0x6f8f6f, 0x8a8a8a, 0xb8b0a0, 0xa0a0a0, 0x705050, 0x506a8a, 0xd8cc80, 0x9fcf6f, 0xb8d890, 0x3f7f3f, 0x2f5f3f, 0x3f6f3f,
            0x5f8f4f, 0xa08aa0, 0x7a6a5a, 0x6f8f7f, 0xeadfb8, 0xb0a080, 0x707070};

    /** Ob der Code Wasser ist. */
    public static boolean wasser(int c) { return c >= MEER && c <= STEHENDES_GEWAESSER; }

    /** Ob der Code Wald ist. */
    public static boolean wald(int c) { return c >= LAUBWALD && c <= MISCHWALD; }

    /** Ob der Code bebaut ist (Siedlung und Verkehr). */
    public static boolean siedlung(int c) { return c >= WOHNBAU && c <= BAHN; }

    /**
     * Code zu einer ALKIS-Objektart und ihren Merkmalen (Werte als Text, null wenn keins). Landwirtschaft nach
     * {@code vegetationsmerkmal} (1010 Ackerland, 1020 Grünland, sonst Garten und Sonstiges), Wald nach
     * {@code vegetationsmerkmal} (1100 Laubholz, 1200 Nadelholz, sonst Mischwald). Gibt -1 für Objekte, die keine Fläche der
     * tatsächlichen Nutzung sind.
     */
    public static int code(String objektart, String vegetationsmerkmal) {
        return switch (objektart) {
            case "AX_Meer" -> MEER;
            case "AX_Hafenbecken" -> HAFENBECKEN;
            case "AX_Fliessgewaesser", "AX_Gewaesserachse" -> FLIESSGEWAESSER;
            case "AX_StehendesGewaesser" -> STEHENDES_GEWAESSER;
            case "AX_Wohnbauflaeche" -> WOHNBAU;
            case "AX_IndustrieUndGewerbeflaeche", "AX_Halde", "AX_Bergbaubetrieb" -> GEWERBE;
            case "AX_FlaecheGemischterNutzung" -> GEMISCHT;
            case "AX_FlaecheBesondererFunktionalerPraegung" -> FUNKTIONAL;
            case "AX_SportFreizeitUndErholungsflaeche" -> ERHOLUNG;
            case "AX_Friedhof" -> FRIEDHOF;
            case "AX_Strassenverkehr" -> STRASSE;
            case "AX_Weg" -> WEG;
            case "AX_Platz" -> PLATZ;
            case "AX_Bahnverkehr" -> BAHN;
            case "AX_Schiffsverkehr", "AX_Flugverkehr" -> SCHIFFSVERKEHR;
            case "AX_Landwirtschaft" -> "1010".equals(vegetationsmerkmal) ? ACKER : "1020".equals(vegetationsmerkmal) ? GRUENLAND : GARTEN;
            case "AX_Wald" -> "1100".equals(vegetationsmerkmal) ? LAUBWALD : "1200".equals(vegetationsmerkmal) ? NADELWALD : MISCHWALD;
            case "AX_Gehoelz" -> GEHOELZ;
            case "AX_Heide" -> HEIDE;
            case "AX_Moor" -> MOOR;
            case "AX_Sumpf" -> SUMPF;
            case "AX_UnlandVegetationsloseFlaeche" -> UNLAND;
            case "AX_TagebauGrubeSteinbruch" -> TAGEBAU;
            default -> -1;
        };
    }

    /** Die Art des Grunds ({@link Terrain#ART}) zu einem Code, für die Farben bis Phase 4 (vor dem Luftbild). */
    public static byte art(int c) {
        if (wasser(c)) return Terrain.SEE;
        if (wald(c)) return Terrain.WALD;
        return switch (c) {
            case WOHNBAU, GEWERBE, GEMISCHT, FUNKTIONAL, FRIEDHOF -> Terrain.SIEDLUNG;
            case STRASSE, WEG, PLATZ, BAHN, SCHIFFSVERKEHR -> Terrain.WEG;
            case ERHOLUNG, GRUENLAND, GARTEN -> Terrain.WIESE;
            case ACKER -> Terrain.ACKER;
            case GEHOELZ, HEIDE -> Terrain.GEBUESCH;
            case MOOR, SUMPF -> Terrain.SCHILF;
            case UNLAND -> Terrain.STRAND;
            case TAGEBAU -> Terrain.KLIFF;
            default -> Terrain.KEINE;
        };
    }
}
