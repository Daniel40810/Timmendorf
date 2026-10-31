package com.dan.timmendorf.scene;

import com.dan.figure.FigureMesh;
import com.dan.figure.Outfit;
import com.dan.figure.Person;
import com.dan.route.Crowd;
import com.dan.route.Route;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.function.DoubleBinaryOperator;

/**
 * Die Menschen der Szene (seit Phase 10a, F22 A): Spaziergänger auf dem Fußnetz ({@code com.dan.route.Crowd} auf
 * {@code TD_NET_FUSS}) mit Zielen an Promenade, Seebrücken, Wolkenlos und Niendorfer Hafen; Zuschauer auf der Schleife,
 * die zur Regatta schauen; Badegäste in den Strandkörben, auf Handtüchern und am Wasser; Leute auf der Kaimauer im
 * Niendorfer Hafen, die Beine über dem Wasser. Gezeichnet mit {@code com.dan.figure} (Sitzen, Liegen, Badezeug).
 * <p>
 * Wie viele es sind, folgt aus Monat und Stunde [G nach dem Eindruck vor Ort]: am Nachmittag im Hochsommer am meisten,
 * nachts fast niemand, im Winter ein Achtel. Badegäste nur in der Saison der Strandkörbe; ob ein Korb besetzt ist,
 * hängt nur vom Korb und der Stunde ab (springt nicht von Bild zu Bild).
 * <p>
 * Seit Phase 12 die Zuschauerströme zum Feuerwerk: Ziele „Feuerwerk“ an Promenade, Seebrücke und Schleife bis 1,2 km um
 * die Seebrücke mit Blick zum Ponton, ohne Gewicht, bis {@link #zuschauer} steigt (ab 50 min vorher); dann kommen mehr
 * Menschen, sie gehen dorthin und bleiben bis zum Ende, danach brechen alle in fünf Minuten auf. Dazu Stehende am Strand
 * vor den Körben, das Gesicht zum Ponton.
 */
public final class Menschen {
    /** Gänger im Fußnetz höchstens (Qualität Hoch); die App setzt es nach der Stufe. */
    public volatile int maxGaenger = 1200;
    public final Crowd crowd;
    /** Badegäste und Sitzende (fest an ihrem Ort, neu je Stunde). */
    private volatile List<Person> ruhend = List.of();
    private final Map<Integer, Person> person = new HashMap<>();
    private final List<Bauwerke.Korb> koerbe;
    private final DoubleBinaryOperator grund;
    private final java.util.function.Supplier<LocalDate> datum;
    private final java.util.function.DoubleSupplier stunde;
    private int ruhendStunde = -1, ruhendTag = -1;
    private volatile List<Person> gaenger = List.of();
    /** Zähler fürs HUD: in Körben, auf Handtüchern, am Wasser, auf der Kaimauer. */
    public final int[] zahl = new int[4];

    /** Seit Phase 12: Anteil 0..1 des Andrangs zum Feuerwerk (Wiring setzt ihn nach der Uhr), Ponton als Blickpunkt. */
    public volatile double zuschauer;
    private final double[] ponton;
    private final java.util.BitSet feuerwerkZiel = new java.util.BitSet();
    private double zuschauerVorher;
    private volatile List<Person> strand = List.of();
    private int strandStufe = -1;

    /** Kaimauer im Niendorfer Hafen: Kante (x, z) von West nach Ost, 4 m nördlich der Kutter [G nach DOP20]. */
    static final double[][] KAIMAUER = {{1993, 787}, {2147, 833}};

    public Menschen(Route fuss, List<Bauwerke.Korb> koerbe, DoubleBinaryOperator grund, double[] regatta,
                    java.util.function.Supplier<LocalDate> datum, java.util.function.DoubleSupplier stunde) {
        this(fuss, koerbe, grund, regatta, null, datum, stunde);
    }

    /** Seit Phase 12 mit dem Ponton des Feuerwerks (null: keine Zuschauer zum Feuerwerk). */
    public Menschen(Route fuss, List<Bauwerke.Korb> koerbe, DoubleBinaryOperator grund, double[] regatta, double[] ponton,
                    java.util.function.Supplier<LocalDate> datum, java.util.function.DoubleSupplier stunde) {
        this.koerbe = koerbe;
        this.grund = grund;
        this.datum = datum;
        this.stunde = stunde;
        this.ponton = ponton;
        List<Crowd.Goal> z = ziele(fuss, regatta);
        int vorher = z.size();
        if (ponton != null) feuerwerkZiele(z, fuss, ponton);
        crowd = new Crowd(fuss, Crowd.Mode.WALK, z, 2026);
        crowd.reach = 700;
        for (int i = vorher; i < z.size(); i++) { feuerwerkZiel.set(i); crowd.weight(i, 0); }
    }

