package com.dan.timmendorf.data;

import com.dan.firework.Firework;
import com.dan.sail.Regatta;
import com.dan.sail.Sail;
import com.dan.store.Felder;
import com.dan.timmendorf.camera.Ansichten;
import com.dan.timmendorf.scene.Flotte;
import com.dan.timmendorf.scene.Regie;
import com.dan.timmendorf.scene.Site;
import com.dan.timmendorf.scene.Sturmflut;
import com.dan.timmendorf.scene.Terrain;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Die mitgelieferten Daten (Phase 17, Werkbuch Abschnitt 12, F37–F49 „alle A“): Befunde aus {@code befunde.txt} in
 * diesem Package, die fünf Regiebücher, die neun Ansichten und die Blicke der Regie, die Fahrten, die Presets der
 * Packages, Bootsklassen mit gerechneten Polaren, die Boote der Szene, die Regatta vor Timmendorf mit Bahn, die Effekte
 * und Zündpläne des Feuerwerks, die Sturmflut vom Oktober 2023 mit Fahrplan, die Oberkante des Brodtener Ufers aus dem
 * DGM1 (nur mit {@code daten/gelaende}), die Leuchtfeuer und die Buslinien [G] – alles aus dem Code. Daraus schreibt
 * {@link StartdatenSql} das Skript {@code db/sql/04_startdaten.sql}; ohne Datenbank liest {@link DateiSpeicher} dieselben
 * Listen.
 */
public final class Startdaten {
    private Startdaten() { }

    /** Der Tag der Szene, an dem Regatta und Feuerwerk spielen. */
    public static final LocalDate TAG = LocalDate.of(2026, 8, 1);
    public static final String REGATTA = "Regatta vor Timmendorf", FEUERWERK = "Großes Feuerwerk", FEUERWERK_KLEIN = "Kleines Feuerwerk",
            STURMFLUT = "Sturmflut Oktober 2023";

    // ------------------------------------------------------------------ Befunde

    static List<String[]> zeilen(String datei) {
        try (InputStream in = Startdaten.class.getResourceAsStream(datei)) {
            if (in == null) throw new IllegalStateException(datei + " fehlt im Package com.dan.timmendorf.data");
            List<String[]> l = new ArrayList<>();
            for (String z : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                if (z.isBlank() || z.startsWith("#")) continue;
                String[] p = z.split("\\|", -1);
                for (int i = 0; i < p.length; i++) p[i] = p[i].strip();
                l.add(p);
            }
            return l;
        } catch (IOException e) {
            throw new IllegalStateException(datei + " nicht lesbar: " + e.getMessage(), e);
        }
    }

    static String leer(String s) { return s == null || s.isEmpty() ? null : s; }

    private static String feld(String[] p, int i) { return i < p.length ? p[i] : ""; }

    public static List<Daten.Befund> befunde() {
        List<Daten.Befund> l = new ArrayList<>();
        for (String[] p : zeilen("befunde.txt")) {
            List<String> q = new ArrayList<>();
            for (String x : feld(p, 8).split(",")) if (!x.isBlank()) q.add(x.strip());
            l.add(new Daten.Befund(Integer.parseInt(p[0]), leer(p[1]), p[2], p[7], p[3], p[4], leer(p[5]), leer(p[6]), q));
        }
        return l;
    }

    // ------------------------------------------------------------------ Regie

    public static List<Daten.Regiebuch> regiebuecher() {
        List<Daten.Regiebuch> l = new ArrayList<>();
        for (String d : Regie.DATEIEN) {
            String t = Regie.text(d);
            Regie.Book b = Regie.parse(t);
            l.add(new Daten.Regiebuch(b.name(), t, b.about(), d, Daten.MITGELIEFERT));
        }
        return l;
    }

    /** Die Blicke, die {@code gl.Regisseur.placeNamed} kennt (ohne festen Ort: sie rechnet die Szene). */
    public static final String[] BLICKE = {"Sonnenaufgang", "Mondaufgang", "Feuerwerk", "Startschiff", "Quallen", "Sturmflut Strand", "Sturmflut Promenade"};

