package com.dan.timmendorf.scene;

import com.dan.sail.Regatta;
import com.dan.sail.Sail;
import com.dan.wake.Wake;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.function.DoubleSupplier;

/**
 * Die Boote der Szene (seit Phase 8a, Werkbuch Abschnitte 9 und 10): was auf der Lübecker Bucht fährt, mit der Physik aus
 * {@code com.dan.sail} und dem Kielwasser aus {@code com.dan.wake}.
 * <ul>
 * <li><b>Der Optimist</b> vor der Seebrücke (Schaustück „Mit dem Optimisten“): segelt ein Dreieck, wendet, halst,
 * krängt; die Kamera kann mitfahren ({@link #imBoot}, {@link #begleitboot}).</li>
 * <li><b>Die Segelschule</b> vor Niendorf (Segelschule Skipper am Hafen, Overpass): acht Optimisten um ein Dreieck von
 * Tonnen, Kinder mit wechselndem Können; ab und zu kentert eines und wird aufgerichtet [G].</li>
 * <li><b>Die Regatta</b> (F13 A): 20 J/70 auf der Bahn aus {@code TD_NET_SEE} (Startschiff, Luv, Lee [G]) vor Timmendorf,
 * Startverfahren, zwei Runden, danach ein neuer Start.</li>
 * <li><b>Fahrtensegler</b>: zwei Fahrtenyachten zwischen Niendorf, Scharbeutz und Travemünde, ein Gaffelschoner
 * (Traditionssegler der Travemünder Woche), zwei Hobie-Kats vor dem Strand, zwei Kiter vor Scharbeutz.</li>
 * <li><b>Die Passat</b> liegt am Priwall, die Segel festgemacht (F12 A).</li>
 * <li><b>Zwei Fähren</b> laufen vom Skandinavienkai die Trave hinab und hinaus nach Nordost (gestaltet [G], Wege von Hand
 * nach dem Gelände: im Fluss 10 kn, draußen 18 kn); ihr Schwell erreicht Minuten später den Strand ({@link Wake#watch}).</li>
 * </ul>
 * Der Wind kommt aus der Verdrahtung, mit Böen, die mit dem Wind ziehen (±18 %, ±6° [G]). Alle Wege und Startplätze liegen
 * im Wasser (Prüfung). Die Klasse kennt keine Grafik; {@code gl.BooteGl} zeichnet, was sie bereitstellt.
 */
public final class Flotte {
    /** Grund über NHN am Ort (Gelände). */
    public interface Grund { double height(double x, double z); }

    /** Ereignisse für HUD und Regie (alle aus dem Faden der Physik). */
    public interface Meldung { void melde(String text); }

    /** Eine Fähre: Weg von Hand, Lage, Kurs, Fahrt, Wartezeiten. */
    public static final class Faehre {
        public final String name;
        public final double length = 200, beam = 30, draft = 6.5;
        public final float[] farbe;
        public double x, z, headingDeg, speed, s;
        /** +1 hinaus, −1 herein, 0 liegt. */
        public int dir;
        double wait, along;
        final double[] cum;
        Faehre(String name, float[] farbe, double wait) {
            this.name = name;
            this.farbe = farbe;
            this.wait = wait;
            cum = new double[ROUTE.length];
            for (int i = 1; i < ROUTE.length; i++) cum[i] = cum[i - 1] + Math.hypot(ROUTE[i][0] - ROUTE[i - 1][0], ROUTE[i][1] - ROUTE[i - 1][1]);
        }
        public double total() { return cum[cum.length - 1]; }
        /** Lage auf dem Weg (x, z) bei Weg s. */
        public double[] at(double s) {
            s = Math.max(0, Math.min(total(), s));
            for (int i = 1; i < cum.length; i++)
                if (s <= cum[i]) {
                    double u = (s - cum[i - 1]) / Math.max(1e-9, cum[i] - cum[i - 1]);
                    return new double[]{ROUTE[i - 1][0] + (ROUTE[i][0] - ROUTE[i - 1][0]) * u, ROUTE[i - 1][1] + (ROUTE[i][1] - ROUTE[i - 1][1]) * u};
                }
            return ROUTE[ROUTE.length - 1].clone();
        }
        /** Seit Phase 15 (Hörproben): ablegen in sec Sekunden, vom Kai aus. */
        public void ablegenIn(double sec) { dir = 0; s = 0; speed = 0; wait = Math.max(0, sec); double[] p = at(0); x = p[0]; z = p[1]; }
        public String zustand() { return dir == 0 ? (s < 1 ? "am Skandinavienkai" : "draußen") : dir > 0 ? "läuft aus" : "läuft ein"; }
    }

