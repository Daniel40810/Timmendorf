package com.dan.timmendorf.app;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Die Packages aus {@code lib/Renderer.jar} (Bibliothek statt Kopie, aus Sylt und Brocken). Findet das Jar über eine
 * Klasse, die darin liegen muss ({@code com.dan.sky.Sky}), und zählt die Packages unter {@code com/dan/}. Für HUD,
 * Bedienfeld und Prüfung.
 */
public final class Bibliothek {
    private Bibliothek() { }

    /**
     * Packages, die Timmendorf nach dem Werkbuch (Abschnitt 6) aus Renderer.jar braucht: genutzt oder erweitert
     * ({@code ocean}, {@code buoyancy}, {@code surf}, {@code beacon}, {@code figure}, {@code cliff}, {@code soundscape}).
     * Seit Phase 5 {@code tree} (mit {@code tree.grass}), aus Nature umgezogen.
     */
    public static final String[] GEBRAUCHT = {"ocean", "buoyancy", "surf", "beacon", "figure", "cliff", "fracture", "soundscape", "wind", "tide", "wave",
            "mudflat", "dune", "reed", "spray", "flock", "seal", "smoke", "sky", "cloud", "rain", "fog", "weather", "lightning", "season", "ice", "rime",
            "kino", "store", "ground", "weathering", "wetglass", "tree"};

    /**
     * Packages, die für Timmendorf im Projekt Renderer neu entstehen; seit Phase 1 als Hüllen nach {@code muster}, seit
     * Phase 11 dazu {@code lamp} (F18 A), seit Phase 20 {@code beachchair} (der Strandkorb nach dem Foto),
     * seit Phase 21 {@code beach} (der Strand aus dem Asset).
     */
    public static final String[] NEU = {"cloth", "sail", "wake", "firework", "route", "lamp", "jelly", "beachchair", "beach"};

    private static volatile List<String> cache;
    private static volatile String jar = "";

    /** Die Packages in Renderer.jar (Namen unter com.dan), sortiert; leer, wenn das Jar fehlt. */
    public static List<String> pakete() {
        List<String> c = cache;
        if (c != null) return c;
        TreeSet<String> s = new TreeSet<>();
        try {
            URL u = Class.forName("com.dan.sky.Sky").getProtectionDomain().getCodeSource().getLocation();
            File f = new File(u.toURI());
            jar = f.getName();
            if (f.isFile()) {
                try (ZipFile z = new ZipFile(f)) {
                    for (ZipEntry e : java.util.Collections.list(z.entries())) {
                        String n = e.getName();
                        if (!n.startsWith("com/dan/") || !n.endsWith(".class")) continue;
                        String[] p = n.split("/");
                        if (p.length >= 4) s.add(p[2]);
                    }
                }
            } else if (f.isDirectory()) {
                File[] d = new File(f, "com/dan").listFiles(File::isDirectory);
                if (d != null) for (File x : d) s.add(x.getName());
            }
        } catch (Exception | LinkageError e) {
            jar = "nicht gefunden (" + e.getClass().getSimpleName() + ")";
        }
        cache = List.copyOf(s);
        return cache;
    }

    /** Was das Werkbuch aus Renderer.jar braucht, dort aber fehlt (gebraucht und neu). */
    public static List<String> fehlend() {
        List<String> p = pakete(), f = new ArrayList<>();
        for (String g : GEBRAUCHT) if (!p.contains(g)) f.add(g);
        for (String g : NEU) if (!p.contains(g)) f.add(g);
        return f;
    }

    /** „45 Packages in Renderer.jar, alle 32 gebrauchten da, dazu cloth, sail, wake, firework, route“ */
    public static String kurz() {
        List<String> p = pakete(), f = fehlend();
        if (p.isEmpty()) return "!nicht gefunden " + jar;
        return String.format(Locale.GERMAN, "%d Packages in %s, %s", p.size(), jar,
                f.isEmpty() ? "alle " + GEBRAUCHT.length + " gebrauchten da, dazu " + String.join(", ", NEU) : "es fehlen " + String.join(", ", f));
    }

    /** „cloth ✓ · sail ✓ · …“ – welche der neuen Packages im Jar liegen. */
    public static String neuStand() {
        List<String> p = pakete();
        StringBuilder sb = new StringBuilder();
        for (String n : NEU) sb.append(sb.length() > 0 ? " · " : "").append(n).append(p.contains(n) ? " ✓" : " fehlt (Renderer neu bauen)");
        return sb.toString();
    }
}
