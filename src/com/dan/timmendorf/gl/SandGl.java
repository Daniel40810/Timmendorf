package com.dan.timmendorf.gl;

import com.dan.dune.gl.DuneGl;
import com.dan.sky.Sky;
import com.dan.timmendorf.scene.Sand;
import com.jogamp.opengl.GL4;

/**
 * Die Körner des Flugsands auf der Grafikkarte (seit Phase 20, aus Sylt Phase 9a): {@link DuneGl#drawGrains} mit dem Licht der Szene
 * (vorbelichtet wie Gelände und Brandung). Den Boden zeichnet das Gelände; {@code dune} gibt nur die Körner.
 */
final class SandGl {
    final DuneGl duneGl = new DuneGl();
    boolean ok;
    String info = "";

    void init(GL4 gl) {
        try {
            duneGl.init(gl);
            ok = true;
            info = duneGl.info();
        } catch (RuntimeException e) {
            info = "Flugsand nicht verfügbar: " + e.getMessage();
            System.err.println("Timmendorf: " + info);
        }
    }

    /** Körner zeichnen (nach See und Brandung, mit Tiefentest, durchscheinend). */
    void draw(GL4 gl, float[] vp, double[] eye, Sand sand, Sky.State st, double pre, double[] sun, Wetter wetter, boolean clouds,
              double[] sunFallback, double fogFallback) {
        if (!ok || sand.feld() == null) return;
        DuneGl.Light l = new DuneGl.Light();
        l.sunDir = sun.clone();
        if (st != null) {
            l.sunColor = Wetter.scale(st.sunColor, pre);
            double[] amb = Wetter.scale(wetter.ambient(st, clouds), pre / Math.PI);
            l.skyHorizon = amb;
            l.skyTop = new double[]{amb[0] * 0.8, amb[1] * 0.9, amb[2] * 1.15};
            l.fogColor = Wetter.scale(st.skyIrradiance, pre / Math.PI);
            l.fogDistance = Wetter.fogDistance(st);
        } else {
            l.sunColor = sunFallback;
            l.fogDistance = fogFallback;
        }
        l.display = 0;
        DuneGl.View v = new DuneGl.View();
        v.viewProj = vp;
        v.eye = eye.clone();
        duneGl.drawGrains(gl, v, l, sand.koerner());
    }

    void dispose(GL4 gl) { if (ok) duneGl.dispose(gl); }
}
