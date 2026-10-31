package com.dan.timmendorf.gl;

import com.dan.timmendorf.scene.Terrain;
import com.jogamp.common.nio.Buffers;
import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL4;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/**
 * Das Gelände als Viererbaum mit Detailstufen (CDLOD nach Strugar 2009, aus Geiranger und Brocken): die Wurzel deckt
 * 131 km um die Mitte des Hauptrasters (Fernring 102 km), jede Teilung halbiert die Kante, die feinste Stufe hat Knoten
 * von 64 m mit 1 m Punktabstand (Timmendorf: DGM1 zu 1 m im Kern, F1 A). Jede Stufe gilt bis {@code lodRange} Knotengrößen; im äußeren Teil ihres Bereichs gleiten die
 * Punkte stufenlos in die Lage der gröberen Stufe (Geomorphing), so dass weder Risse noch Springen entstehen.
 * <p>
 * Wie fein geteilt wird, hängt vom Ort ab (F1 A): im Kern bis {@link #finestKern} (1 m, für das DGM1 und ab Phase 4 die
 * Feinform aus {@code cliff}), außerhalb bis {@link #finest} (8 m; das Hauptraster hat 10 m). Alle Knoten
 * teilen sich ein Gitter aus 65 × 65 Punkten (Viertelknoten: 33 × 33); Lage und Größe kommen je Instanz. Die Höhen holt
 * der Vertex-Shader aus drei R16-Texturen mit Mip-Stufen: Hauptraster (2048²), Fernring (1024²) und, wenn das DGM1 da
 * ist, Kern (4608²). Knoten außerhalb des Sichtkegels fallen weg (Höhenbereich aus den Min-Max-Pyramiden).
 */
public final class TerrainLod {
    public static final int DIM = 64, LEVELS = 12;
    public static final double ROOT = 131_072;
    /** Linke obere Ecke der Wurzel: das Raster liegt in ihrer Mitte. */
    public static final double RX0 = Terrain.MX - ROOT / 2, RZ0 = Terrain.MZ - ROOT / 2;
    static final double LEAF = ROOT / (1 << (LEVELS - 1));

    final double[] range = new double[LEVELS];
    /** Feinste Stufe außerhalb der Kernfenster (0 = 1 m, 1 = 2 m, 2 = 4 m, 3 = 8 m zwischen den Punkten) und in ihnen. */
    public int finest = 3, finestKern = 1;
    /** Sichtweite der feinsten Stufe in ihren Knotengrößen. */
    public double lodRange = 2.6;
    int heightTex, sourceTex, fernTex, kernTex;
    public int heightTexture() { return heightTex; }
    public int artTexture() { return sourceTex; }
    public int fernTexture() { return fernTex; }
    /** Das Kernfenster in der Szene: {xMin, zMin, xMax, zMax}. */
    private final double[][] kern = {com.dan.timmendorf.scene.Site.KERN.scene()};
    private final Patch full = new Patch(DIM), quarter = new Patch(DIM / 2);
    private Terrain terrain;
    public int nodes, vertices;
    private final double[] mm = new double[2];
    private final double[][] planes = new double[4][4];
    private double cx, cy, cz;
    private boolean cull;

    public TerrainLod() { }

    /** Ohne Grafikkarte (Prüfung): nur die Auswahl. */
    public TerrainLod(Terrain t) { terrain = t; }

    /** Das Gelände für die Auswahl setzen (vor dem Hochladen; Prüfung und Werkzeuge wählen so ohne Grafikkarte). */
    public void use(Terrain t) { terrain = t; }

