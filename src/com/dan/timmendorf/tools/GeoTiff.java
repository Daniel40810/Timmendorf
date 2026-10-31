package com.dan.timmendorf.tools;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Liest GeoTIFF in Ausschnitten, ohne GDAL und ohne die ganze Datei in den Speicher zu holen (aus Sylt Phase 2; die Fassung aus
 * Brocken las immer alles, die BAW-Küstenhöhen haben aber 1,8 GB). Klassisches TIFF (kein BigTIFF), unkomprimiert, in
 * Streifen oder Kacheln, die Proben nebeneinander (PlanarConfiguration 1), Werte als float32 oder 8 Bit mit einem oder
 * mehreren Kanälen, seit Timmendorf Phase 2 auch 16 Bit mit und ohne Vorzeichen (GEBCO), Byte-Folge beider Art. So liegen die Daten vor: BAW {@code topography_max_z_2015-2021_ger.tif}
 * (float32, Big-Endian, Streifen zu 8 Zeilen, Lage aus ModelTransformation), bDOM (float32, Kacheln 256²), DOP20 (RGBI,
 * 4 × 8 Bit, Kacheln 256²). Die Lage kommt aus ModelTiepoint und ModelPixelScale oder aus ModelTransformation (linke obere
 * Ecke des linken oberen Pixels, Pixelgröße). {@link #write} schreibt einen Zuschnitt als float32-GeoTIFF (UTM 32 N).
 */
public final class GeoTiff implements Closeable {
    public final File file;
    public final int width, height, samples, bits, format;
    /** Linke obere Ecke (Ost, Nord) und Pixelgröße (m). */
    public final double east0, north0, sizeX, sizeY;
    public final int epsg;
    /** Wert für „kein Wert“ aus GDAL_NODATA (NaN, wenn keiner angegeben ist). */
    public final float nodata;
    private final ByteOrder order;
    private final boolean tiled;
    private final int tileW, tileH, across;
    private final long[] offsets, counts;
    private final RandomAccessFile raf;
    private final FileChannel ch;

    private GeoTiff(File f, RandomAccessFile raf, ByteOrder bo, Map<Integer, long[]> t, Map<Integer, double[]> d, Map<Integer, String> s) throws IOException {
        this.file = f;
        this.raf = raf;
        this.ch = raf.getChannel();
        this.order = bo;
        width = (int) one(t, 256);
        height = (int) one(t, 257);
        long[] bps = t.getOrDefault(258, new long[]{1});
        bits = (int) bps[0];
        samples = (int) opt(t, 277, 1);
        long[] fm = t.getOrDefault(339, new long[]{1});
        format = (int) fm[0];
        int comp = (int) opt(t, 259, 1), planar = (int) opt(t, 284, 1);
        if (comp != 1) throw new IOException(f.getName() + ": Kompression " + comp + " wird nicht gelesen (nur unkomprimiert)");
        if (planar != 1 && samples > 1) throw new IOException(f.getName() + ": Kanäle getrennt abgelegt (PlanarConfiguration 2) wird nicht gelesen");
        if (!(bits == 32 && format == 3) && bits != 8 && bits != 16) throw new IOException(f.getName() + ": " + bits + " Bit, Format " + format + " wird nicht gelesen");
        tiled = t.containsKey(322);
        tileW = tiled ? (int) one(t, 322) : width;
        tileH = tiled ? (int) one(t, 323) : (int) opt(t, 278, height);
        across = (width + tileW - 1) / tileW;
        offsets = tiled ? t.get(324) : t.get(273);
        counts = tiled ? t.get(325) : t.get(279);
        float nd = Float.NaN;
        if (s.containsKey(42113)) try { nd = Float.parseFloat(s.get(42113).trim()); } catch (NumberFormatException ignored) { }
        nodata = nd;
        double[] tr = d.get(34264), sc = d.get(33550), tp = d.get(33922);
        if (tr != null) {
            sizeX = tr[0];
            sizeY = -tr[5];
            east0 = tr[3];
            north0 = tr[7];
        } else if (sc != null && tp != null) {
            sizeX = sc[0];
            sizeY = sc[1];
            east0 = tp[3] - tp[0] * sc[0];
            north0 = tp[4] + tp[1] * sc[1];
        } else throw new IOException(f.getName() + ": keine Lage (ModelTiepoint/ModelTransformation)");
        int e = 0;
        long[] gk = t.get(34735);
        if (gk != null)
            for (int k = 4; k + 3 < gk.length; k += 4) if (gk[k] == 3072 && gk[k + 1] == 0) e = (int) gk[k + 3];
        epsg = e;
    }

    @Override public String toString() {
        return String.format(Locale.GERMAN, "%s: %d × %d Pixel, %d × %d Bit, %.2f m, Ost %.0f–%.0f, Nord %.0f–%.0f, EPSG %d",
                file.getName(), width, height, samples, bits, sizeX, east0, east0 + width * sizeX, north0 - height * sizeY, north0, epsg);
    }

    @Override public void close() throws IOException { raf.close(); }

    /** Spalte (mit Bruchteil) des Ostwerts e, gemessen an der linken Kante; Pixelmitte bei x + 0,5. */
    public double col(double e) { return (e - east0) / sizeX; }
    /** Zeile (mit Bruchteil) des Nordwerts n, gemessen an der oberen Kante. */
    public double row(double n) { return (north0 - n) / sizeY; }

    public static GeoTiff open(File f) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(f, "r");
        try {
            byte[] head = new byte[8];
            raf.readFully(head);
            ByteOrder bo = head[0] == 'I' ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
            ByteBuffer hb = ByteBuffer.wrap(head).order(bo);
            int magic = hb.getShort(2) & 0xffff;
            if (magic == 43) throw new IOException(f.getName() + ": BigTIFF wird nicht gelesen");
            if (magic != 42) throw new IOException(f.getName() + ": keine TIFF-Datei");
            long ifd = hb.getInt(4) & 0xffffffffL;
            Map<Integer, long[]> tags = new HashMap<>();
            Map<Integer, double[]> dtags = new HashMap<>();
            Map<Integer, String> stags = new HashMap<>();
            raf.seek(ifd);
            byte[] nb = new byte[2];
            raf.readFully(nb);
            int n = ByteBuffer.wrap(nb).order(bo).getShort() & 0xffff;
            byte[] ents = new byte[n * 12];
            raf.readFully(ents);
            ByteBuffer eb = ByteBuffer.wrap(ents).order(bo);
            for (int i = 0; i < n; i++) {
                int p = i * 12;
                int tag = eb.getShort(p) & 0xffff, type = eb.getShort(p + 2) & 0xffff;
                long count = eb.getInt(p + 4) & 0xffffffffL;
                int size = switch (type) { case 1, 2, 6, 7 -> 1; case 3, 8 -> 2; case 4, 9, 11 -> 4; case 5, 10, 12 -> 8; default -> 1; };
                long bytes = size * count;
                ByteBuffer vb;
                if (bytes <= 4) vb = ByteBuffer.wrap(ents, p + 8, 4).slice().order(bo);
                else {
                    byte[] v = new byte[(int) bytes];
                    raf.seek(eb.getInt(p + 8) & 0xffffffffL);
                    raf.readFully(v);
                    vb = ByteBuffer.wrap(v).order(bo);
                }
                if (type == 2) {
                    byte[] s = new byte[(int) count];
                    vb.get(s);
                    stags.put(tag, new String(s, java.nio.charset.StandardCharsets.US_ASCII).replace("\0", "").trim());
                } else if (type == 12 || type == 11) {
                    double[] d = new double[(int) count];
                    for (int k = 0; k < count; k++) d[k] = type == 12 ? vb.getDouble(k * 8) : vb.getFloat(k * 4);
                    dtags.put(tag, d);
                } else {
                    long[] l = new long[(int) count];
                    for (int k = 0; k < count; k++) l[k] = switch (size) {
                        case 1 -> vb.get(k) & 0xff;
                        case 2 -> vb.getShort(k * 2) & 0xffff;
                        case 8 -> vb.getInt(k * 8) & 0xffffffffL;
                        default -> vb.getInt(k * 4) & 0xffffffffL;
                    };
                    tags.put(tag, l);
                }
            }
            return new GeoTiff(f, raf, bo, tags, dtags, stags);
        } catch (IOException | RuntimeException e) {
            raf.close();
            throw e;
        }
    }

    /** Bytes je Pixel. */
    int pixelBytes() { return samples * bits / 8; }

    /** Liest einen Block (Streifen oder Kachel) roh; der letzte Streifen kann kürzer sein. */
    private ByteBuffer block(int k) throws IOException {
        int len = (int) counts[k];
        ByteBuffer bb = ByteBuffer.allocate(len).order(order);
        long pos = offsets[k];
        while (bb.hasRemaining()) {
            int r = ch.read(bb, pos + bb.position());
            if (r < 0) throw new IOException(file.getName() + ": Datei endet in Block " + k);
        }
        bb.flip();
        return bb;
    }

    /** Für jeden Block, der das Fenster schneidet: Kopie der Zeilen ins Ziel (Pixel als Bytes). */
    private void window(int x0, int y0, int w, int h, byte[] dst) throws IOException {
        int pb = pixelBytes();
        int bx0 = Math.max(0, x0) / tileW, bx1 = (Math.min(width, x0 + w) - 1) / tileW;
        int by0 = Math.max(0, y0) / tileH, by1 = (Math.min(height, y0 + h) - 1) / tileH;
        if (x0 + w <= 0 || y0 + h <= 0 || x0 >= width || y0 >= height) return;
        for (int by = by0; by <= by1; by++)
            for (int bx = bx0; bx <= bx1; bx++) {
                int k = tiled ? by * across + bx : by;
                ByteBuffer b = block(k);
                int bw = tiled ? tileW : width;
                int ox = tiled ? bx * tileW : 0, oy = by * tileH;
                int rows = Math.min(tileH, height - oy);
                for (int r = 0; r < rows; r++) {
                    int y = oy + r;
                    if (y < y0 || y >= y0 + h) continue;
                    int cx0 = Math.max(ox, Math.max(0, x0)), cx1 = Math.min(Math.min(ox + (tiled ? tileW : width), width), x0 + w);
                    if (cx1 <= cx0) continue;
                    int src = (r * bw + (cx0 - ox)) * pb;
                    if (src + (cx1 - cx0) * pb > b.limit()) continue;
                    b.position(src);
                    b.get(dst, ((y - y0) * w + (cx0 - x0)) * pb, (cx1 - cx0) * pb);
                }
            }
    }

    /** Float-Werte (Kanal 0) im Fenster; NaN außerhalb des Bildes und für „kein Wert“. */
    public float[] floats(int x0, int y0, int w, int h) throws IOException {
        if (bits != 32 && bits != 16) throw new IOException(file.getName() + ": keine float32- oder 16-Bit-Werte");
        byte[] raw = new byte[w * h * pixelBytes()];
        float[] out = new float[w * h];
        java.util.Arrays.fill(out, Float.NaN);
        window(x0, y0, w, h, raw);
        ByteBuffer bb = ByteBuffer.wrap(raw).order(order);
        int pb = pixelBytes();
        for (int y = 0; y < h; y++) {
            int yy = y0 + y;
            if (yy < 0 || yy >= height) continue;
            for (int x = 0; x < w; x++) {
                int xx = x0 + x;
                if (xx < 0 || xx >= width) continue;
                float v = bits == 32 ? bb.getFloat((y * w + x) * pb)
                        : format == 2 ? bb.getShort((y * w + x) * pb) : (bb.getShort((y * w + x) * pb) & 0xffff);
                out[y * w + x] = v == nodata || v < -1e30f || v > 1e30f ? Float.NaN : v;
            }
        }
        return out;
    }

    /** 8-Bit-Proben im Fenster, je Pixel {@link #samples} Bytes nebeneinander; außerhalb 0. */
    public byte[] bytes(int x0, int y0, int w, int h) throws IOException {
        if (bits != 8) throw new IOException(file.getName() + ": keine 8-Bit-Werte");
        byte[] raw = new byte[w * h * samples];
        window(x0, y0, w, h, raw);
        return raw;
    }

    /** Bilinear an (Ost, Nord) aus einem zuvor gelesenen Fenster (x0, y0, w, h); NaN außerhalb oder neben fehlenden Werten. */
    public double bilinear(float[] win, int x0, int y0, int w, int h, double e, double n) {
        double u = col(e) - 0.5 - x0, v = row(n) - 0.5 - y0;
        int i = (int) Math.floor(u), j = (int) Math.floor(v);
        double fx = u - i, fy = v - j;
        i = Math.max(0, Math.min(w - 2, i));
        j = Math.max(0, Math.min(h - 2, j));
        fx = Math.max(0, Math.min(1, u - i));
        fy = Math.max(0, Math.min(1, v - j));
        double a = win[j * w + i], b = win[j * w + i + 1], c = win[(j + 1) * w + i], d = win[(j + 1) * w + i + 1];
        return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy;
    }

    // ------------------------------------------------------------------ schreiben

    /**
     * Schreibt float32-Werte (Zeilen von Nord nach Süd) als GeoTIFF, Little-Endian, unkomprimiert, Streifen zu 16 Zeilen,
     * mit Pixelgröße, Ecke, GDAL_NODATA „nan“ und dem Bezugssystem (GeoKey 3072).
     */
    public static void write(File f, float[] data, int w, int h, double east0, double north0, double cell, int epsg) throws IOException {
        File dir = f.getAbsoluteFile().getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("Ordner " + dir + " lässt sich nicht anlegen");
        int rps = 16, strips = (h + rps - 1) / rps;
        int nTags = 15;
        long ifd = 8;
        long extra = ifd + 2 + nTags * 12L + 4;
        // Zusatzdaten: StripOffsets, StripByteCounts, PixelScale (3 d), Tiepoint (6 d), GeoKeys (16 s), Nodata ("nan\0")
        long offOffs = extra, offCnts = offOffs + strips * 4L, offScale = offCnts + strips * 4L, offTie = offScale + 24, offGeo = offTie + 48,
                offNd = offGeo + 32, offData = (offNd + 4 + 15) / 16 * 16;
        ByteBuffer head = ByteBuffer.allocate((int) offData).order(ByteOrder.LITTLE_ENDIAN);
        head.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt((int) ifd);
        head.position((int) ifd);
        head.putShort((short) nTags);
        tag(head, 256, 4, 1, w);
        tag(head, 257, 4, 1, h);
        tag(head, 258, 3, 1, 32);
        tag(head, 259, 3, 1, 1);
        tag(head, 262, 3, 1, 1);
        tag(head, 273, 4, strips, strips == 1 ? (int) offData : (int) offOffs);
        tag(head, 277, 3, 1, 1);
        tag(head, 278, 4, 1, rps);
        tag(head, 279, 4, strips, strips == 1 ? w * h * 4 : (int) offCnts);
        tag(head, 284, 3, 1, 1);
        tag(head, 339, 3, 1, 3);
        tag(head, 33550, 12, 3, (int) offScale);
        tag(head, 33922, 12, 6, (int) offTie);
        tag(head, 34735, 3, 16, (int) offGeo);
        tag(head, 42113, 2, 4, (int) offNd);
        head.putInt(0);
        head.position((int) offOffs);
        for (int s = 0; s < strips; s++) head.putInt((int) (offData + (long) s * rps * w * 4));
        for (int s = 0; s < strips; s++) head.putInt(Math.min(rps, h - s * rps) * w * 4);
        head.position((int) offScale);
        head.putDouble(cell).putDouble(cell).putDouble(0);
        head.putDouble(0).putDouble(0).putDouble(0).putDouble(east0).putDouble(north0).putDouble(0);
        short[] geo = {1, 1, 0, 3, 1024, 0, 1, 1, 1025, 0, 1, 1, 3072, 0, 1, (short) epsg};
        for (short g : geo) head.putShort(g);
        head.put("nan\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        try (RandomAccessFile out = new RandomAccessFile(f, "rw")) {
            out.setLength(0);
            out.write(head.array());
            ByteBuffer row = ByteBuffer.allocate(w * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int y = 0; y < h; y++) {
                row.clear();
                for (int x = 0; x < w; x++) row.putFloat(data[y * w + x]);
                out.write(row.array());
            }
        }
    }

    private static void tag(ByteBuffer b, int tag, int type, int count, int value) {
        b.putShort((short) tag).putShort((short) type).putInt(count);
        if (type == 3 && count == 1) b.putShort((short) value).putShort((short) 0);
        else b.putInt(value);
    }

    private static long one(Map<Integer, long[]> t, int tag) throws IOException {
        long[] v = t.get(tag);
        if (v == null || v.length == 0) throw new IOException("TIFF-Feld " + tag + " fehlt");
        return v[0];
    }

    private static long opt(Map<Integer, long[]> t, int tag, long def) {
        long[] v = t.get(tag);
        return v == null || v.length == 0 ? def : v[0];
    }

    /** Kurzer Test: {@code GeoTiff datei.tif} zeigt Kopf und den Wertebereich eines Fensters in der Mitte. */
    public static void main(String[] a) throws IOException {
        try (GeoTiff g = open(new File(a[0]))) {
            System.out.println(g);
            int w = Math.min(512, g.width), h = Math.min(512, g.height), x0 = (g.width - w) / 2, y0 = (g.height - h) / 2;
            if (g.bits == 32) {
                float[] v = g.floats(x0, y0, w, h);
                double mn = 1e9, mx = -1e9;
                int nan = 0;
                for (float f : v) if (Float.isNaN(f)) nan++; else { mn = Math.min(mn, f); mx = Math.max(mx, f); }
                System.out.printf(Locale.GERMAN, "Mitte %d²: %.2f … %.2f, %d ohne Wert%n", w, mn, mx, nan);
            } else {
                byte[] v = g.bytes(x0, y0, w, h);
                long[] sum = new long[g.samples];
                for (int i = 0; i < v.length; i++) sum[i % g.samples] += v[i] & 0xff;
                StringBuilder sb = new StringBuilder("Mitte, Mittel je Kanal:");
                for (long s : sum) sb.append(' ').append(s / (w * h));
                System.out.println(sb);
            }
        }
    }
}
