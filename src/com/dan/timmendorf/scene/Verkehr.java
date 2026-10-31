package com.dan.timmendorf.scene;

import com.dan.route.Crowd;
import com.dan.route.Line;
import com.dan.route.Route;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * Der Verkehr an Land (seit Phase 10a): Autos auf dem Straßennetz ({@code TD_NET_STRASSE}, {@code com.dan.route.Crowd}
 * im Modus Fahren: erlaubte Geschwindigkeit aus OSM {@code maxspeed}, im dichten Verkehr langsamer, Rechtsverkehr) mit
 * Zielen in den Orten, die dort eine Weile parken (dann unsichtbar); Busse nach Fahrplan ({@code com.dan.route.Line}) auf
 * dem Busnetz ({@code TD_NET_BUS}) über die Haltestellen aus OSM: Linie Scharbeutz – Timmendorfer Strand – Niendorf und
 * zurück, alle 30 Minuten von 6 bis 22 Uhr [G: Linienweg und Takt gestaltet, nicht der echte Fahrplan].
 */
public final class Verkehr {
    public volatile int maxAutos = 400;
    public final Crowd autos;
    public final List<Line> busse = new ArrayList<>();
    private final java.util.function.DoubleSupplier stunde;
    private final java.util.function.IntSupplier monat;

    public Verkehr(Route strasse, Route bus, Set<Integer> halte, java.util.function.DoubleSupplier stunde, java.util.function.IntSupplier monat) {
        this.stunde = stunde;
        this.monat = monat;
        autos = new Crowd(strasse, Crowd.Mode.DRIVE, ziele(strasse), 2027);
        autos.reach = 2500;
        if (bus != null && bus.nodeCount() > 2 && !halte.isEmpty()) {
            Line hin = linie("Bus Scharbeutz – Niendorf", bus, halte, Site.SCHARBEUTZ.x() + 300, Site.SCHARBEUTZ.z() + 300, 2300, 950);
            if (hin != null) {
                busse.add(hin);
                int[] r = hin.stops.clone();
                for (int i = 0; i < r.length / 2; i++) { int t = r[i]; r[i] = r[r.length - 1 - i]; r[r.length - 1 - i] = t; }
                Line zur = new Line("Bus Niendorf – Scharbeutz", bus, r, 40 / 3.6, 20, 1800, 6 * 3600 + 900, 22 * 3600, Route.driving());
                if (zur.ok()) busse.add(zur);
            }
        }
    }

    /** Ziele der Autos: 80 Knoten im Straßennetz zwischen Haffkrug und Travemünde, an Wohn- und Nebenstraßen [G]. */
    static List<Crowd.Goal> ziele(Route g) {
        List<Crowd.Goal> l = new ArrayList<>();
        if (g.nodeCount() < 3) return l;
        Random r = new Random(77);
        Set<Integer> da = new HashSet<>();
        for (int k = 0; k < 4000 && l.size() < 80; k++) {
            int i = r.nextInt(g.nodeCount());
            Route.Node n = g.node(i);
            if (n.x() < -2500 || n.x() > 7200 || n.z() < -5800 || n.z() > 4800 || !da.add(i) || g.edgesAt(i).size() < 2) continue;
            boolean strasse = false;
            for (int e : g.edgesAt(i)) strasse |= g.edge(e).kind() == Route.Kind.ROAD;
            if (strasse) l.add(new Crowd.Goal(i, 1, 60, 600, "Parkplatz"));
        }
        return l;
    }

    /**
     * Eine Linie von der Haltestelle nahe A zur nahe B über die Haltestellen bis 700 m hinter der Küste, geordnet längs
     * der Strecke A–B, mindestens 400 m auseinander (die Strandallee entlang) [G].
     */
    static Line linie(String name, Route g, Set<Integer> halte, double ax, double az, double bx, double bz) {
        int a = naechsteHalte(g, halte, ax, az), b = naechsteHalte(g, halte, bx, bz);
        if (a < 0 || b < 0 || a == b) return null;
        double ux = g.node(b).x() - g.node(a).x(), uz = g.node(b).z() - g.node(a).z(), L = Math.hypot(ux, uz);
        ux /= L;
        uz /= L;
        List<double[]> kand = new ArrayList<>();
        for (int n : halte) {
            Route.Node q = g.node(n);
            double t = (q.x() - g.node(a).x()) * ux + (q.z() - g.node(a).z()) * uz;
            if (t < 0 || t > L) continue;
            double[] u = Site.toUtm(q.x(), q.z());
            double d = Site.kuestenAbstand(u[0], u[1]);
            if (d < 0 || d > 700) continue;
            kand.add(new double[]{t, n});
        }
        kand.sort((p, q) -> Double.compare(p[0], q[0]));
        List<Integer> f = new ArrayList<>();
        f.add(a);
        double last = 0;
        for (double[] k : kand) if (k[0] - last > 400 && L - k[0] > 400) { f.add((int) k[1]); last = k[0]; }
        f.add(b);
        int[] s = f.stream().mapToInt(Integer::intValue).toArray();
        Line l = new Line(name, g, s, 40 / 3.6, 20, 1800, 6 * 3600, 22 * 3600, Route.driving());
        return l.ok() ? l : null;
    }

