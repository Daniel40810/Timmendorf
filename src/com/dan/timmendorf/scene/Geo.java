package com.dan.timmendorf.scene;

/**
 * Transversale Mercator-Abbildung auf dem GRS80/WGS84-Ellipsoid nach Krüger (Reihen in der dritten Abplattung n bis
 * n⁴, nach Karney 2011, genau auf Millimeter innerhalb einiger hundert Kilometer vom Mittelmeridian).
 * <p>
 * Für Timmendorf (F2 A): Die Szene liegt in UTM Zone 32 Nord (ETRS89, EPSG:25832), in der auch DGM1, bDOM, DOP20, LOD2
 * und ALKIS vorliegen; nur der Ursprung ist an das Landende der Maritim Seebrücke verschoben ({@code Site}). Der
 * Mittelmeridian 9° O liegt 1,78° westlich; der Maßstab weicht im Ausschnitt um rund 0,4 ‰ von 1 ab
 * (k ≈ 0,9996 + E²/2R²), das zählt für die Szene nicht.
 * ETRS89 und WGS84 trennen rund 0,8 m.
 */
public final class Geo {
    /** GRS80: große Halbachse und Abplattung. */
    public static final double A_AXIS = 6_378_137.0, F = 1 / 298.257222101;
    static final double N = F / (2 - F);
    static final double AR = A_AXIS / (1 + N) * (1 + N * N / 4 + N * N * N * N / 64);
    static final double[] ALPHA = {
            N / 2 - 2 * N * N / 3 + 5 * N * N * N / 16 + 41 * Math.pow(N, 4) / 180,
            13 * N * N / 48 - 3 * N * N * N / 5 + 557 * Math.pow(N, 4) / 1440,
            61 * N * N * N / 240 - 103 * Math.pow(N, 4) / 140,
            49561 * Math.pow(N, 4) / 161280};
    static final double[] BETA = {
            N / 2 - 2 * N * N / 3 + 37 * N * N * N / 96 - Math.pow(N, 4) / 360,
            N * N / 48 + N * N * N / 15 - 437 * Math.pow(N, 4) / 1440,
            17 * N * N * N / 480 - 37 * Math.pow(N, 4) / 840,
            4397 * Math.pow(N, 4) / 161280};
    static final double[] DELTA = {
            2 * N - 2 * N * N / 3 - 2 * N * N * N + 116 * Math.pow(N, 4) / 45,
            7 * N * N / 3 - 8 * N * N * N / 5 - 227 * Math.pow(N, 4) / 45,
            56 * N * N * N / 15 - 136 * Math.pow(N, 4) / 35,
            4279 * Math.pow(N, 4) / 630};
    static final double E = Math.sqrt(F * (2 - F));

    /** Eine Abbildung: Mittelmeridian (Grad), Maßstab, Ost- und Nordwert des Ursprungs. */
    public record Tm(double lon0, double k0, double falseEast, double falseNorth) {
        /** Ost- und Nordwert (m) aus Breite und Länge (Grad). */
        public double[] forward(double latDeg, double lonDeg) {
            double phi = Math.toRadians(latDeg), lam = Math.toRadians(lonDeg - lon0);
            double sp = Math.sin(phi);
            double t = Math.sinh(atanh(sp) - E * atanh(E * sp));
            double xi1 = Math.atan2(t, Math.cos(lam)), eta1 = atanh(Math.sin(lam) / Math.sqrt(1 + t * t));
            double xi = xi1, eta = eta1;
            for (int j = 1; j <= 4; j++) {
                xi += ALPHA[j - 1] * Math.sin(2 * j * xi1) * Math.cosh(2 * j * eta1);
                eta += ALPHA[j - 1] * Math.cos(2 * j * xi1) * Math.sinh(2 * j * eta1);
            }
            return new double[]{falseEast + k0 * AR * eta, falseNorth + k0 * AR * xi};
        }

        /** Breite und Länge (Grad) aus Ost- und Nordwert (m). */
        public double[] inverse(double east, double north) {
            double xi = (north - falseNorth) / (k0 * AR), eta = (east - falseEast) / (k0 * AR);
            double xi1 = xi, eta1 = eta;
            for (int j = 1; j <= 4; j++) {
                xi1 -= BETA[j - 1] * Math.sin(2 * j * xi) * Math.cosh(2 * j * eta);
                eta1 -= BETA[j - 1] * Math.cos(2 * j * xi) * Math.sinh(2 * j * eta);
            }
            double chi = Math.asin(Math.sin(xi1) / Math.cosh(eta1));
            double phi = chi;
            for (int j = 1; j <= 4; j++) phi += DELTA[j - 1] * Math.sin(2 * j * chi);
            double lam = Math.atan2(Math.sinh(eta1), Math.cos(xi1));
            return new double[]{Math.toDegrees(phi), lon0 + Math.toDegrees(lam)};
        }
    }

    /** UTM Zone 32 Nord (ETRS89, EPSG:25832): Mittelmeridian 9° O, Maßstab 0,9996, Ostwert 500 000 m. */
    public static final Tm UTM32N = new Tm(9, 0.9996, 500_000, 0);

    /** Eine Abbildung mit Maßstab 1, deren Ursprung (0, 0) genau bei (lat0, lon0) liegt. */
    public static Tm local(double lat0, double lon0) {
        Tm t = new Tm(lon0, 1, 0, 0);
        return new Tm(lon0, 1, 0, -t.forward(lat0, lon0)[1]);
    }

    static double atanh(double x) { return 0.5 * Math.log((1 + x) / (1 - x)); }

    private Geo() { }
}
