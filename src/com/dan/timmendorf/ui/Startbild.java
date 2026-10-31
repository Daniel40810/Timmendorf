package com.dan.timmendorf.ui;

import com.dan.timmendorf.app.Bibliothek;
import com.dan.timmendorf.data.DateiSpeicher;
import com.dan.timmendorf.data.Daten;
import com.dan.timmendorf.data.Datenseite;
import com.dan.timmendorf.data.DbSpeicher;
import com.dan.timmendorf.data.Speicher;
import com.dan.timmendorf.data.Verbindung;
import com.dan.timmendorf.gl.Renderer;
import com.dan.timmendorf.scene.SceneClock;
import com.dan.timmendorf.scene.Site;
import com.dan.timmendorf.scene.Terrain;
import com.dan.fbutton.FButton;
import com.dan.store.Db;
import com.dan.store.Einrichter;
import com.dan.store.Protokoll;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.AWTEvent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.io.FileNotFoundException;
import java.sql.Connection;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.prefs.Preferences;

/**
 * Das Startbild vor der Szene (aus Sylt, seit Phase 17 mit F38 A). Es verbindet mit dem Schema TIMMENDORF über
 * {@code db/db.properties}, fragt nach dem Passwort, wenn keins hinterlegt ist (auch nicht in
 * {@code Timmendorf_DB_PASSWORD}), und trägt es nach dem ersten Verbinden dort ein; es prüft die Ausbaustufe von Teil 2
 * und zeigt den Stand: Regiebücher und Läufe, Ansichten, Presets, Fachdaten, Ergebnisse, Abbrüche, die letzte Sitzung mit
 * Datum, Vorschaubild und Lage, und das letzte Ergebnis des Einrichters. Dazu, ob das Gelände aus {@code daten/gelaende}
 * kommt.
 * <p>
 * Knöpfe: „Weiter wie zuletzt“ (die Vorgabe, wenn es eine Sitzung gibt), „Neu am 1. August“, ein Regiebuch „Im Kino“,
 * „Einrichten“, „Ohne Datenbank“, „Beenden“. Ist alles bereit, geht es nach 3 s von selbst weiter; solange eingerichtet
 * werden muss oder ein Passwort fehlt, nicht. Endete die letzte Sitzung nicht sauber, sagt es das und lädt ihren letzten
 * gesicherten Stand. Jede Eingabe hält an. Ohne Startbild: {@code -Dtimmendorf.splash=false} (dann gilt, was sich ohne
 * Rückfrage verbinden lässt; {@code -Dtimmendorf.weiter=true} stellt die letzte Sitzung her).
 */
public final class Startbild extends JDialog {
    private static final int SEKUNDEN = 3;
    private static final Preferences PREFS = prefs();

    /** Was gewählt wurde: Speicher, für „Weiter wie zuletzt“ die Sitzung, für „Im Kino“ das Regiebuch, oder beenden. */
    public record Wahl(Speicher speicher, Daten.Sitzung weiter, com.dan.timmendorf.scene.Regie.Book kino, boolean beenden) {
        public Wahl(Speicher speicher) { this(speicher, null, null, false); }
        public boolean datenbank() { return speicher != null && speicher.datenbank(); }
    }

    private final JLabel dbLine = new JLabel(" "), datenLine = new JLabel(" "), standLine = new JLabel(" "), countLine = new JLabel(" "), bild = new JLabel();
    private final JPanel pwRow = new JPanel(new BorderLayout(8, 0));
    private final com.dan.ftextfield.FPasswordfield pw = new com.dan.ftextfield.FPasswordfield();
    private final com.dan.ftextarea.FTextArea log = new com.dan.ftextarea.FTextArea(8, 60);
    private final javax.swing.JScrollPane logPane = new javax.swing.JScrollPane(log);
    private final FButton neu = new FButton("Neu am 1. August"), weiter = new FButton("Weiter wie zuletzt"), einrichten = new FButton("Einrichten"),
            ohne = new FButton("Ohne Datenbank"), ende = new FButton("Beenden");
    private Timer timer;
    private int rest = SEKUNDEN;
    private boolean angehalten, fertig;
    private AWTEventListener aufmerken;
    private volatile Db.Konfig konfig;
    private volatile DbSpeicher db;
    private volatile Speicher speicher = new DateiSpeicher(DateiSpeicher.ordner());
    private volatile Daten.Sitzung letzte;
    private Wahl wahl;