    static int naechsteHalte(Route g, Set<Integer> halte, double x, double z) {
        int best = -1;
        double bd = Double.MAX_VALUE;
        for (int n : halte) {
            double d = Math.hypot(g.node(n).x() - x, g.node(n).z() - z);
            if (d < bd) { bd = d; best = n; }
        }
        return best;
    }

    /** Wie viele Autos nach der Stunde [G]: nachts wenige, tagsüber viele, im Sommer mehr. */
    public static double anteil(double h, int m) {
        double t = h < 5 ? 0.05 : h < 8 ? 0.05 + 0.6 * (h - 5) / 3 : h < 19 ? 0.65 + 0.35 * Math.sin(Math.PI * (h - 8) / 11) : h < 23 ? 0.65 - 0.5 * (h - 19) / 4 : 0.1;
        return t * (m >= 6 && m <= 8 ? 1 : 0.7);
    }

    /** Auf dem Faden der Physik; {@link #netz} und {@link #describe} lesen auf dem Faden der Grafik (gleiche Sperre, Phase 19: der Dauerlauf fand die ConcurrentModification). */
    public synchronized void step(double dt) {
        autos.target((int) Math.round(maxAutos * anteil(stunde.getAsDouble(), monat.getAsInt())));
        autos.step(dt);
    }

    /** Busse zur Uhrzeit (s nach Mitternacht). */
    public List<Line.Vehicle> busse(double sekunde) {
        List<Line.Vehicle> l = new ArrayList<>();
        for (Line b : busse) l.addAll(b.vehicles(sekunde));
        return l;
    }

    /**
     * Fahrzeuge als Netz für {@code bauten.vert/frag} im Umkreis r um das Auge: Autos (Karosserie, Fenster, Räder; Farbe
     * nach dem Zufall des Wagens) und Busse (12 m, weiß mit Fensterband).
     */
    public synchronized Baunetz netz(double ex, double ez, double r, double sekunde) {
        Baunetz n = new Baunetz();
        int[] farben = {Baunetz.LACK, Baunetz.LACK_SCHWARZ, Baunetz.DACH_GRAU, Baunetz.ROT, Baunetz.EISEN, Baunetz.LACK, Baunetz.TUER, Baunetz.GELB};
        for (Crowd.Agent a : autos.agents) {
            if (!a.moving || Math.hypot(a.x - ex, a.z - ez) > r) continue;
            int k = (int) (a.look * 1000);
            int lack = Baunetz.merkmal(farben[k % farben.length], false, k, a.y), glas = Baunetz.merkmal(Baunetz.FENSTER, false, k, a.y),
                    rad = Baunetz.merkmal(Baunetz.LACK_SCHWARZ, false, k, a.y);
            double h = Math.toDegrees(a.heading), L = 4.3, B = 1.8;
            double fx = Math.sin(a.heading), fz = -Math.cos(a.heading), rx = -fz, rz = fx;
            n.quader(a.x, a.y + 0.28, a.z, B, L, 0.72, h, lack);
            n.quader(a.x - fx * 0.25, a.y + 1.0, a.z - fz * 0.25, B * 0.86, L * 0.5, 0.48, h, glas);
            for (int i = 0; i < 4; i++) {
                double s = (i < 2 ? 1 : -1) * L * 0.32, q = ((i & 1) == 0 ? 1 : -1) * B * 0.45;
                n.quader(a.x + fx * s + rx * q, a.y, a.z + fz * s + rz * q, 0.2, 0.62, 0.62, h, rad);
            }
        }
        for (Line.Vehicle v : busse(sekunde)) {
            if (Math.hypot(v.x() - ex, v.z() - ez) > r) continue;
            int weiss = Baunetz.merkmal(Baunetz.LACK, false, 5, v.y()), glas = Baunetz.merkmal(Baunetz.FENSTER, false, 5, v.y()),
                    rot = Baunetz.merkmal(Baunetz.ROT, false, 5, v.y());
            double h = Math.toDegrees(v.heading());
            n.quader(v.x(), v.y() + 0.35, v.z(), 2.55, 12, 2.6, h, weiss);
            n.quader(v.x(), v.y() + 1.35, v.z(), 2.57, 11.6, 1.1, h, glas);
            n.quader(v.x(), v.y() + 0.35, v.z(), 2.57, 12.02, 0.3, h, rot);
        }
        return n;
    }

    /** „Verkehr: 210 Autos (150 fahren, 3 im Stau), 2 Buslinien, 4 Busse unterwegs“ */
    public synchronized String describe(double sekunde) {
        int[] m = autos.moving();
        StringBuilder b = new StringBuilder(String.format(Locale.GERMAN, "Verkehr: %d Autos (%d fahren, %d im Stau, %d Ziele), %d Buslinien, %d Busse unterwegs",
                autos.count(), m[0], m[1], autos.goals.size(), busse.size(), busse(sekunde).size()));
        if (!busse.isEmpty()) b.append(" · ").append(busse.get(0).describe());
        return b.toString();
    }
}
