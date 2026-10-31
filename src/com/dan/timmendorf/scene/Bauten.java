package com.dan.timmendorf.scene;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Die Häuser aus LOD2 (seit Phase 4, das Grundbild), gelesen aus {@code daten/bauten/bauten.bin} (Phase 2,
 * {@code tools.BautenImport}, Format „TDBAU1“): je Gebäude Kennung, Dachform und Funktion nach AdV, Geschosse, Mitte,
 * Grund, First und Dreiecke mit Art (Dach, Wand, Boden) in Koordinaten der Szene. Die Baustoffe sind gestaltet [G nach
 * dem Ortsbild der Ostseebäder]: weiß verputzte Bäderarchitektur und Hotels, rote Klinker der Wohnhäuser, rote oder
 * anthrazite Pfannen, flache Dächer grau, wenige Reetdächer auf kleinen, steilen Häusern. Phase 9 baut die Bauwerke am
 * Wasser (Seebrücken, Wolkenlos, Promenade) genauer; nach Fotos werden die Baustoffe dann einzeln geeicht.
 */
public final class Bauten {
    /** Ein Gebäude: Kennung, Dachform, Funktion, Geschosse, Mitte (x, z), Grund und First (m NHN), Dreiecke in {@link #tri}. */
    public record Haus(String id, String dach, String funktion, int geschosse, float x, float z, float grund, float hoehe, int erstes, int zahl) {
        public double hoeheUeberGrund() { return hoehe - grund; }
    }

    /** Dreiecke: je 10 Werte (Art 0 Dach, 1 Wand, 2 Boden; dann x y z dreimal). */
    public final float[] tri;
    public final List<Haus> haeuser = new ArrayList<>();
    public final String quelle;

    private Bauten(float[] tri, List<Haus> h, String q) {
        this.tri = tri;
        haeuser.addAll(h);
        quelle = q;
    }

    /** Ohne Daten: keine Häuser. */
    public static Bauten leer() { return new Bauten(new float[0], List.of(), "ohne daten/bauten"); }

