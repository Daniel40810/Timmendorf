package com.dan.timmendorf.scene;

import com.dan.timmendorf.wiring.Clock;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Die Uhr der Szene mit Datum (aus Sylt und Brocken). Sie startet am 25. Juli 2026 um 19:30 Uhr MESZ [G]: ein
 * Sommerabend zur Travemünder Woche (Werkbuch, Anlass; Termine zu prüfen) – die Sonne steht im Westen hinter dem Ort,
 * Strand und Seebrücke liegen im warmen Licht, die Bucht davor wird schon blau. Zeitraffer bis 3600-fach.
 * <p>
 * Zeit: deutsche Zeit, MEZ (UTC+1) im Winter, MESZ (UTC+2) im Sommer. Die wahre Ortszeit an der Seebrücke (10,78° O)
 * liegt rund 17 Minuten hinter der MEZ.
 * <p>
 * Die Uhr ist zugleich die Schnittstelle {@link Clock}, über die alle Packages Zeit bekommen.
 */
public final class SceneClock implements Clock {
    public static final double MAX_LAPSE = 3600;
    /** Deutsche Zeit mit Sommerzeit. */
    public static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    /** Start der Szene: 25. Juli 2026, 19:30 Uhr MESZ [G]. */
    public static final LocalDate START_DATE = LocalDate.of(2026, 7, 25);
    public static final double START_HOUR = 19.5;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d.M.u", Locale.GERMANY),
            TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.GERMANY);

    /** Szenenzeit in ms seit 1970 (UTC), als double, damit kleine Schritte nicht verloren gehen. */
    private volatile double epochMs;
    /** Zeitraffer: Szenensekunden je echter Sekunde (0 = Uhr steht). */
    public volatile double lapse = 1;
    /** Ort für Sonne, Mond und Sterne. */
    public volatile double lat = Site.LAT, lon = Site.LON;
    /** Laufzeit der Szene in s (für Wellen und Animation; läuft auch bei stehender Uhr) und letzter Schritt. */
    private double t, dt;

    public SceneClock() { this(START_DATE, START_HOUR); }

    public SceneClock(LocalDate date, double hour) { set(date, hour); }

    /** Datum und Uhrzeit (Stunden, deutsche Zeit) setzen. */
    public synchronized void set(LocalDate date, double hour) {
        hour = Math.max(0, Math.min(24 - 1e-9, hour));
        int sec = (int) Math.floor(hour * 3600);
        ZonedDateTime z = ZonedDateTime.of(date, LocalTime.ofSecondOfDay(sec), ZONE);
        epochMs = z.toInstant().toEpochMilli() + (hour * 3600 - sec) * 1000;
    }

    public synchronized void setHour(double hour) { set(date(), hour); }

    /** Läuft um dt echte Sekunden weiter. */
    public synchronized void step(double realDt) {
        dt = realDt;
        t += realDt;
        epochMs += realDt * Math.max(0, Math.min(MAX_LAPSE, lapse)) * 1000;
    }

    public synchronized void shiftDays(int days) { epochMs += days * 86_400_000.0; }

    public ZonedDateTime local() { return Instant.ofEpochMilli((long) Math.floor(epochMs)).atZone(ZONE); }
    public LocalDate date() { return local().toLocalDate(); }
    public double hour() {
        ZonedDateTime z = local();
        return z.getHour() + z.getMinute() / 60.0 + (z.getSecond() + (epochMs - Math.floor(epochMs / 1000) * 1000) / 1000) / 3600.0;
    }

    /** Sonnenhöhe und -azimut in Grad am Ursprung (Landende der Seebrücke). */
    public double[] sun() { return Sun.position(epochMs, lat, lon); }

    /** Szenenzeit direkt setzen (ms seit 1970, UTC). */
    public synchronized void setEpochMillis(double ms) { epochMs = ms; }

    /** „21.6.2026 11:00 MESZ“ */
    public String label() { return dateLabel() + " " + timeLabel(); }
    public String dateLabel() { return DATE.format(date()); }
    public String timeLabel() { return TIME.format(local()) + " " + zoneLabel(); }

    /** „MESZ“ oder „MEZ“. */
    public String zoneLabel() { return ZONE.getRules().isDaylightSavings(Instant.ofEpochMilli((long) epochMs)) ? "MESZ" : "MEZ"; }

    // ------------------------------------------------------------------ Clock
    @Override public double t() { return t; }
    @Override public double dt() { return dt; }
    @Override public double epochMillis() { return epochMs; }
    @Override public double latDeg() { return lat; }
    @Override public double lonDeg() { return lon; }
}