    private static Preferences prefs() {
        try { return Preferences.userRoot().node("com/dan/timmendorf/daten"); } catch (RuntimeException | Error e) { return null; }
    }

    /** Zeigt das Startbild und kehrt zurück, wenn es zu ist (modal); setzt den Speicher der App. */
    public static Wahl zeigen() {
        Startbild s = new Startbild();
        s.setVisible(true);
        Wahl w = s.wahl != null ? s.wahl : new Wahl(s.speicher);
        if (!w.beenden()) Datenseite.setzen(w.speicher());
        return w;
    }

    /** Ohne Startbild: verbinden, wenn es ohne Rückfrage geht und das Schema eingerichtet ist, sonst Dateien. */
    public static Wahl stumm() {
        Speicher sp = new DateiSpeicher(DateiSpeicher.ordner());
        if (!"false".equals(System.getProperty("timmendorf.db")))
            try {
                Db.Konfig k = Verbindung.konfig();
                DbSpeicher d = Datenseite.verbinden(k, null);
                if (d != null) Verbindung.verbunden(k);
                if (d != null && Verbindung.einrichter().stufe(d.verbindung()) >= Verbindung.VERSION) sp = d;
                else if (d != null) d.schliessen();
            } catch (Exception e) { /* ohne Datenbank */ }
        Datenseite.setzen(sp);
        Daten.Sitzung s = null;
        if ("true".equals(System.getProperty("timmendorf.weiter"))) try { s = sp.letzteSitzung(-1); } catch (Exception e) { /* neu */ }
        return new Wahl(sp, s, null, false);
    }

    /** Statuszeile unten rechts im Fenster: wohin gespeichert wird. */
    public static String statuszeile() { return Datenseite.gesetzt() ? Datenseite.wo() : "Datenbank: " + Verbindung.stand(); }

    /** Was über Gelände und Daten zu sagen ist (Regel 2.4: die App liest nur daten/, nie C:\Daten). */
    public static String gelaendeZeile() {
        java.io.File g = Terrain.defaultDir();
        String d = com.dan.timmendorf.scene.Daten.zustand();
        if (new java.io.File(g, Terrain.HOEHE).isFile()) return "Gelände aus " + g.getPath() + ". " + d;
        return "Gelände: die Bühne von Phase 1. " + d;
    }

