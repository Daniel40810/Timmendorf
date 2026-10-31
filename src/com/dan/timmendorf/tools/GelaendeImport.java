package com.dan.timmendorf.tools;

import com.dan.timmendorf.scene.Buehne;
import com.dan.timmendorf.scene.Nutzung;
import com.dan.timmendorf.scene.Site;
import com.dan.timmendorf.scene.Terrain;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.function.Consumer;

/**
 * Das Gelände aus DGM1, ALKIS, GEBCO und BSH (Phase 2, Schritt {@code gelaende}, F3 A, F4 A). Braucht
 * {@code daten/alkis} (Schritt {@code alkis}; ohne sie gelten die Umrisse der Bühne). Schreibt {@code daten/gelaende/} im
 * Format von {@link Terrain} und das Prüfbild {@code docs/status/Gelaende_Phase2.png}.
 * <ol>
 * <li><b>Land</b>: die Punkte des DGM1 2022/2023 unverändert (Kern zu 1 m; im Hauptraster das Mittel je 10 m, wenn
 * mindestens die Hälfte Land ist). Wo keine Kachel liegt (Mecklenburg, die Kacheln von 2005), GEBCO und sonst die Bühne.</li>
 * <li><b>Wasser</b>: die Wasserflächen der Befliegung ({@link Dgm1}), dazu tiefe, ebene Punkte unter
 * {@link #WASSER_HOCH}, die ALKIS als Gewässer führt oder die seewärts der OSM-Küste liegen (die Befliegung 2023 hat den
 * Wasserspiegel mit Wellen gemessen, 0 bis 0,3 m).</li>
 * <li><b>Grund unter Wasser</b> nach dem Abstand d zum nächsten Land ({@link Raster#abstand}): die Ostsee mit einem
 * gestalteten Strandprofil (Vorstrand 1:40, zwei Sandriffe 70 und 160 m vor dem Strand, wie die Bühne) [G], das ab
 * {@link #GEBCO_AB} m in GEBCO übergeht; wo das BSH-Raster liegt (Travemündung), gilt es. Der Hemmelsdorfer See und die
 * kleinen Gewässer flach bis {@link #SEE_TIEFE} m [G], Trave und Hafenbecken bis {@link #TRAVE_TIEFE} m in der
 * Fahrrinne [G].</li>
 * <li><b>Fernring</b> (1 024² zu 100 m): im Hauptraster dessen Mittel, sonst GEBCO, sonst die Bühne.</li>
 * <li><b>Arten</b> vorläufig aus ALKIS ({@link Nutzung#art}), Strand und nasser Sand nach Höhe und Abstand zum Wasser,
 * Steilufer nach der Neigung; der Schritt {@code luftbild} verfeinert sie mit dem DOP20.</li>
 * </ol>
 * Dazu {@code quelle.u8} (je Kernzelle 0 = ersetzt aus GEBCO oder Bühne, 1 = DGM1, 2 = Grund unter Wasser gestaltet,
 * 3 = BSH).
 */
public final class GelaendeImport {
    /** Höchster Punkt (m NHN), der noch als gemessene Wasserfläche gilt, wenn ALKIS oder die Küste Wasser sagen. */
    public static final float WASSER_HOCH = 0.35f;
    /**
     * Weiter als {@link #SEE_SICHER} m seewärts der OSM-Küste gilt jeder Punkt unter {@link #WASSER_WELLE} m als Wasserspiegel
     * (Befund Phase 2: die Befliegung 2023 hat dort Wellenkämme bis rund 0,6 m gemessen).
     */
    public static final float WASSER_WELLE = 1.2f;
    /**
     * Gewässer nach ALKIS, deren Spiegel die Befliegung höher als {@link #WASSER_HOCH} gemessen hat (Befund Phase 2/3: Trave
     * und Pötenitzer Wiek liegen im DGM1 2022 bei 0,8 m, nicht ganz eben): in Hafenbecken, Fließ- und stehenden Gewässern nach
     * ALKIS ist jeder Punkt bis zu dieser Höhe Wasser (das Meer nicht: dort läge sonst der Strand im Wasser).
     */
    public static final float WASSER_ALKIS = 2.0f;
    /** Breite (m), über die GEBCO am Rand des Downloads in Strandprofil und Bühne übergeht. */
    public static final double GEBCO_RAND = 2000;
    public static final double SEE_SICHER = 25;
    /** Ab welchem Abstand vom Land GEBCO gilt (Übergang über {@link #GEBCO_BAND} m). */
    public static final double GEBCO_AB = 900, GEBCO_BAND = 1800;
    /** Größte Tiefe des Hemmelsdorfer Sees und kleiner Gewässer, der Trave und der Hafenbecken (m) [G]. */
    public static final double SEE_TIEFE = 4.5, TRAVE_TIEFE = 9.5, HAFEN_TIEFE = 3.5;
    /** Breite (m), über die das BSH-Raster an seinem Rand in Strandprofil und GEBCO übergeht. */
    public static final double BSH_BAND = 1500;
    public static final String QUELLE = "quelle.u8";
    public static final File BILD = new File("docs/status/Gelaende_Phase2.png");

    private GelaendeImport() { }

    public static void main(String[] a) throws Exception {
        Bericht b = importieren(Rohdaten.laden(), Terrain.defaultDir(), System.out::println);
        bild(b, BILD);
    }

    /** Was der Import gerechnet hat (für das Bild, die Prüfung und das Werkbuch). */
    public static final class Bericht {
        public Terrain terrain;
        public byte[] quelle;
        public final List<String> zeilen = new ArrayList<>();
        public double[] naht = new double[8];
        public int kacheln, kachelnKern;
    }

