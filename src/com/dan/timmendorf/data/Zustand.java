package com.dan.timmendorf.data;

import com.dan.sail.Regatta;
import com.dan.store.Felder;
import com.dan.store.Zahl;
import com.dan.timmendorf.camera.Camera;
import com.dan.timmendorf.gl.Quality;
import com.dan.timmendorf.gl.Renderer;
import com.dan.timmendorf.scene.Physik;
import com.dan.timmendorf.wiring.Wetterregie;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Der ganze Zustand der Szene als Text (F38–F40 A, „Weiter wie zuletzt“): alle öffentlichen Felder der Packages und
 * der Szene, dazu Uhr, Zeitraffer, Kamera, Wetterregie (Modus und Lage), das laufende Regiebuch mit Stelle und die
 * Wettfahrt mit Lage, Schlag und Runde jedes Boots. Das Brodtener Ufer steht von selbst wie zuvor: seine Abbrüche folgen
 * der Uhr im Fahrplan der Sturmflut (F39 A). Feuerwerk und Sturmflut folgen ebenso der Uhr; Menschen, Möwen und
 * Quallen setzt die Szene neu (F40 A). Dasselbe Format dient für gemerkte Ansichten mit Uhr und die Dateien ohne
 * Datenbank. Schlüssel der Form {@code teil.feld}: {@code ocean.windSpeed}, {@code kamera.x}, {@code regie.lage}.
 */
public final class Zustand {
    private Zustand() { }

    static final Set<String> OHNE = Set.of("quality");
    /** Anfragen, Zustände des Laufs, abgeleitete Größen. */
    public static final Set<String> OHNE_RENDERER = Set.of("photoRequest", "frozen", "photoName", "renderScale", "showMarks", "quality", "showHud", "hudFull");   // HUD seit Phase 19: beim Start immer aus
    static final Set<String> OHNE_WIRING = Set.of("pegel", "cloudsDrawn", "gustNow", "sturmflutStunde", "verkehrQuelle", "quality");
    static final Set<String> OHNE_SKY = Set.of("epochMillis", "latDeg", "lonDeg", "quality");
    static final Set<String> OHNE_KLANG = Set.of("on", "offline", "wattsHier");
    static final Set<String> OHNE_RAIN = Set.of("windSpeed", "quality");
    static final Set<String> OHNE_VOGEL = Set.of("ambientLux", "quality");
    static final Set<String> OHNE_QUALLEN = Set.of("dayOfYear", "waterTemp", "windSpeed", "windFromDeg", "hs", "waterLevel", "enabled", "quality");

    /** Die Teile mit öffentlichen Feldern: Name im Zustand → Objekt. */
    static Map<String, Object> teile(Renderer r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("renderer", r);
        m.put("wiring", r.wiring);
        m.put("ocean", r.meer.ocean);
        m.put("sky", r.wetter.sky);
        m.put("clouds", r.wetter.clouds);
        m.put("rain", r.unwetter.rain);
        m.put("fog", r.unwetter.fog);
        m.put("blitz", r.unwetter.blitz);
        m.put("klang", r.klang);
        m.put("soundscape", r.klang.s);
        m.put("quallen", r.quallen);
        m.put("brecher", r.brecher);
        m.put("vogel", r.leben.vogel);
        m.put("sail", r.wiring.flotte.sail);
        return m;
    }

    private static Set<String> ohne(String teil) {
        return switch (teil) {
            case "renderer" -> OHNE_RENDERER;
            case "wiring" -> OHNE_WIRING;
            case "sky" -> OHNE_SKY;
            case "klang" -> OHNE_KLANG;
            case "quallen" -> OHNE_QUALLEN;
            case "rain" -> OHNE_RAIN;
            case "vogel" -> OHNE_VOGEL;
            default -> OHNE;
        };
    }

