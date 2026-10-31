package com.dan.timmendorf.tools;

import com.dan.timmendorf.gl.Quality;
import com.dan.timmendorf.gl.Renderer;
import com.dan.timmendorf.scene.Menschen;
import com.dan.timmendorf.scene.SceneClock;
import com.dan.timmendorf.scene.Site;
import com.jogamp.opengl.GLCapabilities;
import com.jogamp.opengl.GLDrawableFactory;
import com.jogamp.opengl.GLOffscreenAutoDrawable;
import com.jogamp.opengl.GLProfile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Seit Phase 12: „Das Feuerwerk“ als Bildfolge (Fertig-Bedingung der Phase): der Abend des 1. August 2026 an der
 * Seebrücke in neun Bildern, von der Dämmerung mit den ersten Zuschauern über den Mondaufgang und das Feuerwerk bis zum
 * Rauch über der Bucht, wenn alle gehen. Ohne Fenster auf der Grafikkarte gerechnet wie {@link Tempo}; die Uhr springt von
 * Bild zu Bild, die Menschen gehen die Zwischenzeit in Schritten von 2 s, das Feuerwerk steht, wo es zu dieser Zeit wäre.
 * <pre>
 * Bildfolge [breite] [höhe] [MITTEL|HOCH|…] [ordner]
 * Bildfolge 1920 1080 HOCH docs/status/Feuerwerk
 * </pre>
 * Schreibt {@code 01_….png} bis {@code 09_….png} in den Ordner und die Übersicht {@code docs/status/Feuerwerk_Phase12.png}.
 */
public final class Bildfolge {
    private Bildfolge() { }

    /** Ein Bild: Uhrzeit (h), Standort (Art, Wert), Titel. */
    record Bild(double stunde, String ort, double wert, double hoehe, String titel) { }

    static final LocalDate TAG = LocalDate.of(2026, 8, 1);

    /** Die Folge [G: Standorte und Zeiten gewählt; der Mond ist gerechnet]. */
    static final List<Bild> FOLGE = List.of(
            new Bild(22 + 5 / 60.0, "landende", 0, 1.7, "22:05 · Die Promenade nach Sonnenuntergang, die ersten Zuschauer"),
            new Bild(22 + 20 / 60.0, "mond", -350, 1.7, "22:20 · Der Mond geht über der Bucht auf"),
            new Bild(22 + 40 / 60.0, "strand", 320, 1.7, "22:40 · Am Strand: alle schauen zum Ponton"),
            new Bild(22 + 45 / 60.0 + 25 / 3600.0, "strand", 320, 1.7, "22:45 · Die ersten Bomben"),
            new Bild(22 + 46 / 60.0 + 40 / 3600.0, "schleife", 0, 4.3, "22:46 · Von der Schleife der Seebrücke"),
            new Bild(22 + 48 / 60.0, "strand", 600, 1.7, "22:48 · Spiegelung in der Bucht"),
            new Bild(22 + 49 / 60.0 + 20 / 3600.0, "hoch", 0, 0, "22:49 · Über Timmendorfer Strand: Feuerwerk und Lichter der Stadt"),
            new Bild(22 + 50 / 60.0 + 20 / 3600.0, "strand", 320, 1.7, "22:50 · Das Finale"),
            new Bild(22 + 51 / 60.0 + 30 / 3600.0, "landende", 0, 1.7, "22:51 · Rauch über der Bucht, die Zuschauer gehen"));

