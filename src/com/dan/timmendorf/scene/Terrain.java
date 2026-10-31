package com.dan.timmendorf.scene;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Properties;

/**
 * Das Gelände der Szene (Aufbau aus Sylt, Brocken und Geiranger). Drei Raster in UTM 32 N (EPSG:25832), Höhen über NHN,
 * Zeile für Zeile von Nord nach Süd:
 * <ul>
 * <li><b>Kern</b> ({@link #KN}² zu {@link #KCELL} m, 4,6 km um die Mitte des Kerns, deckt die 4,5 × 3,7 km aus
 * F1 A): ab Phase 2 aus dem DGM1 2022/2023 (F3 A). Optional: fehlt es, bleibt {@link #kern} null und das Hauptraster gilt auch im Kern. Auf den letzten
 * {@link #KNAHT} m innerhalb des Kernrasters blendet es weich ins Hauptraster über.</li>
 * <li><b>Hauptraster</b> ({@link #N}² zu {@link #CELL} m, 20,5 km um die Mitte des Fernfelds): ab Phase 2 das Land aus
 * dem DGM1 (auf 10 m gemittelt), die Bucht aus BSH, EMODnet und GEBCO (F4 A). Auf den letzten {@link #NAHT} m blendet es in den Fernring über.</li>
 * <li><b>Fernring</b> ({@link #FN}² zu {@link #FCELL} m, 102 km um dieselbe Mitte): GEBCO, wo vorhanden, sonst gestaltet [G].</li>
 * </ul>
 * Je Zelle des Hauptrasters die Art des Grunds ({@link #ART}); ab Phase 2 aus DOP20 (RGB und Infrarot) und ALKIS (F6 A).
 * Java ({@link #height}) und {@code terrain_height.glsl} rechnen dieselbe Fläche, darum sehen Kamera und Bild dasselbe.
 * Höhen liegen als 16-Bit-Werte vor ({@link #HMIN} bis {@link #HMAX}, 2,3 mm Stufe), dieselben Werte gehen als R16-Textur
 * auf die Grafikkarte. Ohne {@code daten/gelaende} entsteht das Gelände aus der Bühne ({@link Buehne}, Phase 1).
 */
public final class Terrain {
    public static final int N = 2048, FN = 1024, KN = 4608;
    public static final double CELL = 10, FCELL = 100, KCELL = 1;
    /** Mitte von Hauptraster und Fernring in der Szene: Mitte des Fernfelds. */
    public static final double MX, MZ;
    /** Nordwestecke des Hauptrasters; Zelle (i, j) hat ihre Mitte bei X0 + (i + 0,5) · CELL. */
    public static final double X0, Z0;
    /** Nordwestecke des Fernrings. */
    public static final double FX0, FZ0;
    /** Mitte des Kernrasters: Mitte des Kernfensters (F1 A); Nordwestecke. */
    public static final double KMX, KMZ, KX0, KZ0;
    static {
        double[] f = Site.FERNFELD.scene();
        MX = (f[0] + f[2]) / 2;
        MZ = (f[1] + f[3]) / 2;
        X0 = MX - N * CELL / 2;
        Z0 = MZ - N * CELL / 2;
        FX0 = MX - FN * FCELL / 2;
        FZ0 = MZ - FN * FCELL / 2;
        double[] k = Site.KERN.scene();
        KMX = (k[0] + k[2]) / 2;
        KMZ = (k[1] + k[3]) / 2;
        KX0 = KMX - KN * KCELL / 2;
        KZ0 = KMZ - KN * KCELL / 2;
    }
    /**
     * Höhenbereich der 16-Bit-Werte: −50 bis +100 m NHN, Stufe 150/65535 m (2,3 mm). Reicht für die tiefste Stelle der
     * Bucht (GEBCO −22 m), den Grund des Hemmelsdorfer Sees (rund −44 m, zu prüfen) und die Hügel im Hinterland.
     */
    public static final double HMIN = -50, HMAX = 100;
    /** Breite der Überblendbänder (m): Hauptraster in den Fernring, Kern ins Hauptraster. */
    public static final double NAHT = 600, KNAHT = 150;
    /** Wie viel gröber Fernring und Hauptraster sind (für die Mip-Stufe im Shader): log2 der Zellgrößen. */
    public static final double FERN_STUFE = Math.log(FCELL / CELL) / Math.log(2), KERN_STUFE = Math.log(CELL / KCELL) / Math.log(2);

