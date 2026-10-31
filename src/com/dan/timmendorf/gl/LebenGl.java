package com.dan.timmendorf.gl;

import com.dan.flock.gl.FlockGl;
import com.dan.seal.gl.SealGl;
import com.dan.timmendorf.scene.Leben;
import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL4;

import java.util.Locale;

/**
 * Seit Phase 13 (aus Sylt): Möwen, Schwäne, Kormorane und Enten ({@code FlockGl}) und der seltene Seehund
 * ({@code SealGl}). Licht vorbelichtet wie das Gelände ({@link Unwetter#licht}). Nach dem Gelände, vor der See.
 */
final class LebenGl {
    final FlockGl flockGl = new FlockGl();
    final SealGl sealGl = new SealGl();
    boolean flockOk, sealOk;
    String flockCheck = "";

    void init(GL4 gl) {
        try { flockGl.init(gl); flockOk = true; } catch (RuntimeException e) { System.err.println("Timmendorf: Vögel (flock) nicht verfügbar: " + e.getMessage()); }
        try { sealGl.init(gl); sealOk = true; } catch (RuntimeException e) { System.err.println("Timmendorf: Seehund (seal) nicht verfügbar: " + e.getMessage()); }
    }

    void draw(GL4 gl, Leben l, float[] vp, double[] eye, boolean reversed, double[][] L, double fogDist, double shade, int frame) {
        gl.glEnable(GL.GL_DEPTH_TEST);
        gl.glDepthMask(true);
        gl.glDepthFunc(reversed ? GL.GL_GREATER : GL.GL_LESS);
        if (flockOk && l.anVoegel) {
            FlockGl.View v = new FlockGl.View();
            v.viewProj = vp;
            v.eye = eye.clone();
            v.reversedZ = reversed;
            FlockGl.Light fl = new FlockGl.Light();
            fl.sunDir = L[0]; fl.sunColor = L[1]; fl.moonDir = L[2]; fl.moonColor = L[3]; fl.skyTop = L[4]; fl.skyHorizon = L[5]; fl.fogDistance = fogDist;
            flockGl.draw(gl, l.vogel, v, fl);
            if (flockCheck.isEmpty() && frame > 95 && !l.vogel.gulls().isEmpty()) flockCheck = flockGl.selfCheck(gl, l.vogel);
        }
        if (sealOk && l.seehundDa()) {
            sealGl.update(gl, l.seehund);
            SealGl.View v = new SealGl.View();
            v.viewProj = vp;
            v.eye = eye.clone();
            SealGl.Light sl = new SealGl.Light();
            sl.sunDir = L[0]; sl.sunColor = L[1]; sl.moonDir = L[2]; sl.moonColor = L[3]; sl.skyTop = L[4]; sl.skyHorizon = L[5]; sl.fogDistance = fogDist;
            sl.sunShadow = shade;
            sealGl.draw(gl, v, sl);
        }
        gl.glDepthFunc(reversed ? GL.GL_GREATER : GL.GL_LESS);
        gl.glEnable(GL.GL_DEPTH_TEST);
    }

    String describe() {
        return String.format(Locale.GERMAN, "Tiere: Vögel %s · Seehund %s%s", flockOk ? flockGl.info() : "fehlen", sealOk ? sealGl.info() : "fehlt",
                flockCheck.isEmpty() ? "" : " · " + flockCheck);
    }

    void dispose(GL4 gl) {
        if (flockOk) flockGl.dispose(gl);
        if (sealOk) sealGl.dispose(gl);
    }
}
