package com.dan.timmendorf.camera;

import com.dan.timmendorf.gl.Mat4;

/**
 * Die Kamera der Szene (aus Geiranger über Brocken; zwei Arten. Die Ansichten nach Namen stehen in Phase 1 auf der
 * Bühne, Phase 2 stellt sie auf das Gelände; Fahrten und Mitfahrt sind da, gebraucht werden sie mit der Welle in Phase 6
 * („Mit der Welle“) und den Regiebüchern in Phase 15):
 * <ul>
 * <li><b>Kreisen (Orbit):</b> um einen Drehpunkt, links ziehen dreht, rechts ziehen verschiebt, das Rad zoomt in
 * Schritten von 12 %. R lässt sie von selbst kreisen.</li>
 * <li><b>Frei:</b> fliegen mit W A S D, Q und E für ab und auf, Umschalt für schneller; ziehen schaut umher.</li>
 * </ul>
 * Richtungen wie auf der Karte: {@code yaw} ist die Blickrichtung in Grad von Nord im Uhrzeigersinn, {@code pitch} die
 * Neigung nach unten. Alle Werte laufen weich ihrem Ziel nach. Das Auge bleibt mindestens {@link #MIN_EYE} m über dem
 * Grund an dieser Stelle.
 */
public final class Camera {
    public enum Mode { KREISEN, FREI }

    public static final double MIN_DIST = 3, MAX_DIST = 60_000, MIN_EYE = 1.5, MAX_EYE = 8_000;
    public static final double FOVY = 55;
    /** Wo der Drehpunkt liegen darf (m in der Szene): das Hauptraster (20,5 km um die Mitte des Fernfelds). */
    public static final double X_MIN = -11_000, X_MAX = 9_500, Z_MIN = -9_000, Z_MAX = 11_700;

    public volatile Mode mode = Mode.KREISEN;
    /** Ziel: Drehpunkt, Blickrichtung (Grad), Neigung (Grad, positiv nach unten), Abstand. */
    public double tx, ty, tz, yaw, pitch, dist;
    /** Laufender Stand (weich nachgeführt). */
    private double cx, cy, cz, cyaw, cpitch, cdist;
    /** Freie Kamera: Auge und Blick. */
    public double fx, fy, fz, fyaw, fpitch;
    private double fxs, fys, fzs, fyaws, fpitchs;
    public volatile boolean autoOrbit;
    /** Grad je Sekunde beim Kreisen. */
    public double orbitSpeed = 3;
    public volatile boolean kFwd, kBack, kLeft, kRight, kUp, kDown, kRun;
    /** Höhe von Boden und Wasser unter dem Auge (vom Renderer gesetzt; Phase 1: die Bühne und der Pegel der See). */
    public volatile double groundLevel, waterLevel;
    /** Unter Wasser erlaubt (aus Geiranger; in Timmendorf für die Quallen unter der Seebrücke, Ideenspeicher): das Auge darf unter den Spiegel, nicht in den Grund. */
    public volatile boolean diving;
    /** Phase 2: Höhe des Bodens unter dem Drehpunkt; der Drehpunkt bleibt mindestens 0,5 m darüber. */
    public volatile double targetGround = Double.NEGATIVE_INFINITY;

    /** Auge und Blickrichtung des laufenden Bilds. */
    public final double[] eye = new double[3], fwd = new double[3];

    /** Die laufende Kamerafahrt (null: keine); sie stellt das Auge in jedem Schritt. */
    public volatile Fahrt fahrt;

    /**
     * Mitfahrt (null: keine; in Timmendorf ab Phase 8 ein Boot, „Mit dem Optimisten“). Liefert je Schritt {x, y, z, Kurs, Stampfen, Rollen}; Ziehen schaut umher
     * (gegen den Kurs versetzt), die Kamera fährt mit.
     */
    public volatile java.util.function.Supplier<double[]> mitfahrt;
    private double mitYaw, mitPitch = 8, mitYawS;

    public Camera() { home(); snap(); }

