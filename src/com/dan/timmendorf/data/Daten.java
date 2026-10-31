package com.dan.timmendorf.data;

import com.dan.timmendorf.scene.SceneClock;
import com.dan.timmendorf.scene.Site;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Die Datensätze, die {@link Speicher} liest und schreibt (Phase 17, Werkbuch Abschnitt 12, F37–F49 „alle A“). Je
 * Tabelle ein Record mit den Spalten, die die App braucht; Schlüssel, ANGELEGT und GEAENDERT führt die Datenbank selbst.
 * Lagen stehen in Szenenmetern (x nach Osten, z nach Süden, Ursprung am Landende der Maritim Seebrücke); in Tabellen und
 * Dateien in UTM 32N (SRID 25832): Ost = x + 616 826, Nord = 5 985 081 − z.
 * <p>
 * Szenenzeit: Millisekunden seit 1970 wie die Uhr der Szene, dazu als TIMESTAMP WITH TIME ZONE in Europe/Berlin (in
 * der Datenbank) und als Text („01.08.2026 22:45 MESZ“).
 * <p>
 * Nicht zu verwechseln mit {@code com.dan.timmendorf.scene.Daten} (Ordner und Zustand der aufbereiteten Dateien).
 */
public final class Daten {
    private Daten() { }

    /** HERKUNFT: mitgeliefert (stellt der Einrichter wieder her) oder eigen (fasst er nie an). */
    public static final String MITGELIEFERT = "MITGELIEFERT", EIGEN = "EIGEN";

    private static final DateTimeFormatter ZEIT = DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm", Locale.GERMANY);

    /** {Ost, Nord} eines Szenenpunkts. */
    public static double[] utm(double x, double z) { return Site.toUtm(x, z); }

    /** Szenenpunkt {x, z} aus Ost und Nord. */
    public static double[] xz(double ost, double nord) { return Site.fromUtm(ost, nord); }

    public static ZonedDateTime zeit(double epochMs) { return Instant.ofEpochMilli((long) Math.floor(epochMs)).atZone(SceneClock.ZONE); }

    /** „01.08.2026 22:45 MESZ“ */
    public static String text(double epochMs) {
        ZonedDateTime z = zeit(epochMs);
        return ZEIT.format(z) + " " + (SceneClock.ZONE.getRules().isDaylightSavings(z.toInstant()) ? "MESZ" : "MEZ");
    }