    /**
     * Weg der Fähren (Szene x, z): Liegeplatz am Skandinavienkai, die Trave hinab zwischen Travemünde und Priwall, an der
     * Nordmole vorbei, durch das Fahrwasser (Tonnen 1 bis 6, Trave-Tonne) nach Nordost aus dem Fernfeld [G, nach dem
     * Gelände; im Fluss mindestens 1 m Wasser im Raster].
     */
    public static final double[][] ROUTE = utm(new double[][]{{622330, 5978650}, {622250, 5979300}, {622150, 5979800}, {622300, 5980050},
            {622700, 5980260}, {623100, 5980450}, {623450, 5980650}, {623900, 5980850}, {624100, 5981050}, {624700, 5981500}, {625400, 5982900},
            {627500, 5985000}, {630500, 5988300}, {635000, 5993000}});
    /** Bis hier liegt der Weg im Fluss (m): 10 kn, danach 18 kn. */
    public static final double FLUSS = 2700;

    /** Tonnen der Segelschule vor Niendorf, Dreieck des Optimisten vor der Seebrücke, Wege der Fahrtensegler (x, z). */
    public static final double[][] SCHULE = {{2150, 380}, {2450, 180}, {2250, 120}},
            OPTI = {{300, -150}, {500, -350}, {550, -100}},
            YACHT_A = {{2000, 250}, {4000, 0}, {6900, 3500}, {4500, -500}},
            YACHT_B = {{-1200, -3000}, {1500, -1500}, {3500, -1000}, {400, -900}},
            SCHONER = {{1500, -1500}, {4500, -500}, {4000, 0}, {2200, -400}},
            HOBIE = {{-200, -700}, {400, -900}, {900, -500}},
            KITE = {{-1900, -4300}, {-1700, -4900}};
    /** Bahn der Regatta aus {@code TD_NET_SEE}: Startschiff und Luvtonne [G] (UTM). */
    public static final double[] STARTSCHIFF = {617_483.61, 5_985_908.31}, LUV = {618_876.61, 5_987_131.61};

    public final Sail sail = new Sail(), regattaSail = new Sail();
    public final Regatta regatta;
    public final Sail.Boat opti, passat;
    public final List<Sail.Boat> schule = new ArrayList<>(), fahrten = new ArrayList<>();
    public final List<Faehre> faehren = new ArrayList<>();
    public final Wake wake;
    public Meldung meldung = t -> { };
    /** Seit Phase 15: ein Schiff gibt Schall (Fähre legt ab oder kommt zurück; Dauer des langen Tons in s). */
    public interface Horn { void horn(Faehre f, double sekunden); }
    /**
     * Seit Phase 15: Ereignisse für den Klang (aus dem Faden der Physik): Wende, Halse, Anluven, Kentern aller Boote,
     * Signale der Regatta, Hörner der Fähren. null = still.
     */
    public volatile Sail.Events klang;
    public volatile Regatta.Listener signale;
    public volatile Horn hupe;
    private final DoubleSupplier windSpeed, windFrom;
    private final Map<Sail.Boat, Wake.Source> spur = new IdentityHashMap<>();
    private final Map<Sail.Boat, Kurs> kurse = new IdentityHashMap<>();
    private final Map<Faehre, Wake.Source> faehrSpur = new IdentityHashMap<>();
    private final Random rnd = new Random(2026);
    private Grund grund = (x, z) -> -10;
    private double time, raceWait = 30, lastRaceEnd = Double.NaN;
    private int wenden, halsen, kenterungen, schwell;
    private String letzte = "";

