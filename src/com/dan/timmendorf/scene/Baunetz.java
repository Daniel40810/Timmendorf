package com.dan.timmendorf.scene;

import java.util.Arrays;

/**
 * Ein Dreiecksnetz für die Bauten (seit Phase 10), gepackt für die Grafikkarte: je Ecke fünf int – x, y, z als float,
 * die Normale als drei Bytes (−127..127) und ein Merkmal (Baustoff 6 Bit, Fenster 1 Bit, Kennzahl 9 Bit, Grund in
 * 1/20 m über −100 m in 16 Bit). Der Shader {@code bauten.vert} liest es so; der Grund dient den Mustern (Sockel,
 * Geschosse, Bänder), die Kennzahl den Spielarten je Haus.
 * <p>
 * Dazu Bausteine: Dreieck, Viereck, Quader (auch längs einer Strecke), Prisma und Kegel um eine senkrechte Achse (eckig
 * oder rund), Kugel, Geländer im Kreis. Winkel in Grad von Nord im Uhrzeigersinn wie die Szene (x Ost, z Süd).
 */
public final class Baunetz {
    public static final int STRIDE = 5;

    /** Baustoffe (gleiche Nummern in bauten.frag). */
    public static final int BACKSTEIN = 0, PUTZ = 1, REET = 2, DACH_GRAU = 3, DACH_ROT = 4, KUPFER = 5, GLAS_Q = 6, GLAS_K = 7,
            EISEN = 8, HOLZ = 9, GRANIT = 10, KLINKER = 11, KIES = 12, SEGEL = 13, KORB = 14, FELL = 15, FELL_WEISS = 16,
            LACK = 17, LACK_SCHWARZ = 18, FENSTER = 19, TUER = 20, HOLZ_WEISS = 21, ROT = 22, GELB = 23,
            /** Seit Phase 12: Stücke des Kliffs. */
            LEHM = 24, KAOLIN = 25, FLUGSAND = 26,
            /** Seit Timmendorf Phase 9: Leuchte einer Laterne (nachts warm). */
            LAMPE = 27;
    public static final String[] NAMEN = {"Backstein", "Putz", "Reet", "Dach grau", "Dach rot", "Kupfer", "Glas Quermarke",
            "Glas Kampen", "Eisen", "Holz", "Granit", "Klinker", "Kies", "Segeltuch", "Korb", "Fell", "Fell weiß", "Lack weiß",
            "Lack schwarz", "Fenster", "Tür", "Holz weiß", "Rot", "Gelb", "Geschiebelehm", "Kaolinsand", "Flugsand", "Leuchte"};

    int[] d = new int[STRIDE * 3 * 1024];
    int n;

    public Baunetz() { }

    /** Seit Phase 12: Orte der Leuchten, die beim Bau entstanden (x, y der Leuchte, z), für {@link Lichter}. */
    public final java.util.List<double[]> lichter = new java.util.ArrayList<>();
    /** Seit Phase 14: Pfähle der Brücken und Stege (x, z, Grund, Deck, Halbmesser), für die Brecher ({@code com.dan.spray}). */
    public final java.util.List<double[]> pfaehle = new java.util.ArrayList<>();

    /** Zahl der Ecken. */
    public int ecken() { return n / STRIDE; }
    public int dreiecke() { return ecken() / 3; }
    /** Die gepackten Daten (Länge {@link #laenge()}). */
    public int[] daten() { return d; }
    public int laenge() { return n; }
    public void leeren() { n = 0; }

    public static int merkmal(int mat, boolean fenster, int kennzahl, double grund) {
        int g = (int) Math.round((grund + 100) * 20);
        g = Math.max(0, Math.min(65535, g));
        return (mat & 63) | (fenster ? 64 : 0) | ((kennzahl & 511) << 7) | (g << 16);
    }

    public static int baustoff(int m) { return m & 63; }
    public static boolean fenster(int m) { return (m & 64) != 0; }
    public static int kennzahl(int m) { return (m >>> 7) & 511; }
    public static double grund(int m) { return (m >>> 16) / 20.0 - 100; }

    private void platz(int k) {
        if (n + k > d.length) d = Arrays.copyOf(d, Math.max(n + k, d.length * 2));
    }

    static int packNormal(double nx, double ny, double nz) {
        double l = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (l < 1e-12) { nx = 0; ny = 1; nz = 0; l = 1; }
        int x = (int) Math.round(nx / l * 127), y = (int) Math.round(ny / l * 127), z = (int) Math.round(nz / l * 127);
        return (x & 255) | ((y & 255) << 8) | ((z & 255) << 16);
    }

