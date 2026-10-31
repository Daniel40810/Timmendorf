package com.dan.timmendorf.data;

import com.dan.sail.Regatta;
import com.dan.store.Ablage;
import com.dan.store.Dienst;
import com.dan.store.Protokoll;
import com.dan.timmendorf.gl.Renderer;
import com.dan.timmendorf.scene.Abbruch;
import com.dan.timmendorf.scene.Sturmflut;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Die Chronik der Szene (Phase 17, F43, F44, F47 A): beobachtet die laufende Szene alle 0,5 s und schreibt über den
 * gültigen {@link Speicher} in die Datenbank oder in die Dateien. Das Bild wartet nie: beobachtet wird in einem eigenen
 * Faden, geschrieben in einem zweiten, der Reihe nach.
 * <ul>
 * <li><b>Regiebücher</b>: jeder Lauf mit Fotos, Warten, Ausweichen, ob im Kino, Bildern je s und Blatt.</li>
 * <li><b>Regatta</b>: je Boot, das ins Ziel kommt, Platz, Zeit, Wenden, Halsen, Frühstart und Wind.</li>
 * <li><b>Brodtener Ufer</b>: jeder Abbruch der Sturmflut mit Fläche und der Kante danach (springt die Uhr zurück, beginnt
 * die Zählung neu, geschrieben wird nur, was neu bricht).</li>
 * <li><b>Feuerwerk</b>: Beginn und Ende mit Zahl der Bomben und Sterne (TD_LOG, Art FEUERWERK).</li>
 * <li><b>Messung</b>: einmal je Minute Bilder je s und Zeit der Grafikkarte (TD_LOG, Art MESSUNG).</li>
 * </ul>
 */
public final class Chronik {
    /** Echte Sekunden zwischen zwei Messungen. */
    public static final double MESSUNG_S = 60;

    private final Renderer r;
    private final ScheduledExecutorService schauen;
    private final ExecutorService schreiben;
    private volatile boolean an = true;
    /** Zähler für Block DATEN und Prüfung. */
    public volatile int laeufe, ergebnisse, abbrueche, feuerwerke, messungen, fehler;
    public volatile String letzte = "";

    // Regie
    private com.dan.timmendorf.scene.Regie.Book buch;
    private long buchBeginn;
    private boolean buchKino;
    private double fpsSumme, gpuSumme;
    private int proben;
    // Regatta
    private int lauf = -1;
    private final Set<Integer> imZiel = new HashSet<>();
    // Abbrüche
    private int abbruchGesehen;
    // Feuerwerk
    private Object feuerwerk;
    private int bomben, sterne;
    // Messung
    private long messungSeit = System.currentTimeMillis();

    public Chronik(Renderer r) {
        this.r = r;
        if (r.abbruch != null) abbruchGesehen = r.abbruch.abbrueche();
        schauen = Executors.newSingleThreadScheduledExecutor(x -> faden(x, "Timmendorf-Chronik"));
        schreiben = Executors.newSingleThreadExecutor(x -> faden(x, "Timmendorf-Chronik-Schreiben"));
        schauen.scheduleWithFixedDelay(this::schauen, 500, 500, TimeUnit.MILLISECONDS);
    }

    private static Thread faden(Runnable x, String n) {
        Thread t = new Thread(x, n);
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    }

    public void schreiben(boolean ja) { an = ja; }
    public boolean schreibt() { return an; }

    /** Für Prüfungen: einmal beobachten (im Faden des Aufrufers) und warten, bis alles geschrieben ist. */
    public void jetzt() {
        schauen();
        warten();
    }

    /** Warten, bis die Schreibschlange leer ist (höchstens 30 s). */
    public void warten() {
        try { schreiben.submit(() -> { }).get(30, TimeUnit.SECONDS); } catch (Exception e) { /* weiter */ }
    }

    /** Nach „Weiter wie zuletzt“: was der hergestellte Stand schon gebrochen hat, ist nicht neu. */
    public synchronized void hergestellt() {
        if (r.abbruch != null) abbruchGesehen = r.abbruch.abbrueche();
        Regatta g = r.wiring.flotte.regatta;
        lauf = g.race();
        imZiel.clear();
        for (Regatta.Entry e : g.entries) if (e.leg == Regatta.Leg.DONE) imZiel.add(e.number);
    }

