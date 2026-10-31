package com.dan.timmendorf.gl;

import com.jogamp.common.nio.Buffers;
import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL4;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

/**
 * Eine mit Java2D gemalte Tafel als Textur im Bild (aus Haithabu übernommen): das HUD mit Bildern/s, GPU- und
 * CPU-Zeit. Neu gemalt wird im Hintergrund höchstens wenige Male je Sekunde, hochgeladen im Bildlauf und mit dem
 * Shader „overlay“ über das fertige Bild gelegt. Fotos (F12) haben die Tafel mit drin, wenn sie an ist.
 */
final class HudOverlay {
    private int tex, texW, texH;
    int width, height;
    long lastPaint;

    /** Ob wieder gemalt werden darf (Abstand in ms). */
    boolean due(long ms) { return tex == 0 || System.nanoTime() - lastPaint > ms * 1_000_000L; }

    void upload(GL4 gl, BufferedImage im) {
        lastPaint = System.nanoTime();
        upload(gl, pixels(im), im.getWidth(), im.getHeight());
    }

    /** Bild in vormultiplizierte RGBA-Bytes (darf außerhalb des Bildlaufs geschehen). */
    static ByteBuffer pixels(BufferedImage im) {
        int w = im.getWidth(), h = im.getHeight();
        int[] px = im.getRGB(0, 0, w, h, null, 0, w);
        ByteBuffer b = Buffers.newDirectByteBuffer(w * h * 4);
        for (int p : px) {
            int a = p >>> 24;
            b.put((byte) (((p >> 16) & 255) * a / 255)).put((byte) (((p >> 8) & 255) * a / 255)).put((byte) ((p & 255) * a / 255)).put((byte) a);
        }
        b.flip();
        return b;
    }

    /** Fertig gemaltes Bild aus dem Hintergrund: Bytes, Breite, Höhe. */
    private record Ready(ByteBuffer px, int w, int h) { }

    private volatile Ready ready;
    private volatile boolean busy;
    private static final java.util.concurrent.ExecutorService PAINTER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "HUD");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    /**
     * Im Bildlauf: ist es Zeit (ms seit dem letzten Mal), wird im Hintergrund neu gemalt; ein fertiges Bild wird
     * hochgeladen. So hält das Malen mit Java2D den Bildlauf nicht auf. Standbilder (sync) malen gleich hier.
     */
    void refresh(GL4 gl, long ms, java.util.function.Supplier<BufferedImage> painter, boolean sync) {
        if (sync) {
            upload(gl, painter.get());
            return;
        }
        Ready r = ready;
        if (r != null) {
            ready = null;
            upload(gl, r.px(), r.w(), r.h());
        }
        if (!busy && due(ms)) {
            busy = true;
            lastPaint = System.nanoTime();
            PAINTER.execute(() -> {
                try {
                    BufferedImage im = painter.get();
                    ready = new Ready(pixels(im), im.getWidth(), im.getHeight());
                } catch (RuntimeException e) {
                    System.err.println("HUD: " + e);
                } finally {
                    busy = false;
                }
            });
        }
    }

    private void upload(GL4 gl, ByteBuffer b, int w, int h) {
        if (tex == 0 || w != texW || h != texH) {
            if (tex != 0) gl.glDeleteTextures(1, new int[]{tex}, 0);
            int[] t = new int[1];
            gl.glGenTextures(1, t, 0);
            tex = t[0];
            gl.glBindTexture(GL.GL_TEXTURE_2D, tex);
            gl.glTexStorage2D(GL.GL_TEXTURE_2D, 1, GL.GL_RGBA8, w, h);
            gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR);
            gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR);
            gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE);
            gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE);
            texW = w;
            texH = h;
        } else gl.glBindTexture(GL.GL_TEXTURE_2D, tex);
        gl.glPixelStorei(GL.GL_UNPACK_ALIGNMENT, 1);
        gl.glTexSubImage2D(GL.GL_TEXTURE_2D, 0, 0, 0, w, h, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, b);
        gl.glBindTexture(GL.GL_TEXTURE_2D, 0);
        width = w;
        height = h;
    }

    /** Zeichnet die Tafel mit linker unterer Ecke (x, y) in Bildpunkten des Fensters. */
    void draw(GL4 gl, Shader overlay, int vao, int x, int y, int surfW, int surfH) {
        if (tex == 0) return;
        int w = Math.min(width, surfW - x), h = Math.min(height, surfH - y);
        if (w <= 0 || h <= 0) return;
        gl.glViewport(x, y, width, height);
        gl.glEnable(GL.GL_BLEND);
        gl.glBlendFunc(GL.GL_ONE, GL.GL_ONE_MINUS_SRC_ALPHA);
        overlay.use(gl);
        gl.glActiveTexture(GL.GL_TEXTURE0);
        gl.glBindTexture(GL.GL_TEXTURE_2D, tex);
        overlay.set(gl, "uTex", 0);
        overlay.set(gl, "uFlipY", 1);
        overlay.set(gl, "uGain", 1f);
        gl.glBindVertexArray(vao);
        gl.glDrawArrays(GL.GL_TRIANGLES, 0, 3);
        gl.glDisable(GL.GL_BLEND);
        gl.glViewport(0, 0, surfW, surfH);
    }

    void dispose(GL4 gl) {
        if (tex != 0) gl.glDeleteTextures(1, new int[]{tex}, 0);
        tex = 0;
    }
}