    /** Das laufende Objekt einer Klasse mit Presets (einfacher Name, etwa „Ocean“) in der Szene, oder null. */
    public static Object objekt(Renderer r, String klasse) {
        for (Object o : teile(r).values()) if (o != null && o.getClass().getSimpleName().equals(klasse)) return o;
        return null;
    }

    /** Erfasst den Zustand (aus dem Swing-Faden oder dem Faden der Daten; die Felder sind volatile oder einfache Werte). */
    public static LinkedHashMap<String, String> erfassen(Renderer r) {
        LinkedHashMap<String, String> z = new LinkedHashMap<>();
        z.put("app.version", Renderer.VERSION);
        z.put("app.qualitaet", r.quality().name());
        z.put("app.ton", Boolean.toString(r.klang.on));
        z.put("uhr.ms", Long.toString((long) Math.floor(r.clock.epochMillis())));
        z.put("uhr.text", Daten.text(r.clock.epochMillis()));
        z.put("uhr.lapse", Zahl.text(r.clock.lapse));
        Camera c = r.camera;
        z.put("kamera.modus", c.mode == Camera.Mode.KREISEN ? "KREISEN" : "FREI");
        z.put("kamera.kreisen", Boolean.toString(c.autoOrbit));
        double[] k = kamera(r);
        String[] n = {"x", "y", "z", "gier", "neigung"};
        for (int i = 0; i < 5; i++) z.put("kamera." + n[i], Zahl.text(Math.round(k[i] * 1000) / 1000.0));
        z.put("kamera.ziel", Zahl.text(c.tx) + " " + Zahl.text(c.ty) + " " + Zahl.text(c.tz) + " " + Zahl.text(c.yaw) + " " + Zahl.text(c.pitch) + " " + Zahl.text(c.dist));
        Wetterregie w = r.wiring.regie;
        z.put("regie.modus", w.modus().name());
        z.put("regie.lage", w.lage().name());
        z.put("regie.lageText", w.lage().label);
        z.put("pegel.jetzt", Zahl.text(Math.round(r.wiring.pegel * 1000) / 1000.0));
        z.put("sturmflut.aktiv", Boolean.toString(!Double.isNaN(r.wiring.sturmflutStunde)));
        z.put("feuerwerk.tag", r.wiring.feuerwerkTag.toString());
        var rg = r.regisseur.regie;
        var bk = rg.book();
        if (bk != null && rg.running()) {
            z.put("regiebuch.name", bk.name());
            z.put("regiebuch.sekunde", Zahl.text(Math.round(rg.time() * 100) / 100.0));
        }
        regattaErfassen(r.wiring.flotte.regatta, z);
        for (Map.Entry<String, Object> e : teile(r).entrySet())
            for (Map.Entry<String, String> f : Felder.lesen(e.getValue(), ohne(e.getKey())).entrySet()) z.put(e.getKey() + "." + f.getKey(), f.getValue());
        return z;
    }

    /** Die Wettfahrt: Laufzeit, Startschuss, Lauf, im Ziel; je Boot Lage, Kurs, Fahrt, Schlag, Runde, Platz, Frühstart, Zielzeit, Wenden, Halsen. */
    static void regattaErfassen(Regatta g, Map<String, String> z) {
        double tg = g.toGun();
        z.put("regatta.zeit", Zahl.text(Math.round(g.time() * 100) / 100.0));
        z.put("regatta.schuss", Double.isNaN(tg) ? "" : Zahl.text(Math.round((g.time() + tg) * 100) / 100.0));
        z.put("regatta.lauf", Integer.toString(g.race()));
        z.put("regatta.ziel", Integer.toString(g.finished()));
        for (int i = 0; i < g.entries.size(); i++) {
            Regatta.Entry e = g.entries.get(i);
            z.put("regatta.boot." + i, String.join(" ", Zahl.text(Math.round(e.boat.x * 100) / 100.0), Zahl.text(Math.round(e.boat.z * 100) / 100.0),
                    Zahl.text(Math.round(e.boat.headingDeg * 10) / 10.0), Zahl.text(Math.round(e.boat.speed * 100) / 100.0), e.leg.name(), Integer.toString(e.lap),
                    Integer.toString(e.place), Boolean.toString(e.ocs), Double.isNaN(e.finishTime) ? "-" : Zahl.text(Math.round(e.finishTime * 10) / 10.0),
                    Integer.toString(e.tacks), Integer.toString(e.gybes)));
        }
    }