    /**
     * Seit Phase 12: Plätze mit Blick zum Feuerwerk [G]: Knoten von Promenade, Fußwegen am Strand und Seebrücke bis 1,2 km
     * um die Seebrücke und bis 60 m hinter der Küste, mit 40 m Abstand; Verweilen 30 bis 45 min.
     */
    static void feuerwerkZiele(List<Crowd.Goal> l, Route g, double[] ponton) {
        List<double[]> gesetzt = new ArrayList<>();
        for (int i = 0; i < g.nodeCount(); i++) {
            Route.Node n = g.node(i);
            if (Math.hypot(n.x(), n.z()) > 1200) continue;
            boolean steg = false, weg = false;
            for (int e : g.edgesAt(i)) {
                Route.Kind k = g.edge(e).kind();
                steg |= k == Route.Kind.PIER;
                weg |= k == Route.Kind.PROMENADE || k == Route.Kind.FOOTWAY;
            }
            if (!steg) {
                double[] u = Site.toUtm(n.x(), n.z());
                double d = Site.kuestenAbstand(u[0], u[1]);
                if (!weg || d < -5 || d > 60) continue;
            }
            boolean nah = false;
            for (double[] q : gesetzt) if (Math.hypot(q[0] - n.x(), q[1] - n.z()) < 40) { nah = true; break; }
            if (nah) continue;
            gesetzt.add(new double[]{n.x(), n.z()});
            l.add(new Crowd.Goal(i, steg ? 3 : 1, 1800, 2700, ponton[0], ponton[1], "Feuerwerk"));
        }
    }

    /** Seit Phase 12: wie viele Ziele „Feuerwerk“ es gibt und wie viele dort gerade schauen. */
    public int[] feuerwerk() { return new int[]{feuerwerkZiel.cardinality(), crowd.dwelling(feuerwerkZiel::get), strand.size()}; }

    /**
     * Ziele im Fußnetz zwischen Scharbeutz und Niendorf [G]: Seebrücke (Landende und Schleife, dort Zuschauer mit Blick
     * zur Regatta), Wolkenlos, Niendorfer Hafen, Seebrücke Niendorf, dazu alle 150 m ein Punkt der Promenade (bis 120 m
     * hinter der Küste) als Zwischenziel.
     */
    static List<Crowd.Goal> ziele(Route g, double[] regatta) {
        List<Crowd.Goal> l = new ArrayList<>();
        if (g.nodeCount() < 3) return l;
        java.util.Set<Route.Kind> fuss = java.util.EnumSet.of(Route.Kind.FOOTWAY, Route.Kind.PROMENADE, Route.Kind.PIER, Route.Kind.STEPS);
        add(l, g, 0, 0, fuss, 5, 30, 180, Double.NaN, Double.NaN, "Seebrücke");
        add(l, g, Site.SCHLEIFE.x(), Site.SCHLEIFE.z(), java.util.Set.of(Route.Kind.PIER), 5, 120, 900, regatta[0], regatta[1], "Schleife");
        add(l, g, Site.WOLKENLOS.x(), Site.WOLKENLOS.z(), fuss, 3, 300, 1800, Double.NaN, Double.NaN, "Wolkenlos");
        add(l, g, 2080, 800, fuss, 3, 120, 900, 2080, 900, "Niendorfer Hafen");
        add(l, g, 3290, 720, fuss, 2, 60, 600, 3600, 300, "Seebrücke Niendorf");
        add(l, g, Site.SCHARBEUTZ.x(), Site.SCHARBEUTZ.z(), fuss, 2, 60, 600, Double.NaN, Double.NaN, "Seebrücke Scharbeutz");
        // Promenade: Knoten der Fußwege bis 120 m hinter der Küste, mit 150 m Abstand
        List<double[]> gesetzt = new ArrayList<>();
        for (int i = 0; i < g.nodeCount(); i++) {
            Route.Node n = g.node(i);
            if (n.x() < -2200 || n.x() > 3700) continue;
            double[] u = Site.toUtm(n.x(), n.z());
            double d = Site.kuestenAbstand(u[0], u[1]);
            if (d < 5 || d > 120) continue;
            boolean nah = false;
            for (double[] q : gesetzt) if (Math.hypot(q[0] - n.x(), q[1] - n.z()) < 150) { nah = true; break; }
            if (nah) continue;
            boolean promenade = false;
            for (int e : g.edgesAt(i)) promenade |= g.edge(e).kind() == Route.Kind.PROMENADE || g.edge(e).kind() == Route.Kind.FOOTWAY;
            if (!promenade) continue;
            gesetzt.add(new double[]{n.x(), n.z()});
            l.add(new Crowd.Goal(i, 1, 0, 90, "Promenade"));
        }
        return l;
    }

