package com.dan.timmendorf.app;

import com.dan.timmendorf.gl.Renderer;
import com.dan.timmendorf.gl.SceneView;
import com.dan.timmendorf.scene.SceneClock;
import com.dan.timmendorf.ui.AppIcon;
import com.dan.timmendorf.ui.ControlPanel;
import com.dan.timmendorf.ui.Startbild;
import com.dan.timmendorf.ui.Theme;
import com.dan.fframe.FFrame;
import com.dan.foptionpane.FOptionPane;
import com.jogamp.opengl.GLProfile;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.IllegalComponentStateException;
import java.awt.Rectangle;
import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Timmendorf — Timmendorfer Strand und die Lübecker Bucht von Haffkrug bis Travemünde mit Strand, Seebrücken,
 * Promenade, Niendorfer Hafen, Brodtener Ufer, Segelbooten und Feuerwerk als 3D-Szene auf der Grafikkarte (JOGL,
 * OpenGL 4.3). „Das Projekt des Segels“ (Werkbuch, Abschnitt 1).
 * <p>
 * Einstieg: Startbild, dann FFrame beim Start maximiert (Taskleiste bleibt frei), Größe frei veränderbar; Szene in
 * der Mitte, Bedienfeld rechts, Statuszeile unten. Die Element-Packages kommen aus {@code lib/Renderer.jar}; die neuen
 * {@code cloth}, {@code sail}, {@code wake}, {@code firework} und {@code route} entstehen im Projekt Renderer. Das Gerüst
 * ist aus Sylt übernommen und entschlackt (F7 A); {@code Avatar.jar} ist nicht mehr im Klassenpfad (30.10.).
 * <p>
 * Schalter: {@code -Dtimmendorf.splash=false} ohne Startbild, {@code -Dtimmendorf.gljpanel=true} leichter GL-Rückfall,
 * {@code -Dtimmendorf.shaders=<ordner>} Shader von der Platte (F5 lädt neu), {@code -Dtimmendorf.daten=<ordner>} Ordner für
 * Dateien der App.
 */
public final class TimmendorfApp {
    /** Für Werkzeuge: Fenster und Szene, sobald sie stehen. */
    public static volatile FFrame frame;
    public static volatile SceneView scene;
    public static volatile ControlPanel controls;
    /** Fehler in Fäden seit dem Start (für den Dauerlauf in Phase 19). */
    public static final AtomicInteger FEHLER = new AtomicInteger();

