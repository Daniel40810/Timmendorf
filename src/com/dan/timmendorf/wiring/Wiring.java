package com.dan.timmendorf.wiring;

import com.dan.cloth.Cloth;
import com.dan.cloud.CloudType;
import com.dan.cloud.Clouds;
import com.dan.ocean.Ocean;
import com.dan.sky.Sky;
import com.dan.firework.Firework;
import com.dan.route.Route;
import com.dan.sail.Sail;
import com.dan.timmendorf.scene.Flotte;
import com.dan.timmendorf.scene.Netz;
import com.dan.timmendorf.scene.Site;
import com.dan.timmendorf.scene.Terrain;
import com.dan.wake.Wake;

import java.util.Locale;

/**
 * Die Verdrahtung der App (Paketvertrag Punkt 4, aus Sylt, für Phase 1 neu und schlank): hier und nur hier reden die
 * Packages miteinander. Kein Package kennt ein anderes oder die Szene; die App reicht über ihre kleinen Schnittstellen
 * hindurch, was es braucht.
 * <p>
 * Phase 1 verdrahtet die Hüllen der fünf neuen Packages mit Wind, Wasser und Gelände der Bühne, damit der Weg von
 * Anfang an steht:
 * <ul>
 * <li>{@code cloth}: eine Strandflagge am Landende der Seebrücke; {@code Cloth.Wind} ← {@link #windSpeed},
 * {@link #windFromDeg} (ab Phase 13 aus {@code wind}), {@code Cloth.Ground} ← Gelände.</li>
 * <li>{@code sail}: ein Optimist vor der Seebrücke; {@code Sail.Wind} ← derselbe Wind, {@code Sail.Water} ← {@link #pegel}
 * (ab Phase 4 aus {@code ocean}).</li>
 * <li>{@code wake}: dasselbe Boot als Quelle des Kielwassers (seit Phase 8 alle Boote und die Fähren, {@link Flotte}).</li>
 * <li>{@code firework}: der Zündplan der Vorführung über einem Ponton vor der Seebrücke (F17 A), gezündet nur auf
 * Wunsch; {@code Firework.Wind} ← derselbe Wind.</li>
 * <li>{@code route}: ein Graph aus zwei Knoten auf der Seebrücke (ab Phase 3 aus dem Oracle-Netz {@code TD_NET_FUSS}).</li>
 * </ul>
 * Seit Phase 4 (das Grundbild, aus Sylt Phase 3): {@code sky}, {@code cloud} und {@code ocean} bekommen ihre Eingänge von
 * hier ({@link #feed(Sky, double)}, {@link #feed(Clouds)}, {@link #feed(Ocean)}): das Wetter als offene Werte
 * ({@link #oktas}, {@link #cloudType}, {@link #highCover}, {@link #visibilityKm}), der Seegang der Bucht
 * ({@link #seeHs}, {@link #seeTp}, {@link #seeFromDeg}); die Wetterregie kommt mit Phase 13.
 * <p>
 * Seit Phase 12 (der Abend): {@code lamp} in {@link #lichter} (Laternen, Straßenleuchten, Fensterlicht) mit dem
 * Dämmerungsschalter; das Feuerwerk folgt der Uhr der Szene ({@link #feuerwerkTag}, {@link #feuerwerkUhr}: von selbst,
 * oder Taste Z), sein Zustand ist immer der zur Uhrzeit (auch nach einem Sprung); {@code route} lässt die Zuschauer kommen
 * ({@code Menschen.zuschauer}).
 */
