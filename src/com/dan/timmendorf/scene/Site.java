package com.dan.timmendorf.scene;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Der Ort der Szene: Timmendorfer Strand und die Lübecker Bucht von Haffkrug bis Travemünde (Werkbuch, Abschnitte 3
 * und 5).
 * <p>
 * Raum (F2 A): UTM Zone 32 Nord (ETRS89, EPSG:25832), echter Maßstab, Höhen über NHN, Ursprung am Landende der Maritim
 * Seebrücke (aus dem OSM-Umriss „Neue Seebrücke“, way 908673437: E 616 826, N 5 985 081; Phase 2 prüft am DOP20 2025).
 * 1 Einheit = 1 m, x nach Osten, y nach oben, z nach Süden, Nord = −z, y = 0 ist Normalhöhennull. Für die Datenbank und
 * QGIS liegt die Lage zusätzlich in SRID 8307 (WGS84) vor.
 * <p>
 * Ebenen (F1 A, Werkbuch Abschnitt 5): der <b>Kern</b> Timmendorf bis Niendorf mit 4,5 × 3,7 km (DGM1 1 m, Kanten 20 cm
 * aus dem bDOM), die <b>Bucht</b> (Grund aus BSH, EMODnet, GEBCO), das <b>Fernfeld</b> Haffkrug bis Travemünde mit
 * 18 × 14 km (10 m) und dahinter der <b>Fernring</b> über die Bucht bis zur Mecklenburger Küste. Die Küste der Bühne ist die
 * OSM-Küstenlinie ({@code kueste_osm.txt}); die übrigen Orte sind aus Länge und Breite gerechnet [G, zu prüfen].
 */
public final class Site {
    private Site() { }

    /** Ursprung der Szene: Landende der Maritim Seebrücke, UTM 32 N (F2 A). */
    public static final double E0 = 616_826, N0 = 5_985_081;
    public static final int SRID = 8307, EPSG_UTM = 25832;
    /** Breite und Länge des Ursprungs (aus E0/N0 gerechnet). */
    public static final double LAT, LON;
    static {
        double[] ll = Geo.UTM32N.inverse(E0, N0);
        LAT = ll[0];
        LON = ll[1];
    }

    /** Mittlerer Erdradius in m und der wirksame Radius für die Sicht mit normaler Lichtbrechung (k = 0,13). */
    public static final double EARTH_R = 6_371_000, EARTH_R_OPTICAL = EARTH_R / (1 - 0.13);

    /** Ein rechteckiges Fenster in UTM 32 N (Ost- und Nordwerte in m). */
    public record Fenster(String name, double eMin, double nMin, double eMax, double nMax) {
        /** In der Szene: {xMin, zMin, xMax, zMax}. */
        public double[] scene() {
            double[] a = fromUtm(eMin, nMax), b = fromUtm(eMax, nMin);
            return new double[]{a[0], a[1], b[0], b[1]};
        }
        public double breite() { return eMax - eMin; }
        public double hoehe() { return nMax - nMin; }
        public boolean contains(double x, double z) {
            double[] u = toUtm(x, z);
            return u[0] >= eMin && u[0] <= eMax && u[1] >= nMin && u[1] <= nMax;
        }
    }

    /** F1 A: der Kern, 4,5 × 3,7 km: Timmendorf, Seebrücke, Wolkenlos, Niendorfer Hafen, Nordende des Hemmelsdorfer Sees. */
    public static final Fenster KERN = new Fenster("Kern", 615_000, 5_982_800, 619_500, 5_986_500);
    /** F1 A: das Fernfeld Haffkrug bis Travemünde, 18 × 14 km (die Kacheln der Rohdaten). */
    public static final Fenster FERNFELD = new Fenster("Fernfeld", 612_000, 5_977_000, 630_000, 5_991_000);
    /** Halbmesser des Fernrings um die Mitte des Fernfelds bis über die Kimm (m) [G]. */
    public static final double FERNRING = 50_000;

    /** Was ein Ort auf der Bühne ist (Farbe der Marke). */
    public enum Art { SEEZEICHEN, BRUECKE, ORT, STRAND, KLIFF }

