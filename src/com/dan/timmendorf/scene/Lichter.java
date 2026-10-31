package com.dan.timmendorf.scene;

import com.dan.firework.Firework;
import com.dan.lamp.Lamp;
import com.dan.lamp.Lamps;
import com.dan.lamp.Windows;

import java.util.Locale;
import java.util.Set;
import java.util.function.DoubleBinaryOperator;

/**
 * Die Lichter der Stadt (seit Phase 12, F18 A, mit {@code com.dan.lamp}): die Laternen der Seebrücken und der Promenade
 * (aus {@link Bauwerke}, wo sie gebaut werden) und die Straßenleuchten längs des Straßennetzs {@code TD_NET_STRASSE}
 * zwischen Scharbeutz und Travemünde, alle als LED in Neutralweiß (4 000 K; nach Daniel weiß, nicht orange) [G für Lichtstrom
 * und Abstände]. Dämmerungsschalter wie in {@code lamp} (an unter 35 lx). Dazu der Anteil erleuchteter Fenster nach der
 * Uhrzeit ({@link Windows}, Ferienwohnungen und Wohnhäuser gemischt) und je Bild das Licht der Bomben eines Feuerwerks
 * (eine Leuchte je Bombe, {@link Lamp#point}).
 */
public final class Lichter {
    /** Laternen und Straßenleuchten (fest). */
    public final Lamps fest = new Lamps();
    public int laternen, strassen;

    /** Laterne (Seebrücke, Promenade): rundum, 2 500 lm, Glaskugel etwa 0,1 m² [G]. */
    public static final double LATERNE_LM = 2500;
    /** Straßenleuchte: 6 m, voll abgeschirmt, 5 000 lm, alle 32 m wechselseitig [G nach DIN EN 13201, Wohnstraße]. */
    public static final double STRASSE_LM = 5000, STRASSE_H = 6, STRASSE_ABSTAND = 32;
    static final Set<String> BELEUCHTET = Set.of("primary", "secondary", "tertiary", "residential", "unclassified", "living_street",
            "primary_link", "secondary_link", "tertiary_link", "service");

    /** Laternen aus dem Bau der Bauwerke (Kopf auf y). */
    public void laternen(java.util.List<double[]> orte) {
        for (double[] p : orte) {
            Lamp l = fest.add(new Lamp(p[0], p[1], p[2], Lamp.Type.LED_4000K, Lamp.Distribution.ROUND, LATERNE_LM));
            l.area = 0.1;
            laternen++;
        }
    }

    /**
     * Straßenleuchten längs der Straßen (OSM-Arten in {@link #BELEUCHTET}, ohne Autobahn und Feldwege) bis 1,5 km hinter der
     * Küste, zwischen Scharbeutz und Travemünde; abwechselnd rechts und links 4 m neben der Mitte.
     */
    public void strassen(Netz n, DoubleBinaryOperator grund) {
        if (n == null) return;
        for (Netz.Kante k : n.kanten) {
            if (!BELEUCHTET.contains(k.art()) || k.bruecke()) continue;
            if ("service".equals(k.art()) && !"yes".equals(k.licht())) continue;
            double[] pt = k.punkte();
            int np = pt.length / 3;
            if (np < 2) continue;
            double[] mid = Site.fromUtm(pt[(np / 2) * 3], pt[(np / 2) * 3 + 1]);
            if (mid[0] < -3500 || mid[0] > 7500) continue;
            double d = Site.kuestenAbstand(pt[(np / 2) * 3], pt[(np / 2) * 3 + 1]);
            if (d < 0 || d > 1500) continue;
            double lauf = 0, naechste = STRASSE_ABSTAND / 2;
            int seite = (k.id() & 1) == 0 ? 1 : -1;
            for (int i = 0; i + 1 < np; i++) {
                double[] p0 = Site.fromUtm(pt[i * 3], pt[i * 3 + 1]), p1 = Site.fromUtm(pt[i * 3 + 3], pt[i * 3 + 4]);
                double l = Math.hypot(p1[0] - p0[0], p1[1] - p0[1]);
                if (l < 1e-3) continue;
                double dx = (p1[0] - p0[0]) / l, dz = (p1[1] - p0[1]) / l;
                while (lauf + l > naechste) {
                    double u = naechste - lauf, rx = -dz * seite, rz = dx * seite;   // zur Seite des Masts
                    double x = p0[0] + dx * u + rx * 4, z = p0[1] + dz * u + rz * 4;
                    Lamp lp = fest.add(new Lamp(x, grund.applyAsDouble(x, z) + STRASSE_H, z, Lamp.Type.LED_4000K, Lamp.Distribution.STREET, STRASSE_LM));
                    lp.facing = Math.toDegrees(Math.atan2(-rx, rz));          // zur Straße (gegen die Seite des Masts)
                    lp.area = 0.06;
                    strassen++;
                    seite = -seite;
                    naechste += STRASSE_ABSTAND;
                }
                lauf += l;
            }
        }
    }

    /** Schalten (Umgebungslicht lx, Uhrzeit h). */
    public void step(double dt, double lux, double stunde) { fest.step(dt, lux, stunde); }

    /** Brennen die Leuchten (Dämmerungsschalter)? */
    public boolean an() {
        for (Lamp l : fest.all()) return l.output() > 0;
        return false;
    }

    /** Anteil erleuchteter Fenster um diese Stunde: halb Ferienwohnungen, halb Wohnhäuser (Timmendorf im Sommer) [G]. */
    public static double fenster(double stunde) {
        return 0.5 * Windows.share(Windows.Use.HOLIDAY, stunde) + 0.5 * Windows.share(Windows.Use.HOME, stunde);
    }

    /** Alle Lichter dieses Bilds: die festen und je Bombe eines Feuerwerks ein Punktlicht. */
    public Lamps bild(Firework f, Lamps.Quality q) {
        Lamps b = new Lamps();
        b.quality = q;
        b.all().addAll(fest.all());
        if (f != null) for (Firework.Light l : f.lights()) if (l.cd() > 1) b.all().add(Lamp.point(l.x(), l.y(), l.z(), l.cd(), l.r(), l.g(), l.b()));
        return b;
    }

    public String describe() {
        return String.format(Locale.GERMAN, "Lichter: %d Laternen, %,d Straßenleuchten (LED 4 000 K) · %s", laternen, strassen, an() ? "an" : "aus");
    }
}