    public Flotte(Wake wake, DoubleSupplier windSpeed, DoubleSupplier windFrom) {
        this.wake = wake;
        this.windSpeed = windSpeed;
        this.windFrom = windFrom;
        sail.seed = regattaSail.seed = 2026;
        Sail.Wind w = new Sail.Wind() {
            public double speed(double x, double z, double t) { return windSpeed.getAsDouble() * gust(x, z, t); }
            public double fromDeg(double x, double z, double t) { return windFrom.getAsDouble() + 6 * veer(x, z, t); }
        };
        sail.wind = w;
        regattaSail.wind = w;
        Sail.Events ev = new Sail.Events() {
            @Override public void onTack(Sail.Boat b) { wenden++; Sail.Events k = klang; if (k != null) k.onTack(b); }
            @Override public void onGybe(Sail.Boat b, double v) {
                halsen++;
                if (v > 0.5) note(b.rig.name() + (b.label.isEmpty() ? "" : " " + b.label) + ": Patenthalse");
                Sail.Events k = klang;
                if (k != null) k.onGybe(b, v);
            }
            @Override public void onCapsize(Sail.Boat b) { if (b.rig.canCapsize()) { kenterungen++; note(name(b) + " gekentert"); } Sail.Events k = klang; if (k != null) k.onCapsize(b); }
            @Override public void onRight(Sail.Boat b) { note(name(b) + " wieder aufgerichtet"); Sail.Events k = klang; if (k != null) k.onRight(b); }
            @Override public void onLuff(Sail.Boat b, double strength) { Sail.Events k = klang; if (k != null) k.onLuff(b, strength); }
        };
        sail.events = ev;
        regattaSail.events = ev;
        // der Optimist vor der Seebrücke (Phase 1 an derselben Stelle)
        opti = boot(Sail.OPTIMIST, OPTI, 0.85, "Opti");
        opti.headingDeg = 135;
        opti.speed = 1.6;
        opti.hullColor = new float[]{0.80f, 0.80f, 0.78f};
        // Segelschule: acht Optimisten, Kinder [G]
        for (int i = 0; i < 8; i++) {
            Sail.Boat b = boot(Sail.OPTIMIST, SCHULE, 0.55 + 0.35 * rnd.nextDouble(), "Schule " + (i + 1));
            b.x = SCHULE[0][0] + (rnd.nextDouble() - 0.5) * 120;
            b.z = SCHULE[0][1] + (rnd.nextDouble() - 0.5) * 80;
            b.headingDeg = rnd.nextDouble() * 360;
            kurse.get(b).next = i % 3;
            schule.add(b);
        }
        // Fahrtensegler
        fahrten.add(boot(Sail.CRUISER, YACHT_A, 0.95, "Fahrtenyacht"));
        fahrten.add(boot(Sail.CRUISER, YACHT_B, 0.95, "Fahrtenyacht"));
        fahrten.add(boot(Sail.SCHOONER, SCHONER, 0.95, "Gaffelschoner"));
        Sail.Boat h1 = boot(Sail.HOBIE, HOBIE, 0.9, "Hobie"), h2 = boot(Sail.HOBIE, HOBIE, 0.8, "Hobie");
        h2.x += 120;
        kurse.get(h2).next = 1;
        h1.sailColor = new float[]{0.75f, 0.55f, 0.08f};
        h2.sailColor = new float[]{0.08f, 0.35f, 0.65f};
        fahrten.add(h1);
        fahrten.add(h2);
        Sail.Boat k1 = boot(Sail.KITE, KITE, 0.95, "Kiter"), k2 = boot(Sail.KITE, KITE, 0.85, "Kiter");
        k2.x += 60;
        kurse.get(k2).next = 1;
        k2.sailColor = new float[]{0.10f, 0.55f, 0.15f};
        fahrten.add(k1);
        fahrten.add(k2);
        // Passat am Priwall, Segel fest (F12 A); Kurs längs des Kais [G]
        passat = new Sail.Boat(Sail.PASSAT);
        passat.x = Site.PASSAT.x();
        passat.z = Site.PASSAT.z();
        passat.headingDeg = 340;
        passat.furled = true;
        passat.label = "Passat";
        // Regatta: Bahn aus dem Seenetz, 20 J/70, zwei Runden
        double[] cs = toScene(STARTSCHIFF), lv = toScene(LUV);
        regatta = new Regatta(regattaSail, Regatta.Course.fromMarks(cs[0], cs[1], lv[0], lv[1], 160, 2), 2026);
        regatta.listener = new Regatta.Listener() {   // seit Phase 15: die Signale für den Klang
            @Override public void onSignal(String what, double t) { Regatta.Listener l = signale; if (l != null) l.onSignal(what, t); }
        };
        List<Regatta.Entry> fleet = regatta.add(Sail.J70, 20);
        for (Regatta.Entry e : fleet) {
            Color c = Color.of(e.number);
            e.boat.hullColor = c.hull;
            e.boat.sailColor = c.gennaker;
        }
        for (Regatta.Entry e : regatta.entries) spur.put(e.boat, wake.add("J/70 " + e.number, e.boat.x, e.boat.z, e.boat.headingDeg, e.boat.speed, Sail.J70.lwl(), Sail.J70.beam(), 0.35, false));
        for (Sail.Boat b : alleSegler()) {
            if (spur.containsKey(b)) continue;
            Sail.Rig r = b.rig;
            spur.put(b, wake.add(name(b), b.x, b.z, b.headingDeg, b.speed, r.lwl(), r.kind() == Sail.Kind.CATAMARAN ? r.beam() * 0.3 : r.beam(), Math.max(0.12, 0.05 * r.loa()), false));
        }
        for (Wake.Source s : spur.values()) s.autopilot = false;
        // Fähren
        faehren.add(new Faehre("Ostseefähre nach Trelleborg", new float[]{0.04f, 0.10f, 0.30f}, 20));
        faehren.add(new Faehre("Ostseefähre nach Helsinki", new float[]{0.30f, 0.05f, 0.05f}, 1500));
        for (Faehre f : faehren) {
            double[] p = f.at(0);
            f.x = p[0];
            f.z = p[1];
            f.headingDeg = heading(f, 0, 1);
            Wake.Source s = wake.add(f.name, f.x, f.z, f.headingDeg, 0, f.length * 0.95, f.beam, f.draft, true);
            s.autopilot = false;
            faehrSpur.put(f, s);
        }
        wake.watch(strand());
        wake.onArrival = (src, x, z, hh, t) -> { schwell++; note(String.format(Locale.GERMAN, "Schwell der %s am Strand: %.0f cm", src.name, hh * 200)); };
        // Polaren im Hintergrund vorrechnen (sonst stockt der Faden der Physik beim ersten Schlag)
        Thread pre = new Thread(() -> {
            double ws = windSpeed.getAsDouble();
            for (Sail.Rig r : new Sail.Rig[]{Sail.J70, Sail.OPTIMIST, Sail.CRUISER, Sail.SCHOONER, Sail.HOBIE, Sail.KITE}) {
                sail.vmgCached(r, ws);
                if (r == Sail.J70) regattaSail.vmgCached(r, ws);
            }
        }, "polaren");
        pre.setDaemon(true);
        pre.start();
    }

