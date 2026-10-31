package com.dan.timmendorf.camera;

import java.util.Locale;

/**
 * Eine Kamerafahrt (F20 A) entlang eines Wegs aus Punkten {x, Augenhöhe, z}: das Auge fährt mit gleichmäßiger
 * Geschwindigkeit, der Blick folgt der Richtung des Wegs (aus einem Stück davor und dahinter gemittelt, damit er nicht
 * zittert) und ist um {@link #look} Grad zur Seite gedreht, positiv nach rechts (aus Haithabu, Glacier und Brocken). In
 * Timmendorf kommen sie mit den Regiebüchern (Phase 16), etwa die Seebrücke entlang bis zur Schleife.
 */
public final class Fahrt {
    public final String name;
    private final double[][] p;
    private final double[] cum;
    /** Länge des Wegs (m). */
    public final double length;
    /** Dauer der ganzen Fahrt (s), Blick zur Seite (Grad, + rechts), Neigung nach unten (Grad). */
    public volatile double duration, look, pitch;
    /**
     * Blickrichtung am Ende (Grad von Nord; NaN = wie der Weg) und auf wie vielen Metern vor dem Ende der
     * Blick dorthin dreht (aus Haithabu).
     */
    public volatile double endHeading = Double.NaN, endTurn = 5;
    /** Fortschritt 0..1 und ob sie läuft. */
    public volatile double u;
    public volatile boolean running;

    public Fahrt(String name, double[][] points, double durationS, double lookDeg, double pitchDeg) {
        if (points.length < 2) throw new IllegalArgumentException("Fahrt " + name + ": zu wenige Punkte");
        this.name = name;
        this.p = points;
        this.cum = new double[points.length];
        for (int i = 1; i < points.length; i++) cum[i] = cum[i - 1] + Math.hypot(points[i][0] - points[i - 1][0], points[i][2] - points[i - 1][2]);
        this.length = cum[cum.length - 1];
        this.duration = durationS;
        this.look = lookDeg;
        this.pitch = pitchDeg;
    }

    /**
     * Phase 16: die Stützpunkte für die Datenbank (HB_STUETZPUNKT): je Punkt {Zeit s, x, y, z, Blickrichtung, Neigung},
     * die Zeit nach gleichmäßiger Fahrt.
     */
    public double[][] stuetzpunkte() {
        double[][] r = new double[p.length][];
        for (int i = 0; i < p.length; i++) {
            double uu = length > 0 ? cum[i] / length : 0;
            double[] po = pose(uu);
            r[i] = new double[]{uu * duration, p[i][0], p[i][1], p[i][2], po[3], po[4]};
        }
        return r;
    }

    /** Phase 16: eine Fahrt aus Stützpunkten {Zeit, x, y, z, …}; der Blick folgt dem Weg, die Neigung ist das Mittel. */
    public static Fahrt aus(String name, double[][] sp) {
        double[][] pts = new double[sp.length][];
        double nick = 0;
        for (int i = 0; i < sp.length; i++) { pts[i] = new double[]{sp[i][1], sp[i][2], sp[i][3]}; nick += sp[i].length > 5 && !Double.isNaN(sp[i][5]) ? sp[i][5] : 0; }
        return new Fahrt(name, pts, Math.max(1, sp[sp.length - 1][0] - sp[0][0]), 0, nick / sp.length);
    }

    /** Geschwindigkeit (m/s). */
    public double speed() { return length / Math.max(1, duration); }

    /** Ort auf dem Weg bei Bogenlänge sm: {x, y, z}. */
    public double[] at(double sm) {
        sm = Math.max(0, Math.min(length, sm));
        int lo = 0, hi = cum.length - 1;
        while (hi - lo > 1) { int mid = (lo + hi) >>> 1; if (cum[mid] <= sm) lo = mid; else hi = mid; }
        double f = (sm - cum[lo]) / Math.max(1e-9, cum[hi] - cum[lo]);
        return new double[]{p[lo][0] + (p[hi][0] - p[lo][0]) * f, p[lo][1] + (p[hi][1] - p[lo][1]) * f, p[lo][2] + (p[hi][2] - p[lo][2]) * f};
    }

    /** Richtung des Wegs bei sm (Grad von Nord), aus dem Stück von sm − 10 bis sm + 16 m. */
    public double heading(double sm) {
        double[] a = at(sm - 10), b = at(sm + 16);
        double h = Math.toDegrees(Math.atan2(b[0] - a[0], -(b[2] - a[2])));
        return h < 0 ? h + 360 : h;
    }

    /** Stellung bei Fortschritt uu (0..1): {x, y, z, Blickrichtung, Neigung}. */
    public double[] pose(double uu) {
        double sm = Math.max(0, Math.min(1, uu)) * length;
        double[] q = at(sm);
        // am Anfang und Ende den Seitenblick ein- und ausblenden
        double side = look * Math.min(1, Math.min(sm, length - sm) / 25.0 + 0.35);
        double h = (heading(sm) + side + 360) % 360;
        if (!Double.isNaN(endHeading) && length - sm < endTurn) {
            double k = 1 - (length - sm) / endTurn, d = ((endHeading - h + 540) % 360) - 180;
            k = k * k * (3 - 2 * k);
            h = (h + d * k + 360) % 360;
        }
        return new double[]{q[0], q[1], q[2], h, pitch};
    }

    /** Läuft um dt Sekunden weiter; false am Ende. */
    public boolean advance(double dt) {
        if (!running) return false;
        u = Math.min(1, u + dt / Math.max(1, duration));
        if (u >= 1) running = false;
        return running;
    }

    public void start() { u = 0; running = true; }

    /** Größte Drehung des Blicks je Meter Weg (Grad/m), für die Prüfung. */
    public double maxTurnPerMeter() {
        double worst = 0, prev = heading(0);
        for (double sm = 2; sm <= length; sm += 2) {
            double h = heading(sm), d = Math.abs(((h - prev + 540) % 360) - 180);
            worst = Math.max(worst, d / 2);
            prev = h;
        }
        return worst;
    }

    /** „Über die Zunge · 8 000 m in 160 s (50 m/s) · 42 %“ */
    public String describe() {
        return String.format(Locale.GERMAN, "%s · %,.0f m in %.0f s (%.1f m/s)%s", name, length, duration, speed(), running ? String.format(Locale.GERMAN, " · %.0f %%", u * 100) : "");
    }
}
