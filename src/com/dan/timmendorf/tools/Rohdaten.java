package com.dan.timmendorf.tools;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Wo die Rohdaten liegen (Werkbuch, Abschnitt 5). Nur die Werkzeuge in {@code com.dan.timmendorf.tools} lesen sie, einmal
 * in Phase 2; die App nie. Der Pfad steht in {@code daten/rohdaten.properties} (nicht in Git, wird beim ersten Lauf mit
 * der Vorgabe angelegt); {@code -Dtimmendorf.rohdaten=…} setzt ihn für einen Lauf anders. Im Ordner liegen
 * {@code DGM1/}, {@code bDOM/}, {@code DOP20/}, {@code LOD2/}, {@code ALKIS/}, {@code Overpass/}, die GEBCO-ZIP und die
 * BSH-ZIP (Vorgabe C:\Daten\Timmendorf, nach dem Projekt verschoben).
 */
public final class Rohdaten {
    public static final File DATEI = new File("daten", "rohdaten.properties");
    public static final String VORGABE = "C:\\Daten\\Timmendorf";

    public final File ordner;

    private Rohdaten(File ordner) { this.ordner = ordner; }

    public File dgm1() { return new File(ordner, "DGM1"); }
    public File bdom() { return new File(ordner, "bDOM"); }
    public File dop20() { return new File(ordner, "DOP20"); }
    public File lod2() { return new File(ordner, "LOD2"); }
    public File alkis() { return new File(ordner, "ALKIS"); }
    public File overpass() { return new File(ordner, "Overpass"); }

    /** Die GEBCO-ZIP (gebco_2026_…geotiff.tif darin) oder null. */
    public File gebco() { return erste("GEBCO_", ".zip"); }

    /** Die BSH-ZIP von EMODnet (…luebeck_approach.emo.zip) oder null. */
    public File bsh() { return erste("1850_hrdtm", ".zip"); }

    private File erste(String anfang, String ende) {
        File[] fs = ordner.listFiles((d, n) -> n.startsWith(anfang) && n.toLowerCase(java.util.Locale.ROOT).endsWith(ende));
        if (fs == null || fs.length == 0) return null;
        java.util.Arrays.sort(fs);
        return fs[fs.length - 1];
    }

    /** Liest {@code daten/rohdaten.properties} (legt sie mit der Vorgabe an, wenn sie fehlt). */
    public static Rohdaten laden() throws IOException {
        Properties p = new Properties();
        if (DATEI.isFile()) try (InputStreamReader r = new InputStreamReader(new FileInputStream(DATEI), StandardCharsets.UTF_8)) { p.load(r); }
        else {
            File d = DATEI.getParentFile();
            if (!d.isDirectory() && !d.mkdirs()) throw new IOException("Ordner " + d + " lässt sich nicht anlegen");
            p.setProperty("rohdaten", VORGABE);
            try (Writer w = new OutputStreamWriter(new java.io.FileOutputStream(DATEI), StandardCharsets.UTF_8)) {
                p.store(w, " Timmendorf: wo die Rohdaten liegen (nur fuer com.dan.timmendorf.tools, Werkbuch Abschnitt 5). Nicht in Git.");
            }
            System.out.println("Angelegt: " + DATEI + " (rohdaten = " + VORGABE + ")");
        }
        return new Rohdaten(new File(System.getProperty("timmendorf.rohdaten", p.getProperty("rohdaten", VORGABE))));
    }

    /** Was fehlt (leer, wenn alles da ist); {@code schritte} wählt, was gebraucht wird. */
    public List<String> fehlt(List<String> schritte) {
        List<String> f = new ArrayList<>();
        if (!ordner.isDirectory()) { f.add("Ordner " + ordner + " fehlt (Pfad in " + DATEI + ")"); return f; }
        if (schritte.contains("gelaende")) {
            if (!dgm1().isDirectory()) f.add("Ordner " + dgm1() + " fehlt");
            if (gebco() == null) f.add("GEBCO_….zip fehlt in " + ordner);
        }
        if (schritte.contains("alkis") && !alkis().isDirectory()) f.add("Ordner " + alkis() + " fehlt");
        if (schritte.contains("bewuchs") && !bdom().isDirectory()) f.add("Ordner " + bdom() + " fehlt");
        if (schritte.contains("luftbild") && !dop20().isDirectory()) f.add("Ordner " + dop20() + " fehlt");
        if (schritte.contains("bauten") && !lod2().isDirectory()) f.add("Ordner " + lod2() + " fehlt");
        if ((schritte.contains("netz") || schritte.contains("bauwerke")) && !overpass().isDirectory()) f.add("Ordner " + overpass() + " fehlt");
        return f;
    }

    @Override public String toString() {
        return "Rohdaten " + ordner + " · GEBCO " + (gebco() == null ? "fehlt" : gebco().getName()) + " · BSH " + (bsh() == null ? "fehlt" : bsh().getName());
    }
}