    public static void main(String[] args) {
        System.setProperty("sun.java2d.uiScale.enabled", "true");
        // Der GL-Canvas ist ein schweres AWT-Element: Menüs und Tooltips müssen darüber liegen können
        JPopupMenu.setDefaultLightWeightPopupEnabled(false);
        ToolTipManager.sharedInstance().setLightWeightPopupEnabled(false);
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            System.err.println("Faden " + t.getName() + ": " + e);
            FEHLER.incrementAndGet();
            e.printStackTrace();
            fehlerDatei("Faden " + t.getName(), e);
        });
        GLProfile.initSingleton();
        SwingUtilities.invokeLater(() -> {
            try {
                open();
            } catch (Throwable e) {
                // ohne Konsole (javaw, Verknüpfung) bliebe ein Fehler beim Start sonst unsichtbar (Befund aus Haithabu)
                e.printStackTrace();
                File f = fehlerDatei("Start", e);
                FOptionPane.showMessageDialog(null, "Timmendorf ließ sich nicht starten:\n" + e + (e.getStackTrace().length > 0 ? "\n  bei " + e.getStackTrace()[0] : "")
                        + "\n\nDie ganze Meldung steht in\n" + (f != null ? f.getAbsolutePath() : "der Konsole") + ".", "Timmendorf", FOptionPane.ERROR_MESSAGE);
                System.exit(1);
            }
        });
    }

    /** Ordner für Dateien der App ({@code daten/}): Fehler, ab Phase 2 das aufbereitete Gelände (nie C:\\Daten, Werkbuch 2.4). */
    public static File datenOrdner() {
        String d = System.getProperty("timmendorf.daten");
        return new File(d != null ? d : "daten");
    }

    /** Schreibt einen Fehler mit Stapel nach daten/fehler.txt (angehängt) und gibt die Datei zurück, oder null. */
    static File fehlerDatei(String wo, Throwable e) {
        try {
            File f = new File(datenOrdner(), "fehler.txt");
            f.getParentFile().mkdirs();
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            String t = java.time.LocalDateTime.now().withNano(0) + " · " + Renderer.VERSION + " · Java " + System.getProperty("java.version") + " · " + wo + "\n" + sw + "\n";
            java.nio.file.Files.writeString(f.toPath(), t, java.nio.charset.StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            return f;
        } catch (Exception x) {
            return null;
        }
    }

    private static void open() {
        Startbild.Wahl wahl = "false".equals(System.getProperty("timmendorf.splash")) ? Startbild.stumm() : Startbild.zeigen();
        if (wahl.beenden()) System.exit(0);
        SceneClock clock = new SceneClock();
        Renderer rd = new Renderer(clock);
        FFrame f = new FFrame("Timmendorfer Strand · Das Projekt des Segels · " + Renderer.VERSION);
        AppIcon.install(f);
        if (!Boolean.getBoolean("timmendorf.gljpanel")) makeOpaque(f);
        SceneView view;
        try {
            view = new SceneView(rd);
        } catch (RuntimeException e) {
            FOptionPane.showMessageDialog(null, "OpenGL lässt sich nicht starten:\n" + e.getMessage()
                    + "\n\nBitte den Grafiktreiber aktualisieren. Als Rückfall: Start mit -Dtimmendorf.gljpanel=true.", "Timmendorf", FOptionPane.ERROR_MESSAGE);
            System.exit(1);
            return;
        }
        ControlPanel controls = new ControlPanel(view);
        JLabel status = new JLabel("Die Szene startet …");
        status.setForeground(new Color(170, 182, 192));
        status.setFont(new Font("SansSerif", Font.PLAIN, 12));
        status.setBorder(BorderFactory.createEmptyBorder(5, 12, 5, 12));
        String gel = rd.terrain.measured() ? "Gelände " + rd.terrain.source + (rd.terrain.hasKern() ? " (Kern DGM1)" : " (Kern ohne DGM1)") : "ohne daten/gelaende: Bühne von Phase 1";
        JLabel right = new JLabel(gel + " · " + Startbild.statuszeile());
        new javax.swing.Timer(2000, e -> right.setText(gel + " · " + Startbild.statuszeile())).start();
        right.setFont(new Font("SansSerif", Font.PLAIN, 12));
        right.setBorder(BorderFactory.createEmptyBorder(5, 12, 5, 12));
        right.setForeground(Theme.GOLD);
        rd.setStatusListener(s -> SwingUtilities.invokeLater(() -> status.setText(s)));
        rd.setErrorListener(msg -> SwingUtilities.invokeLater(() -> FOptionPane.showMessageDialog(f, msg, "Timmendorf · Grafik", FOptionPane.ERROR_MESSAGE)));

        JPanel root = f.getComponentPane();
        root.setLayout(new BorderLayout());
        root.setBackground(Theme.bg());
        root.add(view, BorderLayout.CENTER);
        JScrollPane side = new JScrollPane(controls, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        side.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, Theme.line()));
        side.getViewport().setBackground(Theme.bg());
        side.setBackground(Theme.bg());
        try {
            com.dan.fscrollbar.FScrollBar bar = new com.dan.fscrollbar.FScrollBar(javax.swing.JScrollBar.VERTICAL);
            bar.setUnitIncrement(16);
            bar.setBlockIncrement(120);
            side.setVerticalScrollBar(bar);
        } catch (RuntimeException | LinkageError e) {
            side.getVerticalScrollBar().setUnitIncrement(16);
        }
        side.setPreferredSize(new Dimension(360, 600));   // Phase 19: breiter, die Knöpfe brechen um (ui.Raster)
        root.add(side, BorderLayout.EAST);
        JPanel south = new JPanel(new BorderLayout());
        south.setBackground(Theme.status());
        south.add(status, BorderLayout.CENTER);
        south.add(right, BorderLayout.EAST);
        root.add(south, BorderLayout.SOUTH);

        f.setPreferredFrameSize(new Dimension(1480, 900));
        f.setSize(1480, 900);
        f.setMinimumSize(new Dimension(800, 520));
        f.setLocationRelativeTo(null);
        f.setResizable(true);
        f.setVisible(true);
        startMaximized(f);
        view.start();
        // seit Phase 17 (F37–F47 A): Sitzung, Protokoll alle 5 s, Zustand und Vorschaubild alle 2 min und beim Ende; die Chronik
        com.dan.store.Protokoll.kontext(() -> com.dan.timmendorf.data.Daten.text(clock.epochMillis()),
                () -> String.format(java.util.Locale.ROOT, "%.0f %.0f %.0f", rd.camera.eye[0], rd.camera.eye[1], rd.camera.eye[2]));
        com.dan.store.Dienst.starten(Renderer.VERSION, "", rd.quality().name(), () -> sicherung(rd));
        com.dan.store.Protokoll.info("Sitzung", "Beginn · Timmendorf " + Renderer.VERSION + " · " + com.dan.timmendorf.data.Datenseite.wo());
        com.dan.timmendorf.data.Chronik ch = new com.dan.timmendorf.data.Chronik(rd);
        chronik = ch;
        controls.chronik(ch);
        fachdaten(rd);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { ch.beenden(); } catch (RuntimeException e) { System.err.println("Timmendorf: Chronik nicht beendet: " + e); }
            try { com.dan.store.Dienst.beenden(); } catch (RuntimeException e) { System.err.println("Timmendorf: Sitzung nicht beendet: " + e); }
            view.stop();
        }, "Timmendorf-Ende"));
        if (wahl.weiter() != null) com.dan.timmendorf.ui.Weiter.anwenden(rd, wahl.weiter(), ch);
        if (wahl.kino() != null) com.dan.timmendorf.ui.Weiter.kino(rd, wahl.kino());
        // F11: nur die Szene (Bedienfeld und Statuszeile weg und wieder da)
        view.setKeyAction(java.awt.event.KeyEvent.VK_F11, () -> SwingUtilities.invokeLater(() -> {
            boolean an = !side.isVisible();
            side.setVisible(an);
            south.setVisible(an);
            root.revalidate();
        }));
        final SceneView kv = view;
        rd.regisseur.kinoFenster = an -> SwingUtilities.invokeLater(() -> kinoFenster(f, kv, side, south, root, an));   // seit Phase 16
        view.requestFocusInWindow();
        frame = f;
        scene = view;
        TimmendorfApp.controls = controls;
        System.out.println("Timmendorf " + Renderer.VERSION + " gestartet · " + clock.label() + " · " + (wahl.datenbank() ? "Datenbank " : "") + Startbild.statuszeile()
                + " · Renderer.jar: " + Bibliothek.kurz());
    }

    /** Seit Phase 17: die Chronik der laufenden Szene (Läufe, Ergebnisse, Abbrüche, Feuerwerk, Messungen). */
    public static volatile com.dan.timmendorf.data.Chronik chronik;

    /** Der Zustand für die Sitzung (alle 2 min, mit Strg+S und beim Ende); dazu ein Vorschaubild für das Startbild. */
    private static com.dan.store.Ablage.Sicherung sicherung(Renderer r) {
        java.util.LinkedHashMap<String, String> z = com.dan.timmendorf.data.Zustand.erfassen(r);
        long s = com.dan.store.Dienst.sitzung();
        if (s > 0 && r.grabRequest == null && !r.kino.laeuft())
            r.grabRequest = img -> {
                byte[] png = ControlPanel.vorschau(img);
                if (png != null) com.dan.store.Dienst.spaeter(() -> {
                    try { com.dan.timmendorf.data.Datenseite.speicher().vorschau(s, png); } catch (com.dan.store.Ablage.Fehler e) { com.dan.store.Protokoll.warnung("Sitzung", "Vorschau: " + e.getMessage()); }
                });
            };
        return new com.dan.store.Ablage.Sicherung(com.dan.store.Felder.alsText(z, "Timmendorf · Sitzung · " + Renderer.VERSION), com.dan.timmendorf.data.Zustand.spalten(z));
    }

    /** Seit Phase 17 (F44 A): Fachdaten aus der Datenbank, wenn sie da sind – der große Zündplan, falls er dort anders steht als im Code. */
    private static void fachdaten(Renderer r) {
        com.dan.store.Dienst.spaeter(() -> {
            try {
                var sp = com.dan.timmendorf.data.Datenseite.speicher();
                if (!sp.datenbank()) return;
                for (var z : sp.zuendplaene())
                    if (z.name().equals(com.dan.timmendorf.data.Startdaten.FEUERWERK) && z.text() != null) {
                        String code = com.dan.timmendorf.data.Startdaten.zuendplaene().get(0).text();
                        if (!z.text().strip().equals(code.strip())) {
                            r.wiring.zuendplanText = z.text();
                            com.dan.store.Protokoll.info("Fachdaten", "Großes Feuerwerk aus " + sp.wo() + " (" + z.schuesse().size() + " Bomben)");
                        }
                    }
            } catch (Exception e) {
                com.dan.store.Protokoll.warnung("Fachdaten", e.getMessage());
            }
        });
    }

    /**
     * Startet maximiert (MAXIMIZED_BOTH), ohne die Taskleiste zu verdecken. Die normale Größe von 1480 × 900 bleibt
     * als Rückfall; „Wiederherstellen“ des FFrame stellt sie wieder her (wie in Brocken).
     */
    private static void startMaximized(FFrame f) {
        Rectangle normal = f.getBounds();
        f.setMaximizedBounds(GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds());
        f.setExtendedState(f.getExtendedState() | Frame.MAXIMIZED_BOTH);
        try {
            // Die Titelleiste des FFrame führt einen eigenen Maximiert-Zustand; ihn angleichen
            for (java.lang.reflect.Field fd : FFrame.class.getDeclaredFields()) {
                if (!fd.getType().getSimpleName().equals("FTaskbar")) continue;
                fd.setAccessible(true);
                Object bar = fd.get(f);
                Class<?> bc = bar.getClass();
                java.lang.reflect.Field mx = bc.getDeclaredField("maximized"), rb = bc.getDeclaredField("restoreBounds"),
                        btn = bc.getDeclaredField("btnMaximize");
                mx.setAccessible(true);
                rb.setAccessible(true);
                btn.setAccessible(true);
                mx.setBoolean(bar, true);
                rb.set(bar, normal);
                Object icon = btn.get(bar);
                icon.getClass().getMethod("setType", com.dan.ficons.FIconType.class).invoke(icon, com.dan.ficons.FIconType.RESTORE);
                ((javax.swing.JComponent) icon).setToolTipText("Wiederherstellen");
                ((java.awt.Component) icon).addMouseListener(new java.awt.event.MouseAdapter() {
                    @Override public void mouseReleased(java.awt.event.MouseEvent e) {
                        SwingUtilities.invokeLater(() -> {
                            if ((f.getExtendedState() & Frame.MAXIMIZED_BOTH) != 0) {
                                f.setExtendedState(f.getExtendedState() & ~Frame.MAXIMIZED_BOTH);
                                f.setBounds(normal);
                            }
                        });
                    }
                });
            }
        } catch (Exception e) {
            System.err.println("FFrame-Titelleiste nicht angeglichen: " + e);
        }
    }

    /**
     * Das FFrame ist ein durchscheinendes Fenster (runde Ecken). Darin zeigt Windows schwere Elemente wie den
     * GL-Canvas nicht; darum wird es deckend gemacht und die Rundung als Fensterform gesetzt.
     */
    private static void makeOpaque(FFrame f) {
        f.setBackground(Theme.bg());
        java.awt.event.ComponentAdapter shape = new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent e) { reshape(f); }
            @Override public void componentShown(java.awt.event.ComponentEvent e) { reshape(f); }
        };
        f.addComponentListener(shape);
        f.addWindowStateListener(e -> reshape(f));
    }

    private static void reshape(FFrame f) {
        try {
            boolean max = (f.getExtendedState() & Frame.MAXIMIZED_BOTH) != 0;
            f.setShape(max ? null : new java.awt.geom.RoundRectangle2D.Double(0, 0, f.getWidth(), f.getHeight(), 20, 20));
        } catch (UnsupportedOperationException | IllegalComponentStateException e) {
            // Form nicht möglich: dann eben eckig
        }
    }

    /** Seit Phase 16: das Kino im ganzen Bildschirm (aus Sylt): Bedienfeld, Statuszeile und Titelleiste weg, Mauszeiger verborgen. */
    private static boolean kinoVoll, kinoSeite = true, kinoSueden = true;
    private static int kinoZustand;
    private static Rectangle kinoRahmen;

    /** Seit Phase 15: Vollbild für das Kino und zurück (aus Glacier und Rungholt). */
    private static void kinoFenster(FFrame f, SceneView view, java.awt.Component side, java.awt.Component south, JPanel root, boolean an) {
        if (an == kinoVoll) return;
        java.awt.Component bar = taskbar(f);
        if (an) {
            kinoSeite = side.isVisible();
            kinoSueden = south.isVisible();
            kinoZustand = f.getExtendedState();
            kinoRahmen = f.getBounds();
            kinoVoll = true;
            side.setVisible(false);
            south.setVisible(false);
            if (bar != null) bar.setVisible(false);
            Rectangle schirm = f.getGraphicsConfiguration().getBounds();
            f.setExtendedState(Frame.NORMAL);
            f.setBounds(schirm);
            reshape(f);
            view.zeigerVerbergen(true);
            f.toFront();
        } else {
            kinoVoll = false;
            side.setVisible(kinoSeite);
            south.setVisible(kinoSueden);
            if (bar != null) bar.setVisible(true);
            if ((kinoZustand & Frame.MAXIMIZED_BOTH) != 0) f.setExtendedState(kinoZustand);
            else if (kinoRahmen != null) f.setBounds(kinoRahmen);
            reshape(f);
            view.zeigerVerbergen(false);
        }
        root.revalidate();
        root.repaint();
        view.requestFocusInWindow();
    }

    /** Die Titelleiste des FFrame (FTaskbar), für das Kino zum Verbergen. */
    private static java.awt.Component taskbar(FFrame f) {
        try {
            for (java.lang.reflect.Field fd : FFrame.class.getDeclaredFields()) {
                if (!fd.getType().getSimpleName().equals("FTaskbar")) continue;
                fd.setAccessible(true);
                return (java.awt.Component) fd.get(f);
            }
        } catch (Exception e) {
            System.err.println("FFrame-Titelleiste nicht gefunden: " + e);
        }
        return null;
    }

    private TimmendorfApp() { }
}