    public static void main(String[] a) throws Exception {
        int w = a.length > 0 ? Integer.parseInt(a[0]) : 1920, h = a.length > 1 ? Integer.parseInt(a[1]) : 1080;
        Quality q = a.length > 2 ? Quality.valueOf(a[2]) : Quality.HOCH;
        File dir = new File(a.length > 3 ? a[3] : "docs/status/Feuerwerk");
        dir.mkdirs();

        GLProfile prof = GLProfile.get(GLProfile.GL4);
        GLCapabilities caps = new GLCapabilities(prof);
        caps.setOnscreen(false);
        caps.setFBO(true);
        caps.setDepthBits(24);
        GLOffscreenAutoDrawable d = GLDrawableFactory.getFactory(prof).createOffscreenAutoDrawable(null, caps, null, w, h);
        d.setAutoSwapBufferMode(false);
        SceneClock clock = new SceneClock(TAG, FOLGE.get(0).stunde() - 0.25);
        clock.lapse = 0;
        Renderer r = new Renderer(clock);
        r.frozen = true;
        r.setQuality(q);
        r.autoScale = false;
        r.showHud = false;
        r.wiring.feuerwerkTag = TAG;
        r.wiring.feuerwerkAbends = true;
        d.addGLEventListener(r);
        d.display();   // init (startet den Faden der Physik)
        r.physik.stop();   // die Folge rechnet selbst, Schritt für Schritt
        Menschen m = r.wiring.menschen;
        double bisher = clock.epochMillis();
        List<BufferedImage> bilder = new ArrayList<>();
        int nr = 0;
        for (Bild b : FOLGE) {
            nr++;
            long t0 = System.nanoTime();
            // die Zwischenzeit: Menschen in Schritten von 2 s (mit dem Andrang dieser Zeit), Lichter schalten
            clock.set(TAG, b.stunde());
            double ziel = clock.epochMillis();
            for (double t = bisher; t < ziel; t += 2000) {
                clock.setEpochMillis(t);
                if (m != null) {
                    m.zuschauer = r.wiring.andrang();
                    m.step(2.0);
                }
            }
            clock.setEpochMillis(ziel);
            bisher = ziel;
            double lux = com.dan.timmendorf.wiring.Wiring.umgebungsLux(clock.sun()[0]);
            r.wiring.lichter.step(0, lux, r.wiring.stunde());
            r.wiring.leuchtfeuer.step(0, lux);
            // das Feuerwerk auf den Stand der Uhr
            for (int i = 0; i < 400; i++) {
                r.wiring.feuerwerkSchritt();
                double fz = r.wiring.feuerwerkZeit();
                if (r.wiring.firework == null || Math.abs(r.wiring.firework.time() - fz) < 0.02) break;
            }
            r.step(1 / 30.0);   // Himmel zur neuen Uhrzeit (Mond für den Blick)
            stelle(r, b);
            // Belichtung einschwingen (Uhr steht)
            for (int i = 0; i < 45; i++) { r.step(1 / 30.0); d.display(); }
            final BufferedImage[] img = new BufferedImage[1];
            d.invoke(true, x -> { img[0] = r.grab(x); return true; });
            File f = new File(dir, String.format(Locale.ROOT, "%02d_%s.png", nr, b.titel().substring(0, 5).replace(':', '-')));
            ImageIO.write(img[0], "png", f);
            bilder.add(beschriften(img[0], b.titel()));
            System.out.printf(Locale.GERMAN, "%s  %s  (%.1f s) · %s%n", f.getPath(), b.titel(), (System.nanoTime() - t0) / 1e9,
                    r.wiring.hullenZeile(r.camera.eye[0], r.camera.eye[2]).replaceAll("^.*Feuerwerk: ", "Feuerwerk: ").replaceAll(" · Knall.*$", ""));
            System.out.println("    " + r.abendZeile());
            if (m != null) System.out.println("    " + m.describe());
            System.out.println("    Durchgänge (ms): " + r.passTimes());
        }
        File sheet = new File("docs/status/Feuerwerk_Phase12.png");
        ImageIO.write(uebersicht(bilder), "png", sheet);
        System.out.println("Übersicht: " + sheet.getPath());
        if (r.error() != null) System.err.println("FEHLER: " + r.error());
        d.destroy();
        System.exit(r.error() == null ? 0 : 1);
    }

    /** Die Kamera für ein Bild. */
    static void stelle(Renderer r, Bild b) {
        switch (b.ort()) {
            case "strand", "landende" -> {
                double[] p = r.strandPlatz(b.wert());
                double pitch = b.ort().equals("landende") ? -4 : p[3];   // Neigung nach unten positiv
                r.camera.place(p[0], r.groundHeight(p[0], p[1]) + b.hoehe(), p[1], p[2], pitch);
            }
            case "mond" -> {
                double[] p = r.strandPlatz(b.wert());
                com.dan.sky.Sky.State st = r.wetter.state();
                double az = st != null ? st.moonAz : 100, alt = st != null ? st.moonAlt : 3;
                r.camera.place(p[0], r.groundHeight(p[0], p[1]) + b.hoehe(), p[1], az, -Math.max(2, alt + 1));
            }
            case "schleife" -> {
                double x = Site.SCHLEIFE.x(), z = Site.SCHLEIFE.z();
                double[] po = r.wiring.ponton;
                double dx = po[0] - x, dz = po[1] - z;
                r.camera.place(x, b.hoehe(), z, Math.toDegrees(Math.atan2(dx, -dz)), -24);
            }
            default -> {   // hoch über dem Hinterland, Blick über den Ort zur Bucht
                double[] po = r.wiring.ponton;
                double x = -700, z = 900;
                r.camera.place(x, 260, z, Math.toDegrees(Math.atan2(po[0] - x, -(po[1] - z))), 12);
            }
        }
    }

    static BufferedImage beschriften(BufferedImage im, String titel) {
        BufferedImage o = new BufferedImage(im.getWidth(), im.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = o.createGraphics();
        g.drawImage(im, 0, 0, null);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(14, im.getHeight() / 36)));
        g.setColor(new Color(0, 0, 0, 140));
        g.fillRect(0, im.getHeight() - im.getHeight() / 18, im.getWidth(), im.getHeight() / 18);
        g.setColor(new Color(235, 238, 242));
        g.drawString(titel, im.getHeight() / 60, im.getHeight() - im.getHeight() / 60);
        g.dispose();
        return o;
    }

    /** Drei mal drei Bilder mit Titel. */
    static BufferedImage uebersicht(List<BufferedImage> l) {
        return uebersicht(l, "Das Feuerwerk · Timmendorfer Strand, Samstag 1. August 2026 · Timmendorf Phase 12");
    }

    /** Drei mal drei Bilder mit Titel (seit Phase 13 mit eigener Überschrift). */
    static BufferedImage uebersicht(List<BufferedImage> l, String ueberschrift) {
        int tw = 640, th = 360, n = l.size(), cols = 3, rows = (n + cols - 1) / cols;
        BufferedImage o = new BufferedImage(cols * tw, rows * th + 40, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = o.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(11, 17, 24));
        g.fillRect(0, 0, o.getWidth(), o.getHeight());
        g.setColor(new Color(230, 234, 238));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        g.drawString(ueberschrift, 14, 27);
        for (int i = 0; i < n; i++) g.drawImage(l.get(i), (i % cols) * tw, 40 + (i / cols) * th, tw, th, null);
        g.dispose();
        return o;
    }
}