    /** Art des Wassers je Zelle: 0 Land, 1 Ostsee, 2 See und Teich, 3 Trave und Hafen. */
    static byte wasserArt(int nutzung, double e, double n) {
        if (nutzung == Nutzung.STEHENDES_GEWAESSER) return 2;
        if (nutzung == Nutzung.FLIESSGEWAESSER || nutzung == Nutzung.HAFENBECKEN || nutzung == Nutzung.SCHIFFSVERKEHR) return 3;
        if (nutzung == Nutzung.MEER) return 1;
        return Buehne.FELD.abstand(e, n) < 30 ? (byte) 1 : (byte) 2;
    }

    /** Grund (m NHN) unter Wasser der Art art im Abstand d (m) zum nächsten Land, ohne GEBCO und BSH. */
    static double grund(byte art, double d, double e, double n) {
        if (art == 1) {
            double h = -Buehne.seeTiefe(d);
            for (double[] r : Buehne.RIFFE) {
                double q = (-d - r[0]) / r[2];
                h += r[1] * Math.exp(-q * q);
            }
            return h;
        }
        if (art == 3) {
            boolean trave = Buehne.linienAbstand(Buehne.TRAVE, e, n) < 250;
            double max = trave ? TRAVE_TIEFE : HAFEN_TIEFE;
            return -Math.min(max, 0.5 + d * (trave ? 0.12 : 0.15));
        }
        return -Math.min(SEE_TIEFE, 0.3 + d * 0.03);
    }