    /** Die Wettfahrt herstellen (im Faden der Physik, damit sie nicht mitten im Schritt wechselt). */
    static boolean regattaAnwenden(Regatta g, Map<String, String> z) {
        if (!z.containsKey("regatta.zeit")) return false;
        double t = Zahl.lies(z.get("regatta.zeit")), gun = Zahl.lies(z.getOrDefault("regatta.schuss", ""));
        if (Double.isNaN(t)) return false;
        g.resume(t, gun, (int) Zahl.lies(z.getOrDefault("regatta.ziel", "0")), (int) Zahl.lies(z.getOrDefault("regatta.lauf", "0")));
        for (int i = 0; i < g.entries.size(); i++) {
            String v = z.get("regatta.boot." + i);
            if (v == null) continue;
            String[] p = v.split(" ");
            if (p.length < 11) continue;
            Regatta.Entry e = g.entries.get(i);
            e.boat.x = Zahl.lies(p[0]);
            e.boat.z = Zahl.lies(p[1]);
            e.boat.headingDeg = Zahl.lies(p[2]);
            e.boat.targetHeadingDeg = Double.NaN;
            e.boat.speed = Zahl.lies(p[3]);
            try { e.leg = Regatta.Leg.valueOf(p[4]); } catch (IllegalArgumentException x) { /* bleibt */ }
            e.lap = Integer.parseInt(p[5]);
            e.place = Integer.parseInt(p[6]);
            e.ocs = Boolean.parseBoolean(p[7]);
            e.finishTime = p[8].equals("-") ? Double.NaN : Zahl.lies(p[8]);
            e.tacks = Integer.parseInt(p[9]);
            e.gybes = Integer.parseInt(p[10]);
        }
        return true;
    }

