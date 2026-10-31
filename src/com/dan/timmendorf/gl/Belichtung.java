package com.dan.timmendorf.gl;

import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL4;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Phase 3 (aus Geiranger, dort Phase 8): Belichtungsmessung im Bild. Die Augenanpassung allein kennt nur den Himmel
 * und schätzt das Land; der Blick über hellen Sand oder ins dunkle Watt braucht das Bild selbst (aus Brocken). Alle {@link #INTERVALL} s rechnet {@code lum.frag} aus
 * dem HDR-Bild eine Karte 64 × 64 mit dem Logarithmus der Leuchtdichte (die Mitte doppelt gewichtet), die Mip-Stufen
 * mitteln sie auf einen Texel, und ein Pufferobjekt holt ihn ab, ohne auf die Grafikkarte zu warten: gelesen wird
 * beim nächsten Mal, wenn der Zaun durch ist. Ergebnis: das geometrische Mittel der Leuchtdichte im Bild, geteilt durch
 * die Vorbelichtung, mit der das Bild gerechnet wurde (also in den Einheiten von {@code com.dan.sky}).
 */
final class Belichtung {
    static final double INTERVALL = 0.2;
    private static final int N = 64, LEVELS = 7;
    private int tex, fbo, pbo;
    private long fence;
    private double pendingPre = 1;
    private long last;
    private volatile double lum = -1;
    private boolean ok;

    void init(GL4 gl) {
        int[] t = new int[1];
        gl.glGenTextures(1, t, 0);
        tex = t[0];
        gl.glBindTexture(GL.GL_TEXTURE_2D, tex);
        gl.glTexStorage2D(GL.GL_TEXTURE_2D, LEVELS, GL.GL_RGBA32F, N, N);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR_MIPMAP_LINEAR);
        gl.glBindTexture(GL.GL_TEXTURE_2D, 0);
        gl.glGenFramebuffers(1, t, 0);
        fbo = t[0];
        gl.glBindFramebuffer(GL.GL_FRAMEBUFFER, fbo);
        gl.glFramebufferTexture2D(GL.GL_FRAMEBUFFER, GL.GL_COLOR_ATTACHMENT0, GL.GL_TEXTURE_2D, tex, 0);
        gl.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0);
        gl.glGenBuffers(1, t, 0);
        pbo = t[0];
        gl.glBindBuffer(GL4.GL_PIXEL_PACK_BUFFER, pbo);
        gl.glBufferData(GL4.GL_PIXEL_PACK_BUFFER, 16, null, GL4.GL_STREAM_READ);
        gl.glBindBuffer(GL4.GL_PIXEL_PACK_BUFFER, 0);
        ok = true;
    }

    /** Nach Sprüngen der Kamera oder der Uhr: die alte Messung gilt nicht mehr (bis zur nächsten die Schätzung). */
    void reset() {
        lum = -1;
        hell = -1;
        last = 0;
        discard = fence != 0;
    }

    private boolean discard;

    /** Mittlere Leuchtdichte des Bilds (Einheiten von com.dan.sky), oder −1, solange noch nichts gemessen ist. */
    double lum() { return lum; }

    /**
     * Seit Phase 15: das lineare Mittel der Leuchtdichte (je Probe höchstens 8 nach der Vorbelichtung, damit Lampenköpfe
     * und Sterne nicht zählen), −1 ohne Messung. Liegt ein großer Teil des Bilds hell (Pflaster unter Laternen bei Nacht),
     * ist es viel größer als das geometrische Mittel.
     */
    double hell() { return hell; }

    private volatile double hell = -1;

    /**
     * Einmal je Bild nach den Durchgängen der Szene: liest eine fertige Messung ab und startet höchstens alle
     * {@link #INTERVALL} s eine neue (pre: Vorbelichtung dieses Bilds).
     */
    void step(GL4 gl, Shader s, int hdrTex, int emptyVao, double pre, boolean force) {
        if (!ok) return;
        if (fence != 0) {
            int r = gl.glClientWaitSync(fence, 0, 0);
            if (r == GL4.GL_ALREADY_SIGNALED || r == GL4.GL_CONDITION_SATISFIED) {
                gl.glDeleteSync(fence);
                fence = 0;
                gl.glBindBuffer(GL4.GL_PIXEL_PACK_BUFFER, pbo);
                ByteBuffer b = gl.glMapBufferRange(GL4.GL_PIXEL_PACK_BUFFER, 0, 16, GL4.GL_MAP_READ_BIT);
                if (b != null) {
                    b.order(ByteOrder.nativeOrder());
                    float sum = b.getFloat(0), w = b.getFloat(4), lin = b.getFloat(8);
                    gl.glUnmapBuffer(GL4.GL_PIXEL_PACK_BUFFER);
                    if (discard) discard = false;
                    else if (w > 0 && Float.isFinite(sum)) {
                        lum = Math.exp(sum / w) / Math.max(1e-9, pendingPre);
                        hell = Float.isFinite(lin) ? Math.max(0, lin / w) / Math.max(1e-9, pendingPre) : -1;
                    }
                }
                gl.glBindBuffer(GL4.GL_PIXEL_PACK_BUFFER, 0);
            }
        }
        long now = System.nanoTime();
        if (fence != 0 || (now - last < INTERVALL * 1e9 && !force && last != 0)) return;
        last = now;
        int[] vp = new int[4];
        gl.glGetIntegerv(GL.GL_VIEWPORT, vp, 0);
        int[] prev = new int[1];
        gl.glGetIntegerv(GL.GL_FRAMEBUFFER_BINDING, prev, 0);
        gl.glBindFramebuffer(GL.GL_FRAMEBUFFER, fbo);
        gl.glViewport(0, 0, N, N);
        gl.glDisable(GL.GL_DEPTH_TEST);
        gl.glDisable(GL.GL_BLEND);
        s.use(gl);
        gl.glActiveTexture(GL.GL_TEXTURE0);
        gl.glBindTexture(GL.GL_TEXTURE_2D, hdrTex);
        s.set(gl, "uHdr", 0);
        gl.glBindVertexArray(emptyVao);
        gl.glDrawArrays(GL.GL_TRIANGLES, 0, 3);
        gl.glBindTexture(GL.GL_TEXTURE_2D, tex);
        gl.glGenerateMipmap(GL.GL_TEXTURE_2D);
        gl.glBindBuffer(GL4.GL_PIXEL_PACK_BUFFER, pbo);
        gl.glGetTexImage(GL.GL_TEXTURE_2D, LEVELS - 1, GL.GL_RGBA, GL.GL_FLOAT, 0);
        gl.glBindBuffer(GL4.GL_PIXEL_PACK_BUFFER, 0);
        gl.glBindTexture(GL.GL_TEXTURE_2D, 0);
        fence = gl.glFenceSync(GL4.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        pendingPre = pre;
        gl.glBindFramebuffer(GL.GL_FRAMEBUFFER, prev[0]);
        gl.glViewport(vp[0], vp[1], vp[2], vp[3]);
    }

    void dispose(GL4 gl) {
        if (!ok) return;
        if (fence != 0) gl.glDeleteSync(fence);
        gl.glDeleteTextures(1, new int[]{tex}, 0);
        gl.glDeleteFramebuffers(1, new int[]{fbo}, 0);
        gl.glDeleteBuffers(1, new int[]{pbo}, 0);
        ok = false;
    }
}