    /** Farben der Regatta: Rümpfe weiß, grau, blau, Gennaker bunt [G]. */
    record Color(float[] hull, float[] gennaker) {
        static Color of(int n) {
            float[][] h = {{0.82f, 0.82f, 0.80f}, {0.60f, 0.62f, 0.64f}, {0.05f, 0.10f, 0.25f}, {0.82f, 0.82f, 0.80f}, {0.10f, 0.10f, 0.11f}};
            float[][] g = {{0.60f, 0.06f, 0.05f}, {0.05f, 0.20f, 0.60f}, {0.75f, 0.55f, 0.05f}, {0.08f, 0.45f, 0.15f}, {0.55f, 0.10f, 0.45f}, {0.85f, 0.85f, 0.82f}};
            return new Color(h[n % h.length], g[(n * 7) % g.length]);
        }
    }

    private Sail.Boat boot(Sail.Rig rig, double[][] wege, double skill, String label) {
        Sail.Boat b = new Sail.Boat(rig);
        b.skill = skill;
        b.label = label;
        b.x = wege[0][0];
        b.z = wege[0][1];
        b.headingDeg = bearing(wege[0][0], wege[0][1], wege[1 % wege.length][0], wege[1 % wege.length][1]);
        b.speed = Sail.hullSpeed(rig) * 0.4;
        kurse.put(b, new Kurs(wege));
        return b;
    }

