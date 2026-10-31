package com.dan.timmendorf.scene;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;

/**
 * Seit Phase 14 (F27 A): die Ostseesturmflut vom 19. bis 21. Oktober 2023 („Wolfgang“) am Pegel Travemünde, als Fahrplan
 * für Wasserstand, Wind und Seegang der Szene.
 * <p>
 * Belegt [Nöthel, BAW, „Ablauf der Ostseesturmflut vom Oktober 2023“, Die Küste 94; BSH, „Schwere Sturmflut vom 20.
 * Oktober 2023“]: Scheitel in Travemünde <b>1,77 m NHN</b> am späten Abend des 20. Oktober; langsamer Anstieg über rund drei
 * Tage, schnellerer Fall; am benachbarten Pegel Lübeck-Bauhof 26 h über 1,25 m, 15 h über 1,50 m, 3 h über 1,75 m; die
 * Warngrenze von 1 m über Mittelwasser schon am 19. Oktober überschritten; Füllung der Ostsee 35–45 cm. Wind: nach dem Dreh
 * auf Ost bis etwa zum Mittag des 21. Oktober, an der Marienleuchte am Abend des 20. knapp 30 m/s, an Land (Fehmarn,
 * Kiel-Holtenau) um 18 m/s. Nach dem Sturm schwingt die westliche Ostsee nach (Seiche, an der Marienleuchte rund 45 cm).
 * <p>
 * Gestaltet [G]: die Stundenwerte zwischen diesen Ankern (Glättung, Scheitel um 23 Uhr MESZ, das Nachschwingen mit 26 h
 * Periode und 0,25 m Anfangshöhe), der Wind an der Bucht (bis 22 m/s aus 85°), Bedeckung und Schauer. Die Zahlen werden
 * gegen die Anker geprüft ({@code Pruefung}, Teil „Sturmflut“).
 */
public final class Sturmflut {
    public static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    /** Beginn des Fahrplans (Stunde 0) und seine Länge (h). */
    public static final LocalDateTime BEGINN = LocalDateTime.of(2023, 10, 18, 0, 0);
    public static final int STUNDEN = 132;   // bis 23.10. 12:00
    /** Belegter Scheitel Travemünde (m NHN) und seine Stunde im Fahrplan (20.10. 23:00 MESZ) [Zeit G]. */
    public static final double SCHEITEL = 1.77, SCHEITEL_H = 71;

    /** Pegel Travemünde (m NHN) zu jeder vollen Stunde ab {@link #BEGINN}. */
    static final double[] PEGEL = new double[STUNDEN + 1];
    /** Wind an der Bucht (m/s) und Richtung, aus der er kommt (Grad), je Stunde. */
    static final double[] WIND = new double[STUNDEN + 1], RICHTUNG = new double[STUNDEN + 1];

    static {
        // Anker (Stunde, m NHN): Füllung 0,4 m; über 1,0 m am 19. 14 Uhr; 1,25 m am 20. 6 Uhr; 1,50 m am 20. 15 Uhr;
        // 1,75 m 21:30 bis 0:30; Scheitel 1,77 m um 23 Uhr; unter 1,50 m am 21. 6 Uhr, unter 1,25 m am 21. 8 Uhr; Fall bis 0,1 m
        // am 22. mittags, dann Nachschwingen
        double[][] anker = {{0, 0.38}, {12, 0.48}, {24, 0.68}, {38, 1.00}, {48, 1.13}, {54, 1.25}, {63, 1.50}, {68, 1.68}, {69.5, 1.75},
                {71, 1.77}, {72.5, 1.75}, {75, 1.66}, {78, 1.50}, {80, 1.25}, {84, 0.95}, {90, 0.62}, {96, 0.38}, {108, 0.10}};
        for (int h = 0; h <= STUNDEN; h++) {
            double v;
            if (h <= 108) v = interp(anker, h);
            else {
                double t = h - 108;   // Nachschwingen der westlichen Ostsee [G]: 26 h, 0,25 m, abklingend; stetig an den Fall angesetzt
                v = 0.10 + 0.05 * (1 - Math.exp(-t / 6)) + 0.25 * Math.exp(-t / 40) * Math.sin(2 * Math.PI * t / 26);
            }
            PEGEL[h] = v;
            // Wind: am 18. Südwest, Dreh auf Ost am 19. früh, Höchstwert am Abend des 20., ab dem 21. mittags nachlassend auf Nordost
            double w, d;
            if (h < 20) { w = 7 + 0.2 * h; d = 220 - 6 * h; }
            else if (h < 30) { w = 11 + 0.4 * (h - 20); d = 100 - (h - 20) * 1.5; }
            else if (h < 68) { w = 15 + 7 * (h - 30) / 38.0; d = 85; }
            else if (h < 84) { w = 22 - 3 * (h - 68) / 16.0; d = 85; }
            else if (h < 96) { w = 19 - 9 * (h - 84) / 12.0; d = 85 - 35 * (h - 84) / 12.0; }
            else { w = 10 - 4 * Math.min(1, (h - 96) / 24.0); d = 50; }
            WIND[h] = w;
            RICHTUNG[h] = d;
        }
    }