    public static Bericht importieren(Rohdaten r, File out, Consumer<String> log) throws IOException {
        long t0 = System.nanoTime();
        Bericht b = new Bericht();
        Dgm1 dgm = new Dgm1(r.dgm1());
        log.accept(Raster.f("DGM1: %d Kacheln, davon %d ab %d gültig, %d ältere übergangen", dgm.alle, dgm.kacheln.size(), Dgm1.AB_JAHR, dgm.alt));
        Gebco gebco = Gebco.laden(r.gebco());
        log.accept(gebco.toString());

        int KN = Terrain.KN, N = Terrain.N, FN = Terrain.FN;
        double kE0 = Terrain.KX0 + Site.E0, kN0 = Site.N0 - Terrain.KZ0;      // Nordwestecke des Kerns (UTM)
        double hE0 = Terrain.X0 + Site.E0, hN0 = Site.N0 - Terrain.Z0;        // des Hauptrasters
        int kE = (int) Math.round(kE0), kN = (int) Math.round(kN0), hE = (int) Math.round(hE0), hN = (int) Math.round(hN0);

        // ------------------------------------------------ ALKIS-Nutzung (Schritt alkis)
        File ad = new File(out.getParentFile(), "alkis");
        byte[] nk = lies(new File(ad, AlkisImport.KERN), KN * KN), nh = lies(new File(ad, AlkisImport.HAUPT), N * N);
        boolean mitAlkis = nk != null && nh != null;
        if (!mitAlkis) {
            log.accept("  ALKIS fehlt (daten/alkis): Wasser und Arten nach der Bühne; besser zuerst Schritt alkis");
            nk = new byte[KN * KN];
            nh = new byte[N * N];
        }

        // ------------------------------------------------ BSH (Travemündung)
        float[] bsh = null;
        if (r.bsh() != null) {
            long[] z = new long[2];
            long tb = System.nanoTime();
            bsh = Bsh.raster(r.bsh(), hE0, hN0, Terrain.CELL, N, z);
            log.accept(Raster.f("BSH %s: %,d Zeilen gelesen, %,d Zellen im Hauptraster (%.0f s)", r.bsh().getName(), z[0], z[1], (System.nanoTime() - tb) / 1e9));
        } else log.accept("  BSH-ZIP fehlt: die Bucht nur aus GEBCO und dem Strandprofil");

        // ------------------------------------------------ DGM1 lesen: Kern zu 1 m, Hauptraster als Summen je 10 m
        float[] kern = new float[KN * KN];
        java.util.Arrays.fill(kern, Float.NaN);
        boolean[] kWasser = new boolean[KN * KN], kKachel = new boolean[KN * KN];
        double[] hSum = new double[N * N];
        int[] hCnt = new int[N * N], hWas = new int[N * N], hPkt = new int[N * N];
        int eLo = (int) Math.floor(hE0 / 1000), eHi = (int) Math.floor((hE0 + N * Terrain.CELL - 1) / 1000);
        int nLo = (int) Math.floor((hN0 - N * Terrain.CELL) / 1000), nHi = (int) Math.floor((hN0 - 1) / 1000);
        int gelesen = 0, imKern = 0;
        long punkte = 0, wasser = 0, tief = 0, spiegel = 0;
        for (int ek = eLo; ek <= eHi; ek++)
            for (int nkm = nLo; nkm <= nHi; nkm++) {
                Dgm1.Kachel k = dgm.kachel(ek, nkm);
                if (k == null) continue;
                Dgm1.Feld f = Dgm1.lesen(k);
                gelesen++;
                punkte += f.punkte;
                wasser += f.wasser;
                boolean kTreffer = false;
                for (int y = 0; y < 1000; y++) {
                    int nRow = (nkm + 1) * 1000 - y;
                    int hj = (hN - nRow) / 10, kj = kN - nRow;
                    for (int x = 0; x < 1000; x++) {
                        float v = f.h[y * 1000 + x];
                        int eCol = ek * 1000 + x;
                        boolean was = Float.isNaN(v);
                        // tief und eben: in ALKIS-Gewässern und seewärts der Küste ist das der gemessene Wasserspiegel
                        if (!was && v < WASSER_ALKIS) {
                            int hi0 = (eCol - hE) / 10;
                            int nu = hi0 >= 0 && hj >= 0 && hi0 < N && hj < N ? nh[hj * N + hi0] : 0;
                            double s = nu == 0 ? Buehne.FELD.abstand(eCol + 0.5, nRow - 0.5) : 1;
                            if ((v < WASSER_HOCH && (Nutzung.wasser(nu) || s < 0)) || (s < -SEE_SICHER && v < WASSER_WELLE)) { was = true; tief++; }
                            else if (nu >= Nutzung.HAFENBECKEN && nu <= Nutzung.STEHENDES_GEWAESSER && v < WASSER_ALKIS) { was = true; spiegel++; }
                        }
                        int hi = (eCol - hE) / 10;
                        if (hi >= 0 && hj >= 0 && hi < N && hj < N && eCol >= hE && hN - nRow >= 0) {
                            int q = hj * N + hi;
                            hPkt[q]++;
                            if (was) hWas[q]++;
                            else { hSum[q] += v; hCnt[q]++; }
                        }
                        int ki = eCol - kE;
                        if (ki >= 0 && kj >= 0 && ki < KN && kj < KN) {
                            kTreffer = true;
                            kKachel[kj * KN + ki] = true;
                            if (was) kWasser[kj * KN + ki] = true;
                            else kern[kj * KN + ki] = v;
                        }
                    }
                }
                if (kTreffer) imKern++;
                if (gelesen % 20 == 0) log.accept(Raster.f("  DGM1 %d Kacheln gelesen …", gelesen));
            }
        b.kacheln = gelesen;
        b.kachelnKern = imKern;
        log.accept(Raster.f("DGM1: %d Kacheln im Hauptraster gelesen (%d im Kern), %,d Punkte, davon %,d Wasserfläche der Befliegung, %,d gemessener Wasserspiegel "
                        + "und %,d Spiegel in ALKIS-Gewässern bis %.1f m", gelesen, imKern, punkte, wasser, tief, spiegel, WASSER_ALKIS));
        double[] mitte = Site.toUtm((Terrain.FX0 + Terrain.FN * Terrain.FCELL / 2), (Terrain.FZ0 + Terrain.FN * Terrain.FCELL / 2));
        if (!gebco.deckt(mitte[0], mitte[1], Site.FERNRING))
            log.accept(Raster.f("  Hinweis: der GEBCO-Download deckt den Fernring (%.0f km) nicht; dort bleibt die Bühne. Besser: GEBCO neu laden mit "
                    + "Nord 54,50, Süd 53,50, West 10,00, Ost 11,70 (GeoTIFF)", Site.FERNRING / 1000));

        // ------------------------------------------------ Hauptraster: Land, Wasser, Lücken
        float[] haupt = new float[N * N];
        boolean[] hLand = new boolean[N * N];
        byte[] hArtW = new byte[N * N];
        int gebcoLand = 0, buehneLand = 0;
        for (int j = 0; j < N; j++)
            for (int i = 0; i < N; i++) {
                int q = j * N + i;
                double e = hE0 + (i + 0.5) * Terrain.CELL, n = hN0 - (j + 0.5) * Terrain.CELL;
                if (hCnt[q] * 2 >= hPkt[q] && hCnt[q] > 0) { hLand[q] = true; haupt[q] = (float) (hSum[q] / hCnt[q]); continue; }
                if (hPkt[q] > 0) { hArtW[q] = wasserArt(nh[q], e, n); continue; }
                // keine Kachel: Land nach ALKIS oder der OSM-Küste, Höhe aus GEBCO oder der Bühne
                boolean land = nh[q] != 0 ? !Nutzung.wasser(nh[q]) : Buehne.FELD.abstand(e, n) > 0;
                if (land) {
                    double g = gebco.utm(e, n);
                    double[] xz = Site.fromUtm(e, n);
                    double bu = Math.max(0.5, Buehne.height(xz[0], xz[1]));
                    if (!Double.isNaN(g)) {
                        double w = smooth(0, GEBCO_RAND, gebco.randAbstand(e, n));
                        haupt[q] = (float) (Math.max(0.5, g) * w + bu * (1 - w));
                        gebcoLand++;
                    } else { haupt[q] = (float) bu; buehneLand++; }
                    hLand[q] = true;
                } else hArtW[q] = wasserArt(nh[q], e, n);
            }
        float[] hAbst = Raster.abstand(hLand, N, Terrain.CELL);
        // BSH weich einblenden: Abstand jeder BSH-Zelle zum Rand ihres Rasters
        float[] bshRand = null;
        if (bsh != null) {
            boolean[] ohne = new boolean[N * N];
            for (int q = 0; q < N * N; q++) ohne[q] = Float.isNaN(bsh[q]) || bsh[q] >= 0;
            bshRand = Raster.abstand(ohne, N, Terrain.CELL);
        }
        int bshZellen = 0;
        for (int j = 0; j < N; j++)
            for (int i = 0; i < N; i++) {
                int q = j * N + i;
                if (hLand[q]) continue;
                double e = hE0 + (i + 0.5) * Terrain.CELL, n = hN0 - (j + 0.5) * Terrain.CELL, d = hAbst[q];
                double g = grund(hArtW[q], d, e, n);
                if (hArtW[q] == 1) {
                    double gb = gebco.utm(e, n);
                    if (!Double.isNaN(gb)) {
                        double w = smooth(GEBCO_AB, GEBCO_AB + GEBCO_BAND, d) * smooth(0, GEBCO_RAND, gebco.randAbstand(e, n));
                        g = g * (1 - w) + Math.min(gb, -0.5) * w;
                    }
                }
                if (bsh != null && !Float.isNaN(bsh[q]) && bsh[q] < 0) {
                    double w = smooth(0, BSH_BAND, bshRand[q]) * smooth(0, 60, d);
                    g = g * (1 - w) + bsh[q] * w;
                    bshZellen++;
                }
                haupt[q] = (float) g;
            }
        hSum = null;

        // ------------------------------------------------ Kern
        boolean[] kLand = new boolean[KN * KN];
        byte[] quelle = new byte[KN * KN];
        for (int k = 0; k < KN * KN; k++) kLand[k] = !Float.isNaN(kern[k]) || (!kKachel[k] && !Nutzung.wasser(nk[k]) && nk[k] != 0);
        float[] kAbst = Raster.abstand(kLand, KN, Terrain.KCELL);
        float[] kFin = new float[KN * KN];
        final byte[] fnk = nk, fnh = nh;
        final float[] fbsh = bsh;
        java.util.stream.IntStream.range(0, KN).parallel().forEach(j -> {
            for (int i = 0; i < KN; i++) {
                int k = j * KN + i;
                double e = kE0 + i + 0.5, n = kN0 - j - 0.5;
                double hb = bilinear(haupt, N, (e - hE0) / Terrain.CELL - 0.5, (hN0 - n) / Terrain.CELL - 0.5);
                if (!Float.isNaN(kern[k])) { kFin[k] = kern[k]; quelle[k] = 1; continue; }
                if (kLand[k]) { kFin[k] = (float) hb; quelle[k] = 0; continue; }
                int hi = (int) Math.floor((e - hE0) / Terrain.CELL), hj = (int) Math.floor((hN0 - n) / Terrain.CELL);
                byte art = wasserArt(fnk[k] != 0 ? fnk[k] : fnh[Math.max(0, Math.min(N * N - 1, hj * N + hi))], e, n);
                double d = kAbst[k], g = grund(art, d, e, n);
                double w = smooth(120, 400, d);
                kFin[k] = (float) (g * (1 - w) + Math.min(hb, -0.3) * w);
                quelle[k] = (byte) (fbsh != null && !Float.isNaN(fbsh[Math.max(0, Math.min(N * N - 1, hj * N + hi))]) && w > 0.5 ? 3 : 2);
            }
        });
        // das Hauptraster im Kern aus dem Kern (Mittel je 10 m), damit beide dieselbe Fläche zeigen
        double kx0 = Terrain.KX0, kz0 = Terrain.KZ0;
        for (int j = 0; j < N; j++)
            for (int i = 0; i < N; i++) {
                int ki0 = (int) Math.round(Terrain.X0 + i * Terrain.CELL - kx0), kj0 = (int) Math.round(Terrain.Z0 + j * Terrain.CELL - kz0);
                if (ki0 < 0 || kj0 < 0 || ki0 + 10 > KN || kj0 + 10 > KN) continue;
                double s = 0;
                for (int b2 = 0; b2 < 10; b2++) for (int a2 = 0; a2 < 10; a2++) s += kFin[(kj0 + b2) * KN + ki0 + a2];
                haupt[j * N + i] = (float) (s / 100);
            }
        long kDgm = 0, kGrund = 0, kErs = 0;
        for (byte x : quelle) if (x == 1) kDgm++; else if (x == 2 || x == 3) kGrund++; else kErs++;
        log.accept(Raster.f("Kern: %.0f %% DGM1, %.0f %% Grund unter Wasser, %.1f %% ohne DGM1 aus GEBCO oder Bühne; Hauptraster: %,d Zellen BSH, %,d Land aus GEBCO, %,d aus der Bühne",
                100.0 * kDgm / quelle.length, 100.0 * kGrund / quelle.length, 100.0 * kErs / quelle.length, bshZellen, gebcoLand, buehneLand));

        // ------------------------------------------------ Fernring
        float[] fern = new float[FN * FN];
        int ratio = (int) Math.round(Terrain.FCELL / Terrain.CELL);
        int offI = (int) Math.round((Terrain.X0 - Terrain.FX0) / Terrain.FCELL), offJ = (int) Math.round((Terrain.Z0 - Terrain.FZ0) / Terrain.FCELL);
        int[] fz = new int[2];
        for (int j = 0; j < FN; j++)
            for (int i = 0; i < FN; i++) {
                int q = j * FN + i;
                int hi = (i - offI) * ratio, hj = (j - offJ) * ratio;
                if (hi >= 0 && hj >= 0 && hi + ratio <= N && hj + ratio <= N) {
                    double s = 0;
                    for (int b2 = 0; b2 < ratio; b2++) for (int a2 = 0; a2 < ratio; a2++) s += haupt[(hj + b2) * N + hi + a2];
                    fern[q] = (float) (s / (ratio * ratio));
                    continue;
                }
                double x = Terrain.fcx(i), z = Terrain.fcz(j);
                double[] u = Site.toUtm(x, z);
                double g = gebco.utm(u[0], u[1]), bu = Buehne.height(x, z);
                if (!Double.isNaN(g)) {
                    double w = smooth(0, GEBCO_RAND * 2, gebco.randAbstand(u[0], u[1]));
                    fern[q] = (float) ((bu < 0 ? Math.min(g, -0.5) : Math.max(g, 0.5)) * w + bu * (1 - w));
                    fz[0]++;
                }
                else { fern[q] = (float) bu; fz[1]++; }
            }
        log.accept(Raster.f("Fernring: %.0f %% aus dem Hauptraster, %.0f %% GEBCO, %.0f %% Bühne [G]",
                100.0 - 100.0 * (fz[0] + fz[1]) / (FN * FN), 100.0 * fz[0] / (FN * FN), 100.0 * fz[1] / (FN * FN)));

        // ------------------------------------------------ Arten (vorläufig)
        byte[] art = new byte[N * N];
        for (int j = 0; j < N; j++)
            for (int i = 0; i < N; i++) {
                int q = j * N + i;
                float h = haupt[q];
                double e = hE0 + (i + 0.5) * Terrain.CELL, n = hN0 - (j + 0.5) * Terrain.CELL;
                if (!hLand[q]) { art[q] = h > -0.4 && hArtW[q] == 1 ? Terrain.NASS : Terrain.SEE; continue; }
                byte a = Nutzung.art(nh[q]);
                double nb = neigung(haupt, N, i, j, Terrain.CELL), dw = landAbstandZumWasser(hAbst, hLand, N, i, j);
                if (nb > 0.45) a = Terrain.KLIFF;
                else if (dw < 25 && h < 1.0 && (a == Terrain.KEINE || a == Terrain.STRAND)) a = Terrain.NASS;
                else if (dw < 80 && h < 3.0 && (a == Terrain.KEINE || a == Terrain.STRAND || a == Terrain.WIESE) && hArtNachbar(hArtW, hLand, N, i, j) == 1) a = Terrain.STRAND;
                if (a == Terrain.KEINE) {
                    double[] xz = Site.fromUtm(e, n);
                    a = Buehne.art(xz[0], xz[1], h);
                    if (a == Terrain.SEE || a == Terrain.NASS) a = Terrain.WIESE;
                }
                art[q] = a;
            }
        short[] kS = new short[KN * KN], hS = new short[N * N], fS = new short[FN * FN];
        for (int k = 0; k < kS.length; k++) kS[k] = Terrain.encode(kFin[k]);
        for (int k = 0; k < hS.length; k++) hS[k] = Terrain.encode(haupt[k]);
        for (int k = 0; k < fS.length; k++) fS[k] = Terrain.encode(fern[k]);
        Properties p = new Properties();
        p.setProperty("quelle", Raster.f("DGM1 2022/2023 (%d Kacheln) + Grund aus Strandprofil, GEBCO%s", gelesen, bsh != null ? " und BSH" : ""));
        p.setProperty("kern", "DGM1 2022/2023, 1 m; Wasser: Grund nach dem Abstand zum Land (Strandprofil mit Riffen [G]), in die 10-m-Fläche übergehend");
        p.setProperty("haupt", "DGM1 zu 10 m gemittelt; Bucht: Strandprofil [G], ab " + (int) GEBCO_AB + " m GEBCO, Travemündung BSH; ohne Kachel GEBCO oder Bühne");
        p.setProperty("fern", "Hauptraster zu 100 m; sonst GEBCO 15″, sonst Bühne [G]");
        p.setProperty("arten", mitAlkis ? "vorläufig aus ALKIS, Höhe und Abstand zum Wasser (Schritt luftbild verfeinert sie mit DOP20)" : "vorläufig aus Höhe und Bühne (ohne ALKIS)");
        p.setProperty("werkzeug", "com.dan.timmendorf.tools.GelaendeImport");
        p.setProperty("datum", java.time.LocalDate.now().toString());
        p.setProperty("lizenz", "Geobasisdaten © GeoBasis-DE/LVermGeo SH (Datenlizenz Deutschland – Namensnennung – 2.0); GEBCO Compilation Group (2026) GEBCO 2026 Grid; "
                + "BSH 1850 Lübeck approach via EMODnet Bathymetry (CC BY 4.0); Küste © OpenStreetMap-Mitwirkende (ODbL)");
        Terrain t = new Terrain(hS, fS, kS, art, p);
        t.save(out);
        Files.write(new File(out, QUELLE).toPath(), quelle);
        b.terrain = t;
        b.quelle = quelle;

        // Orte und Prüfwerte
        for (Site.Ort o : Site.ORTE)
            b.zeilen.add(Raster.f("%s: Grund %.1f m NHN (Bühne %.1f m)", o.name(), t.roh(o.x(), o.z()), Buehne.height(o.x(), o.z())));
        b.zeilen.add(Raster.f("Bucht vor der Seebrücke: 200 m %.1f m, 1 km %.1f m, 3 km %.1f m, 6 km %.1f m NHN",
                quer(t, -200), quer(t, -1000), quer(t, -3000), quer(t, -6000)));
        b.zeilen.add(Raster.f("Wasserlinie gegen die OSM-Küste: %s", wasserlinieGegenOsm(t, quelle)));
        boolean[] kValid = new boolean[KN * KN];
        for (int k = 0; k < KN * KN; k++) kValid[k] = quelle[k] == 1;
        b.naht = naehte(t, kValid, quelle);
        b.zeilen.add(0, Raster.f("Nähte: an den Kachelrändern im Kern springt das Gelände 99 %% unter %.2f m, auf den Mittellinien 99 %% unter %.2f m "
                        + "(Rauheit), am Rand im Mittel %+.3f m mehr; DGM1 → Grund 99 %% unter %.2f m (größte %.2f m); Kern → Hauptraster %.3f m, "
                        + "Hauptraster → Fernring %.3f m",
                b.naht[0], b.naht[6], b.naht[7], b.naht[2], b.naht[3], b.naht[4], b.naht[5]));
        b.zeilen.add(0, t.describe());
        for (String z : b.zeilen) log.accept("  " + z);
        log.accept(Raster.f("Gelände: %s · %.1f s", out.getPath(), (System.nanoTime() - t0) / 1e9));
        return b;
    }

