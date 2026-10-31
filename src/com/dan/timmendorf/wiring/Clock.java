package com.dan.timmendorf.wiring;

/**
 * Zeit der Szene. Gibt: die App ({@code SceneClock}). Nimmt: alle Packages, vor allem {@code sky} (Sonnen- und Mondaufgang
 * über der Bucht), {@code tide} (Windstau, Phase 13), {@code season} (Strandkörbe nur im Sommer, Buchen am Steilufer),
 * {@code firework} (Zündplan) und {@code sail} (Regattastart). Die Laufzeit {@code t} läuft
 * auch bei stehender Uhr weiter (Wellen, Strähnen), die Uhrzeit {@code epochMillis} folgt dem Zeitraffer.
 */
public interface Clock {
    /** Laufzeit der Szene in s und letzter Schritt in s. */
    double t();
    double dt();

    /** Szenenzeit in ms seit 1970 (UTC). */
    double epochMillis();

    /** Ort in Grad (Landende der Maritim Seebrücke). */
    double latDeg();
    double lonDeg();
}