    static double interp(double[][] a, double x) {
        for (int i = 0; i + 1 < a.length; i++) {
            if (x <= a[i + 1][0]) {
                double t = (x - a[i][0]) / (a[i + 1][0] - a[i][0]);
                t = t * t * (3 - 2 * t);
                return a[i][1] + (a[i + 1][1] - a[i][1]) * t;
            }
        }
        return a[a.length - 1][1];
    }

    /** Stunde im Fahrplan für eine Epoche (ms), NaN außerhalb. */
    public static double stunde(double epochMs) {
        double t0 = BEGINN.atZone(ZONE).toInstant().toEpochMilli();
        double h = (epochMs - t0) / 3_600_000.0;
        return h < 0 || h > STUNDEN ? Double.NaN : h;
    }

    /** Stunde im Fahrplan ohne Grenzen nach oben (nach der Sturmflut STUNDEN), vor dem Beginn negativ. */
    public static double stundeRoh(double epochMs) {
        double t0 = BEGINN.atZone(ZONE).toInstant().toEpochMilli();
        return Math.min(STUNDEN, (epochMs - t0) / 3_600_000.0);
    }

    private static double lerp(double[] a, double h) {
        int i = (int) Math.floor(h);
        if (i >= STUNDEN) return a[STUNDEN];
        double t = h - i;
        return a[i] * (1 - t) + a[i + 1] * t;
    }

    /** Pegel (m NHN) zur Stunde h im Fahrplan. */
    public static double pegel(double h) { return lerp(PEGEL, Math.max(0, Math.min(STUNDEN, h))); }
    public static double wind(double h) { return lerp(WIND, Math.max(0, Math.min(STUNDEN, h))); }
    public static double richtung(double h) { return lerp(RICHTUNG, Math.max(0, Math.min(STUNDEN, h))); }

    /** Stunden über einer Schwelle (m NHN), in Schritten von 6 min. */
    public static double stundenUeber(double schwelle) {
        double n = 0;
        for (double h = 0; h <= STUNDEN; h += 0.1) if (pegel(h) > schwelle) n += 0.1;
        return n;
    }

    /** Die Uhrzeit (Ortszeit) zur Stunde h. */
    public static LocalDateTime zeit(double h) { return BEGINN.plusSeconds(Math.round(h * 3600)); }

    /** „20.10.2023 23:00 · Pegel Travemünde 1,77 m NHN · Ost 22 m/s“ */
    public static String zeile(double h) {
        if (Double.isNaN(h)) return "Sturmflut 2023: außerhalb (18.10. bis 23.10.2023)";
        LocalDateTime z = zeit(h);
        return String.format(Locale.GERMAN, "Sturmflut 2023: %td.%tm. %tH:%tM · Pegel Travemünde %.2f m NHN (Scheitel belegt 1,77 m) · Wind %.0f m/s aus %03.0f°",
                z, z, z, z, pegel(h), wind(h), richtung(h));
    }
}
