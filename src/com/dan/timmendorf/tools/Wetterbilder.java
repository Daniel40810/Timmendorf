package com.dan.timmendorf.tools;

import com.dan.timmendorf.camera.Ansichten;
import com.dan.timmendorf.gl.Quality;
import com.dan.timmendorf.gl.Renderer;
import com.dan.timmendorf.scene.SceneClock;
import com.dan.timmendorf.wiring.Wetterregie;
import com.jogamp.opengl.GLCapabilities;
import com.jogamp.opengl.GLDrawableFactory;
import com.jogamp.opengl.GLOffscreenAutoDrawable;
import com.jogamp.opengl.GLProfile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Seit Phase 13: „Schlechtwetter und Winter als Bilder“ (Fertig-Bedingung der Phase): neun Bilder der Wetterlagen und des
 * Winters an der Bucht, ohne Fenster auf der Grafikkarte gerechnet wie {@link Tempo} und {@link Bildfolge}. Der Wintertag
 * kommt aus dem gewürfelten Jahr ({@code com.dan.season}): der Tag der Winter 2027 bis 2030 mit dem meisten Schnee und Eis.
 * <pre>
 * Wetterbilder [breite] [höhe] [MITTEL|HOCH|…] [ordner]
 * </pre>
 * Schreibt die Bilder in den Ordner und die Übersicht {@code docs/status/Wetter_Phase13.png}.
 */
public final class Wetterbilder {
    private Wetterbilder() { }

    /** Ein Bild: Datum, Uhrzeit, Lage, Ansicht (Name) oder Strandplatz (Zahl als Text), Blick (NaN = wie die Ansicht), Titel. */
    record Bild(LocalDate tag, double stunde, Wetterregie.Lage lage, String ort, double blick, double neigung, boolean blitz, String titel) { }