    /** Höhe quer zur Küste an der Seebrücke, s m landwärts (negativ: seewärts). */
    static double quer(Terrain t, double s) {
        double[] u = Site.toUtm(0, 0);
        double h = 5, ge = (Site.kuestenAbstand(u[0] + h, u[1]) - Site.kuestenAbstand(u[0] - h, u[1])) / (2 * h),
                gn = (Site.kuestenAbstand(u[0], u[1] + h) - Site.kuestenAbstand(u[0], u[1] - h)) / (2 * h), l = Math.hypot(ge, gn);
        double d = Site.kuestenAbstand(u[0], u[1]);
        double[] xz = Site.fromUtm(u[0] + (s - d) * ge / l, u[1] + (s - d) * gn / l);
        return t.roh(xz[0], xz[1]);
    }

    /** Wo das Gelände NHN 0 schneidet, gegen die OSM-Küste im Kern: Mittel und 90 % des Abstands. */
    static String wasserlinieGegenOsm(Terrain t, byte[] quelle) {
        double[] d = new double[200_000];
        int c = 0;
        for (int j = 2; j < Terrain.KN - 2 && c < d.length; j += 2)
            for (int i = 2; i < Terrain.KN - 2 && c < d.length; i += 1) {
                double a = Terrain.decode(t.kern[j * Terrain.KN + i]), b = Terrain.decode(t.kern[j * Terrain.KN + i + 1]);
                if ((a < 0) == (b < 0)) continue;
                if (quelle[j * Terrain.KN + i] != 1 && quelle[j * Terrain.KN + i + 1] != 1) continue;
                double x = Terrain.kcx(i) + 0.5, z = Terrain.kcz(j);
                double[] u = Site.toUtm(x, z);
                double s = Site.kuestenAbstand(u[0], u[1]);
                if (Math.abs(s) > 300) continue;   // Seen und Teiche zählen nicht
                d[c++] = Math.abs(s);
            }
        if (c == 0) return "keine Wasserlinie im Kern";
        double m = 0;
        for (int k = 0; k < c; k++) m += d[k];
        return Raster.f("%,d Stellen, im Mittel %.1f m, 90 %% unter %.1f m", c, m / c, Raster.quantil(d, c, 0.9));
    }

