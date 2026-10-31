package com.dan.timmendorf.scene;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.function.DoubleBinaryOperator;

/**
 * Seit Phase 14: Abbrüche am Brodtener Ufer während der Sturmflut. Hohe Wasserstände und Wellen aus Nordost bis Ost
 * greifen den Fuß des Kliffs an; dann bricht die Kante in Stücken von 15 bis 50 m Länge um 1 bis 5 m zurück, das Material
 * liegt als Sturzkegel am Fuß [G: Häufigkeit und Maße gestaltet; das Ufer weicht im Mittel rund 0,8 m im Jahr zurück, nach
 * schweren Sturmfluten örtlich mehrere Meter – Landesamt für Umwelt Schleswig-Holstein].
 * <p>
 * Die Abbrüche stehen im Voraus fest (aus dem Fahrplan der {@link Sturmflut} und einem Startwert); gilt sind die, deren Zeit
 * vorbei ist – so passt das Kliff zu jeder Uhrzeit, auch nach einem Sprung. Das Ergebnis ist ein Feld der Höhenänderung
 * ({@link #korr}), das der Geländeshader addiert ({@code terrain_height.glsl}, {@code uKorr}).
 */
public final class Abbruch {
    /** Ein Abbruch: Stunde im Fahrplan, Ort der Kante (x, z), Richtung zur See (x, z), Länge, Rückgang (m). */
    public record Ereignis(double stunde, double x, double z, double sx, double sz, double laenge, double rueck) { }

    public final double x0, z0, zelle;
    public final int n;
    /** Änderung der Höhe je Zelle (m), Zeilen von Nord nach Süd (z wachsend), wie {@code uKorr}. */
    public final float[] korr;
    private final float[] grund;
    private final List<Ereignis> plan = new ArrayList<>();
    private int angewandt = -1;
    private volatile int version;
    private double volumen;

    /** Das Feld über dem Ausschnitt des Kliffs (Ecke x0, z0, Zellen zu zelle m). */
    public Abbruch(DoubleBinaryOperator gelaende, double x0, double z0, double zelle, int n, long seed) {
        this.x0 = x0; this.z0 = z0; this.zelle = zelle; this.n = n;
        korr = new float[n * n];
        grund = new float[n * n];
        for (int j = 0; j < n; j++) for (int i = 0; i < n; i++) grund[j * n + i] = (float) gelaende.applyAsDouble(x0 + (i + 0.5) * zelle, z0 + (j + 0.5) * zelle);
        planen(seed);
    }

    /** Kante des Kliffs: Zellen mit Wand (über 40°), oben über 8 m, unten keine 40 m zur See unter 2 m. */
    private void planen(long seed) {
        List<double[]> kante = new ArrayList<>();
        for (int j = 2; j < n - 2; j += 3) for (int i = 2; i < n - 2; i += 3) {
            double gx = (h(i + 1, j) - h(i - 1, j)) / (2 * zelle), gz = (h(i, j + 1) - h(i, j - 1)) / (2 * zelle), g = Math.hypot(gx, gz);
            if (g < Math.tan(Math.toRadians(40))) continue;
            double sx = -gx / g, sz = -gz / g;   // bergab = zur See
            // oben: gegen die See bis die Neigung flach wird; unten: zur See bis zum Strand
            double tx = x(i), tz = z(j), top = h(i, j);
            for (int k = 0; k < 20; k++) {
                double nx = tx - sx * zelle, nz = tz - sz * zelle, nh = at(nx, nz);
                if (nh - top < zelle * Math.tan(Math.toRadians(20))) break;
                tx = nx; tz = nz; top = nh;
            }
            double foot = h(i, j);
            for (int k = 0; k < 16; k++) foot = Math.min(foot, at(x(i) + sx * k * zelle, z(j) + sz * k * zelle));
            if (top < 8 || foot > 2.0) continue;
            kante.add(new double[]{tx, tz, sx, sz});
        }
        if (kante.isEmpty()) return;
        // Zeitpunkte: Poisson nach Pegel über 1,3 m und Wind aus 20°–140° (auf das Ufer) [G]
        SplittableRandom r = new SplittableRandom(seed);
        for (double h = 0; h < Sturmflut.STUNDEN; h += 0.25) {
            double ueber = Sturmflut.pegel(h) - 1.3, d = Sturmflut.richtung(h);
            if (ueber <= 0 || d < 20 || d > 140) continue;
            double rate = 10 * ueber * Math.min(1, Sturmflut.wind(h) / 20);   // je Stunde
            if (r.nextDouble() < rate * 0.25) {
                double[] k = kante.get(r.nextInt(kante.size()));
                plan.add(new Ereignis(h + r.nextDouble() * 0.25, k[0], k[1], k[2], k[3], 15 + 35 * r.nextDouble(), 1 + 4 * r.nextDouble() * r.nextDouble()));
            }
        }
    }

    double x(int i) { return x0 + (i + 0.5) * zelle; }
    double z(int j) { return z0 + (j + 0.5) * zelle; }
    double h(int i, int j) { return grund[Math.max(0, Math.min(n - 1, j)) * n + Math.max(0, Math.min(n - 1, i))]; }

    /** Höhe (mit den Abbrüchen bis jetzt) am Ort, bilinear. */
    double at(double x, double z) {
        double fi = (x - x0) / zelle - 0.5, fj = (z - z0) / zelle - 0.5;
        int i = (int) Math.floor(fi), j = (int) Math.floor(fj);
        double a = fi - i, b = fj - j;
        return (hk(i, j) * (1 - a) + hk(i + 1, j) * a) * (1 - b) + (hk(i, j + 1) * (1 - a) + hk(i + 1, j + 1) * a) * b;
    }

