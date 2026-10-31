package com.dan.timmendorf.gl;

import com.jogamp.common.nio.Buffers;
import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL4;

/** Kleine Helfer für Texturen, Bildspeicher und Netze auf der Grafikkarte (aus Haithabu übernommen). */
final class Gpu {
    private Gpu() { }

    /** Ein Netz mit verschachtelten Float-Attributen (Größen je Attribut) und 32-Bit-Indizes. */
    static final class Mesh {
        int vao, vbo, ibo, count;

        void upload(GL4 gl, float[] verts, int[] idx, int[] attrSizes) {
            dispose(gl);
            int[] v = new int[2];
            gl.glGenVertexArrays(1, v, 0);
            vao = v[0];
            gl.glGenBuffers(2, v, 0);
            vbo = v[0];
            ibo = v[1];
            gl.glBindVertexArray(vao);
            gl.glBindBuffer(GL.GL_ARRAY_BUFFER, vbo);
            gl.glBufferData(GL.GL_ARRAY_BUFFER, (long) verts.length * 4, Buffers.newDirectFloatBuffer(verts), GL.GL_STATIC_DRAW);
            gl.glBindBuffer(GL.GL_ELEMENT_ARRAY_BUFFER, ibo);
            gl.glBufferData(GL.GL_ELEMENT_ARRAY_BUFFER, (long) idx.length * 4, Buffers.newDirectIntBuffer(idx), GL.GL_STATIC_DRAW);
            int stride = 0;
            for (int s : attrSizes) stride += s;
            int off = 0;
            for (int a = 0; a < attrSizes.length; a++) {
                gl.glEnableVertexAttribArray(a);
                gl.glVertexAttribPointer(a, attrSizes[a], GL.GL_FLOAT, false, stride * 4, off * 4L);
                off += attrSizes[a];
            }
            gl.glBindVertexArray(0);
            count = idx.length;
        }

        void draw(GL4 gl) {
            if (count == 0) return;
            gl.glBindVertexArray(vao);
            gl.glDrawElements(GL.GL_TRIANGLES, count, GL.GL_UNSIGNED_INT, 0);
        }

        /** Nur ein Teil der Indizes (erster Index, Anzahl). */
        void drawRange(GL4 gl, int first, int n) {
            if (n <= 0) return;
            gl.glBindVertexArray(vao);
            gl.glDrawElements(GL.GL_TRIANGLES, n, GL.GL_UNSIGNED_INT, first * 4L);
        }

        void dispose(GL4 gl) {
            if (vao != 0) {
                gl.glDeleteVertexArrays(1, new int[]{vao}, 0);
                gl.glDeleteBuffers(2, new int[]{vbo, ibo}, 0);
            }
            vao = vbo = ibo = count = 0;
        }
    }

    static int texture(GL4 gl, int w, int h, int internal, boolean mips) {
        int[] t = new int[1];
        gl.glGenTextures(1, t, 0);
        gl.glBindTexture(GL.GL_TEXTURE_2D, t[0]);
        int levels = mips ? 1 + (int) Math.floor(Math.log(Math.max(w, h)) / Math.log(2)) : 1;
        gl.glTexStorage2D(GL.GL_TEXTURE_2D, levels, internal, w, h);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, mips ? GL.GL_LINEAR_MIPMAP_LINEAR : GL.GL_LINEAR);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE);
        return t[0];
    }

    /** Phase 8: RGBA8-Textur aus Bytes (Zeilen hintereinander), linear gefiltert, Rand fest. */
    static int texture2D(GL4 gl, int w, int h, byte[] rgba) {
        int t = texture(gl, w, h, GL.GL_RGBA8, false);
        gl.glBindTexture(GL.GL_TEXTURE_2D, t);
        gl.glPixelStorei(GL.GL_UNPACK_ALIGNMENT, 1);
        gl.glTexSubImage2D(GL.GL_TEXTURE_2D, 0, 0, 0, w, h, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, java.nio.ByteBuffer.wrap(rgba));
        gl.glPixelStorei(GL.GL_UNPACK_ALIGNMENT, 4);
        gl.glBindTexture(GL.GL_TEXTURE_2D, 0);
        return t;
    }

    static int fbo(GL4 gl, int color, int depth) {
        int[] f = new int[1];
        gl.glGenFramebuffers(1, f, 0);
        gl.glBindFramebuffer(GL.GL_FRAMEBUFFER, f[0]);
        gl.glFramebufferTexture2D(GL.GL_FRAMEBUFFER, GL.GL_COLOR_ATTACHMENT0, GL.GL_TEXTURE_2D, color, 0);
        if (depth != 0) gl.glFramebufferTexture2D(GL.GL_FRAMEBUFFER, GL.GL_DEPTH_ATTACHMENT, GL.GL_TEXTURE_2D, depth, 0);
        int st = gl.glCheckFramebufferStatus(GL.GL_FRAMEBUFFER);
        gl.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0);
        if (st != GL.GL_FRAMEBUFFER_COMPLETE) throw new com.jogamp.opengl.GLException("Bildspeicher unvollständig: 0x" + Integer.toHexString(st));
        return f[0];
    }

    /** Tiefentextur für Schattenkarten: 32F, Vergleich eingeschaltet (sampler2DShadow), Rand = hell. */
    static int depthTexture(GL4 gl, int size) {
        int t = texture(gl, size, size, GL4.GL_DEPTH_COMPONENT32F, false);
        gl.glBindTexture(GL.GL_TEXTURE_2D, t);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL4.GL_TEXTURE_COMPARE_MODE, GL4.GL_COMPARE_REF_TO_TEXTURE);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL4.GL_TEXTURE_COMPARE_FUNC, GL.GL_LEQUAL);
        gl.glBindTexture(GL.GL_TEXTURE_2D, 0);
        return t;
    }

    /** Bildspeicher nur mit Tiefe (ohne Farbe). */
    static int depthFbo(GL4 gl, int depth) {
        int[] f = new int[1];
        gl.glGenFramebuffers(1, f, 0);
        gl.glBindFramebuffer(GL.GL_FRAMEBUFFER, f[0]);
        gl.glFramebufferTexture2D(GL.GL_FRAMEBUFFER, GL.GL_DEPTH_ATTACHMENT, GL.GL_TEXTURE_2D, depth, 0);
        gl.glDrawBuffer(GL.GL_NONE);
        gl.glReadBuffer(GL.GL_NONE);
        int st = gl.glCheckFramebufferStatus(GL.GL_FRAMEBUFFER);
        gl.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0);
        if (st != GL.GL_FRAMEBUFFER_COMPLETE) throw new com.jogamp.opengl.GLException("Schatten-Bildspeicher unvollständig: 0x" + Integer.toHexString(st));
        return f[0];
    }

    static void deleteTextures(GL4 gl, int... t) {
        for (int x : t) if (x != 0) gl.glDeleteTextures(1, new int[]{x}, 0);
    }

    static void deleteFbos(GL4 gl, int... f) {
        for (int x : f) if (x != 0) gl.glDeleteFramebuffers(1, new int[]{x}, 0);
    }
}
