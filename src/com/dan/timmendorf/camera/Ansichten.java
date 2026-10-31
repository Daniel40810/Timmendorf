package com.dan.timmendorf.camera;

import com.dan.timmendorf.scene.Buehne;
import com.dan.timmendorf.scene.Site;

import java.util.List;
import java.util.Locale;

/**
 * Die neun Ansichten nach Namen auf den Tasten 1 bis 9 (Werkbuch, Schaustücke und Vorlagen in {@code templates/}). Seit
 * Phase 1 auf der Bühne, ab Phase 2 auf dem Gelände; die Lagen sind aus den Orten in {@link Site} und der OSM-Küste
 * gesetzt [G], Phase 2 legt sie auf das gemessene Gelände:
 * <ol>
 * <li><b>Seebrücke</b> (Seebruecke1–3): am Landende der Maritim Seebrücke, Blick die Brücke entlang nach Nordost auf die
 * Bucht. Abnahme von Phase 4: „Blick von der Seebrücke“.</li>
 * <li><b>Schleife</b>: auf dem Rundweg am Kopf der Seebrücke, 5 m über dem Wasser [G], Blick zurück auf Strand und
 * Promenade.</li>
 * <li><b>Strandkörbe</b> (Strandkorb1–2): 150 m nordwestlich der Seebrücke im Sand, Blick nach Südost den Strand entlang.</li>
 * <li><b>Spülsaum</b>: an der Wasserlinie, Auge 0,6 m, Blick nach Nordost in die kurze Ostseewelle.</li>
 * <li><b>Promenade</b> (Promenade1–2): 60 m landwärts der Wasserlinie, Blick nach Nordwest die Promenade entlang.</li>
 * <li><b>Niendorfer Hafen</b> (Hafen1–2): am Hafen, Blick nach Nordost auf Mole und Bucht.</li>
 * <li><b>Brodtener Ufer</b> (Brodten1–3): oben an der Kante, Blick nach Nordwest über die Bucht nach Timmendorf.</li>
 * <li><b>Hemmelsdorfer See</b> (HemmelsdorferSee1–3): am Nordufer, Blick nach Süden über den See.</li>
 * <li><b>Vogelschau</b>: 3 km draußen über der Bucht, 600 m hoch, Blick nach Südwesten auf die ganze Küste. Abnahme von
 * Phase 4.</li>
 * </ol>
 */
public final class Ansichten {
    /**
     * Eine Ansicht: Taste, Name, Lage (x, z), Blickrichtung (Grad von Nord), Neigung (Grad nach unten, negativ nach
     * oben), Augenhöhe über Grund (m) und ab welcher Phase sie einen Ort hat.
     */
    public record Ansicht(int key, String name, double x, double z, double heading, double pitch, double eyeAboveGround, int phase) {
        @Override public String toString() { return key + " " + name; }
    }

    /**
     * Ein Punkt s m landwärts der Küste (negativ: seewärts), quer zur Küste am Fußpunkt des Orts (e, n), als
     * Szenenpunkt (x, z). Die Richtung quer zur Küste kommt aus dem Gefälle des Küstenabstands.
     */
    static double[] quer(double e, double n, double s) {
        double d = Site.kuestenAbstand(e, n), h = 5;
        double ge = (Site.kuestenAbstand(e + h, n) - Site.kuestenAbstand(e - h, n)) / (2 * h);
        double gn = (Site.kuestenAbstand(e, n + h) - Site.kuestenAbstand(e, n - h)) / (2 * h);
        double l = Math.hypot(ge, gn);
        if (l < 1e-9) return Site.fromUtm(e, n);
        ge /= l;
        gn /= l;
        return Site.fromUtm(e + (s - d) * ge, n + (s - d) * gn);
    }

    static double[] quer(Site.Ort o, double s) { return quer(o.e(), o.n(), s); }