    static FloatBuffer direct(int n) { return ByteBuffer.allocateDirect(n * 4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer(); }

    /** Ein Gitter für viele Knoten. */
    private static final class Patch {
        final int dim;
        int vao, vbo, ibo, inst, count, instances;
        FloatBuffer data = direct(4 * 2048);

        Patch(int dim) { this.dim = dim; }

        void init(GL4 gl) {
            int n = dim + 1;
            float[] v = new float[n * n * 2];
            for (int j = 0, k = 0; j < n; j++)
                for (int i = 0; i < n; i++) { v[k++] = i / (float) dim; v[k++] = j / (float) dim; }
            int[] idx = new int[dim * dim * 6];
            int k = 0;
            for (int j = 0; j < dim; j++)
                for (int i = 0; i < dim; i++) {
                    int a = j * n + i, b = a + 1, c = a + n, d = c + 1;
                    if (((i ^ j) & 1) == 0) { idx[k++] = a; idx[k++] = c; idx[k++] = d; idx[k++] = a; idx[k++] = d; idx[k++] = b; }
                    else { idx[k++] = a; idx[k++] = c; idx[k++] = b; idx[k++] = b; idx[k++] = c; idx[k++] = d; }
                }
            count = idx.length;
            int[] t = new int[3];
            gl.glGenVertexArrays(1, t, 0);
            vao = t[0];
            gl.glGenBuffers(3, t, 0);
            vbo = t[0];
            ibo = t[1];
            inst = t[2];
            gl.glBindVertexArray(vao);
            gl.glBindBuffer(GL.GL_ARRAY_BUFFER, vbo);
            gl.glBufferData(GL.GL_ARRAY_BUFFER, v.length * 4L, Buffers.newDirectFloatBuffer(v), GL.GL_STATIC_DRAW);
            gl.glEnableVertexAttribArray(0);
            gl.glVertexAttribPointer(0, 2, GL.GL_FLOAT, false, 8, 0);
            gl.glBindBuffer(GL.GL_ELEMENT_ARRAY_BUFFER, ibo);
            gl.glBufferData(GL.GL_ELEMENT_ARRAY_BUFFER, idx.length * 4L, Buffers.newDirectIntBuffer(idx), GL.GL_STATIC_DRAW);
            gl.glBindBuffer(GL.GL_ARRAY_BUFFER, inst);
            gl.glBufferData(GL.GL_ARRAY_BUFFER, data.capacity() * 4L, null, GL.GL_DYNAMIC_DRAW);
            gl.glEnableVertexAttribArray(1);
            gl.glVertexAttribPointer(1, 4, GL.GL_FLOAT, false, 16, 0);
            gl.glVertexAttribDivisor(1, 1);
            gl.glBindVertexArray(0);
        }

        void add(double x0, double z0, double size, int lod) {
            if (data.remaining() < 4) {
                FloatBuffer b = direct(data.capacity() * 2);
                data.flip();
                b.put(data);
                data = b;
            }
            data.put((float) x0).put((float) z0).put((float) size).put(lod);
        }

        /** Nahe Knoten zuerst (früher Tiefentest spart die Rechnung für das Gelände dahinter). */
        void sortNearFirst(double cx, double cz) {
            int n = data.position() / 4;
            if (n < 2) return;
            float[] a = new float[n * 4];
            data.flip();
            data.get(a);
            data.clear();
            long[] key = new long[n];
            for (int i = 0; i < n; i++) {
                double h = a[4 * i + 2] * 0.5, dx = a[4 * i] + h - cx, dz = a[4 * i + 1] + h - cz;
                key[i] = ((long) Math.min(Integer.MAX_VALUE, (dx * dx + dz * dz) / 16.0) << 20) | i;
            }
            java.util.Arrays.sort(key);
            for (int k = 0; k < n; k++) {
                int i = (int) (key[k] & 0xFFFFF);
                data.put(a, 4 * i, 4);
            }
        }

        void upload(GL4 gl) {
            instances = data.position() / 4;
            data.flip();
            gl.glBindBuffer(GL.GL_ARRAY_BUFFER, inst);
            gl.glBufferData(GL.GL_ARRAY_BUFFER, data.capacity() * 4L, null, GL.GL_DYNAMIC_DRAW);
            gl.glBufferSubData(GL.GL_ARRAY_BUFFER, 0, data.limit() * 4L, data);
            data.clear();
        }

        void draw(GL4 gl, Shader s) {
            if (instances == 0) return;
            s.set(gl, "uGridDim", (float) dim);
            gl.glBindVertexArray(vao);
            gl.glDrawElementsInstanced(GL.GL_TRIANGLES, count, GL.GL_UNSIGNED_INT, 0, instances);
        }

        void dispose(GL4 gl) {
            if (vao == 0) return;
            gl.glDeleteVertexArrays(1, new int[]{vao}, 0);
            gl.glDeleteBuffers(3, new int[]{vbo, ibo, inst}, 0);
            vao = 0;
        }
    }

    /** Eine R16-Höhentextur mit Mip-Stufen (n × n). */
    private static int r16(GL4 gl, short[] data, int n) {
        int[] t = new int[1];
        gl.glGenTextures(1, t, 0);
        gl.glBindTexture(GL.GL_TEXTURE_2D, t[0]);
        int levels = 1 + (int) Math.floor(Math.log(n) / Math.log(2));
        gl.glTexStorage2D(GL.GL_TEXTURE_2D, levels, GL4.GL_R16, n, n);
        gl.glPixelStorei(GL.GL_UNPACK_ALIGNMENT, 2);
        gl.glTexSubImage2D(GL.GL_TEXTURE_2D, 0, 0, 0, n, n, GL4.GL_RED, GL.GL_UNSIGNED_SHORT, Buffers.newDirectShortBuffer(data));
        gl.glGenerateMipmap(GL.GL_TEXTURE_2D);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR_MIPMAP_LINEAR);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE);
        return t[0];
    }

    /** Höhen- und Arttexturen hochladen (im GL-Faden, einmal oder nach einem neuen Import). */
    void upload(GL4 gl, Terrain t) {
        terrain = t;
        if (full.vao == 0) { full.init(gl); quarter.init(gl); }
        Gpu.deleteTextures(gl, heightTex, sourceTex, fernTex, kernTex);
        heightTex = r16(gl, t.h, Terrain.N);
        fernTex = r16(gl, t.fern, Terrain.FN);
        kernTex = t.kern != null ? r16(gl, t.kern, Terrain.KN) : 0;
        int n = Terrain.N;
        int[] tex = new int[1];
        gl.glGenTextures(1, tex, 0);
        sourceTex = tex[0];
        gl.glBindTexture(GL.GL_TEXTURE_2D, sourceTex);
        gl.glTexStorage2D(GL.GL_TEXTURE_2D, 1, GL4.GL_R8UI, n, n);
        gl.glPixelStorei(GL.GL_UNPACK_ALIGNMENT, 1);
        gl.glTexSubImage2D(GL.GL_TEXTURE_2D, 0, 0, 0, n, n, GL4.GL_RED_INTEGER, GL.GL_UNSIGNED_BYTE, ByteBuffer.wrap(t.art));
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_NEAREST);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_NEAREST);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE);
        gl.glBindTexture(GL.GL_TEXTURE_2D, 0);
        gl.glPixelStorei(GL.GL_UNPACK_ALIGNMENT, 4);
    }

    /** Uniforms und Texturen für terrain_height.glsl (Einheiten 8 Hauptraster, 9 Art des Grunds, 10 Fernring, 11 Kern). */
    void bind(GL4 gl, Shader s) {
        gl.glActiveTexture(GL.GL_TEXTURE8);
        gl.glBindTexture(GL.GL_TEXTURE_2D, heightTex);
        gl.glActiveTexture(GL.GL_TEXTURE9);
        gl.glBindTexture(GL.GL_TEXTURE_2D, sourceTex);
        gl.glActiveTexture(GL.GL_TEXTURE10);
        gl.glBindTexture(GL.GL_TEXTURE_2D, fernTex);
        gl.glActiveTexture(GL.GL_TEXTURE11);
        gl.glBindTexture(GL.GL_TEXTURE_2D, kernTex != 0 ? kernTex : heightTex);
        gl.glActiveTexture(GL.GL_TEXTURE0);
        s.set(gl, "uHeight", 8);
        s.set(gl, "uSource", 9);
        s.set(gl, "uFern", 10);
        s.set(gl, "uKern", 11);
        s.set(gl, "uKernOn", kernTex != 0 ? 1 : 0);
        s.set(gl, "uGrid", (float) Terrain.X0, (float) Terrain.Z0, (float) Terrain.CELL);
        s.set(gl, "uGridN", (float) Terrain.N);
        s.set(gl, "uFernGrid", (float) Terrain.FX0, (float) Terrain.FZ0, (float) Terrain.FCELL);
        s.set(gl, "uFernN", (float) Terrain.FN);
        s.set(gl, "uKernGrid", (float) Terrain.KX0, (float) Terrain.KZ0, (float) Terrain.KCELL);
        s.set(gl, "uKernN", (float) Terrain.KN);
        s.set(gl, "uNaht", (float) Terrain.NAHT);
        s.set(gl, "uKNaht", (float) Terrain.KNAHT);
        s.set(gl, "uHMap", (float) (Terrain.HMAX - Terrain.HMIN), (float) Terrain.HMIN);
        s.set(gl, "uStufen", (float) Terrain.FERN_STUFE, (float) Terrain.KERN_STUFE);
        s.set(gl, "uLodRange", (float) (LEAF * lodRange), 0.72f, (float) lodRange);
    }

    /** Knoten wählen, ordnen, hochladen und zeichnen. */
    void draw(GL4 gl, Shader s, double[] cam, float[] vp) {
        collect(cam, vp);
        full.sortNearFirst(cam[0], cam[2]);
        quarter.sortNearFirst(cam[0], cam[2]);
        full.upload(gl);
        quarter.upload(gl);
        full.draw(gl, s);
        quarter.draw(gl, s);
        gl.glBindVertexArray(0);
    }

    /** Nur die Auswahl, ohne Grafikkarte. */
    public void collect(double[] cam, float[] vp) {
        full.data.clear();
        quarter.data.clear();
        for (int i = 0; i < LEVELS; i++) range[i] = LEAF * lodRange * (1 << i);
        cx = cam[0];
        cy = cam[1];
        cz = cam[2];
        cull = vp != null;
        if (cull) frustum(vp);
        nodes = 0;
        if (!node(RX0, RZ0, ROOT, LEVELS - 1)) { full.add(RX0, RZ0, ROOT, LEVELS - 1); nodes++; }
        vertices = full.data.position() / 4 * (DIM + 1) * (DIM + 1) + quarter.data.position() / 4 * (DIM / 2 + 1) * (DIM / 2 + 1);
    }

    /** Gewählte Knoten als (x0, z0, Kante, Stufe) je vier Werte; quarter: die Viertelknoten. */
    public float[] selection(boolean quarterNodes) {
        FloatBuffer b = (quarterNodes ? quarter : full).data.duplicate();
        b.flip();
        float[] r = new float[b.remaining()];
        b.get(r);
        return r;
    }

    /** Wie Strugar: false = Knoten liegt ganz außerhalb seiner Sichtweite (der Elternknoten zeichnet die Fläche). */
    private boolean node(double x0, double z0, double size, int lod) {
        terrain.range(x0, z0, size, mm);
        // die Erdkrümmung senkt ferne Knoten (terrain.vert); der Höhenbereich nach unten um die Absenkung erweitert
        double dist = Math.hypot(Math.max(0, Math.max(x0 - cx, cx - (x0 + size))), Math.max(0, Math.max(z0 - cz, cz - (z0 + size))));
        double y0 = mm[0] - 1 - dist * dist / (2 * 6_371_000.0 / 0.87), y1 = mm[1] + 1;
        if (!sphereBox(range[lod], x0, y0, z0, size, y1)) return false;
        if (cull && !visible(x0, y0, z0, size, y1)) return true;
        if (lod <= finestAt(x0, z0, size) || !sphereBox(range[lod - 1], x0, y0, z0, size, y1)) {
            full.add(x0, z0, size, lod);
            nodes++;
            return true;
        }
        double h = size / 2;
        for (int c = 0; c < 4; c++) {
            double qx = x0 + (c & 1) * h, qz = z0 + (c >> 1) * h;
            if (!node(qx, qz, h, lod - 1)) {
                quarter.add(qx, qz, h, lod);
                nodes++;
            }
        }
        return true;
    }

    /** Feinste Stufe für den Knoten: im Kernfenster (auch nur angeschnitten) {@link #finestKern}, sonst {@link #finest}. */
    int finestAt(double x0, double z0, double size) {
        for (double[] k : kern)
            if (x0 < k[2] && x0 + size > k[0] && z0 < k[3] && z0 + size > k[1]) return Math.min(finest, finestKern);
        return finest;
    }

    private boolean sphereBox(double r, double x0, double y0, double z0, double size, double y1) {
        double dx = Math.max(0, Math.max(x0 - cx, cx - (x0 + size)));
        double dy = Math.max(0, Math.max(y0 - cy, cy - y1));
        double dz = Math.max(0, Math.max(z0 - cz, cz - (z0 + size)));
        return dx * dx + dy * dy + dz * dz <= r * r;
    }

    /** Vier Seitenebenen aus der Matrix (Gribb/Hartmann). */
    private void frustum(float[] m) {
        for (int p = 0; p < 4; p++) {
            int row = p >> 1;
            double s = (p & 1) == 0 ? 1 : -1;
            double[] q = planes[p];
            for (int c = 0; c < 4; c++) q[c] = m[c * 4 + 3] + s * m[c * 4 + row];
        }
    }

    private boolean visible(double x0, double y0, double z0, double size, double y1) {
        for (double[] q : planes) {
            double px = q[0] >= 0 ? x0 + size : x0, py = q[1] >= 0 ? y1 : y0, pz = q[2] >= 0 ? z0 + size : z0;
            if (q[0] * px + q[1] * py + q[2] * pz + q[3] < 0) return false;
        }
        return true;
    }

    void dispose(GL4 gl) {
        full.dispose(gl);
        quarter.dispose(gl);
        Gpu.deleteTextures(gl, heightTex, sourceTex, fernTex, kernTex);
        heightTex = sourceTex = fernTex = kernTex = 0;
    }
}