public final class Wiring {
    public final Clock clock;
    /** Wasserstand der Bucht über NHN (m): Phase 1 fest, ab Phase 13 Windstau aus {@code tide}, Phase 14 die Sturmflut. */
    public volatile double pegel = 0;
    /** Wind 10 m über Grund (m/s) und Richtung, aus der er kommt (Grad); Phase 1 offene Werte: Seewind aus Nordost [G]. */
    public volatile double windSpeed = 5.5, windFromDeg = 45;
    /**
     * Seit Phase 4: Bedeckung (Achtel), hohe Schicht, Sicht (km) und Wolkenart: ein Sommerabend zur Travemünder Woche mit
     * Schönwetter-Cumulus über dem Land [G]. {@link #cloudsDrawn}: ob die Wolken gezeichnet werden (sonst nimmt sie der Himmel).
     */
    public volatile double oktas = 2.5, highCover = 0.3, visibilityKm = 35;
    public volatile CloudType cloudType = CloudType.CUMULUS;
    public volatile boolean cloudsDrawn;
    /**
     * Seegang der Lübecker Bucht bei Seewind aus Nordost (Streichlänge rund 40 km) [G nach JONSWAP für 5–6 m/s]:
     * signifikante Höhe (m), Spitzenperiode (s), Richtung, aus der er kommt (Grad); ab Phase 7 aus {@code surf}.
     */
    public volatile double seeHs = 0.45, seeTp = 3.6, seeFromDeg = 45;
    /**
     * Seit Phase 13 aus der Wetterregie ({@link #regie}): Böigkeit und Böe des Augenblicks (m/s), Regen (mm/h unter einem
     * Vorhang), Nebel 0..1 und seine Obergrenze (m über dem Pegel), Gewitter 0..4, Lufttemperatur (°C).
     */
    public volatile double gustiness = 0.14, gustNow = 5.5, regen, nebel, nebelHoch = 50, temperatur = 18;
    public volatile int gewitter;
    /** Seit Phase 13: die Wetterregie (Vorgabe: Lage „Sommerabend mit Seewind“, wie bis Phase 12 von Hand). */
    public final Wetterregie regie = new Wetterregie(this);
    /**
     * Seit Phase 14: die Sturmflut vom 19. bis 21. Oktober 2023 ({@link com.dan.timmendorf.scene.Sturmflut}). Steht die Uhr
     * zwischen dem 18. und dem 23. Oktober 2023 und ist sie an, führt ihr Fahrplan Pegel, Wind, Wolken und Seegang (die
     * Wetterregie ruht so lange).
     */
    public volatile boolean sturmflutAn = true;
    private volatile boolean sturmflutLief;
    /** Stunde im Fahrplan der Sturmflut (NaN = sie führt gerade nicht). */
    public volatile double sturmflutStunde = Double.NaN;
    /** Seit Phase 13: das Jahr ({@code com.dan.season}): Schnee, Eis in der Bucht, Reif, Laub. */
    public final Jahreszeit jahr = new Jahreszeit();

    /** Tuch (seit Phase 1, XPBD seit Phase 6): eine Strandflagge am Landende der Seebrücke. */
    public final Cloth flagge = Cloth.beachFlag(-6, 2.2, 4);
    /** Kielwasser (Hülle seit Phase 1, Kelvin-Muster seit Phase 8): alle Boote und Fähren der Flotte. */
    public final Wake wake = Wake.luebeckerBucht();
    /**
     * Seit Phase 8a: die Boote der Szene ({@link Flotte}): Optimist vor der Seebrücke, Segelschule, Regatta, Fahrtensegler,
     * Kiter, Passat, Fähren. {@link #sail} und {@link #opti} sind die aus Phase 1 (jetzt Teil der Flotte).
     */
    public final Flotte flotte;
    /** Segel der Flotte (ohne Regatta). */
    public final Sail sail;
    /** Der Optimist vor der Seebrücke (Schaustück „Mit dem Optimisten“). */
    public final Sail.Boat opti;
    public final Wake.Source optiWake;
    /** Feuerwerk (Hülle seit Phase 1, echt seit Phase 12 in der Szene): Ponton 350 m vor der Seebrücke [G, F17 A]. */
    public volatile Firework firework;
    /** Seit Phase 12: die Lichter der Stadt (Laternen kommen aus dem Bau der Bauwerke, Straßen aus dem Straßennetz). */
    public final com.dan.timmendorf.scene.Lichter lichter = new com.dan.timmendorf.scene.Lichter();

    /** Seit Phase 12: Zündpläne. */
    public enum Plan {
        GROSS("Großes Feuerwerk (91 Bomben, gut 5 min)"), VORFUEHRUNG("Vorführung (12 Bomben, 30 s)");
        public final String label;
        Plan(String l) { label = l; }
    }

