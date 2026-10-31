package com.dan.timmendorf.tools;

import com.dan.timmendorf.camera.Ansichten;
import com.dan.timmendorf.gl.Quality;
import com.dan.timmendorf.gl.Renderer;
import com.dan.timmendorf.scene.SceneClock;
import com.jogamp.opengl.GLCapabilities;
import com.jogamp.opengl.GLDrawableFactory;
import com.jogamp.opengl.GLOffscreenAutoDrawable;
import com.jogamp.opengl.GLProfile;

import java.io.File;
import java.time.LocalDate;
import java.util.Locale;

/**
 * Tempo-Probe und Standbild ohne Fenster (aus Sylt und Brocken, entschlackt). Rechnet die Szene unsichtbar in fester Größe,
 * misst die Wanduhr je Bild bis glFinish und die Zeit jedes Durchgangs auf der Grafikkarte und schreibt auf Wunsch ein
 * PNG. So lässt sich jede Phase vorher und nachher vergleichen und im Leistungsbudget (Werkbuch, Abschnitt 14)
 * eintragen.
 * <pre>
 * Tempo [bilder] [breite] [höhe] [png|-] [NIEDRIG|MITTEL|HOCH|ULTRA] [uhrzeit] [datum JJJJ-MM-TT]
 * Tempo 120 1920 1080 docs/status/Status_Phase1.png MITTEL 19.5 2026-07-25
 * </pre>
 * Schalter (als -D… aus Ant oder als Umgebungsvariable in Großbuchstaben): {@code ansicht} = Name oder Taste einer
 * Ansicht (Seebruecke, Schleife, Strandkoerbe, Spuelsaum, Promenade, NiendorferHafen, BrodtenerUfer, HemmelsdorferSee,
 * Vogelschau),
 * {@code kamera} = x,z,Richtung,Neigung,Höhe über Grund (frei stehend), {@code hud} = kurz|voll|aus,
 * {@code marken} = an|aus (in Phase 1 an), {@code meer} = aus, {@code pegel} = m über NHN (von Hand), seit Phase 20
 * {@code sandsturm} = an (Flugsand bei Bft 9 auflandig, Blick zwischen den Körben).
 */
public final class Tempo {
    private Tempo() { }

    private static String opt(String prop) {
        String v = System.getProperty(prop);
        return v != null ? v : System.getenv(prop.toUpperCase(Locale.ROOT));
    }