    private static Ansicht at(int key, String name, double[] xz, double heading, double pitch, double above) {
        return new Ansicht(key, name, xz[0], xz[1], heading, pitch, above, 1);
    }

    public static final List<Ansicht> ALLE = List.of(
            at(1, "Seebrücke", quer(Site.SEEBRUECKE, 4), 50, 3, 1.7),
            at(2, "Schleife", new double[]{Site.SCHLEIFE.x(), Site.SCHLEIFE.z()}, 230, 4, 5.2),
            at(3, "Strandkörbe", quer(Site.E0 - 110, Site.N0 + 110, 25), 135, 3, 1.7),
            at(4, "Spülsaum", quer(Site.E0 - 60, Site.N0 + 40, 1), 40, 1, 0.6),
            at(5, "Promenade", quer(Site.E0 + 60, Site.N0 - 70, 60), 318, 2, 1.7),
            at(6, "Niendorfer Hafen", quer(Site.NIENDORF, 25), 35, 2, 1.7),
            at(7, "Brodtener Ufer", quer(Site.BRODTEN, 30), 300, 6, 1.7),
            at(8, "Hemmelsdorfer See", Site.fromUtm(617_217, 5_984_390), 185, 2, 1.7),
            at(9, "Vogelschau", quer(Site.SEEBRUECKE, -3000), 225, 11, 600));

    /** Taste der Ansicht „Seebrücke“ (Grundansicht, Abnahme von Phase 1 und 4). */
    public static final int SEEBRUECKE = 1;

    /** Ob die Ansicht schon einen Ort hat. */
    public static boolean ready(Ansicht a, int phase) { return a.phase() <= phase; }

    public static Ansicht byName(String name) {
        for (Ansicht a : ALLE) if (a.name().equalsIgnoreCase(name) || a.name().replace(" ", "").equalsIgnoreCase(name)) return a;
        // ohne Sonderzeichen (Umgebungsvariablen unter Windows): „Spuelsaum“, „Seebruecke“ …
        String n = name.replace(" ", "").replace("-", "").toLowerCase(Locale.ROOT);
        for (Ansicht a : ALLE) {
            String k = a.name().replace(" ", "").replace("-", "").toLowerCase(Locale.ROOT).replace("ü", "ue").replace("ö", "oe").replace("ä", "ae").replace("ß", "ss");
            if (k.equals(n)) return a;
        }
        try { return byKey(Integer.parseInt(name.trim())); } catch (NumberFormatException e) { return null; }
    }

    public static Ansicht byKey(int key) {
        for (Ansicht a : ALLE) if (a.key() == key) return a;
        return null;
    }

    /** Stellt die Kamera auf die Ansicht über dem Grund {@code ground}; false, wenn sie in dieser Phase noch keinen Ort hat. */
    public static boolean apply(Ansicht a, Camera c, int phase, java.util.function.DoubleBinaryOperator ground) {
        if (!ready(a, phase)) return false;
        c.stopFahrt();
        c.stopMitfahrt();
        c.diving = false;
        double g = Math.max(ground.applyAsDouble(a.x(), a.z()), c.waterLevel);
        c.groundLevel = ground.applyAsDouble(a.x(), a.z());
        c.place(a.x(), g + a.eyeAboveGround(), a.z(), a.heading(), a.pitch());
        return true;
    }

    /** Wie oben, über der Bühne von Phase 1. */
    public static boolean apply(Ansicht a, Camera c, int phase) { return apply(a, c, phase, Buehne::height); }

    /** „1 Seebrücke (Auge 1,7 m über Grund, Blick 050°)“ */
    public static String describe(Ansicht a, int phase) {
        if (!ready(a, phase)) return a + " (folgt in Phase " + a.phase() + ")";
        return String.format(Locale.GERMAN, "%s (Auge %.1f m über Grund, Blick %03.0f°)", a, a.eyeAboveGround(), a.heading());
    }

    private Ansichten() { }
}
