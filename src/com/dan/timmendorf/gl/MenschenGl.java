package com.dan.timmendorf.gl;

import com.dan.figure.Person;
import com.dan.figure.gl.FigureGl;
import com.jogamp.opengl.GL4;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Seit Phase 10a: die Menschen der Szene auf der Grafikkarte über {@code com.dan.figure.gl.FigureGl} in drei Stufen
 * (nah bis 25 m, fern bis 120 m, dahinter die Menge bis 1,2 km; seit Phase 11 enger, Befund Phase 10: 2 ms statt 1 ms).
 * Mehr als {@link #max} im Umkreis: die nächsten.
 * Licht wie Bauten und Bewuchs (Sonne als Bestrahlung, Himmel als Bestrahlung/π, vorbelichtet).
 */
final class MenschenGl {
    final FigureGl figureGl = new FigureGl();
    boolean ok;
    int max = 1500;
    String info = "Menschen: noch nicht gezeichnet";

    void init(GL4 gl) {
        try {
            figureGl.init(gl);
            figureGl.nearRange = 25;
            figureGl.farRange = 120;
            figureGl.maxRange = 1200;
            ok = true;
        } catch (RuntimeException e) {
            System.err.println("Timmendorf: Menschen nicht verfügbar: " + e.getMessage());
            info = "!Menschen: " + e.getMessage();
        }
    }

    void draw(GL4 gl, List<Person> alle, float[] vp, double[] eye, double[] sunDir, double[] sunIrr, double[] sky, double fog, double shadow) {
        if (!ok) return;
        double r2 = figureGl.maxRange * figureGl.maxRange;
        List<Person> nah = new ArrayList<>();
        List<double[]> d = new ArrayList<>();
        for (Person p : alle) {
            double dx = p.x - eye[0], dz = p.z - eye[2], q = dx * dx + dz * dz;
            if (q < r2) { nah.add(p); d.add(new double[]{q, nah.size() - 1}); }
        }
        if (nah.size() > max) {
            d.sort((a, b) -> Double.compare(a[0], b[0]));
            List<Person> k = new ArrayList<>(max);
            for (int i = 0; i < max; i++) k.add(nah.get((int) d.get(i)[1]));
            nah = k;
        }
        FigureGl.View v = new FigureGl.View();
        v.viewProj = vp;
        v.eye = eye.clone();
        FigureGl.Light l = new FigureGl.Light();
        l.sunDir = sunDir;
        l.sunColor = sunIrr;
        l.skyTop = sky;
        l.skyHorizon = sky;
        l.fogDistance = fog;
        l.sunShadow = shadow;
        figureGl.draw(gl, nah, v, l);
        info = String.format(Locale.GERMAN, "Figuren: %,d im Umkreis von %.1f km · %s", nah.size(), figureGl.maxRange / 1000, figureGl.info());
    }

    void dispose(GL4 gl) { if (ok) figureGl.dispose(gl); }
}
