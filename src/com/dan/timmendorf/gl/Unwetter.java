package com.dan.timmendorf.gl;

import com.dan.cloud.Clouds;
import com.dan.fog.Fog;
import com.dan.fog.gl.FogGl;
import com.dan.lightning.Lightning;
import com.dan.lightning.gl.LightningGl;
import com.dan.ocean.gl.OceanGl;
import com.dan.rain.Rain;
import com.dan.rain.gl.RainGl;
import com.dan.sky.Sky;
import com.dan.timmendorf.scene.Site;
import com.dan.timmendorf.wiring.Wiring;
import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL4;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * Seit Phase 13 (aus Sylt Phase 13 übernommen): das Wetter, das man sieht, außer Himmel und Wolken ({@link Wetter}): Regen
 * ({@code com.dan.rain}: Streifen um das Auge, Vorhänge unter den Schauern über der Bucht, Ringe auf dem Wasser, Krönchen),
 * Seenebel und Seerauch ({@code com.dan.fog}: Bänke über dem Pegel, die mit dem Wind ziehen) und Gewitter
 * ({@code com.dan.lightning}: Blitze über der Bucht, das Maritim und der Alte Leuchtturm als Fangpunkte, das Licht in Wolken,
 * See und Szene). Die Zahlen
 * kommen aus der Verdrahtung ({@link Wiring}: Regen, Nebel, Gewitter, Wind), wo es regnet, sagen die Wolken.
 * <p>
 * Jedes Package startet für sich; was nicht startet, fehlt. Gezeichnet wird nach den Wolken: Nebel (Bilddurchgang mit der
 * Tiefe), Regen (mit einer Kopie der Tiefe), Blitzkanäle mit Tiefentest, das Leuchten der Luft.
 */
public final class Unwetter {
    public final Rain rain = Rain.moderate();
    final RainGl rainGl = new RainGl();
    public final Fog fog = Fog.banks();
    final FogGl fogGl = new FogGl();
    public final Lightning blitz = Lightning.storm();
    final LightningGl blitzGl = new LightningGl();
    boolean rainOk, fogOk, blitzOk;
    String rainCheck = "", fogCheck = "", blitzCheck = "";
    /** Zeigen (Bedienfeld). */
    public volatile boolean showRegen = true, showNebel = true, showGewitter = true;
    private int tiefeTex, tiefeFbo, tw, th;
    private Lightning.Snapshot blitzSnap;
    private Clouds.Snapshot wolken;
    private double cloudBase = 900;
    /** Blitzlicht am Auge (Bestrahlung in Szeneneinheiten von com.dan.sky, unbelichtet) und Richtung zum stärksten Licht. */
    double[] blitzE = {0, 0, 0}, blitzDir = {0, 1, 0};

    void init(GL4 gl, Consumer<String> err) {
        rain.seed = 2026;
        fog.seed = 2026;
        blitz.seed = 2026;
        blitz.setLevel(Lightning.Level.OFF);
        rainGl.crownBand = 0.4;   // Krönchen nur auf dem Wasser (auf Sand spritzt es nicht weiß)
        try { rainGl.init(gl); rainOk = true; } catch (RuntimeException e) { fail(err, "Der Regen (com.dan.rain) lässt sich nicht starten", e); }
        try { fogGl.init(gl); fogOk = true; } catch (RuntimeException e) { fail(err, "Der Seenebel (com.dan.fog) lässt sich nicht starten", e); }
        try { blitzGl.init(gl); blitzOk = true; } catch (RuntimeException e) { fail(err, "Die Blitze (com.dan.lightning) lassen sich nicht starten", e); }
        System.out.println("Timmendorf: Unwetter · Regen " + (rainOk ? "bereit" : "fehlt") + " · Nebel " + (fogOk ? "bereit" : "fehlt") + " · Blitze " + (blitzOk ? "bereit" : "fehlt"));
    }

    private static void fail(Consumer<String> err, String what, RuntimeException e) {
        System.err.println("Timmendorf: " + what + ": " + e.getMessage());
        err.accept(what + ".\n" + e.getMessage());
    }