    static String name(Sail.Boat b) { return b.label.isEmpty() ? b.rig.name() : b.label.startsWith("Schule") ? "Optimist der Segelschule" : b.label.equals("Opti") ? "der Optimist" : b.label; }

    public static double[][] utm(double[][] en) {
        double[][] o = new double[en.length][];
        for (int i = 0; i < en.length; i++) o[i] = toScene(en[i]);
        return o;
    }

    static double[] toScene(double[] en) { return new double[]{en[0] - Site.E0, Site.N0 - en[1]}; }

    /** Punkte am Strand von Scharbeutz bis Niendorf (aus der OSM-Küste), an denen der Schwell gemeldet wird. */
    static double[][] strand() {
        List<double[]> l = new ArrayList<>();
        for (double[] k : Site.KUESTE) {
            double x = k[0] - Site.E0, z = Site.N0 - k[1];
            if (x > -2500 && x < 2500 && z > -4000 && z < 1500) l.add(new double[]{x, z});
        }
        List<double[]> out = new ArrayList<>();
        int step = Math.max(1, l.size() / 30);
        for (int i = 0; i < l.size(); i += step) out.add(l.get(i));
        return out.toArray(new double[0][]);
    }

    public void setGrund(Grund g) { grund = g; }

    // ------------------------------------------------------------------ Wind mit Böen

    /** Böen: Muster, das mit dem Wind zieht (zwei Wellen, Längen 300 m und 140 m, ±18 %) [G]. */
    public double gust(double x, double z, double t) {
        double from = Math.toRadians(windFrom.getAsDouble()), v = windSpeed.getAsDouble();
        double ax = -Math.sin(from), az = Math.cos(from);
        double along = x * ax + z * az - v * t, across = -x * az + z * ax;
        double g = 0.6 * Math.sin(along / 300 * 2 * Math.PI) * Math.cos(across / 420 * 2 * Math.PI) + 0.4 * Math.sin(along / 140 * 2 * Math.PI + 1.3) * Math.sin(across / 190 * 2 * Math.PI + 0.7);
        return 1 + 0.18 * g;
    }

    /** Drehen des Winds in Böen (−1..1). */
    double veer(double x, double z, double t) {
        double from = Math.toRadians(windFrom.getAsDouble()), v = windSpeed.getAsDouble();
        double along = x * -Math.sin(from) + z * Math.cos(from) - v * t;
        return Math.sin(along / 520 * 2 * Math.PI + 0.4);
    }

    // ------------------------------------------------------------------ Schritt

    /** Alle Segelboote, die sich bewegen (ohne Passat). */
    public List<Sail.Boat> alleSegler() {
        List<Sail.Boat> l = new ArrayList<>();
        l.add(opti);
        l.addAll(schule);
        l.addAll(fahrten);
        for (Regatta.Entry e : regatta.entries) l.add(e.boat);
        return l;
    }

    /** Alles, was gezeichnet wird: Segelboote und die Passat. */
    public List<Sail.Boat> zumZeichnen() {
        List<Sail.Boat> l = alleSegler();
        l.add(passat);
        return Collections.unmodifiableList(l);
    }