    /** Die neun festen Ansichten (Tasten 1–9) und die Blicke der Regie. Kamera: x, Augenhöhe NaN (aus dem Gelände), z, Blick, Neigung. */
    public static List<Daten.Ansicht> ansichten() {
        List<Daten.Ansicht> l = new ArrayList<>();
        for (Ansichten.Ansicht a : Ansichten.ALLE)
            l.add(new Daten.Ansicht(0, a.name(), a.key(), "FEST", new double[]{a.x(), Double.NaN, a.z(), a.heading(), a.pitch()}, a.eyeAboveGround(), 55.0, false,
                    null, null, null, null, Daten.MITGELIEFERT));
        for (String b : BLICKE) {
            double[] k = blickOrt(b);
            l.add(new Daten.Ansicht(0, b, null, "BLICK", k, null, 55.0, false, null, null, null, null, Daten.MITGELIEFERT));
        }
        return l;
    }

    /** Ort eines Blicks, soweit er ohne Szene feststeht (sonst NaN). */
    static double[] blickOrt(String b) {
        double sx = Site.SCHLEIFE.x(), sz = Site.SCHLEIFE.z();
        return switch (b) {
            case "Quallen" -> {
                double l = Math.hypot(sx, sz), ux = sx / l, uz = sz / l, nx = uz, nz = -ux;   // quer zur Brücke nach Norden (wie Regisseur)
                double dx = nx * 5 + ux * 3, dz = nz * 5 + uz * 3, gier = (Math.toDegrees(Math.atan2(dx, -dz)) + 360) % 360;
                yield new double[]{sx * 0.45 + nx * 2, com.dan.timmendorf.scene.Bauwerke.DECK + 1.7, sz * 0.45 + nz * 2, gier, 30};
            }
            case "Mondaufgang" -> new double[]{sx, com.dan.timmendorf.scene.Bauwerke.DECK + 1.7, sz, 98, -2};
            case "Startschiff" -> {
                double[] st = Site.fromUtm(Flotte.STARTSCHIFF[0], Flotte.STARTSCHIFF[1]);
                yield new double[]{st[0], 2.2, st[1], 315, 2};
            }
            default -> new double[]{Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN};
        };
    }

    /** Die Fahrten der Regiebücher; ihre Wege rechnet die App aus der Szene (Brücke, Küste, Boote, Möwen, Fußnetz). */
    public static List<Daten.Fahrt> fahrten() {
        double sx = Site.SCHLEIFE.x(), sz = Site.SCHLEIFE.z();
        Daten.Geom bruecke = Daten.Geom.linie(new double[][]{{0, 0}, {sx * 0.5, sz * 0.5}, {sx, sz}});
        return List.of(
                new Daten.Fahrt("Seebrücke", "STRECKE", 40.0, 230.0, "Vom Landende über die Maritim Seebrücke bis zur Schleife", Daten.MITGELIEFERT, bruecke, List.of()),
                new Daten.Fahrt("Promenade", "STRECKE", 40.0, null, "Die Promenade entlang, Blick zur See", Daten.MITGELIEFERT, null, List.of()),
                new Daten.Fahrt("Drohne", "STRECKE", 50.0, 2020.0, "250 m vor der Küste, 80 m hoch, von Niendorf bis zum Landende", Daten.MITGELIEFERT, null, List.of()),
                new Daten.Fahrt("Möwe", "MITFAHRT", null, null, "5 m hinter und 1 m über der nächsten fliegenden Möwe", Daten.MITGELIEFERT, null, List.of()),
                new Daten.Fahrt("Optimist", "MITFAHRT", null, null, "Im Optimisten vor der Seebrücke", Daten.MITGELIEFERT, null, List.of()),
                new Daten.Fahrt("Begleitboot", "MITFAHRT", null, null, "Neben dem Optimisten im Begleitboot", Daten.MITGELIEFERT, null, List.of()),
                new Daten.Fahrt("Regatta", "MITFAHRT", null, null, "Begleitboot an der Spitze der Regatta", Daten.MITGELIEFERT, null, List.of()),
                new Daten.Fahrt("Weg", "WEG", null, null, "Über das Fußnetz vom frei gewählten Ort zum Landende (der Ort wird nicht gespeichert)", Daten.MITGELIEFERT, null,
                        List.of()));
    }

    // ------------------------------------------------------------------ Presets (F42 A)

