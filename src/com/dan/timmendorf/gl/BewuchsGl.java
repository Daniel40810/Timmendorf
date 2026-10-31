package com.dan.timmendorf.gl;

import com.dan.reed.Reed;
import com.dan.reed.gl.ReedGl;
import com.dan.timmendorf.scene.Bewuchs;
import com.dan.timmendorf.scene.Terrain;
import com.dan.tree.Tree;
import com.dan.tree.TreeSpecies;
import com.dan.tree.TreeWind;
import com.dan.tree.gl.TreeGl;
import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL4;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Seit Phase 5a: der Bewuchs im Bild. Die Bäume aus {@link Bewuchs} zeichnet je Art ein {@code TreeGl} aus
 * {@code com.dan.tree} (instanziert, vier Stufen, Wind und Jahreszeit); Strandhafer, Schilf, Binsen und Gras um das Auge
 * ein {@code ReedGl} aus {@code com.dan.reed}. Beide schreiben linear und vorbelichtet in den HDR-Puffer, mit Tiefe.
 * <p>
 * Bäume gibt es bis zur Sichtweite der Qualitätsstufe ({@link #weite}); ab 70 % davon dünnen sie nach einem Hash aus.
 * Damit nicht jedes Bild alle Bäume durchgeht, bekommt jedes {@code TreeGl} nur die Bäume im Umkreis (neu, wenn das
 * Auge über ein Viertel der Weite gewandert ist).
 */
public final class BewuchsGl {
    /** Sichtweite der Bäume je Qualitätsstufe (m) [G nach Tempo]. */
    public static double weite(Quality q) {
        return switch (q) { case NIEDRIG -> 500; case MITTEL -> 1000; case HOCH -> 1800; case ULTRA -> 3000; };
    }

    /** Wie hoch eine Variante einer Art mindestens skaliert wird und höchstens [G]. */
    public static final float MIN_SKALA = 0.45f, MAX_SKALA = 1.6f;

    final Map<String, TreeGl> arten = new LinkedHashMap<>();
    final Map<String, TreeSpecies> spezies = new LinkedHashMap<>();
    final TreeWind wind = new TreeWind();
    public final Reed halme = Reed.breeze();
    final ReedGl halmeGl = new ReedGl();
    private Bewuchs bewuchs;
    private Terrain terrain;
    private double fillX = Double.NaN, fillZ = Double.NaN, fillWeite;
    private boolean ok;
    public String info = "aus";
    public volatile boolean showBaeume = true, showHalme = true;
    private long dreiecke;
    private int imUmkreis;

    /** Arten laden und die Bäume verteilen (vor {@link #init}, ohne GL). */
    public void use(Bewuchs b, Terrain t) {
        bewuchs = b;
        terrain = t;
        for (Bewuchs.Gruppe g : Bewuchs.Gruppe.values())
            for (String key : g.arten) {
                if (spezies.containsKey(key)) continue;
                Tree.Art a = Tree.art(key);
                if (a != null) spezies.put(key, a.create());
            }
        halme.seed = 2026;
        halme.wind = null;
    }

    public void init(GL4 gl) {
        try {
            for (var e : spezies.entrySet()) {
                TreeGl tg = new TreeGl(e.getValue());
                // Erlen haben sehr feine Stufen 0 und 1 (bis 2,8 Mio. Dreiecke): erst ab Stufe 1, Stufe 2 schon ab 2 Höhen
                if (e.getKey().equals("schwarzerle")) {
                    tg.minLevel = 1;
                    tg.lodDistance = new float[]{0.8f, 2.0f, 12f};   // Hülle ab 12 Höhen (Phase 7: ab 6 sah man sie an der Seebrücke)
                }
                // Stufe 3 ist eine Hülle, aus der Nähe ein glatter Körper: erst ab 25 Höhen (Vorgabe 14) [G nach den Bildern vom 30.10.]
                else tg.lodDistance = new float[]{1.4f, 3.5f, 25f};
                // Greasewood (Strauch) hat bis 1,9 Mio. Dreiecke in Stufe 0: in der Szene erst ab Stufe 2 (bis 43 000)
                if (e.getKey().equals("greasewood")) tg.minLevel = 2;
                tg.init(gl);
                arten.put(e.getKey(), tg);
            }
            halmeGl.init(gl);
            ok = true;
            info = String.format(Locale.GERMAN, "%d Arten", arten.size());
        } catch (RuntimeException e) {
            ok = false;
            info = "!Bewuchs startet nicht: " + e.getMessage();
            System.err.println("Timmendorf: " + info);
        }
    }

    public boolean ok() { return ok; }

    /**
     * Seit Phase 19 (F24): Art und Variante eines Baums {Index in {@code gruppe.arten}, Variante}. Für den Anteil
     * {@code gruppe.echt} zuerst die eigentliche Art (die erste), sonst eine der übrigen nach der Kennzahl; genommen wird die
     * erste, deren Varianten die Höhe mit einer Skala zwischen {@link #MIN_SKALA} und {@link #MAX_SKALA} erreichen, und
     * davon die Variante, die der Höhe am nächsten kommt (bei mehreren ähnlichen nach der Kennzahl). Erreicht keine die Höhe,
     * bleibt es wie bis Phase 18 (Art und Variante nach der Kennzahl, die Skala begrenzt).
     */
    int[] waehle(Bewuchs.Baum b) {
        return waehle(b, key -> {
            TreeGl tg = arten.get(key);
            if (tg == null) return null;
            float[] h = new float[tg.species.variants.length];
            for (int v = 0; v < h.length; v++) h[v] = tg.height(v);
            return h;
        });
    }

    /** Wie oben, die Höhen der Varianten je Art von außen (null = Art nicht da); ohne GL, für die Prüfung. */
    public static int[] waehle(Bewuchs.Baum b, java.util.function.Function<String, float[]> hoehen) {
        String[] a = b.gruppe().arten;
        int n = a.length, k = b.kennzahl();
        boolean echtZuerst = ((k >>> 12) % 1000) / 1000.0 < b.gruppe().echt;
        int rest = n > 1 ? 1 + (k >>> 8) % (n - 1) : 0;
        int[] reihe = new int[n];
        int m = 0;
        if (echtZuerst || n == 1) reihe[m++] = 0;
        for (int i = 0; i < n - 1 && n > 1; i++) reihe[m++] = 1 + (rest - 1 + i) % (n - 1);
        if (!echtZuerst && n > 1) reihe[m++] = 0;
        for (int i = 0; i < m; i++) {
            float[] hs = hoehen.apply(a[reihe[i]]);
            if (hs == null) continue;
            int nv = hs.length, best = -1;
            float bestF = Float.MAX_VALUE;
            for (int v = 0; v < nv; v++) {
                float hv = Math.max(0.3f, hs[v]), f = b.hoehe() / hv;
                if (f < MIN_SKALA || f > MAX_SKALA) continue;
                // am nächsten an 1, mit etwas Zufall aus der Kennzahl, damit nicht jeder Baum dieselbe Variante hat
                float d = Math.abs((float) Math.log(f)) + 0.15f * (((k >>> (4 + v * 3)) & 7) / 7f);
                if (d < bestF) { bestF = d; best = v; }
            }
            if (best >= 0) return new int[]{reihe[i], best};
        }
        int i0 = (k >>> 8) % n;
        float[] hs = hoehen.apply(a[i0]);
        if (hs == null) return null;
        return new int[]{i0, (k >>> 4) % hs.length};
    }

    /** Bäume im Umkreis an die Arten verteilen. */
    private void fuellen(double ex, double ez, double weite) {
        for (TreeGl tg : arten.values()) tg.clear();
        double r = weite * 1.25, r2 = r * r;
        int n = 0;
        for (Bewuchs.Baum b : bewuchs.baeume) {
            double dx = b.x() - ex, dz = b.z() - ez;
            if (dx * dx + dz * dz > r2) continue;
            int[] wahl = waehle(b);
            if (wahl == null) continue;
            TreeGl tg = arten.get(b.gruppe().arten[wahl[0]]);
            int v = wahl[1];
            float s = Math.max(MIN_SKALA, Math.min(MAX_SKALA, b.hoehe() / Math.max(0.3f, tg.height(v))));
            float y = (float) terrain.height(b.x(), b.z()) - 0.15f;
            tg.add(v, b.x(), y, b.z(), b.drehung(), s);
            n++;
        }
        imUmkreis = n;
        fillX = ex;
        fillZ = ez;
        fillWeite = weite;
    }

    /** Je Bild: Jahreszeit, Wind, Umkreis, Stufen; dann zeichnen (Ziel mit Tiefe gebunden). */
    public void draw(GL4 gl, float[] vp, double[] eye, boolean reversedZ, Quality q, int tag, double windSpeed, double windFromDeg, double t,
                     double[] sunDir, double[] sun, double[] skyPi, double fogDist, int cloudShadow, double[] cloudRect) {
        if (!ok || bewuchs == null) return;
        double weite = weite(q);
        dreiecke = 0;
        if (showBaeume && !bewuchs.baeume.isEmpty()) {
            if (Double.isNaN(fillX) || Math.hypot(eye[0] - fillX, eye[2] - fillZ) > weite / 4 || weite != fillWeite) fuellen(eye[0], eye[2], weite);
            wind.speed = (float) windSpeed;
            wind.fromDeg = (float) windFromDeg;
            wind.gustiness = 0.5f;
            TreeGl.View v = new TreeGl.View();
            v.viewProj = vp;
            v.eye = eye.clone();
            v.tonemap = false;
            v.exposure = 1;
            TreeGl.Light l = new TreeGl.Light();
            l.sunDir = sunDir.clone();
            l.sunColor = sun.clone();
            l.skyColor = skyPi.clone();
            l.groundColor = new double[]{skyPi[0] * 0.3, skyPi[1] * 0.28, skyPi[2] * 0.2};
            l.fogColor = new double[]{skyPi[0] * 2.5, skyPi[1] * 2.5, skyPi[2] * 2.5};
            l.fogDistance = fogDist;
            for (TreeGl tg : arten.values()) {
                float h = 0;
                for (int i = 0; i < tg.species.variants.length; i++) h += tg.height(i);
                h = Math.max(1, h / tg.species.variants.length);
                tg.maxDistance = (float) (weite / h);
                tg.fadeDistance = (float) (weite * 0.7 / h);
                tg.setSeason(tag, 0);
                tg.setWind(wind);
                tg.update(gl, eye);
            }
            gl.glEnable(GL.GL_DEPTH_TEST);
            gl.glDepthMask(true);
            gl.glDepthFunc(reversedZ ? GL.GL_GREATER : GL.GL_LESS);
            gl.glDisable(GL.GL_BLEND);
            for (TreeGl tg : arten.values()) {
                tg.draw(gl, v, l, t);
                dreiecke += tg.triangles();
            }
            gl.glEnable(GL.GL_CULL_FACE);
        }
        double ueber = eye[1] - terrain.height(eye[0], eye[2]);
        if (showHalme && ueber < halme.range(Reed.Kind.SCHILF) * 1.2) {
            halme.windSpeed = windSpeed;
            halme.windFromDeg = windFromDeg;
            halme.season = jahreszeit(tag);
            halme.quality = switch (q) { case NIEDRIG -> Reed.Quality.LOW; case MITTEL -> Reed.Quality.MEDIUM; case HOCH -> Reed.Quality.HIGH; case ULTRA -> Reed.Quality.ULTRA; };
            halmeGl.update(gl, halme, eye[0], eye[2], (x, z, k) -> bewuchs.dichte(x, z, halm(k), terrain) * (k == Reed.Kind.STEPPE ? 0 : 1), terrain::height);
            ReedGl.View v = new ReedGl.View();
            v.viewProj = vp;
            v.eye = eye.clone();
            v.reversedZ = reversedZ;
            ReedGl.Light l = new ReedGl.Light();
            l.sunDir = sunDir.clone();
            l.sunColor = sun.clone();
            l.skyColor = skyPi.clone();
            l.skyWeight = 1;
            l.fogColor = new double[]{skyPi[0] * 2.5, skyPi[1] * 2.5, skyPi[2] * 2.5};
            l.fogDistance = fogDist;
            l.shadowTexture = cloudShadow;
            l.shadowRect = cloudRect;
            halmeGl.draw(gl, halme, v, l, t);
        }
        info = String.format(Locale.GERMAN, "%,d Bäume im Umkreis von %.0f m, %,.1f Mio. Dreiecke · Halme: %s", imUmkreis, weite * 1.25, dreiecke / 1e6, halmeGl.info());
    }

    static Bewuchs.Halm halm(Reed.Kind k) {
        return switch (k) {
            case SCHILF -> Bewuchs.Halm.SCHILF;
            case BINSE -> Bewuchs.Halm.BINSE;
            case STRANDHAFER -> Bewuchs.Halm.STRANDHAFER;
            default -> Bewuchs.Halm.GRAS;
        };
    }

    /** Jahreszeit der Halme 0 Sommer … 1 Winter aus dem Tag im Jahr (Küste: früh grün, spät gelb) [G]. */
    public static double jahreszeit(int tag) {
        if (tag < 90 || tag > 345) return 1;
        if (tag < 140) return 1 - (tag - 90) / 50.0;
        if (tag < 230) return 0;
        if (tag < 300) return 0.75 * (tag - 230) / 70.0;
        return 0.75 + 0.25 * (tag - 300) / 45.0;
    }

    public long dreiecke() { return dreiecke; }

    public void dispose(GL4 gl) {
        for (TreeGl tg : arten.values()) tg.dispose(gl);
        arten.clear();
        halmeGl.dispose(gl);
        ok = false;
    }
}