    /**
     * Seit Phase 12: das Feuerwerk des Abends von selbst: an {@link #feuerwerkTag} um {@link #feuerwerkUhr} (Ortszeit) –
     * Samstag, 1. August 2026, 22:45, eine Stunde nach dem Mondaufgang (22:04, Azimut 96°, über der See) [G: der Anlass ist
     * erfunden, Mond und Sonne sind gerechnet].
     */
    public volatile boolean feuerwerkAbends = true;
    public volatile java.time.LocalDate feuerwerkTag = java.time.LocalDate.of(2026, 8, 1);
    public volatile double feuerwerkUhr = 22.75;
    public volatile Plan plan = Plan.GROSS;
    /** Seit Phase 17: der Text des großen Zündplans aus der Datenbank (null: der aus dem Code). */
    public volatile String zuendplanText;
    /** Beginn des laufenden Feuerwerks (Epoche, ms; NaN = keins). */
    private volatile double fwStart = Double.NaN;
    private volatile int fwQualitaet = 2;
    public final double[] ponton = {Site.SCHLEIFE.x() + 120, Site.SCHLEIFE.z() - 160};
    /**
     * Wegenetz zu Fuß: seit Phase 3 das Netz {@code TD_NET_FUSS} aus {@code daten/netz} (Datei-Abbild des Oracle-Netzes);
     * ohne Netz die Hülle von Phase 1 (zwei Knoten auf der Seebrücke).
     */
    public final Route wege;
    /** Seit Phase 9: das Fußnetz selbst (Laternen der Promenade), null ohne daten/netz. */
    public final Netz fuss;
    /** Seit Phase 9: Seebrücken, Wolkenlos, DLRG, Kutter, Strandkörbe ({@code daten/bauwerke}) und die Leuchtfeuer. */
    public final com.dan.timmendorf.scene.Bauwerke bauwerke = com.dan.timmendorf.scene.Bauwerke.laden(com.dan.timmendorf.scene.Daten.ordner());
    public final com.dan.timmendorf.scene.Leuchtfeuer leuchtfeuer = new com.dan.timmendorf.scene.Leuchtfeuer(bauwerke);
    /** Seit Phase 10: die Strandkörbe (nach dem Gelände), Menschen und Verkehr; null bis {@link #setTerrain}. */
    public volatile java.util.List<com.dan.timmendorf.scene.Bauwerke.Korb> koerbe = java.util.List.of();
    public volatile com.dan.timmendorf.scene.Menschen menschen;
    public volatile com.dan.timmendorf.scene.Verkehr verkehr;
    /** Woher Straßen- und Busnetz kommen (HUD). */
    public volatile String verkehrQuelle = "";
    /** Woher {@link #wege} kommt. */
    public final String wegeQuelle;

    public Wiring(Clock clock) {
        this.clock = clock;
        regie.lage(Wetterregie.Lage.SOMMERABEND);
        Route w = null;
        Netz fn = null;
        String q;
        try {
            Netz n = null;
            String woher = "daten/netz";
            // F20 A: aus Oracle, wenn verbunden und TD_NETZ_STAND dieselbe Prüfsumme trägt wie die Datei
            com.dan.store.Db.Konfig k = com.dan.timmendorf.data.Verbindung.verbunden();
            if (k != null && Netz.da(Netz.ordner(), Netz.Art.FUSS)) {
                try (java.sql.Connection c = com.dan.store.Db.oeffnen(k)) {
                    String ps = com.dan.timmendorf.data.NetzOracle.pruefsumme(c, Netz.Art.FUSS);
                    if (ps != null && ps.equals(Netz.pruefsumme(Netz.ordner(), Netz.Art.FUSS))) {
                        n = com.dan.timmendorf.data.NetzOracle.laden(c, Netz.Art.FUSS);
                        woher = "Oracle";
                    } else woher = ps == null ? "daten/netz (in Oracle noch nicht: ant netzdb)" : "daten/netz (Oracle hat einen anderen Stand: ant netzdb)";
                } catch (java.sql.SQLException e) {
                    woher = "daten/netz (Oracle: " + com.dan.store.Db.erste(e) + ")";
                }
            }
            if (n == null && Netz.da(Netz.ordner(), Netz.Art.FUSS)) n = Netz.laden(Netz.ordner(), Netz.Art.FUSS);
            if (n != null && !n.kanten.isEmpty()) w = n.route();
            fn = n;
            q = n == null ? "Hülle (daten/netz fehlt)" : String.format(Locale.GERMAN, "TD_NET_FUSS aus %s: %,d Knoten, %,d Kanten", woher, n.knoten.size(), n.kanten.size());
        } catch (java.io.IOException | RuntimeException e) {
            q = "Hülle (daten/netz nicht lesbar: " + e.getMessage() + ")";
        }
        if (w == null) {
            w = new Route();
            int a = w.node(1, Site.SEEBRUECKE.x(), 3, Site.SEEBRUECKE.z()), b = w.node(2, Site.SCHLEIFE.x(), 3.5, Site.SCHLEIFE.z());
            w.edge(a, b, Route.Kind.PIER, 4, false);
        }
        wege = w;
        fuss = fn;
        wegeQuelle = q;
        flotte = new Flotte(wake, () -> windSpeed, () -> windFromDeg);
        sail = flotte.sail;
        opti = flotte.opti;
        optiWake = flotte.spur(opti);
        flagge.seed = sail.seed = wake.seed = wege.seed = 2026;
        flagge.wind = (x, y, z, t, o) -> windVektor(y, o);
        sail.water = (x, z, t) -> pegel;
        flotte.regattaSail.water = (x, z, t) -> pegel;
        // die Polaren rechnen in einer festen Stufe (Mittel): ein Wechsel der Qualität soll nicht alle neu rechnen lassen
        sail.quality = flotte.regattaSail.quality = Sail.Quality.MEDIUM;
    }

