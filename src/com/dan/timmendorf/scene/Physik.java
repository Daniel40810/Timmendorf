package com.dan.timmendorf.scene;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Der Faden der Physik (aus Geiranger, F5 A): was nicht im Bildlauf rechnen soll, rechnet hier mit festem Schritt von
 * 1/120 s. Der Bildlauf gibt nur die verstrichene Zeit weiter ({@link #advance}) und liest Lagen und Höhen, die die
 * Aufgaben selbst bereitstellen. Hinkt der Faden hinterher, werden höchstens {@link #MAX_BEHIND} Schritte nachgeholt,
 * der Rest verfällt (die Szene läuft dann langsamer, statt zu explodieren).
 * <p>
 * Phase 1: der Faden läuft, misst sich selbst und rechnet die Hüllen der neuen Packages ({@code wiring.Wiring#step}:
 * Flagge aus {@code cloth}, Optimist aus {@code sail}, Kielwasser aus {@code wake}, Feuerwerk aus {@code firework}). Ab
 * Phase 7 hängen die Boote mit Kräften und Gleichgewicht an, ab Phase 10 die Gänger auf den Wegen aus {@code route}.
 */
public final class Physik {
    /** Eine Aufgabe im Faden: ein fester Schritt der Länge dt (s) zur Laufzeit t (s). */
    @FunctionalInterface
    public interface Aufgabe {
        void step(double t, double dt);
        default String name() { return getClass().getSimpleName(); }
    }

    public static final double DT = 1.0 / 120;
    /**
     * Wie viele Schritte höchstens nachgeholt werden. Seit Phase 16 (Dauerlauf) 24 statt 12: Der Bildlauf gibt nach
     * einem langsamen Bild bis 0,1 s auf einmal weiter; mit 12 Schritten (0,1 s) war der Puffer damit schon voll, und
     * jeder Hänger des Bilds zählte als Verlust der Physik.
     */
    public static final int MAX_BEHIND = 24;

    private final List<Aufgabe> aufgaben = new CopyOnWriteArrayList<>();
    private final Object lock = new Object();
    /** Seit Phase 17: ein Schritt zur Zeit, ob vom Faden oder von {@link #drain} (Befund: ConcurrentModification in „Hüllen“). */
    private final Object stepLock = new Object();
    private final java.util.Map<Aufgabe, Integer> fehler = new java.util.concurrent.ConcurrentHashMap<>();
    private double pending, t;
    private long steps, dropped;
    /** Phase 16: Zeit (s), die verfallen ist, weil der Faden nicht nachkam. */
    private double lost;
    private volatile double stepMs, busy;
    private volatile boolean running;
    private Thread thread;

    private final String name;

    public Physik() { this("Physik"); }

    /** Ein Faden mit eigenem Namen (seit Phase 8: „Welle“ für com.dan.wave). */
    public Physik(String name) { this.name = name; }

    public void add(Aufgabe a) { aufgaben.add(a); }
    public void remove(Aufgabe a) { aufgaben.remove(a); }
    public int aufgaben() { return aufgaben.size(); }

    /** Vom Bildlauf: dt echte Sekunden sind vergangen (0 bei stehender Szene). */
    public void advance(double dt) {
        synchronized (lock) {
            double want = pending + Math.max(0, dt), cap = DT * MAX_BEHIND;
            // Phase 16: gezählt wird, was wirklich verfällt (vorher zählte jeder Durchlauf mit vollem Puffer)
            if (want > cap + 1e-9) { lost += want - cap; dropped++; }
            pending = Math.min(want, cap);
            lock.notifyAll();
        }
    }

    /** Rechnet ohne Faden alles, was ansteht (für Prüfungen und Standbilder); gibt die Zahl der Schritte zurück. */
    public int drain() {
        int n = 0;
        while (true) {
            synchronized (lock) {
                if (pending < DT) return n;
                pending -= DT;
            }
            once();
            n++;
        }
    }

    private void once() {
        synchronized (stepLock) {
            long t0 = System.nanoTime();
            for (Aufgabe a : aufgaben) {
                try {
                    a.step(t, DT);
                    fehler.remove(a);
                } catch (RuntimeException e) {
                    // seit Phase 17: erst nach drei Fehlern in Folge heraus (vorher fiel „Hüllen“ beim ersten weg, und mit ihr Boote und Feuerwerk)
                    int n = fehler.merge(a, 1, Integer::sum);
                    if (n == 1) { System.err.println("Physik: " + a.name() + ": " + e); e.printStackTrace(); }
                    if (n >= 3) { System.err.println("Physik: " + a.name() + " fällt heraus (drei Fehler in Folge)"); aufgaben.remove(a); fehler.remove(a); }
                }
            }
            t += DT;
            steps++;
            double ms = (System.nanoTime() - t0) / 1e6;
            stepMs = stepMs * 0.95 + ms * 0.05;
        }
    }

    public void start() {
        if (running) return;
        running = true;
        thread = new Thread(() -> {
            long last = System.nanoTime(), busyNs = 0;
            while (running) {
                boolean go;
                synchronized (lock) {
                    go = pending >= DT;
                    if (go) pending -= DT;
                    else try { lock.wait(4); } catch (InterruptedException e) { return; }
                }
                if (go) {
                    long a = System.nanoTime();
                    once();
                    busyNs += System.nanoTime() - a;
                }
                long now = System.nanoTime();
                if (now - last > 1_000_000_000L) {
                    busy = busyNs / (double) (now - last);
                    busyNs = 0;
                    last = now;
                }
            }
        }, name);
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY + 1);
        thread.start();
    }

    /** Hält den Faden an und wartet, bis er steht (seit Phase 17: sonst rechnete er noch, während {@link #drain} begann). */
    public void stop() {
        running = false;
        Thread th = thread;
        if (th != null) {
            th.interrupt();
            if (th != Thread.currentThread()) {
                try { th.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }
        thread = null;
    }

    public double time() { return t; }
    public long steps() { return steps; }

    /** Phase 16: wie oft die Physik nicht nachkam (für den Dauerlauf). */
    public long dropped() { return dropped; }

    /** Phase 16: verfallene Zeit in Sekunden (die Szene lief so viel langsamer). */
    public double lostSeconds() { synchronized (lock) { return lost; } }

    /** „Physik 1/120 s · 0 Aufgaben · 0,00 ms je Schritt · Faden 0 % belegt“ */
    public String describe() {
        return String.format(Locale.GERMAN, name + " 1/%d s · %d Aufgaben · %.2f ms je Schritt · Faden %.0f %% belegt%s",
                Math.round(1 / DT), aufgaben.size(), stepMs, busy * 100, dropped > 0 ? String.format(Locale.GERMAN, " · %d× nicht nachgekommen (%.1f s verfallen)", dropped, lost) : "");
    }
}