    /** Ein benannter Ort mit Lage in UTM 32 N und Höhe des Grunds über NHN (für die Bühne von Phase 1). */
    public record Ort(String name, double e, double n, double grund, Art art) {
        public double x() { return e - E0; }
        public double z() { return -(n - N0); }
    }

    /**
     * Die Orte aus dem Werkbuch (Abschnitte 3 und 9). Seit Phase 3 gemessen: Seebrücken, Wolkenlos, Seehotel, Hafen und
     * Passat als Mitte ihres OSM-Umrisses (Overpass 30.10.), das Maritim ClubHotel und das Leuchtfeuer auf dem Maritim
     * Travemünde als höchster Baukörper aus LOD2, der Hemmelsdorfer See in ALKIS; das Brodtener Ufer bleibt [G]. Die Höhe
     * {@link Ort#grund} gilt nur für die Bühne [G].
     */
    public static final Ort SEEBRUECKE = new Ort("Maritim Seebrücke", E0, N0, 2, Art.BRUECKE),
            SCHLEIFE = new Ort("Schleife der Seebrücke", 617_024, 5_985_159, 0, Art.BRUECKE),
            WOLKENLOS = ort("Wolkenlos", 53.995707, 10.793547, 0, Art.BRUECKE),
            SEEHOTEL = ort("Maritim Seehotel", 54.001000, 10.779948, 4, Art.ORT),
            CLUBHOTEL = new Ort("Maritim ClubHotel", 616_045, 5_985_605, 3, Art.ORT),
            NIENDORF = ort("Niendorfer Hafen", 53.992808, 10.814096, 1, Art.ORT),
            SEEBR_NIENDORF = ort("Seebrücke Niendorf", 53.993320, 10.832187, 0, Art.BRUECKE),
            BRODTEN = ort("Brodtener Ufer", 53.9842, 10.8520, 18, Art.KLIFF),
            HEMMELSDORF = new Ort("Hemmelsdorfer See", 617_700, 5_982_300, 0, Art.ORT),
            SCHARBEUTZ = ort("Seebrücke Scharbeutz", 54.028345, 10.760943, 0, Art.BRUECKE),
            HAFFKRUG = ort("Seebrücke Haffkrug", 54.051677, 10.754251, 0, Art.BRUECKE),
            TRAVEMUENDE = new Ort("Leuchtfeuer Travemünde (Maritim)", 623_409, 5_980_935, 3, Art.SEEZEICHEN),
            PASSAT = ort("Passat (Priwall)", 53.958013, 10.880875, 0, Art.SEEZEICHEN);
    public static final Ort[] ORTE = {SEEBRUECKE, SCHLEIFE, WOLKENLOS, SEEHOTEL, CLUBHOTEL, NIENDORF, SEEBR_NIENDORF, BRODTEN, HEMMELSDORF,
            SCHARBEUTZ, HAFFKRUG, TRAVEMUENDE, PASSAT};

    // ------------------------------------------------------------------ Küste (OSM)

    /** Die Küstenlinie aus {@code kueste_osm.txt} als {Ost, Nord} je Punkt; das Land liegt links in Laufrichtung. */
    public static final double[][] KUESTE = ladeKueste();

