package com.dan.timmendorf.tools;

import com.dan.timmendorf.scene.Geo;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Das GEBCO-Gitter aus der ZIP des Downloads (Phase 2): {@code gebco_2026_nN_sS_wW_eE_geotiff.tif}, 15″ (rund 270 × 460 m),
 * int16, Höhen über dem Meeresspiegel (an Land aus SRTM, in der Bucht bis −22 m). Die Grenzen stehen im Dateinamen und
 * in ModelTiepoint; Werte als kubischer B-Spline über die Zellmitten (glatt,
 * ohne die Kanten der 15″-Zellen, die bilinear im Schummerbild zu sehen waren), NaN außerhalb; {@link #randAbstand} sagt,
 * wie weit ein Punkt innerhalb liegt, damit die Werkzeuge am Rand des Downloads weich übergehen. Quelle: GEBCO Compilation Group (2026)
 * GEBCO 2026 Grid.
 */
public final class Gebco {
    static final Pattern NAME = Pattern.compile("gebco_\\d{4}_n([-\\d.]+)_s([-\\d.]+)_w([-\\d.]+)_e([-\\d.]+)_geotiff\\.tif", Pattern.CASE_INSENSITIVE);

    public final float[] h;
    public final int w, hgt;
    public final double nord, sued, west, ost, zelle;
    public final String datei;

    private Gebco(float[] h, int w, int hgt, double nord, double west, double zelle, String datei) {
        this.h = h; this.w = w; this.hgt = hgt; this.nord = nord; this.west = west; this.zelle = zelle;
        this.sued = nord - hgt * zelle;
        this.ost = west + w * zelle;
        this.datei = datei;
    }

    /** Liest das GeoTIFF aus der ZIP (über eine Zwischendatei, GeoTiff braucht wahlfreien Zugriff). */
    public static Gebco laden(File zip) throws IOException {
        try (ZipFile z = new ZipFile(zip)) {
            for (ZipEntry e : java.util.Collections.list(z.entries())) {
                Matcher m = NAME.matcher(new File(e.getName()).getName());
                if (!m.matches()) continue;
                File tmp = File.createTempFile("gebco", ".tif");
                tmp.deleteOnExit();
                try (InputStream in = z.getInputStream(e)) { Files.copy(in, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING); }
                try (GeoTiff g = GeoTiff.open(tmp)) {
                    float[] v = g.floats(0, 0, g.width, g.height);
                    for (int i = 0; i < v.length; i++) if (v[i] <= -32000) v[i] = Float.NaN;
                    return new Gebco(v, g.width, g.height, g.north0, g.east0, g.sizeX, e.getName());
                } finally {
                    Files.deleteIfExists(tmp.toPath());
                }
            }
        }
        throw new IOException("GEBCO: kein gebco_…_geotiff.tif in " + zip);
    }

    /** Höhe (m) an Breite und Länge, kubischer B-Spline über 4 × 4 Zellmitten (Ränder fortgesetzt); NaN außerhalb. */
    public double at(double lat, double lon) {
        double u = (lon - west) / zelle - 0.5, v = (nord - lat) / zelle - 0.5;
        if (u < -0.5 || v < -0.5 || u > w - 0.5 || v > hgt - 0.5) return Double.NaN;
        int i = (int) Math.floor(u), j = (int) Math.floor(v);
        double[] wx = bspline(u - i), wy = bspline(v - j);
        double s = 0, ws = 0;
        for (int b = 0; b < 4; b++)
            for (int a = 0; a < 4; a++) {
                int x = Math.max(0, Math.min(w - 1, i - 1 + a)), y = Math.max(0, Math.min(hgt - 1, j - 1 + b));
                float q = h[y * w + x];
                if (Float.isNaN(q)) continue;
                double k = wx[a] * wy[b];
                s += q * k;
                ws += k;
            }
        return ws > 0 ? s / ws : Double.NaN;
    }

    static double[] bspline(double t) {
        double t2 = t * t, t3 = t2 * t;
        return new double[]{(1 - t) * (1 - t) * (1 - t) / 6, (3 * t3 - 6 * t2 + 4) / 6, (-3 * t3 + 3 * t2 + 3 * t + 1) / 6, t3 / 6};
    }

    /** Wie weit (m, grob) ein Punkt UTM 32 N innerhalb des Downloads liegt; negativ außerhalb. */
    public double randAbstand(double e, double n) {
        double[] ll = Geo.UTM32N.inverse(e, n);
        double mLat = 111_320, mLon = 111_320 * Math.cos(Math.toRadians(ll[0]));
        return Math.min(Math.min((ll[1] - west) * mLon, (ost - ll[1]) * mLon), Math.min((nord - ll[0]) * mLat, (ll[0] - sued) * mLat));
    }

    /** Ob der Download den Kreis um (e, n) mit Halbmesser r (m) ganz deckt. */
    public boolean deckt(double e, double n, double r) {
        for (int k = 0; k < 16; k++) {
            double a = k * Math.PI / 8;
            if (randAbstand(e + r * Math.cos(a), n + r * Math.sin(a)) < 0) return false;
        }
        return true;
    }

    /** Höhe (m) an Ost- und Nordwert UTM 32 N. */
    public double utm(double e, double n) {
        double[] ll = Geo.UTM32N.inverse(e, n);
        return at(ll[0], ll[1]);
    }

    /** Kleinste und größte Höhe. */
    public double[] spanne() {
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (float f : h) if (!Float.isNaN(f)) { lo = Math.min(lo, f); hi = Math.max(hi, f); }
        return new double[]{lo, hi};
    }

    @Override public String toString() {
        double[] s = spanne();
        return Raster.f("GEBCO %s: %d × %d Zellen zu %.0f″, %.2f–%.2f° N, %.2f–%.2f° O, %.0f bis %.0f m", datei, w, hgt, zelle * 3600, sued, nord, west, ost, s[0], s[1]);
    }
}