    /** Wind als Vektor (x Ost, z Süd) in der Höhe y, mit dem Gesetz der Wandgrenzschicht über Wasser (z0 = 0,2 mm) [G]. */
    void windVektor(double y, double[] o) {
        double h = Math.max(0.5, y - pegel), f = Math.log(h / 2e-4) / Math.log(10 / 2e-4);
        double to = Math.toRadians(windFromDeg + 180);
        o[0] = Math.sin(to) * windSpeed * f;
        o[1] = 0;
        o[2] = -Math.cos(to) * windSpeed * f;
    }

    /** Das Gelände an alle, die den Grund brauchen (einmal nach dem Laden). */
    public void setTerrain(Terrain t) {
        flagge.ground = t::height;
        flotte.setGrund(t::height);
        wake.depth = 12;
        // seit Phase 10: Strandkörbe, Menschen auf dem Fußnetz und am Strand, Autos und Busse
        koerbe = bauwerke.koerbe(t::height, (x, z) -> t.artAt(x, z) == 3);
        com.dan.sail.Regatta.Course rc = flotte.regatta.course;
        menschen = new com.dan.timmendorf.scene.Menschen(wege, koerbe, t::height, new double[]{rc.cx, rc.cz}, ponton, this::datum, this::stunde);
        Route strasse = new Route(), bus = null;
        java.util.Set<Integer> halte = new java.util.HashSet<>();
        try {
            if (Netz.da(Netz.ordner(), Netz.Art.STRASSE)) {
                Netz ns = Netz.laden(Netz.ordner(), Netz.Art.STRASSE);
                strasse = ns.route();
                lichter.strassen(ns, t::height);   // seit Phase 12: Straßenleuchten
            }
            if (Netz.da(Netz.ordner(), Netz.Art.BUS)) {
                Netz nb = Netz.laden(Netz.ordner(), Netz.Art.BUS);
                bus = nb.route();
                for (Netz.Knoten k : nb.knoten) if ("haltestelle".equals(k.art())) { int i = bus.indexOf(k.id()); if (i >= 0) halte.add(i); }
            }
            verkehrQuelle = String.format(Locale.GERMAN, "Straßen %,d Knoten, Bus %d Haltestellen (daten/netz)", strasse.nodeCount(), halte.size());
        } catch (java.io.IOException | RuntimeException e) {
            verkehrQuelle = "ohne Straßen (" + e.getMessage() + ")";
        }
        verkehr = new com.dan.timmendorf.scene.Verkehr(strasse, bus, halte, this::stunde, () -> datum().getMonthValue());
    }

