package com.dan.timmendorf.gl;

import com.jogamp.common.nio.Buffers;
import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL4;

import java.nio.IntBuffer;
import java.util.Locale;

/**
 * Der winzige Compute-Test aus Haithabu. Er läuft einmal beim Start
 * und prüft genau das, was der See (FFT aus com.dan.ocean, Phase 2) und später wave brauchen: einen Compute-Shader,
 * einen Speicherpuffer (SSBO), geteilten Speicher in der Arbeitsgruppe und {@code barrier()}. 64 Gruppen zu je 64
 * Fäden bilden Präfixsummen; das Ergebnis wird zurückgelesen und Wert für Wert mit der Rechnung auf dem
 * Prozessor verglichen. Fällt der Test, zeigt das HUD es an, und der See rechnet seine FFT auf dem Prozessor.
 */
public final class ComputeProbe {
    public static final int GROUPS = 64, LOCAL = 64;

    /** Ergebnis: ok, Zeit auf der Grafikkarte in ms, Grenzen der Karte, Meldung. */
    public record Result(boolean ok, double gpuMs, int maxInvocations, int sharedKiB, String message) {
        public String line() {
            return ok ? String.format(Locale.GERMAN, "Compute 4.3: in Ordnung (%d×%d, %.3f ms, bis %d Fäden, %d KiB geteilt)",
                    GROUPS, LOCAL, gpuMs, maxInvocations, sharedKiB)
                    : "Compute 4.3: " + message;
        }
    }

    private ComputeProbe() { }

    /** Erwarteter Wert an Stelle i der Gruppe g: Summe von g·64 + k + 1 für k = 0..i. */
    public static long expected(int g, int i) {
        long n = i + 1;
        return n * (long) g * LOCAL + n * (n + 1) / 2;
    }

    static Result run(GL4 gl) {
        Shader s = new Shader("Compute-Test", null, "probe.comp");
        try {
            s.build(gl);
        } catch (RuntimeException e) {
            return new Result(false, 0, 0, 0, "Shader lässt sich nicht übersetzen – " + firstLine(e.getMessage()));
        }
        int[] buf = new int[1], q = new int[1];
        gl.glGenBuffers(1, buf, 0);
        gl.glBindBuffer(GL4.GL_SHADER_STORAGE_BUFFER, buf[0]);
        gl.glBufferData(GL4.GL_SHADER_STORAGE_BUFFER, (long) GROUPS * LOCAL * 4, null, GL4.GL_DYNAMIC_READ);
        gl.glBindBufferBase(GL4.GL_SHADER_STORAGE_BUFFER, 0, buf[0]);
        gl.glGenQueries(1, q, 0);
        try {
            s.use(gl);
            gl.glBeginQuery(GL4.GL_TIME_ELAPSED, q[0]);
            gl.glDispatchCompute(GROUPS, 1, 1);
            gl.glEndQuery(GL4.GL_TIME_ELAPSED);
            gl.glMemoryBarrier(GL4.GL_BUFFER_UPDATE_BARRIER_BIT | GL4.GL_SHADER_STORAGE_BARRIER_BIT);
            IntBuffer out = Buffers.newDirectIntBuffer(GROUPS * LOCAL);
            gl.glBindBuffer(GL4.GL_SHADER_STORAGE_BUFFER, buf[0]);
            gl.glGetBufferSubData(GL4.GL_SHADER_STORAGE_BUFFER, 0, (long) GROUPS * LOCAL * 4, out);
            long[] ns = new long[1];
            gl.glGetQueryObjecti64v(q[0], GL4.GL_QUERY_RESULT, ns, 0);
            int bad = 0, firstBad = -1;
            for (int g = 0; g < GROUPS; g++)
                for (int i = 0; i < LOCAL; i++) {
                    long got = out.get(g * LOCAL + i) & 0xffffffffL;
                    if (got != expected(g, i)) { bad++; if (firstBad < 0) firstBad = g * LOCAL + i; }
                }
            int[] lim = new int[2];
            gl.glGetIntegerv(GL4.GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS, lim, 0);
            gl.glGetIntegerv(GL4.GL_MAX_COMPUTE_SHARED_MEMORY_SIZE, lim, 1);
            int err = gl.glGetError();
            if (bad > 0) return new Result(false, ns[0] / 1e6, lim[0], lim[1] / 1024,
                    bad + " von " + (GROUPS * LOCAL) + " Werten falsch (erster an Stelle " + firstBad + ") – der See rechnet die FFT dann auf dem Prozessor");
            if (err != GL.GL_NO_ERROR) return new Result(false, ns[0] / 1e6, lim[0], lim[1] / 1024, "OpenGL-Fehler 0x" + Integer.toHexString(err));
            return new Result(true, ns[0] / 1e6, lim[0], lim[1] / 1024, "in Ordnung");
        } catch (RuntimeException e) {
            return new Result(false, 0, 0, 0, firstLine(String.valueOf(e.getMessage())));
        } finally {
            gl.glBindBuffer(GL4.GL_SHADER_STORAGE_BUFFER, 0);
            gl.glDeleteBuffers(1, buf, 0);
            gl.glDeleteQueries(1, q, 0);
            gl.glUseProgram(0);
            s.dispose(gl);
        }
    }

    private static String firstLine(String s) {
        if (s == null) return "unbekannter Fehler";
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }
}