    /** Die Klassen, deren Presets mitkommen: jede öffentliche statische Methode ohne Argument, die die Klasse liefert. */
    public static final String[] PAKETE = {"com.dan.ocean.Ocean", "com.dan.cloud.Clouds", "com.dan.rain.Rain", "com.dan.fog.Fog", "com.dan.lightning.Lightning",
            "com.dan.sail.Sail", "com.dan.wake.Wake", "com.dan.jelly.Bloom", "com.dan.spray.Spray", "com.dan.soundscape.Soundscape", "com.dan.flock.Flock",
            "com.dan.cliff.Cliff"};

    private static List<Daten.PaketPreset> presets;

    public static synchronized List<Daten.PaketPreset> presets() {
        if (presets != null) return presets;
        List<Daten.PaketPreset> l = new ArrayList<>();
        for (String k : PAKETE) {
            try {
                Class<?> c = Class.forName(k);
                List<Method> ms = new ArrayList<>();
                for (Method m : c.getMethods())
                    if (Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 0 && m.getReturnType() == c) ms.add(m);
                ms.sort(Comparator.comparing(Method::getName));
                for (Method m : ms) {
                    Object o = m.invoke(null);
                    l.add(new Daten.PaketPreset(c.getPackageName(), c.getSimpleName(), m.getName(), "Preset " + m.getName() + "() aus " + c.getPackageName(),
                            Daten.MITGELIEFERT, Felder.lesen(o)));
                }
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Preset von " + k + " nicht lesbar: " + e, e);
            }
        }
        presets = Collections.unmodifiableList(l);
        return presets;
    }

    /** Die Typen der Felder eines Presets aus seiner Klasse. */
    public static Map<String, String> typen(Daten.PaketPreset p) {
        try {
            return Felder.typen(Class.forName(p.paket() + "." + p.klasse()));
        } catch (ClassNotFoundException e) {
            return Map.of();
        }
    }

    // ------------------------------------------------------------------ Boote

    /** Wind der Polaren (kn) und Winkel (Grad). */
    public static final double[] TWS_KN = {4, 6, 8, 10, 12, 16, 20};
    public static final double KN = 0.514444;

    private static List<Daten.BootKlasse> klassen;

    /** Die Takelagen aus com.dan.sail mit gerechneten Polaren (je TWS 30° bis 180° in Stufen von 10°). */
    public static synchronized List<Daten.BootKlasse> bootKlassen() {
        if (klassen != null) return klassen;
        Sail s = new Sail();
        List<Daten.BootKlasse> l = new ArrayList<>();
        for (Sail.Rig r : Sail.RIGS) {
            List<Daten.Polarpunkt> p = new ArrayList<>();
            for (double kn : TWS_KN) {
                for (double[] row : s.polar(r, kn * KN)) {
                    double twa = row[0];
                    if (Math.abs(twa / 10 - Math.rint(twa / 10)) > 1e-6) continue;
                    p.add(new Daten.Polarpunkt("GERECHNET", kn, twa, Math.round(row[1] / KN * 100) / 100.0, Math.round(Math.abs(row[2]) * 10) / 10.0));
                }
            }
            l.add(new Daten.BootKlasse(r.name(), r.kind().name(), r.plan().name(), r.loa(), r.lwl(), r.beam(), r.mass(), r.sailArea(), r.downwindArea(), r.mastHeight(),
                    r.crew(), "com.dan.sail (Timmendorf Phase 7 und 8)", p));
        }
        klassen = Collections.unmodifiableList(l);
        return klassen;
    }

    /** Die Boote der Szene (wie in scene.Flotte). */
    public static List<Daten.Boot> boote() {
        List<Daten.Boot> l = new ArrayList<>();
        l.add(new Daten.Boot("Opti vor der Seebrücke", Sail.OPTIMIST.name(), "GER 1", "SEGELSCHULE", "weiß"));
        for (int i = 1; i <= 8; i++) l.add(new Daten.Boot("Schule " + i, Sail.OPTIMIST.name(), "SN " + i, "SEGELSCHULE", null));
        for (int i = 1; i <= 20; i++) l.add(new Daten.Boot("J/70 " + i, Sail.J70.name(), Integer.toString(i), "REGATTA", null));
        l.add(new Daten.Boot("Fahrtenyacht A", Sail.CRUISER.name(), null, "FAHRT", "weiß"));
        l.add(new Daten.Boot("Fahrtenyacht B", Sail.CRUISER.name(), null, "FAHRT", "weiß"));
        l.add(new Daten.Boot("Gaffelschoner", Sail.SCHOONER.name(), null, "FAHRT", "lohfarben"));
        l.add(new Daten.Boot("Hobie 1", Sail.HOBIE.name(), null, "FAHRT", "bunt"));
        l.add(new Daten.Boot("Hobie 2", Sail.HOBIE.name(), null, "FAHRT", "bunt"));
        l.add(new Daten.Boot("Kiter 1", Sail.KITE.name(), null, "KITE", "rot"));
        l.add(new Daten.Boot("Kiter 2", Sail.KITE.name(), null, "KITE", "gelb"));
        l.add(new Daten.Boot("Passat", Sail.PASSAT.name(), null, "LIEGEPLATZ", "gepackt"));
        return l;
    }