    /** Ein Schritt der Hüllen (aus dem Faden der Physik oder dem Bildlauf). */
    public void step(double dt) {
        flagge.step(dt);
        // seit Phase 8a: alle Boote, die Regatta, die Fähren und ihr Kielwasser
        flotte.step(dt);
        // seit Phase 9: die Leuchtfeuer mit dem Dämmerungsschalter
        double[] sun = com.dan.timmendorf.scene.Sun.position(clock.epochMillis(), clock.latDeg(), clock.lonDeg());
        leuchtfeuer.step(dt, umgebungsLux(sun[0]));
        com.dan.timmendorf.scene.Menschen m = menschen;
        if (m != null && showMenschen) m.step(dt);
        com.dan.timmendorf.scene.Verkehr v = verkehr;
        if (v != null && showVerkehr) v.step(dt);
        // seit Phase 13: Wetterregie und Jahr
        regie.schritt(dt);
        sturmflutSchritt();
        jahr.step();
        // seit Phase 12: Lichter, Feuerwerk nach der Uhr, Zuschauer
        lichter.step(dt, umgebungsLux(sun[0]), stunde());
        feuerwerkSchritt();
        if (m != null) m.zuschauer = andrang();
    }

    /**
     * Seit Phase 13: das Jahr an der Bucht ({@code com.dan.season.Year}, Klima wie an der Schlei [G: Travemünde ist kaum
     * anders]). Temperatur: in der freien Regie, im Sturmtag und in den Winterlagen die der Regie, sonst das gewürfelte
     * Tagesmittel mit Tagesgang (eine Sommerlage im Januar schmilzt keinen Schnee). Niederschlag aus der Regie.
     */
    public final class Jahreszeit {
        public final com.dan.season.Year year = new com.dan.season.Year(com.dan.season.Climate.schleswig());
        private volatile com.dan.season.Year.State state;
        private double letzte = Double.NaN;
        { year.seed = 2026; }

        /** Ein Schritt nach der Uhr der Szene (rechnet nach einem Sprung selbst vor). */
        public com.dan.season.Year.State step() {
            double now = clock.epochMillis();
            double dt = Double.isNaN(letzte) ? 0 : (now - letzte) / 1000;
            if (!Double.isNaN(letzte) && Math.abs(dt) < 1 && state != null) return state;   // höchstens einmal je Sekunde
            letzte = now;
            java.time.ZonedDateTime z = java.time.Instant.ofEpochMilli((long) now).atZone(com.dan.timmendorf.scene.SceneClock.ZONE);
            int y = z.getYear(), doy = com.dan.season.Year.dayOfYear(z.getMonthValue(), z.getDayOfMonth());
            double h = z.getHour() + z.getMinute() / 60.0 + z.getSecond() / 3600.0;
            double air = luft(y, doy, h);
            double sun = com.dan.season.Year.sunAltitude(doy, h);
            com.dan.season.Year.State st = year.step(Math.max(0, dt), y, doy, h, air, Math.max(0, regen) * 0.5, sun, oktas, windSpeed);
            state = st;
            return st;
        }

        /** Lufttemperatur für das Jahr (°C). */
        public double luft(int y, int doy, double h) {
            Wetterregie.Modus m = regie.modus();
            Wetterregie.Lage l = regie.lage();
            boolean winter = l == Wetterregie.Lage.WINTERTAG || l == Wetterregie.Lage.SCHNEE || l == Wetterregie.Lage.SEERAUCH;
            if (m == Wetterregie.Modus.FREI || m == Wetterregie.Modus.STURMTAG || (m == Wetterregie.Modus.LAGE && winter)) return temperatur;
            return year.dayMean(y, doy) + 3.0 * Math.sin(2 * Math.PI * (h - 9) / 24);
        }

        public com.dan.season.Year.State state() {
            com.dan.season.Year.State s = state;
            return s != null ? s : step();
        }

        /** Schnee am Boden (m, mit Bedeckung), Reif 0..1, Schnee auf Dächern (m), Eisdicke (m). */
        public double schnee() { com.dan.season.Year.State s = state; return s == null ? 0 : s.snowDepth() * s.snowCover(); }
        public double reif() { com.dan.season.Year.State s = state; return s == null ? 0 : s.frost(); }
        public double dach() { com.dan.season.Year.State s = state; return s == null ? 0 : s.roofSnow(); }
        public double eis() { com.dan.season.Year.State s = state; return s == null ? 0 : s.ice(); }

        /** Fällt jetzt Schnee (mm/h Wasser), statt Regen? */
        public double schneefall() {
            com.dan.season.Year.State s = state;
            double t = s == null ? temperatur : s.airTemp();
            return Math.max(0, regen) * (1 - com.dan.season.Year.rainFraction(t));
        }