    static double[][] ladeKueste() {
        List<double[]> l = new ArrayList<>();
        try (InputStream in = Site.class.getResourceAsStream("kueste_osm.txt")) {
            if (in == null) throw new IllegalStateException("kueste_osm.txt fehlt im Package scene");
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String s; (s = r.readLine()) != null; ) {
                s = s.strip();
                if (s.isEmpty() || s.startsWith("#")) continue;
                String[] p = s.split("\\s+");
                l.add(new double[]{Double.parseDouble(p[0]), Double.parseDouble(p[1])});
            }
        } catch (IOException e) {
            throw new IllegalStateException("kueste_osm.txt: " + e.getMessage(), e);
        }
        return l.toArray(new double[0][]);
    }

    /**
     * Vorzeichenbehafteter Abstand zur Küste in der Ebene (m, UTM): positiv an Land (links der Linie), negativ auf See.
     * Über die Enden hinaus läuft die Linie geradeaus weiter.
     */
    public static double kuestenAbstand(double e, double n) {
        double[][] k = KUESTE;
        double best = Double.MAX_VALUE, sign = 1;
        int last = k.length - 2;
        for (int i = 0; i <= last; i++) {
            double ax = k[i][0], ay = k[i][1], dx = k[i + 1][0] - ax, dy = k[i + 1][1] - ay, l2 = dx * dx + dy * dy;
            double t = ((e - ax) * dx + (n - ay) * dy) / l2;
            int ecke = -1;
            if (i > 0 && t < 0) { t = 0; ecke = i; }
            if (i < last && t > 1) { t = 1; ecke = i + 1; }
            double px = ax + t * dx - e, py = ay + t * dy - n, d = Math.hypot(px, py);
            if (d < best - 1e-9) {
                best = d;
                if (ecke < 0) sign = dx * (n - ay) - dy * (e - ax) >= 0 ? 1 : -1;
                else {
                    // am Knick: Seite nach der Winkelhalbierenden der beiden Normalen (Pseudonormale), sonst kippt das
                    // Vorzeichen hinter spitzen Ecken
                    double[] a = k[ecke - 1], v = k[ecke], b = k[ecke + 1];
                    double l1 = Math.hypot(v[0] - a[0], v[1] - a[1]), lb = Math.hypot(b[0] - v[0], b[1] - v[1]);
                    double nx = -(v[1] - a[1]) / l1 - (b[1] - v[1]) / lb, ny = (v[0] - a[0]) / l1 + (b[0] - v[0]) / lb;
                    sign = (e - v[0]) * nx + (n - v[1]) * ny >= 0 ? 1 : -1;
                }
            }
        }
        return sign * best;
    }

    /** Szenenpunkt (x, z) aus UTM 32 Nord (Ost- und Nordwert in m). */
    public static double[] fromUtm(double east, double north) { return new double[]{east - E0, -(north - N0)}; }

    /** UTM 32 Nord (Ost, Nord) aus einem Szenenpunkt (x, z). */
    public static double[] toUtm(double x, double z) { return new double[]{x + E0, N0 - z}; }

    /** Breite und Länge (Grad) eines Szenenpunkts. */
    public static double[] latLon(double x, double z) { return Geo.UTM32N.inverse(x + E0, N0 - z); }

    /** Szenenpunkt (x, z) aus Breite und Länge (Grad, als {lat, lon}). */
    public static double[] xz(double[] latLon) {
        double[] en = Geo.UTM32N.forward(latLon[0], latLon[1]);
        return fromUtm(en[0], en[1]);
    }

    static Ort ort(String name, double lat, double lon, double grund, Art art) {
        double[] en = Geo.UTM32N.forward(lat, lon);
        return new Ort(name, en[0], en[1], grund, art);
    }

    /** Absenkung der Oberfläche in d m Entfernung durch die Erdkrümmung: d²/2R. */
    public static double curvatureDrop(double d) { return d * d / (2 * EARTH_R); }

    /** Entfernung der Kimm für ein Auge h m über der Ebene, mit normaler Lichtbrechung (m). */
    public static double horizon(double h) { return Math.sqrt(2 * EARTH_R_OPTICAL * Math.max(0, h)); }

    /** „54°00′04″ N · 10°47′00″ E“ */
    public static String dms(double lat, double lon) {
        return dms(lat, lat >= 0 ? "N" : "S") + " · " + dms(lon, lon >= 0 ? "E" : "W");
    }

    private static String dms(double v, String h) {
        v = Math.abs(v);
        int d = (int) v;
        double mm = (v - d) * 60;
        int m = (int) mm;
        int s = (int) Math.round((mm - m) * 60);
        if (s == 60) { s = 0; m++; }
        if (m == 60) { m = 0; d++; }
        return String.format("%d°%02d′%02d″ %s", d, m, s, h);
    }
}