    /** Die Regatta der Szene: Bahn aus Startschiff und Luvtonne (wie in scene.Flotte), Linie 160 m, zwei Runden. */
    public static List<Daten.Regatta> regatten() {
        double[] cs = Site.fromUtm(Flotte.STARTSCHIFF[0], Flotte.STARTSCHIFF[1]), lv = Site.fromUtm(Flotte.LUV[0], Flotte.LUV[1]);
        Regatta.Course c = Regatta.Course.fromMarks(cs[0], cs[1], lv[0], lv[1], 160, 2);
        String[] rolle = {"STARTSCHIFF", "PIN", "LUV", "LEE_TOR", "LEE_TOR"};
        List<double[]> m = c.marks();
        List<Daten.Tonne> t = new ArrayList<>();
        for (int i = 0; i < m.size(); i++) t.add(new Daten.Tonne(i + 1, rolle[i], m.get(i)[0], m.get(i)[1]));
        return List.of(new Daten.Regatta(REGATTA, TAG, Sail.J70.name(), c.axisDeg, c.length, c.line, c.laps, t));
    }

    // ------------------------------------------------------------------ Feuerwerk

    /** Der Ponton 350 m vor der Seebrücke (wie in Wiring). */
    public static double[] ponton() { return new double[]{Site.SCHLEIFE.x() + 120, Site.SCHLEIFE.z() - 160}; }

    /** Ein Zündplan als Text im Format von {@link Firework#load}. */
    public static String text(Firework f) {
        StringBuilder b = new StringBuilder();
        for (Firework.Shell s : f.shells()) {
            b.append(String.format(Locale.ROOT, "%.1f %d %s %s", s.launchT, s.caliberMm, s.effect, s.salt));
            if (s.tiltDeg != 0) b.append(String.format(Locale.ROOT, " neigung=%.1f", s.tiltDeg));
            if (s.azimuthDeg != 0) b.append(String.format(Locale.ROOT, " richtung=%.1f", s.azimuthDeg));
            b.append('\n');
        }
        return b.toString();
    }

    static String effektName(Firework.Effect e, Firework.Salt s, int mm) { return e + " " + s + " " + mm; }

    public static List<Daten.Zuendplan> zuendplaene() {
        double[] p = ponton();
        List<Daten.Zuendplan> l = new ArrayList<>();
        Firework[] f = {Firework.travemuenderWoche(p[0], p[1]), Firework.demo(p[0], p[1])};
        String[] n = {FEUERWERK, FEUERWERK_KLEIN};
        for (int k = 0; k < f.length; k++) {
            List<Daten.Schuss> s = new ArrayList<>();
            int nr = 0;
            for (Firework.Shell x : f[k].shells())
                s.add(new Daten.Schuss(++nr, x.launchT, effektName(x.effect, x.salt, x.caliberMm), x.caliberMm, x.tiltDeg, x.azimuthDeg));
            l.add(new Daten.Zuendplan(n[k], TAG, "22:45", Math.round(f[k].duration() * 10) / 10.0, text(f[k]), p[0], p[1], Daten.MITGELIEFERT, s));
        }
        return l;
    }