        public String describe() {
            com.dan.season.Year.State s = state;
            return s == null ? "Jahr: noch nicht gerechnet" : String.format(Locale.GERMAN, "Jahr (season): %s · Luft %.1f °C · Wasser %.1f °C · Laub %.0f %%, Herbst %.0f %%",
                    s.describe().replace("Noor", "Bucht"), s.airTemp(), s.waterTemp(), s.foliage() * 100, s.autumn() * 100);
        }
    }

    /** Seit Phase 14: Pegel, Wind und Wetter aus dem Fahrplan der Sturmflut, solange die Uhr darin steht. */
    public void sturmflutSchritt() {
        double h = sturmflutAn ? com.dan.timmendorf.scene.Sturmflut.stunde(clock.epochMillis()) : Double.NaN;
        sturmflutStunde = h;
        if (Double.isNaN(h)) {
            if (sturmflutLief) {   // vorbei: Pegel zurück, die Regie wieder mit ihrer Lage
                sturmflutLief = false;
                pegel = 0;
                if (regie.modus() == Wetterregie.Modus.LAGE) regie.lage(regie.lage());
            }
            return;
        }
        sturmflutLief = true;
        pegel = com.dan.timmendorf.scene.Sturmflut.pegel(h);
        windSpeed = com.dan.timmendorf.scene.Sturmflut.wind(h);
        windFromDeg = com.dan.timmendorf.scene.Sturmflut.richtung(h);
        gustiness = 0.25;
        gustNow = windSpeed * (1 + 0.25 * Math.max(0, Math.sin(clock.epochMillis() / 1000.0 * 0.7) * Math.sin(clock.epochMillis() / 1000.0 * 0.23)));
        oktas = windSpeed > 12 ? 7.5 : 6;
        cloudType = com.dan.cloud.CloudType.STRATOCUMULUS;
        highCover = 0;
        regen = windSpeed > 15 ? 2.0 : 0.5;
        visibilityKm = windSpeed > 15 ? 8 : 15;
        nebel = 0;
        gewitter = 0;
        temperatur = 10;
        double[] sg = Wetterregie.seegang(windSpeed, windFromDeg);
        seeHs = sg[0];
        seeTp = sg[1];
        seeFromDeg = windFromDeg;
    }

    /** Ortszeit als Epoche (ms). */
    private static double epoche(java.time.LocalDate d, double stunde) {
        return d.atStartOfDay(com.dan.timmendorf.scene.SceneClock.ZONE).toInstant().toEpochMilli() + stunde * 3_600_000.0;
    }

    /**
     * Seit Phase 12: das Feuerwerk auf den Stand der Uhr bringen. Von selbst gezündet am Abend, wenn die Uhr den Termin
     * erreicht (oder darüber springt); läuft die Uhr vor den Beginn zurück, ist es weg; nach dem Ende verschwindet es mit
     * dem Rauch. Höchstens 600 Schritte je Aufruf, damit ein Sprung den Bildlauf nicht anhält.
     */
    public void feuerwerkSchritt() {
        double now = clock.epochMillis();
        if (feuerwerkAbends) {
            double t0 = epoche(feuerwerkTag, feuerwerkUhr);
            if (now >= t0 && now < t0 + 900_000 && fwStart != t0) zuenden(t0);
        }
        Firework f = firework;
        if (f == null) return;
        double soll = (now - fwStart) / 1000;
        if (soll < -1) { firework = null; fwStart = Double.NaN; return; }
        if (soll < f.time() - 2) { zuenden(fwStart); f = firework; }   // Uhr zurück: neu von vorn
        synchronized (f) {
            for (int n = 0; f.time() < soll - 1e-6 && n < 600; n++) f.step(Math.min(1 / 30.0, soll - f.time()));
        }
        if (soll > f.duration() + 120) { firework = null; }
    }

    /** Seit Phase 12: Andrang 0..1 zum Feuerwerk des Abends: ab 50 min vorher steigend, voll ab 5 min vorher bis zum Ende. */
    public double andrang() {
        if (!feuerwerkAbends) return firework != null ? 1 : 0;
        double now = clock.epochMillis(), t0 = epoche(feuerwerkTag, feuerwerkUhr);
        double dauer = (plan == Plan.GROSS ? 340 : 40) * 1000.0, vor = (t0 - now) / 60_000.0;
        if (now > t0 + dauer) return 0;
        if (vor > 50) return 0;
        return vor <= 5 ? 1 : (50 - vor) / 45;
    }