    private static void add(List<Crowd.Goal> l, Route g, double x, double z, java.util.Set<Route.Kind> kinds, double w, double d0, double d1, double lx, double lz, String name) {
        int n = g.nearest(x, z, kinds);
        if (n >= 0 && Math.hypot(g.node(n).x() - x, g.node(n).z() - z) < 80) l.add(new Crowd.Goal(n, w, d0, d1, lx, lz, name));
    }

    // ------------------------------------------------------------------ wie viele

    /** Anteil 0..1 nach der Stunde (Ortszeit): nachts fast niemand, Nachmittag am meisten, abends die Promenade [G]. */
    public static double tag(double h) {
        if (h < 6 || h >= 23.5) return 0.02;
        if (h < 9) return 0.05 + 0.25 * (h - 6) / 3;
        if (h < 12) return 0.3 + 0.4 * (h - 9) / 3;
        if (h < 14) return 0.7 + 0.3 * (h - 12) / 2;
        if (h < 18) return 1;
        if (h < 21) return 1 - 0.4 * (h - 18) / 3;
        return 0.6 - 0.55 * (h - 21) / 2.5;
    }

    /** Anteil 0..1 nach dem Monat [G]. */
    public static double saison(int m) {
        return switch (m) {
            case 7, 8 -> 1;
            case 6 -> 0.8;
            case 5, 9 -> 0.5;
            case 4, 10 -> 0.3;
            default -> 0.12;
        };
    }

    /** Badegäste: nur warm am Tag (10 bis 19 Uhr am meisten) und in der Saison der Körbe [G]. */
    public static double strand(LocalDate d, double h) {
        if (!Bauwerke.saison(d)) return 0;
        int m = d.getMonthValue();
        double s = m == 7 || m == 8 ? 1 : m == 6 ? 0.6 : 0.25;
        double t = h < 9 || h > 20 ? 0 : h < 11 ? (h - 9) / 2 : h > 18 ? (20 - h) / 2 : 1;
        return s * t;
    }

    // ------------------------------------------------------------------ Schritt

    /** Ein Schritt der Gänger und, wenn die Stunde gewechselt hat, die Badegäste neu. */
    public void step(double dt) {
        LocalDate d = datum.get();
        double h = stunde.getAsDouble();
        int soll = (int) Math.round(maxGaenger * tag(h) * saison(d.getMonthValue()));
        // seit Phase 12: Andrang zum Feuerwerk
        double zs = Math.max(0, Math.min(1, zuschauer));
        if (zs != zuschauerVorher) {
            for (int i = feuerwerkZiel.nextSetBit(0); i >= 0; i = feuerwerkZiel.nextSetBit(i + 1)) crowd.weight(i, 25 * zs * crowd.goals.get(i).weight());
            if (zs == 0 && zuschauerVorher > 0) crowd.hurry(feuerwerkZiel::get, 300);
            zuschauerVorher = zs;
        }
        if (zs > 0) soll = Math.max(soll, (int) Math.round(maxGaenger * (0.35 + 0.65 * zs) * Math.max(0.3, saison(d.getMonthValue()))));
        crowd.target(soll);
        crowd.step(dt);
        // Personen zu den Gängern
        List<Person> l = new ArrayList<>(crowd.agents.size());
        java.util.Set<Integer> da = new java.util.HashSet<>();
        for (Crowd.Agent a : crowd.agents) {
            Person p = person.computeIfAbsent(a.id, id -> neu(id, a.look, d.getMonthValue(), false));
            double ds = a.speed * dt;
            p.x = a.x;
            p.y = a.y;
            p.z = a.z;
            p.heading = a.heading;
            p.speed = a.speed;
            p.gait += (Math.min(1.3, a.speed / 1.25) - p.gait) * Math.min(1, dt * 4);
            p.advance(ds);
            p.look = a.moving ? 0 : 0.4 * Math.sin(crowd.time() * 0.3 + a.id);
            da.add(a.id);
            l.add(p);
        }
        person.keySet().retainAll(da);
        gaenger = l;
        int std = (int) Math.floor(h);
        int stufe = (int) Math.round(zs * 20);
        if (stufe != strandStufe) { strandStufe = stufe; strand = strandZuschauer(d, zs); }
        if (std != ruhendStunde || d.getDayOfYear() != ruhendTag) {
            ruhendStunde = std;
            ruhendTag = d.getDayOfYear();
            ruhend = ruhende(d, h);
        }
    }