    public void beenden() {
        try {
            synchronized (this) { if (buch != null) laufEnde(false); }
            warten();
        } finally {
            schauen.shutdownNow();
            schreiben.shutdown();
        }
    }

    private void spaeter(String was, Arbeit a) {
        if (!an) return;
        Long sitzung = Dienst.sitzung() > 0 ? Dienst.sitzung() : null;
        schreiben.submit(() -> {
            try {
                a.tun(Datenseite.speicher(), sitzung);
            } catch (Ablage.Fehler | RuntimeException e) {
                fehler++;
                Protokoll.warnung("Chronik", was + ": " + e.getMessage());
            }
        });
    }

    private interface Arbeit { void tun(Speicher s, Long sitzung) throws Ablage.Fehler; }

    // ------------------------------------------------------------------ beobachten

    synchronized void schauen() {
        try {
            regie();
            regatta();
            abbrueche();
            feuerwerk();
            messung();
        } catch (RuntimeException e) {
            fehler++;
            Protokoll.fehler("Chronik", "Beobachten: " + e, e);
        }
    }

    private void regie() {
        var rg = r.regisseur.regie;
        var b = rg.running() ? rg.book() : null;
        if (b != null && buch == null) {
            buch = b;
            buchBeginn = System.currentTimeMillis();
            buchKino = r.kino.laeuft();
            fpsSumme = gpuSumme = 0;
            proben = 0;
        } else if (b != null) {
            fpsSumme += r.fps();
            gpuSumme += r.gpuMs();
            proben++;
            buchKino |= r.kino.laeuft();
        } else if (buch != null) {
            laufEnde(rg.describe().contains("zu Ende"));
        }
    }

    private void laufEnde(boolean vollstaendig) {
        var rg = r.regisseur.regie;
        java.io.File blatt = r.regisseur.lastSheet;
        Daten.Regielauf l = new Daten.Regielauf(buch.name(), buchBeginn, System.currentTimeMillis(), vollstaendig, buchKino, null, rg.waitedTotal(), rg.ausgewichen,
                r.regisseur.fotosVerlangt, proben == 0 ? Double.NaN : fpsSumme / proben, proben == 0 ? Double.NaN : gpuSumme / proben, r.quality().name(),
                blatt == null ? null : blatt.getPath());
        buch = null;
        laeufe++;
        letzte = "Regiebuch „" + l.name() + "“ " + (vollstaendig ? "bis zum Ende" : "abgebrochen");
        spaeter("Lauf", (s, z) -> {
            s.regielauf(l, z);
            s.ereignis("REGIE", "Regie", letzte, (double) l.fotos(), l.gewartetS(), z);
        });
    }

    private void regatta() {
        Regatta g = r.wiring.flotte.regatta;
        if (g.race() != lauf) { lauf = g.race(); imZiel.clear(); }
        double tg = g.toGun();
        if (Double.isNaN(tg)) return;
        double schuss = g.time() + tg;
        List<Daten.Ergebnis> neu = new ArrayList<>();
        for (Regatta.Entry e : g.entries) {
            if (e.leg != Regatta.Leg.DONE || imZiel.contains(e.number)) continue;
            imZiel.add(e.number);
            double zeit = Double.isNaN(e.finishTime) ? Double.NaN : e.finishTime - schuss;
            neu.add(new Daten.Ergebnis(Startdaten.REGATTA, g.race(), Integer.toString(e.number), e.place, zeit, e.tacks, e.gybes, e.ocs, r.wiring.windSpeed,
                    r.wiring.windFromDeg, (long) r.clock.epochMillis()));
        }
        if (neu.isEmpty()) return;
        ergebnisse += neu.size();
        letzte = neu.get(neu.size() - 1).text();
        spaeter("Ergebnis", (s, z) -> s.ergebnisse(neu, z));
    }