    /** Arten des Grunds je Zelle (Phase 1 aus der Bühne, ab Phase 2 aus DOP20 und ALKIS). */
    public static final byte KEINE = 0, SEE = 1, SCHILF = 2, STRAND = 3, NASS = 4, KLIFF = 5, ACKER = 6, WALD = 7, GEBUESCH = 8, WIESE = 9, SIEDLUNG = 10, WEG = 11;
    public static final String[] ART = {"ohne Angabe", "Wasser", "Schilf und Ufer", "Strand", "nasser Sand", "Steilufer", "Acker", "Wald", "Gebüsch",
            "Wiese, Park", "Siedlung", "Weg, Promenade"};

    /** Höhen als vorzeichenlose 16-Bit-Werte. */
    public final short[] h, fern;
    /** Das Kernraster (DGM1); null ohne DGM1. */
    public final short[] kern;
    public final byte[] art;
    public final String source;
    public final Properties props;
    private static final int LEVELS = 12;
    private final float[][] minPyr = new float[LEVELS][], maxPyr = new float[LEVELS][];
    private final int[] side = new int[LEVELS];
    private final float[][] fMin = new float[LEVELS][], fMax = new float[LEVELS][];
    private final int[] fSide = new int[LEVELS];
    private final float[][] kMin = new float[LEVELS][], kMax = new float[LEVELS][];
    private final int[] kSide = new int[LEVELS];

    public Terrain(short[] h, short[] fern, short[] kern, byte[] art, Properties props) {
        if (h.length != N * N || fern.length != FN * FN || art.length != N * N || (kern != null && kern.length != KN * KN))
            throw new IllegalArgumentException("Gelände: falsche Rastergrößen");
        this.h = h;
        this.fern = fern;
        this.kern = kern;
        this.art = art;
        this.props = props;
        this.source = props.getProperty("quelle", "Bühne (Phase 1, keine Höhendaten)");
        pyramid(h, N, minPyr, maxPyr, side);
        pyramid(fern, FN, fMin, fMax, fSide);
        if (kern != null) pyramid(kern, KN, kMin, kMax, kSide);
    }

    /** Ob echte Höhendaten geladen sind (sonst die Bühne). */
    public boolean measured() { return props.containsKey("quelle"); }

    /** Ob das Kernraster aus dem DGM1 da ist. */
    public boolean hasKern() { return kern != null; }

    // ------------------------------------------------------------------ Abfragen

    public static short encode(double y) {
        double v = (y - HMIN) / (HMAX - HMIN) * 65535.0;
        return (short) (int) Math.max(0, Math.min(65535, Math.round(v)));
    }

    public static double decode(short s) { return HMIN + (s & 0xFFFF) / 65535.0 * (HMAX - HMIN); }

    static double at(short[] a, int n, int i, int j) {
        i = Math.max(0, Math.min(n - 1, i));
        j = Math.max(0, Math.min(n - 1, j));
        return decode(a[j * n + i]);
    }

    public double at(int i, int j) { return at(h, N, i, j); }
    public double fernAt(int i, int j) { return at(fern, FN, i, j); }
    public double kernAt(int i, int j) { return kern == null ? Double.NaN : at(kern, KN, i, j); }

    /** Mitte der Zelle i (x) bzw. j (z) in den drei Rastern. */
    public static double cx(int i) { return X0 + (i + 0.5) * CELL; }
    public static double cz(int j) { return Z0 + (j + 0.5) * CELL; }
    public static double fcx(int i) { return FX0 + (i + 0.5) * FCELL; }
    public static double fcz(int j) { return FZ0 + (j + 0.5) * FCELL; }
    public static double kcx(int i) { return KX0 + (i + 0.5) * KCELL; }
    public static double kcz(int j) { return KZ0 + (j + 0.5) * KCELL; }

    /** Nutzungsart am Ort; außerhalb des Hauptrasters {@link #KEINE}. */
    public byte artAt(double x, double z) {
        int i = (int) Math.floor((x - X0) / CELL), j = (int) Math.floor((z - Z0) / CELL);
        if (i < 0 || j < 0 || i >= N || j >= N) return KEINE;
        return art[j * N + i];
    }

    /** Abstand zum Rand eines Rasters (m, positiv innen, negativ außen). */
    static double inside(double x, double z, double x0, double z0, double size) {
        return Math.min(Math.min(x - x0, x0 + size - x), Math.min(z - z0, z0 + size - z));
    }

