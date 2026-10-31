package com.dan.timmendorf.gl;

import com.dan.sail.Sail;
import com.dan.sail.gl.SailGl;
import com.dan.timmendorf.scene.FaehrenForm;
import com.dan.timmendorf.scene.Flotte;
import com.jogamp.opengl.GL4;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleBinaryOperator;

/**
 * Seit Phase 8a: die Boote der Szene auf der Grafikkarte. Segelboote, Kiter und die Passat aus {@code com.dan.sail}
 * ({@code BoatShape} über {@code gl.SailGl}), die Fähren aus {@link FaehrenForm} als fremde Dreiecke im selben Puffer. Je
 * Bild neu gebaut; was weiter weg ist, als es für seine Größe lohnt (300 Längen, Passat und Fähren immer), bleibt weg.
 * Licht vorbelichtet wie Bauten und Bewuchs (Sonne und Himmel als Bestrahlung/π), Ausgabe linear in den HDR-Puffer.
 */
final class BooteGl {
    final SailGl sailGl = new SailGl();
    private final FaehrenForm faehrenForm = new FaehrenForm();
    boolean ok;
    String info = "Boote: noch nicht gezeichnet";

    void init(GL4 gl) {
        try {
            sailGl.init(gl);
            ok = true;
        } catch (RuntimeException e) {
            System.err.println("Timmendorf: Boote nicht verfügbar: " + e.getMessage());
            info = "!Boote: " + e.getMessage();
        }
    }

    /** Boote bauen, hochladen und zeichnen (in den gebundenen HDR-Puffer, mit Tiefe). */
    void draw(GL4 gl, Flotte f, float[] vp, double[] eye, DoubleBinaryOperator water, double time,
              double[] sunDir, double[] sunC, double[] sky, double fog, double level) {
        if (!ok) return;
        List<Sail.Boat> near = new ArrayList<>();
        for (Sail.Boat b : f.zumZeichnen()) {
            double d = Math.hypot(b.x - eye[0], b.z - eye[2]);
            if (b.rig.kind() == Sail.Kind.SHIP || d < 300 * b.rig.loa() + 200) near.add(b);
        }
        List<float[]> extra = new ArrayList<>();
        int total = 0;
        for (Flotte.Faehre fe : f.faehren) {
            float[] t = faehrenForm.build(fe, water.applyAsDouble(fe.x, fe.z));
            extra.add(t);
            total += t.length;
        }
        float[] all = new float[total];
        int o = 0;
        for (float[] t : extra) { System.arraycopy(t, 0, all, o, t.length); o += t.length; }
        sailGl.time = time;
        sailGl.update(gl, near, b -> water.applyAsDouble(b.x, b.z), all);
        SailGl.View v = new SailGl.View();
        v.viewProj = vp;
        v.eye = eye.clone();
        v.tonemap = false;
        v.exposure = 1;
        SailGl.Light l = new SailGl.Light();
        l.sunDir = sunDir;
        l.sunColor = sunC;
        l.skyColor = sky;
        l.groundColor = new double[]{sky[0] * 0.35, sky[1] * 0.4, sky[2] * 0.45};
        l.fogColor = sky;
        l.fogDistance = fog;
        l.water = level;
        sailGl.draw(gl, v, l);
        info = String.format(Locale.GERMAN, "Boote: %d von %d gezeichnet, %d Fähren, %,d Dreiecke", near.size(), f.zumZeichnen().size(), f.faehren.size(), sailGl.triangles());
    }

    void dispose(GL4 gl) { if (ok) sailGl.dispose(gl); }
}