    private void abbrueche() {
        Abbruch a = r.abbruch;
        if (a == null) return;
        int n = a.abbrueche();
        if (n < abbruchGesehen) { abbruchGesehen = n; return; }   // Uhr zurück: der Stand beginnt neu
        if (n == abbruchGesehen) return;
        List<Abbruch.Ereignis> plan = a.plan();
        double vol = a.volumen();
        long t0 = Sturmflut.BEGINN.atZone(Sturmflut.ZONE).toInstant().toEpochMilli();
        for (int k = abbruchGesehen; k < n && k < plan.size(); k++) {
            Abbruch.Ereignis e = plan.get(k);
            Daten.Abbruch x = abbruch(k + 1, e, t0, k == n - 1 ? vol : Double.NaN);
            abbrueche++;
            letzte = x.text();
            spaeter("Abbruch", (s, z) -> {
                s.abbruch(x, z);
                s.ereignis("ABBRUCH", "Brodtener Ufer", x.text(), x.laenge(), x.rueck(), z);
            });
        }
        abbruchGesehen = n;
    }

    /** Für Prüfungen: ein Abbruch des Plans als Datensatz. */
    public static Daten.Abbruch abbruchFuer(int nr, Abbruch.Ereignis e, long t0, double volumen) { return abbruch(nr, e, t0, volumen); }

    /** Ein Abbruch als Datensatz: die Fläche zwischen alter und neuer Kante, die neue Kante als Linie. */
    static Daten.Abbruch abbruch(int nr, Abbruch.Ereignis e, long t0, double volumen) {
        double ax = -e.sz(), az = e.sx(), h = e.laenge() / 2, b = e.rueck();
        double[] p1 = {e.x() - ax * h, e.z() - az * h}, p2 = {e.x() + ax * h, e.z() + az * h};
        double[] p3 = {p2[0] - e.sx() * b, p2[1] - e.sz() * b}, p4 = {p1[0] - e.sx() * b, p1[1] - e.sz() * b};
        long ms = t0 + Math.round(e.stunde() * 3_600_000);
        Daten.Geom kante = Daten.Geom.linie(new double[][]{p4, p3});
        return new Daten.Abbruch(nr, e.stunde(), ms, e.x(), e.z(), e.laenge(), e.rueck(), volumen, Daten.Geom.flaeche(new double[][]{p1, p2, p3, p4}),
                new Daten.Kante(Daten.text(ms), "LAUF", nr, kante.laenge(), kante));
    }

    private void feuerwerk() {
        com.dan.firework.Firework f = r.wiring.firework;
        if (f != null && f != feuerwerk) {
            feuerwerk = f;
            bomben = f.shells().size();
            String t = String.format(Locale.GERMAN, "Feuerwerk beginnt: %d Bomben, %.0f s", bomben, f.duration());
            spaeter("Feuerwerk", (s, z) -> s.ereignis("FEUERWERK", "Feuerwerk", t, (double) bomben, f.duration(), z));
        } else if (f != null) {
            sterne = f.starsMade();
        } else if (feuerwerk != null) {
            feuerwerk = null;
            feuerwerke++;
            int st = sterne, bo = bomben;
            letzte = String.format(Locale.GERMAN, "Feuerwerk zu Ende: %d Bomben, %,d Sterne", bo, st);
            String t = letzte;
            spaeter("Feuerwerk", (s, z) -> s.ereignis("FEUERWERK", "Feuerwerk", t, (double) bo, (double) st, z));
        }
    }

    private void messung() {
        long now = System.currentTimeMillis();
        if (now - messungSeit < MESSUNG_S * 1000) return;
        messungSeit = now;
        if (r.frames() < 10) return;
        double fps = r.fps(), gpu = r.gpuMs();
        messungen++;
        String t = String.format(Locale.GERMAN, "%.0f Bilder/s, Grafikkarte %.1f ms, %s", fps, gpu, r.quality().name());
        spaeter("Messung", (s, z) -> s.ereignis("MESSUNG", "Renderer", t, fps, gpu, z));
    }

    /** „Chronik: 2 Läufe, 40 Ergebnisse, 58 Abbrüche, 1 Feuerwerk, 12 Messungen · zuletzt …“ */
    public String describe() {
        return String.format(Locale.GERMAN, "Chronik%s: %d Läufe, %d Ergebnisse, %d Abbrüche, %d Feuerwerke, %d Messungen%s%s", an ? "" : " (angehalten)", laeufe,
                ergebnisse, abbrueche, feuerwerke, messungen, fehler > 0 ? ", " + fehler + " Fehler" : "", letzte.isEmpty() ? "" : " · zuletzt " + letzte);
    }
}