    /**
     * Je Bild (Szenenzeit dt): Regen, Nebel und Gewitter aus der Verdrahtung, Vorhänge und Gewitterzellen unter den Wolken,
     * Wind mit der Böe des Augenblicks; dazu der Niederschlag der Wolken. {@code grund} gibt das Gelände (für den Fang).
     */
    public void schritt(double dt, Wiring w, Clouds clouds, boolean cloudsShown, double[] eye, java.util.function.DoubleBinaryOperator grund) {
        Clouds.Snapshot cs = clouds.snapshot();
        wolken = cs;
        cloudBase = cs.type().base;
        // Regen: unter den Wolken in Vorhängen (auf 0..1 bezogen, doppelt: deren Feld erreicht nur in den Kernen 1)
        double rate = showRegen ? Math.max(0, w.regen) : 0;
        clouds.precipitation = rate > 0 ? Math.min(1, 0.35 + rate / 25) : 0;
        rain.rate = rate;
        rain.windSpeed = Math.max(w.windSpeed, w.gustNow);
        rain.windFromDeg = w.windFromDeg;
        rain.gust = w.gustiness;
        rain.waterLevel = w.pegel;
        rain.cloudBase = cs.type().base;
        rain.eyeX = eye[0];
        rain.eyeY = eye[1];
        rain.eyeZ = eye[2];
        if (cloudsShown && rate > 0) {
            double p = Math.max(1e-3, clouds.precipitation);
            Clouds.Snapshot c = cs;
            rain.curtain = (x, z) -> Math.min(1, Clouds.rain(c, x, z) / p * 2);
        } else rain.curtain = null;
        rain.step(dt);
        // Seenebel: Bänke über dem Pegel; der Wind reißt ihn ab Bft 4 auf, ab Bft 6 ist er fort [G, wie Lighthouse]
        double n = showNebel ? Math.max(0, Math.min(1, w.nebel)) : 0;
        double windCut = 1 - smooth(6, 12, w.windSpeed);
        fog.amount = smooth(0.05, 0.35, n) * windCut;
        fog.visibilityM = 4000 * Math.pow(60 / 4000.0, Math.pow(n, 0.6));
        fog.coverage = Math.min(1, 0.3 + 0.8 * n);
        fog.top = Math.max(4, w.nebelHoch);
        fog.wisps = w.nebelHoch < 15 ? 0.85 : 0.35;     // Seerauch: dünne, aufsteigende Fäden
        fog.windSpeed = w.windSpeed;
        fog.windFromDeg = w.windFromDeg;
        fog.waterLevel = w.pegel;
        fog.hazeVisibilityM = w.visibilityKm * 1000;
        fog.step(dt);
        // Gewitter: Zellen unter den dichten Wolken, über der See stärker; Fang an den Türmen
        Lightning.Level lv = showGewitter ? Lightning.Level.values()[Math.max(0, Math.min(Lightning.Level.values().length - 1, w.gewitter))] : Lightning.Level.OFF;
        if (blitz.level() != lv) blitz.setLevel(lv);
        blitz.cx = eye[0];
        blitz.cz = eye[2];
        blitz.radius = 16_000;
        blitz.listenerX = eye[0];
        blitz.listenerY = eye[1];
        blitz.listenerZ = eye[2];
        blitz.groundLevel = w.pegel;
        blitz.temperatureC = w.temperatur;
        if (blitz.ground == null) blitz.ground = grund::applyAsDouble;
        if (blitz.attractors.isEmpty()) {
            com.dan.beacon.Beacon m = w.leuchtfeuer.maritim, a = w.leuchtfeuer.alter;
            blitz.attractors.add(new Lightning.Attractor(m.x, m.y + 4, m.z, "Maritim Travemünde"));
            blitz.attractors.add(new Lightning.Attractor(a.x, a.y + 7, a.z, "Alter Leuchtturm"));
        }
        if (cloudsShown) {
            double base = cs.type().base, top = base + cs.type().maxThickness;
            blitz.cloudBase = base;
            blitz.cells = (x, z) -> Math.max(Clouds.rain(cs, x, z) * 1.5, Clouds.coverage(cs, x, z) * 0.35) * (grund.applyAsDouble(x, z) < -1 ? 1 : 0.3);
            blitz.occlusion = (px, py, pz, ex, ey, ez) -> wolkenDurchlass(cs, px, py, pz, ex, ey, ez, base, top);
        } else {
            blitz.cloudBase = 1200;
            blitz.cells = (x, z) -> grund.applyAsDouble(x, z) < -1 ? 1 : 0.3;
            blitz.occlusion = null;
        }
        blitz.step(dt);
    }

