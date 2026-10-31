package com.dan.timmendorf.wiring;

import com.dan.cloud.CloudType;
import com.dan.weather.Scenario;
import com.dan.weather.Weather;

import java.util.Locale;

/**
 * Seit Phase 13: die Wetterregie der Lübecker Bucht ({@code com.dan.weather}, aus Sylt Phase 13 übernommen und auf die Bucht
 * gestellt). Drei Arten zu führen:
 * <ul>
 * <li><b>Lage</b> (Vorgabe „Sommerabend“, wie bis Phase 12 von Hand): eine benannte Lage steht fest, die Zeitleiste ruht.</li>
 * <li><b>Freie Regie</b>: die Markow-Kette des Packages wählt die Lagen nach dem Klima von Travemünde.</li>
 * <li><b>„Ein Sturmtag“</b>: das Regiebuch des Packages.</li>
 * </ul>
 * „Von Hand“ schaltet die Regie ab: die Regler im Bedienfeld schreiben direkt in die Verdrahtung.
 * <p>
 * Der Seegang der Bucht folgt nicht der Dünung der Regie, sondern dem Wind über der Streichlänge ({@link #seegang}): die
 * Bucht ist nach Nordost offen (rund 40 km), nach Osten und Südosten 12 km bis Travemünde und zum Klützer Winkel, bei
 * Wind vom Land (Süd bis West) nur wenige Kilometer [G nach der Seekarte].
 */
public final class Wetterregie {
    public enum Modus {
        HAND("von Hand"), LAGE("Lage"), FREI("freie Regie"), STURMTAG("Ein Sturmtag");
        public final String label;
        Modus(String l) { label = l; }
    }

    /**
     * Die Lagen der Bucht [G]: Wind 10 m über Grund (m/s, aus Grad), Böigkeit, Bedeckung (Achtel), hohe Schicht, Wolkenart,
     * Regen (mm/h unter einem Vorhang), Sicht (km), Nebel 0..1, Obergrenze des Nebels (m über dem Pegel), Gewitter 0..4,
     * Temperatur (°C; im Winter fällt darunter Schnee).
     */
    public enum Lage {
        SOMMERABEND("Sommerabend mit Seewind", Weather.Phase.KLAR, 5.5, 45, 0.14, 2.5, 0.3, CloudType.CUMULUS, 0, 35, 0, 50, 0, 18),
        WESTWIND("Westwind, ablandig", Weather.Phase.AUFZIEHEND, 9, 250, 0.22, 4, 0.2, CloudType.CUMULUS, 0, 40, 0, 50, 0, 17),
        NORDOST("Nordost mit Schaumkronen", Weather.Phase.KLAR, 11, 50, 0.20, 3, 0.1, CloudType.CUMULUS, 0, 30, 0, 50, 0, 15),
        SCHAUER("Schauer über der Bucht", Weather.Phase.ABKLINGEND, 10, 290, 0.30, 5, 0.1, CloudType.CUMULONIMBUS, 6, 25, 0, 50, 0, 13),
        LANDREGEN("Landregen", Weather.Phase.FRONT, 8, 220, 0.20, 8, 0.2, CloudType.NIMBOSTRATUS, 2.5, 8, 0, 50, 0, 12),
        HERBSTSTURM("Herbststurm aus Nordwest", Weather.Phase.STURM, 19, 305, 0.25, 7.5, 0, CloudType.STRATOCUMULUS, 4, 10, 0, 50, 0, 9),
        SEENEBEL("Seenebel", Weather.Phase.NEBEL, 2.5, 60, 0.08, 1, 0.25, CloudType.CUMULUS, 0, 12, 0.9, 45, 0, 14),
        GEWITTER("Gewitter über der Bucht", Weather.Phase.GEWITTER, 7, 230, 0.30, 6, 0.1, CloudType.CUMULONIMBUS, 9, 15, 0, 50, 2, 24),
        WINTERTAG("Wintertag mit Ostwind und Frost", Weather.Phase.KLAR, 4, 90, 0.12, 1, 0.1, CloudType.CUMULUS, 0, 40, 0, 50, 0, -6),
        SCHNEE("Schneefall", Weather.Phase.FRONT, 6, 70, 0.20, 8, 0.1, CloudType.NIMBOSTRATUS, 1.5, 4, 0, 50, 0, -1.5),
        SEERAUCH("Seerauch bei strengem Frost", Weather.Phase.KLAR, 3, 100, 0.10, 0, 0.05, CloudType.CUMULUS, 0, 25, 0.55, 9, 0, -9);

        public final String label;
        public final Weather.Phase phase;
        public final double wind, dir, gust, oktas, high, rain, vis, fog, fogTop, temp;
        public final CloudType cloud;
        public final int thunder;

        Lage(String label, Weather.Phase phase, double wind, double dir, double gust, double oktas, double high, CloudType cloud, double rain, double vis,
             double fog, double fogTop, int thunder, double temp) {
            this.label = label; this.phase = phase; this.wind = wind; this.dir = dir; this.gust = gust; this.oktas = oktas; this.high = high;
            this.cloud = cloud; this.rain = rain; this.vis = vis; this.fog = fog; this.fogTop = fogTop; this.thunder = thunder; this.temp = temp;
        }

        @Override public String toString() { return label; }
    }

    /** Monatsmittel der Lufttemperatur in Travemünde/Lübeck (DWD 1991–2020, gerundet) [G]. */
    public static final double[] KLIMA = {1.3, 1.7, 4.0, 8.0, 12.1, 15.3, 17.6, 17.4, 14.3, 10.0, 5.8, 2.6};

    public final Weather weather = new Weather();
    private final Wiring w;
    private volatile Modus modus = Modus.LAGE;
    private volatile Lage lage = Lage.SOMMERABEND;
    private Weather.Snapshot snap;

