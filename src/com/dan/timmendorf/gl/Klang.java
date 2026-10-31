package com.dan.timmendorf.gl;

import com.dan.firework.Firework;
import com.dan.sail.Regatta;
import com.dan.sail.Sail;
import com.dan.soundscape.AudioOut;
import com.dan.soundscape.Crowd;
import com.dan.soundscape.Flap;
import com.dan.soundscape.Hum;
import com.dan.soundscape.Rig;
import com.dan.soundscape.Soundscape;
import com.dan.soundscape.Soundscape.Kind;
import com.dan.timmendorf.scene.Bauwerke;
import com.dan.timmendorf.scene.Flotte;
import com.dan.timmendorf.scene.Site;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Phase 15: der Klang der Szene (F33 A) mit {@code com.dan.soundscape}, erzeugt, ohne Audiodateien. Das Ohr ist die
 * Kamera. Diese Klasse verdrahtet:
 * <ul>
 * <li><b>Brandung der Ostsee</b>: die Küste aus OSM ({@link Site#KUESTE}) als Linie von Stücken zu 25 m auf ±2,4 km
 * um das Ohr, 25 m seewärts der Wasserlinie (dort brechen die kurzen Wellen auf den Riffen). Je m Küste vergeht die
 * Energie, die der Seegang heranträgt (ρ g² H² T / 64π, nach dem Winkel zur Küste [G]); fast nur Schwallbrecher. Nah
 * am Wasser läuft jede Welle als Schwall den Sand hinauf.</li>
 * <li><b>See und Pfähle</b>: Rauschen der See nach Seegang, Glucksen an den Pfählen der Seebrücken (die nächsten in
 * 60 m), bei der Sturmflut Brecher an den Pfählen.</li>
 * <li><b>Boote</b> ({@code Flotte.klang}): Anluven und Wende lassen das Segel schlagen, an Kielbooten rattert danach die
 * Winsch, die Halse knallt, ein kenterndes Boot klatscht ins Wasser; die Signale der Regatta vom Startschiff (Hörner,
 * Startschuss); die Fähren mit Diesel und dem langen Ton beim Ablegen.</li>
 * <li><b>Hafen und Flaggen</b>: Fallen schlagen an den Masten im Niendorfer Hafen und im Passathafen am Priwall,
 * Flaggen an den Türmen der DLRG und am Strand (nur in der Saison).</li>
 * <li><b>Feuerwerk</b> ({@code Firework.events}): Abschuss am Ponton, die Zerlegung mit Laufzeit (erst das Bild, dann
 * der Knall), Knistern der Kamuro, Weiden und Crossetten.</li>
 * <li><b>Promenade</b>: Stimmengewirr der Leute in 40 m, Schritte der Gehenden in 15 m auf Pflaster, auf dem Holzdeck
 * der Seebrücken oder im Sand.</li>
 * <li><b>Tiere</b>: Möwen, Schwäne, Kormorane, Enten ({@code flock.onCall}), der Seehund.</li>
 * <li><b>Wetter</b>: Wind, Regen, Donner mit Laufzeit, die Abbrüche am Brodtener Ufer während der Sturmflut.</li>
 * </ul>
 * Ereignisse werden in einem eigenen Faden gerechnet (nur bei Ton an), {@link AudioOut} spielt in seinem. Offline
 * (Hörproben, Prüfung) läuft alles im rufenden Faden.
 */
public final class Klang {
    /** Halbe Länge der Brandungslinie (m), Länge eines Stücks (m), Abstand der Brecherlinie vor der Wasserlinie (m). */
    public static final double LINIE = 2400, STUECK = 25, BRECHER_VOR = 25;
    /** Masten [G]: Yachthafen Niendorf rund 120, Passathafen am Priwall rund 300. */
    public static final int MASTEN_NIENDORF = 120, MASTEN_PRIWALL = 300;

    public final Soundscape s = new Soundscape();
    private final Renderer r;
    private AudioOut out;
    public volatile boolean on;
    /** Teile an und aus (Bedienfeld KLANG). */
    public volatile boolean brandungAn = true, windAn = true, booteAn = true, hafenAn = true, feuerwerkAn = true, menschenAn = true, tiereAn = true, wetterAn = true;
    /** Offline (Hörproben, Prüfung): Ereignisse gleich im rufenden Faden, auch ohne Lautsprecher. */
    public volatile boolean offline;
    private final SplittableRandom rng = new SplittableRandom(2026);
    private final java.util.concurrent.ExecutorService synth = java.util.concurrent.Executors.newSingleThreadExecutor(q -> {
        Thread t = new Thread(q, "Klang-Synthese");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private final java.util.concurrent.atomic.AtomicInteger queued = new java.util.concurrent.atomic.AtomicInteger();

    /** Gezählt (HUD, Hörproben, Prüfung). */
    public volatile int segel, winschen, halsen, kenterungen, signale, schuesse, hoerner, abschuesse, zerlegungen, knistern, schwaelle, brecher,
            moewen, schwaene, kormorane, enten, seehunde, donner, abbrueche;
    public volatile String note = "", letztesSignal = "";
    /** Was die Linie gerade gibt: Stücke, Leistung je m Küste am nächsten Stück (W/m vergehen), Leute in 40 m, Gehende in 15 m. */
    public volatile int stuecke, leute, gehende;
    public volatile double wattsHier, kuesteHier = Double.NaN;

    private final double[][] kueste;
    private final Crowd menge = new Crowd("Promenade", 0, 1.6, 0, 0);
    private final Flap strandflagge;
    private final List<Flap> wachFlaggen = new ArrayList<>();
    private final Rig niendorf, priwall;
    private final Map<Flotte.Faehre, Hum> diesel = new IdentityHashMap<>();
    private final Map<Sail.Boat, Double> luvT = new IdentityHashMap<>();
    private final List<double[]> spaeter = new ArrayList<>();
    private List<double[]> pfaehle = List.of(), sturmPfaehle = List.of();
    private Firework fwHooked;
    private double time, lineT = 99, lineX = 1e18, lineZ = 1e18, pileT = 99, crowdT, schwallT, brecherT, moeweT, donnerT = -1e9;
    private int abbruchStand = Integer.MIN_VALUE, burstsThisFeed;

    Klang(Renderer r) {
        this.r = r;
        s.seed = 2026;
        s.quality = Soundscape.Quality.MEDIUM;
        s.windCarry = true;
        s.crowds.add(menge);
        // die Küste in Szenenkoordinaten
        kueste = new double[Site.KUESTE.length][];
        for (int i = 0; i < kueste.length; i++) kueste[i] = Site.fromUtm(Site.KUESTE[i][0], Site.KUESTE[i][1]);
        var w = r.wiring;
        // Flaggen: am Strand (die Flagge aus cloth) und an jedem Turm der DLRG (Flagge 1,2 m² auf 7 m [G])
        strandflagge = new Flap("Strandflagge", -6, 8.2, 4, 1.5);   // wie Cloth.beachFlag(-6, 2.2, 4) in Wiring
        s.flaps.add(strandflagge);
        for (Bauwerke.Wache wa : w.bauwerke.wachen) {
            if (!wa.turm()) continue;
            Flap f = new Flap("DLRG " + wa.name(), wa.x(), r.groundHeight(wa.x(), wa.z()) + 7, wa.z(), 1.2);
            wachFlaggen.add(f);
            s.flaps.add(f);
        }
        // Häfen mit Masten
        niendorf = new Rig("Niendorfer Hafen", Site.NIENDORF.x(), 1.0, Site.NIENDORF.z(), 110, MASTEN_NIENDORF);
        niendorf.woodShare = 0.05;
        priwall = new Rig("Passathafen am Priwall", Site.PASSAT.x() - 150, 1.0, Site.PASSAT.z() - 100, 200, MASTEN_PRIWALL);
        priwall.woodShare = 0.03;
        s.rigs.add(niendorf);
        s.rigs.add(priwall);
        // die Fähren: Diesel (mittelschnell, 500/min, 8 Zylinder) [G]
        for (Flotte.Faehre f : w.flotte.faehren) {
            Hum h = new Hum(f.name, f.x, 8, f.z);
            h.firingHz = Hum.firing(500, 8);
            h.levelDb = 112;
            diesel.put(f, h);
            s.hums.add(h);
        }
        // Boote
        w.flotte.klang = new Sail.Events() {
            @Override public void onLuff(Sail.Boat b, double strength) {
                if (!booteAn || near(b, 350)) return;
                Double last = luvT.get(b);
                if (last != null && time - last < 10) return;
                if (!marke(b)) return;
                luvT.put(b, time);
                segel++;
                ev(Kind.SEGEL, b.x, 2.5, b.z, 0.5 + 0.5 * strength, b.rig.sailArea());
            }
            @Override public void onTack(Sail.Boat b) {
                if (!booteAn || near(b, 400)) return;
                Double last = luvT.get(b);
                if (last != null && time - last < 5) return;
                if (!marke(b)) return;
                luvT.put(b, time);
                segel++;
                ev(Kind.SEGEL, b.x, 2.5, b.z, 0.9, b.rig.sailArea());
                if (b.rig.kind() == Sail.Kind.KEELBOAT || b.rig.kind() == Sail.Kind.SHIP) {   // an Kielbooten holt die Crew die neue Schot dicht
                    winschen++;
                    spaeter(time + 1.4 + rng.nextDouble(), Kind.WINSCH, b.x, 1.2, b.z, 0.8, 8 + rng.nextInt(14));
                }
            }
            @Override public void onGybe(Sail.Boat b, double v) {
                if (!booteAn || near(b, 400) || !marke(b)) return;
                halsen++;
                ev(Kind.SEGEL, b.x, 2.5, b.z, Math.min(1, 0.6 + 0.4 * v), b.rig.sailArea());
            }
            @Override public void onCapsize(Sail.Boat b) {
                if (!booteAn || near(b, 400) || !b.rig.canCapsize()) return;
                kenterungen++;
                ev(Kind.SCHWALL, b.x, 0.3, b.z, 0.8, 0.4);
                ev(Kind.SEGEL, b.x, 1.5, b.z, 1, b.rig.sailArea());
            }
        };
        double[] st = Site.fromUtm(Flotte.STARTSCHIFF[0], Flotte.STARTSCHIFF[1]);
        w.flotte.signale = new Regatta.Listener() {
            @Override public void onSignal(String what, double t) {
                if (!booteAn) return;
                letztesSignal = what;
                signale++;
                switch (what) {
                    case "Start" -> { schuesse++; ev(Kind.STARTSCHUSS, st[0], 3, st[1], 1, 0); ev(Kind.SIGNALHORN, st[0], 3.5, st[1], 1, 1.2); }
                    case "Einzelrückruf" -> ev(Kind.SIGNALHORN, st[0], 3.5, st[1], 1, 0.5);
                    case "Ziel" -> ev(Kind.SIGNALHORN, st[0], 3.5, st[1], 0.8, 0.35);
                    default -> { schuesse++; ev(Kind.STARTSCHUSS, st[0], 3, st[1], 0.8, 0); ev(Kind.SIGNALHORN, st[0], 3.5, st[1], 1, 1.0); }
                }
            }
        };
        w.flotte.hupe = (f, sec) -> {
            if (!booteAn) return;
            hoerner++;
            ev(Kind.FAEHRHORN, f.x, 32, f.z, 1, sec);
        };
        // Tiere
        r.leben.vogel.onCall = (x, y, z, str, g) -> {
            if (!tiereAn || time - moeweT < 0.6 || rng.nextDouble() > rufe()) return;
            moeweT = time;
            Kind k;
            switch (g.species) {
                case MOEWE -> { k = Kind.MOEWE; moewen++; }
                case SCHWAN -> { k = Kind.SCHWAN; schwaene++; }
                case KORMORAN -> { k = Kind.KORMORAN; kormorane++; }
                case ENTE -> { k = rng.nextDouble() < 0.3 ? Kind.ERPEL : Kind.ENTE; enten++; }
                case KRAEHE -> k = Kind.KRAEHE;
                case SCHWALBE -> k = Kind.SCHWALBE;
                default -> { return; }
            }
            if (k == Kind.MOEWE) ev(k, x, y, z, str);
            else ev(k, x, y, z, str, 0.5);
        };
        r.leben.seehund.seal.onCall = (x, z, str) -> {
            if (!tiereAn) return;
            seehunde++;
            ev(Kind.SEEHUND, x, r.wiring.pegel + 0.3, z, str > 0.55 ? 1 : 0.7, 0.5);
        };
        r.unwetter.blitz.onThunder = th -> {
            if (!wetterAn) return;
            donner++;
            donnerT = time;
            run(() -> s.thunder(th.x(), th.y(), th.z(), th.levelDb(), null, null, null, true));
        };
    }

    /** Wie gern die Vögel jetzt rufen (0..1): im Gewitter, Regen und Sturm kaum, nach einem Donner eine halbe Minute nicht [G]. */
    public double rufe() {
        var w = r.wiring;
        if (time - donnerT < 30) return 0;
        double f = 1;
        if (w.gewitter > 0) f *= 0.08;
        if (w.regen > 1.5) f *= 0.3;
        if (w.windSpeed > 17) f *= 0.3;
        return f;
    }

    /**
     * Ein Segel darf schlagen: höchstens eines je s für alle Boote zusammen (im Startverfahren wenden 20 Boote alle paar
     * Sekunden); ist der Vorrat knapp, nur die Boote in 120 m.
     */
    private boolean marke(Sail.Boat b) {
        double d = Math.hypot(b.x - s.earX, b.z - s.earZ);
        if (segelVorrat < 1 || (segelVorrat < 2 && d > 120)) return false;
        segelVorrat -= 1;
        return true;
    }

    private double segelVorrat = 3;

    /** true, wenn das Boot weiter als d m vom Ohr ist (dann bleibt es still: zu leise, kostet nur Rechenzeit). */
    private boolean near(Sail.Boat b, double d) { return Math.hypot(b.x - s.earX, b.z - s.earZ) > d; }

    /** Ein Ereignis rechnen lassen: im Bildlauf in einem eigenen Faden, nur bei Ton an; offline gleich. */
    private void run(Runnable job) {
        if (offline) { job.run(); return; }
        if (!on || queued.get() > 24) return;
        queued.incrementAndGet();
        synth.execute(() -> { try { job.run(); } catch (RuntimeException e) { note = "!" + e; } finally { queued.decrementAndGet(); } });
    }

    private void ev(Kind k, double x, double y, double z, double str) { run(() -> s.event(k, x, y, z, str)); }

    private void ev(Kind k, double x, double y, double z, double str, double tone) { run(() -> s.event(k, x, y, z, str, tone)); }

    /** Ein Ereignis zu einer späteren Zeit (s der Klangzeit). */
    private synchronized void spaeter(double t, Kind k, double x, double y, double z, double str, double tone) {
        spaeter.add(new double[]{t, k.ordinal(), x, y, z, str, tone});
    }

    /** Ton an oder aus (Lautsprecher im eigenen Faden). */
    public synchronized void setOn(boolean b) {
        on = b;
        if (b && out == null) {
            try { out = new AudioOut(s); out.start(); } catch (RuntimeException | LinkageError e) { note = "!kein Ton: " + e.getMessage(); out = null; on = false; }
        } else if (!b && out != null) { out.stop(); out = null; }
    }

    public synchronized void dispose() {
        if (out != null) { out.stop(); out = null; }
        on = false;
        synth.shutdownNow();
    }

    /** „Klang an · Wind 52 dB · See 48 dB · … · Brandung fern 58 dB (Linie 190 Stücke, nächstes 46 m) · Boote: 3 Segel …“ */
    public String describe() {
        double nl = s.surfLineNearest();
        return (on ? "Klang an · " : offline ? "Klang (Probe) · " : "Klang aus · ") + s.describe()
                + (Double.isNaN(nl) ? "" : String.format(Locale.GERMAN, " · Brandung fern %.0f dB, Linie %d Stücke, nächstes %.0f m", s.surfLineDb(), stuecke, nl))
                + String.format(Locale.GERMAN, " · Boote: %d Segel, %d Winschen, %d Signale%s, %d Fährhorn · Feuerwerk: %d Abschüsse, %d Knalle, %d Knistern",
                segel, winschen, signale, letztesSignal.isEmpty() ? "" : " (zuletzt " + letztesSignal + ")", hoerner, abschuesse, zerlegungen, knistern)
                + String.format(Locale.GERMAN, " · Promenade: %d Leute, %d gehen · Fallen %d, Flaggen %d, Schritte %d · Rufe: %d Möwen, %d Schwäne, %d Kormorane, %d Enten, %d Seehund",
                leute, gehende, s.rigSlaps(), s.flagSlaps(), s.footsteps(), moewen, schwaene, kormorane, enten, seehunde)
                + (donner + abbrueche + brecher > 0 ? String.format(Locale.GERMAN, " · %d Donner, %d Abbrüche, %d Brecher", donner, abbrueche, brecher) : "")
                + (note.isEmpty() ? "" : " · " + note);
    }

    /** Je Bild (Echtzeit dt): alle Eingänge setzen, Linie bei Bedarf neu, Ereignisse planen. */
    public void feed(double dt) {
        time += dt;
        burstsThisFeed = 0;
        segelVorrat = Math.min(4, segelVorrat + 1.0 * dt);
        var w = r.wiring;
        var cam = r.camera;
        double ex = cam.eye[0], ey = cam.eye[1], ez = cam.eye[2];
        double grund = r.groundHeight(ex, ez), unter = Math.max(grund, w.pegel), hoch = ey - unter;
        s.earX = ex; s.earY = ey; s.earZ = ez;
        s.earYawDeg = cam.heading();
        s.windSpeed = w.windSpeed;
        s.windFromDeg = w.windFromDeg;
        s.gustiness = Math.max(0.1, w.gustiness);
        s.temperatureC = w.temperatur;
        s.waterLevel = unter;
        s.surfLineLevel = w.pegel;
        s.windGainDb = windAn ? 0 : -80;
        s.rainRate = wetterAn && r.unwetter.regenFaellt() ? Math.max(0, r.unwetter.rain.rate) : 0;
        // über Land dämpfen Häuser und Bäume, der Wind am Ohr ist schwächer (Rauigkeit), auf Strand und See nicht
        double land = Site.kuestenAbstand(ex + Site.E0, Site.N0 - ez) + w.pegel * 40;
        kuesteHier = land;
        s.obstruction = land > 120 ? 3 : land > 60 ? 1.5 : 0;
        s.roughness = land > 120 ? 0.3 : land > 40 ? 0.03 : Soundscape.Z0;

        // die See: so laut, wie das Wasser nah ist
        double sea = land <= 0 ? 1 : 1 / (1 + land / 60);
        s.waveHeight = brandungAn ? Math.max(0.03, w.seeHs) * sea : 0;
        s.wavePeriod = Math.max(2.5, w.seeTp);
        // seit Phase 20: Flugsand prasselt am Ohr (Fluss aus dune, wie in Sylt)
        s.sandFlux = windAn && hoch < 4 && r.sand != null ? r.sand.flussBei(ex, ez) : 0;

        // die ferne Brandung: Linie neu, wenn das Ohr gewandert ist oder alle 2 s (Seegang und Pegel ändern sich)
        lineT += dt;
        if (!brandungAn) { s.surfLine = null; stuecke = 0; }
        else if (lineT > 2 || Math.hypot(ex - lineX, ez - lineZ) > 25) {
            lineT = 0;
            lineX = ex; lineZ = ez;
            s.surfLine = linie(ex, ez);
        }
        // nah am Wasser: jede Welle läuft als Schwall den Sand hinauf
        schwallT -= dt;
        if (brandungAn && hoch < 6 && land > -5 && land < 70 && schwallT <= 0) {
            double tp = Math.max(2.5, w.seeTp);
            schwallT = tp * (0.8 + 0.4 * rng.nextDouble());
            double[] p = wasserlinie(ex, ez);
            if (p != null && w.seeHs > 0.05) {
                schwaelle++;
                double h = Math.min(1.5, 0.35 * w.seeHs * (0.7 + 0.6 * rng.nextDouble()));
                ev(Kind.SCHWALL, p[0], w.pegel + 0.05, p[1], 0.8, h);
            }
        }

        // Pfähle in 60 m (alle 1 s neu); im Sturm brechen die Kämme an ihnen (wie die Gischt in Renderer.brecherSchritt [G])
        pileT += dt;
        if (pileT > 1) {
            pileT = 0;
            if (pfaehle.isEmpty()) pfaehle = r.pfaehle();
            List<double[]> nah = new ArrayList<>(), sturm = new ArrayList<>();
            for (double[] p : pfaehle) {
                if (p[2] >= w.pegel - 0.2) continue;
                double d = Math.hypot(p[0] - ex, p[1] - ez);
                if (d < 60) nah.add(new double[]{p[0], p[1]});
                if (d < 200) sturm.add(p);
            }
            s.pilings.clear();
            if (brandungAn) s.pilings.addAll(nah);
            sturmPfaehle = sturm;
        }
        double reach = w.seeHs - 0.8;
        if (wetterAn && brandungAn && reach > 0 && !sturmPfaehle.isEmpty()) {
            brecherT += dt * Math.min(3, sturmPfaehle.size() * 0.02 * reach * (1 + Math.max(0, w.pegel - 0.5)));
            while (brecherT >= 1) {
                brecherT -= 1;
                double[] p = sturmPfaehle.get(rng.nextInt(sturmPfaehle.size()));
                brecher++;
                double h = w.seeHs * (0.6 + 0.6 * rng.nextDouble());
                ev(h > 1.6 ? Kind.STURZBRECHER : Kind.SCHWALLBRECHER, p[0], w.pegel + 0.5, p[1], 0.8, Math.min(2.5, 0.5 * h));
            }
        } else brecherT = 0;

        // Flaggen: in der Saison an den Türmen der DLRG, die Strandflagge immer
        boolean saison = Bauwerke.saison(r.clock.date());
        strandflagge.strength = windAn ? 1 : 0;
        for (Flap f : wachFlaggen) f.strength = windAn && saison ? 1 : 0;
        // Häfen: im Winter stehen die meisten Boote an Land
        int monat = r.clock.date().getMonthValue();
        double boote = monat >= 4 && monat <= 10 ? 1 : 0.15;
        niendorf.strength = hafenAn ? boote : 0;
        priwall.strength = hafenAn ? boote : 0;
        // Fähren: Diesel unterwegs laut, am Kai die Hilfsdiesel
        for (Map.Entry<Flotte.Faehre, Hum> e : diesel.entrySet()) {
            Flotte.Faehre f = e.getKey();
            Hum h = e.getValue();
            h.x = f.x; h.z = f.z;
            h.levelDb = !booteAn ? 0 : f.dir != 0 ? 112 : 96;
        }

        // Feuerwerk: an das laufende hängen
        Firework fw = w.firework;
        if (fw != fwHooked) {
            fwHooked = fw;
            if (fw != null) haken(fw);
        }

        // Promenade: Leute in 40 m reden, Gehende in 15 m gehen hörbar
        crowdT -= dt;
        if (crowdT <= 0) {
            crowdT = 0.25;
            menschen(ex, ey, ez, hoch);
        }
        s.footing = belag(ex, ey, ez, grund, w.pegel);

        // die Abbrüche der Sturmflut am Brodtener Ufer: nur, wenn die Uhr läuft (nicht nach Sprüngen)
        int ab = r.abbruch.abbrueche();
        if (abbruchStand != Integer.MIN_VALUE && ab > abbruchStand && ab - abbruchStand <= 2 && wetterAn) {
            var plan = r.abbruch.plan();
            for (int k = abbruchStand + 1; k <= ab && k - 1 < plan.size(); k++) {
                if (k - 1 < 0) continue;
                var e = plan.get(k - 1);
                double vol = Math.max(50, e.laenge() * e.rueck() * 8);
                abbrueche++;
                ev(Kind.ABBRUCH, e.x(), r.groundHeight(e.x(), e.z()) + 4, e.z(), 1, vol);
            }
        }
        abbruchStand = ab;

        // was später klingen soll
        synchronized (this) {
            for (int i = 0; i < spaeter.size(); i++) {
                double[] q = spaeter.get(i);
                if (q[0] > time) continue;
                Kind k = Kind.values()[(int) q[1]];
                ev(k, q[2], q[3], q[4], q[5], q[6]);
                spaeter.remove(i--);
            }
            if (spaeter.size() > 64) spaeter.subList(0, spaeter.size() - 64).clear();
        }
    }

    /** Hängt den Klang an ein Feuerwerk: Abschuss, Zerlegung, Knistern. */
    private void haken(Firework fw) {
        fw.events = new Firework.Events() {
            @Override public void onLaunch(Firework.Shell sh, double t) {
                if (!feuerwerkAn || r.clock.lapse > 2 || burstsThisFeed > 8) return;
                abschuesse++;
                ev(Kind.ABSCHUSS, sh.x, r.wiring.pegel + 1.2, sh.z, 1, sh.caliberMm);
            }
            @Override public void onBurst(Firework.Shell sh, double x, double y, double z, double strength, double t) {
                if (!feuerwerkAn || r.clock.lapse > 2 || ++burstsThisFeed > 8) return;
                if (sh.effect == Firework.Effect.FAN) { abschuesse++; ev(Kind.ABSCHUSS, x, y, z, 0.6, Math.max(30, sh.caliberMm * 0.5)); return; }
                zerlegungen++;
                ev(Kind.ZERLEGUNG, x, y, z, 1, sh.caliberMm);
                double c = s.soundSpeed();
                switch (sh.effect) {
                    case KAMURO, WILLOW -> { knistern++; spaeter(time + 1.0 + 0.4 * rng.nextDouble(), Kind.KNISTERN, x, y - 15, z, 0.8, 60 + sh.caliberMm); }
                    case CROSSETTE -> { knistern++; spaeter(time + 0.6 + 0.2 * rng.nextDouble(), Kind.KNISTERN, x, y - 5, z, 1, 12); }
                    default -> { }
                }
                if (c < 0) note = "";   // (Laufzeit rechnet der Mischer selbst)
            }
        };
    }

    /** Leute um das Ohr: Menge in 40 m (Mitte und Zahl), Gehende in 15 m. */
    private void menschen(double ex, double ey, double ez, double hoch) {
        var m = r.wiring.menschen;
        if (m == null || !menschenAn || !r.wiring.showMenschen || hoch > 8) {
            menge.people = 0;
            s.walkers = 0;
            leute = gehende = 0;
            return;
        }
        int n = 0, g = 0;
        double cx = 0, cz = 0;
        for (var p : m.gaenger()) if (Math.hypot(p.x - ex, p.z - ez) < 15) g++;
        for (var p : m.alle()) {
            double d = Math.hypot(p.x - ex, p.z - ez);
            if (d < 40) { n++; cx += p.x; cz += p.z; }
        }
        leute = n;
        gehende = g;
        if (n > 0) { menge.x = cx / n; menge.z = cz / n; menge.y = r.groundHeight(menge.x, menge.z) + 1.6; }
        menge.radius = 15;
        // nicht alle reden: ein Drittel [G]
        menge.people = n * 0.35;
        s.walkers = g;
    }

    /** Belag unter dem Ohr: Holzdeck (über Wasser auf Höhe einer Seebrücke), Sand (Strand, nasser Sand, Spülsaum), sonst Pflaster. */
    private int belag(double ex, double ey, double ez, double grund, double pegel) {
        if (grund < pegel - 0.1 && ey > Bauwerke.DECK + 0.6 && ey < Bauwerke.DECK + 3.5) return 1;
        byte art = r.terrain.artAt(ex, ez);
        // Strand, nasser Sand am Spülsaum oder zwischen Wasserlinie und 50 m landwärts (Befund Phase 15: am Spülsaum stand „nasser Sand“)
        if (art == com.dan.timmendorf.scene.Terrain.STRAND || art == com.dan.timmendorf.scene.Terrain.NASS) return 2;
        double land = Site.kuestenAbstand(ex + Site.E0, Site.N0 - ez) + pegel * 40;
        return land > -5 && land < 50 && grund > pegel - 0.3 ? 2 : 0;
    }

    /** Der nächste Punkt der Wasserlinie (Küste aus OSM, Szene x, z), null ohne Küste. */
    double[] wasserlinie(double ex, double ez) {
        double best = 1e18;
        double[] q = null;
        for (int i = 0; i + 1 < kueste.length; i++) {
            double[] a = kueste[i], b = kueste[i + 1];
            double dx = b[0] - a[0], dz = b[1] - a[1], l2 = dx * dx + dz * dz;
            if (l2 < 1e-9) continue;
            double t = Math.max(0, Math.min(1, ((ex - a[0]) * dx + (ez - a[1]) * dz) / l2));
            double px = a[0] + t * dx, pz = a[1] + t * dz, d = Math.hypot(px - ex, pz - ez);
            if (d < best) { best = d; q = new double[]{px, pz, dx / Math.sqrt(l2), dz / Math.sqrt(l2)}; }
        }
        return q;
    }

    /**
     * Die Brandungslinie: Stücke zu 25 m längs der Küste im Umkreis von 2,4 km, 25 m seewärts. Je m Küste vergeht die
     * Energie, die der Seegang heranträgt: P = ρ g² H² T / 64π (Tiefwasser) mal dem Kosinus des Winkels zwischen
     * Wellenlauf und Küstennormale (mindestens 0,15: auch schräg anlaufende Wellen brechen) [G]. Bei auflandigem Wind
     * vergeht sie, bei ablandigem kommen kaum Wellen an.
     */
    double[][] linie(double ex, double ez) {
        var w = r.wiring;
        double hs = Math.max(0, w.seeHs), tp = Math.max(2.5, w.seeTp);
        double flux = 1025 * 9.81 * 9.81 * hs * hs * tp / (64 * Math.PI);
        double to = Math.toRadians(w.seeFromDeg + 180), wx = Math.sin(to), wz = -Math.cos(to);   // Laufrichtung der Wellen (x Ost, z Süd)
        List<double[]> outl = new ArrayList<>();
        double best = 1e18;
        wattsHier = 0;
        for (int i = 0; i + 1 < kueste.length; i++) {
            double[] a = kueste[i], b = kueste[i + 1];
            double dx = b[0] - a[0], dz = b[1] - a[1], l = Math.hypot(dx, dz);
            if (l < 1e-6) continue;
            double tx = dx / l, tz = dz / l;
            // seewärts: die Seite, auf der der Küstenabstand negativ ist (in der Szene ist z = −Nord gespiegelt)
            double mx = a[0] + dx / 2, mz = a[1] + dz / 2;
            double sg = Site.kuestenAbstand(mx - tz * 10 + Site.E0, Site.N0 - (mz + tx * 10)) < 0 ? 1 : -1;
            double nx = -tz * sg, nz = tx * sg;
            int pieces = Math.max(1, (int) Math.round(l / STUECK));
            for (int k = 0; k < pieces; k++) {
                double u = (k + 0.5) / pieces;
                double px = a[0] + dx * u + nx * BRECHER_VOR, pz = a[1] + dz * u + nz * BRECHER_VOR;
                double d = Math.hypot(px - ex, pz - ez);
                if (d > LINIE) continue;
                double cos = -(wx * nx + wz * nz);   // Welle läuft gegen die seewärtige Normale = auf die Küste zu
                double share = Math.max(0.15, cos);
                double pw = Soundscape.surfAcousticPower(flux * share);
                outl.add(new double[]{px, pz, pw, 0.1, tp, l / pieces});
                if (d < best) { best = d; wattsHier = flux * share; }
            }
        }
        stuecke = outl.size();
        return outl.isEmpty() ? null : outl.toArray(new double[0][]);
    }
}
