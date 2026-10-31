package com.dan.timmendorf.scene;

import com.dan.dune.Dune;
import com.dan.dune.FluxField;
import com.dan.dune.Grains;
import com.dan.wind.WindField;
import com.dan.wind.WindSolution;

import java.util.Locale;
import java.util.function.DoubleBinaryOperator;

/**
 * Seit Phase 20: der Flugsand am Strand wie in Sylt (dort seit Phase 9a): {@code com.dan.dune} mit dem Wind aus
 * {@code com.dan.wind}.
 * <ul>
 * <li><b>Wind:</b> ein massenkonsistentes Feld über dem Kern ({@link WindField#solve}, im eigenen Faden, neu bei anderem
 * Wind); der Sand nimmt den Wind in 10 m über dem Grund am Ort, also schwächer im Lee der Promenade, des Kurparks und
 * des Brodtener Ufers. Rauigkeit nach der Art des Grunds.</li>
 * <li><b>Nässe:</b> die Ostsee hat kaum Tide: nass ist der Sand unter dem Pegel und im Auflauf darüber (0,3 m und
 * 0,7 · H_s, bei Sturmflut entsprechend höher) [G]; nach Regen ein Boden für alles ({@link #regen}). Der nasse Strand
 * fliegt nicht.</li>
 * <li><b>Bewuchs:</b> nach der Art des Grunds: Strand kahl, Schilf, Wiese, Acker, Wald, Siedlung und Wege geben keinen
 * Sand, das Brodtener Ufer nur, wo es flacher ist als der Schüttwinkel. Liegt Schnee, fliegt nichts.</li>
 * <li><b>Feld und Körner:</b> ein {@link FluxField} um das Auge (240 m in Windrichtung, 160 m quer, 0,5 m), im eigenen
 * Faden neu, wenn das Auge 50 m gewandert ist oder Wind, Pegel oder Regen sich geändert haben; die Körner
 * ({@link Grains}) springen um das Auge.</li>
 * </ul>
 * Der Ostseesand am Timmendorfer Strand ist etwas feiner als der Sylter (Mittelkorn 0,28 mm [G]); weht der Wind auflandig
 * mit Bft 6 und mehr über trockenen Strand, ziehen Sandfahnen zwischen den Körben hindurch.
 */
public final class Sand {
    /** Das Feld um das Auge: Länge in Windrichtung, quer, Zelle (m), Weg des Auges bis zum Neubau (m). */
    public static final double LAENGS = 240, QUER = 160, ZELLE = 0.5, WANDERN = 50;

    /** Art des Grunds am Ort (Terrain.STRAND …). */
    public interface Art { byte at(double x, double z); }

    public final Dune dune;
    public final WindField wind = WindField.westlage();
    private final DoubleBinaryOperator grund;
    private final Art art;
    private final java.util.function.DoubleSupplier pegel;
    /** Ob Sand fliegt (Bedienfeld), Nässe nach Regen als Massenanteil (0 trocken … 0,06 durchnässt), Auflauf über dem Pegel (m), Schnee (m). */
    public volatile boolean an = true;
    public volatile double regen, auflauf = 0.4, schnee;
    private volatile FluxField feld;
    private volatile Grains koerner;
    private volatile Thread bauFeld, bauWind;
    private double windV = Double.NaN, windR = Double.NaN, pegelF = Double.NaN, regenF = Double.NaN, cxF = Double.NaN, czF = Double.NaN,
            auflaufF = Double.NaN, schneeF = Double.NaN;
    private double windGeloest = Double.NaN, richtungGeloest = Double.NaN;
    private volatile double geloestMit;
    public volatile String meldung = "noch kein Feld";
    public volatile double windMs, feldMs, koernerMs;

    /** @param grund Höhe des Geländes, @param art Art des Grunds, @param pegel Wasserstand (m NHN) */
    public Sand(DoubleBinaryOperator grund, Art art, java.util.function.DoubleSupplier pegel) {
        this.grund = grund;
        this.art = art;
        this.pegel = pegel;
        dune = Dune.sylt();
        dune.grain = 0.28e-3;
        dune.windFromDeg = 250;
        dune.seed = 1923;
        dune.ground = grund::applyAsDouble;
        dune.moisture = this::feuchte;
        dune.cover = this::bewuchs;
        wind.seed = 1923;
        wind.quality = WindField.Quality.MEDIUM;
        wind.ground = grund::applyAsDouble;
        wind.roughness = this::rauigkeit;
        dune.wind = new Dune.Wind() {
            public double speed(double x, double z) { double[] v = wind10(x, z); return Math.hypot(v[0], v[2]); }
            public double fromDeg(double x, double z) {
                double[] v = wind10(x, z);
                if (Math.hypot(v[0], v[2]) < 1e-3) return wind.fromDeg;
                return (Math.toDegrees(Math.atan2(v[0], -v[2])) + 180 + 360) % 360;
            }
        };
        koerner = new Grains(dune);
    }