    /** x, y, z der Ecke i. */
    public float x(int i) { return Float.intBitsToFloat(d[i * STRIDE]); }
    public float y(int i) { return Float.intBitsToFloat(d[i * STRIDE + 1]); }
    public float z(int i) { return Float.intBitsToFloat(d[i * STRIDE + 2]); }
    /** Normale der Ecke i (aus den Bytes). */
    public double[] normal(int i) {
        int p = d[i * STRIDE + 3];
        return new double[]{(byte) p / 127.0, (byte) (p >> 8) / 127.0, (byte) (p >> 16) / 127.0};
    }
    public int merkmal(int i) { return d[i * STRIDE + 4]; }

    public void ecke(double x, double y, double z, int normal, int merkmal) {
        platz(STRIDE);
        d[n++] = Float.floatToRawIntBits((float) x);
        d[n++] = Float.floatToRawIntBits((float) y);
        d[n++] = Float.floatToRawIntBits((float) z);
        d[n++] = normal;
        d[n++] = merkmal;
    }

    /** Ein Dreieck mit flacher Normale aus den Ecken (Umlauf a, b, c gegen den Uhrzeigersinn von außen gesehen). */
    public void dreieck(double[] a, double[] b, double[] c, int merkmal) {
        double ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2], vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
        int nn = packNormal(uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx);
        ecke(a[0], a[1], a[2], nn, merkmal);
        ecke(b[0], b[1], b[2], nn, merkmal);
        ecke(c[0], c[1], c[2], nn, merkmal);
    }

    /** Ein Dreieck mit gegebener Normale. */
    public void dreieck(double[] a, double[] b, double[] c, double[] nrm, int merkmal) {
        int nn = packNormal(nrm[0], nrm[1], nrm[2]);
        ecke(a[0], a[1], a[2], nn, merkmal);
        ecke(b[0], b[1], b[2], nn, merkmal);
        ecke(c[0], c[1], c[2], nn, merkmal);
    }

    /** Ein Viereck a, b, c, d (eben, Umlauf von außen gegen den Uhrzeigersinn). */
    public void viereck(double[] a, double[] b, double[] c, double[] e, int merkmal) {
        dreieck(a, b, c, merkmal);
        dreieck(a, c, e, merkmal);
    }

    static double[] p(double x, double y, double z) { return new double[]{x, y, z}; }

    /**
     * Ein Quader: Mitte unten (x, y0, z), Breite quer (sx, in Richtung rechts von „vorn“), Tiefe (sz, nach vorn), Höhe,
     * gedreht um {@code richtung} (Grad von Nord: wohin „vorn“ zeigt). Ohne Boden.
     */
    public void quader(double x, double y0, double z, double sx, double sz, double h, double richtung, int merkmal) {
        double a = Math.toRadians(richtung);
        double fx = Math.sin(a), fz = -Math.cos(a);          // vorn
        double rx = -fz, rz = fx;                            // rechts (Blick nach vorn, x Ost, z Süd): rechts = vorn um 90° im Uhrzeigersinn
        double[][] c = new double[8][];
        for (int k = 0; k < 8; k++) {
            double sr = ((k & 1) == 0 ? -0.5 : 0.5) * sx, sf = ((k & 2) == 0 ? -0.5 : 0.5) * sz, y = (k & 4) == 0 ? y0 : y0 + h;
            c[k] = p(x + rx * sr + fx * sf, y, z + rz * sr + fz * sf);
        }
        kastenFlaechen(c, merkmal, false);
    }

    /** Ein Quader von Punkt a nach b (Achse), Querschnitt w × h (h in der Ebene mit „oben“), z. B. Holm, Balken, Pfahl. */
    public void balken(double[] a, double[] b, double w, double h, int merkmal) {
        double ax = b[0] - a[0], ay = b[1] - a[1], az = b[2] - a[2], l = Math.sqrt(ax * ax + ay * ay + az * az);
        if (l < 1e-6) return;
        ax /= l; ay /= l; az /= l;
        // zwei Querachsen: s waagerecht (wenn möglich), u = Achse × s
        double sx = -az, sy = 0, sz = ax, sl = Math.hypot(sx, sz);
        if (sl < 1e-3) { sx = 1; sy = 0; sz = 0; sl = 1; }
        sx /= sl; sz /= sl;
        double ux = ay * sz - az * sy, uy = az * sx - ax * sz, uz = ax * sy - ay * sx;
        if (uy < 0) { ux = -ux; uy = -uy; uz = -uz; sx = -sx; sz = -sz; }
        double[][] c = new double[8][];
        for (int k = 0; k < 8; k++) {
            double[] o = (k & 4) == 0 ? a : b;
            double ss = ((k & 1) == 0 ? -0.5 : 0.5) * w, uu = ((k & 2) == 0 ? -0.5 : 0.5) * h;
            c[k] = p(o[0] + sx * ss + ux * uu, o[1] + sy * ss + uy * uu, o[2] + sz * ss + uz * uu);
        }
        // Ecken so ordnen, dass k&4 „oben“ (Achse) ist: kastenFlaechen rechnet die Normalen nach außen selbst
        kastenFlaechen(c, merkmal, true);
    }

    /** Die sechs (oder fünf) Flächen eines Kastens aus acht Ecken (Bit 0 rechts, Bit 1 vorn, Bit 2 oben); Normalen nach außen. */
    private void kastenFlaechen(double[][] c, int merkmal, boolean boden) {
        int[][] f = {{0, 1, 3, 2}, {4, 6, 7, 5}, {0, 4, 5, 1}, {2, 3, 7, 6}, {0, 2, 6, 4}, {1, 5, 7, 3}};
        double mx = 0, my = 0, mz = 0;
        for (double[] q : c) { mx += q[0] / 8; my += q[1] / 8; mz += q[2] / 8; }
        for (int i = 0; i < 6; i++) {
            if (i == 0 && !boden) continue;
            double[] a = c[f[i][0]], b = c[f[i][1]], cc = c[f[i][2]], e = c[f[i][3]];
            double ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2], vx = cc[0] - a[0], vy = cc[1] - a[1], vz = cc[2] - a[2];
            double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
            double fx = (a[0] + cc[0]) / 2 - mx, fy = (a[1] + cc[1]) / 2 - my, fz = (a[2] + cc[2]) / 2 - mz;
            if (nx * fx + ny * fy + nz * fz < 0) { double[] t = b; b = e; e = t; nx = -nx; ny = -ny; nz = -nz; }
            double[] nrm = {nx, ny, nz};
            dreieck(a, b, cc, nrm, merkmal);
            dreieck(a, cc, e, nrm, merkmal);
        }
    }

    /**
     * Ein Prisma um die senkrechte Achse (cx, cz) von y0 bis y1 mit Halbmesser r0 unten und r1 oben (Umkreis), n Seiten,
     * erste Ecke bei {@code dreh} Grad von Nord. rund: Normalen glatt (Rundturm), sonst flach je Seite (Achteck).
     * deckel: oben schließen (bei r1 > 0), boden: unten schließen.
     */
    public void prisma(double cx, double cz, double y0, double y1, double r0, double r1, int seiten, double dreh, boolean rund,
                       boolean deckel, boolean boden, int merkmal) {
        double slope = (r0 - r1) / Math.max(1e-6, y1 - y0);
        for (int i = 0; i < seiten; i++) {
            double a0 = Math.toRadians(dreh + 360.0 * i / seiten), a1 = Math.toRadians(dreh + 360.0 * (i + 1) / seiten);
            double s0 = Math.sin(a0), c0 = -Math.cos(a0), s1 = Math.sin(a1), c1 = -Math.cos(a1);
            double[] p00 = p(cx + s0 * r0, y0, cz + c0 * r0), p10 = p(cx + s1 * r0, y0, cz + c1 * r0);
            double[] p01 = p(cx + s0 * r1, y1, cz + c0 * r1), p11 = p(cx + s1 * r1, y1, cz + c1 * r1);
            if (rund) {
                int n0 = packNormal(s0, slope, c0), n1 = packNormal(s1, slope, c1);
                ecke(p00[0], p00[1], p00[2], n0, merkmal); ecke(p10[0], p10[1], p10[2], n1, merkmal); ecke(p11[0], p11[1], p11[2], n1, merkmal);
                if (r1 > 1e-6) { ecke(p00[0], p00[1], p00[2], n0, merkmal); ecke(p11[0], p11[1], p11[2], n1, merkmal); ecke(p01[0], p01[1], p01[2], n0, merkmal); }
            } else {
                double am = (a0 + a1) / 2;
                double[] nrm = {Math.sin(am), slope * Math.cos(Math.PI / seiten), -Math.cos(am)};
                dreieck(p00, p10, p11, nrm, merkmal);
                if (r1 > 1e-6) dreieck(p00, p11, p01, nrm, merkmal);
            }
            if (deckel && r1 > 1e-6) dreieck(p(cx, y1, cz), p01, p11, new double[]{0, 1, 0}, merkmal);
            if (boden && r0 > 1e-6) dreieck(p(cx, y0, cz), p10, p00, new double[]{0, -1, 0}, merkmal);
        }
    }

    /** Ein Ring (flache Scheibe zwischen ri und ra) in der Höhe y, oben sichtbar; mit Kante der Dicke t nach unten. */
    public void scheibe(double cx, double cz, double y, double ri, double ra, double t, int seiten, double dreh, int merkmal) {
        for (int i = 0; i < seiten; i++) {
            double a0 = Math.toRadians(dreh + 360.0 * i / seiten), a1 = Math.toRadians(dreh + 360.0 * (i + 1) / seiten);
            double s0 = Math.sin(a0), c0 = -Math.cos(a0), s1 = Math.sin(a1), c1 = -Math.cos(a1);
            double[] i0 = p(cx + s0 * ri, y, cz + c0 * ri), i1 = p(cx + s1 * ri, y, cz + c1 * ri);
            double[] o0 = p(cx + s0 * ra, y, cz + c0 * ra), o1 = p(cx + s1 * ra, y, cz + c1 * ra);
            double[] up = {0, 1, 0}, dn = {0, -1, 0};
            dreieck(i0, o0, o1, up, merkmal);
            dreieck(i0, o1, i1, up, merkmal);
            double[] u0 = p(o0[0], y - t, o0[2]), u1 = p(o1[0], y - t, o1[2]), ui0 = p(i0[0], y - t, i0[2]), ui1 = p(i1[0], y - t, i1[2]);
            dreieck(ui0, u1, u0, dn, merkmal);
            dreieck(ui0, ui1, u1, dn, merkmal);
            double am = (a0 + a1) / 2;
            double[] out = {Math.sin(am), 0, -Math.cos(am)};
            dreieck(u0, u1, o1, out, merkmal);
            dreieck(u0, o1, o0, out, merkmal);
        }
    }

    /** Eine Kugel (niedrig aufgelöst) um (x, y, z). */
    public void kugel(double x, double y, double z, double r, int merkmal) {
        int nl = 6, na = 10;
        for (int j = 0; j < nl; j++) {
            double t0 = Math.PI * j / nl - Math.PI / 2, t1 = Math.PI * (j + 1) / nl - Math.PI / 2;
            for (int i = 0; i < na; i++) {
                double a0 = 2 * Math.PI * i / na, a1 = 2 * Math.PI * (i + 1) / na;
                double[][] q = {kp(t0, a0), kp(t0, a1), kp(t1, a1), kp(t1, a0)};
                double[][] w = new double[4][];
                int[] nn = new int[4];
                for (int k = 0; k < 4; k++) {
                    w[k] = p(x + q[k][0] * r, y + q[k][1] * r, z + q[k][2] * r);
                    nn[k] = packNormal(q[k][0], q[k][1], q[k][2]);
                }
                ecke(w[0][0], w[0][1], w[0][2], nn[0], merkmal); ecke(w[2][0], w[2][1], w[2][2], nn[2], merkmal); ecke(w[1][0], w[1][1], w[1][2], nn[1], merkmal);
                ecke(w[0][0], w[0][1], w[0][2], nn[0], merkmal); ecke(w[3][0], w[3][1], w[3][2], nn[3], merkmal); ecke(w[2][0], w[2][1], w[2][2], nn[2], merkmal);
            }
        }
    }

    private static double[] kp(double t, double a) { return new double[]{Math.cos(t) * Math.sin(a), Math.sin(t), -Math.cos(t) * Math.cos(a)}; }

    /**
     * Ein Geländer im Kreis (Vieleck mit n Seiten, Umkreis r) auf der Höhe y: Pfosten an jeder Ecke und auf halber Seite,
     * Handlauf in Höhe h, ein Zwischenholm auf halber Höhe.
     */
    public void gelaender(double cx, double cz, double y, double r, double h, int seiten, double dreh, double staerke, int merkmal) {
        double[][] ecken = new double[seiten][];
        for (int i = 0; i < seiten; i++) {
            double a = Math.toRadians(dreh + 360.0 * i / seiten);
            ecken[i] = new double[]{cx + Math.sin(a) * r, cz - Math.cos(a) * r};
        }
        for (int i = 0; i < seiten; i++) {
            double[] a = ecken[i], b = ecken[(i + 1) % seiten];
            balken(p(a[0], y, a[1]), p(a[0], y + h, a[1]), staerke * 1.3, staerke * 1.3, merkmal);
            double mx = (a[0] + b[0]) / 2, mz = (a[1] + b[1]) / 2;
            balken(p(mx, y, mz), p(mx, y + h, mz), staerke, staerke, merkmal);
            balken(p(a[0], y + h, a[1]), p(b[0], y + h, b[1]), staerke * 1.6, staerke, merkmal);
            balken(p(a[0], y + h * 0.5, a[1]), p(b[0], y + h * 0.5, b[1]), staerke, staerke, merkmal);
        }
    }

    /** Alles aus einem anderen Baunetz anhängen. */
    public void anhaengen(Baunetz o) {
        platz(o.n);
        System.arraycopy(o.d, 0, d, n, o.n);
        n += o.n;
    }

    /** Umgebende Höhen: kleinstes und größtes y (für Prüfungen). */
    public double[] hoehen() {
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (int i = 0; i < ecken(); i++) { lo = Math.min(lo, y(i)); hi = Math.max(hi, y(i)); }
        return new double[]{lo, hi};
    }
}