    public void step(double dt) {
        time += dt;
        double ws = windSpeed.getAsDouble();
        regatta.tacticsWind = ws;
        if (restart) {
            restart = false;
            lastRaceEnd = Double.NaN;
            regatta.start(300);
            note("Regatta: neuer Lauf von Hand, Start in 5 Minuten");
        }
        if (regatta.entries.size() > 0) {
            if (Double.isNaN(regatta.toGun()) && time >= raceWait) { regatta.start(300); note("Regatta: Ankündigung, Start in 5 Minuten"); }
            else if (!Double.isNaN(regatta.toGun())) {
                boolean over = regatta.over() || regatta.toGun() < -3600;
                if (over && Double.isNaN(lastRaceEnd)) lastRaceEnd = time;
                if (over && time - lastRaceEnd > 180) { lastRaceEnd = Double.NaN; regatta.start(300); note("Regatta: neuer Lauf, Start in 5 Minuten"); }
            }
            regatta.step(dt);
        }
        for (Sail.Boat b : alleSegler()) {
            Kurs k = kurse.get(b);
            if (k == null) continue;
            k.steer(b, ws);
            sail.step(b, dt);
            // Patzer der Kinder: im Mittel einmal in 25 min je Boot [G]
            if (schule.contains(b) && !b.capsized && rnd.nextDouble() < dt / (25 * 60) * (1.2 - b.skill)) sail.capsize(b);
        }
        for (Faehre f : faehren) faehre(f, dt);
        for (Map.Entry<Sail.Boat, Wake.Source> e : spur.entrySet()) {
            Sail.Boat b = e.getKey();
            wake.moveTo(e.getValue(), b.x, b.z, b.headingDeg, b.capsized ? 0 : b.speed);
        }
        for (Map.Entry<Faehre, Wake.Source> e : faehrSpur.entrySet()) {
            Faehre f = e.getKey();
            wake.moveTo(e.getValue(), f.x, f.z, f.headingDeg, f.speed);
        }
        wake.step(dt);
    }

    /** Neuer Lauf der Regatta: Startverfahren ab jetzt (5 min). */
    public void neuerStart() { restart = true; }

    private volatile boolean restart;

    /** Die Fähre fährt ihren Weg: im Fluss 10 kn, draußen 18 kn, am Kai und draußen eine Weile liegen. */
    void faehre(Faehre f, double dt) {
        if (f.dir == 0) {
            f.speed = Math.max(0, f.speed - dt * 0.3);
            f.wait -= dt;
            if (f.wait <= 0) {
                f.dir = f.s < 1 ? 1 : -1;
                note(f.name + (f.dir > 0 ? " legt am Skandinavienkai ab" : " kommt zurück"));
                Horn h = hupe;
                if (h != null) h.horn(f, 5);   // seit Phase 15: ein langer Ton beim Ablegen (4 bis 6 s, KVR Regel 32)
            }
            return;
        }
        double target = (f.dir > 0 ? f.s : f.total() - f.s) < 300 ? 3 : f.s < FLUSS ? 5.1 : 9.3;
        if ((f.dir > 0 ? f.total() - f.s : f.s) < 800) target = Math.min(target, 3);
        f.speed += Math.max(-0.05, Math.min(0.04, target - f.speed)) * dt * 2;
        f.s += f.dir * f.speed * dt;
        if (f.s <= 0 || f.s >= f.total()) {
            f.s = Math.max(0, Math.min(f.total(), f.s));
            f.dir = 0;
            f.wait = f.s < 1 ? 1800 : 900;
        }
        double[] p = f.at(f.s);
        f.x = p[0];
        f.z = p[1];
        double want = heading(f, f.s, f.dir);
        double d = ((want - f.headingDeg) % 360 + 540) % 360 - 180;
        f.headingDeg = (f.headingDeg + Math.max(-2 * dt, Math.min(2 * dt, d)) + 360) % 360;
    }

    static double heading(Faehre f, double s, int dir) {
        double[] a = f.at(s), b = f.at(s + (dir >= 0 ? 120 : -120));
        if (Math.hypot(b[0] - a[0], b[1] - a[1]) < 1) return f.headingDeg;
        return bearing(a[0], a[1], b[0], b[1]);
    }