    private Startbild() {
        super((java.awt.Frame) null, "Timmendorf", true);
        setUndecorated(true);
        JPanel root = new JPanel(new BorderLayout(18, 14)) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g;
                g2.setPaint(new GradientPaint(0, 0, new Color(22, 31, 44), 0, getHeight(), new Color(10, 13, 19)));
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setColor(Theme.SEE);
                g2.fillRect(0, 0, getWidth(), 3);
                int y = getHeight() - 6, w = getWidth();
                for (int i = 0; i < Theme.BAND.length; i++) {
                    g2.setColor(Theme.BAND[i]);
                    g2.fillRect((int) (w * Theme.BAND_AT[i]), y, (int) Math.ceil(w * (Theme.BAND_AT[i + 1] - Theme.BAND_AT[i])) + 1, 6);
                }
            }
        };
        root.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(45, 58, 74)), BorderFactory.createEmptyBorder(22, 26, 24, 28)));
        JLabel il = new JLabel(new ImageIcon(AppIcon.load(128)));
        il.setVerticalAlignment(JLabel.TOP);
        root.add(il, BorderLayout.WEST);
        JPanel mid = new JPanel();
        mid.setOpaque(false);
        mid.setLayout(new BoxLayout(mid, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("TIMMENDORFER STRAND");
        title.setFont(new Font("Serif", Font.PLAIN, 34));
        title.setForeground(Theme.SEGEL);
        JLabel sub = new JLabel("Das Projekt des Segels · Lübecker Bucht von Haffkrug bis Travemünde, " + Site.dms(Site.LAT, Site.LON).replace(" · ", " "));
        sub.setFont(new Font("SansSerif", Font.PLAIN, 14));
        sub.setForeground(Theme.SEE);
        SceneClock start = new SceneClock();
        JLabel info = new JLabel("<html><body style='width:560px'>" + "Timmendorf · " + Renderer.VERSION + ". Strand, Seebrücke, Bucht mit Segelbooten "
                + "und Regatta, Feuerwerk, Brodtener Ufer, Sturmflut, Jahr, Klang, Ohrenquallen und fünf Regiebücher; neu „Weiter wie zuletzt“ mit Uhr, Wetter, "
                + "Kamera, Reglern und der laufenden Wettfahrt, gemerkte Ansichten, eigene Presets und Regiebücher, Ergebnisse der Regatta. Neu beginnt die Szene am "
                + start.label() + ".<br>Renderer.jar: " + Bibliothek.kurz().replace("!", "") + "</body></html>");
        info.setFont(new Font("SansSerif", Font.PLAIN, 12));
        info.setForeground(new Color(170, 182, 192));
        info.setBorder(BorderFactory.createEmptyBorder(10, 0, 8, 0));
        for (JLabel l : new JLabel[]{dbLine, datenLine, standLine, countLine}) {
            l.setFont(new Font("SansSerif", Font.PLAIN, 12));
            l.setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        for (JLabel l : new JLabel[]{title, sub, info}) l.setAlignmentX(Component.LEFT_ALIGNMENT);
        dbLine.setForeground(new Color(224, 190, 120));
        datenLine.setForeground(new Color(196, 206, 210));
        datenLine.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        datenLine.setText("<html><body style='width:620px'>" + gelaendeZeile() + "</body></html>");
        standLine.setForeground(new Color(196, 206, 214));
        standLine.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        countLine.setForeground(Theme.SEE);
        countLine.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        pwRow.setOpaque(false);
        pwRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel pl = ControlPanel.small("Passwort für das Schema:");
        FButton verb = new FButton("Verbinden");
        verb.addActionListener(e -> mitPasswort());
        pw.addActionListener(e -> mitPasswort());
        pwRow.add(pl, BorderLayout.WEST);
        pwRow.add(pw, BorderLayout.CENTER);
        pwRow.add(verb, BorderLayout.EAST);
        pwRow.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        pwRow.setMaximumSize(new Dimension(620, 40));
        pwRow.setVisible(false);
        log.setEditable(false);
        log.setLineWrap(true);
        log.setWrapStyleWord(true);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        logPane.setAlignmentX(Component.LEFT_ALIGNMENT);
        logPane.setPreferredSize(new Dimension(620, 160));
        logPane.setVisible(false);
        mid.add(title);
        mid.add(sub);
        mid.add(info);
        mid.add(dbLine);
        mid.add(pwRow);
        mid.add(datenLine);
        mid.add(standLine);
        mid.add(logPane);
        mid.add(countLine);
        root.add(mid, BorderLayout.CENTER);
        bild.setVerticalAlignment(JLabel.TOP);
        bild.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        root.add(bild, BorderLayout.EAST);
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        south.setOpaque(false);
        neu.setToolTipText("Die Szene neu: " + start.label());
        weiter.setToolTipText("Die letzte Sitzung herstellen: Uhr, Wetter, Kamera, alle Regler, das Regiebuch, die laufende Wettfahrt; das Brodtener Ufer folgt der Uhr");
        einrichten.setToolTipText("Die Skripte aus db/sql ausführen und alles prüfen (löscht nie etwas)");
        ohne.setToolTipText("Mit Dateien in " + DateiSpeicher.ordner() + " statt der Datenbank (CSV mit WKT für QGIS); beim nächsten Verbinden übernommen");
        ohne.addActionListener(e -> { if (db != null) db.schliessen(); db = null; speicher = new DateiSpeicher(DateiSpeicher.ordner()); schliessen(new Wahl(speicher, null, null, false)); });
        einrichten.addActionListener(e -> einrichten());
        weiter.addActionListener(e -> schliessen(new Wahl(speicher, letzte, null, false)));
        neu.addActionListener(e -> schliessen(new Wahl(speicher)));
        ende.addActionListener(e -> schliessen(new Wahl(speicher, null, null, true)));
        einrichten.setEnabled(false);
        weiter.setEnabled(false);
        java.util.List<com.dan.timmendorf.scene.Regie.Book> buecher;
        try { buecher = com.dan.timmendorf.scene.Regie.all(); } catch (RuntimeException ex) { buecher = java.util.List.of(); }
        if (!buecher.isEmpty()) {
            com.dan.fcombobox.FComboBox buch = new com.dan.fcombobox.FComboBox(buecher.toArray());
            buch.setPreferredSize(new Dimension(220, buch.getPreferredSize().height));
            FButton kino = new FButton("Im Kino");
            kino.setToolTipText("Das Regiebuch im Kinomodus: ganzer Bildschirm, Vor- und Abspann, Untertitel; Esc beendet das Kino, Leertaste hält an");
            kino.addActionListener(e -> { if (buch.getSelectedItem() instanceof com.dan.timmendorf.scene.Regie.Book b) schliessen(new Wahl(speicher, null, b, false)); });
            south.add(buch);
            south.add(kino);
            south.add(javax.swing.Box.createHorizontalStrut(14));
        }
        south.add(ohne);
        south.add(einrichten);
        south.add(ende);
        south.add(weiter);
        south.add(neu);
        root.add(south, BorderLayout.SOUTH);
        setContentPane(root);
        dbLine.setText("Datenbank: verbinde …");
        pack();
        setSize(Math.max(getWidth(), 1100), Math.max(getHeight(), 420));
        setLocationRelativeTo(null);
        try { setIconImages(AppIcon.images()); } catch (RuntimeException e) { /* ohne Symbol */ }
        getRootPane().setDefaultButton(neu);
        aufmerken = ev -> {
            if (ev.getSource() instanceof Component c && SwingUtilities.getWindowAncestor(c) == this && !angehalten
                    && (ev.getID() == java.awt.event.MouseEvent.MOUSE_PRESSED || ev.getID() == java.awt.event.KeyEvent.KEY_PRESSED) && !(c instanceof FButton))
                anhalten("Angehalten. „Weiter wie zuletzt“ oder „Neu am 1. August“.");
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(aufmerken, AWTEvent.MOUSE_EVENT_MASK | AWTEvent.KEY_EVENT_MASK);
        hintergrund(this::pruefen);
    }

    private static void hintergrund(Runnable r) {
        Thread t = new Thread(r, "Timmendorf-Startbild");
        t.setDaemon(true);
        t.start();
    }

    private void anhalten(String text) {
        angehalten = true;
        if (timer != null) timer.stop();
        countLine.setText(text);
    }

    private void groesse() {
        pack();
        setSize(Math.max(getWidth(), 1100), getHeight());
    }

    /** Verbinden, Stufe prüfen, Stand zeigen (im Hintergrund). */
    private void pruefen() {
        String zeile;
        boolean zaehlen = true;
        if ("false".equals(System.getProperty("timmendorf.db"))) zeile = "Ohne Datenbank (-Dtimmendorf.db=false): Dateien in " + DateiSpeicher.ordner() + ".";
        else try {
            konfig = Verbindung.konfig();
            if (!konfig.hatPasswort()) {
                String sl = stand(speicher, "Bis dahin: ");
                SwingUtilities.invokeLater(() -> {
                    dbLine.setText("<html><body style='width:620px'>Datenbank " + konfig.kurz() + ": bitte das Passwort eingeben (es wird nach dem "
                            + "Verbinden in db/db.properties eingetragen; die Datei steht in .gitignore).</body></html>");
                    pwRow.setVisible(true);
                    standLine.setText(sl);
                    anhalten("Wartet auf das Passwort oder „Ohne Datenbank“.");
                    groesse();
                    pw.requestFocusInWindow();
                });
                return;
            }
            String[] grund = {""};
            DbSpeicher d = Datenseite.verbinden(konfig, grund);
            if (d == null) {
                zeile = "Datenbank " + konfig.kurz() + " nicht erreichbar (" + grund[0] + "). Ohne Datenbank: Dateien in " + DateiSpeicher.ordner() + ".";
                Protokoll.warnung("Startbild", zeile);
                rest = 6;
            } else {
                db = d;
                Verbindung.verbunden(konfig);   // die Netze lesen damit aus Oracle (Teil 1, Phase 3b)
                int st = Verbindung.einrichter().stufe(d.verbindung());
                if (st < Verbindung.VERSION) {
                    zeile = d.wo() + ": " + (st == 0 ? "Teil 2 noch nicht eingerichtet" : st < 0 ? "Tabellen ohne TD_SCHEMA" : "Ausbaustufe " + st + " statt " + Verbindung.VERSION)
                            + ". Bitte „Einrichten“ (führt db/sql aus, löscht nie etwas) oder „Ohne Datenbank“.";
                    zaehlen = false;
                } else {
                    speicher = d;
                    zeile = d.wo() + " · Ausbaustufe " + st + " · eingerichtet";
                }
            }
        } catch (FileNotFoundException e) {
            zeile = "Keine db/db.properties (Vorlage db/db.properties.example). Ohne Datenbank: Dateien in " + DateiSpeicher.ordner() + ".";
        } catch (Exception e) {
            zeile = "Datenbank: " + e.getMessage() + ". Ohne Datenbank: Dateien in " + DateiSpeicher.ordner() + ".";
            rest = 6;
        }
        String z = zeile;
        boolean zz = zaehlen;
        String sl = stand(speicher, "");
        try { letzte = speicher.letzteSitzung(-1); } catch (Exception e) { letzte = null; }
        SwingUtilities.invokeLater(() -> {
            dbLine.setText("<html><body style='width:620px'>" + z + "</body></html>");
            standLine.setText(sl);
            vorschau(letzte);
            einrichten.setEnabled(db != null);
            weiter.setEnabled(letzte != null && letzte.zustand() != null);
            if (weiter.isEnabled()) getRootPane().setDefaultButton(weiter);
            groesse();
            if (zz && !angehalten) zaehlen(); else if (!zz) anhalten("Wartet: „Einrichten“, „Ohne Datenbank“ oder „Neu“ mit Dateien.");
        });
    }

    /** Das Vorschaubild der letzten Sitzung rechts (oder nichts). */
    private void vorschau(Daten.Sitzung s) {
        if (s == null || s.vorschau() == null) { bild.setIcon(null); bild.setText(null); return; }
        try {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(s.vorschau()));
            if (img == null) return;
            int w = 260, h = (int) Math.round(260.0 * img.getHeight() / img.getWidth());
            bild.setIcon(new ImageIcon(img.getScaledInstance(w, h, java.awt.Image.SCALE_SMOOTH)));
            String wann = DateTimeFormatter.ofPattern("dd.MM. HH:mm").format(Instant.ofEpochMilli(s.zustandMs() != null ? s.zustandMs() : s.beginnMs()).atZone(ZoneId.systemDefault()));
            bild.setText("<html><body style='width:250px'>Zuletzt gesichert " + wann + "<br>Szene " + s.szeneText() + "</body></html>");
            bild.setHorizontalTextPosition(JLabel.CENTER);
            bild.setVerticalTextPosition(JLabel.BOTTOM);
            bild.setForeground(new Color(196, 206, 214));
            bild.setFont(new Font("SansSerif", Font.PLAIN, 11));
        } catch (Exception e) { /* ohne Bild */ }
    }

    /** Eine Zeile mit dem Stand der Daten. */
    static String stand(Speicher sp, String vor) {
        try {
            Daten.Stand s = sp.stand();
            StringBuilder b = new StringBuilder("<html><body style='width:620px'>").append(vor).append(sp.wo()).append(": ");
            b.append(s.kurz()).append(String.format(Locale.GERMAN, "; %d Befunde.", s.befunde()));
            Daten.Sitzung l = s.letzte();
            if (l != null) {
                String wann = DateTimeFormatter.ofPattern("dd.MM. HH:mm").format(Instant.ofEpochMilli(l.zustandMs() != null ? l.zustandMs() : l.beginnMs()).atZone(ZoneId.systemDefault()));
                b.append("<br>Letzte Sitzung ").append(wann).append(l.sauber() ? ", geordnet beendet" : ", <b>nicht sauber beendet</b> – „Weiter wie zuletzt“ lädt ihren letzten gesicherten Stand");
                if (l.szeneMs() != null) b.append(" · Szene ").append(l.szeneText());
                if (l.lage() != null) b.append(" · ").append(l.lage());
                if (l.regiebuch() != null) b.append(" · Regiebuch „").append(l.regiebuch()).append("“");
            }
            String e = PREFS == null ? "" : PREFS.get("einrichter", "");
            if (!e.isEmpty()) b.append("<br>Einrichter zuletzt: ").append(e);
            return b.append("</body></html>").toString();
        } catch (Exception e) {
            return "Stand nicht lesbar: " + e.getMessage();
        }
    }

    /** Das Ergebnis eines Einrichtens merken (für Startbild und Block DATEN). */
    static void merkeEinrichter(String kurz) {
        Datenseite.einrichterStand = kurz;
        if (PREFS != null) try { PREFS.put("einrichter", kurz + " (" + DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").format(java.time.LocalDateTime.now()) + ")"); } catch (RuntimeException e) { /* nicht gemerkt */ }
    }

    private void mitPasswort() {
        char[] p = pw.getPassword();
        if (p.length == 0 || konfig == null) return;
        Db.Konfig k = konfig.mitPasswort(p);
        dbLine.setText("Datenbank: verbinde …");
        hintergrund(() -> {
            String[] grund = {""};
            DbSpeicher d = Datenseite.verbinden(k, grund);
            if (d == null) {
                SwingUtilities.invokeLater(() -> dbLine.setText("<html><body style='width:620px'>Nicht verbunden: " + grund[0] + "</body></html>"));
                return;
            }
            try { Db.passwortSpeichern(k, p); } catch (Exception e) { Protokoll.warnung("Startbild", "Passwort nicht in db.properties eingetragen: " + e.getMessage()); }
            d.schliessen();
            SwingUtilities.invokeLater(() -> { pwRow.setVisible(false); angehalten = false; });
            konfig = k;
            hintergrund(this::pruefen);
        });
    }

    /** Einrichten mit Fortschritt; danach gilt die Datenbank. */
    private void einrichten() {
        DbSpeicher d = db;
        if (d == null) return;
        anhalten("Einrichten läuft …");
        einrichten.setEnabled(false);
        neu.setEnabled(false);
        logPane.setVisible(true);
        log.setText("");
        groesse();
        hintergrund(() -> {
            try {
                Connection c = d.verbindung();
                Einrichter.Ergebnis r = Verbindung.einrichter().einrichten(c, Verbindung.skripte(), (t, a) -> SwingUtilities.invokeLater(() ->
                        countLine.setText(String.format(Locale.GERMAN, "Einrichten %.0f %% · %s", a * 100, t))));
                merkeEinrichter(r.kurz());
                Protokoll.info("Einrichter", r.kurz() + " · " + r.ausgefuehrt() + " Anweisungen");
                for (String z : r.zeilen()) if (!z.startsWith("ok") && !z.contains("alles in Ordnung")) Protokoll.warnung("Einrichter", z);
                if (r.ok()) speicher = d;
                String sl = stand(speicher, "");
                try { letzte = speicher.letzteSitzung(-1); } catch (Exception e) { letzte = null; }
                SwingUtilities.invokeLater(() -> {
                    log.setText(String.join("\n", r.zeilen()));
                    log.setCaretPosition(0);
                    standLine.setText(sl);
                    vorschau(letzte);
                    dbLine.setText(r.ok() ? d.wo() + " · eingerichtet: alles in Ordnung" : "<html>Einrichter: " + r.kurz() + "</html>");
                    countLine.setText(r.ok() ? "Fertig. „Weiter wie zuletzt“ oder „Neu am 1. August“." : "Einrichter mit Befunden; siehe oben. „Neu“ startet mit " + speicher.wo() + ".");
                    einrichten.setEnabled(true);
                    neu.setEnabled(true);
                    weiter.setEnabled(letzte != null && letzte.zustand() != null);
                });
            } catch (Exception e) {
                Protokoll.fehler("Einrichter", "Einrichten abgebrochen: " + e.getMessage(), e);
                SwingUtilities.invokeLater(() -> {
                    log.setText("Einrichten abgebrochen: " + e.getMessage());
                    einrichten.setEnabled(true);
                    neu.setEnabled(true);
                });
            }
        });
    }

    private void zaehlen() {
        countLine.setText((letzte != null && letzte.zustand() != null ? "Weiter wie zuletzt in " : "Die Szene startet in ") + rest + " s …");
        timer = new Timer(1000, e -> {
            if (angehalten) return;
            rest--;
            if (rest <= 0) schliessen(letzte != null && letzte.zustand() != null ? new Wahl(speicher, letzte, null, false) : new Wahl(speicher));
            else countLine.setText((letzte != null && letzte.zustand() != null ? "Weiter wie zuletzt in " : "Die Szene startet in ") + rest + " s …");
        });
        timer.start();
    }

    private void schliessen(Wahl w) {
        if (fertig) return;
        fertig = true;
        wahl = w;
        if (db != null && w.speicher() != db) db.schliessen();
        if (timer != null) timer.stop();
        if (aufmerken != null) Toolkit.getDefaultToolkit().removeAWTEventListener(aufmerken);
        dispose();
    }
}