    private static Person neu(int id, double look, int monat, boolean baden) {
        SplittableRandom r = new SplittableRandom(id * 7919L + 17);
        boolean kind = r.nextDouble() < 0.15;
        Person p = new Person(id, kind ? Person.Role.CHILD : Person.Role.ADULT, Outfit.beach(monat, r, kind, baden));
        p.scale = kind ? 0.58 + 0.17 * r.nextDouble() : 0.92 + 0.14 * r.nextDouble();
        p.phase = look;
        return p;
    }

    /**
     * Badegäste und Sitzende zur Stunde: je Korb mit der Wahrscheinlichkeit des Strandtags ein oder zwei im Korb, davor
     * oft einer auf dem Handtuch, manche am Wasser oder darin bis zu den Knien; auf der Kaimauer in Niendorf sitzen bis zu
     * zwölf mit den Beinen über dem Wasser.
     */
    List<Person> ruhende(LocalDate d, double h) {
        java.util.Arrays.fill(zahl, 0);
        List<Person> l = new ArrayList<>();
        int m = d.getMonthValue();
        double p = strand(d, h);
        int id = 1_000_000;
        if (p > 0) {
            for (int i = 0; i < koerbe.size(); i++) {
                Bauwerke.Korb k = koerbe.get(i);
                SplittableRandom r = new SplittableRandom(k.kennzahl() * 31L + i * 1_000_003L + d.getDayOfYear() * 7L);
                double a = Math.toRadians(k.richtung()), fx = Math.sin(a), fz = -Math.cos(a), rx = -fz, rz = fx;
                if (r.nextDouble() > p * 0.55) continue;
                int n = r.nextDouble() < 0.45 ? 2 : 1;
                for (int j = 0; j < n; j++) {
                    Person q = neu(id++, r.nextDouble(), m, r.nextDouble() < 0.6);
                    double side = n == 1 ? 0 : (j == 0 ? -0.3 : 0.3);
                    q.x = k.x() + fx * 0.02 + rx * side;
                    q.z = k.z() + fz * 0.02 + rz * side;
                    q.y = k.y() + Bauwerke.KORB_SITZ - FigureMesh.SEAT * q.scale;
                    q.heading = a;
                    q.sit = 1;
                    q.look = (r.nextDouble() - 0.5) * 0.8;
                    l.add(q);
                    zahl[0]++;
                }
                if (r.nextDouble() < 0.5) {   // auf dem Handtuch vor dem Korb, Füße zur See
                    Person q = neu(id++, r.nextDouble(), m, true);
                    double off = 2.4 + r.nextDouble() * 0.8, lat = (r.nextDouble() - 0.5) * 1.2;
                    q.x = k.x() + fx * off + rx * lat;
                    q.z = k.z() + fz * off + rz * lat;
                    q.y = grund.applyAsDouble(q.x, q.z);
                    q.heading = a + (r.nextDouble() - 0.5) * 0.3;
                    q.lie = 1;
                    l.add(q);
                    zahl[1]++;
                }
                if (r.nextDouble() < 0.22) {  // am Wasser oder darin (der Grund liegt dort unter 0 m)
                    Person q = neu(id++, r.nextDouble(), m, true);
                    double off = 14 + r.nextDouble() * 16, lat = (r.nextDouble() - 0.5) * 4;
                    q.x = k.x() + fx * off + rx * lat;
                    q.z = k.z() + fz * off + rz * lat;
                    q.y = Math.max(grund.applyAsDouble(q.x, q.z), -0.6);
                    q.heading = a + (r.nextDouble() - 0.5) * 1.2;
                    q.wet = Math.max(0, -q.y);
                    l.add(q);
                    zahl[2]++;
                }
            }
        }
        // Kaimauer im Niendorfer Hafen
        int nk = (int) Math.round(12 * tag(h) * saison(m));
        double ax = KAIMAUER[0][0], az = KAIMAUER[0][1], bx = KAIMAUER[1][0], bz = KAIMAUER[1][1], len = Math.hypot(bx - ax, bz - az);
        double dx = (bx - ax) / len, dz = (bz - az) / len, wx = -dz, wz = dx;   // zum Wasser (nach Süden)
        SplittableRandom r = new SplittableRandom(d.getDayOfYear() * 13L + (long) Math.floor(h));
        for (int i = 0; i < nk; i++) {
            Person q = neu(id++, r.nextDouble(), m, false);
            double s = r.nextDouble() * len;
            double ex = ax + dx * s, ez = az + dz * s;
            double oben = grund.applyAsDouble(ex - wx * 1.5, ez - wz * 1.5);
            q.x = ex + wx * 0.25;
            q.z = ez + wz * 0.25;
            q.y = oben - FigureMesh.SEAT * q.scale;
            q.heading = Math.atan2(wx, -wz) + (r.nextDouble() - 0.5) * 0.4;
            q.sit = 1;
            l.add(q);
            zahl[3]++;
        }
        return l;
    }