    /** Ob die 3 × 3 Nachbarn eines DGM1-Punkts bis auf {@link Dgm1#EBEN} so hoch sind wie er (Wasserspiegel). */
    static boolean eben(float[] h, int x, int y) {
        float v = h[y * 1000 + x];
        for (int b = -1; b <= 1; b++)
            for (int a = -1; a <= 1; a++) {
                int xx = x + a, yy = y + b;
                if (xx < 0 || yy < 0 || xx >= 1000 || yy >= 1000) continue;
                float q = h[yy * 1000 + xx];
                if (!Float.isNaN(q) && Math.abs(q - v) > Dgm1.EBEN) return false;
            }
        return true;
    }

    static byte[] lies(File f, int len) throws IOException {
        if (!f.isFile()) return null;
        byte[] b = Files.readAllBytes(f.toPath());
        return b.length == len ? b : null;
    }

    static double bilinear(float[] a, int n, double u, double v) {
        int i = (int) Math.floor(u), j = (int) Math.floor(v);
        double fx = u - i, fy = v - j;
        i = Math.max(0, Math.min(n - 2, i));
        j = Math.max(0, Math.min(n - 2, j));
        fx = Math.max(0, Math.min(1, u - i));
        fy = Math.max(0, Math.min(1, v - j));
        return (a[j * n + i] * (1 - fx) + a[j * n + i + 1] * fx) * (1 - fy) + (a[(j + 1) * n + i] * (1 - fx) + a[(j + 1) * n + i + 1] * fx) * fy;
    }