    /** Anteil des Fernrings an der Höhe (0 tief innen, 1 außen), wie {@code terrain_height.glsl}. */
    public static double fernShare(double x, double z) { return 1 - smooth(0, NAHT, inside(x, z, X0, Z0, N * CELL)); }

    /** Anteil des Kerns an der Höhe (1 tief innen, 0 außen); ohne Kernraster 0. */
    public double kernShare(double x, double z) { return kern == null ? 0 : smooth(0, KNAHT, inside(x, z, KX0, KZ0, KN * KCELL)); }

    static double smooth(double a, double b, double x) {
        double t = Math.max(0, Math.min(1, (x - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }

    static double bilinear(short[] a, int n, double x0, double z0, double cell, double x, double z) {
        double u = (x - x0) / cell - 0.5, v = (z - z0) / cell - 0.5;
        int i = (int) Math.floor(u), j = (int) Math.floor(v);
        double fx = u - i, fz = v - j;
        return (at(a, n, i, j) * (1 - fx) + at(a, n, i + 1, j) * fx) * (1 - fz) + (at(a, n, i, j + 1) * (1 - fx) + at(a, n, i + 1, j + 1) * fx) * fz;
    }

    /** Höhe des Hauptrasters an (x, z), bilinear zwischen den Zellmitten wie die Textur. */
    public double mainHeight(double x, double z) { return bilinear(h, N, X0, Z0, CELL, x, z); }
    /** Höhe des Fernrings an (x, z), bilinear. */
    public double fernHeight(double x, double z) { return bilinear(fern, FN, FX0, FZ0, FCELL, x, z); }
    /** Höhe des Kernrasters an (x, z), bilinear; NaN ohne DGM1. */
    public double kernHeight(double x, double z) { return kern == null ? Double.NaN : bilinear(kern, KN, KX0, KZ0, KCELL, x, z); }

    /** Hauptraster mit Fernring (ohne Kern). */
    public double baseHeight(double x, double z) {
        double f = fernShare(x, z);
        if (f <= 0) return mainHeight(x, z);
        if (f >= 1) return fernHeight(x, z);
        return mainHeight(x, z) * (1 - f) + fernHeight(x, z) * f;
    }

    /**
     * Eine Änderung der Oberfläche von außen (aus Sylt und Brocken; in Timmendorf ab Phase 14 Abbrüche am Brodtener Ufer und Sand nach der Sturmflut), in m, nur im
     * Rechteck {@link #box}; {@link #spanne} sagt, wie weit sie höchstens nach unten und oben geht.
     */
    public interface Korrektur {
        double delta(double x, double z);
        /** {x0, z0, x1, z1} */
        double[] box();
        /** {kleinste, größte Änderung} */
        double[] spanne();
    }

    /** Die Änderung der Oberfläche (null = keine); {@link #height} und {@link #range} rechnen sie mit. */
    public volatile Korrektur korrektur;

    /** Oberfläche an (x, z): Kern, Hauptraster und Fernring, an den Nähten weich übergeblendet; dazu die Korrektur. */
    public double height(double x, double z) {
        Korrektur kr = korrektur;
        return roh(x, z) + (kr == null ? 0 : kr.delta(x, z));
    }

    /** Oberfläche ohne Korrektur (das gemessene Gelände). */
    public double roh(double x, double z) {
        double k = kernShare(x, z);
        if (k <= 0) return baseHeight(x, z);
        if (k >= 1) return kernHeight(x, z);
        return kernHeight(x, z) * k + baseHeight(x, z) * (1 - k);
    }

    /** Kleinste und größte Höhe im Quadrat (x0, z0, Kante) aus den Min-Max-Pyramiden aller Raster. */
    public void range(double x0, double z0, double size, double[] out) {
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        double[] r = new double[2];
        boolean mainHit = x0 + size > X0 && z0 + size > Z0 && x0 < X0 + N * CELL && z0 < Z0 + N * CELL;
        boolean fullyMain = inside(x0, z0, X0, Z0, N * CELL) > NAHT && inside(x0 + size, z0 + size, X0, Z0, N * CELL) > NAHT;
        if (mainHit) {
            pyrRange(minPyr, maxPyr, side, N, X0, Z0, CELL, x0, z0, size, r);
            lo = r[0];
            hi = r[1];
        }
        if (!fullyMain) {
            pyrRange(fMin, fMax, fSide, FN, FX0, FZ0, FCELL, x0, z0, size, r);
            lo = Math.min(lo, r[0]);
            hi = Math.max(hi, r[1]);
        }
        if (kern != null && x0 + size > KX0 && z0 + size > KZ0 && x0 < KX0 + KN * KCELL && z0 < KZ0 + KN * KCELL) {
            pyrRange(kMin, kMax, kSide, KN, KX0, KZ0, KCELL, x0, z0, size, r);
            lo = Math.min(lo, r[0]);
            hi = Math.max(hi, r[1]);
        }
        Korrektur kr = korrektur;
        if (kr != null) {
            double[] b = kr.box(), sp = kr.spanne();
            if (x0 + size > b[0] && z0 + size > b[1] && x0 < b[2] && z0 < b[3]) { lo += Math.min(0, sp[0]); hi += Math.max(0, sp[1]); }
        }
        out[0] = lo;
        out[1] = hi;
    }

    private static void pyrRange(float[][] mn, float[][] mx, int[] sd, int n, double ox, double oz, double cell, double x0, double z0, double size, double[] out) {
        int i0 = (int) Math.floor((x0 - ox) / cell) - 1, j0 = (int) Math.floor((z0 - oz) / cell) - 1;
        int i1 = (int) Math.ceil((x0 + size - ox) / cell) + 1, j1 = (int) Math.ceil((z0 + size - oz) / cell) + 1;
        i0 = Math.max(0, Math.min(n - 1, i0)); j0 = Math.max(0, Math.min(n - 1, j0));
        i1 = Math.max(0, Math.min(n - 1, i1)); j1 = Math.max(0, Math.min(n - 1, j1));
        int span = Math.max(i1 - i0, j1 - j0) + 1, k = 0;
        while (k < LEVELS - 1 && mn[k + 1] != null && (1 << (k + 1)) * 2 <= span) k++;
        int s = sd[k];
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (int j = j0 >> k; j <= j1 >> k; j++)
            for (int i = i0 >> k; i <= i1 >> k; i++) {
                int ii = Math.min(s - 1, i), jj = Math.min(s - 1, j);
                lo = Math.min(lo, mn[k][jj * s + ii]);
                hi = Math.max(hi, mx[k][jj * s + ii]);
            }
        out[0] = lo;
        out[1] = hi;
    }

    private static void pyramid(short[] src, int n0, float[][] minPyr, float[][] maxPyr, int[] side) {
        float[] base = new float[n0 * n0];
        for (int i = 0; i < base.length; i++) base[i] = (float) decode(src[i]);
        minPyr[0] = base;
        maxPyr[0] = base;
        side[0] = n0;
        int n = n0;
        for (int k = 1; k < LEVELS && n > 1; k++) {
            int m = (n + 1) / 2;
            float[] mn = new float[m * m], mx = new float[m * m], pmn = minPyr[k - 1], pmx = maxPyr[k - 1];
            for (int j = 0; j < m; j++)
                for (int i = 0; i < m; i++) {
                    float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
                    for (int b = 0; b < 2; b++)
                        for (int a = 0; a < 2; a++) {
                            int ii = Math.min(n - 1, 2 * i + a), jj = Math.min(n - 1, 2 * j + b);
                            lo = Math.min(lo, pmn[jj * n + ii]);
                            hi = Math.max(hi, pmx[jj * n + ii]);
                        }
                    mn[j * m + i] = lo;
                    mx[j * m + i] = hi;
                }
            minPyr[k] = mn;
            maxPyr[k] = mx;
            side[k] = m;
            n = m;
        }
    }

    /** Anteil der Zellen je Nutzungsart im Hauptraster. */
    public double[] shares() {
        long[] c = new long[ART.length];
        for (byte b : art) c[Math.max(0, Math.min(ART.length - 1, b))]++;
        double[] r = new double[c.length];
        for (int k = 0; k < c.length; k++) r[k] = c[k] / (double) art.length;
        return r;
    }

    /** „Bühne (Phase 1 …) · Kern ohne DGM1 · Wasser 58 %, Acker 20 % …“ (die fünf häufigsten Arten). */
    public String describe() {
        double[] s = shares();
        Integer[] idx = new Integer[s.length];
        for (int k = 0; k < idx.length; k++) idx[k] = k;
        java.util.Arrays.sort(idx, (a, b) -> Double.compare(s[b], s[a]));
        StringBuilder b = new StringBuilder(source).append(kern == null ? " · Kern ohne DGM1" : measured() ? " · Kern DGM1 1 m" : " · Kern aus der Bühne zu 1 m");
        for (int k = 0; k < Math.min(5, idx.length); k++) if (s[idx[k]] > 0) b.append(k == 0 ? " · " : ", ").append(ART[idx[k]]).append(String.format(Locale.GERMAN, " %.0f %%", s[idx[k]] * 100));
        return b.toString();
    }

    // ------------------------------------------------------------------ Dateien

    public static final String HOEHE = "hoehe.r16", FERN = "fern.r16", KERN = "kern.r16", ARTEN = "art.u8", INFO = "gelaende.properties";

    /** Liest {@code daten/gelaende} (oder die Bühne, wenn es fehlt). */
    public static Terrain load(File dir) {
        File hf = new File(dir, HOEHE), ff = new File(dir, FERN), kf = new File(dir, KERN), af = new File(dir, ARTEN), pf = new File(dir, INFO);
        if (!hf.isFile() || !ff.isFile() || !af.isFile() || !pf.isFile()) return ausBuehne();
        try {
            Properties p = new Properties();
            try (InputStream in = new FileInputStream(pf)) { p.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)); }
            short[] h = readShorts(hf, N), f = readShorts(ff, FN), k = kf.isFile() ? readShorts(kf, KN) : null;
            byte[] a = Files.readAllBytes(af.toPath());
            if (a.length != N * N) throw new IOException(af + ": falsche Größe");
            return new Terrain(h, f, k, a, p);
        } catch (IOException | RuntimeException e) {
            System.err.println("Gelände: " + e.getMessage() + " – es bleibt die Bühne");
            return ausBuehne();
        }
    }

    static short[] readShorts(File f, int n) throws IOException {
        byte[] raw = Files.readAllBytes(f.toPath());
        if (raw.length != n * n * 2) throw new IOException(f + ": " + raw.length + " Bytes statt " + n * n * 2);
        short[] s = new short[n * n];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(s);
        return s;
    }

    /** Ordner {@code daten/gelaende} im Arbeitsverzeichnis ({@code -Dtimmendorf.daten=…} setzt ihn anders). */
    public static File defaultDir() {
        String d = System.getProperty("timmendorf.daten");
        return new File(d != null ? d : "daten", "gelaende");
    }

    public void save(File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Ordner " + dir + " lässt sich nicht anlegen");
        writeShorts(new File(dir, HOEHE), h);
        writeShorts(new File(dir, FERN), fern);
        if (kern != null) writeShorts(new File(dir, KERN), kern);
        Files.write(new File(dir, ARTEN).toPath(), art);
        try (OutputStream out = new java.io.FileOutputStream(new File(dir, INFO))) {
            props.store(new java.io.OutputStreamWriter(out, java.nio.charset.StandardCharsets.UTF_8), "Gelaende der Szene Timmendorf (tools, Phase 2)");
        }
    }

    private static void writeShorts(File f, short[] s) throws IOException {
        ByteBuffer bb = ByteBuffer.allocate(s.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        bb.asShortBuffer().put(s);
        Files.write(f.toPath(), bb.array());
    }

    /**
     * Das Gelände aus der Bühne ({@link Buehne}, Phase 1), wenn {@code daten/gelaende} fehlt; Arten aus dem Profil. Auch
     * das Kernraster zu 1 m entsteht (wie in Sylt): so ist der Weg des DGM1 (Phase 2) schon geprüft, und Strand und
     * Sandriffe vor der Seebrücke sind im Kern keine 10-m-Treppen.
     */
    public static Terrain ausBuehne() {
        short[] h = new short[N * N], f = new short[FN * FN], k = new short[KN * KN];
        byte[] a = new byte[N * N];
        java.util.stream.IntStream.range(0, N).parallel().forEach(j -> {
            for (int i = 0; i < N; i++) {
                double x = cx(i), z = cz(j), y = Buehne.height(x, z);
                h[j * N + i] = encode(y);
                a[j * N + i] = Buehne.art(x, z, y);
            }
        });
        java.util.stream.IntStream.range(0, FN).parallel().forEach(j -> {
            for (int i = 0; i < FN; i++) f[j * FN + i] = encode(Buehne.height(fcx(i), fcz(j)));
        });
        java.util.stream.IntStream.range(0, KN).parallel().forEach(j -> {
            for (int i = 0; i < KN; i++) k[j * KN + i] = encode(Buehne.height(kcx(i), kcz(j)));
        });
        return new Terrain(h, f, k, a, new Properties());
    }
}
