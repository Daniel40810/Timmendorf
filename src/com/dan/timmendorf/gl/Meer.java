package com.dan.timmendorf.gl;

import com.dan.ocean.Ocean;
import com.dan.ocean.gl.OceanGl;
import com.dan.sky.Sky;
import com.dan.timmendorf.scene.Terrain;
import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL4;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Seit Phase 4 (aus Sylt Phase 3): die Ostsee aus {@code com.dan.ocean}: Windsee aus dem Wind der Verdrahtung, dazu der
 * Seegang der Lübecker Bucht bei Seewind aus Nordost (H_s 0,45 m, T_p 3,6 s [G], {@code Wiring.seeHs}), Pegel aus der
 * Verdrahtung. Der Grund ist das Hauptraster (DGM1, Strandprofil mit Riffen, GEBCO, BSH; 10 m): zum Ufer hin werden die
 * Wellen flacher, im seichten Wasser scheint der Sand durch (die Ostsee ist klar: Sicht 3–5 m [G]), am Strand liegt ein
 * Schaumsaum; außerhalb des Hauptrasters offene See (−20 m). Senken im Land, die nicht mit der See verbunden sind, blendet
 * eine Maske aus ({@link #binnen}); der Hemmelsdorfer See und die Trave liegen tief genug und bekommen Wasser im Pegel.
 * Brandung an den Riffen und Auflauf kommen mit {@code surf} (Phase 13). Startet das Meer nicht (kein Compute),
 * bleibt der Platzhalter aus Phase 1. Seit Phase 8: die Rümpfe der nächsten Boote sparen die See aus, das Kielwasser aller
 * Boote und Fähren ({@code com.dan.wake}, Kacheln nah 256 m und weit 2 km) hebt und neigt sie und legt Schaum auf.
 */
public final class Meer {
    public final Ocean ocean = Ocean.beaufort(3);
    final OceanGl oceanGl = new OceanGl();
    /** Seit Phase 8: das Kielwasser aller Boote als Höhenfeld-Kacheln aus {@code com.dan.wake} (Compute), Eingang der See. */
    final com.dan.wake.gl.WakeGl kielGl = new com.dan.wake.gl.WakeGl();
    boolean ok, kielOk;
    /** Kielwasser zeigen (Bedienfeld). */
    public volatile boolean showKiel = true;
    String check = "";
    int maskTex;
    /** Grund außerhalb des Hauptrasters (m NHN) [G]. */
    public static final double DRAUSSEN = -20;
    /** Höhe, bis zu der eine Zelle mit der See verbunden sein kann (m NHN): Watt und Strand liegen darunter. */
    public static final double VERBUNDEN_BIS = 1.5;

    Meer() { ocean.seed = 2026; }

    void init(GL4 gl, Terrain t, boolean compute, java.util.function.Consumer<String> err) {
        maskTex = maskTexture(gl, binnen(t));
        if (!compute) {
            System.err.println("Timmendorf: Meer braucht Compute-Shader, es bleibt der Platzhalter");
            return;
        }
        try {
            oceanGl.init(gl);
            ok = true;
            System.out.println("Timmendorf: Meer " + oceanGl.info());
            try {
                kielGl.init(gl, com.dan.wake.Wake.Quality.HIGH);
                kielOk = true;
            } catch (RuntimeException e) {
                System.err.println("Timmendorf: Kielwasser nicht verfügbar: " + e.getMessage());
            }
        } catch (RuntimeException e) {
            System.err.println("Timmendorf: Meer nicht verfügbar: " + e.getMessage());
            err.accept("Das Meer (com.dan.ocean) lässt sich nicht starten, es bleibt der Platzhalter aus Phase 1.\n" + e.getMessage());
        }
    }

    /**
     * Maske der Senken im Land (1 = nicht mit der See verbunden): von den Rändern des Hauptrasters aus über alle Zellen
     * unter {@link #VERBUNDEN_BIS} m geflutet; was dabei nicht erreicht wird und tiefer liegt, ist Binnenland.
     */
    public static byte[] binnen(Terrain t) { return binnen(t, VERBUNDEN_BIS); }

    /**
     * Seit Phase 14: dieselbe Maske bis zur Höhe bis (m NHN): bei Sturmflut läuft die See über Strand und Promenade in
     * alles, was mit ihr verbunden tiefer liegt.
     */
    public static byte[] binnen(Terrain t, double bis) {
        int n = Terrain.N;
        boolean[] see = new boolean[n * n];
        ArrayDeque<Integer> q = new ArrayDeque<>();
        for (int i = 0; i < n; i++)
            for (int k : new int[]{i, (n - 1) * n + i, i * n, i * n + n - 1})
                if (!see[k] && t.at(k % n, k / n) < bis) { see[k] = true; q.add(k); }
        while (!q.isEmpty()) {
            int k = q.poll(), i = k % n, j = k / n;
            int[][] nb = {{i - 1, j}, {i + 1, j}, {i, j - 1}, {i, j + 1}};
            for (int[] c : nb) {
                if (c[0] < 0 || c[1] < 0 || c[0] >= n || c[1] >= n) continue;
                int m = c[1] * n + c[0];
                if (see[m] || t.at(c[0], c[1]) >= bis) continue;
                see[m] = true;
                q.add(m);
            }
        }
        byte[] mask = new byte[n * n];
        for (int k = 0; k < mask.length; k++) mask[k] = (byte) (see[k] ? 0 : 255);
        return mask;
    }

    private volatile double maskBis = VERBUNDEN_BIS;
    private volatile byte[] neueMaske;
    private volatile boolean maskRechnet;

    /**
     * Seit Phase 14: die Maske folgt dem Pegel (über 1,35 m): neu gerechnet in einem eigenen Faden, sobald der Pegel um 5 cm
     * gewandert ist, und im nächsten Bild hochgeladen.
     */
    void pegelMaske(GL4 gl, Terrain t, double pegel) {
        byte[] m = neueMaske;
        if (m != null && maskTex != 0) {
            neueMaske = null;
            int n = Terrain.N;
            gl.glBindTexture(GL.GL_TEXTURE_2D, maskTex);
            gl.glPixelStorei(GL.GL_UNPACK_ALIGNMENT, 1);
            gl.glTexSubImage2D(GL.GL_TEXTURE_2D, 0, 0, 0, n, n, GL4.GL_RED, GL.GL_UNSIGNED_BYTE, java.nio.ByteBuffer.wrap(m));
            gl.glPixelStorei(GL.GL_UNPACK_ALIGNMENT, 4);
            gl.glBindTexture(GL.GL_TEXTURE_2D, 0);
        }
        double soll = Math.max(VERBUNDEN_BIS, pegel + 0.15);
        if (!maskRechnet && Math.abs(soll - maskBis) > 0.05) {
            maskRechnet = true;
            maskBis = soll;
            Thread th = new Thread(() -> { try { neueMaske = binnen(t, soll); } finally { maskRechnet = false; } }, "Maske der See");
            th.setDaemon(true);
            th.start();
        }
    }

    /** Ist die Maske auf dem Stand (nichts in Arbeit, nichts hochzuladen)? */
    boolean maskeFertig() { return !maskRechnet && neueMaske == null; }

    /** Bis zu welcher Höhe die See gerade mit dem Land verbunden ist (m NHN). */
    double maskBis() { return maskBis; }

    static int maskTexture(GL4 gl, byte[] m) {
        int n = Terrain.N;
        int[] tex = new int[1];
        gl.glGenTextures(1, tex, 0);
        gl.glBindTexture(GL.GL_TEXTURE_2D, tex[0]);
        gl.glTexStorage2D(GL.GL_TEXTURE_2D, 1, GL4.GL_R8, n, n);
        gl.glPixelStorei(GL.GL_UNPACK_ALIGNMENT, 1);
        gl.glTexSubImage2D(GL.GL_TEXTURE_2D, 0, 0, 0, n, n, GL4.GL_RED, GL.GL_UNSIGNED_BYTE, java.nio.ByteBuffer.wrap(m));
        gl.glPixelStorei(GL.GL_UNPACK_ALIGNMENT, 4);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE);
        gl.glBindTexture(GL.GL_TEXTURE_2D, 0);
        return tex[0];
    }

    /**
     * Die See zeichnen (nach dem Gelände, mit Tiefentest): Licht aus dem Himmelszustand (vorbelichtet) oder dem
     * Platzhalter, Spiegelung aus dem Rundumbild, Wolkenschatten, Grund aus der Höhentextur.
     */
    /** Seit Phase 13: was die See noch mitbekommt (Ringe des Regens, Blitzlicht), kurz vor dem Zeichnen. */
    volatile java.util.function.Consumer<OceanGl.Light> zusatz;

    void draw(GL4 gl, float[] vp, double[] eye, boolean reversedZ, Sky.State st, double pre, int panoTex, int cloudTex, double[] cloudRect,
              int bedTex, double[] sun, double[] sunFallback, double fogFallback, com.dan.wake.Wake wake, java.util.List<double[]> rumpfe) {
        oceanGl.update(gl, ocean);
        // seit Phase 8: Rümpfe sparen die See aus (die acht nächsten), das Kielwasser kommt als Kacheln dazu
        java.util.List<com.dan.ocean.HullMask> hm = new java.util.ArrayList<>();
        for (double[] r : rumpfe) hm.add(new com.dan.ocean.HullMask(r[0], r[1], r[2], r[3], r[4], r[5]));
        ocean.hulls = hm;
        boolean kiel = kielOk && showKiel && wake != null;
        if (kiel) kielGl.update(gl, wake, eye[0], eye[2]);
        OceanGl.View ov = new OceanGl.View();
        ov.viewProj = vp;
        ov.eye = eye.clone();
        ov.reversedZ = reversedZ;
        OceanGl.Light ol = new OceanGl.Light();
        ol.sunDir = sun;
        ol.skyTexture = panoTex;
        if (st != null) {
            ol.sunColor = Wetter.scale(st.sunColor, pre);
            ol.moonDir = st.moonDir;
            ol.moonColor = Wetter.scale(st.moonColor, pre);
            ol.fogDistance = Wetter.fogDistance(st);
            if (cloudTex != 0) {
                ol.shadowTexture = cloudTex;
                ol.shadowRect = cloudRect;
            }
        } else {
            ol.sunColor = sunFallback;
            ol.fogDistance = fogFallback;
        }
        ol.bedTexture = bedTex;
        ol.bedRect = new double[]{Terrain.X0, Terrain.Z0, Terrain.N * Terrain.CELL};
        ol.bedScale = Terrain.HMAX - Terrain.HMIN;
        ol.bedOffset = Terrain.HMIN;
        ol.bedOutside = DRAUSSEN;
        ol.bedTurbidity = 0.7;
        ol.bedColor = new double[]{0.30, 0.26, 0.18};
        ol.maskTexture = maskTex;
        ol.maskRect = new double[]{Terrain.X0, Terrain.Z0, Terrain.N * Terrain.CELL};
        ol.maskMode = -1;
        ol.tileBreak = 0.6;
        if (kiel) {
            ol.kielTexture = kielGl.texture();
            ol.kielRect0 = kielGl.rect(0);
            ol.kielRect1 = kielGl.rect(1);
        }
        java.util.function.Consumer<OceanGl.Light> z = zusatz;
        if (z != null) z.accept(ol);
        gl.glDepthFunc(reversedZ ? GL.GL_GREATER : GL.GL_LESS);
        oceanGl.draw(gl, ocean, ov, ol);
    }

    String describe() {
        return ok ? String.format(Locale.GERMAN, "See (ocean) %s · Wind %.1f m/s aus %03.0f° · Dünung %.1f m, %.0f s aus %03.0f° · Pegel %+.2f m · %s%s",
                oceanGl.info(), ocean.windSpeed, ocean.windFromDeg, ocean.swellHeight, ocean.swellPeriod, ocean.swellFromDeg, ocean.waterLevel,
                kielOk ? kielGl.info() : "Kielwasser aus",
                check.isEmpty() ? "" : " · " + check) : "!See: com.dan.ocean nicht gestartet, Platzhalter aus Phase 1";
    }

    void dispose(GL4 gl) {
        if (ok) oceanGl.dispose(gl);
        if (kielOk) kielGl.dispose(gl);
        Gpu.deleteTextures(gl, maskTex);
    }
}