    /** Abstand einer Landzelle zum nächsten Wasser (m), grob aus den Nachbarn bis 100 m. */
    static double landAbstandZumWasser(float[] abst, boolean[] land, int n, int i, int j) {
        for (int r = 1; r <= 10; r++)
            for (int b = -r; b <= r; b++)
                for (int a = -r; a <= r; a++) {
                    if (Math.max(Math.abs(a), Math.abs(b)) != r) continue;
                    int x = i + a, y = j + b;
                    if (x < 0 || y < 0 || x >= n || y >= n) continue;
                    if (!land[y * n + x]) return Math.hypot(a, b) * Terrain.CELL;
                }
        return 1e9;
    }

    /** Art des nächsten Wassers um eine Landzelle (bis 100 m), 0 wenn keins. */
    static byte hArtNachbar(byte[] artW, boolean[] land, int n, int i, int j) {
        for (int r = 1; r <= 10; r++)
            for (int b = -r; b <= r; b++)
                for (int a = -r; a <= r; a++) {
                    int x = i + a, y = j + b;
                    if (x < 0 || y < 0 || x >= n || y >= n) continue;
                    if (!land[y * n + x]) return artW[y * n + x];
                }
        return 0;
    }

    static double neigung(float[] h, int n, int i, int j, double cell) {
        int i0 = Math.max(0, i - 1), i1 = Math.min(n - 1, i + 1), j0 = Math.max(0, j - 1), j1 = Math.min(n - 1, j + 1);
        double dx = (h[j * n + i1] - h[j * n + i0]) / ((i1 - i0) * cell), dz = (h[j1 * n + i] - h[j0 * n + i]) / ((j1 - j0) * cell);
        return Math.hypot(dx, dz);
    }