    /** Mitfahrt starten: Blick voraus, leicht nach unten. */
    public void startMitfahrt(java.util.function.Supplier<double[]> pose) { startMitfahrt(pose, 0); }

    /** Mitfahrt mit dem Blick um lookDeg zur Seite (negativ nach Backbord). */
    public void startMitfahrt(java.util.function.Supplier<double[]> pose, double lookDeg) {
        stopFahrt();
        mitYaw = mitYawS = lookDeg;
        mitPitch = 8;
        mitfahrt = pose;
        double[] q = pose.get();
        place(q[0], q[1], q[2], q[3], mitPitch);
    }

    /** Phase 16: bei der Mitfahrt den Blick weich zur Seite drehen (Grad, negativ nach Backbord). */
    public void mitBlick(double lookDeg) { mitYaw = Math.max(-170, Math.min(170, lookDeg)); }

    /** Mitfahrt beenden; die Kamera bleibt frei, wo sie ist. */
    public void stopMitfahrt() { mitfahrt = null; }

    /** Fahrt starten (vom Anfang). */
    public void startFahrt(Fahrt f) {
        mitfahrt = null;
        f.start();
        fahrt = f;
        double[] q = f.pose(0);
        place(q[0], q[1], q[2], q[3], q[4]);
    }

    /** Laufende Fahrt anhalten; die Kamera bleibt frei, wo sie ist. */
    public void stopFahrt() {
        Fahrt f = fahrt;
        if (f != null) f.running = false;
        fahrt = null;
    }

    /** Untergrenze für das Auge. */
    private double floor() { return (diving ? groundLevel : Math.max(groundLevel, waterLevel)) + MIN_EYE; }

    /**
     * Grundansicht (Pos1): kreisen um die Maritim Seebrücke, 1,8 km weit von See aus Nordost (Auge rund 500 m), Blick
     * nach Südwest auf Seebrücke, Strand, Promenade und Ort [G].
     */
    public void home() {
        mitfahrt = null;
        mode = Mode.KREISEN;
        autoOrbit = false;
        tx = com.dan.timmendorf.scene.Site.SCHLEIFE.x() * 0.5; ty = 3; tz = com.dan.timmendorf.scene.Site.SCHLEIFE.z() * 0.5;
        yaw = 225; pitch = 16; dist = 1800;
        clamp();
    }

    /** Setzt alles sofort auf das Ziel (nach Sprüngen, beim Start, für Standbilder). */
    public void snap() {
        cx = tx; cy = ty; cz = tz; cyaw = yaw; cpitch = pitch; cdist = dist;
        fxs = fx; fys = fy; fzs = fz; fyaws = fyaw; fpitchs = fpitch;
        update();
    }

    public void lookAt(double x, double y, double z, double yawDeg, double pitchDeg, double distance) {
        mitfahrt = null;
        tx = x; ty = y; tz = z; yaw = yawDeg; pitch = pitchDeg; dist = distance;
        clamp();
    }