    public static void main(String[] a) throws Exception {
        int w = a.length > 0 ? Integer.parseInt(a[0]) : 1920, h = a.length > 1 ? Integer.parseInt(a[1]) : 1080;
        Quality q = a.length > 2 ? Quality.valueOf(a[2]) : Quality.HOCH;
        File dir = new File(a.length > 3 ? a[3] : "docs/status/Wetter");
        dir.mkdirs();
        LocalDate[] winter = winter();
        String wt = String.format(Locale.GERMAN, "%td.%tm.%tY", winter[0], winter[0], winter[0]), et = String.format(Locale.GERMAN, "%td.%tm.%tY", winter[1], winter[1], winter[1]);
        List<Bild> folge = List.of(
                new Bild(LocalDate.of(2026, 7, 14), 15.0, Wetterregie.Lage.LANDREGEN, "Promenade", Double.NaN, Double.NaN, false, "14.7. 15:00 · Landregen an der Promenade"),
                new Bild(LocalDate.of(2026, 8, 20), 16.5, Wetterregie.Lage.SCHAUER, "strand:300", 40, -4, false, "20.8. 16:30 · Schauer über der Bucht"),
                new Bild(LocalDate.of(2026, 7, 22), 21.6, Wetterregie.Lage.GEWITTER, "strand:-200", 70, -8, true, "22.7. 21:36 · Gewitter über der Bucht"),
                new Bild(LocalDate.of(2026, 6, 10), 6.5, Wetterregie.Lage.SEENEBEL, "Seebrücke", Double.NaN, Double.NaN, false, "10.6. 6:30 · Seenebel an der Seebrücke"),
                new Bild(LocalDate.of(2026, 10, 24), 14.0, Wetterregie.Lage.HERBSTSTURM, "strand:150", 20, -2, false, "24.10. 14:00 · Herbststurm aus Nordwest"),
                new Bild(LocalDate.of(2026, 7, 25), 18.5, Wetterregie.Lage.SOMMERABEND, "Niendorfer Hafen", Double.NaN, Double.NaN, false, "25.7. 18:30 · Möwen und Schwäne am Niendorfer Hafen"),
                new Bild(winter[0], 11.0, Wetterregie.Lage.WINTERTAG, "strand:250", 60, -1, false, wt + " 11:00 · Schnee am Strand"),
                new Bild(winter[1], 13.0, Wetterregie.Lage.WINTERTAG, "Seebrücke", Double.NaN, Double.NaN, false, et + " 13:00 · Eis in der Bucht"),
                new Bild(winter[0].minusDays(1), 10.0, Wetterregie.Lage.SCHNEE, "Promenade", Double.NaN, Double.NaN, false, String.format(Locale.GERMAN, "%td.%tm.%tY 10:00 · Schneefall",
                        winter[0].minusDays(1), winter[0].minusDays(1), winter[0].minusDays(1))));

        GLProfile prof = GLProfile.get(GLProfile.GL4);
        GLCapabilities caps = new GLCapabilities(prof);
        caps.setOnscreen(false);
        caps.setFBO(true);
        caps.setDepthBits(24);
        GLOffscreenAutoDrawable d = GLDrawableFactory.getFactory(prof).createOffscreenAutoDrawable(null, caps, null, w, h);
        d.setAutoSwapBufferMode(false);
        SceneClock clock = new SceneClock(folge.get(0).tag(), folge.get(0).stunde());
        clock.lapse = 0;
        Renderer r = new Renderer(clock);
        r.frozen = true;
        r.setQuality(q);
        r.autoScale = false;
        r.showHud = false;
        r.wiring.feuerwerkAbends = false;
        d.addGLEventListener(r);
        d.display();
        r.physik.stop();
        List<BufferedImage> bilder = new ArrayList<>();
        int nr = 0;
        for (Bild b : folge) {
            nr++;
            long t0 = System.nanoTime();
            clock.set(b.tag(), b.stunde());
            r.wiring.regie.lage(b.lage());
            r.wiring.jahr.step();
            double lux = com.dan.timmendorf.wiring.Wiring.umgebungsLux(clock.sun()[0]);
            r.wiring.lichter.step(0, lux, r.wiring.stunde());
            r.wiring.leuchtfeuer.step(0, lux);
            stelle(r, b);
            // Wolken, Regen, Nebel und Tiere einschwingen lassen: 20 s Szenenzeit, die Uhr steht dabei nur für das Licht
            clock.lapse = 1;
            for (int i = 0; i < 200; i++) {
                r.step(0.1);
                if (b.blitz() && i == 170) r.unwetter.blitzVonHand(r.camera.eye.clone(), r.camera.fwd.clone(), false);
            }
            clock.lapse = 0;
            for (int i = 0; i < 40; i++) { r.step(1 / 30.0); d.display(); }
            if (b.blitz()) { r.unwetter.blitzVonHand(r.camera.eye.clone(), r.camera.fwd.clone(), false); clock.lapse = 1; for (int i = 0; i < 4; i++) { r.step(0.05); d.display(); } clock.lapse = 0; }
            final BufferedImage[] img = new BufferedImage[1];
            d.invoke(true, x -> { img[0] = r.grab(x); return true; });
            File f = new File(dir, String.format(Locale.ROOT, "%02d_%s.png", nr, b.lage().name().toLowerCase(Locale.ROOT)));
            ImageIO.write(img[0], "png", f);
            bilder.add(Bildfolge.beschriften(img[0], b.titel()));
            System.out.printf(Locale.GERMAN, "%s  %s  (%.1f s)%n    %s%n", f.getPath(), b.titel(), (System.nanoTime() - t0) / 1e9, r.wiring.wetterZeile());
            for (String l : r.unwetter.describe(r.camera.eye)) System.out.println("    " + l);
            System.out.println("    " + r.wiring.jahr.describe());
            System.out.println("    " + r.leben.describe());
            System.out.println("    Durchgänge (ms): " + r.passTimes());
        }
        File sheet = new File("docs/status/Wetter_Phase13.png");
        ImageIO.write(Bildfolge.uebersicht(bilder, "Wetter, Jahr, Leben · Timmendorfer Strand · Timmendorf Phase 13"), "png", sheet);
        System.out.println("Übersicht: " + sheet.getPath());
        if (r.error() != null) System.err.println("FEHLER: " + r.error());
        d.destroy();
        System.exit(r.error() == null ? 0 : 1);
    }

    /** Der Wintertag mit dem meisten Schnee und der Tag mit dem meisten Eis aus den Wintern 2027 bis 2030 (gleicher Startwert wie die App). */
    public static LocalDate[] winter() {
        LocalDate schnee = LocalDate.of(2027, 1, 10), eis = LocalDate.of(2027, 1, 5);
        double bs = 0, be = 0;
        for (int y = 2027; y <= 2030; y++) {
            com.dan.season.Year yr = new com.dan.season.Year(com.dan.season.Climate.schleswig());
            yr.seed = 2026;
            List<com.dan.season.Year.State> l = yr.trace(y, 11.0);
            for (int i = 0; i < l.size(); i++) {
                int dd = com.dan.season.Year.FIRST + i;
                if (dd < -40 || dd > 75) continue;
                com.dan.season.Year.State s = l.get(i);
                LocalDate t = LocalDate.of(y, 1, 1).plusDays(dd - 1);
                double sn = s.snowDepth() * s.snowCover();
                if (sn > bs) { bs = sn; schnee = t; }
                if (s.ice() > be) { be = s.ice(); eis = t; }
            }
        }
        return new LocalDate[]{schnee, eis};
    }

    static void stelle(Renderer r, Bild b) {
        if (b.ort().startsWith("strand:")) {
            double[] p = r.strandPlatz(Double.parseDouble(b.ort().substring(7)));
            r.camera.place(p[0], r.groundHeight(p[0], p[1]) + 1.7, p[1], b.blick(), b.neigung());
            return;
        }
        Ansichten.Ansicht v = Ansichten.byName(b.ort());
        if (v != null) r.placeView(v);
    }
}