    /** Seit Phase 10: Menschen und Verkehr rechnen (das Bedienfeld schaltet sie mit der Anzeige ab). */
    public volatile boolean showMenschen = true, showVerkehr = true;

    /** Datum der Szene (Ortszeit). */
    public java.time.LocalDate datum() { return java.time.Instant.ofEpochMilli((long) clock.epochMillis()).atZone(com.dan.timmendorf.scene.SceneClock.ZONE).toLocalDate(); }

    /** Stunde der Szene (Ortszeit, mit Bruchteil). */
    public double stunde() {
        java.time.ZonedDateTime z = java.time.Instant.ofEpochMilli((long) clock.epochMillis()).atZone(com.dan.timmendorf.scene.SceneClock.ZONE);
        return z.getHour() + z.getMinute() / 60.0 + z.getSecond() / 3600.0;
    }

    /**
     * Beleuchtungsstärke am Boden aus der Sonnenhöhe (lx) [G, Näherung für klaren Himmel]: 500 lx bei Sonnenuntergang,
     * 2 lx am Ende der bürgerlichen Dämmerung (−6°), darunter auf 0,001 lx (Nacht); am Tag bis 100 000 lx.
     */
    public static double umgebungsLux(double sonnenhoehe) {
        if (sonnenhoehe >= 0) return Math.min(100_000, 500 + 1800 * sonnenhoehe);
        return Math.max(1e-3, Math.pow(10, 2.7 + 0.4 * sonnenhoehe));
    }

    /** Zündet den gewählten Plan jetzt über dem Ponton (Taste Z). */
    public Firework feuerwerk() { return zuenden(clock.epochMillis()); }

    /** Seit Phase 12: der gewählte Plan, begonnen zur Epoche t0 (ms); die Uhr holt ihn im nächsten Schritt ein. */
    public Firework zuenden(double t0) {
        Firework f = plan == Plan.GROSS ? Firework.travemuenderWoche(ponton[0], ponton[1]) : Firework.demo(ponton[0], ponton[1]);
        String zt = zuendplanText;
        if (plan == Plan.GROSS && zt != null && !zt.isBlank()) {
            // seit Phase 17 (F44 A): der Zündplan aus TD_ZUENDPLAN, wenn er dort steht
            try { Firework g = new Firework(); g.load(ponton[0], 1.5, ponton[1], zt); f = g; }
            catch (RuntimeException e) { System.err.println("Timmendorf: Zündplan aus der Datenbank nicht lesbar: " + e.getMessage()); }
        }
        f.seed = 2026;
        f.quality = Firework.Quality.values()[fwQualitaet];
        f.wind = (x, y, z, t, o) -> windVektor(y, o);
        fwStart = t0;
        firework = f;
        return f;
    }

    /** Sekunden seit dem Beginn des laufenden Feuerwerks (NaN = keins). */
    public double feuerwerkZeit() { return firework == null ? Double.NaN : (clock.epochMillis() - fwStart) / 1000; }

    /** Eine Zeile für das HUD: der Optimist und die Flagge. */
    public String segelZeile() {
        double[] f = opti.last;
        double[] tip = flagge.point(flagge.nu - 1, flagge.nv - 1), base = flagge.point(0, 0);
        double aus = Math.toDegrees(Math.atan2(tip[0] - base[0], -(tip[2] - base[2])));
        return String.format(Locale.GERMAN, "Segel: Wind %.1f m/s aus %03.0f° · Optimist %.1f kn, Kurs %03.0f°, Krängung %.0f°, V_A %.1f m/s unter %.0f°, Vortrieb %.0f N%s · Flagge weht nach %03.0f°",
                windSpeed, windFromDeg, opti.speed / Sail.KN, opti.headingDeg, opti.rollDeg, f[3], f[4], f[0], opti.state().isEmpty() ? "" : " (" + opti.state() + ")", (aus + 360) % 360);
    }

