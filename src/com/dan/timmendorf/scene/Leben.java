package com.dan.timmendorf.scene;

import com.dan.flock.Flock;
import com.dan.flock.Species;
import com.dan.seal.Colony;
import com.dan.seal.Seal;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleBinaryOperator;

/**
 * Seit Phase 13: das Leben an der Bucht (F26 A), aus {@code Renderer.jar} (Muster aus Sylt Phase 13):
 * <ul>
 * <li><b>Möwen, Schwäne und Kormorane</b> ({@code com.dan.flock}): ein Schwarm, dessen Revier dem Blick folgt – zum
 * Niendorfer Hafen, zur Maritim Seebrücke oder zur Seeschlösschenbrücke, je nachdem, was am nächsten liegt. Sitzplätze sind
 * die Laternen auf den Seebrücken über dem Wasser und die Kutter im Hafen; Schwäne schwimmen im Hafen und vor dem Strand,
 * Kormorane sitzen auf den Laternen und tauchen. Nachts fliegen die Möwen zum Hemmelsdorfer See.</li>
 * <li><b>Seehund, selten</b> ({@code com.dan.seal}): in etwa einer von acht Stunden schwimmt einer vor der Schleife der
 * Seebrücke.</li>
 * </ul>
 * Liegt Eis auf der Bucht, schwimmen die Schwäne nur, wo das Wasser offen ist. Ohrenquallen unter der Seebrücke (F26) fehlen
 * noch: dafür gibt es kein Package.
 */
public final class Leben {
    public final Flock vogel = new Flock();
    public final Colony seehund = new Colony(Seal.harbourSeal());
    public volatile boolean anVoegel = true, anSeehund = true;
    private final DoubleBinaryOperator grund;
    private volatile double pegel, eisTiefe;
    private boolean seehundDa;
    private long seehundStunde = Long.MIN_VALUE;
    private double homeTakt;
    private int sitzplaetze;

    /** Orte, an die das Revier springt (x, z, Halbmesser) [G]. */
    static final double[][] REVIERE = {{2080, 800, 380}, {Site.SCHLEIFE.x() * 0.6, Site.SCHLEIFE.z() * 0.6, 320}, {752, 545, 260}};

    public Leben(DoubleBinaryOperator grund) {
        this.grund = grund;
        vogel.seed = 2026;
        vogel.gullCount = 14;
        vogel.homeX = REVIERE[1][0];
        vogel.homeZ = REVIERE[1][1];
        vogel.homeRadius = REVIERE[1][2];
        vogel.coastDeg = 230;   // abends und im Sturm landeinwärts zum Hemmelsdorfer See
        vogel.ground = grund::applyAsDouble;
        int[] mix = new int[Species.values().length];
        mix[Species.SCHWAN.ordinal()] = 6;
        mix[Species.KORMORAN.ordinal()] = 5;
        mix[Species.ENTE.ordinal()] = 6;
        vogel.mix = mix;
        vogel.water = (x, z) -> {
            double g = grund.applyAsDouble(x, z), d = pegel - g;
            if (d < 0.4) return Double.NaN;
            if (eisTiefe > 0 && d < eisTiefe) return Double.NaN;   // zugefroren
            return pegel;
        };
        vogel.waters.add(new Flock.Area(2080, 830, 140));                                    // Niendorfer Hafen
        vogel.waters.add(new Flock.Area(Site.SCHLEIFE.x() * 0.5, Site.SCHLEIFE.z() * 0.5 - 30, 260));   // vor dem Strand an der Seebrücke
        vogel.waters.add(new Flock.Area(760, 520, 200));                                    // an der Seeschlösschenbrücke
        // Sitzplätze auf den Kuttern (Mast, Ruderhaus)
        for (double[] k : Bauwerke.KUTTER) vogel.perches.add(new Flock.Perch("Kutter", k[0], 3.6, k[1]).appeal(1.4));
        seehund.seal.seed = 2026;
        seehund.count = 1;
    }