    static double smooth(double a, double b, double x) {
        double t = Math.max(0, Math.min(1, (x - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }

    /**
     * Kennzahlen der Nähte (m): [0, 1] Kachelränder des DGM1 im Kern (99 %, größter): Sprung über den Rand gegen die Steigung
     * zu beiden Seiten; [6] dasselbe auf den Mittellinien der Kacheln (99 %), [7] mittlerer Sprung am Rand minus in der Mitte; [2, 3] Übergang DGM1 → BAW (Betrag des Schritts zwischen Nachbarn, 99 %, größter); [4] Kern →
     * Hauptraster und [5] Hauptraster → Fernring: größter Unterschied des Schritts der Oberfläche an den Bandkanten gegen
     * den Schritt des inneren Rasters allein.
     */
    public static double[] naehte(Terrain t, boolean[] valid, byte[] quelle) {
        int KN = Terrain.KN;
        double kE0 = Terrain.KX0 + Site.E0, kN0 = Site.N0 - Terrain.KZ0;
        double[] rand = new double[KN * 16], mitte = new double[KN * 16];
        int[] c = new int[2];
        double mx = 0;
        double[] summe = new double[2];
        // Kachelränder (ganze km) und zum Vergleich die Mittellinien der Kacheln (km + 500 m): der Sprung über den
        // Rand misst auch die Rauheit des Geländes; eine Naht ist, was am Rand mehr springt als in der Mitte
        for (int pass = 0; pass < 2; pass++) {
            double[] buf = pass == 0 ? rand : mitte;
            int off = pass == 0 ? 0 : 500;
            for (int km = (int) Math.floor(kE0 / 1000); km * 1000 < kE0 + KN; km++) {
                int b = (int) Math.round(km * 1000 + off - kE0);
                if (b < 2 || b > KN - 2) continue;
                for (int j = 0; j < KN; j++) {
                    int k = j * KN + b;
                    if (!valid[k - 2] || !valid[k - 1] || !valid[k] || !valid[k + 1]) continue;
                    double s = sprung(t.kernAt(b - 2, j), t.kernAt(b - 1, j), t.kernAt(b, j), t.kernAt(b + 1, j));
                    if (c[pass] < buf.length) buf[c[pass]++] = s;
                    summe[pass] += s;
                    if (pass == 0) mx = Math.max(mx, s);
                }
            }
            for (int km = (int) Math.floor((kN0 - KN) / 1000); km * 1000 < kN0; km++) {
                int b = (int) Math.round(kN0 - km * 1000 - off);
                if (b < 2 || b > KN - 2) continue;
                for (int i = 0; i < KN; i++) {
                    if (!valid[(b - 2) * KN + i] || !valid[(b - 1) * KN + i] || !valid[b * KN + i] || !valid[(b + 1) * KN + i]) continue;
                    double s = sprung(t.kernAt(i, b - 2), t.kernAt(i, b - 1), t.kernAt(i, b), t.kernAt(i, b + 1));
                    if (c[pass] < buf.length) buf[c[pass]++] = s;
                    summe[pass] += s;
                    if (pass == 0) mx = Math.max(mx, s);
                }
            }
        }
        double[] r = new double[8];
        r[0] = Raster.quantil(rand, c[0], 0.99);
        r[1] = mx;
        r[6] = Raster.quantil(mitte, c[1], 0.99);
        r[7] = summe[0] / Math.max(1, c[0]) - summe[1] / Math.max(1, c[1]);
        // DGM1 → BAW
        double[] tb = new double[KN * 8];
        int tc = 0;
        double tmx = 0;
        for (int j = 0; j < KN - 1; j++)
            for (int i = 0; i < KN - 1; i++) {
                int k = j * KN + i;
                for (int d = 0; d < 2; d++) {
                    int q = d == 0 ? k + 1 : k + KN;
                    if (valid[k] == valid[q]) continue;
                    double s = Math.abs(t.kernAt(i, j) - (d == 0 ? t.kernAt(i + 1, j) : t.kernAt(i, j + 1)));
                    if (tc < tb.length) tb[tc++] = s;
                    tmx = Math.max(tmx, s);
                }
            }
        r[2] = Raster.quantil(tb, tc, 0.99);
        r[3] = tmx;
        // Bandkanten: Kern → Hauptraster (1 m), Hauptraster → Fernring (10 m)
        r[4] = bandSprung(t, Terrain.KX0, Terrain.KZ0, KN * Terrain.KCELL, Terrain.KNAHT, 1, true);
        r[5] = bandSprung(t, Terrain.X0, Terrain.Z0, Terrain.N * Terrain.CELL, Terrain.NAHT, 10, false);
        return r;
    }

    /** Sprung am Rand zwischen h1 und h2 gegen die mittlere Steigung zu beiden Seiten. */
    static double sprung(double h0, double h1, double h2, double h3) {
        return Math.abs((h2 - h1) - ((h1 - h0) + (h3 - h2)) / 2);
    }

    static double bandSprung(Terrain t, double x0, double z0, double size, double band, double step, boolean kern) {
        double mx = 0;
        for (double edge : new double[]{0.5 * step, band - 0.5 * step})
            for (double s = band; s < size - band; s += step * 3) {
                double[][] pts = {{x0 + edge, z0 + s, 1, 0}, {x0 + size - edge, z0 + s, -1, 0}, {x0 + s, z0 + edge, 0, 1}, {x0 + s, z0 + size - edge, 0, -1}};
                for (double[] p : pts) {
                    double ax = p[0] - p[2] * step / 2, az = p[1] - p[3] * step / 2, bx = p[0] + p[2] * step / 2, bz = p[1] + p[3] * step / 2;
                    double sc = t.roh(bx, bz) - t.roh(ax, az);
                    double si = kern ? t.kernHeight(bx, bz) - t.kernHeight(ax, az) : t.mainHeight(bx, bz) - t.mainHeight(ax, az);
                    double so = kern ? t.baseHeight(bx, bz) - t.baseHeight(ax, az) : t.fernHeight(bx, bz) - t.fernHeight(ax, az);
                    double w = kern ? t.kernShare(p[0], p[1]) : 1 - Terrain.fernShare(p[0], p[1]);
                    mx = Math.max(mx, Math.abs(sc - (si * w + so * (1 - w))));
                }
            }
        return mx;
    }

    // ------------------------------------------------------------------ Prüfbild

    /** Prüfbild: links das Hauptraster (geschummert, eingefärbt), rechts der Kern zu 1 m mit den Orten, unten die Zahlen. */
    public static void bild(Bericht b, File png) throws IOException {
        Terrain t = b.terrain;
        int S = 1024, W = 2 * S + 30, H = S + 300;
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0x1d2328));
        g.fillRect(0, 0, W, H);
        int N = Terrain.N, KN = Terrain.KN;
        float[] hf = new float[N * N];
        for (int k = 0; k < hf.length; k++) hf[k] = (float) Terrain.decode(t.h[k]);
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++) {
                int i = x * 2, j = y * 2;
                img.setRGB(10 + x, 10 + y, Raster.hoehenfarbe(hf[j * N + i], Raster.schummer(hf, N, i, j, Terrain.CELL, 2)));
            }
        float[] kf = new float[KN * KN];
        for (int k = 0; k < kf.length; k++) kf[k] = (float) Terrain.decode(t.kern[k]);
        double sk = KN / (double) S;
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++) {
                int i = (int) (x * sk) + 2, j = (int) (y * sk) + 2;
                img.setRGB(20 + S + x, 10 + y, Raster.hoehenfarbe(kf[j * KN + i], Raster.schummer(kf, KN, i, j, 4, 1.5)));
            }
        g.setClip(20 + S, 10, S, S);
        // Kern- und Fernfeld-Rahmen im Hauptraster
        g.setStroke(new BasicStroke(1.5f));
        rahmen(g, Site.KERN, new Color(0xe0, 0x50, 0x40), 10, 10, Terrain.X0, Terrain.Z0, Terrain.CELL * 2);
        rahmen(g, Site.FERNFELD, new Color(0xf0, 0xd0, 0x80), 10, 10, Terrain.X0, Terrain.Z0, Terrain.CELL * 2);
        // OSM-Küste im Kern
        g.setColor(new Color(0x40, 0xa0, 0xff));
        double ks = Terrain.KN / (double) S;
        for (int i = 0; i + 1 < Site.KUESTE.length; i++) {
            double[] a = Site.fromUtm(Site.KUESTE[i][0], Site.KUESTE[i][1]), c = Site.fromUtm(Site.KUESTE[i + 1][0], Site.KUESTE[i + 1][1]);
            g.drawLine((int) Math.round(20 + S + (a[0] - Terrain.KX0) / ks), (int) Math.round(10 + (a[1] - Terrain.KZ0) / ks),
                    (int) Math.round(20 + S + (c[0] - Terrain.KX0) / ks), (int) Math.round(10 + (c[1] - Terrain.KZ0) / ks));
        }
        g.setClip(null);
        // Orte
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        for (Site.Ort o : Site.ORTE) {
            int px = (int) Math.round(20 + S + (o.x() - Terrain.KX0) / ks), py = (int) Math.round(10 + (o.z() - Terrain.KZ0) / ks);
            if (px < 20 + S || px > 20 + 2 * S || py < 10 || py > 10 + S) continue;
            g.setColor(Color.WHITE);
            g.drawOval(px - 4, py - 4, 8, 8);
            g.drawString(o.name(), px + 7, py + 4);
        }
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
        g.setColor(new Color(0xee, 0xee, 0xee));
        g.drawString("Hauptraster 20,5 km zu 10 m (rot: Kern, gelb: Fernfeld)", 16, 30);
        g.drawString("Kern 4,6 km zu 1 m (blau: Küste aus OSM)", 26 + S, 30);
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        int y = S + 34;
        g.drawString("Timmendorf Phase 2 · Gelände aus DGM1 2022/2023, GEBCO, BSH und Strandprofil · " + java.time.LocalDate.now(), 12, y);
        y += 20;
        for (String z : b.zeilen) {
            for (String l : Raster.wrap(z, 160)) { g.drawString(l, 12, y); y += 17; }
            if (y > H - 10) break;
        }
        g.dispose();
        File d = png.getAbsoluteFile().getParentFile();
        if (d != null) d.mkdirs();
        javax.imageio.ImageIO.write(img, "png", png);
    }

    static void rahmen(Graphics2D g, Site.Fenster f, Color c, int ox, int oy, double x0, double z0, double scale) {
        double[] s = f.scene();
        g.setColor(c);
        g.drawRect((int) Math.round(ox + (s[0] - x0) / scale), (int) Math.round(oy + (s[1] - z0) / scale), (int) Math.round((s[2] - s[0]) / scale), (int) Math.round((s[3] - s[1]) / scale));
    }

}