    static double bearing(double x0, double z0, double x1, double z1) { return (Math.toDegrees(Math.atan2(x1 - x0, -(z1 - z0))) + 360) % 360; }

    void note(String s) {
        letzte = String.format(Locale.GERMAN, "%d:%02d %s", (int) time / 60, (int) time % 60, s);
        meldung.melde(s);
    }

    // ------------------------------------------------------------------ Steuermann

    /**
     * Ein Steuermann, der Tonnen der Reihe nach ansteuert: liegt die Tonne zu hoch am Wind, kreuzt er in einer Gasse
     * (Wende am Rand), liegt sie platt vor dem Wind, halst er im Zickzack; vor flachem Wasser wendet er [G].
     */
    final class Kurs {
        final double[][] wege;
        int next = 1;
        double side = 1, lastTurn = -1e9;
        Kurs(double[][] wege) { this.wege = wege; }

        void steer(Sail.Boat b, double ws) {
            if (b.capsized) return;
            double[] wp = wege[next % wege.length];
            double dx = wp[0] - b.x, dz = wp[1] - b.z, d = Math.hypot(dx, dz);
            double arrive = Math.max(15, 3 * b.rig.loa());
            if (d < arrive) { next = (next + 1) % wege.length; wp = wege[next]; dx = wp[0] - b.x; dz = wp[1] - b.z; d = Math.hypot(dx, dz); }
            double from = windFrom.getAsDouble(), brg = bearing(b.x, b.z, wp[0], wp[1]);
            double twa = ((brg - from) % 360 + 540) % 360 - 180;
            double[] v = sail.vmgCached(b.rig, ws);
            double up = v[0] + 2, dn = Math.min(170, v[3]);
            double h = brg;
            double gasse = Math.max(40, Math.min(250, 0.35 * d)) * (b.rig.kind() == Sail.Kind.SHIP ? 2 : 1);
            if (Math.abs(twa) < up) {
                // kreuzen: auf dem jetzigen Bug bleiben, bis die Tonne über die Gasse hinaus seitlich liegt
                double cur = b.windSide();
                double lat = Math.sin(Math.toRadians(twa)) * d;          // + Tonne rechts vom Wind
                if (time - lastTurn > 15 && ((cur > 0 && lat > gasse) || (cur < 0 && lat < -gasse))) { cur = -cur; lastTurn = time; }
                side = cur;
                h = from - side * up;
            } else if (Math.abs(twa) > dn && b.rig.plan() != Sail.Plan.SQUARE) {
                double cur = b.windSide();
                double lat = Math.sin(Math.toRadians(180 - Math.abs(twa))) * d * Math.signum(twa);
                if (time - lastTurn > 20 && ((cur > 0 && lat < -gasse) || (cur < 0 && lat > gasse))) { cur = -cur; lastTurn = time; }
                h = from + 180 + cur * (180 - dn);
            }
            // flaches Wasser voraus: anders herum
            double ahead = 3 * b.rig.loa() + 10, hr = Math.toRadians(h);
            double need = b.rig.finDepth() + 0.6;
            if (grund.height(b.x + Math.sin(hr) * ahead, b.z - Math.cos(hr) * ahead) > -need && time - lastTurn > 10) {
                next = (next + 1) % wege.length;
                lastTurn = time;
            }
            b.targetHeadingDeg = (h % 360 + 360) % 360;
        }
    }

    // ------------------------------------------------------------------ Kamera

    /**
     * Mitfahrt im Boot (Schaustück „Mit dem Optimisten“): das Auge sitzt in Luv am Heck, 0,9 m über dem Wasser, krängt mit;
     * {x, y, z, Kurs, Nicken, Rollen} für {@code Camera.startMitfahrt}.
     */
    public static double[] imBoot(Sail.Boat b, double water) {
        double h = Math.toRadians(b.headingDeg), fx = Math.sin(h), fz = -Math.cos(h), rx = Math.cos(h), rz = Math.sin(h);
        double side = b.windSide(), ph = Math.toRadians(-b.rollDeg);
        double back = b.rig.loa() * 0.42, q = side * b.rig.beam() * 0.35, up = 0.9;
        // gerollt um die Längsachse wie in BoatShape (Steuerbord unten: rollDeg > 0)
        double qq = q * Math.cos(ph) - up * Math.sin(ph), yy = q * Math.sin(ph) + up * Math.cos(ph);
        return new double[]{b.x - fx * back + rx * qq, water + Math.max(0.35, yy), b.z - fz * back + rz * qq, b.headingDeg, 0, b.rollDeg};
    }