    /** Wechsel zwischen Kreisen und Frei; die Ansicht bleibt dabei stehen. */
    public void setMode(Mode m) {
        if (m == mode) return;
        if (m == Mode.FREI) {
            fx = fxs = eye[0]; fy = fys = eye[1]; fz = fzs = eye[2];
            fyaw = fyaws = cyaw; fpitch = fpitchs = cpitch;
            autoOrbit = false;
        } else {
            // Drehpunkt dort, wo der Blick 60 m voraus hinzeigt (nicht unter dem Boden)
            double d = 60;
            tx = eye[0] + fwd[0] * d; tz = eye[2] + fwd[2] * d;
            ty = Math.max(groundLevel + 1, eye[1] + fwd[1] * d);
            yaw = fyaw; dist = Math.hypot(Math.hypot(tx - eye[0], tz - eye[2]), ty - eye[1]);
            pitch = Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, (eye[1] - ty) / dist))));
            cx = tx; cy = ty; cz = tz; cyaw = yaw; cpitch = pitch; cdist = dist;
        }
        mode = m;
    }

    /** Drehen um den Drehpunkt bzw. Umschauen (Grad). */
    public void orbit(double dYawDeg, double dPitchDeg) {
        if (mitfahrt != null) {
            mitYaw = Math.max(-170, Math.min(170, mitYaw + dYawDeg));
            mitPitch = Math.max(-40, Math.min(60, mitPitch + dPitchDeg));
            return;
        }
        if (mode == Mode.FREI) {
            fyaw += dYawDeg;
            fpitch = Math.max(-85, Math.min(85, fpitch + dPitchDeg));
        } else {
            yaw += dYawDeg;
            pitch += dPitchDeg;
            clamp();
        }
    }

    /** Verschieben in Bildschirmanteilen (rechts ziehen). */
    public void pan(double dxFrac, double dyFrac) {
        if (mode != Mode.KREISEN) return;
        double s = dist * 1.1;
        double a = Math.toRadians(yaw);
        double rx = Math.cos(a), rz = Math.sin(a);
        double fxh = Math.sin(a), fzh = -Math.cos(a);
        tx += -dxFrac * s * rx + dyFrac * s * fxh;
        tz += -dxFrac * s * rz + dyFrac * s * fzh;
        clamp();
    }

    /** Rad: Schritte von 12 % (positiv = weg). */
    public void zoom(double steps) {
        if (mode == Mode.FREI) {
            double d = steps * -4 * (1 + eye[1] / 20);
            fx += fwd[0] * d; fy += fwd[1] * d; fz += fwd[2] * d;
            return;
        }
        dist *= Math.pow(1.12, steps);
        clamp();
    }

    private void clamp() {
        dist = Math.max(MIN_DIST, Math.min(MAX_DIST, dist));
        pitch = Math.max(-60, Math.min(89, pitch));
        // Auge über Boden und Wasser: ty + dist·sin(pitch) ≥ Untergrenze
        double need = (floor() - ty) / dist;
        if (need > -1 && need < 1) pitch = Math.max(pitch, Math.toDegrees(Math.asin(need)));
        ty = Math.max(Math.max(groundLevel - 5, targetGround + 0.5), Math.min(MAX_EYE, ty));
        // im Hauptraster mit 3 km Rand
        tx = Math.max(X_MIN, Math.min(X_MAX, tx));
        tz = Math.max(Z_MIN, Math.min(Z_MAX, tz));
    }

    /** Frei an einen Ort stellen und in eine Richtung schauen; springt sofort. */
    public void place(double x, double y, double z, double yawDeg, double pitchDeg) {
        mode = Mode.FREI;
        autoOrbit = false;
        fx = x; fy = y; fz = z; fyaw = yawDeg; fpitch = pitchDeg;
        snap();
    }

    /** Läuft um dt Sekunden weiter: Kreisen, Tasten, weiches Nachführen. */
    public void step(double dt) {
        java.util.function.Supplier<double[]> mf = mitfahrt;
        if (mf != null) {
            double[] q = mf.get();
            mode = Mode.FREI;
            autoOrbit = false;
            mitYawS += (mitYaw - mitYawS) * (1 - Math.exp(-dt * 8));
            fx = fxs = q[0]; fy = fys = q[1]; fz = fzs = q[2];
            fyaw = fyaws = q[3] + mitYawS;
            // das Fahrzeug nickt und wankt, das Auge mit (vorn hoch: der Blick voraus hebt sich, zur Seite gilt das Rollen)
            double lk = Math.toRadians(mitYawS), bob = q.length > 5 ? q[4] * Math.cos(lk) - q[5] * Math.sin(lk) : q.length > 4 ? q[4] : 0;
            fpitch = fpitchs = mitPitch - bob;
            update();
            return;
        }
        Fahrt ride = fahrt;
        if (ride != null) {
            boolean more = ride.advance(dt);
            double[] q = ride.pose(ride.u);
            mode = Mode.FREI;
            autoOrbit = false;
            fx = fxs = q[0]; fy = fys = q[1]; fz = fzs = q[2];
            // Blick weich nachführen (der Weg knickt an den Toren)
            double k = 1 - Math.exp(-dt * 3);
            double dy = ((q[3] - fyaws + 540) % 360) - 180;
            fyaw = fyaws = fyaws + dy * k;
            fpitch = fpitchs = q[4];
            if (!more) fahrt = null;
            update();
            return;
        }
        if (mode == Mode.KREISEN) {
            if (autoOrbit) yaw += orbitSpeed * dt;
            double f = (kFwd ? 1 : 0) - (kBack ? 1 : 0), s = (kRight ? 1 : 0) - (kLeft ? 1 : 0);
            if (f != 0 || s != 0) {
                double v = (kRun ? 4 : 1) * Math.max(8, dist * 0.6) * dt, a = Math.toRadians(yaw);
                tx += (Math.sin(a) * f + Math.cos(a) * s) * v;
                tz += (-Math.cos(a) * f + Math.sin(a) * s) * v;
            }
            clamp();
            double k = 1 - Math.exp(-dt * 7);
            cx += (tx - cx) * k; cy += (ty - cy) * k; cz += (tz - cz) * k;
            cyaw += (yaw - cyaw) * k; cpitch += (pitch - cpitch) * k;
            cdist = Math.exp(Math.log(cdist) + (Math.log(dist) - Math.log(cdist)) * k);
        } else {
            double f = (kFwd ? 1 : 0) - (kBack ? 1 : 0), s = (kRight ? 1 : 0) - (kLeft ? 1 : 0), u = (kUp ? 1 : 0) - (kDown ? 1 : 0);
            double v = (kRun ? 4 : 1) * 6 * (1 + Math.max(0, fy - groundLevel) / 20) * dt;
            double a = Math.toRadians(fyaw), p = Math.toRadians(fpitch);
            double dx = Math.sin(a) * Math.cos(p), dy = -Math.sin(p), dz = -Math.cos(a) * Math.cos(p);
            fx += (dx * f + Math.cos(a) * s) * v;
            fy += (dy * f + u) * v;
            fz += (dz * f + Math.sin(a) * s) * v;
            fy = Math.max(floor(), Math.min(MAX_EYE, fy));
            double k = 1 - Math.exp(-dt * 10);
            fxs += (fx - fxs) * k; fys += (fy - fys) * k; fzs += (fz - fzs) * k;
            fyaws += (fyaw - fyaws) * k; fpitchs += (fpitch - fpitchs) * k;
        }
        update();
    }

    private void update() {
        if (mode == Mode.KREISEN) {
            setFwd(cyaw, cpitch);
            eye[0] = cx - fwd[0] * cdist;
            eye[1] = cy - fwd[1] * cdist;
            eye[2] = cz - fwd[2] * cdist;
            if (eye[1] < floor()) eye[1] = floor();
        } else {
            setFwd(fyaws, fpitchs);
            eye[0] = fxs; eye[1] = Math.max(floor(), fys); eye[2] = fzs;
        }
    }

    private void setFwd(double yawDeg, double pitchDeg) {
        double a = Math.toRadians(yawDeg), p = Math.toRadians(pitchDeg);
        fwd[0] = Math.sin(a) * Math.cos(p);
        fwd[1] = -Math.sin(p);
        fwd[2] = -Math.cos(a) * Math.cos(p);
    }

    public float[] view() {
        return Mat4.lookAt(eye[0], eye[1], eye[2], eye[0] + fwd[0], eye[1] + fwd[1], eye[2] + fwd[2]);
    }

    /** Blickrichtung des laufenden Bilds als Kompasswert 0..360. */
    public double heading() {
        double h = Math.toDegrees(Math.atan2(fwd[0], -fwd[2]));
        return h < 0 ? h + 360 : h;
    }

    /** Abstand vom Auge zum Landende der Seebrücke (Ursprung) in der Ebene (m). */
    public double originDistance() { return Math.hypot(eye[0], eye[2]); }

    /** „Kreisen“, „Kreisen (läuft)“, „Frei“ (HUD). */
    public String modeName() { return mitfahrt != null ? "Mitfahrt" : mode == Mode.FREI ? "Frei" : autoOrbit ? "Kreisen (läuft)" : "Kreisen"; }
}