    /** Seit Phase 4: dem Himmel Ort, Zeit, Auge, Sicht und (ohne gezeichnete Wolken) die Bedeckung. */
    public void feed(Sky sky, double eyeAsl) {
        sky.epochMillis = clock.epochMillis();
        sky.latDeg = clock.latDeg();
        sky.lonDeg = clock.lonDeg();
        sky.eyeHeight = Math.max(1, eyeAsl);
        sky.visibilityKm = visibilityKm;
        com.dan.season.Year.State js = jahr.state();
        sky.groundAlbedo = js != null ? Math.max(0.12, js.groundAlbedo()) : 0.12;   // seit Phase 13: Schnee hellt den Himmel auf
        sky.cloudCover = cloudsDrawn ? 0 : oktas / 8;
        sky.cloudVeil = !cloudsDrawn;
    }

    /** Den Wolken Bedeckung, Art, hohe Schicht und den Wind in Wolkenhöhe (1,6-fach, 20° rechtsdrehend, aus Rungholt) [G]. */
    public void feed(Clouds c) {
        c.oktas = oktas;
        c.type = cloudType;
        c.highCover = highCover;
        c.windSpeed = Math.max(3, windSpeed * 1.6);
        c.windFromDeg = (windFromDeg + 20) % 360;
        c.lens = 0;
    }

    /** Der See Wind, Seegang der Bucht und Pegel. */
    public void feed(Ocean o) {
        o.windSpeed = windSpeed;
        o.windFromDeg = windFromDeg;
        o.swellHeight = seeHs;
        o.swellPeriod = seeTp;
        o.swellFromDeg = seeFromDeg;
        o.waterLevel = pegel;
    }

    /** Eine Zeile für das HUD: das Wetter (seit Phase 13 aus der Regie). */
    public String wetterZeile() {
        return String.format(Locale.GERMAN, "Wetter: %s · %.1f/8 %s, hoher Schleier %.0f %%, Sicht %.0f km, Wind %.1f m/s aus %03.0f° (Böe %.1f), Regen %.1f mm/h, Nebel %.0f %%, %.1f °C · Seegang %.2f m, %.1f s (Streichlänge %.0f km)",
                Double.isNaN(sturmflutStunde) ? regie.zeile() : "Fahrplan der Sturmflut 2023", oktas, cloudType.label, highCover * 100, visibilityKm, windSpeed, windFromDeg, gustNow, regen, nebel * 100, temperatur, seeHs, seeTp,
                Wetterregie.streichlaenge(windFromDeg) / 1000);
    }

    /** Eine Zeile für das HUD: Kielwasser, Feuerwerk, Wege (Hüllen). */
    public String hullenZeile(double x, double z) {
        Firework f = firework;
        long luft = f == null ? 0 : f.shells().stream().filter(s -> s.launched && !s.burst).count(),
                aus = f == null ? 0 : f.shells().stream().filter(s -> s.burst).count();
        double d = Math.hypot(x - ponton[0], z - ponton[1]);
        return String.format(Locale.GERMAN, "Kielwasser: %d Quellen, Keil ±%.1f° (Optimist Fr %.2f) · Feuerwerk: %s · Knall bis hier %.1f s · Wege: %,d Punkte, %.1f km",
                wake.sources().size(), Wake.wakeAngleDeg(optiWake.froude()), optiWake.froude(),
                f == null ? (feuerwerkAbends ? String.format(Locale.GERMAN, "%s am %s um %s (Taste Z: jetzt)", plan.label, feuerwerkTag.format(java.time.format.DateTimeFormatter.ofPattern("d.M.")),
                        String.format(Locale.GERMAN, "%d:%02d", (int) feuerwerkUhr, (int) Math.round((feuerwerkUhr % 1) * 60))) : "nicht gezündet (Taste Z)")
                        : luft + " in der Luft, " + aus + " von " + f.shells().size() + " zerlegt, " + f.stars().size() + " Sterne",
                Firework.soundDelay(d, 18), wege.nodeCount(), wege.totalLength() / 1000);
    }

    /** Qualität an alle Hüllen. */
    public void setQuality(int ordinal) {
        flagge.quality = Cloth.Quality.values()[ordinal];
        wake.quality = Wake.Quality.values()[ordinal];
        wege.quality = Route.Quality.values()[ordinal];
        fwQualitaet = ordinal;
        Firework f = firework;
        if (f != null) f.quality = Firework.Quality.values()[ordinal];
        lichter.fest.quality = com.dan.lamp.Lamps.Quality.values()[ordinal];
    }
}
