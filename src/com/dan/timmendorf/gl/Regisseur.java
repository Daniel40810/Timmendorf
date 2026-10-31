package com.dan.timmendorf.gl;

import com.dan.timmendorf.camera.Ansichten;
import com.dan.timmendorf.camera.Camera;
import com.dan.timmendorf.camera.Fahrt;
import com.dan.timmendorf.scene.Bauwerke;
import com.dan.timmendorf.scene.Flotte;
import com.dan.timmendorf.scene.Regie;
import com.dan.timmendorf.scene.Site;
import com.dan.timmendorf.wiring.Wetterregie;
import com.jogamp.opengl.GL4;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Phase 16: Regie und Kino (aus Sylt Phase 15). Führt die Stichworte der Regiebücher ({@link Regie}) in der Szene aus:
 * Uhr, Zeitraffer, Wetterlage, Sturmflut, Feuerwerk, Regatta, Ansichten und Fahrten; sammelt die Fotos und schreibt am
 * Ende das Blatt nach {@code docs/status}. Im Kino ({@code com.dan.kino}) laufen Schnitte über die Überblendung, die
 * Kamera schwenkt sacht, der Ton folgt dem Schwarz, und am Ende kommt der Abspann.
 * <p>
 * Fahrten: Seebrücke (vom Landende auf dem Deck hinaus bis zur Schleife, 40 s), Promenade (300 m zu Fuß, 50 s), Drohne
 * (vor der Küste von Niendorf bis Scharbeutz, 90 m hoch, 45 s), Möwe (hinter der nächsten fliegenden Möwe), Optimist (im
 * Boot), Begleitboot (neben dem Optimisten), Regatta (Begleitboot an der Spitze), Weg (über das Fußnetz von {@link #wegStart}
 * zum Landende). Zusätzliche Blicke: Sonnenaufgang, Mondaufgang, Feuerwerk, Startschiff, Sturmflut Strand, Sturmflut
 * Promenade [G].
 */
public final class Regisseur {
    private final Renderer r;
    public final Regie regie = new Regie();
    private final Ctx ctx = new Ctx();
    private com.dan.kino.gl.KinoGl kinoGl;
    private final List<Runnable> kinoSchnitte = new ArrayList<>();
    private double kinoYaw, kinoTonBasis = Double.NaN;
    private boolean kinoFensterAn, tonVorher;
    private Regie.Book kinoBuch;
    /** true, wenn das Kino beginnt (Bedienfeld weg, Vollbild), false am Ende; im Bildlauf gerufen. */
    public volatile Consumer<Boolean> kinoFenster = an -> { };
    private volatile String captureCaption, sheetName, msg = "";
    private final List<BufferedImage> album = new ArrayList<>();
    private final List<String> albumCaps = new ArrayList<>();
    private double albumFps, albumGpu;
    private int albumSamples;
    /** Das zuletzt geschriebene Blatt (für Tempo und die Prüfung). */
    public volatile File lastSheet;
    /** Seit Phase 19: zählt jeden Sprung und Schnitt des Regiebuchs (Uhr, Ansicht, Fahrt, Sturmflut, Feuerwerk, Regatta, Lage, Foto, Blatt); der Dauerlauf misst danach mit Nachsicht. */
    public volatile int ereignisse;
    /** Ohne Blatt (Prüfung); Fotos noch nicht abholen (Tempo: erst einschwingen). */
    public volatile boolean ohneBlatt, fotoAufschub;
    public int fotosVerlangt;
    /**
     * Wo „Mein Weg zum Strand“ beginnt: ein Name aus {@code daten/netz/poi.tsv} (Teil genügt) oder „Ost Nord“ in UTM.
     * Wird nicht gespeichert. Leer: der Bahnhof Timmendorfer Strand (oder 900 m landeinwärts vom Landende).
     */
    public volatile String wegStart = "";
    public volatile String wegInfo = "";
    private boolean fwGesehen;

    Regisseur(Renderer r) { this.r = r; }

    // ------------------------------------------------------------------ Start und Stopp

    /** Ein Regiebuch von vorn (das Album beginnt leer). */
    public void start(Regie.Book b) {
        album.clear();
        albumCaps.clear();
        albumFps = albumGpu = 0;
        albumSamples = 0;
        fotosVerlangt = 0;
        msg = "";
        fwGesehen = false;
        r.frozen = false;
        regie.start(b, ctx);
    }

    public void stop() {
        regie.stop();
        if (r.kino.laeuft()) r.kino.abbrechen();
    }

    /** Ein Regiebuch im Kino: Vorspann mit Titel und Beschreibung, Ton an, am Ende der Abspann. */
    public void kino(Regie.Book b) {
        r.frozen = false;
        regie.beiText = r.kino::untertitel;
        kinoBuch = b;
        tonVorher = r.klang.on;
        if (!r.klang.offline) r.klang.setOn(true);
        r.kino.beginnen("Timmendorfer Strand · Das Projekt des Segels", b.name(), b.about());
        start(b);
    }

    /** Die Zeilen des Abspanns (erste Zeile groß). */
    List<String> abspann(Regie.Book b) {
        List<String> z = new ArrayList<>();
        z.add(b != null ? b.name() : "Timmendorfer Strand");
        z.add("Ort: die Lübecker Bucht von Scharbeutz bis Travemünde");
        z.add("Szene: " + r.clock.dateLabel());
        z.add("Gelände: DGM1, DOP20, LOD2 und ALKIS © GeoBasis-DE/LVermGeo SH; Wege © OpenStreetMap-Mitwirkende");
        z.add("Segel, Tuch, Kielwasser, Feuerwerk, Wege, Licht und Klang: com.dan");
        if (!album.isEmpty()) z.add("Fotos: " + album.size() + " auf dem Blatt in docs/status");
        z.add("Timmendorfer Strand · Das Projekt des Segels · Phase 16");
        return z;
    }

    // ------------------------------------------------------------------ je Schritt und je Bild

    /** Je Schritt (vor der Uhr): fällige Stichworte, dann das Kino. */
    void schritt(double dt) {
        regie.step(dt, ctx);
        if (r.wiring.firework != null) fwGesehen = true;
        kinoSchritt(dt);
    }

    /** Ein Schnitt (Ansicht, Fahrt). Im Kino wartet er auf das Merken des Bilds für die Überblendung. */
    private void schnitt(Runnable x) {
        if (r.kino.schnitt()) kinoSchnitte.add(x);
        else { x.run(); kinoYaw = 0; }
    }

    private void schnitteJetzt() {
        if (kinoSchnitte.isEmpty()) return;
        List<Runnable> l = new ArrayList<>(kinoSchnitte);
        kinoSchnitte.clear();
        for (Runnable x : l) x.run();
        kinoYaw = 0;
    }

    /** Kino weiter, Abspann am Ende des Regiebuchs, Schwenk und Ton. */
    private void kinoSchritt(double dt) {
        com.dan.kino.Kino kino = r.kino;
        com.dan.kino.Kino.Lage l = kino.lage();
        if ((l == com.dan.kino.Kino.Lage.VORSPANN || l == com.dan.kino.Kino.Lage.FILM) && !regie.running() && !wantsFrame())
            kino.beenden(abspann(kinoBuch));
        kino.schritt(l == com.dan.kino.Kino.Lage.VORSPANN && regie.waiting() != null ? 0 : dt);
        if (!kino.laeuft() && !kinoSchnitte.isEmpty() && !kino.schnittFaellig()) schnitteJetzt();
        // der Schwenk: nur bei stehender Kamera, als Änderung der Blickrichtung
        Camera c = r.camera;
        double s = kino.schwenk();
        if (c.fahrt == null && c.mitfahrt == null && kinoSchnitte.isEmpty()) {
            double d = s - kinoYaw;
            if (c.mode == Camera.Mode.FREI) c.fyaw += d; else c.yaw += d;
        }
        kinoYaw = s;
        // der Ton folgt dem Schwarz
        com.dan.soundscape.Soundscape ss = r.klang.s;
        if (kino.sichtbar()) {
            if (Double.isNaN(kinoTonBasis)) kinoTonBasis = ss.volume;
            ss.volume = kinoTonBasis * kino.ton();
        } else if (!Double.isNaN(kinoTonBasis)) {
            ss.volume = kinoTonBasis;
            kinoTonBasis = Double.NaN;
            if (!tonVorher && !r.klang.offline) r.klang.setOn(false);
        }
    }

    /** Ohne Bild (Prüfung): Schnitte ausführen, Fotos und Blatt verwerfen. */
    public void bildlos() {
        if (r.kino.schnittFaellig()) { schnitteJetzt(); r.kino.geschnitten(); }
        if (captureCaption != null) { captureCaption = null; albumCaps.add("ohne Bild"); }
        sheetName = null;
    }

    private double fotoWartetSeit = Double.NaN;

    /**
     * Seit Phase 17 (Befund: zwei Fotos des Feuerwerks ohne Bombe): läuft das Feuerwerk und brennt gerade kein Stern,
     * wartet ein verlangtes Foto bis zu 8 s Szenenzeit auf die nächste Zerlegung. Tempo zeichnet so lange nicht.
     */
    public boolean fotoWartet() {
        if (captureCaption == null) { fotoWartetSeit = Double.NaN; return false; }
        com.dan.firework.Firework fw = r.wiring.firework;
        double ft = r.wiring.feuerwerkZeit();
        if (fw == null || !(ft >= 0 && ft < 420) || !fw.lights().isEmpty()) return false;
        double now = r.clock.epochMillis() / 1000.0;
        if (Double.isNaN(fotoWartetSeit)) fotoWartetSeit = now;
        return now - fotoWartetSeit < 8;
    }

    /** Ob ein Foto oder das Blatt auf das nächste Bild wartet (Tempo zeichnet dann sofort). */
    public boolean wantsFrame() { return captureCaption != null || sheetName != null || r.kino.schnittFaellig(); }

    /** Ob gerade ein Foto genommen wird (dann ohne HUD). */
    boolean fotoJetzt() { return captureCaption != null && !fotoAufschub; }

    /** „Regie: Ein Tag am Strand · 22:45: das Feuerwerk · 3:50 von 5:50 · 9 Fotos“ (HUD). */
    public String zeile() {
        return "Regie: " + regie.describe() + (album.isEmpty() ? "" : " · " + album.size() + " Fotos") + (msg.isEmpty() ? "" : " · " + msg);
    }

    void initGl(GL4 gl) {
        try {
            com.dan.kino.KinoMaler km = new com.dan.kino.KinoMaler();
            km.kopfFarbe = new java.awt.Color(150, 196, 210);
            kinoGl = new com.dan.kino.gl.KinoGl(r.kino, km);
            kinoGl.init(gl);
        } catch (RuntimeException | LinkageError e) {
            System.err.println("Timmendorf: Kino nicht verfügbar (ohne Balken und Blenden): " + e.getMessage());
            kinoGl = null;
        }
    }

    void dispose(GL4 gl) { if (kinoGl != null) kinoGl.dispose(gl); }

    /** Nach dem fertigen Bild: Fotos und Blatt (ohne HUD und Balken), dann das Kino darüber; Fenster auf und zu. */
    void bild(GL4 gl, int fbo, int w, int h, long now) {
        if (!fotoAufschub) {
            String cap = captureCaption;
            if (cap != null && !fotoWartet()) {
                captureCaption = null;
                fotoWartetSeit = Double.NaN;
                ereignisse++;
                album.add(r.readDefault(gl, w, h));
                albumCaps.add(r.clock.dateLabel() + " " + r.clock.timeLabel() + " · " + cap + "\n" + String.format(Locale.GERMAN, "%.0f Bilder/s · Grafikkarte %.1f ms", r.fps(), r.gpuMs()));
            }
            if (regie.running()) { albumFps += r.fps(); albumGpu += r.gpuMs(); albumSamples++; }
            String sn = sheetName;
            if (sn != null) { sheetName = null; ereignisse++; blatt(sn); }
        }
        com.dan.kino.Kino kino = r.kino;
        if (kinoGl != null && kinoGl.bereit()) {
            try {
                if (kino.schnittFaellig()) {
                    kinoGl.merken(gl, fbo, w, h);
                    schnitteJetzt();
                    kino.geschnitten();
                }
                kinoGl.zeichnen(gl, fbo, w, h, now / 1e9);
            } catch (RuntimeException e) {
                System.err.println("Timmendorf: Kino: " + e);
                kinoGl = null;
            }
        } else if (kino.schnittFaellig()) { schnitteJetzt(); kino.geschnitten(); }
        boolean an = kino.laeuft();
        if (an != kinoFensterAn) {
            kinoFensterAn = an;
            try { kinoFenster.accept(an); } catch (RuntimeException e) { System.err.println("Timmendorf: Kinofenster: " + e); }
        }
        if (kino.endeMelden()) r.hudMeldung("Kino zu Ende" + (lastSheet != null ? " · Blatt: " + lastSheet.getName() : ""));
    }

    /** Die Fotos des Regiebuchs als ein Blatt nach docs/status (zwei Spalten, Unterschrift in zwei Zeilen). */
    private void blatt(String name) {
        if (album.isEmpty() || ohneBlatt) return;
        try {
            int n = album.size(), cols = 2, rows = (n + 1) / 2, cw = 960, ch = (int) Math.round(album.get(0).getHeight() * cw / (double) album.get(0).getWidth()), cap = 50, head = 44;
            BufferedImage out = new BufferedImage(cols * cw, head + rows * (ch + cap), BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = out.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(new java.awt.Color(13, 18, 25));
            g.fillRect(0, 0, out.getWidth(), out.getHeight());
            g.setColor(new java.awt.Color(232, 236, 239));
            g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 20));
            g.drawString(String.format(Locale.GERMAN, "Timmendorfer Strand · Regiebuch „%s“ · %s · Ø %.0f Bilder/s · Grafikkarte Ø %.2f ms · %s", name, r.quality().label,
                    albumFps / Math.max(1, albumSamples), albumGpu / Math.max(1, albumSamples), r.glInfo().replaceAll(" ·.*", "")), 14, 29);
            g.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 16));
            for (int i = 0; i < n; i++) {
                int cx = (i % cols) * cw, cy = head + (i / cols) * (ch + cap);
                g.drawImage(album.get(i), cx, cy, cw, ch, null);
                g.setColor(new java.awt.Color(145, 157, 170));
                String[] ln = albumCaps.get(i).split("\n");
                for (int k = 0; k < ln.length; k++) g.drawString(ln[k], cx + 10, cy + ch + 21 + 20 * k);
            }
            g.dispose();
            File dir = r.photoDir != null ? r.photoDir : new File("docs/status");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("Ordner " + dir + " lässt sich nicht anlegen");
            String fn = "Regiebuch_" + Regie.plainName(name).replaceAll("[^a-z0-9]", "") + "_"
                    + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".png";
            File f = new File(dir, fn);
            javax.imageio.ImageIO.write(out, "png", f);
            lastSheet = f;
            r.hudMeldung("Regiebuch gespeichert: " + f.getPath());
            System.out.println("Regiebuch gespeichert: " + f.getPath());
        } catch (Exception e) {
            r.hudMeldung("Blatt des Regiebuchs fehlgeschlagen: " + e.getMessage());
            System.err.println("Blatt des Regiebuchs fehlgeschlagen: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ Ansichten und Fahrten

    /** Eine Ansicht nach Namen: die neun Tasten und die Blicke der Regiebücher [G]. */
    public boolean placeNamed(String name) {
        String k = Regie.plainName(name);
        Camera c = r.camera;
        switch (k) {
            case "sonnenaufgang" -> {
                Ansichten.Ansicht a = Ansichten.byName("Seebrücke");
                r.placeView(a);
                c.place(c.eye[0], c.eye[1], c.eye[2], r.clock.sun()[1] < 0 || r.clock.sun()[0] > 10 ? 57 : r.clock.sun()[1], -1);
                return true;
            }
            case "quallen", "ohrenquallen" -> {   // seit Phase 17: von der Mitte der Seebrücke hinab, Sonne im Rücken (Nordseite)
                double sx = Site.SCHLEIFE.x(), sz = Site.SCHLEIFE.z(), l = Math.hypot(sx, sz), ux = sx / l, uz = sz / l;
                double nx = uz, nz = -ux;   // nach Norden, quer zur Brücke
                double mx = sx * 0.45, mz = sz * 0.45;
                return blick(mx + nx * 2, mz + nz * 2, Bauwerke.DECK + 1.7, true, mx + nx * 7 + ux * 3, r.wiring.pegel, mz + nz * 7 + uz * 3);
            }
            case "mondaufgang" -> {   // der Mond geht am 1.8.2026 um 22:04 bei 96° auf (Phase 12, gerechnet)
                // seit Phase 17 (Befund: im Foto kein Mond): zum Mond, wo er gerade steht, sonst auf den Aufgangspunkt
                double[] md = r.wetter.skyOk ? r.wetter.state().moonDir : null;
                if (md != null && md[1] > -0.01) {
                    double ex = Site.SCHLEIFE.x(), ez = Site.SCHLEIFE.z(), ey = Bauwerke.DECK + 1.7, hy = Math.max(0.015, md[1] + 0.01);
                    return blick(ex, ez, ey, true, ex + md[0] * 1000, ey + hy * 1000, ez + md[2] * 1000);
                }
                return blick(Site.SCHLEIFE.x(), Site.SCHLEIFE.z(), Bauwerke.DECK + 1.7, true, Site.SCHLEIFE.x() + Math.sin(Math.toRadians(98)) * 1000,
                        Bauwerke.DECK + 40, Site.SCHLEIFE.z() - Math.cos(Math.toRadians(98)) * 1000);
            }
            case "feuerwerk", "ponton" -> { c.stopFahrt(); c.stopMitfahrt(); r.zumFeuerwerk(); return true; }
            case "startschiff" -> {
                double[] st = Site.fromUtm(Flotte.STARTSCHIFF[0], Flotte.STARTSCHIFF[1]);
                double[] pin = r.wiring.flotte.regatta.course.pin();
                double dx = pin[0] - st[0], dz = pin[1] - st[1], l = Math.max(1, Math.hypot(dx, dz));
                double ex = st[0] - dx / l * 60, ez = st[1] - dz / l * 60;
                return blick(ex, ez, r.wiring.pegel + 2.2, true, pin[0], r.wiring.pegel + 1, pin[1]);
            }
            case "sturmflutstrand" -> { c.stopFahrt(); c.stopMitfahrt(); r.zurSturmflut(false); return true; }
            case "sturmflutpromenade" -> { c.stopFahrt(); c.stopMitfahrt(); r.zurSturmflut(true); return true; }
            default -> { }
        }
        Ansichten.Ansicht a = Ansichten.byName(name);
        if (a == null) return false;
        c.stopFahrt();
        c.stopMitfahrt();
        return r.placeView(a);
    }

    /** Frei stehen bei (ex, ez) in der Höhe y (absolut, wenn abs, sonst über Grund), Blick auf (tx, ty, tz). */
    private boolean blick(double ex, double ez, double y, boolean abs, double tx, double ty, double tz) {
        Camera c = r.camera;
        c.stopFahrt();
        c.stopMitfahrt();
        c.diving = false;
        c.waterLevel = r.wiring.pegel;
        c.groundLevel = r.groundHeight(ex, ez);
        double ey = abs ? y : Math.max(r.groundHeight(ex, ez), r.wiring.pegel) + y;
        double yaw = (Math.toDegrees(Math.atan2(tx - ex, -(tz - ez))) + 360) % 360;
        double pitch = -Math.toDegrees(Math.atan2(ty - ey, Math.hypot(tx - ex, tz - ez)));
        c.place(ex, ey, ez, yaw, pitch);
        return true;
    }

    /** Die Fahrt „Seebrücke“: vom Landende auf dem Deck hinaus bis zur Schleife, am Ende der Blick zurück (40 s). */
    public Fahrt seebrueckenFahrt() {
        double sx = Site.SCHLEIFE.x(), sz = Site.SCHLEIFE.z(), l = Math.hypot(sx, sz);
        List<double[]> p = new ArrayList<>();
        for (double s = -25; s <= l + 1; s += 10) {
            double x = sx / l * s, z = sz / l * s;
            double y = s < 0 ? r.groundHeight(x, z) + 1.7 : Math.max(Bauwerke.DECK, r.groundHeight(x, z)) + 1.7;
            p.add(new double[]{x, y, z});
        }
        Fahrt f = new Fahrt("Seebrücke", p.toArray(new double[0][]), 40, 0, 3);
        f.endHeading = (Math.toDegrees(Math.atan2(-sx, sz)) + 360) % 360;
        f.endTurn = 10;
        return f;
    }

    /** Die Fahrt „Promenade“: 300 m zu Fuß die Promenade entlang nach Südost, Blick halb zur See (50 s). */
    public Fahrt promenadenFahrt() {
        Ansichten.Ansicht a = Ansichten.byName("Promenade");
        double[] q = r.strandPlatz(0);
        double sx = Site.SCHLEIFE.x(), sz = Site.SCHLEIFE.z(), l = Math.hypot(sx, sz), kx = -sz / l, kz = sx / l;
        if (kz < 0) { kx = -kx; kz = -kz; }
        double x0 = a != null ? a.x() : q[0], z0 = a != null ? a.z() : q[1];
        List<double[]> p = new ArrayList<>();
        for (double s = 0; s <= 300; s += 10) {
            double x = x0 + kx * s, z = z0 + kz * s;
            p.add(new double[]{x, Math.max(r.groundHeight(x, z), r.wiring.pegel) + 1.7, z});
        }
        return new Fahrt("Promenade", p.toArray(new double[0][]), 50, -35, 2);
    }

    /** Die Fahrt „Drohne“: 250 m vor der Küste, 80 m hoch, von Niendorf bis über die Maritim Seebrücke, Blick schräg aufs Land (50 s). */
    public Fahrt drohnenFahrt() {
        List<double[]> p = new ArrayList<>();
        double e0 = Site.NIENDORF.e() - 600, n0 = Site.NIENDORF.n() + 250, e1 = Site.E0 - 300, n1 = Site.N0 + 150;   // westlich der Hafeneinfahrt
        for (int i = 0; i <= 24; i++) {
            double u = i / 24.0, e = e0 + (e1 - e0) * u, n = n0 + (n1 - n0) * u;
            // auf 250 m seewärts der Küste schieben (dem Gefälle des Küstenabstands nach)
            for (int k = 0; k < 4; k++) {
                double d = Site.kuestenAbstand(e, n), h = 10;
                double ge = (Site.kuestenAbstand(e + h, n) - Site.kuestenAbstand(e - h, n)) / (2 * h), gn = (Site.kuestenAbstand(e, n + h) - Site.kuestenAbstand(e, n - h)) / (2 * h);
                double gl = Math.max(1e-9, Math.hypot(ge, gn));
                e -= (d + 250) * ge / gl;
                n -= (d + 250) * gn / gl;
            }
            double[] xz = Site.fromUtm(e, n);
            p.add(new double[]{xz[0], r.wiring.pegel + 80, xz[1]});
        }
        return new Fahrt("Drohne", p.toArray(new double[0][]), 50, -50, 14);
    }

    /** Die Fahrt „Weg“: über das Fußnetz von {@link #wegStart} zum Landende der Seebrücke (20 bis 90 s, im Zeitraffer der Füße). */
    public Fahrt wegFahrt() {
        com.dan.route.Route g = r.wiring.wege;
        double[] von = wegVon();
        int a = g.nearest(von[0], von[1]), b = g.nearest(0, 0);
        if (a < 0 || b < 0) { wegInfo = "kein Fußnetz"; return null; }
        com.dan.route.Route.Path path = g.astar(a, b, g.walking(), 1 / g.v0);
        if (!path.found() || path.nodes().length < 2) { wegInfo = "kein Weg von dort zum Strand"; return null; }
        List<double[]> p = new ArrayList<>();
        double[] last = null;
        for (int i : path.nodes()) {
            com.dan.route.Route.Node n = g.node(i);
            if (last != null && Math.hypot(n.x() - last[0], n.z() - last[2]) < 6) continue;
            last = new double[]{n.x(), Math.max(r.groundHeight(n.x(), n.z()), r.wiring.pegel) + 1.7, n.z()};
            p.add(last);
        }
        if (p.size() < 2) { wegInfo = "Start liegt schon am Strand"; return null; }
        double dur = Math.max(20, Math.min(90, path.length() / 1.34 / 8));
        wegInfo = String.format(Locale.GERMAN, "%s: %,.0f m zu Fuß (%.0f min), gezeigt in %.0f s", von.length > 2 ? "" : "Weg", path.length(), path.length() / 1.34 / 60, dur).replaceFirst("^: ", "");
        return new Fahrt("Weg", p.toArray(new double[0][]), dur, 0, 4);
    }

    /**
     * Wo der Weg beginnt (Szene x, z): ein Ort aus poi.tsv, „Ost Nord“ oder der Bahnhof. Von mehreren Treffern gilt der
     * nächste zur Seebrücke (Befund Phase 16: der erste Treffer für „Bahnhof“ war der Strandbahnhof in Travemünde, 9 km).
     * Leer: der Bahnhof Timmendorfer Strand.
     */
    double[] wegVon() {
        String s = wegStart == null ? "" : wegStart.strip();
        String[] p = s.split("[\\s,;]+");
        if (p.length == 2) {
            try {
                double e = Double.parseDouble(p[0]), n = Double.parseDouble(p[1]);
                if (e > 100_000 && n > 1_000_000) return Site.fromUtm(e, n);
            } catch (NumberFormatException ignored) { }
        }
        String suche = Regie.plainName(s.isEmpty() ? "Timmendorfer Strand, Bahnhof" : s);
        File f = new File(com.dan.timmendorf.scene.Netz.ordner(), "poi.tsv");
        if (f.isFile()) {
            try {
                double[] best = null;
                double bd = Double.MAX_VALUE;
                String bn = "";
                for (int runde = 0; runde < 2 && best == null; runde++) {
                    String such = runde == 0 ? suche : (s.isEmpty() ? Regie.plainName("Bahnhof") : null);
                    if (such == null) break;
                    for (String line : java.nio.file.Files.readAllLines(f.toPath(), java.nio.charset.StandardCharsets.UTF_8)) {
                        String[] c = line.split("\t", -1);
                        if (c.length < 6 || c[0].equals("osm")) continue;
                        if (!Regie.plainName(c[5]).contains(such) && !(s.isEmpty() && runde == 1 && Regie.plainName(c[4]).contains("railway"))) continue;
                        double[] xz = Site.fromUtm(Double.parseDouble(c[2]), Double.parseDouble(c[3]));
                        double d = Math.hypot(xz[0], xz[1]);
                        if (d < bd) { bd = d; best = xz; bn = c[5].isEmpty() ? c[4] : c[5]; }
                    }
                }
                if (best != null) {
                    wegInfo = String.format(java.util.Locale.GERMAN, "Start: %s (%.0f m Luftlinie)", bn, bd);
                    return best;
                }
            } catch (java.io.IOException | RuntimeException e) { wegInfo = "poi.tsv: " + e.getMessage(); }
        }
        // 900 m landeinwärts vom Landende [G]
        double sx = Site.SCHLEIFE.x(), sz = Site.SCHLEIFE.z(), l = Math.hypot(sx, sz);
        return new double[]{-sx / l * 900, -sz / l * 900};
    }

    /** Die Fahrt „Möwe“: hinter der nächsten fliegenden Möwe, 5 m hinter und 1 m über ihr. */
    public boolean moewenFlug() {
        com.dan.flock.Flock.Gull best = null;
        double bd = 1e18;
        for (com.dan.flock.Flock.Gull g : r.leben.vogel.gulls()) {
            if (g.species != com.dan.flock.Species.MOEWE) continue;
            if (g.state != com.dan.flock.Flock.State.SOAR && g.state != com.dan.flock.Flock.State.FLY) continue;
            double d = Math.hypot(g.pos[0] - r.camera.eye[0], g.pos[2] - r.camera.eye[2]);
            if (d < bd) { bd = d; best = g; }
        }
        if (best == null) return false;
        final com.dan.flock.Flock.Gull g = best;
        final double[] kurs = {Double.NaN};
        r.camera.stopFahrt();
        r.camera.startMitfahrt(() -> {
            double vx = g.vel[0], vz = g.vel[2], v = Math.hypot(vx, vz);
            double h = v > 0.5 ? (Math.toDegrees(Math.atan2(vx, -vz)) + 360) % 360 : (Double.isNaN(kurs[0]) ? 0 : kurs[0]);
            if (Double.isNaN(kurs[0])) kurs[0] = h;
            else kurs[0] = (kurs[0] + (((h - kurs[0] + 540) % 360) - 180) * 0.04 + 360) % 360;
            double a = Math.toRadians(kurs[0]);
            return new double[]{g.pos[0] - Math.sin(a) * 5, g.pos[1] + 1.0, g.pos[2] + Math.cos(a) * 5, kurs[0], 6, 0};
        });
        return true;
    }

    /** Eine Fahrt von Hand (Bedienfeld), ohne Regiebuch. */
    public void fahrtJetzt(String name) { ctx.fahrt(name); if (!wegInfo.isEmpty() && name.equals("weg")) r.hudMeldung("Mein Weg: " + wegInfo); }

    /** Eine Wetterlage nach Namen (Anfang des Namens im Bedienfeld oder des Schlüssels, ohne Umlaute). */
    public static Wetterregie.Lage lageNachName(String name) {
        String k = Regie.plainName(name);
        for (Wetterregie.Lage l : Wetterregie.Lage.values())
            if (Regie.plainName(l.label).equals(k) || Regie.plainName(l.name()).equals(k)) return l;
        for (Wetterregie.Lage l : Wetterregie.Lage.values())
            if (Regie.plainName(l.label).startsWith(k)) return l;
        return null;
    }

    /** Was die Regiebücher in der Szene tun. */
    private final class Ctx implements Regie.Ctx {
        @Override public void time(java.time.LocalDate date, double hour) {
            ereignisse++;
            r.clock.set(date, hour);
            r.wetter.sky.epochMillis = r.clock.epochMillis();
            r.readapt();
        }
        @Override public void lapse(double x) { r.clock.lapse = x; }
        @Override public void lage(String name) {
            Wetterregie.Lage l = lageNachName(name);
            ereignisse++;
            if (l == null) { msg = "Lage „" + name + "“ gibt es nicht"; return; }
            r.wiring.regie.lage(l);
        }
        @Override public void regie(String modus) {
            switch (modus) {
                case "frei" -> r.wiring.regie.frei();
                case "sturmtag" -> r.wiring.regie.sturmtag();
                default -> r.wiring.regie.hand();
            }
        }
        @Override public void sturmflut(java.time.LocalDate tag, double stunde) {
            ereignisse++;
            r.wiring.sturmflutAn = true;
            r.clock.set(tag, stunde);
            r.wiring.sturmflutSchritt();
            r.wetter.sky.epochMillis = r.clock.epochMillis();
            r.readapt();
        }
        @Override public void feuerwerk(String wie) {
            ereignisse++;
            if (wie.equals("abend")) { r.wiring.feuerwerkTag = r.wiring.datum(); r.wiring.feuerwerkAbends = true; }
            else r.wiring.feuerwerk();
        }
        @Override public void regatta() { ereignisse++; r.wiring.flotte.neuerStart(); }
        @Override public void view(String name) {
            ereignisse++;
            schnitt(() -> {
                if (!placeNamed(name)) msg = "Ansicht „" + name + "“ gibt es nicht";
                r.readapt();
            });
        }
        @Override public void fahrt(String name) {
            ereignisse++;
            schnitt(() -> {
                String k = Regie.plainName(name);
                Camera c = r.camera;
                c.stopFahrt();
                c.stopMitfahrt();
                switch (k) {
                    case "seebruecke" -> c.startFahrt(seebrueckenFahrt());
                    case "promenade" -> c.startFahrt(promenadenFahrt());
                    case "drohne" -> c.startFahrt(drohnenFahrt());
                    case "moewe" -> { if (!moewenFlug()) { msg = "keine Möwe im Flug, die Drohne fliegt"; c.startFahrt(drohnenFahrt()); } }
                    case "optimist" -> r.mitOptimist();
                    case "begleitboot" -> r.begleitboot(false);
                    case "regatta" -> r.begleitboot(true);
                    case "weg" -> {
                        Fahrt f = wegFahrt();
                        if (f == null) { msg = "Mein Weg: " + wegInfo; c.startFahrt(seebrueckenFahrt()); }
                        else { c.startFahrt(f); msg = wegInfo; }
                    }
                    default -> msg = "Fahrt „" + name + "“ gibt es nicht";
                }
                r.readapt();
            });
        }
        @Override public boolean ready(Regie.Warte w) {
            return switch (w.was()) {
                case "uhr" -> r.clock.epochMillis() / 3_600_000.0 >= w.hours();
                case "start" -> { double tg = r.wiring.flotte.regatta.toGun(); yield !Double.isNaN(tg) && tg <= 0; }
                case "ziel" -> r.wiring.flotte.regatta.finished() > 0;
                case "feuerwerk" -> fwGesehen && (r.wiring.firework == null || r.wiring.feuerwerkZeit() > r.wiring.firework.duration() + 8);
                case "fahrt" -> r.camera.fahrt == null && kinoSchnitte.isEmpty() && !r.kino.schnittFaellig();
                default -> true;
            };
        }
        @Override public void capture(String caption) { captureCaption = caption; fotosVerlangt++; ereignisse++; }
        @Override public void sheet(String name) { sheetName = name; ereignisse++; }
        @Override public void say(String text) { msg = text; r.kino.untertitel(text); }
    }
}