    /** Die Spalten von TD_SITZUNG aus einem Zustand (für {@code Ablage.Sicherung}). */
    public static Map<String, String> spalten(Map<String, String> z) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(Speicher.SZENE_MS, z.getOrDefault("uhr.ms", ""));
        m.put(Speicher.ZEITRAFFER, z.getOrDefault("uhr.lapse", ""));
        m.put(Speicher.LAGE, z.getOrDefault("regie.lageText", ""));
        m.put(Speicher.PEGEL, z.getOrDefault("pegel.jetzt", ""));
        m.put(Speicher.WIND, z.getOrDefault("wiring.windSpeed", ""));
        m.put(Speicher.WIND_AUS, z.getOrDefault("wiring.windFromDeg", ""));
        m.put(Speicher.STURMFLUT, z.getOrDefault("sturmflut.aktiv", "false"));
        m.put(Speicher.KAMERA_X, z.getOrDefault("kamera.x", ""));
        m.put(Speicher.KAMERA_Y, z.getOrDefault("kamera.y", ""));
        m.put(Speicher.KAMERA_Z, z.getOrDefault("kamera.z", ""));
        if (z.containsKey("regiebuch.name")) { m.put(Speicher.REGIEBUCH, z.get("regiebuch.name")); m.put(Speicher.REGIE_SEKUNDE, z.get("regiebuch.sekunde")); }
        return m;
    }

    /** Kamera für Ansichten: {x, y, z, Gier, Neigung nach unten} aus dem laufenden Bild. */
    public static double[] kamera(Renderer r) {
        Camera c = r.camera;
        double[] e = c.eye, f = c.fwd;
        double neigung = Math.toDegrees(-Math.asin(Math.max(-1, Math.min(1, f[1]))));
        return new double[]{e[0], e[1], e[2], c.heading(), neigung};
    }

    public static Long szeneMs(Map<String, String> z) {
        double v = Zahl.lies(z.get("uhr.ms"));
        return Double.isNaN(v) ? null : (long) v;
    }

    public static Quality qualitaet(Map<String, String> z) {
        try { return Quality.valueOf(z.get("app.qualitaet")); } catch (RuntimeException e) { return null; }
    }

    /** Stellt den Zustand her (aus dem Swing-Faden) außer Qualität und Ton (das macht {@code ui.Weiter}). Gibt zurück, was nicht ging. */
    public static List<String> anwenden(Renderer r, Map<String, String> z) {
        List<String> fehler = new ArrayList<>();
        // erst die Regie, denn sie setzt Wind, Wolken und See; die Felder danach sind die gespeicherten
        Wetterregie w = r.wiring.regie;
        try {
            Wetterregie.Modus mo = Wetterregie.Modus.valueOf(z.getOrDefault("regie.modus", "LAGE"));
            Wetterregie.Lage la = Wetterregie.Lage.valueOf(z.getOrDefault("regie.lage", "SOMMERABEND"));
            switch (mo) {
                case LAGE -> w.lage(la);
                case FREI -> w.frei();
                case STURMTAG -> w.sturmtag();
                case HAND -> { w.lage(la); w.hand(); }
            }
        } catch (IllegalArgumentException e) {
            fehler.add("regie: " + e.getMessage());
        }
        for (Map.Entry<String, Object> e : teile(r).entrySet()) {
            Map<String, String> t = Felder.teil(z, e.getKey());
            t.keySet().removeIf(x -> x.contains("."));
            for (String f : Felder.schreiben(e.getValue(), t, ohne(e.getKey()))) fehler.add(e.getKey() + "." + f);
        }
        Long ms = szeneMs(z);
        if (ms != null) r.clock.setEpochMillis(ms);
        double lapse = Zahl.lies(z.get("uhr.lapse"));
        if (!Double.isNaN(lapse)) r.clock.lapse = lapse;
        try { if (z.containsKey("feuerwerk.tag")) r.wiring.feuerwerkTag = java.time.LocalDate.parse(z.get("feuerwerk.tag")); } catch (RuntimeException e) { fehler.add("feuerwerk.tag"); }
        // die Wettfahrt im Faden der Physik (einmal)
        if (z.containsKey("regatta.zeit")) {
            Regatta g = r.wiring.flotte.regatta;
            r.physik.add(new Physik.Aufgabe() {
                boolean fertig;
                @Override public void step(double t, double dt) {
                    if (fertig) return;
                    fertig = true;
                    regattaAnwenden(g, z);
                    r.physik.remove(this);
                }
                @Override public String name() { return "Weiter: Wettfahrt"; }
            });
        }
        Camera c = r.camera;
        c.stopFahrt();
        c.stopMitfahrt();
        String[] ziel = z.getOrDefault("kamera.ziel", "").split(" ");
        if ("KREISEN".equals(z.get("kamera.modus")) && ziel.length == 6) {
            c.mode = Camera.Mode.KREISEN;
            c.lookAt(Zahl.lies(ziel[0]), Zahl.lies(ziel[1]), Zahl.lies(ziel[2]), Zahl.lies(ziel[3]), Zahl.lies(ziel[4]), Zahl.lies(ziel[5]));
            c.autoOrbit = "true".equals(z.get("kamera.kreisen"));
            c.snap();
        } else {
            double x = Zahl.lies(z.get("kamera.x")), y = Zahl.lies(z.get("kamera.y")), zz = Zahl.lies(z.get("kamera.z"));
            if (!Double.isNaN(x) && !Double.isNaN(y) && !Double.isNaN(zz)) {
                c.mode = Camera.Mode.FREI;
                c.place(x, y, zz, Zahl.lies(z.get("kamera.gier")), Zahl.lies(z.get("kamera.neigung")));
                c.snap();
            }
        }
        r.readapt();
        return fehler;
    }
}