    /** Wind 10 m über dem Grund am Ort {Ost, oben, Süd}; bis das neue Feld fertig ist, das alte auf die Lage skaliert. */
    double[] wind10(double x, double z) {
        double[] v = wind.windAt(x, grund.applyAsDouble(x, z) + 10, z, 0);
        double k = wind.solution != null && geloestMit > 0.1 ? wind.speed / geloestMit : 1;
        return new double[]{v[0] * k, v[1] * k, v[2] * k};
    }

    /** Nässe des Sands als Massenanteil: unter dem Pegel nass, im Auflauf darüber feucht bis trocken, nach Regen mindestens {@link #regen}. */
    double feuchte(double x, double z) {
        double ueber = grund.applyAsDouble(x, z) - pegel.getAsDouble(), a = Math.max(0.15, auflauf), m;
        if (ueber < 0) m = 0.25;
        else if (ueber < a) { double t = 1 - ueber / a; m = 0.25 * t * t + 0.0003; }
        else m = 0.0003;
        return Math.max(m, regen);
    }

    /** Bewuchs λ nach der Art des Grunds (1 = kein loser Sand). */
    double bewuchs(double x, double z) {
        if (schnee > 0.005) return 1;
        double gx = grund.applyAsDouble(x + 1, z) - grund.applyAsDouble(x - 1, z), gz = grund.applyAsDouble(x, z + 1) - grund.applyAsDouble(x, z - 1);
        if (Math.hypot(gx, gz) / 2 > Math.tan(Math.toRadians(dune.reposeDeg))) return 1;
        return switch (art.at(x, z)) {
            // die Karte kennt den Strand, wie ihn das Luftbild bei seinem Wasserstand zeigt: über dem Pegel ist „See“ Strand
            case Terrain.STRAND, Terrain.NASS, Terrain.KEINE, Terrain.SEE -> 0;
            default -> 1;
        };
    }

    /** Rauigkeit für das Windfeld (m). */
    double rauigkeit(double x, double z) {
        return switch (art.at(x, z)) {
            case Terrain.SEE -> 0.0002;
            case Terrain.STRAND, Terrain.NASS -> 0.002;   // mit den Strandkörben etwas rauer als in Sylt [G]
            case Terrain.WEG -> 0.01;
            case Terrain.WIESE, Terrain.KLIFF -> 0.04;
            case Terrain.ACKER -> 0.06;
            case Terrain.SCHILF -> 0.1;
            case Terrain.GEBUESCH -> 0.3;
            case Terrain.SIEDLUNG -> 0.8;
            case Terrain.WALD -> 1.0;
            default -> 0.03;
        };
    }

    /**
     * Je Bild: Wind der Lage übernehmen (neues Windfeld im Hintergrund, wenn er sich deutlich ändert), Feld um das Auge bei
     * Bedarf neu (Hintergrund), Körner um das Auge einen Schritt.
     */
    public void schritt(double dt, double[] auge, double windSpeed, double windFromDeg) {
        wind.speed = windSpeed;
        wind.fromDeg = windFromDeg;
        loeseWind(windSpeed, windFromDeg, false);
        if (!an) return;
        double p = pegel.getAsDouble();
        boolean neu = feld == null || Math.hypot(auge[0] - cxF, auge[2] - czF) > WANDERN || Math.abs(windSpeed - windV) > 0.8
                || Math.abs(winkel(windFromDeg - windR)) > 6 || Math.abs(p - pegelF) > 0.1 || Math.abs(regen - regenF) > 0.002
                || Math.abs(auflauf - auflaufF) > 0.1 || (schnee > 0.005) != (schneeF > 0.005);
        if (neu && bauFeld == null) {
            final double cx = auge[0], cz = auge[2];
            windV = windSpeed; windR = windFromDeg; pegelF = p; regenF = regen; cxF = cx; czF = cz; auflaufF = auflauf; schneeF = schnee;
            Thread th = new Thread(() -> {
                try { baueFeld(cx, cz); } catch (RuntimeException e) { meldung = "Feld: " + e.getMessage(); } finally { bauFeld = null; }
            }, "sand-feld");
            th.setDaemon(true);
            bauFeld = th;
            th.start();
        }
        FluxField f = feld;
        if (f == null || dt <= 0) return;
        long t0 = System.nanoTime();
        koerner.step(Math.min(0.05, dt), f, auge[0], auge[2]);
        koernerMs = koernerMs * 0.9 + (System.nanoTime() - t0) / 1e6 * 0.1;
    }

    /**
     * Qualitätsstufe 0 (Niedrig) bis 3 (Ultra): Körner nach {@code dune}, das Windfeld eine Stufe gröber als die Szene
     * (Mittel rechnet es mit MEDIUM wie Sylt); mit anderer Auflösung wird es neu gelöst.
     */
    public void stufe(int o) {
        dune.quality = Dune.Quality.values()[Math.max(0, Math.min(3, o))];
        koerner.budget = dune.grainBudget();
        WindField.Quality[] v = WindField.Quality.values();
        WindField.Quality q = v[Math.max(0, Math.min(v.length - 1, WindField.Quality.MEDIUM.ordinal() + o - 1))];
        if (wind.quality != q) {
            wind.quality = q;
            windGeloest = Double.NaN;
        }
    }

