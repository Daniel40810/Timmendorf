import java.io.DataInputStream;
import java.io.File;
import java.io.InputStream;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Prüfung von {@code lib/Renderer.jar} (Ant-Target {@code check-packages}, aus Sylt). Die Element-Packages liegen
 * nicht im Projekt Timmendorf: ihre Quelle ist das Projekt Renderer (Paketvertrag Punkt 11), dort prüft
 * {@code tools/PaketPruefung.java} jedes Package einzeln (Punkt 9). Hier wird nur geprüft, ob das Jar alles hat, was
 * Timmendorf braucht: jedes Package mit Klassen und README, Klassen für Java 21 (Version 65) oder älter, und bei den
 * neuen Packages {@code cloth}, {@code sail}, {@code wake}, {@code firework} und {@code route} (Hüllen seit Phase 1), seit Phase 11 {@code lamp}
 * Kern, Darstellung, Shader, Vorführung und Prüfklasse. Aufruf ohne Ant: {@code java tools/JarPruefung.java lib/Renderer.jar}.
 */
public final class JarPruefung {
    /** Was Timmendorf aus Renderer.jar braucht (wie {@code app.Bibliothek}: GEBRAUCHT und NEU). */
    static final String[] GEBRAUCHT = {"ocean", "buoyancy", "surf", "beacon", "figure", "cliff", "fracture", "soundscape", "wind", "tide", "wave",
            "mudflat", "dune", "reed", "spray", "flock", "seal", "smoke", "sky", "cloud", "rain", "fog", "weather", "lightning", "season", "ice", "rime",
            "kino", "store", "ground", "weathering", "wetglass", "cloth", "sail", "wake", "firework", "route", "lamp", "tree", "jelly"};
    static final String[][] SHADER = {
            {"tree", "Tree.class", "europeanbeech/EuropeanBeech.class", "europeanbeech/beech_a.dtree", "balticpine/BalticPine.class", "balticpine/pine_a.dtree"},   // seit Phase 19 (F24)
            {"cloth", "Cloth.class", "gl/ClothGl.class", "gl/cloth.vert", "gl/cloth.frag", "demo/ClothCheck.class", "demo/ClothDemo.class"},
            {"sail", "Sail.class", "Regatta.class", "BoatShape.class", "gl/SailGl.class", "gl/sail.vert", "gl/sail.frag", "demo/SailCheck.class", "demo/SailDemo.class"},
            {"wake", "Wake.class", "gl/WakeGl.class", "gl/wake.comp", "demo/WakeCheck.class", "demo/WakeDemo.class"},
            {"firework", "Firework.class", "Spectrum.class", "gl/FireworkGl.class", "gl/firework.vert", "gl/firework.frag", "gl/firework_trail.vert", "gl/firework_trail.frag", "demo/FireworkCheck.class", "demo/FireworkDemo.class"},
            {"route", "Route.class", "Crowd.class", "Line.class", "gl/RouteGl.class", "gl/route.vert", "gl/route.frag", "demo/RouteCheck.class", "demo/RouteDemo.class"},
            {"lamp", "Lamp.class", "Lamps.class", "Spectra.class", "Windows.class", "Wet.class", "gl/LampGl.class", "gl/lamp.glsl", "gl/lamp_head.vert", "gl/lamp_head.frag", "demo/LampCheck.class", "demo/LampDemo.class"}};

    public static void main(String[] a) throws Exception {
        File jar = new File(a.length > 0 ? a[0] : "lib/Renderer.jar");
        if (!jar.isFile()) { System.err.println("check-packages: " + jar + " fehlt"); System.exit(1); }
        Map<String, int[]> p = new TreeMap<>();   // Klassen, README, höchste Version
        try (ZipFile z = new ZipFile(jar)) {
            for (ZipEntry e : java.util.Collections.list(z.entries())) {
                String n = e.getName();
                if (!n.startsWith("com/dan/") || e.isDirectory()) continue;
                String[] t = n.split("/");
                if (t.length < 4) continue;
                int[] v = p.computeIfAbsent(t[2], k -> new int[3]);
                if (n.endsWith(".class")) {
                    v[0]++;
                    try (InputStream in = z.getInputStream(e); DataInputStream d = new DataInputStream(in)) {
                        d.readInt();
                        d.readUnsignedShort();
                        v[2] = Math.max(v[2], d.readUnsignedShort());
                    }
                } else if (t.length == 4 && t[3].equals("README.md")) v[1] = 1;
            }
            int fehler = 0;
            System.out.println("check-packages: " + jar.getName() + " mit " + p.size() + " Packages (Quelle: Projekt Renderer)");
            System.out.println("  Package        Klassen  README  Java");
            for (String g : GEBRAUCHT) {
                int[] v = p.get(g);
                boolean ok = v != null && v[0] > 0 && v[1] == 1 && v[2] <= 65;
                if (!ok) fehler++;
                System.out.printf("  %-14s %-8s %-7s %-5s %s%n", g, v == null ? "–" : v[0], v == null || v[1] == 0 ? "fehlt" : "ok", v == null ? "–" : v[2] - 44, ok ? "" : "FEHLER");
            }
            for (String[] s : SHADER)
                for (int i = 1; i < s.length; i++)
                    if (z.getEntry("com/dan/" + s[0] + "/" + s[i]) == null) { fehler++; System.out.println("  FEHLER: com/dan/" + s[0] + "/" + s[i] + " fehlt"); }
            if (fehler > 0) { System.out.println("check-packages: " + fehler + " Fehler – Renderer neu bauen (Clean and Build) und dist/Renderer.jar nach lib/ kopieren"); System.exit(1); }
            System.out.println("check-packages: alle " + GEBRAUCHT.length + " gebrauchten Packages in " + jar.getName()
                    + ", dazu Kern, Darstellung, Shader, Vorführung und Prüfklasse von cloth, sail, wake, firework, route (seit Phase 1) und lamp (seit Phase 11), Rotbuche und Kiefer in tree (seit Phase 19).");
        }
    }
}
