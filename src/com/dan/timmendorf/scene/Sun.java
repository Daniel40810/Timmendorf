package com.dan.timmendorf.scene;

/**
 * Sonnenstand nach Datum, Uhrzeit und Ort (vereinfachte Formeln des Astronomical Almanac, genau auf rund 0,01°).
 * Phase 1 braucht ihn für den Himmelsverlauf und die Belichtung (aus Haithabu übernommen); ab Phase 4 rechnet
 * {@code com.dan.sky} Sonne und Mond (Phase 4). An der Seebrücke (54,00° N, 10,78° O) steht die Sonne rund 17 Minuten
 * nach 12 Uhr MEZ im Süden (mit der Zeitgleichung ± 16 min); von Mai bis August geht sie über der Bucht auf.
 */
public final class Sun {
    private Sun() { }

    /** Höhe und Azimut in Grad (Azimut von Nord im Uhrzeigersinn) für die Zeit in ms seit 1970 (UTC). */
    public static double[] position(double epochMs, double latDeg, double lonDeg) {
        double jd = epochMs / 86_400_000.0 + 2440587.5;
        double n = jd - 2451545.0;
        double L = norm(280.460 + 0.9856474 * n);
        double g = Math.toRadians(norm(357.528 + 0.9856003 * n));
        double lambda = Math.toRadians(L + 1.915 * Math.sin(g) + 0.020 * Math.sin(2 * g));
        double eps = Math.toRadians(23.439 - 0.0000004 * n);
        double ra = Math.atan2(Math.cos(eps) * Math.sin(lambda), Math.cos(lambda));
        double dec = Math.asin(Math.sin(eps) * Math.sin(lambda));
        double gmst = norm(280.46061837 + 360.98564736629 * n);
        double ha = Math.toRadians(norm(gmst + lonDeg - Math.toDegrees(ra)));
        double phi = Math.toRadians(latDeg);
        double el = Math.asin(Math.sin(phi) * Math.sin(dec) + Math.cos(phi) * Math.cos(dec) * Math.cos(ha));
        double az = Math.atan2(-Math.sin(ha), Math.tan(dec) * Math.cos(phi) - Math.sin(phi) * Math.cos(ha));
        return new double[]{Math.toDegrees(el), norm(Math.toDegrees(az))};
    }

    /** Richtung zur Sonne als Einheitsvektor der Szene (x Ost, y oben, z Süd). */
    public static double[] direction(double elevDeg, double azDeg) {
        double e = Math.toRadians(elevDeg), a = Math.toRadians(azDeg);
        return new double[]{Math.cos(e) * Math.sin(a), Math.sin(e), -Math.cos(e) * Math.cos(a)};
    }

    /**
     * Sonnenauf- oder -untergang (Oberrand mit Brechung, −0,833°) am Tag, der um dayStartMs (Mitternacht Ortszeit)
     * beginnt; Ergebnis in ms seit 1970, NaN wenn es keinen gibt. rise = true für den Aufgang.
     */
    public static double crossing(double dayStartMs, double latDeg, double lonDeg, boolean rise) {
        double step = 60_000, prev = position(dayStartMs, latDeg, lonDeg)[0] + 0.833;
        for (double t = dayStartMs + step; t <= dayStartMs + 86_400_000; t += step) {
            double h = position(t, latDeg, lonDeg)[0] + 0.833;
            if (rise ? prev < 0 && h >= 0 : prev > 0 && h <= 0) {
                double lo = t - step, hi = t;
                for (int i = 0; i < 30; i++) {
                    double mid = (lo + hi) / 2, hm = position(mid, latDeg, lonDeg)[0] + 0.833;
                    if (rise == (hm < 0)) lo = mid; else hi = mid;
                }
                return (lo + hi) / 2;
            }
            prev = h;
        }
        return Double.NaN;
    }

    /** Stützstellen der Himmelshelligkeit: Sonnenhöhe in Grad und log10 der Helligkeit (Tag = 1). Gleiche Tabelle wie im Shader. */
    static final double[] B_ELEV = {-18, -12, -6, 0, 10, 30}, B_LOG = {-4.3, -3.5, -2.3, -1.0, -0.3, 0};

    /**
     * Wie hell der Himmel ist, als Faktor aus der Sonnenhöhe: Tag 1, Sonnenuntergang 0,1, Ende der bürgerlichen
     * Dämmerung 0,005, der nautischen 0,0003, Nacht ohne Mond 0,00005. Daraus rechnet die App die Belichtung, bis
     * Phase 3 die echte Streuung und eine Belichtungsmessung liefert.
     */
    public static double skyBrightness(double elevDeg) {
        if (elevDeg <= B_ELEV[0]) return Math.pow(10, B_LOG[0]);
        for (int i = 0; i < B_ELEV.length - 1; i++)
            if (elevDeg <= B_ELEV[i + 1]) return Math.pow(10, B_LOG[i] + (B_LOG[i + 1] - B_LOG[i]) * (elevDeg - B_ELEV[i]) / (B_ELEV[i + 1] - B_ELEV[i]));
        return 1;
    }

    static double smooth(double a, double b, double x) {
        double t = Math.max(0, Math.min(1, (x - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }

    static double norm(double deg) {
        deg %= 360;
        return deg < 0 ? deg + 360 : deg;
    }
}