    /** Liest {@code daten/bauten/bauten.bin}. */
    public static Bauten laden(File daten) {
        File f = new File(daten, "bauten/bauten.bin");
        if (!f.isFile()) return leer();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f), 1 << 20))) {
            byte[] mag = new byte[6];
            in.readFully(mag);
            if (!"TDBAU1".equals(new String(mag, StandardCharsets.US_ASCII))) throw new IOException("kein TDBAU1");
            int n = in.readInt();
            long nt = in.readLong();
            float[] t = new float[(int) Math.min(Integer.MAX_VALUE - 16, nt * 10)];
            List<Haus> hs = new ArrayList<>(n);
            int k = 0;
            for (int i = 0; i < n; i++) {
                String id = in.readUTF(), dach = in.readUTF(), fkt = in.readUTF();
                int gs = in.readShort();
                float x = in.readFloat(), z = in.readFloat(), g = in.readFloat(), h = in.readFloat();
                in.readFloat();   // gemessene Höhe
                int m = in.readInt();
                int erstes = k / 10;
                for (int j = 0; j < m; j++) {
                    t[k++] = in.readByte();
                    for (int q = 0; q < 9; q++) t[k++] = in.readFloat();
                }
                hs.add(new Haus(id, dach, fkt, gs, x, z, g, h, erstes, m));
            }
            return new Bauten(java.util.Arrays.copyOf(t, k), hs, f.getPath());
        } catch (IOException e) {
            System.err.println("Timmendorf: Bauten nicht lesbar: " + e.getMessage());
            return leer();
        }
    }

    public int dreiecke() { return tri.length / 10; }

    /** Baustoffe eines Hauses: Wand, Dach, ob die Wände Fenster bekommen. */
    public record Stil(int wand, int dach, boolean fenster) { }

    /** Kennzahl 0..511 aus der Kennung (Spielarten im Shader, Zufall der Baustoffe). */
    public static int kennzahl(String id) {
        int h = id.hashCode();
        h ^= h >>> 16;
        h *= 0x45d9f3b;
        h ^= h >>> 16;
        return h & 511;
    }

    /** Ob die Dachform (AdV) flach ist: Flachdach 1000, sonst über die Neigung. */
    static boolean flach(String dach, double neigung) { return dach.equals("1000") || neigung < 10; }

    /**
     * Die Baustoffe eines Hauses [G nach dem Ortsbild]: hohe Häuser (über 14 m) und Flachdächer weiß verputzt mit grauem
     * Dach; Steildächer über rotem Klinker (die Hälfte), weißem Putz oder gelbem Klinker, Pfannen rot oder anthrazit; kleine
     * steile Häuser zu 4 % mit Reet; Garagen und Schuppen (unter 3,5 m) ohne Fenster.
     */
    public Stil stil(Haus h) {
        double steil = 0, dach = 0;
        for (int t = h.erstes(); t < h.erstes() + h.zahl(); t++) {
            int o = t * 10;
            if (tri[o] != 0) continue;
            double ux = tri[o + 4] - tri[o + 1], uy = tri[o + 5] - tri[o + 2], uz = tri[o + 6] - tri[o + 3];
            double vx = tri[o + 7] - tri[o + 1], vy = tri[o + 8] - tri[o + 2], vz = tri[o + 9] - tri[o + 3];
            double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx, l = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (l < 1e-9) continue;
            dach += l / 2;
            steil += l / 2 * Math.toDegrees(Math.acos(Math.min(1, Math.abs(ny) / l)));
        }
        double neigung = dach > 0 ? steil / dach : 0, hoch = h.hoeheUeberGrund();
        int k = kennzahl(h.id());
        double z1 = (k & 63) / 64.0, z2 = ((k >> 6) & 7) / 8.0 + (k & 7) / 64.0;
        boolean klein = hoch < 3.5;
        if (hoch > 14 || flach(h.dach(), neigung)) {
            int w = hoch > 14 || z1 < 0.7 ? Baunetz.PUTZ : Baunetz.KLINKER;
            return new Stil(w, Baunetz.DACH_GRAU, !klein);
        }
        if (neigung >= 38 && hoch < 9 && z1 < 0.04) return new Stil(Baunetz.PUTZ, Baunetz.REET, true);
        int w = z2 < 0.50 ? Baunetz.BACKSTEIN : z2 < 0.88 ? Baunetz.PUTZ : Baunetz.KLINKER;
        boolean rot = z1 < (w == Baunetz.BACKSTEIN ? 0.45 : 0.55);
        return new Stil(w, rot ? Baunetz.DACH_ROT : Baunetz.DACH_GRAU, !klein);
    }

    /**
     * Das Netz aller Häuser mit ihren Baustoffen (Böden bleiben weg): Dächer mit der Normale nach oben, Wände nach außen
     * (von der Mitte des Hauses weg). {@code zaehler}: Häuser mit Reet, grauem, rotem Dach.
     */
    public Baunetz netz(int[] zaehler) { return netz(zaehler, h -> false); }

    /** Wie {@link #netz(int[])}, ohne die Häuser, die {@code weg} nennt (seit Phase 9: von Hand gebaute Bauwerke). */
    public Baunetz netz(int[] zaehler, java.util.function.Predicate<Haus> weg) {
        Baunetz nz = new Baunetz();
        for (Haus h : haeuser) {
            if (weg.test(h)) continue;
            Stil s = stil(h);
            int k = kennzahl(h.id());
            if (zaehler != null) zaehler[s.dach() == Baunetz.REET ? 0 : s.dach() == Baunetz.DACH_GRAU ? 1 : 2]++;
            int mDach = Baunetz.merkmal(s.dach(), false, k, h.grund()), mWand = Baunetz.merkmal(s.wand(), s.fenster(), k, h.grund());
            for (int t = h.erstes(); t < h.erstes() + h.zahl(); t++) {
                int o = t * 10;
                int art = (int) tri[o];
                if (art == 2) continue;
                double[] a = {tri[o + 1], tri[o + 2], tri[o + 3]}, b = {tri[o + 4], tri[o + 5], tri[o + 6]}, c = {tri[o + 7], tri[o + 8], tri[o + 9]};
                double ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2], vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
                double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nzz = ux * vy - uy * vx;
                if (nx * nx + ny * ny + nzz * nzz < 1e-12) continue;
                boolean flip;
                if (art == 0) flip = ny < 0;
                else {
                    double mx = (a[0] + b[0] + c[0]) / 3 - h.x(), mz = (a[2] + b[2] + c[2]) / 3 - h.z();
                    flip = nx * mx + nzz * mz < 0;
                }
                if (flip) { nx = -nx; ny = -ny; nzz = -nzz; double[] tt = b; b = c; c = tt; }
                nz.dreieck(a, b, c, new double[]{nx, ny, nzz}, art == 0 ? mDach : mWand);
            }
        }
        return nz;
    }

    /** Das höchste Haus (First), oder null. */
    public Haus hoechstes() {
        Haus b = null;
        for (Haus h : haeuser) if (b == null || h.hoehe() > b.hoehe()) b = h;
        return b;
    }

    /** „34 077 Häuser, 1 041 106 Dreiecke (LOD2)“ */
    public String describe() {
        return String.format(Locale.GERMAN, "%,d Häuser, %,d Dreiecke (LOD2)", haeuser.size(), dreiecke());
    }
}