    /** Durchlässigkeit der Wolken zwischen Kanal und Auge (zehn Proben in der Schicht, halbe Auslöschung; aus Lighthouse). */
    static double wolkenDurchlass(Clouds.Snapshot cs, double px, double py, double pz, double ex, double ey, double ez, double base, double top) {
        double dx = ex - px, dy = ey - py, dz = ez - pz, len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1) return 1;
        double t0 = 0, t1 = 1;
        if (Math.abs(dy) > 1e-6) {
            double ta = (base - py) / dy, tb = (top - py) / dy;
            t0 = Math.max(0, Math.min(ta, tb));
            t1 = Math.min(1, Math.max(ta, tb));
        } else if (py < base || py > top) return 1;
        if (t1 <= t0) return 1;
        double tau = 0, step = (t1 - t0) / 10;
        for (int i = 0; i < 10; i++) {
            double t = t0 + (i + 0.5) * step;
            tau += Clouds.density(cs, px + dx * t, py + dy * t, pz + dz * t, false) * step * len;
            if (tau > 20) break;
        }
        return Math.exp(-0.5 * tau);
    }

    /** Es regnet in der Simulation (auch ohne Grafikkarte, für Nässe und Prüfung). */
    public boolean regenFaellt() { return showRegen && rain.rate > 0.02; }
    public boolean regnet() { return rainOk && showRegen && rain.rate > 0.02; }
    public boolean nebelt() { return fogOk && showNebel && fog.snapshot().any(); }
    public boolean blitzt() { return blitzOk && showGewitter && blitzGl.active(); }

    /** Vor der See: Ringkarte des Regens, Blitze hochladen, das Blitzlicht am Auge (für Szene und Gischt). */
    void hochladen(GL4 gl, double[] eye) {
        if (rainOk) {
            rainGl.update(gl, rain.snapshot());
            if (rainCheck.isEmpty() && rain.rate > 0 && rain.time() > 2) rainCheck = rainGl.selfCheck(gl, rain.snapshot());
        }
        java.util.Arrays.fill(blitzE, 0);
        if (blitzOk) {
            blitzSnap = blitz.snapshot();
            blitzGl.update(gl, blitzSnap);
            if (blitzt()) {
                double best = 0;
                for (int i = 0; i < blitzGl.lightCount(); i++) {
                    double[] p = blitzGl.lightPos(i), c = blitzGl.lightRgb(i);
                    double dx = p[0] - eye[0], dy = p[1] - eye[1], dz = p[2] - eye[2], d2 = dx * dx + dy * dy + dz * dz + p[3] * p[3], d = Math.sqrt(dx * dx + dy * dy + dz * dz + 1);
                    // Lichtstärke in cd → Bestrahlung in lx → Szeneneinheiten
                    for (int k = 0; k < 3; k++) blitzE[k] += c[k] / d2 / Sky.CD_PER_UNIT;
                    double el = (0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]) / d2;
                    if (el > best) { best = el; blitzDir = new double[]{dx / d, Math.max(0.05, dy / d), dz / d}; }
                }
            }
        }
    }

    /** Ringe des Regens und Blitzlicht für die See (im Rückruf von {@code Meer.draw}). */
    void see(OceanGl.Light ol, double pre) {
        if (regnet()) {
            ol.detailTexture = rainGl.rippleTexture();
            ol.detailTile = rainGl.rippleTile();
            ol.detailStrength = rainGl.rippleStrength() * Math.min(1, rain.snapshot().rateAt(rain.eyeX, rain.eyeZ) / Math.max(1e-3, rain.rate) + 0.2);
        }
        if (blitzt()) {
            int n = Math.min(8, blitzGl.lightCount());
            for (int i = 0; i < n; i++) {
                double[] lp = blitzGl.lightPos(i), lr = blitzGl.lightRgb(i);
                for (int k = 0; k < 4; k++) ol.flashPos[i * 4 + k] = (float) lp[k];
                for (int k = 0; k < 3; k++) ol.flashRgb[i * 4 + k] = (float) (lr[k] * pre);
            }
            ol.flashCount = n;
        }
    }

    /** Blitzlicht in den Wolken (beim Auflegen). */
    void wolken(com.dan.cloud.gl.CloudGl.Light l, double pre) {
        l.flashCount = 0;
        if (!blitzt()) return;
        int n = Math.min(4, blitzGl.cloudLightCount());
        for (int i = 0; i < n; i++) {
            double[] c = blitzGl.cloudLight(i);
            for (int k = 0; k < 3; k++) {
                l.flashPos[i * 4 + k] = (float) c[k];
                l.flashRgb[i * 4 + k] = (float) (c[3] * Lightning.CHANNEL_RGB[k] * pre);
            }
        }
        l.flashCount = n;
    }

    /** Auslöschung und Streuung der Luft für das Leuchten der Blitze (1/m): Dunst aus der Sicht, dazu Regen. */
    double[] luft(double sichtKm, double[] eye) {
        double haze = 2.996 / (Math.max(0.5, sichtKm) * 1000);
        double r = regnet() ? rain.scatter(eye[0], eye[1], eye[2]) * 3e-3 : 0;
        return new double[]{haze + r, haze * 0.9 + r * 0.98};
    }

    /** Blitze ins Rundumbild (Spiegelung in der See), das gerade gebunden ist. */
    void rundum(GL4 gl, int w, int h, double[] eye, double pre, double sichtKm) {
        if (!blitzt()) return;
        double[] air = luft(sichtKm, eye);
        blitzGl.drawPanorama(gl, w, h, eye, pre, air[0], air[1], cloudBase);
    }

    /** Die Szenentiefe kopieren (Regen braucht sie, während er mit Tiefentest ins HDR-Ziel zeichnet). */
    int tiefe(GL4 gl, int hdrFbo, int w, int h) {
        if (tiefeTex == 0 || tw != w || th != h) {
            Gpu.deleteFbos(gl, tiefeFbo);
            Gpu.deleteTextures(gl, tiefeTex);
            tiefeTex = Gpu.texture(gl, w, h, GL4.GL_DEPTH_COMPONENT32F, false);
            gl.glBindTexture(GL.GL_TEXTURE_2D, tiefeTex);
            gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_NEAREST);
            gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_NEAREST);
            gl.glBindTexture(GL.GL_TEXTURE_2D, 0);
            tiefeFbo = Gpu.depthFbo(gl, tiefeTex);
            tw = w;
            th = h;
        }
        gl.glBindFramebuffer(GL4.GL_READ_FRAMEBUFFER, hdrFbo);
        gl.glBindFramebuffer(GL4.GL_DRAW_FRAMEBUFFER, tiefeFbo);
        gl.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL.GL_DEPTH_BUFFER_BIT, GL.GL_NEAREST);
        gl.glBindFramebuffer(GL.GL_FRAMEBUFFER, hdrFbo);
        gl.glViewport(0, 0, w, h);
        return tiefeTex;
    }

    /** Licht für Nebel und Regen (vorbelichtet): Sonne und Mond (im Blitz der Blitz), Himmel oben und am Horizont. */
    static double[][] licht(Sky.State st, double pre, double[] amb, double shade, double[] heading, double[] blitzE, double[] blitzDir) {
        double[] sunC = Wetter.scale(st.sunColor, pre * shade), moonC = Wetter.scale(st.moonColor, pre * shade), moonD = st.moonDir;
        double[] fe = Wetter.scale(blitzE, pre);
        if (0.2126 * fe[0] + 0.7152 * fe[1] + 0.0722 * fe[2] > 0.2126 * moonC[0] + 0.7152 * moonC[1] + 0.0722 * moonC[2]) { moonC = fe; moonD = blitzDir; }
        double[] top = Wetter.scale(amb, pre / Math.PI), hor = Wetter.scale(st.radiance(heading), pre);
        return new double[][]{st.sunDir, sunC, moonD, moonC, top, hor};
    }

    /** Der Nebel über das HDR-Bild (ohne Tiefentest, mit der Szenentiefe), Ziel ist gebunden. */
    void zeichneNebel(GL4 gl, float[] inv, double[] eye, boolean reversed, int w, int h, int depthTex, double[][] L, boolean still, int frame) {
        if (!nebelt()) return;
        Fog.Snapshot s = fog.snapshot();
        fogGl.update(gl, s, eye.clone());
        if (fogCheck.isEmpty() && frame > 100) fogCheck = fogGl.selfCheck(gl, fog, eye.clone());
        FogGl.View v = new FogGl.View();
        v.invViewProj = inv;
        v.eye = eye.clone();
        v.reversedZ = reversed;
        v.width = w;
        v.height = h;
        v.depthTexture = depthTex;
        v.maxDistance = 9000;
        v.jitter = still ? 0.5 : -1;
        v.frame = frame;
        FogGl.Light l = new FogGl.Light();
        l.sunDir = L[0];
        l.sunColor = L[1];
        l.moonDir = L[2];
        l.moonColor = L[3];
        l.ambientTop = L[4];
        l.ambientHorizon = L[5];
        fogGl.draw(gl, s, v, l);
    }

    /** Der Regen ins HDR-Ziel mit Tiefe (Streifen mit Tiefentest), Vorhänge und Krönchen mit der Kopie der Tiefe. */
    void zeichneRegen(GL4 gl, float[] vp, float[] inv, double[] eye, double[] fwd, boolean reversed, double fovy, int w, int h, int depthCopy, double[][] L,
                      double unterWolke, double fogDist) {
        if (!regnet()) return;
        RainGl.View v = new RainGl.View();
        v.viewProj = vp;
        v.invViewProj = inv;
        v.eye = eye.clone();
        v.forward = fwd.clone();
        v.reversedZ = reversed;
        v.near = Renderer.NEAR;
        v.far = Renderer.FAR;
        v.fovyDeg = fovy;
        v.width = w;
        v.height = h;
        v.depthTexture = depthCopy;
        RainGl.Light l = new RainGl.Light();
        l.sunDir = L[0];
        l.sunColor = L[1];
        l.moonDir = L[2];
        l.moonColor = L[3];
        // im Regen steht man unter der Wolke: Licht von oben nur, so weit sie es durchlässt (aus Lighthouse)
        l.skyTop = Wetter.scale(L[4], unterWolke);
        l.skyHorizon = L[5];
        l.fogDistance = fogDist;
        gl.glDepthFunc(reversed ? GL.GL_GREATER : GL.GL_LESS);
        rainGl.draw(gl, v, l, 1.0);
        gl.glDisable(GL.GL_BLEND);
        gl.glDepthMask(true);
    }

    /** Blitzkanäle (Tiefentest gegen die Szene) und das Leuchten der Luft (mit der Kopie der Tiefe) ins HDR-Ziel. */
    void zeichneBlitze(GL4 gl, float[] vp, float[] inv, double[] eye, boolean reversed, double fovy, int w, int h, int hdrFbo, int depthCopy, double pre, double sichtKm) {
        if (!blitzt()) return;
        double[] air = luft(sichtKm, eye);
        LightningGl.View v = new LightningGl.View();
        v.viewProj = vp;
        v.invViewProj = inv;
        v.eye = eye.clone();
        v.reversedZ = reversed;
        v.width = w;
        v.height = h;
        v.fovyDeg = fovy;
        v.targetFbo = hdrFbo;
        v.depthTest = true;
        v.depthTexture = depthCopy;
        v.preExposure = pre;
        v.extinction = air[0];
        v.scatter = air[1];
        v.ceiling = cloudBase;
        blitzGl.drawChannels(gl, v);
        blitzGl.drawGlow(gl, v);
        if (blitzCheck.isEmpty() && blitzSnap != null && !blitzSnap.bolts().isEmpty()) {
            blitzCheck = blitzGl.selfCheck(gl, blitzSnap);
            blitzGl.update(gl, blitzSnap);
        }
    }

    /** Ein Blitz von Hand: Erdblitz 4 bis 8 km in Blickrichtung über der Bucht, oder in das Maritim. */
    public void blitzVonHand(double[] eye, double[] fwd, boolean turm) {
        if (turm) { blitz.strikeAttractor(0); return; }
        double h = Math.hypot(fwd[0], fwd[2]), fx = h > 1e-6 ? fwd[0] / h : -1, fz = h > 1e-6 ? fwd[2] / h : 0;
        // über der See: 4 bis 8 km in Blickrichtung, mindestens 2 km seewärts des Auges (sonst seewärts geschoben)
        double d = 4000 + 4000 * Math.random();
        double x = eye[0] + fx * d, z = eye[2] + fz * d;
        // seewärts: längs der Seebrücke (nach Nordost); liegt der Punkt nicht mindestens 2 km draußen, wird er geschoben
        double sl = Math.hypot(Site.SCHLEIFE.x(), Site.SCHLEIFE.z()), sx = Site.SCHLEIFE.x() / sl, sz = Site.SCHLEIFE.z() / sl;
        double s = (x - eye[0]) * sx + (z - eye[2]) * sz;
        if (s < 2000) { double m = 2000 - s + 1500 * Math.random(); x += sx * m; z += sz * m; }
        blitz.strike(x, z);
    }

    /** Für das HUD: drei Zeilen (Regen, Nebel, Gewitter). */
    public String[] describe(double[] eye) {
        String r = !rainOk ? "!Regen: com.dan.rain nicht gestartet" : rain.rate <= 0.02 ? "Regen (rain): trocken"
                : String.format(Locale.GERMAN, "Regen (rain): %.1f mm/h unter den Vorhängen, hier %.1f mm/h · Sicht im Regen %.1f km · %s%s",
                rain.rate, rain.snapshot().rateAt(eye[0], eye[2]), Math.min(99, Rain.visibilityKm(Math.max(0.01, rain.snapshot().rateAt(eye[0], eye[2])))),
                rainGl.info(), rainCheck.isEmpty() ? "" : " · " + rainCheck);
        Fog.Snapshot fs = fog.snapshot();
        String n = !fogOk ? "!Nebel: com.dan.fog nicht gestartet" : !fs.any() ? "Seenebel (fog): keiner"
                : String.format(Locale.GERMAN, "Seenebel (fog): %.0f %% bedeckt, Sicht in der Bank %.0f m, bis %.0f m über dem Pegel · hier %.0f m%s",
                fog.coverage * 100, fog.visibilityM, fog.top, Math.min(99_000, fog.visibilityAt(eye[0], eye[1], eye[2])), fogCheck.isEmpty() ? "" : " · " + fogCheck);
        String g = !blitzOk ? "!Gewitter: com.dan.lightning nicht gestartet" : blitz.level() == Lightning.Level.OFF ? "Gewitter (lightning): keins"
                : String.format(Locale.GERMAN, "Gewitter (lightning): %s · %d Blitze, %d Erdblitze, %d in die Türme%s%s", stufe(blitz.level()), blitz.flashes(),
                blitz.groundFlashes(), blitz.attractorHits(), blitz.lastThunder() == null ? "" : String.format(Locale.GERMAN, " · letzter Donner %.1f km, %.0f dB",
                blitz.lastThunder().distance() / 1000, blitz.lastThunder().levelDb()), blitzCheck.isEmpty() ? "" : " · " + blitzCheck);
        return new String[]{r, n, g};
    }

    /** Die Stufe auf Deutsch (Gewitter 0..4 in der Verdrahtung). */
    public static String stufe(Lightning.Level l) {
        return switch (l.ordinal()) { case 0 -> "keins"; case 1 -> "Wetterleuchten"; case 2 -> "mäßig"; case 3 -> "kräftig"; default -> "schwer"; };
    }

    static double smooth(double a, double b, double x) {
        double t = Math.max(0, Math.min(1, (x - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }

    void dispose(GL4 gl) {
        if (rainOk) rainGl.dispose(gl);
        if (fogOk) fogGl.dispose(gl);
        if (blitzOk) blitzGl.dispose(gl);
        Gpu.deleteFbos(gl, tiefeFbo);
        Gpu.deleteTextures(gl, tiefeTex);
    }
}