    public static void main(String[] a) throws Exception {
        int frames = a.length > 0 ? Integer.parseInt(a[0]) : 120;
        int w = a.length > 1 ? Integer.parseInt(a[1]) : 1920, h = a.length > 2 ? Integer.parseInt(a[2]) : 1080;
        // „auto“: docs/status/Status_Phase<N>.png nach dem Stand (mit Ansicht: Status_Phase<N>_<ansicht>.png)
        String av = opt("ansicht");
        File png = a.length > 3 && !a[3].equals("-")
                ? (a[3].equals("auto") ? new File("docs/status/Status_Phase" + Renderer.PHASE + (av == null || av.isBlank() ? "" : "_" + av.replaceAll("[^A-Za-z0-9]+", "")) + ".png") : new File(a[3]))
                : null;
        Quality q = a.length > 4 ? Quality.valueOf(a[4]) : Quality.MITTEL;
        double hour = a.length > 5 ? Double.parseDouble(a[5].replace(',', '.')) : SceneClock.START_HOUR;
        LocalDate day = a.length > 6 && !a[6].equals("-") ? LocalDate.parse(a[6]) : SceneClock.START_DATE;

        GLProfile prof = GLProfile.get(GLProfile.GL4);
        GLCapabilities caps = new GLCapabilities(prof);
        caps.setOnscreen(false);
        caps.setFBO(true);
        caps.setDepthBits(24);
        GLOffscreenAutoDrawable d = GLDrawableFactory.getFactory(prof).createOffscreenAutoDrawable(null, caps, null, w, h);
        d.setAutoSwapBufferMode(false);
        SceneClock clock = new SceneClock(day, hour);
        clock.lapse = 0;
        Renderer r = new Renderer(clock);
        r.frozen = true;
        r.setQuality(q);
        r.autoScale = false;
        String hud = opt("hud");
        r.showHud = !"aus".equals(hud);   // seit Phase 19 ist das HUD beim Start aus; Tempo zeigt es wie bisher, außer mit hud=aus
        if ("voll".equals(hud)) r.hudFull = true;
        if ("aus".equals(opt("marken"))) r.showMarks = false;
        if ("an".equals(opt("marken"))) r.showMarks = true;
        if ("aus".equals(opt("meer"))) r.showMeer = false;
        String pg = opt("pegel");
        if (pg != null && !pg.isBlank()) r.wiring.pegel = Double.parseDouble(pg.replace(',', '.'));
        String an = opt("ansicht"), ka = opt("kamera");
        if (ka != null && !ka.isBlank()) {
            String[] p = ka.split(",");
            double x = Double.parseDouble(p[0]), z = Double.parseDouble(p[1]);
            double hd = p.length > 2 ? Double.parseDouble(p[2]) : 0, pt = p.length > 3 ? Double.parseDouble(p[3]) : 10, up = p.length > 4 ? Double.parseDouble(p[4]) : 2;
            r.camera.place(x, Math.max(r.groundHeight(x, z), r.wiring.pegel) + up, z, hd, pt);
        } else if (an != null && !an.isBlank()) {
            Ansichten.Ansicht v = Ansichten.byName(an);
            if (v == null) System.err.println("FEHLER: keine Ansicht „" + an + "“");
            else r.placeView(v);
        }
        // seit Phase 20: -Dsandsturm=an stellt „Sandsturm am Strand“ (Bft 9 auflandig, Blick zwischen den Körben) und rechnet
        // Windfeld und Flugsand vor dem ersten Bild
        boolean sturm = "an".equals(opt("sandsturm"));
        if (sturm) r.sandsturm();
        d.addGLEventListener(r);
        d.display();   // init
        if (sturm) r.sandJetzt();
        for (int i = 0; i < 20; i++) { r.step(1 / 60.0); d.display(); }
        // seit Phase 16: -Dregiebuch=Name[,Name…] oder alle läuft Regiebücher ohne Fenster bis zum Ende (Schritte von 0,1 s);
        // Bilder nur für Fotos und Schnitte (davor 12 Bilder zum Einschwingen), am Ende das Blatt in docs/status
        String rb = opt("regiebuch");
        if (rb != null && !rb.isBlank()) {
            r.physik.stop();
            r.klang.offline = true;   // Ereignisse ohne Lautsprecher (sie bleiben stumm)
            java.util.List<com.dan.timmendorf.scene.Regie.Book> buecher = new java.util.ArrayList<>();
            if (rb.equalsIgnoreCase("alle")) buecher.addAll(com.dan.timmendorf.scene.Regie.all());
            else for (String n : rb.split("[,+]")) {
                com.dan.timmendorf.scene.Regie.Book b = com.dan.timmendorf.scene.Regie.byName(n.trim());
                if (b == null) { System.err.println("FEHLER: kein Regiebuch „" + n + "“"); System.exit(1); }
                buecher.add(b);
            }
            boolean ok = true, kino = "an".equals(opt("kino"));
            com.dan.timmendorf.gl.Regisseur rg = r.regisseur;
            for (com.dan.timmendorf.scene.Regie.Book b : buecher) {
                rg.lastSheet = null;
                if (kino) rg.kino(b); else rg.start(b);
                r.frozen = true;
                long t0 = System.nanoTime();
                int schritte = 0, bilder = 0;
                double letzte = -30;
                while (rg.regie.running() || rg.wantsFrame() || (kino && r.kino.laeuft())) {
                    r.step(0.1);
                    r.physik.drain();
                    schritte++;
                    if ((rg.wantsFrame() && !rg.fotoWartet()) || (kino && schritte % 5 == 0)) {
                        rg.fotoAufschub = true;
                        r.wetter.sky.updateNow();
                        for (int i = 0; i < 12; i++) { r.step(1 / 60.0); r.physik.drain(); d.display(); }
                        rg.fotoAufschub = false;
                        d.display();
                        bilder++;
                    }
                    if (rg.regie.time() - letzte >= 30) { letzte = rg.regie.time(); System.out.println("  " + rg.zeile() + " · " + clock.label()); }
                    if (schritte > 200_000) { System.err.println("FEHLER: Regiebuch endet nicht"); break; }
                }
                System.out.printf(Locale.GERMAN, "Regiebuch „%s“%s: %d Schritte, %d Bilder, %.0f s gewartet, %d Mal ausgewichen, %d Fotos, %.0f s Rechenzeit · Blatt: %s%n", b.name(),
                        kino ? " im Kino" : "", schritte, bilder, rg.regie.waitedTotal(), rg.regie.ausgewichen, rg.fotosVerlangt, (System.nanoTime() - t0) / 1e9, rg.lastSheet);
                ok &= rg.lastSheet != null && rg.regie.ausgewichen == 0;
            }
            if (r.error() != null) System.err.println("FEHLER: " + r.error());
            d.destroy();
            System.exit(ok && r.error() == null ? 0 : 1);
        }
        double best = Double.MAX_VALUE, sum = 0;
        for (int i = 0; i < frames; i++) {
            long t0 = System.nanoTime();
            r.step(1 / 60.0);
            d.display();
            d.getContext().makeCurrent();
            d.getGL().glFinish();
            d.getContext().release();
            double ms = (System.nanoTime() - t0) / 1e6;
            best = Math.min(best, ms);
            sum += ms;
        }
        if (r.error() != null) System.err.println("FEHLER: " + r.error());
        System.out.println(r.glInfo());
        System.out.println(r.compute() == null ? "Compute-Test: nicht gelaufen" : r.compute().line());
        System.out.printf(Locale.GERMAN, "%d×%d %s, %s: bestes Bild %.2f ms, Mittel %.2f ms (%.0f Bilder/s)%n", w, h, q.label, clock.label(), best, sum / frames, 1000 / (sum / frames));
        System.out.println("Durchgänge (ms): " + r.passTimes());
        for (String l : r.hudLines()) System.out.println("  " + l);
        if (png != null) {
            File dir = png.getAbsoluteFile().getParentFile();
            if (dir != null) dir.mkdirs();
            final java.awt.image.BufferedImage[] img = new java.awt.image.BufferedImage[1];
            d.invoke(true, x -> { img[0] = r.grab(x); return true; });
            javax.imageio.ImageIO.write(img[0], "png", png);
            System.out.println("Bild: " + png.getPath());
        }
        d.destroy();
        System.exit(r.error() == null ? 0 : 1);
    }
}