    /** Eine Zahl mit höchstens zwei Stellen nach dem Punkt (Meter in UTM: Zentimeter). */
    static String n2(double v) {
        String s = String.format(Locale.ROOT, "%.2f", v);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    /** Eine Geometrie in Szenenmetern: PUNKT, LINIE oder FLAECHE (ein Ring außen). Als WKT in UTM 32N mit Zentimetern. */
    public record Geom(String art, double[][] p) {
        public static Geom punkt(double x, double z) { return new Geom("PUNKT", new double[][]{{x, z}}); }
        public static Geom linie(double[][] p) { return new Geom("LINIE", p); }
        public static Geom flaeche(double[][] p) { return new Geom("FLAECHE", p); }

        private static String folge(double[][] p, boolean zu) {
            StringBuilder b = new StringBuilder("(");
            int n = zu ? p.length + 1 : p.length;
            for (int i = 0; i < n; i++) {
                double[] u = utm(p[i % p.length][0], p[i % p.length][1]);
                b.append(i > 0 ? ", " : "").append(n2(u[0])).append(' ').append(n2(u[1]));
            }
            return b.append(')').toString();
        }

        /** Als WKT in UTM 32N (Fläche geschlossen und außen gegen den Uhrzeigersinn in Ost/Nord, wie Oracle es will). */
        public String wkt() {
            return switch (art) {
                case "PUNKT" -> { double[] u = utm(p[0][0], p[0][1]); yield "POINT (" + n2(u[0]) + " " + n2(u[1]) + ")"; }
                case "LINIE" -> "LINESTRING " + folge(p, false);
                default -> {
                    double[][] q = p;
                    // in Ost/Nord ist die Drehung wegen z = −Nord gespiegelt: gegen den Uhrzeigersinn heißt hier Fläche < 0
                    double a = 0;
                    for (int i = 0; i < q.length; i++) { double[] u = q[i], v = q[(i + 1) % q.length]; a += u[0] * v[1] - v[0] * u[1]; }
                    if (a > 0) {
                        double[][] r = new double[q.length][];
                        for (int i = 0; i < q.length; i++) r[i] = q[q.length - 1 - i];
                        q = r;
                    }
                    yield "POLYGON (" + folge(q, true) + ")";
                }
            };
        }

        /** Aus WKT in UTM (wie {@code SDO_UTIL.TO_WKTGEOMETRY} es liefert) zurück in Szenenmeter. */
        public static Geom ausWkt(String wkt) {
            if (wkt == null || wkt.isBlank()) return null;
            String w = wkt.strip().toUpperCase(Locale.ROOT);
            String art = w.startsWith("POINT") ? "PUNKT" : w.startsWith("POLYGON") ? "FLAECHE" : "LINIE";
            int a = w.lastIndexOf('(') , e = w.indexOf(')');
            if (art.equals("FLAECHE")) { a = w.indexOf("((") + 1; e = w.indexOf(')', a); }
            if (a < 0 || e < a) return null;
            List<double[]> pts = new ArrayList<>();
            for (String s : w.substring(a + 1, e).split(",")) {
                String[] v = s.strip().split("\\s+");
                if (v.length >= 2) pts.add(xz(Double.parseDouble(v[0]), Double.parseDouble(v[1])));
            }
            if (art.equals("FLAECHE") && pts.size() > 3) pts.remove(pts.size() - 1);
            return pts.isEmpty() ? null : new Geom(art, pts.toArray(new double[0][]));
        }

        public double laenge() {
            double s = 0;
            for (int i = 0; i + 1 < p.length; i++) s += Math.hypot(p[i + 1][0] - p[i][0], p[i + 1][1] - p[i][1]);
            return s;
        }

        public double flaecheM2() {
            double a = 0;
            for (int i = 0; i < p.length; i++) { double[] u = p[i], v = p[(i + 1) % p.length]; a += u[0] * v[1] - v[0] * u[1]; }
            return Math.abs(a) / 2;
        }
    }

    // ================================================================== Quellen und Befunde

    /** Ein Befund (TD_BEFUND) mit den Namen seiner Belege (TD_QUELLE.NAME). */
    public record Befund(int nr, String phase, String thema, String text, String art, String status, String behobenIn, String commit, List<String> belege) {
        public Befund { belege = List.copyOf(belege); }
        @Override public String toString() { return nr + " · " + (phase == null ? "" : "Phase " + phase + " · ") + thema + ": " + text; }
    }

    // ================================================================== Regie

    /** Ein Regiebuch als ganzer Text (Format von {@code scene.Regie.parse}). */
    public record Regiebuch(String name, String text, String titel, String datei, String herkunft) {
        @Override public String toString() { return name + (EIGEN.equals(herkunft) ? " (eigen)" : ""); }
    }

    /** Ein Lauf eines Regiebuchs (TD_REGIELAUF). */
    public record Regielauf(String name, long beginnMs, Long endeMs, boolean vollstaendig, boolean kino, Integer schritte, double gewartetS, int ausgewichen,
                            int fotos, double bilderS, double gpuMs, String qualitaet, String blatt) { }

    /**
     * Eine Ansicht (TD_ANSICHT): FEST (Tasten 1–9), BLICK (Regie) oder GEMERKT (Taste M) mit Uhr, Lage, Zustand und
     * Vorschau (PNG oder null). kamera = {x, y, z, Gier, Neigung}; y NaN heißt: aus dem Gelände (augeUeberGrund).
     */
    public record Ansicht(long id, String name, Integer taste, String art, double[] kamera, Double augeUeberGrund, Double fov, boolean mitUhr, Long szeneMs,
                          String lage, String zustand, byte[] vorschau, String herkunft) {
        @Override public String toString() { return (taste != null && taste > 0 ? taste + " " : "") + name + (mitUhr && szeneMs != null ? " · " + text(szeneMs) : ""); }
    }

    /** Ein Stützpunkt einer Fahrt (TD_STUETZPUNKT); Szenenmeter und Grad. */
    public record Stuetzpunkt(int nr, double t, double x, double y, double z, double gier, double neigung) { }

    /** Eine Kamerafahrt (TD_FAHRT). */
    public record Fahrt(String name, String art, Double dauerS, Double laengeM, String beschreibung, String herkunft, Geom weg, List<Stuetzpunkt> punkte) {
        public Fahrt { punkte = List.copyOf(punkte); }
        @Override public String toString() { return name; }
    }

    /** Ein benanntes Preset eines Package (TD_PAKET_PRESET mit TD_PAKET_WERT). */
    public record PaketPreset(String paket, String klasse, String name, String beschreibung, String herkunft, Map<String, String> felder) {
        public PaketPreset { felder = new LinkedHashMap<>(felder); }
        @Override public String toString() { return name + (EIGEN.equals(herkunft) ? " (eigen)" : ""); }
    }

    // ================================================================== Fachdaten

    /** Ein Punkt einer Polare (TD_POLARE). */
    public record Polarpunkt(String art, double twsKn, double twa, double fahrtKn, double kraengung) { }

    /** Eine Bootsklasse (TD_BOOT_KLASSE) mit ihren Polaren. */
    public record BootKlasse(String name, String art, String rigg, double luea, double lwl, double breite, double masse, double segel, double vorwind, double mast,
                             int crew, String quelle, List<Polarpunkt> polare) {
        public BootKlasse { polare = List.copyOf(polare); }
        @Override public String toString() { return name; }
    }

    /** Ein Boot der Szene (TD_BOOT). */
    public record Boot(String name, String klasse, String segelnummer, String rolle, String farbe) { }

    /** Eine Tonne oder das Startschiff (TD_REGATTA_TONNE). */
    public record Tonne(int nr, String rolle, double x, double z) { }

    /** Eine Regatta (TD_REGATTA) mit Bahn und Tonnen. */
    public record Regatta(String name, LocalDate datum, String klasse, double richtung, double laenge, double linie, int runden, List<Tonne> tonnen) {
        public Regatta { tonnen = List.copyOf(tonnen); }
    }

    /** Ein Ergebnis eines Laufs (TD_REGATTA_ERGEBNIS). */
    public record Ergebnis(String regatta, int lauf, String segelnummer, int platz, double zeitS, int wenden, int halsen, boolean fruehstart, double wind,
                           double windAus, long szeneMs) {
        public String text() { return String.format(Locale.GERMAN, "%s · Lauf %d · %d. Nr. %s in %s%s", Daten.text(szeneMs), lauf, platz, segelnummer, dauer(zeitS), fruehstart ? " (Frühstart)" : ""); }
        static String dauer(double s) { return Double.isNaN(s) ? "–" : String.format(Locale.GERMAN, "%d:%02d", (int) (s / 60), (int) (s % 60)); }
    }

    /** Ein Effekt des Feuerwerks (TD_EFFEKT). */
    public record Effekt(String name, String bild, String salz, String farbe, Double nm, int kaliber, double halbmesser, double brenndauer) { }

    /** Ein Schuss eines Zündplans (TD_ZUENDPLAN_SCHRITT). */
    public record Schuss(int nr, double t, String effekt, int kaliber, double neigung, double azimut) { }

    /** Ein Zündplan (TD_ZUENDPLAN) mit dem Ponton und den Schüssen; text im Format von com.dan.firework. */
    public record Zuendplan(String name, LocalDate datum, String beginn, double dauerS, String text, double x, double z, String herkunft, List<Schuss> schuesse) {
        public Zuendplan { schuesse = List.copyOf(schuesse); }
        @Override public String toString() { return name; }
    }

    /** Eine Stunde im Fahrplan einer Sturmflut (TD_STURMFLUT_PEGEL). */
    public record Pegel(double stunde, long ms, double pegel, double wind, double windAus, Double hs) { }

    /** Eine belegte Sturmflut (TD_STURMFLUT) mit Fahrplan. */
    public record Sturmflut(String name, long beginnMs, long scheitelMs, double scheitelM, String klasse, String quelle, String bemerkung, List<Pegel> pegel) {
        public Sturmflut { pegel = List.copyOf(pegel); }
        @Override public String toString() { return name; }
    }

    /** Ein Stück Oberkante (TD_KLIFFKANTE): aus dem DGM1 oder nach einem Abbruch. */
    public record Kante(String stand, String art, Integer abbruchNr, double laenge, Geom linie) { }

    /** Ein Abbruch am Brodtener Ufer (TD_ABBRUCH) mit der abgebrochenen Fläche und der Kante danach. */
    public record Abbruch(int nr, double stunde, long szeneMs, double x, double z, double laenge, double rueck, double volumen, Geom flaeche, Kante kante) {
        public String text() { return String.format(Locale.GERMAN, "%s · Abbruch %d: %.0f m Kante, %.1f m zurück", Daten.text(szeneMs), nr, laenge, rueck); }
    }

    /** Ein Sektor (TD_SEKTOR). */
    public record Sektor(int nr, double von, double bis, String farbe, Double tragweite) { }

    /** Ein Leuchtfeuer (TD_LEUCHTFEUER) mit Sektoren. */
    public record Leuchtfeuer(String name, String kennung, double feuerhoehe, Double tragweite, boolean heuteAn, double x, double z, String quelle, List<Sektor> sektoren) {
        public Leuchtfeuer { sektoren = List.copyOf(sektoren); }
    }

    /** Eine Abfahrt (TD_ABFAHRT). */
    public record Abfahrt(String haltestelle, Long knoten, String tage, String zeit, String richtung) { }

    /** Eine Buslinie (TD_LINIE) mit Abfahrten. */
    public record Linie(String nr, String name, String betreiber, int takt, List<Abfahrt> abfahrten) {
        public Linie { abfahrten = List.copyOf(abfahrten); }
    }

    // ================================================================== Betrieb

    /** Eine Sitzung für „Weiter wie zuletzt“ (mit Zustand und Vorschau). */
    public record Sitzung(long id, long beginnMs, Long endeMs, boolean sauber, String zustand, Long zustandMs, String regiebuch, Double regieSekunde, Long szeneMs,
                          String lage, Double pegelM, byte[] vorschau) {
        public String szeneText() { return szeneMs == null ? "" : Daten.text(szeneMs); }
    }

    /** Was das Startbild und der Block DATEN über den Stand zeigen. */
    public record Stand(int befunde, int regiebuecher, int regielaeufe, int ansichten, int gemerkt, int fahrten, int presets, int eigenePresets, int klassen,
                        int boote, int ergebnisse, int zuendplaene, int sturmfluten, int abbrueche, int kanten, int leuchtfeuer, int linien, int sitzungen, Sitzung letzte) {
        public String kurz() {
            return String.format(Locale.GERMAN, "%d Regiebücher (%d Läufe), %d Ansichten (%d gemerkt), %d Presets (%d eigen), %d Bootsklassen, %d Ergebnisse, "
                    + "%d Zündpläne, %d Sturmfluten, %d Abbrüche, %d Sitzungen", regiebuecher, regielaeufe, ansichten, gemerkt, presets, eigenePresets, klassen, ergebnisse,
                    zuendplaene, sturmfluten, abbrueche, sitzungen);
        }
    }
}