    /** Begleitboot: 12 m achteraus und 9 m in Lee, 2,5 m hoch, Blick auf das Boot. */
    public static double[] begleitboot(Sail.Boat b, double water) {
        double h = Math.toRadians(b.headingDeg), fx = Math.sin(h), fz = -Math.cos(h), rx = Math.cos(h), rz = Math.sin(h);
        double lee = -b.windSide(), back = 6 + 2 * b.rig.loa(), aside = 4 + 1.4 * b.rig.loa();
        if (b.rig.plan() == Sail.Plan.KITE) { back = 25; aside = 15; }
        double x = b.x - fx * back + rx * aside * lee, z = b.z - fz * back + rz * aside * lee;
        return new double[]{x, water + 2.5 + 0.1 * b.rig.loa(), z, bearing(x, z, b.x, b.z), -4, 0};
    }

    /** Das führende Boot der Regatta (oder das erste). */
    public Sail.Boat regattaSpitze() {
        List<Regatta.Entry> r = regatta.ranking();
        for (Regatta.Entry e : r) if (e.leg != Regatta.Leg.DONE) return e.boat;
        return r.isEmpty() ? opti : r.get(0).boat;
    }

    // ------------------------------------------------------------------ Rümpfe für die See

    /** Rümpfe nahe (x, z), die die See ausspart: je {x, z, Kurs, halbe Länge, halbe Breite, Völligkeit}, höchstens n, nächste zuerst. */
    public List<double[]> rumpfe(double x, double z, int n, double maxDist) {
        List<double[]> l = new ArrayList<>();
        for (Sail.Boat b : zumZeichnen()) {
            if (b.capsized || b.rig.kind() == Sail.Kind.CATAMARAN || b.rig.kind() == Sail.Kind.KITE) continue;
            l.add(new double[]{b.x, b.z, b.headingDeg, b.rig.lwl() / 2, b.rig.beam() * 0.45, b.rig.kind() == Sail.Kind.SHIP ? 3 : 2});
        }
        for (Faehre f : faehren) l.add(new double[]{f.x, f.z, f.headingDeg, f.length * 0.48, f.beam / 2, 3.2});
        l.removeIf(r -> Math.hypot(r[0] - x, r[1] - z) > maxDist);
        l.sort((a, b) -> Double.compare(Math.hypot(a[0] - x, a[1] - z), Math.hypot(b[0] - x, b[1] - z)));
        return l.size() > n ? new ArrayList<>(l.subList(0, n)) : l;
    }

    // ------------------------------------------------------------------ Anzeigen

    public double time() { return time; }
    /** Die Quelle des Kielwassers eines Boots (null für die Passat). */
    public Wake.Source spur(Sail.Boat b) { return spur.get(b); }
    public int wenden() { return wenden; }
    public int halsen() { return halsen; }
    public int kenterungen() { return kenterungen; }
    public int schwell() { return schwell; }
    public String letzte() { return letzte; }

    /** „Boote: 36 Segler, 2 Fähren, Passat am Priwall · 412 Wenden, 96 Halsen, 3× gekentert · Optimist 2,1 kn …“ */
    public String zeile() {
        int n = alleSegler().size();
        return String.format(Locale.GERMAN, "Boote: %d Segler, %d Fähren (%s, %s), Passat am Priwall · %d Wenden, %d Halsen, %d× gekentert, %d× Schwell am Strand",
                n, faehren.size(), faehren.get(0).zustand(), faehren.get(1).zustand(), wenden, halsen, kenterungen, schwell);
    }

    public String optiZeile() { return "Optimist: " + sail.describe(opti); }
}