    /**
     * Seit Phase 12: Stehende am Strand vor den Körben bis 900 m um die Seebrücke, 6 bis 20 m vor dem Korb zur See, das
     * Gesicht zum Ponton; bis 700 bei vollem Andrang [G]. Fest je Korb (springen nicht).
     */
    List<Person> strandZuschauer(LocalDate d, double zs) {
        if (zs <= 0 || ponton == null) return List.of();
        List<Person> l = new ArrayList<>();
        int id = 3_000_000, m = d.getMonthValue();
        int max = (int) Math.round(700 * zs);
        for (int i = 0; i < koerbe.size() && l.size() < max; i++) {
            Bauwerke.Korb k = koerbe.get(i);
            if (Math.hypot(k.x(), k.z()) > 900) continue;
            SplittableRandom r = new SplittableRandom(k.kennzahl() * 53L + i * 999_983L);
            if (r.nextDouble() > 0.6) continue;
            double a = Math.toRadians(k.richtung()), fx = Math.sin(a), fz = -Math.cos(a);
            int n = 1 + (r.nextDouble() < 0.4 ? 1 : 0) + (r.nextDouble() < 0.15 ? 1 : 0);
            for (int j = 0; j < n && l.size() < max; j++) {
                Person q = neu(id++, r.nextDouble(), m, false);
                double off = 6 + r.nextDouble() * 14, lat = (r.nextDouble() - 0.5) * 3;
                q.x = k.x() + fx * off - fz * lat;
                q.z = k.z() + fz * off + fx * lat;
                q.y = grund.applyAsDouble(q.x, q.z);
                if (q.y < 0.2) continue;   // nicht im Wasser
                q.heading = Math.atan2(ponton[0] - q.x, -(ponton[1] - q.z)) + (r.nextDouble() - 0.5) * 0.3;
                q.look = (r.nextDouble() - 0.5) * 0.3;
                l.add(q);
            }
        }
        return l;
    }

    /** Alle Figuren zum Zeichnen (Gänger, Ruhende, seit Phase 12 die Zuschauer am Strand). */
    public List<Person> alle() {
        List<Person> l = new ArrayList<>(gaenger);
        l.addAll(ruhend);
        l.addAll(strand);
        return l;
    }

    public List<Person> gaenger() { return gaenger; }
    public List<Person> ruhend() { return ruhend; }

    /** „Menschen: 820 auf den Wegen (12 im Stau), 1 410 in Körben, 380 auf Handtüchern, 90 am Wasser, 9 auf der Kaimauer“ */
    public String describe() {
        int[] mv = crowd.moving();
        String fw = zuschauer > 0 ? String.format(Locale.GERMAN, " · zum Feuerwerk %.0f %%: %,d schauen von %d Plätzen, %d am Strand", zuschauer * 100,
                crowd.dwelling(feuerwerkZiel::get), feuerwerkZiel.cardinality(), strand.size()) : "";
        return String.format(Locale.GERMAN, "Menschen: %,d auf den Wegen (%,d unterwegs, %d im Stau, %d Ziele), %,d in Körben, %,d auf Handtüchern, %d am Wasser, %d auf der Kaimauer",
                crowd.count(), mv[0], mv[1], crowd.goals.size(), zahl[0], zahl[1], zahl[2], zahl[3]) + fw;
    }
}