    private double hk(int i, int j) {
        i = Math.max(0, Math.min(n - 1, i));
        j = Math.max(0, Math.min(n - 1, j));
        return grund[j * n + i] + korr[j * n + i];
    }

    /** Auf den Stand zur Stunde h des Fahrplans bringen (NaN: vor der Sturmflut = nichts; danach bleibt alles). Gibt true bei Änderung. */
    public synchronized boolean stand(double h) {
        int soll = -1;
        double hh = Double.isNaN(h) ? -1 : h;
        for (int k = 0; k < plan.size(); k++) if (plan.get(k).stunde() <= hh) soll = k;
        if (soll == angewandt) return false;
        if (soll < angewandt) { java.util.Arrays.fill(korr, 0); angewandt = -1; volumen = 0; }
        for (int k = angewandt + 1; k <= soll; k++) anwenden(plan.get(k));
        angewandt = soll;
        version++;
        return true;
    }

    /** Ein Abbruch: das Profil weicht um rueck zurück (Höhe von weiter seewärts), das Material liegt als Kegel am Fuß. */
    private void anwenden(Ereignis e) {
        double ax = -e.sz(), az = e.sx(), reich = e.laenge() / 2 + 6;
        int i0 = Math.max(0, (int) ((e.x() - x0 - 40) / zelle)), i1 = Math.min(n - 1, (int) ((e.x() - x0 + 40) / zelle) + 1);
        int j0 = Math.max(0, (int) ((e.z() - z0 - 40) / zelle)), j1 = Math.min(n - 1, (int) ((e.z() - z0 + 40) / zelle) + 1);
        float[] neu = new float[(i1 - i0 + 1) * (j1 - j0 + 1)];
        double weg = 0, fussH = Double.MAX_VALUE, fussX = e.x(), fussZ = e.z();
        for (int j = j0; j <= j1; j++) for (int i = i0; i <= i1; i++) {
            double px = x(i), pz = z(j), a = (px - e.x()) * ax + (pz - e.z()) * az, s = -((px - e.x()) * e.sx() + (pz - e.z()) * e.sz());
            double w = Math.abs(a) > reich ? 0 : Math.abs(a) < e.laenge() / 2 ? 1 : 1 - (Math.abs(a) - e.laenge() / 2) / 6;
            if (s > 2 * e.rueck() + 4) w = 0;          // nicht weit ins Land
            double h0 = hk(i, j), h1 = w > 0 ? at(px + e.sx() * e.rueck() * w, pz + e.sz() * e.rueck() * w) : h0;
            float v = (float) Math.min(h0, h1);
            neu[(j - j0) * (i1 - i0 + 1) + (i - i0)] = v;
            weg += (h0 - v) * zelle * zelle;
        }
        for (int j = j0; j <= j1; j++) for (int i = i0; i <= i1; i++) korr[j * n + i] += neu[(j - j0) * (i1 - i0 + 1) + (i - i0)] - (float) hk(i, j);
        // Fuß: der tiefste Punkt bis 40 m seewärts
        for (int k = 0; k < 16; k++) {
            double px = e.x() + e.sx() * k * 2.5, pz = e.z() + e.sz() * k * 2.5, ph = at(px, pz);
            if (ph < fussH) { fussH = ph; fussX = px; fussZ = pz; }
            if (ph < 1.0) break;
        }
        // Sturzkegel: das Volumen als Kegel (Höhe bis 3 m), etwas zur See
        if (weg > 1) {
            double kh = Math.min(3, Math.max(0.6, Math.cbrt(weg) * 0.35)), r = Math.sqrt(3 * weg / (Math.PI * kh));
            double cx = fussX - e.sx() * r * 0.3, cz = fussZ - e.sz() * r * 0.3;
            int ci0 = Math.max(0, (int) ((cx - x0 - r) / zelle)), ci1 = Math.min(n - 1, (int) ((cx - x0 + r) / zelle) + 1);
            int cj0 = Math.max(0, (int) ((cz - z0 - r) / zelle)), cj1 = Math.min(n - 1, (int) ((cz - z0 + r) / zelle) + 1);
            for (int j = cj0; j <= cj1; j++) for (int i = ci0; i <= ci1; i++) {
                double d = Math.hypot(x(i) - cx, z(j) - cz);
                if (d < r) korr[j * n + i] += (float) (kh * (1 - d / r));
            }
        }
        volumen += weg;
    }

    public int version() { return version; }
    public synchronized int abbrueche() { return angewandt + 1; }
    public int geplant() { return plan.size(); }
    public List<Ereignis> plan() { return List.copyOf(plan); }
    public synchronized double volumen() { return volumen; }

    /** Größter Rückgang der Kante bisher (m): aus der Summe der angewandten. */
    public synchronized double groessterRueckgang() {
        double m = 0;
        for (int k = 0; k <= angewandt; k++) m = Math.max(m, plan.get(k).rueck());
        return m;
    }

    public String describe() {
        return String.format(Locale.GERMAN, "Brodtener Ufer: %d von %d Abbrüchen der Sturmflut, %,.0f m³ abgegangen, Kante bis %.1f m zurück", abbrueche(), geplant(), volumen(),
                groessterRueckgang());
    }
}