    /** Laternen auf den Seebrücken über dem Wasser als Sitzplätze (jede zweite; Kopf auf y). */
    public void laternen(List<double[]> orte) {
        int i = 0;
        for (double[] p : orte) {
            if (grund.applyAsDouble(p[0], p[2]) > -0.3) continue;   // nur über Wasser
            if (i++ % 2 != 0) continue;
            vogel.perches.add(new Flock.Perch("Laterne", p[0], p[1] + 0.35, p[2]).appeal(1.2).only(Species.MOEWE, Species.KORMORAN));
            sitzplaetze++;
        }
    }

    /** Stufe: Zahl der Möwen. */
    public void stufe(int q) { vogel.gullCount = q == 0 ? 8 : q == 1 ? 14 : q == 2 ? 20 : 28; }

    /**
     * Je Bild (Szenenzeit): Wind, Licht, Jahr, Auge als Störer; das Revier folgt dem Ziel der Kamera; der Seehund in seiner
     * Stunde. eisTiefe: bis zu welcher Wassertiefe die Bucht zugefroren ist (m, 0 = offen).
     */
    public void schritt(double dt, double[] auge, double tx, double tz, double windSpeed, double windFromDeg, double lux, double sonne,
                        LocalDate tag, double stunde, double pegel, double eisTiefe, double wellen) {
        this.pegel = pegel;
        this.eisTiefe = eisTiefe;
        if (anVoegel) {
            vogel.windSpeed = windSpeed;
            vogel.windFromDeg = windFromDeg;
            vogel.ambientLux = lux;
            vogel.dayOfYear = tag.getDayOfYear();
            homeTakt -= dt;
            if (homeTakt <= 0) {
                homeTakt = 10;
                double[] best = REVIERE[1];
                double bd = Double.MAX_VALUE;
                for (double[] r : REVIERE) { double d = Math.hypot(r[0] - tx, r[1] - tz); if (d < bd) { bd = d; best = r; } }
                vogel.homeX = best[0];
                vogel.homeZ = best[1];
                vogel.homeRadius = best[2];
            }
            vogel.disturbers.clear();
            vogel.disturbers.add(new double[]{auge[0], auge[1], auge[2]});
            vogel.step(Math.min(0.1, dt));
        }
        long std = (long) Math.floor(tag.toEpochDay() * 24 + stunde);
        if (std != seehundStunde) {
            seehundStunde = std;
            long h = (std * 0x9E3779B97F4A7C15L) ^ 2026;
            h ^= h >>> 31;
            seehundDa = anSeehund && Math.floorMod(h, 8) == 0 && wellen < 1.2 && eisTiefe <= 0;
            if (seehundDa) {
                double off = 120 + 60 * Math.floorMod(h >>> 8, 100) / 100.0;
                double sl = Math.hypot(Site.SCHLEIFE.x(), Site.SCHLEIFE.z());
                seehund.banks.clear();
                seehund.banks.add(new Colony.Bank(Site.SCHLEIFE.x() + Site.SCHLEIFE.x() / sl * off, Site.SCHLEIFE.z() + Site.SCHLEIFE.z() / sl * off, 25));
                seehund.world = new Colony.World() {
                    public double ground(double x, double z) { return grund.applyAsDouble(x, z); }
                    public double water(double x, double z) { return grund.applyAsDouble(x, z) < Leben.this.pegel - 0.4 ? Leben.this.pegel : Double.NaN; }
                };
                seehund.month = tag.getMonthValue();
                seehund.settle();
            }
        }
        if (seehundDa) {
            seehund.sunAltDeg = sonne;
            seehund.disturbers.clear();
            seehund.step(Math.min(1, dt));
        }
    }

    public boolean seehundDa() { return seehundDa && anSeehund; }

    public String describe() {
        int[] n = new int[Species.values().length];
        for (Flock.Gull g : vogel.gulls()) n[g.species.ordinal()]++;
        return String.format(Locale.GERMAN, "Leben (flock, seal): %d Möwen, %d Schwäne, %d Kormorane, %d Enten · Revier %.0f/%.0f · %d Laternen als Sitzplatz · %s",
                n[Species.MOEWE.ordinal()], n[Species.SCHWAN.ordinal()], n[Species.KORMORAN.ordinal()], n[Species.ENTE.ordinal()], vogel.homeX, vogel.homeZ, sitzplaetze,
                seehundDa() ? "ein Seehund vor der Seebrücke" : "kein Seehund (selten)");
    }
}