    /** Alle Effekte der mitgelieferten Zündpläne (Bild, Salz, Kaliber) mit Farbe, Halbmesser und Brenndauer. */
    public static List<Daten.Effekt> effekte() {
        Map<String, Daten.Effekt> m = new LinkedHashMap<>();
        double[] p = ponton();
        for (Firework f : new Firework[]{Firework.travemuenderWoche(p[0], p[1]), Firework.demo(p[0], p[1])})
            for (Firework.Shell s : f.shells()) {
                String name = effektName(s.effect, s.salt, s.caliberMm);
                if (m.containsKey(name)) continue;
                double[] rgb = s.salt.rgb();
                double mx = Math.max(1e-9, Math.max(rgb[0], Math.max(rgb[1], rgb[2])));
                String farbe = String.format("#%02X%02X%02X", srgb(rgb[0] / mx), srgb(rgb[1] / mx), srgb(rgb[2] / mx));
                m.put(name, new Daten.Effekt(name, s.effect.name(), s.salt.name(), farbe, s.salt.nm > 0 ? s.salt.nm : null, s.caliberMm,
                        Math.round(Firework.burstRadius(s.caliberMm) * 10) / 10.0, Math.round(Firework.starBurn(s.caliberMm, s.effect) * 100) / 100.0));
            }
        return new ArrayList<>(m.values());
    }

    static int srgb(double v) {
        v = Math.max(0, Math.min(1, v));
        return (int) Math.round(255 * (v <= 0.0031308 ? 12.92 * v : 1.055 * Math.pow(v, 1 / 2.4) - 0.055));
    }

    // ------------------------------------------------------------------ Küste

    /** Die Sturmflut vom 20./21. Oktober 2023 mit dem Fahrplan der App (Pegel, Wind, Seegang je Stunde). */
    public static List<Daten.Sturmflut> sturmfluten() {
        long t0 = Sturmflut.BEGINN.atZone(Sturmflut.ZONE).toInstant().toEpochMilli();
        List<Daten.Pegel> p = new ArrayList<>();
        for (int h = 0; h <= Sturmflut.STUNDEN; h++) {
            double w = Sturmflut.wind(h), d = Sturmflut.richtung(h);
            double[] see = com.dan.timmendorf.wiring.Wetterregie.seegang(w, d);
            p.add(new Daten.Pegel(h, t0 + h * 3_600_000L, Math.round(Sturmflut.pegel(h) * 100) / 100.0, Math.round(w * 100) / 100.0, Math.round(d * 10) / 10.0,
                    Math.round(see[0] * 100) / 100.0));
        }
        return List.of(new Daten.Sturmflut(STURMFLUT, t0, t0 + (long) (Sturmflut.SCHEITEL_H * 3_600_000L), Sturmflut.SCHEITEL, "SCHWER", "BSH",
                "Schwere Sturmflut an der westlichen Ostsee in der Nacht zum 21.10.2023: Scheitel Travemünde 1,77 m über NHN (belegt), Stunden dazwischen "
                        + "und Nachschwingen geglättet [G]; Abbrüche am Brodtener Ufer", p));
    }

    /**
     * Die Oberkante des Brodtener Ufers aus dem DGM1: eine Linie durch die Orte der geplanten Abbrüche längs der Küste
     * (dieselben Zellen, an denen das Kliff bricht). Ohne {@code daten/gelaende} leer.
     */
    public static List<Daten.Kante> kanten(Terrain t) {
        if (t == null || !t.measured()) return List.of();
        com.dan.timmendorf.scene.Abbruch a = abbruch(t);
        List<double[]> pts = new ArrayList<>();
        for (com.dan.timmendorf.scene.Abbruch.Ereignis e : a.plan()) pts.add(new double[]{e.x(), e.z()});
        if (pts.size() < 2) return List.of();
        double[][] line = laengs(pts);
        return List.of(new Daten.Kante("DGM1 2022", "DGM1", null, Math.round(Daten.Geom.linie(line).laenge() * 10) / 10.0, Daten.Geom.linie(line)));
    }

    /** Der Plan der Abbrüche wie in gl.Renderer (Zellen 2,5 m über dem Ausschnitt des Kliffs, Startwert 2023). */
    public static com.dan.timmendorf.scene.Abbruch abbruch(Terrain t) {
        double x0 = com.dan.timmendorf.gl.Renderer.KLIFF_X0, z0 = com.dan.timmendorf.gl.Renderer.KLIFF_Z0;
        int n = (int) Math.round(com.dan.timmendorf.gl.Renderer.KLIFF_ZELLE * com.dan.timmendorf.gl.Renderer.KLIFF_N / 2.5);
        return new com.dan.timmendorf.scene.Abbruch(t::height, x0, z0, 2.5, n, 2023);
    }