    static double winkel(double d) { return ((d % 360) + 540) % 360 - 180; }

    /** Das Windfeld über dem Kern neu (im eigenen Faden, wenn {@code sofort} falsch). */
    public void loeseWind(double v, double r, boolean sofort) {
        Thread laeuft = bauWind;
        if (sofort && laeuft != null) {
            try { laeuft.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        boolean gleich = !Double.isNaN(windGeloest) && Math.abs(v - windGeloest) < 1.5 && Math.abs(winkel(r - richtungGeloest)) < 10;
        if (gleich && (!sofort || wind.solution != null)) return;
        if (bauWind != null && !sofort) return;
        windGeloest = v;
        richtungGeloest = r;
        double[] k = Site.KERN.scene();
        WindField w = wind.copy();
        w.speed = v;
        w.fromDeg = r;
        Runnable job = () -> {
            long t0 = System.nanoTime();
            WindSolution s = w.solve(Math.min(k[0], k[2]), Math.min(k[1], k[3]), Math.max(k[0], k[2]), Math.max(k[1], k[3]));
            wind.solution = s;
            geloestMit = v;
            windMs = (System.nanoTime() - t0) / 1e6;
            regenF = Double.NaN;   // Feld mit dem neuen Wind neu
        };
        if (sofort) { job.run(); return; }
        Thread th = new Thread(() -> { try { job.run(); } finally { bauWind = null; } }, "sand-wind");
        th.setDaemon(true);
        bauWind = th;
        th.start();
    }

    /** Das Feld um (cx, cz) neu (im Faden des Aufrufers). */
    public FluxField baueFeld(double cx, double cz) {
        long t0 = System.nanoTime();
        FluxField f = new FluxField(dune, cx, cz, LAENGS, QUER, ZELLE).compute();
        feld = f;
        feldMs = (System.nanoTime() - t0) / 1e6;
        meldung = f.describe();
        return f;
    }

    public FluxField feld() { return feld; }

    /** Für Standbilder und Prüfung: Windfeld und Feld um das Auge sofort rechnen und die Körner so viele Sekunden vorlaufen lassen. */
    public void bereit(double[] auge, double windSpeed, double windFromDeg, double sekunden) {
        wind.speed = windSpeed;
        wind.fromDeg = windFromDeg;
        loeseWind(windSpeed, windFromDeg, true);
        windV = windSpeed; windR = windFromDeg; pegelF = pegel.getAsDouble(); regenF = regen; cxF = auge[0]; czF = auge[2]; auflaufF = auflauf; schneeF = schnee;
        baueFeld(auge[0], auge[2]);
        for (double s = 0; s < sekunden; s += 1 / 60.0) koerner.step(1 / 60.0, feld, auge[0], auge[2]);
    }

    public Grains koerner() { return koerner; }

    /** Fluss am Ort (kg/(m·s)), 0 außerhalb des Felds; für den Klang. */
    public double flussBei(double x, double z) {
        FluxField f = feld;
        if (!an || f == null) return 0;
        double q = f.fluxAt(x, z);
        return Double.isNaN(q) ? 0 : q;
    }

    /** „Flugsand: Wind hier 14 m/s aus 268° (Feld aus wind, 2,8 s) · u* 0,52 m/s, Schwelle 0,24 m/s · fliegt 40 g/(m·s) · 9 800 Körner“ */
    public String zeile(double x, double z) {
        if (!an) return "Flugsand: aus";
        FluxField f = feld;
        double[] v = wind10(x, z);
        double sp = Math.hypot(v[0], v[2]);
        String w = String.format(Locale.GERMAN, "Wind hier %.0f m/s aus %03.0f°%s", sp, dune.wind.fromDeg(x, z), wind.solution != null
                ? String.format(Locale.GERMAN, " (Feld aus wind, %.1f s)", windMs / 1000) : " (Näherung, Feld rechnet)");
        if (f == null) return "Flugsand: " + w + " · Feld wird gerechnet";
        double q = f.fluxAt(x, z), us = f.ustarAt(x, z), ut = f.thresholdAt(x, z);
        String hier = Double.isNaN(q) ? "außerhalb des Felds" : schnee > 0.005 ? "Schnee, liegt" : q > 1e-5 ? String.format(Locale.GERMAN, "fliegt %.0f g/(m·s)", q * 1000)
                : dune.moisture.moisture(x, z) > 0.01 ? "nass, liegt" : us < ut ? "liegt (unter der Schwelle)" : "liegt";
        return String.format(Locale.GERMAN, "Flugsand: %s · u* %.2f m/s, Schwelle %.2f m/s · %s · %d Körner (%.1f ms)", w,
                Double.isNaN(us) ? 0 : us, Double.isNaN(ut) ? 0 : ut, hier, koerner.count, koernerMs);
    }
}
