package com.dan.timmendorf.scene;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Der Bewuchs der Szene (seit Phase 5a): Bäume und Sträucher aus dem bDOM, Strandhafer, Schilf und Gras nach den Klassen
 * des Luftbilds. Kennt weder OpenGL noch {@code com.dan.tree}; welche Art aus dem Renderer eine Gruppe zeichnet, sagt
 * {@link Gruppe#arten}.
 * <p>
 * <b>Bäume im Kern</b> (1 m): Kandidaten sind die Meter der Klassen Wald und Gebüsch ({@code daten/kern/klasse.u8}, Pflanzen
 * nach dem Infrarot, also keine Dächer). Über die Bewuchshöhe ({@code daten/kern/bewuchs.u8}, bDOM minus Gelände)
 * geglättet, ist jeder Meter ein Baum, der in seinem Kronenhalbmesser (1,5 bis 4,5 m, mit der Höhe wachsend [G]) am
 * höchsten ist; seine Höhe ist der höchste Wert daneben. Unter 2 m bleibt es Wiese, bis 5 m ist es ein Strauch.
 * <p>
 * <b>Bäume im Hauptraster</b> (10 m, außerhalb des Kerns): je Zelle der Art Wald mit über 4 m Bewuchs ein Baum, versetzt
 * nach einem Hash in der Zelle, 85 % des höchsten Meters [G].
 * <p>
 * <b>Gruppen</b> [G nach dem Werkbuch, Abschnitt 9]: Ufer (bis 25 m am Binnenwasser, Sumpf, Moor) → Erlen; Kurpark und
 * Grünanlagen (ALKIS Sport, Freizeit und Erholung, Friedhof) → Park; Straßen, Wege, Plätze und Bebauung → Straßenbäume
 * (darunter die der Promenade); am Steilufer und im Hauptraster am Kliff → Buchen; Nadelwald nach ALKIS → Kiefern; sonst
 * Laubwald. Seit Phase 19 (F24 A) zeichnen Rotbuche und junge Kiefer aus dem Renderer, wo sie die Höhe erreichen; die
 * Linde fehlt noch (ohne Texturen), für sie und für hohe Kiefern stehen Laubbäume aus dem Bestand ein.
 */
public final class Bewuchs {
    /** Wofür ein Baum steht, mit den Arten aus {@code com.dan.tree.Tree} (Schlüssel), die ihn bis F24 zeichnen. */
    public enum Gruppe {
        PARK("Kurpark und Grünanlagen", "Kiefer, Linde", 0.5, "kiefer", "laubbaum", "grosser-laubbaum"),
        STRASSE("Straßen und Promenade", "Linde", 0, "laubbaum", "grosser-laubbaum"),
        LAUBWALD("Laubwald", "Rotbuche", 0.7, "rotbuche", "laubbaum", "grosser-laubbaum"),
        NADELWALD("Nadelwald", "Kiefer", 1, "kiefer", "laubbaum"),
        STEILUFER("Brodtener Steilufer", "Rotbuche", 0.7, "rotbuche", "grosser-laubbaum", "laubbaum"),
        UFER("Ufer und Bruch", "Schwarzerle", 1, "schwarzerle"),
        STRAUCH("Gebüsch und Hecken", "Strauch", 1, "greasewood");

        public final String name, eigentlich;
        public final String[] arten;
        /**
         * Seit Phase 19 (F24): Anteil der Bäume, für die die erste Art (die eigentliche, sobald sie im Renderer ist)
         * zuerst gefragt wird; 0 = es gibt sie noch nicht (Linde). Die Zeichnung nimmt sie nur, wo ihre Varianten die Höhe
         * des Baums erreichen (die Jungkiefern bis rund 6 m), sonst eine der übrigen Arten.
         */
        public final double echt;

        Gruppe(String name, String eigentlich, double echt, String... arten) {
            this.name = name;
            this.eigentlich = eigentlich;
            this.echt = echt;
            this.arten = arten;
        }
    }

    /** Ein Baum: Lage (Szene, m), Höhe über Grund (m), Gruppe, Kennzahl (Variante, Drehung, Art). */
    public record Baum(float x, float z, float hoehe, Gruppe gruppe, int kennzahl) {
        /** Die Art aus {@link Gruppe#arten} nach der Kennzahl (bis Phase 18 die gezeichnete; seit Phase 19 wählt {@code gl.BewuchsGl.waehle} nach Höhe und Art). */
        public String art() { return gruppe.arten[(kennzahl >>> 8) % gruppe.arten.length]; }
        /** Drehung um die Hochachse (rad). */
        public float drehung() { return (float) ((kennzahl & 255) / 256.0 * Math.PI * 2); }
    }

    /** Schwellen [G]: Wiese bis hier (m), Strauch bis {@link #STRAUCH_BIS}, Baum im Hauptraster ab {@link #HAUPT_AB}. */
    public static final double BAUM_AB = 2.0, STRAUCH_BIS = 5.0, HAUPT_AB = 4.0;
    /** Wie nah ein Baum an der Promenade stehen muss, um zu ihr zu zählen (m). */
    public static final double PROMENADE_ABSTAND = 15;

    public final List<Baum> baeume;
    public final String quelle;
    /** Kern: Klassen und Abstand zum Binnenwasser und zum Meer (m, gekappt bei 250) je Meter; null ohne Daten. */
    final byte[] klasse;
    final short[] binnen, meer;
    public final long millis;

    private Bewuchs(List<Baum> b, byte[] klasse, short[] binnen, short[] meer, String q, long ms) {
        baeume = Collections.unmodifiableList(b);
        this.klasse = klasse;
        this.binnen = binnen;
        this.meer = meer;
        quelle = q;
        millis = ms;
    }

    public static Bewuchs leer(String warum) { return new Bewuchs(List.of(), null, null, null, warum, 0); }

    /** Liest die Raster aus {@code daten} und sucht die Bäume (gleiche Daten, gleiche Bäume). */
    public static Bewuchs laden(File daten, Terrain t) {
        long t0 = System.nanoTime();
        int KN = Terrain.KN, N = Terrain.N;
        try {
            byte[] bew = lies(new File(daten, "kern/bewuchs.u8"), KN * KN), kl = lies(new File(daten, "kern/klasse.u8"), KN * KN);
            if (bew == null || kl == null) return leer("ohne daten/kern/bewuchs.u8 und klasse.u8");
            byte[] nk = lies(new File(daten, "alkis/nutzung_kern.u8"), KN * KN), nh = lies(new File(daten, "alkis/nutzung_haupt.u8"), N * N);
            byte[] bh = lies(new File(daten, "gelaende/bewuchs_haupt.u8"), N * N);
            if (nk == null) nk = new byte[KN * KN];
            if (nh == null) nh = new byte[N * N];
            boolean[] bw = new boolean[KN * KN], mw = new boolean[KN * KN];
            for (int k = 0; k < bw.length; k++) {
                int nu = nk[k];
                bw[k] = nu == Nutzung.STEHENDES_GEWAESSER || nu == Nutzung.FLIESSGEWAESSER;
                mw[k] = kl[k] == Terrain.SEE && !bw[k];
            }
            short[] db = abstand(bw, KN), dm = abstand(mw, KN);
            List<Baum> b = new ArrayList<>();
            kern(bew, kl, nk, db, b);
            if (bh != null) haupt(bh, t.art, nh, b);
            String q = String.format(Locale.GERMAN, "bDOM 2023 mit Klassen aus DOP20 und ALKIS%s", bh == null ? " (ohne Hauptraster)" : "");
            return new Bewuchs(b, kl, db, dm, q, (System.nanoTime() - t0) / 1_000_000);
        } catch (IOException | OutOfMemoryError e) {
            return leer("daten nicht lesbar: " + e);
        }
    }

    static byte[] lies(File f, int n) throws IOException {
        return f.length() == n ? Files.readAllBytes(f.toPath()) : null;
    }

    /** Bäume im Kern aus den Kronenspitzen. */
    static void kern(byte[] bew, byte[] kl, byte[] nk, short[] binnen, List<Baum> out) {
        int n = Terrain.KN;
        float[] s = new float[n * n];
        // geglättet (3×3, nur Pflanzenmeter); 0,2 m je Stufe
        for (int j = 1; j < n - 1; j++)
            for (int i = 1; i < n - 1; i++) {
                int k = j * n + i;
                if (!pflanze(kl[k])) continue;
                float sum = 0;
                int c = 0;
                for (int b = -1; b <= 1; b++)
                    for (int a = -1; a <= 1; a++) {
                        int q = k + b * n + a;
                        if (!pflanze(kl[q])) continue;
                        sum += (bew[q] & 0xFF) * 0.2f;
                        c++;
                    }
                s[k] = sum / c;
            }
        double[] w = Site.KERN.scene();
        for (int j = 5; j < n - 5; j++)
            for (int i = 5; i < n - 5; i++) {
                int k = j * n + i;
                float h = s[k];
                if (h < BAUM_AB) continue;
                double cx = Terrain.kcx(i), cz = Terrain.kcz(j);
                if (cx < w[0] || cx > w[2] || cz < w[1] || cz > w[3]) continue;   // gleiche Grenze wie im Hauptraster
                int r = (int) Math.round(Math.max(1.5, Math.min(4.5, 1.2 + 0.1 * h)));
                boolean spitze = true;
                for (int b = -r; b <= r && spitze; b++)
                    for (int a = -r; a <= r; a++) {
                        if (a * a + b * b > r * r || (a == 0 && b == 0)) continue;
                        float o = s[k + b * n + a];
                        // bei Gleichstand gewinnt der erste Meter (nach Zeile, dann Spalte)
                        if (o > h || (o == h && (b < 0 || (b == 0 && a < 0)))) { spitze = false; break; }
                    }
                if (!spitze) continue;
                float top = 0;
                for (int b = -1; b <= 1; b++) for (int a = -1; a <= 1; a++) top = Math.max(top, (bew[k + b * n + a] & 0xFF) * 0.2f);
                int nu = nk[k];
                Gruppe g;
                if (top < STRAUCH_BIS) g = Gruppe.STRAUCH;
                else if (binnen[k] < 25 || nu == Nutzung.SUMPF || nu == Nutzung.MOOR) g = Gruppe.UFER;
                else if (nu == Nutzung.ERHOLUNG || nu == Nutzung.FRIEDHOF) g = Gruppe.PARK;
                else if (nu == Nutzung.NADELWALD) g = Gruppe.NADELWALD;
                else if (Nutzung.wald(nu) || nu == Nutzung.GEHOELZ) g = steilufer(i, j) ? Gruppe.STEILUFER : Gruppe.LAUBWALD;
                else g = Gruppe.STRASSE;
                float jx = (float) (hash(i, j, 1) - 0.5) * 0.6f, jz = (float) (hash(i, j, 2) - 0.5) * 0.6f;
                out.add(new Baum((float) Terrain.kcx(i) + jx, (float) Terrain.kcz(j) + jz, top, g, (int) (hash(i, j, 3) * (1 << 20))));
            }
    }

    /** Ob ein Meter des Kerns am Brodtener Ufer liegt (östlich von Niendorf, in Ost 619 000 bis 619 500) [G]. */
    static boolean steilufer(int i, int j) { return Terrain.kcx(i) + Site.E0 > 618_900 && Site.N0 - Terrain.kcz(j) > 5_983_600; }

    static boolean pflanze(byte c) { return c == Terrain.WALD || c == Terrain.GEBUESCH; }

    /** Bäume im Hauptraster außerhalb des Kerns. */
    static void haupt(byte[] bh, byte[] art, byte[] nh, List<Baum> out) {
        int n = Terrain.N;
        double[] kern = Site.KERN.scene();
        for (int j = 1; j < n - 1; j++)
            for (int i = 1; i < n - 1; i++) {
                int k = j * n + i;
                if (art[k] != Terrain.WALD && art[k] != Terrain.GEBUESCH) continue;
                double h = (bh[k] & 0xFF) * 0.2;
                if (h < HAUPT_AB) continue;
                double x = Terrain.cx(i) + (hash(i, j, 11) - 0.5) * 7, z = Terrain.cz(j) + (hash(i, j, 12) - 0.5) * 7;
                if (x > kern[0] && x < kern[2] && z > kern[1] && z < kern[3]) continue;
                int nu = nh[k];
                boolean kliff = false;
                for (int b = -2; b <= 2 && !kliff; b++)
                    for (int a = -2; a <= 2; a++) {
                        int ii = i + a, jj = j + b;
                        if (ii >= 0 && jj >= 0 && ii < n && jj < n && art[jj * n + ii] == Terrain.KLIFF) { kliff = true; break; }
                    }
                Gruppe g = kliff ? Gruppe.STEILUFER : nu == Nutzung.NADELWALD ? Gruppe.NADELWALD : nu == Nutzung.SUMPF || nu == Nutzung.MOOR ? Gruppe.UFER
                        : art[k] == Terrain.GEBUESCH && h < 7 ? Gruppe.STRAUCH : Nutzung.siedlung(nu) ? Gruppe.STRASSE : Gruppe.LAUBWALD;
                out.add(new Baum((float) x, (float) z, (float) (h * 0.85), g, (int) (hash(i, j, 13) * (1 << 20))));
            }
    }

    /** Hash 0..1 aus Zelle und Salz (fest, ohne Zufall). */
    public static double hash(int i, int j, int s) {
        long h = i * 0x9E3779B97F4A7C15L ^ j * 0xC2B2AE3D27D4EB4FL ^ s * 0x165667B19E3779F9L ^ 1141L;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }

    /** Abstand zum nächsten wahren Meter (m, Chamfer 3-4, gekappt bei 250 m). */
    static short[] abstand(boolean[] ziel, int n) {
        int max = 250 * 3;
        short[] d = new short[n * n];
        for (int k = 0; k < d.length; k++) d[k] = (short) (ziel[k] ? 0 : max);
        for (int j = 0; j < n; j++)
            for (int i = 0; i < n; i++) {
                int k = j * n + i, v = d[k];
                if (i > 0) v = Math.min(v, d[k - 1] + 3);
                if (j > 0) {
                    v = Math.min(v, d[k - n] + 3);
                    if (i > 0) v = Math.min(v, d[k - n - 1] + 4);
                    if (i < n - 1) v = Math.min(v, d[k - n + 1] + 4);
                }
                d[k] = (short) Math.min(max, v);
            }
        for (int j = n - 1; j >= 0; j--)
            for (int i = n - 1; i >= 0; i--) {
                int k = j * n + i, v = d[k];
                if (i < n - 1) v = Math.min(v, d[k + 1] + 3);
                if (j < n - 1) {
                    v = Math.min(v, d[k + n] + 3);
                    if (i < n - 1) v = Math.min(v, d[k + n + 1] + 4);
                    if (i > 0) v = Math.min(v, d[k + n - 1] + 4);
                }
                d[k] = (short) Math.min(max, v);
            }
        for (int k = 0; k < d.length; k++) d[k] = (short) (d[k] / 3);
        return d;
    }

    private int kIndex(double x, double z) {
        int i = (int) Math.floor(x - Terrain.KX0), j = (int) Math.floor(z - Terrain.KZ0);
        return i < 0 || j < 0 || i >= Terrain.KN || j >= Terrain.KN ? -1 : j * Terrain.KN + i;
    }

    /**
     * Dichte 0..1 von Halmen am Ort [G]: Schilf auf der Klasse Schilf und bis 6 m am Binnenwasser (Hemmelsdorfer See,
     * Aalbek); Binsen am nassen Rand (bis 12 m); Strandhafer auf dem Strand 30 bis 110 m vom Meer und auf Wiese bis 50 m
     * vom Meer; Gras auf Wiese und unter Gebüsch. Außerhalb des Kerns nur Gras nach der Art des Hauptrasters.
     */
    public double dichte(double x, double z, Halm art, Terrain t) {
        int k = klasse == null ? -1 : kIndex(x, z);
        if (k < 0) {
            int a = t.artAt(x, z);
            return switch (art) {
                case GRAS -> a == Terrain.WIESE ? 0.7 : a == Terrain.GEBUESCH ? 0.4 : 0;
                case SCHILF -> a == Terrain.SCHILF ? 0.8 : 0;
                default -> 0;
            };
        }
        int c = klasse[k], db = binnen[k], dm = meer[k];
        return switch (art) {
            case SCHILF -> c == Terrain.SCHILF ? 1 : (c == Terrain.WIESE || c == Terrain.NASS) && db <= 6 ? 0.85 : 0;
            case BINSE -> (c == Terrain.WIESE || c == Terrain.SCHILF) && db > 3 && db <= 12 ? 0.5 : 0;
            case STRANDHAFER -> c == Terrain.STRAND && dm > 30 && dm < 110 ? 0.35 : c == Terrain.WIESE && dm < 50 ? 0.6 : 0;
            case GRAS -> c == Terrain.WIESE ? (dm < 50 ? 0.3 : 0.8) : c == Terrain.GEBUESCH ? 0.4 : c == Terrain.WALD ? 0.15 : 0;
        };
    }

    /** Die Halme, die {@link #dichte} kennt (wie {@code com.dan.reed.Reed.Kind}). */
    public enum Halm { SCHILF, BINSE, GRAS, STRANDHAFER }

    /** Zahl der Bäume je Gruppe. */
    public Map<Gruppe, Integer> zahl() {
        Map<Gruppe, Integer> m = new EnumMap<>(Gruppe.class);
        for (Gruppe g : Gruppe.values()) m.put(g, 0);
        for (Baum b : baeume) m.merge(b.gruppe(), 1, Integer::sum);
        return m;
    }

    /** Wie viele Bäume (über {@link #STRAUCH_BIS}) höchstens {@link #PROMENADE_ABSTAND} m neben der Promenade stehen. */
    public int anDerPromenade(Netz fuss) {
        if (fuss == null) return 0;
        List<double[]> p = new ArrayList<>();
        for (Netz.Kante k : fuss.kanten) {
            String nm = k.name().toLowerCase(Locale.ROOT);
            if (!nm.contains("promenade")) continue;
            double[] q = k.punkte();
            for (int i = 0; i + 1 < k.punktzahl(); i++)
                p.add(new double[]{q[i * 3] - Site.E0, Site.N0 - q[i * 3 + 1], q[i * 3 + 3] - Site.E0, Site.N0 - q[i * 3 + 4]});
        }
        int n = 0;
        double r2 = PROMENADE_ABSTAND * PROMENADE_ABSTAND;
        for (Baum b : baeume) {
            if (b.hoehe() < STRAUCH_BIS) continue;
            for (double[] s : p) {
                if (Math.min(s[0], s[2]) - PROMENADE_ABSTAND > b.x() || Math.max(s[0], s[2]) + PROMENADE_ABSTAND < b.x()) continue;
                if (abstand2(b.x(), b.z(), s) <= r2) { n++; break; }
            }
        }
        return n;
    }

    static double abstand2(double x, double z, double[] s) {
        double dx = s[2] - s[0], dz = s[3] - s[1], l = dx * dx + dz * dz;
        double u = l == 0 ? 0 : Math.max(0, Math.min(1, ((x - s[0]) * dx + (z - s[1]) * dz) / l));
        double ex = s[0] + u * dx - x, ez = s[1] + u * dz - z;
        return ex * ex + ez * ez;
    }

    private String beschreibung;

    /** „41 230 Bäume (Kern 28 100, Wald 12 300 …) aus bDOM … in 0,9 s“ (einmal gerechnet; das HUD fragt jedes Bild) */
    public String describe() {
        String d = beschreibung;
        if (d == null) beschreibung = d = beschreiben();
        return d;
    }

    private String beschreiben() {
        if (baeume.isEmpty()) return "kein Bewuchs (" + quelle + ")";
        StringBuilder sb = new StringBuilder();
        for (var e : zahl().entrySet()) if (e.getValue() > 0) sb.append(sb.length() > 0 ? ", " : "").append(e.getKey().name).append(' ').append(String.format(Locale.GERMAN, "%,d", e.getValue()));
        return String.format(Locale.GERMAN, "%,d Bäume und Sträucher (%s) aus %s, %.1f s", baeume.size(), sb, quelle, millis / 1000.0);
    }
}
