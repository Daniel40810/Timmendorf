package com.dan.timmendorf.gl;

import com.dan.cloud.Clouds;
import com.dan.ocean.Ocean;

import java.util.Locale;

/**
 * Qualitätsstufen der App (aus Brocken, entschlackt). Phase 1: der Anteil der Bildgröße, in dem gerechnet wird, das
 * Zeitbudget und Sichtweite und Punktabstand des Geländes, im Kern feiner (F1 A: DGM1 zu 1 m). Jede weitere Phase
 * hängt die Stufe ihres Packages an (3 {@code ocean}, {@code sky}, {@code cloud}; 5–7 {@code surf}; 8–9 {@code dune} …);
 * die Packages haben ihre eigene Kopie dieser Aufzählung (Paketvertrag Punkt 7) und folgen der App eins zu eins über
 * {@link #ordinal()}.
 * <p>
 * Ziel (F28 A, Werkbuch Abschnitt 13): „Mittel“ hält 60 Bilder/s in Full HD auf der Radeon 680M mit 2 GB, nie unter
 * 30; „Hoch“ und „Ultra“ sind für Standbilder und Kino.
 */
public enum Quality {
    NIEDRIG("Niedrig", 0.67, 2.2, 3, 2, Ocean.Quality.LOW, Clouds.Quality.FLAT),
    MITTEL("Mittel", 0.85, 2.6, 3, 1, Ocean.Quality.MEDIUM, Clouds.Quality.MEDIUM),
    HOCH("Hoch", 1.0, 3.0, 2, 0, Ocean.Quality.HIGH, Clouds.Quality.HIGH),
    ULTRA("Ultra", 1.0, 3.4, 2, 0, Ocean.Quality.ULTRA, Clouds.Quality.ULTRA);

    public final String label;
    /** Anteil der Bildgröße, in dem gerechnet wird. */
    public final double renderScale;
    /** Sichtweite jeder Geländestufe in ihren Knotengrößen (CDLOD). */
    public final double terrainRange;
    /** Feinste Stufe außerhalb des Kerns und in ihm (0 = 1 m, 1 = 2 m, 2 = 4 m, 3 = 8 m Punktabstand). */
    public final int terrainFine, terrainKern;
    /** Seit Phase 4: die See ({@code ocean}) und die Wolken ({@code cloud}). */
    public final Ocean.Quality ocean;
    public final Clouds.Quality clouds;

    Quality(String label, double renderScale, double terrainRange, int terrainFine, int terrainKern, Ocean.Quality ocean, Clouds.Quality clouds) {
        this.ocean = ocean;
        this.clouds = clouds;
        this.label = label;
        this.renderScale = renderScale;
        this.terrainRange = terrainRange;
        this.terrainFine = terrainFine;
        this.terrainKern = terrainKern;
    }

    /**
     * Seit Phase 12: bis zu welchem Abstand vom Auge Gelände und Häuser das Licht der Leuchten rechnen (m); die Spiegelung im
     * Wasser bis zum Vierfachen. Wie viele Leuchten, folgt aus {@code com.dan.lamp.Lamps.Quality} (32 bis 256).
     */
    public final double lampRange = switch (ordinal()) { case 0 -> 400; case 1 -> 700; case 2 -> 1200; default -> 2000; };

    /** Punktabstand (m) einer Stufe: 1 m · 2^Stufe. */
    public static int spacing(int level) { return 1 << level; }

    /**
     * Bildzeit, die die Stufe höchstens verbrauchen darf (ms): „Mittel“ 16,5 ms (60 Bilder/s mit Rand), „Hoch“ das
     * Doppelte, „Niedrig“ die Hälfte.
     */
    public double budgetMs() {
        return switch (this) { case NIEDRIG -> 8.0; case MITTEL -> 16.5; case HOCH -> 33.0; case ULTRA -> 50.0; };
    }

    /** Was die Stufe einstellt, als eine Zeile (Bedienfeld, README). */
    public String summary() {
        return String.format(Locale.GERMAN, "%s: %d %% der Auflösung, Budget %.1f ms, Gelände %.1f Knoten, außen %d m, im Kern %d m, See %d² × %d, Wolken %s", label,
                Math.round(renderScale * 100), budgetMs(), terrainRange, spacing(terrainFine), spacing(terrainKern), ocean.n, ocean.cascades, clouds);
    }

    @Override public String toString() { return label; }
}