    Wetterregie(Wiring w) {
        this.w = w;
        weather.seed = 2026;
        weather.zone = java.time.ZoneId.of("Europe/Berlin");
        weather.climate = KLIMA.clone();
    }

    public Modus modus() { return modus; }
    public Lage lage() { return lage; }
    public Weather.Snapshot snapshot() { return snap; }

    private double t() { return w.clock.epochMillis() / 1000.0; }

    /** Eine Lage halten (die Zeitleiste ruht, alle Größen stehen sofort). */
    public synchronized void lage(Lage l) {
        lage = l;
        modus = Modus.LAGE;
        weather.running = true;
        weather.release();
        weather.startFree(t(), l.phase);
        weather.set(Weather.Var.WIND, l.wind);
        weather.set(Weather.Var.DIRECTION, l.dir);
        weather.set(Weather.Var.GUST, l.gust);
        weather.set(Weather.Var.COVER, l.oktas);
        weather.set(Weather.Var.HIGH, l.high);
        weather.set(Weather.Var.CLOUD, l.cloud.ordinal());
        weather.set(Weather.Var.RAIN, l.rain);
        weather.set(Weather.Var.VISIBILITY, l.vis);
        weather.set(Weather.Var.FOG, l.fog);
        weather.set(Weather.Var.THUNDER, l.thunder);
        weather.set(Weather.Var.TEMPERATURE, l.temp);
        weather.running = false;
        anwenden(weather.update(t(), 0));
    }

    /** Freie Regie ab jetzt (in der Phase, die gerade gilt). */
    public synchronized void frei() {
        Weather.Phase p = snap != null ? snap.phase() : lage.phase;
        modus = Modus.FREI;
        weather.release();
        weather.running = true;
        weather.startFree(t(), p);
        anwenden(weather.update(t(), 0));
    }

    /** Das Regiebuch „Ein Sturmtag“ ab jetzt. */
    public synchronized void sturmtag() {
        modus = Modus.STURMTAG;
        weather.release();
        weather.running = true;
        weather.start(Scenario.stormDay(), t());
        anwenden(weather.update(t(), 0));
    }

    /** Von Hand: die Regie ruht, die Regler schreiben direkt. */
    public synchronized void hand() { modus = Modus.HAND; }

    /** Je Schritt: Regie weiter nach der Uhr der Szene, die Werte in die Verdrahtung. */
    public synchronized void schritt(double realDt) {
        if (modus == Modus.HAND) return;
        anwenden(weather.update(t(), realDt));
    }

    private void anwenden(Weather.Snapshot s) {
        if (s == null) return;
        snap = s;
        w.windSpeed = s.wind();
        w.windFromDeg = s.fromDeg();
        w.gustiness = s.gustiness();
        w.gustNow = s.gustNow();
        w.oktas = s.oktas();
        w.highCover = s.high();
        w.cloudType = CloudType.values()[Math.max(0, Math.min(CloudType.values().length - 1, s.cloud().ordinal()))];
        w.regen = s.rain();
        w.visibilityKm = s.visibilityKm();
        w.nebel = s.fog();
        w.gewitter = s.thunder();
        w.temperatur = s.temperature();
        w.nebelHoch = modus == Modus.LAGE ? lage.fogTop : 18 + 60 * s.fog() * s.fog();
        double[] sg = seegang(s.wind(), s.fromDeg());
        w.seeHs = sg[0];
        w.seeTp = sg[1];
        w.seeFromDeg = s.fromDeg();
    }

    /** Streichlänge der Bucht (m) für Wind aus fromDeg [G nach der Seekarte]. */
    public static double streichlaenge(double fromDeg) {
        double d = ((fromDeg % 360) + 360) % 360;
        if (d < 100) return 40_000;      // offene Ostsee nach Nord bis Ost
        if (d < 140) return 12_000;      // über die Bucht vom Klützer Winkel und von Travemünde
        if (d < 315) return 1_500;       // vom Land
        return 20_000;                   // Neustädter Bucht im Nordwesten
    }

    /**
     * Seegang nach JONSWAP für begrenzte Streichlänge (g·Hs/U² = 1,6·10⁻³·(gF/U²)^½, g·Tp/U = 0,286·(gF/U²)^⅓), nach oben
     * durch die ausgereifte See begrenzt; mindestens 0,1 m und 2 s. {Hs (m), Tp (s)}.
     */
    public static double[] seegang(double wind, double fromDeg) {
        double u = Math.max(1, wind), g = 9.81, f = streichlaenge(fromDeg), x = g * f / (u * u);
        double hs = Math.min(0.0016 * Math.sqrt(x) * u * u / g, 0.24 * u * u / g);
        double tp = Math.min(0.286 * Math.cbrt(x) * u / g, 7.7 * u / g);
        return new double[]{Math.max(0.1, hs), Math.max(2, tp)};
    }

    /** „Lage Sommerabend mit Seewind“ / „freie Regie: Front (Bft 6), 1003 hPa“ */
    public String zeile() {
        Weather.Snapshot s = snap;
        return switch (modus) {
            case HAND -> "Regie: von Hand";
            case LAGE -> "Lage " + lage.label;
            case FREI -> s == null ? "freie Regie" : String.format(Locale.GERMAN, "freie Regie: %s (Bft %d), %.0f hPa, dann %s in %.1f h",
                    s.phase().label, s.beaufort(), s.pressure(), s.next().label, s.nextInH());
            case STURMTAG -> s == null ? "Ein Sturmtag" : String.format(Locale.GERMAN, "Ein Sturmtag, Schritt %d von %d: %s (Bft %d, %.0f hPa)",
                    s.step() + 1, s.steps(), s.stepText(), s.beaufort(), s.pressure());
        };
    }
}