    /** Punkte längs ihrer Hauptachse ordnen und auf einen je 10 m ausdünnen. */
    static double[][] laengs(List<double[]> pts) {
        double mx = 0, mz = 0;
        for (double[] p : pts) { mx += p[0]; mz += p[1]; }
        mx /= pts.size();
        mz /= pts.size();
        double sxx = 0, sxz = 0, szz = 0;
        for (double[] p : pts) { double dx = p[0] - mx, dz = p[1] - mz; sxx += dx * dx; sxz += dx * dz; szz += dz * dz; }
        double ang = 0.5 * Math.atan2(2 * sxz, sxx - szz), ux = Math.cos(ang), uz = Math.sin(ang);
        final double cx = mx, cz = mz;
        List<double[]> s = new ArrayList<>(pts);
        s.sort(Comparator.comparingDouble(p -> (p[0] - cx) * ux + (p[1] - cz) * uz));
        List<double[]> out = new ArrayList<>();
        for (double[] p : s) if (out.isEmpty() || Math.hypot(p[0] - out.get(out.size() - 1)[0], p[1] - out.get(out.size() - 1)[1]) >= 10) out.add(p);
        if (out.size() < 2) out.add(s.get(s.size() - 1));
        return out.toArray(new double[0][]);
    }

    // ------------------------------------------------------------------ Leuchtfeuer und Busse

    /** Die Leuchtfeuer der Bucht aus com.dan.beacon (ohne die aus OSM, die nur mit daten/bauwerke kommen). */
    public static List<Daten.Leuchtfeuer> leuchtfeuer() {
        com.dan.timmendorf.scene.Leuchtfeuer lf = new com.dan.timmendorf.scene.Leuchtfeuer(
                com.dan.timmendorf.scene.Bauwerke.laden(com.dan.timmendorf.scene.Daten.ordner()));
        List<Daten.Leuchtfeuer> l = new ArrayList<>();
        java.util.Set<String> namen = new java.util.HashSet<>();
        for (com.dan.timmendorf.scene.Leuchtfeuer.Licht x : lf.lichter) {
            com.dan.beacon.Beacon b = x.beacon();
            String name = b.name;
            for (int i = 2; !namen.add(name); i++) name = b.name + " (" + i + ")";
            List<Daten.Sektor> s = new ArrayList<>();
            int nr = 0;
            for (com.dan.beacon.SectorTable.Sector q : b.sectors().sectors()) s.add(new Daten.Sektor(++nr, q.from(), q.to(), q.colour().code(), q.rangeNm()));
            l.add(new Daten.Leuchtfeuer(name, b.characteristic().text, Math.round(b.y * 10) / 10.0, b.sectors().maxRangeNm(), b != lf.alter, b.x, b.z, x.quelle(), s));
        }
        return l;
    }

    /** Die Buslinie Scharbeutz – Niendorf hin und zurück, alle 30 min von 6 bis 22 Uhr [G]. */
    public static List<Daten.Linie> linien() {
        List<Daten.Linie> l = new ArrayList<>();
        String[][] r = {{"Bus Scharbeutz – Niendorf", "Scharbeutz", "Niendorf"}, {"Bus Niendorf – Scharbeutz", "Niendorf", "Scharbeutz"}};
        for (String[] x : r) {
            List<Daten.Abfahrt> a = new ArrayList<>();
            for (int m = 6 * 60; m <= 22 * 60; m += 30) a.add(new Daten.Abfahrt(x[1], null, "Mo-So", String.format("%02d:%02d", m / 60, m % 60), x[2]));
            l.add(new Daten.Linie(null, x[0], null, 30, a));
        }
        return l;
    }

    // ------------------------------------------------------------------ Hilfen

    /** SHA-256 eines Texts (hex). */
    public static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder b = new StringBuilder();
            for (byte x : md.digest(s.getBytes(StandardCharsets.UTF_8))) b.append(String.format("%02x", x));
            return b.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** Für Prüfungen: alle Zahlen auf einen Blick. */
    public static String zusammenfassung() {
        return String.format(Locale.GERMAN, "%d Befunde, %d Regiebücher, %d Ansichten, %d Fahrten, %d Presets, %d Bootsklassen, %d Boote, %d Regatta, %d Effekte, "
                        + "%d Zündpläne, %d Sturmflut, %d Leuchtfeuer, %d Linien", befunde().size(), regiebuecher().size(), ansichten().size(), fahrten().size(),
                presets().size(), bootKlassen().size(), boote().size(), regatten().size(), effekte().size(), zuendplaene().size(), sturmfluten().size(),
                leuchtfeuer().size(), linien().size());
    }
}
