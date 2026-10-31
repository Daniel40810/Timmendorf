package com.dan.timmendorf.tools;

import com.dan.timmendorf.scene.Daten;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * {@code daten/INHALT.txt}: jede Datei, die die Aufbereitung geschrieben hat, mit SHA-256 und Größe (Werkbuch, Abschnitt 5, F5 A).
 * Nicht darin: {@code rohdaten.properties} (Pfad dieses Rechners), {@code fehler.txt} und Protokolle. Die App prüft beim
 * Start nur, ob alles da ist und die Größen stimmen ({@link Daten}); {@code ant pruefung} rechnet die Prüfsummen nach.
 */
public final class Inhalt {
    private Inhalt() { }

    public static void main(String[] a) throws Exception {
        if (a.length > 0 && a[0].equals("pruefen")) {
            List<String> f = pruefen(new File("daten"));
            System.out.println(f.isEmpty() ? "INHALT.txt: alle Prüfsummen stimmen" : String.join("\n", f));
            System.exit(f.isEmpty() ? 0 : 1);
        }
        schreiben(new File("daten"), System.out::println);
    }

    static boolean dazu(Path rel) {
        String n = rel.toString().replace('\\', '/');
        return !n.equals(Daten.INHALT) && !n.equals("rohdaten.properties") && !n.equals("fehler.txt") && !n.endsWith(".log") && !n.startsWith(".");
    }

    public static void schreiben(File daten, java.util.function.Consumer<String> log) throws IOException {
        Path root = daten.toPath();
        List<Path> fs = new ArrayList<>();
        try (Stream<Path> st = Files.walk(root)) { st.filter(Files::isRegularFile).filter(p -> dazu(root.relativize(p))).sorted().forEach(fs::add); }
        StringBuilder sb = new StringBuilder();
        sb.append("# Timmendorf: Inhalt von daten/ (Werkbuch, Abschnitt 5). Geschrieben von com.dan.timmendorf.tools.Inhalt am ").append(java.time.LocalDateTime.now().withNano(0)).append('\n');
        sb.append("# Die App liest nur diese Dateien, nie C:\\Daten. Zeile: SHA-256  Bytes  Pfad\n");
        long sum = 0;
        for (Path p : fs) {
            long len = Files.size(p);
            sum += len;
            sb.append(sha(p)).append("  ").append(len).append("  ").append(root.relativize(p).toString().replace('\\', '/')).append('\n');
        }
        Files.writeString(new File(daten, Daten.INHALT).toPath(), sb.toString());
        log.accept(String.format(Locale.GERMAN, "INHALT.txt: %d Dateien, %.1f MB", fs.size(), sum / 1e6));
    }

    /** Was nicht stimmt (leer, wenn alle Prüfsummen passen). */
    public static List<String> pruefen(File daten) throws IOException {
        List<String> f = new ArrayList<>();
        File inh = new File(daten, Daten.INHALT);
        if (!inh.isFile()) { f.add(inh + " fehlt"); return f; }
        for (String l : Files.readAllLines(inh.toPath())) {
            if (l.isBlank() || l.startsWith("#")) continue;
            String[] a = l.split("  ", 3);
            Path p = new File(daten, a[2]).toPath();
            if (!Files.isRegularFile(p)) { f.add(a[2] + " fehlt"); continue; }
            if (Files.size(p) != Long.parseLong(a[1])) { f.add(a[2] + ": Größe " + Files.size(p) + " statt " + a[1]); continue; }
            if (!sha(p).equals(a[0])) f.add(a[2] + ": Prüfsumme weicht ab");
        }
        return f;
    }

    static String sha(Path p) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 20];
            try (InputStream in = Files.newInputStream(p)) {
                for (int r; (r = in.read(buf)) > 0; ) md.update(buf, 0, r);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) { throw new IOException(e); }
    }
}
