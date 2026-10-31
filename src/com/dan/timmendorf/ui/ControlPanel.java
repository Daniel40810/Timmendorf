package com.dan.timmendorf.ui;

import com.dan.timmendorf.app.Bibliothek;
import com.dan.timmendorf.camera.Ansichten;
import com.dan.timmendorf.camera.Camera;
import com.dan.timmendorf.data.Verbindung;
import com.dan.timmendorf.gl.ComputeProbe;
import com.dan.timmendorf.gl.Quality;
import com.dan.timmendorf.gl.Renderer;
import com.dan.timmendorf.gl.SceneView;
import com.dan.timmendorf.scene.SceneClock;
import com.dan.timmendorf.scene.Sun;
import com.dan.fbutton.FButton;
import com.dan.fcheckbox.FCheckBox;
import com.dan.fcollapsiblepanel.FCollapsiblePanel;
import com.dan.fcombobox.FComboBox;
import com.dan.fslider.FSlider;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.prefs.Preferences;

/**
 * Das Bedienfeld rechts als Klappblöcke (aus Sylt, entschlackt). Die Blöcke stehen nach dem Werkbuch (Abschnitt 11)
 * schon alle da: KAMERA, ZEIT, WETTER, WASSER, BOOTE, REGATTA, FEUERWERK, MENSCHEN, KLANG, REGIEBÜCHER, DATEN, TECHNIK.
 * In Phase 1 arbeiten KAMERA (neun Ansichten, frei fliegen, kreisen), ZEIT (Datum, Uhrzeit, Zeitraffer, Sonne), WASSER
 * (Pegel von Hand, die See als Platzhalter an/aus), BOOTE (Wind und die Hüllen von {@code cloth}, {@code sail},
 * {@code wake}), FEUERWERK (die Hülle von {@code firework}), DATEN (Verbindung) und TECHNIK (Qualität, Auflösung, Belichtung, HUD, Marken, Foto, Shader,
 * Compute-Test, Physik, Packages); die übrigen sagen, in welcher Phase sie kommen. Offen bleibt, was zuletzt offen war
 * (je Rechner gemerkt). Jeder Regler schreibt direkt in die öffentlichen Felder von Uhr, Kamera, Verdrahtung und
 * Renderer; es gibt keinen zweiten Zustand.
 */
public final class ControlPanel extends JPanel implements javax.swing.Scrollable {
    private final SceneView view;
    private final Renderer r;
    private final SceneClock clock;
    private final JLabel dateLbl = label(""), timeLbl = label(""), sunLbl = small(""), camLbl = small(""), perfLbl = small(""), computeLbl = small(""),
            glLbl = small(""), physLbl = small(""), gelLbl = small(""), hudLbl = small(""), scaleLbl = label(""), qualLbl = small(""), pkgLbl = small(""),
            pegelLbl = label(""), segelLbl = small(""), wetterLbl = small(""), flutLbl = small(""), feuerLbl = small(""), windLbl = label(""), windDirLbl = label(""), dbLbl = small(""),
            booteLbl = small(""), regattaLbl = small(""), klangLbl = small(""), regieLbl = small("");
    private final FSlider time = new FSlider(0, 24 * 60 - 1, (int) (SceneClock.START_HOUR * 60)), scaleSl = new FSlider(50, 100, 85), expSl = new FSlider(-30, 30, 0),
            pegelSl = new FSlider(-150, 250, 0), windSl = new FSlider(0, 50, 11), windDirSl = new FSlider(0, 359, 45);
    private final FCheckBox free = new FCheckBox("Frei fliegen (F)"), orbit = new FCheckBox("Von selbst kreisen (R)"),
            marks = new FCheckBox("Marken (UTM-Raster, Höhenlinien, Wasserlinie, Kern, Orte)"), autoBox = new FCheckBox("Auflösung selbst regeln"),
            meerBox = new FCheckBox("Wasser zeigen"),
            wolkenBox = new FCheckBox("Wolken (com.dan.cloud)"), bautenBox = new FCheckBox("Häuser aus LOD2"), bewuchsBox = new FCheckBox("Bäume und Halme (tree, reed)"),
            luftBox = new FCheckBox("Luftbild DOP20 auf dem Gelände"), oceanBox = new FCheckBox("Ostsee aus com.dan.ocean (sonst Platzhalter)"),
            booteBox = new FCheckBox("Boote zeigen (sail)"), kielBox = new FCheckBox("Kielwasser im Meer (wake)"),
            bauwerkeBox = new FCheckBox("Seebrücken, Wolkenlos, DLRG, Kutter"), koerbeBox = new FCheckBox("Strandkörbe (beachchair, Mitte April bis September)"),
            feuerBox = new FCheckBox("Leuchtfeuer (beacon)"), altBox = new FCheckBox("Alter Leuchtturm brennt wie bis 1972"),
            kliffBox = new FCheckBox("Brodtener Ufer aus cliff");
    private FComboBox lapseBox, qualBox, hudBox;
    private boolean fromScene;
    private int ticks;
    private JPanel target = this;
    private static final Preferences PREFS = prefs();
    private final java.util.List<Object[]> bloecke = new java.util.ArrayList<>();
    private final java.util.List<FCollapsiblePanel> zu = new java.util.ArrayList<>();
    private static final double[] LAPSE = {0, 1, 10, 60, 180, 720, 3600};

    /** Die Reihenfolge der Blöcke (Werkbuch, Abschnitt 11). */
    public static final String[] REIHENFOLGE = {"KAMERA", "ZEIT", "WETTER", "WASSER", "BOOTE", "REGATTA", "FEUERWERK", "MENSCHEN", "KLANG", "REGIEBÜCHER",
            "DATEN", "TECHNIK"};

    private static Preferences prefs() {
        try { return Preferences.userRoot().node("com/dan/timmendorf/bedienfeld"); } catch (RuntimeException | Error e) { return null; }
    }

    public ControlPanel(SceneView view) {
        this.view = view;
        this.r = view.renderer();
        this.clock = r.clock;
        setBackground(Theme.bg());
        setOpaque(true);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(10, 8, 16, 10));

        // ============================================================== KAMERA
        block("KAMERA", true);
        put(camLbl);
        JPanel v1 = grid(2), v2 = grid(2), v3 = grid(2), v4 = grid(2), v5 = grid(2);
        JPanel[] rows = {v1, v1, v2, v2, v3, v3, v4, v4, v5};
        for (Ansichten.Ansicht a : Ansichten.ALLE) {
            FButton b = new FButton(a.key() + " " + a.name());
            b.setToolTipText(Ansichten.describe(a, Renderer.PHASE));
            b.addActionListener(e -> ansicht(a));
            rows[a.key() - 1].add(b);
            view.setKeyAction(java.awt.event.KeyEvent.VK_0 + a.key(), () -> javax.swing.SwingUtilities.invokeLater(() -> ansicht(a)));
            view.setKeyAction(java.awt.event.KeyEvent.VK_NUMPAD0 + a.key(), () -> javax.swing.SwingUtilities.invokeLater(() -> ansicht(a)));
        }
        put(v1);
        put(v2);
        put(v3);
        put(v4);
        put(v5);
        free.addActionListener(e -> { view.setMode(free.isSelected() ? Camera.Mode.FREI : Camera.Mode.KREISEN); view.requestFocusInWindow(); });
        orbit.addActionListener(e -> { view.setAutoOrbit(orbit.isSelected()); view.requestFocusInWindow(); });
        put(free);
        put(orbit);
        view.setModeListener(m -> javax.swing.SwingUtilities.invokeLater(() -> free.setSelected(m == Camera.Mode.FREI)));
        view.setOrbitListener(b -> javax.swing.SwingUtilities.invokeLater(() -> orbit.setSelected(b)));
        JPanel home = grid(1);
        FButton hb = new FButton("Pos1 Grundansicht");
        hb.addActionListener(e -> { view.goHome(); view.requestFocusInWindow(); });
        home.add(hb);
        put(home);
        put(small("<html><body style='width:215px'>Die Ansichten stehen in Phase 1 auf der Bühne (Küste aus OpenStreetMap); "
                + "Phase 2 legt sie auf das DGM1, Phase 9 baut Seebrücke und Schleife. „Mit dem Optimisten“ kommt mit Phase 8.</body></html>"));

        // ============================================================== ZEIT
        block("ZEIT", true);
        put(dateLbl);
        JPanel days = grid(3);
        FButton minus = new FButton("− Tag"), heute = new FButton("25.7.2026"), plus = new FButton("+ Tag");
        minus.addActionListener(e -> { clock.shiftDays(-1); syncFromScene(); view.requestFocusInWindow(); });
        plus.addActionListener(e -> { clock.shiftDays(1); syncFromScene(); view.requestFocusInWindow(); });
        heute.setToolTipText("Zurück zum Start der Szene: " + new SceneClock().label());
        heute.addActionListener(e -> { clock.set(SceneClock.START_DATE, SceneClock.START_HOUR); syncFromScene(); view.requestFocusInWindow(); });
        days.add(minus); days.add(heute); days.add(plus);
        put(days);
        FComboBox monthBox = new FComboBox(new Object[]{"Monat springen …", "20. Januar", "20. Februar", "20. März", "20. April", "20. Mai", "20. Juni",
                "20. Juli", "20. August", "20. September", "20. Oktober", "20. November", "20. Dezember"});
        monthBox.setToolTipText("Zum 20. eines Monats im laufenden Jahr, gleiche Uhrzeit (Strandkörbe und Jahreszeit kommen mit season)");
        monthBox.addActionListener(e -> {
            int i = monthBox.getSelectedIndex();
            if (i <= 0) return;
            clock.set(LocalDate.of(clock.date().getYear(), i, 20), clock.hour());
            monthBox.setSelectedIndex(0);
            syncFromScene();
            view.requestFocusInWindow();
        });
        put(monthBox);
        put(timeLbl);
        put(time);
        time.addChangeListener(e -> { if (!fromScene) clock.setHour(time.getValue() / 60.0); labels(); });
        put(label("Zeitraffer"));
        lapseBox = new FComboBox(new Object[]{"Uhr steht", "Echtzeit", "10-fach", "1 min/s", "3 min/s", "12 min/s (ein Tag in 2 min)", "1 h/s"});
        lapseBox.setSelectedIndex(1);
        lapseBox.addActionListener(e -> { clock.lapse = LAPSE[Math.max(0, lapseBox.getSelectedIndex())]; view.requestFocusInWindow(); });
        put(lapseBox);
        put(sunLbl);
        put(small("<html><body style='width:215px'>Deutsche Zeit (MEZ, im Sommer MESZ). An der Seebrücke steht die Sonne rund 17 Minuten nach "
                + "12 Uhr MEZ im Süden; von Mai bis August geht sie über der Bucht auf. Himmel, Sonne, Mond und Sterne rechnet ab Phase 4 com.dan.sky.</body></html>"));

        // ============================================================== WETTER (Phase 13)
        block("WETTER", true);
        // seit Phase 13: die Wetterregie (com.dan.weather) mit den Lagen der Bucht, Regen, Nebel, Gewitter, Jahr, Tiere
        put(wetterLbl);
        java.util.List<Object> lagen = new java.util.ArrayList<>();
        lagen.add("Von Hand (Regler unter BOOTE)");
        for (com.dan.timmendorf.wiring.Wetterregie.Lage l : com.dan.timmendorf.wiring.Wetterregie.Lage.values()) lagen.add("Lage: " + l.label);
        lagen.add("Freie Regie (Markow-Kette)");
        lagen.add("Regiebuch „Ein Sturmtag“");
        FComboBox lageBox = new FComboBox(lagen.toArray());
        lageBox.setSelectedIndex(1 + r.wiring.regie.lage().ordinal());
        lageBox.addActionListener(e -> {
            int i = lageBox.getSelectedIndex(), n = com.dan.timmendorf.wiring.Wetterregie.Lage.values().length;
            if (i <= 0) r.wiring.regie.hand();
            else if (i <= n) r.wiring.regie.lage(com.dan.timmendorf.wiring.Wetterregie.Lage.values()[i - 1]);
            else if (i == n + 1) r.wiring.regie.frei();
            else r.wiring.regie.sturmtag();
            labels();
            view.requestFocusInWindow();
        });
        put(lageBox);
        JPanel uw = grid(2);
        FButton blitz = new FButton("Blitz über der Bucht"), turm = new FButton("Blitz ins Maritim");
        blitz.addActionListener(e -> { r.unwetter.blitzVonHand(r.camera.eye.clone(), r.camera.fwd.clone(), false); view.requestFocusInWindow(); });
        turm.addActionListener(e -> { r.unwetter.blitzVonHand(r.camera.eye.clone(), r.camera.fwd.clone(), true); view.requestFocusInWindow(); });
        uw.add(blitz);
        uw.add(turm);
        put(uw);
        FCheckBox regenBox = new FCheckBox("Regen (rain)"), nebelBox = new FCheckBox("Seenebel (fog)"), gewitterBox = new FCheckBox("Gewitter (lightning)"),
                jahrBox = new FCheckBox("Schnee, Eis, Reif (season)"), lebenBox = new FCheckBox("Möwen, Schwäne, Kormorane, Seehund (flock, seal)");
        regenBox.setSelected(r.unwetter.showRegen);
        regenBox.addActionListener(e -> { r.unwetter.showRegen = regenBox.isSelected(); view.requestFocusInWindow(); });
        nebelBox.setSelected(r.unwetter.showNebel);
        nebelBox.addActionListener(e -> { r.unwetter.showNebel = nebelBox.isSelected(); view.requestFocusInWindow(); });
        gewitterBox.setSelected(r.unwetter.showGewitter);
        gewitterBox.addActionListener(e -> { r.unwetter.showGewitter = gewitterBox.isSelected(); view.requestFocusInWindow(); });
        jahrBox.setSelected(r.showJahr);
        jahrBox.addActionListener(e -> { r.showJahr = jahrBox.isSelected(); view.requestFocusInWindow(); });
        lebenBox.setSelected(r.showLeben);
        lebenBox.addActionListener(e -> { r.showLeben = lebenBox.isSelected(); view.requestFocusInWindow(); });
        put(regenBox);
        put(nebelBox);
        put(gewitterBox);
        put(jahrBox);
        put(lebenBox);
        // seit Phase 20: Flugsand wie in Sylt (com.dan.dune mit dem Wind aus com.dan.wind)
        sub("Flugsand am Strand");
        FCheckBox sandBox = new FCheckBox("Flugsand (com.dan.dune, Wind aus com.dan.wind)"), koernerBox = new FCheckBox("Körner zeigen (Sandfahnen)");
        sandBox.setSelected(r.sand.an);
        sandBox.addActionListener(e -> { r.sand.an = sandBox.isSelected(); view.requestFocusInWindow(); });
        koernerBox.setSelected(r.showKoerner);
        koernerBox.addActionListener(e -> { r.showKoerner = koernerBox.isSelected(); view.requestFocusInWindow(); });
        put(sandBox);
        put(koernerBox);
        FButton sturmB = new FButton("Sandsturm am Strand");
        sturmB.addActionListener(e -> {
            r.sandsturm();
            lageBox.setSelectedIndex(0);
            sandBox.setSelected(true);
            koernerBox.setSelected(true);
            labels();
            view.requestFocusInWindow();
        });
        put(sturmB);
        // seit Phase 21: Relief und Korn des Strands aus dem Asset (com.dan.beach)
        FCheckBox strandBox = new FCheckBox("Strand aus dem Asset (beach: Relief und Korn am Auge)");
        strandBox.setSelected(r.showStrand);
        strandBox.addActionListener(e -> { r.showStrand = strandBox.isSelected(); view.requestFocusInWindow(); });
        put(strandBox);
        put(small("<html><body style='width:215px'>Seit Phase 20: Flugsand wie in Sylt. Der Wind kommt aus einem Feld über dem Kern "
                + "(im Lee der Promenade und des Kurparks schwächer). Nasser Sand am Wasser und nach Regen fliegt nicht, bei Schnee nichts. "
                + "Ab etwa Bft 5 über trockenem Strand ziehen Sandfahnen zwischen den Körben. „Sandsturm am Strand“: Bft 9 auflandig, "
                + "trocken, Blick zwischen den Körben. Seit Phase 21 hat der Sand am Auge Relief und Korn aus dem Asset sand_dunes_gltf.zip "
                + "(com.dan.beach): zertreten zwischen den Körben, glatt am Wasser.</body></html>"));
        sub("Ebenen");
        wolkenBox.setSelected(r.showClouds);
        wolkenBox.addActionListener(e -> { r.showClouds = wolkenBox.isSelected(); view.requestFocusInWindow(); });
        put(wolkenBox);
        luftBox.setSelected(r.showLuftbild);
        luftBox.addActionListener(e -> { r.showLuftbild = luftBox.isSelected(); view.requestFocusInWindow(); });
        put(luftBox);
        bautenBox.setSelected(r.showBauten);
        bautenBox.addActionListener(e -> { r.showBauten = bautenBox.isSelected(); view.requestFocusInWindow(); });
        put(bautenBox);
        bewuchsBox.setSelected(r.showBewuchs);
        bewuchsBox.addActionListener(e -> { r.showBewuchs = bewuchsBox.isSelected(); view.requestFocusInWindow(); });
        put(bewuchsBox);
        // seit Phase 9: Bauwerke am Wasser, Strandkörbe, Leuchtfeuer, Brodtener Ufer
        bauwerkeBox.setSelected(r.showBauwerke);
        bauwerkeBox.addActionListener(e -> { r.showBauwerke = bauwerkeBox.isSelected(); view.requestFocusInWindow(); });
        put(bauwerkeBox);
        koerbeBox.setSelected(r.showKoerbe);
        koerbeBox.addActionListener(e -> { r.showKoerbe = koerbeBox.isSelected(); view.requestFocusInWindow(); });
        put(koerbeBox);
        feuerBox.setSelected(r.showFeuer);
        feuerBox.addActionListener(e -> { r.showFeuer = feuerBox.isSelected(); view.requestFocusInWindow(); });
        put(feuerBox);
        altBox.setSelected(r.wiring.leuchtfeuer.historisch());
        altBox.addActionListener(e -> { r.wiring.leuchtfeuer.setHistorisch(altBox.isSelected()); view.requestFocusInWindow(); });
        put(altBox);
        kliffBox.setSelected(r.showKliff);
        kliffBox.addActionListener(e -> { r.showKliff = kliffBox.isSelected(); view.requestFocusInWindow(); });
        put(kliffBox);
        put(small("<html><body style='width:215px'>Seit Phase 4: Himmel, Sonne, Mond und Sterne aus com.dan.sky, Schönwetter-Cumulus "
                + "aus com.dan.cloud mit Schatten, Belichtung wie das Auge. Seit Phase 13 die Wetterregie (com.dan.weather) mit den Lagen der "
                + "Lübecker Bucht; der Seegang folgt dem Wind über der Streichlänge (nach Nordost offen, vom Land fast glatt). Schnee, Eis und "
                + "Reif rechnet com.dan.season aus einem gewürfelten Jahr nach dem Klima; im Winter friert die Bucht vom Rand her zu. "
                + "Die Ostlage mit Windstau kommt mit der Sturmflut (Phase 14).</body></html>"));

        // ============================================================== WASSER
        block("WASSER", true);
        put(pegelLbl);
        put(pegelSl);
        pegelSl.addChangeListener(e -> { if (!fromScene) r.wiring.pegel = pegelSl.getValue() / 100.0; labels(); });
        // seit Phase 14: die Sturmflut vom 19. bis 21. Oktober 2023
        FCheckBox flutBox = new FCheckBox("Sturmflut 2023 nach dem Fahrplan (tide, spray, cliff)");
        flutBox.setSelected(r.wiring.sturmflutAn && r.showSturmflut);
        flutBox.addActionListener(e -> { r.wiring.sturmflutAn = flutBox.isSelected(); r.showSturmflut = flutBox.isSelected(); view.requestFocusInWindow(); });
        put(flutBox);
        JPanel sf = grid(2);
        FButton flut1 = new FButton("20.10.2023 20:00"), flut2 = new FButton("Scheitel 23:00");
        flut1.setToolTipText("Die Uhr auf den Abend der Sturmflut, Blick vom Strand auf die Seebrücke");
        flut2.setToolTipText("Pegel Travemünde 1,77 m NHN (belegt): Wasser auf der Promenade");
        flut1.addActionListener(e -> { clock.set(java.time.LocalDate.of(2023, 10, 20), 20.0); r.zurSturmflut(false); syncFromScene(); view.requestFocusInWindow(); });
        flut2.addActionListener(e -> { clock.set(java.time.LocalDate.of(2023, 10, 20), 23.0); r.zurSturmflut(true); syncFromScene(); view.requestFocusInWindow(); });
        sf.add(flut1);
        sf.add(flut2);
        put(sf);
        put(flutLbl);
        meerBox.setSelected(r.showMeer);
        meerBox.addActionListener(e -> { r.showMeer = meerBox.isSelected(); view.requestFocusInWindow(); });
        put(meerBox);
        oceanBox.setSelected(r.showOcean);
        oceanBox.addActionListener(e -> { r.showOcean = oceanBox.isSelected(); view.requestFocusInWindow(); });
        put(oceanBox);
        put(small("<html><body style='width:215px'>Pegel von Hand (m über NHN). Die Ostsee hat kaum Gezeiten; ab Phase 13 kommt der "
                + "Windstau aus com.dan.tide, Phase 14 bringt die Sturmflut vom 20./21.10.2023. Die Bucht aus com.dan.ocean seit Phase 4, "
                + "die Brandung an den Riffen mit surf in Phase 7 und 8.</body></html>"));

        // ============================================================== BOOTE (Hüllen)
        block("BOOTE", true);
        put(segelLbl);
        put(windLbl);
        put(windSl);
        put(windDirLbl);
        put(windDirSl);
        // seit Phase 13: wer den Wind von Hand stellt, schaltet die Wetterregie ab
        windSl.addChangeListener(e -> { if (!fromScene) { r.wiring.regie.hand(); r.wiring.windSpeed = windSl.getValue() / 2.0; } labels(); });
        windDirSl.addChangeListener(e -> { if (!fromScene) { r.wiring.regie.hand(); r.wiring.windFromDeg = windDirSl.getValue(); } labels(); });
        JPanel wl = grid(3);
        FButton seewind = new FButton("Seewind"), west = new FButton("Westwind"), flaute = new FButton("Flaute");
        seewind.addActionListener(e -> wind(5.5, 45));
        west.addActionListener(e -> wind(10, 260));
        flaute.addActionListener(e -> wind(1, 90));
        wl.add(seewind); wl.add(west); wl.add(flaute);
        put(wl);
        put(small("<html><body style='width:215px'>Strandflagge aus com.dan.cloth (XPBD), Boote aus com.dan.sail (Gleichgewicht der Kräfte, "
                + "Wende, Halse, Kentern), Kielwasser aus com.dan.wake (Kelvin-Muster aus Bug und Heck). Der Wind der Boote hat Böen, die mit "
                + "ihm ziehen.</body></html>"));

        // ============================================================== BOOTE (Phase 8a)
        block("BOOTE", true);
        put(booteLbl);
        booteBox.setSelected(r.showBoote);
        booteBox.addActionListener(e -> { r.showBoote = booteBox.isSelected(); view.requestFocusInWindow(); });
        put(booteBox);
        FButton polare = new FButton("Polare (P)");
        polare.setToolTipText("Die Polare eines Boots im kleinen Fenster, mit dem Punkt, an dem es gerade segelt (F45 A)");
        polare.addActionListener(e -> PolarFenster.zeigen(javax.swing.SwingUtilities.getWindowAncestor(this), r));
        put(polare);
        view.setKeyAction(java.awt.event.KeyEvent.VK_P, () -> javax.swing.SwingUtilities.invokeLater(() -> PolarFenster.zeigen(javax.swing.SwingUtilities.getWindowAncestor(this), r)));
        kielBox.setSelected(r.meer.showKiel);
        kielBox.addActionListener(e -> { r.meer.showKiel = kielBox.isSelected(); view.requestFocusInWindow(); });
        put(kielBox);
        JPanel bt = grid(1);
        FButton imOpti = new FButton("Mit dem Optimisten (O)"), begleit = new FButton("Begleitboot des Optimisten (B)");
        imOpti.addActionListener(e -> { r.mitOptimist(); view.requestFocusInWindow(); });
        begleit.addActionListener(e -> { r.begleitboot(false); view.requestFocusInWindow(); });
        bt.add(imOpti);
        bt.add(begleit);
        put(bt);
        view.setKeyAction(java.awt.event.KeyEvent.VK_O, () -> javax.swing.SwingUtilities.invokeLater(r::mitOptimist));
        view.setKeyAction(java.awt.event.KeyEvent.VK_B, () -> javax.swing.SwingUtilities.invokeLater(() -> r.begleitboot(false)));
        put(small("<html><body style='width:215px'>Der Optimist segelt ein Dreieck vor der Seebrücke; acht Optimisten der Segelschule vor "
                + "Niendorf (Kinder, ab und zu kentert eines); zwei Fahrtenyachten, ein Gaffelschoner, zwei Hobie-Kats, zwei Kiter vor "
                + "Scharbeutz; die Passat liegt mit festgemachten Segeln am Priwall; zwei Fähren laufen die Trave hinab, ihr Schwell kommt "
                + "Minuten später am Strand an. R oder F beendet die Mitfahrt.</body></html>"));

        // ============================================================== REGATTA (Phase 8)
        block("REGATTA", true);
        put(regattaLbl);
        JPanel rg = grid(2);
        FButton spitze = new FButton("Spitze (G)"), neu = new FButton("Neuer Start");
        spitze.addActionListener(e -> { r.begleitboot(true); view.requestFocusInWindow(); });
        neu.addActionListener(e -> { r.wiring.flotte.neuerStart(); view.requestFocusInWindow(); });
        rg.add(spitze);
        rg.add(neu);
        put(rg);
        view.setKeyAction(java.awt.event.KeyEvent.VK_G, () -> javax.swing.SwingUtilities.invokeLater(() -> r.begleitboot(true)));
        put(small("<html><body style='width:215px'>20 J/70 auf der Bahn aus TD_NET_SEE vor Timmendorf (F13 A): Startverfahren 5–4–1–0, "
                + "zwei Runden auf und ab, Wegerecht nach den Regeln 10 bis 12 vereinfacht, Abdeckung in Lee der Segel. Nach dem Lauf "
                + "folgt der nächste.</body></html>"));

        // ============================================================== FEUERWERK (seit Phase 12 der Abend)
        block("FEUERWERK", true);
        put(feuerLbl);
        FComboBox planBox = new FComboBox(new Object[]{com.dan.timmendorf.wiring.Wiring.Plan.GROSS.label, com.dan.timmendorf.wiring.Wiring.Plan.VORFUEHRUNG.label});
        planBox.setSelectedIndex(r.wiring.plan.ordinal());
        planBox.addActionListener(e -> { r.wiring.plan = com.dan.timmendorf.wiring.Wiring.Plan.values()[Math.max(0, planBox.getSelectedIndex())]; view.requestFocusInWindow(); });
        put(planBox);
        FCheckBox abendBox = new FCheckBox("Von selbst am 1. August um 22:45");
        abendBox.setSelected(r.wiring.feuerwerkAbends);
        abendBox.addActionListener(e -> { r.wiring.feuerwerkAbends = abendBox.isSelected(); view.requestFocusInWindow(); });
        put(abendBox);
        JPanel fw = grid(2);
        FButton zuenden = new FButton("Jetzt zünden (Z)"), hin = new FButton("Zum Abend: 22:30");
        zuenden.addActionListener(e -> { feuerwerk(); view.requestFocusInWindow(); });
        hin.setToolTipText("Uhr auf den 1. August 2026, 22:30 (Mondaufgang 22:04, Feuerwerk 22:45), Blick von der Promenade zum Ponton");
        hin.addActionListener(e -> {
            clock.set(r.wiring.feuerwerkTag, r.wiring.feuerwerkUhr - 0.25);
            r.zumFeuerwerk();
            syncFromScene();
            view.requestFocusInWindow();
        });
        fw.add(zuenden);
        fw.add(hin);
        put(fw);
        FCheckBox lichterBox = new FCheckBox("Lichter der Stadt und Spiegelung (lamp)"), fwBox = new FCheckBox("Feuerwerk zeigen (firework)");
        lichterBox.setSelected(r.showLichter);
        lichterBox.addActionListener(e -> { r.showLichter = lichterBox.isSelected(); view.requestFocusInWindow(); });
        fwBox.setSelected(r.showFeuerwerk);
        fwBox.addActionListener(e -> { r.showFeuerwerk = fwBox.isSelected(); view.requestFocusInWindow(); });
        put(lichterBox);
        put(fwBox);
        view.setKeyAction(java.awt.event.KeyEvent.VK_Z, () -> javax.swing.SwingUtilities.invokeLater(this::feuerwerk));
        put(small("<html><body style='width:215px'>com.dan.firework vom Ponton vor der Seebrücke (F17 A): Sterne mit Farbe aus dem Spektrum, "
                + "Funkenschweife, Rauch im Wind, ein Licht je Bombe auf Gelände, Häuser und Wasser, die Spiegelung in der Bucht. Das Feuerwerk "
                + "folgt der Uhr: springt sie, steht es, wo es zu dieser Zeit wäre. Ab 50 min vorher kommen die Zuschauer an Promenade, Strand "
                + "und Seebrücke, danach gehen sie. Die Lichter (com.dan.lamp): Laternen und Straßenleuchten als LED 4 000 K mit "
                + "Dämmerungsschalter, Fenster nach der Uhrzeit. Den Knall hört man mit Ton an (KLANG), mit der Laufzeit des Schalls.</body></html>"));

        // ============================================================== spätere Blöcke
        // ============================================================== MENSCHEN (Phase 10a)
        block("MENSCHEN", true);
        FCheckBox menschBox = new FCheckBox("Menschen (route, figure)"), verkehrBox = new FCheckBox("Autos und Busse");
        menschBox.setSelected(r.wiring.showMenschen);
        menschBox.addActionListener(e -> { r.wiring.showMenschen = menschBox.isSelected(); view.requestFocusInWindow(); });
        put(menschBox);
        verkehrBox.setSelected(r.wiring.showVerkehr);
        verkehrBox.addActionListener(e -> { r.wiring.showVerkehr = verkehrBox.isSelected(); view.requestFocusInWindow(); });
        put(verkehrBox);
        put(small("<html><body style='width:215px'>Spaziergänger auf dem Fußnetz (com.dan.route: Ziele, Gehen nach Weidmann, Stau auf "
                + "der Seebrücke), Zuschauer auf der Schleife mit Blick zur Regatta, Badegäste im Strandkorb, auf dem Handtuch und im Wasser, "
                + "Leute auf der Kaimauer in Niendorf (com.dan.figure: Sitzen, Liegen, Badezeug). Wie viele, folgt aus Monat und Stunde. Autos "
                + "auf dem Straßennetz, Busse nach Fahrplan über die Haltestellen.</body></html>"));
        // ============================================================== KLANG (Phase 15)
        block("KLANG", false);
        com.dan.timmendorf.gl.Klang kl = r.klang;
        FCheckBox tonBox = new FCheckBox("Ton an (Lautsprecher)");
        tonBox.setSelected(kl.on);
        tonBox.addActionListener(e -> { kl.setOn(tonBox.isSelected()); tonBox.setSelected(kl.on); view.requestFocusInWindow(); });
        put(tonBox);
        put(label("Lautstärke"));
        FSlider lautSl = new FSlider(0, 200, 100);
        lautSl.addChangeListener(e -> kl.s.volume = lautSl.getValue() / 100.0);
        put(lautSl);
        FCheckBox ohrBox = new FCheckBox("Ohr passt sich an (leise Nacht hörbar)");
        ohrBox.setSelected(kl.s.autoGain);
        ohrBox.addActionListener(e -> { kl.s.autoGain = ohrBox.isSelected(); view.requestFocusInWindow(); });
        put(ohrBox);
        String[][] teile = {{"Brandung, See, Pfähle", "brandung"}, {"Wind und Flaggen", "wind"}, {"Boote, Regatta, Fähren", "boote"}, {"Fallen im Hafen", "hafen"},
                {"Feuerwerk", "feuerwerk"}, {"Promenade (Stimmen, Schritte)", "menschen"}, {"Tiere", "tiere"}, {"Wetter, Sturmflut", "wetter"}};
        JPanel tg = grid(2);
        for (String[] t : teile) {
            FCheckBox b = new FCheckBox(t[0]);
            b.setSelected(true);
            b.addActionListener(e -> {
                boolean on = b.isSelected();
                switch (t[1]) {
                    case "brandung" -> kl.brandungAn = on;
                    case "wind" -> kl.windAn = on;
                    case "boote" -> kl.booteAn = on;
                    case "hafen" -> kl.hafenAn = on;
                    case "feuerwerk" -> kl.feuerwerkAn = on;
                    case "menschen" -> kl.menschenAn = on;
                    case "tiere" -> kl.tiereAn = on;
                    default -> kl.wetterAn = on;
                }
                view.requestFocusInWindow();
            });
            tg.add(b);
        }
        put(tg);
        put(klangLbl);
        put(small("<html><body style='width:215px'>com.dan.soundscape, alles erzeugt, ohne Audiodateien (F33 A): Brandung der Ostsee auf den Riffen, "
                + "Wellen an den Pfählen, Fallen an den Masten im Niendorfer Hafen, Segel schlagen beim Anluven und in der Wende, Winschen, "
                + "Startsignale der Regatta, Fährhorn beim Ablegen, Feuerwerk mit Laufzeit (erst das Bild, dann der Knall), Stimmen und Schritte "
                + "an der Promenade und auf dem Holzdeck der Seebrücke, Möwen, Schwäne, Kormorane. Das Ohr ist die Kamera. Hörproben ohne "
                + "Grafikkarte: ant hoerproben.</body></html>"));
        // ============================================================== REGIEBÜCHER (Phase 16)
        block("REGIEBÜCHER", true);
        put(regieLbl);
        java.util.List<com.dan.timmendorf.scene.Regie.Book> books;
        JLabel buchInfo = small("");
        try { books = com.dan.timmendorf.scene.Regie.all(); }
        catch (RuntimeException ex) { books = java.util.List.of(); buchInfo.setText("Regiebücher nicht lesbar: " + ex.getMessage()); }
        FComboBox bookBox = new FComboBox(books.toArray());
        regieBox = bookBox;
        put(bookBox);
        Runnable showAbout = () -> {
            if (bookBox.getSelectedItem() instanceof com.dan.timmendorf.scene.Regie.Book b)
                buchInfo.setText("<html><body style='width:215px'>" + esc(b.about()) + String.format(Locale.GERMAN, " · %d:%02d min ohne Warten, %d Fotos",
                        (int) b.length() / 60, (int) b.length() % 60, b.fotos()) + "</body></html>");
        };
        bookBox.addActionListener(e -> { showAbout.run(); view.requestFocusInWindow(); });
        showAbout.run();
        JPanel bookRow = grid(2);
        FButton bookGo = new FButton("Von vorn"), bookStop = new FButton("Anhalten");
        bookGo.setToolTipText("Das Regiebuch von vorn: Datum, Uhrzeit, Wetter, Ansichten, Fahrten; am Ende ein Blatt mit den Fotos in docs/status");
        FCheckBox imKino = new FCheckBox("Im Kino zeigen (mit Ton)");
        imKino.setSelected(PREFS == null || PREFS.getBoolean("kino", true));
        imKino.setToolTipText("Ganzer Bildschirm ohne Bedienfeld, Breitwand mit Balken, Vor- und Abspann, Untertitel aus den Stichworten, "
                + "Überblendung bei jedem Schnitt, der Ton blendet mit. Esc beendet das Kino (das Regiebuch läuft weiter), Leertaste hält an, F12 fotografiert.");
        imKino.addActionListener(e -> { if (PREFS != null) PREFS.putBoolean("kino", imKino.isSelected()); view.requestFocusInWindow(); });
        bookGo.addActionListener(e -> {
            if (bookBox.getSelectedItem() instanceof com.dan.timmendorf.scene.Regie.Book b)
                r.imBild(() -> { if (imKino.isSelected()) r.regisseur.kino(b); else r.regisseur.start(b); });
            view.requestFocusInWindow();
        });
        bookStop.addActionListener(e -> { r.imBild(r.regisseur::stop); view.requestFocusInWindow(); });
        bookRow.add(bookGo);
        bookRow.add(bookStop);
        put(bookRow);
        put(imKino);
        put(buchInfo);
        // seit Phase 17 (F43 A): eigene Regiebücher mit einem einfachen Editor, gespeichert in TD_REGIEBUCH oder in der Ablage
        JPanel edRow = grid(2);
        FButton bearbeiten = new FButton("Bearbeiten …"), neuBuch = new FButton("Neu aus Vorlage …");
        bearbeiten.setToolTipText("Das gewählte Regiebuch im Editor; mitgelieferte speichert man unter eigenem Namen");
        bearbeiten.addActionListener(e -> { if (bookBox.getSelectedItem() instanceof com.dan.timmendorf.scene.Regie.Book b) regiebuchEditor(b.text(), b.name()); });
        neuBuch.setToolTipText("Ein neues Regiebuch aus „Mein Weg zum Strand“ als Vorlage");
        neuBuch.addActionListener(e -> regiebuchEditor(com.dan.timmendorf.scene.Regie.text("weg.txt").replaceFirst("(?m)^Regiebuch:.*$", "Regiebuch: Mein Regiebuch"), null));
        edRow.add(bearbeiten);
        edRow.add(neuBuch);
        put(edRow);
        put(label("Mein Weg zum Strand: Start"));
        com.dan.ftextfield.FTextfield wegFeld = new com.dan.ftextfield.FTextfield();
        wegFeld.setToolTipText("Ein Ort aus OpenStreetMap (Name oder Teil davon, etwa „Kurpark“) oder „Ost Nord“ in UTM 32; leer: der Bahnhof. Wird nicht gespeichert.");
        wegFeld.addActionListener(e -> { r.regisseur.wegStart = wegFeld.getText(); view.requestFocusInWindow(); });
        wegFeld.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            void neu() { r.regisseur.wegStart = wegFeld.getText(); }
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { neu(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { neu(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { neu(); }
        });
        put(wegFeld);
        JPanel fa = grid(2);
        String[][] fahrten = {{"Seebrücke", "seebruecke"}, {"Promenade", "promenade"}, {"Drohne", "drohne"}, {"Möwe", "moewe"}, {"Mein Weg", "weg"}};
        for (String[] fz : fahrten) {
            FButton b = new FButton(fz[0]);
            b.setToolTipText("Kamerafahrt „" + fz[0] + "“ jetzt (R oder F beendet)");
            b.addActionListener(e -> { r.imBild(() -> r.regisseur.fahrtJetzt(fz[1])); view.requestFocusInWindow(); });
            fa.add(b);
        }
        put(fa);
        put(small("<html><body style='width:215px'>Fünf Regiebücher als Text (scene/regie/*.txt): „Ein Tag am Strand“ (1.8.2026 vom Sonnenaufgang bis "
                + "zum Feuerwerk), „Die Regatta“, „Die Sturmflut“ (Oktober 2023), „Ein Jahr an der Bucht“, „Mein Weg zum Strand“ (zu Fuß über das "
                + "Fußnetz von einem Ort deiner Wahl). Jedes setzt Datum, Uhr, Zeitraffer und Wetter, wechselt Ansichten und Fahrten, wartet auf die "
                + "Uhr, den Startschuss, das Ende des Feuerwerks oder der Fahrt („warte“) und fotografiert; am Ende liegt ein Blatt mit allen Fotos "
                + "in docs/status (Regiebuch_….png). Ohne Fenster: ant regiebuecher.</body></html>"));

        // ============================================================== DATEN
        datenBlock();   // seit Phase 17 (F37–F49 A)

        // ============================================================== TECHNIK
        block("TECHNIK", false);
        put(perfLbl);
        put(label("Qualität"));
        qualBox = new FComboBox(Quality.values());
        qualBox.setSelectedItem(r.quality());
        qualBox.addActionListener(e -> { r.setQuality((Quality) qualBox.getSelectedItem()); view.requestFocusInWindow(); });
        put(qualBox);
        put(qualLbl);
        autoBox.setSelected(r.autoScale);
        autoBox.addActionListener(e -> { r.autoScale = autoBox.isSelected(); if (!r.autoScale) r.renderScale = scaleSl.getValue() / 100.0; view.requestFocusInWindow(); });
        put(autoBox);
        put(scaleLbl);
        put(scaleSl);
        scaleSl.addChangeListener(e -> {
            if (fromScene) return;
            r.autoScale = false;
            autoBox.setSelected(false);
            r.renderScale = scaleSl.getValue() / 100.0;
        });
        put(label("Belichtung (Blenden von Hand)"));
        put(expSl);
        expSl.addChangeListener(e -> { if (!fromScene) r.exposureBias = expSl.getValue() / 10.0; });
        put(label("HUD"));
        hudBox = new FComboBox(new Object[]{"kurz", "voll", "aus"});
        hudBox.setSelectedIndex(r.showHud ? (r.hudFull ? 1 : 0) : 2);   // seit Phase 19 beim Start „aus“
        hudBox.addActionListener(e -> {
            int i = hudBox.getSelectedIndex();
            r.showHud = i != 2;
            r.hudFull = i == 1;
            view.requestFocusInWindow();
        });
        put(hudBox);
        marks.setSelected(r.showMarks);
        marks.addActionListener(e -> { r.showMarks = marks.isSelected(); view.requestFocusInWindow(); });
        put(marks);
        JPanel pic = grid(2);
        FButton photo = new FButton("Foto (F12)"), shaders = new FButton("Shader neu (F5)");
        photo.addActionListener(e -> { r.photoRequest = true; view.requestFocusInWindow(); });
        shaders.addActionListener(e -> { r.requestShaderReload(); view.requestFocusInWindow(); });
        pic.add(photo); pic.add(shaders);
        put(pic);
        put(small("<html><body style='width:215px'>Fotos gehen nach docs/status. Marken: weiß der Rand des Fernfelds, gold der Kern "
                + "(dort ab Phase 2 das DGM1 zu 1 m), blau das 1-km-Raster in UTM und die Höhenlinien alle 5 m, hellblau die Wasserlinie NHN 0; "
                + "die Orte weiß (Seezeichen), holzbraun (Brücken), hell (Orte), blau (Strand), rot (Steilufer).</body></html>"));
        sub("Gelände");
        put(gelLbl);
        sub("Grafikkarte");
        put(computeLbl);
        put(glLbl);
        put(physLbl);
        sub("Packages");
        put(pkgLbl);
        sub("Alle Zeilen des HUD");
        put(hudLbl);

        reihenfolge();
        for (Object[] b : bloecke) ((FCollapsiblePanel) b[0]).setBodyComponent((JComponent) b[1]);
        for (FCollapsiblePanel p : zu) p.setExpanded(false, false);
        add(Box.createVerticalGlue());
        view.setKeyAction(java.awt.event.KeyEvent.VK_F1, () -> javax.swing.SwingUtilities.invokeLater(this::tasten));
        view.setKeyAction(java.awt.event.KeyEvent.VK_SPACE, () -> javax.swing.SwingUtilities.invokeLater(() ->
                lapseBox.setSelectedIndex(clock.lapse == 0 ? 1 : 0)));
        syncFromScene();
        new Timer(500, e -> {
            // Befund aus Glacier: solange eine Auswahlliste offen ist, nichts auffrischen
            if (popupOpen(this) || menuOpen()) return;
            int hi = r.showHud ? (r.hudFull ? 1 : 0) : 2;   // seit Phase 19: Taste H, die Auswahl folgt
            if (hudBox.getSelectedIndex() != hi) hudBox.setSelectedIndex(hi);
            syncFromScene();
            if (ticks++ % 2 == 0) { technik(); datenZeilen(); }
            nachmessen();
        }).start();
    }

    // ============================================================== seit Phase 17: DATEN (F37–F49 A, aus Sylt)

    private com.dan.timmendorf.data.Chronik chronik;
    private final JLabel datenLbl = small(""), standLbl = small(""), presetLbl = small(""), protLbl = small(""), gemerktLbl = small(""), sitzungLbl = small("");
    private FComboBox paketBox, presetBox, sitzungBox, gemerktBox, regieBox;
    private final FCheckBox mitUhr = new FCheckBox("mit Uhr und Lage");
    private java.util.List<com.dan.timmendorf.data.Daten.PaketPreset> presetListe = java.util.List.of();
    private java.util.List<com.dan.timmendorf.data.Daten.Ansicht> gemerktListe = java.util.List.of();
    private java.util.List<com.dan.timmendorf.data.Daten.Sitzung> sitzungListe = java.util.List.of();
    private volatile String datenNote = "";
    private long naechsteListe;

    /** Die Chronik der laufenden Szene (aus der App, sobald sie steht). */
    public void chronik(com.dan.timmendorf.data.Chronik c) {
        chronik = c;
        neuLesen();
    }

    private static com.dan.timmendorf.data.Speicher sp() { return com.dan.timmendorf.data.Datenseite.speicher(); }

    /** Eine Combo neu füllen, ohne dass ihr Hörer feuert; die Auswahl bleibt, wenn es den Eintrag noch gibt. */
    private void fuellen(FComboBox box, java.util.List<?> l, String wahl) {
        fromScene = true;
        try {
            box.removeAllItems();
            int k = -1;
            for (int i = 0; i < l.size(); i++) { box.addItem(l.get(i)); if (l.get(i).toString().equals(wahl)) k = i; }
            if (k >= 0) box.setSelectedIndex(k); else if (!l.isEmpty()) box.setSelectedIndex(0);
        } finally { fromScene = false; }
    }

    private static String gewaehlt(FComboBox box) { Object o = box.getSelectedItem(); return o == null ? null : o.toString(); }

    /** Im Faden der Daten arbeiten, danach auf dem Swing-Faden weiter; Fehler landen im Protokoll und in einer Meldung. */
    private <T> void hinten(String was, java.util.concurrent.Callable<T> arbeit, java.util.function.Consumer<T> danach) {
        com.dan.store.Dienst.spaeter(() -> {
            try {
                T t = arbeit.call();
                javax.swing.SwingUtilities.invokeLater(() -> danach.accept(t));
            } catch (Exception ex) {
                com.dan.store.Protokoll.fehler("Bedienfeld", was + ": " + ex.getMessage(), ex instanceof RuntimeException ? ex : null);
                javax.swing.SwingUtilities.invokeLater(() -> com.dan.foptionpane.FOptionPane.showMessageDialog(this, was + " ging nicht:\n" + ex.getMessage(),
                        "Timmendorf · " + was, com.dan.foptionpane.FOptionPane.ERROR_MESSAGE));
            }
        });
    }

    /** Block DATEN (F46 A): Verbindung, Speichern und Laden, Sitzungen, Presets, gemerkte Ansichten, Befunde und Fachdaten, Protokoll. */
    private void datenBlock() {
        block("DATEN", false);
        put(datenLbl);
        put(dbLbl);
        JPanel g = grid(2);
        FButton verb = new FButton("Verbinden …"), ohne = new FButton("Ohne Datenbank"), einr = new FButton("Einrichten"), pruef = new FButton("Prüfen"),
                sichern = new FButton("Speichern (Strg+S)"), weiter = new FButton("Weiter wie zuletzt"), ueber = new FButton("Übernehmen"), befunde = new FButton("Befunde …");
        verb.setToolTipText("Mit dem Schema aus db/db.properties verbinden (fragt nach dem Passwort, wenn keins hinterlegt ist)");
        verb.addActionListener(e -> verbinden());
        ohne.setToolTipText("Mit Dateien in " + com.dan.timmendorf.data.DateiSpeicher.ordner() + " weiterarbeiten");
        ohne.addActionListener(e -> {
            com.dan.timmendorf.data.Datenseite.setzen(new com.dan.timmendorf.data.DateiSpeicher(com.dan.timmendorf.data.DateiSpeicher.ordner()));
            neuLesen();
        });
        einr.setToolTipText("Die Skripte aus db/sql ausführen und alles prüfen; löscht nie etwas");
        einr.addActionListener(e -> einrichter(false));
        pruef.setToolTipText("Nur prüfen: Tabellen, Spalten, Sequenzen, Trigger, Sichten, Metadaten, räumliche Indizes, Zahl der Startdaten");
        pruef.addActionListener(e -> einrichter(true));
        sichern.setToolTipText("Den Zustand jetzt sichern (sonst alle 2 min und beim Ende), mit Vorschaubild");
        sichern.addActionListener(e -> speichernJetzt());
        weiter.setToolTipText("Die letzte gesicherte Sitzung vor dieser herstellen: Uhr, Wetter, Kamera, Regler, die laufende Wettfahrt");
        weiter.addActionListener(e -> weiterWieZuletzt());
        ueber.setToolTipText("Chronik, eigene Regiebücher, Ansichten und Presets aus " + com.dan.timmendorf.data.DateiSpeicher.ordner() + " in die Datenbank (je Datei einmal)");
        ueber.addActionListener(e -> uebernehmen());
        befunde.setToolTipText("Die Befunde des Werkbuchs und die Fachdaten (Boote, Regatta, Zündpläne, Sturmflut, Leuchtfeuer, Busse) zum Lesen");
        befunde.addActionListener(e -> befundeZeigen());
        g.add(verb); g.add(ohne); g.add(einr); g.add(pruef); g.add(sichern); g.add(weiter); g.add(ueber); g.add(befunde);
        put(g);
        put(standLbl);
        view.speichern = () -> javax.swing.SwingUtilities.invokeLater(this::speichernJetzt);
        sub("Sitzungen (die letzten 20)");
        sitzungBox = new FComboBox();
        put(sitzungBox);
        put(sitzungLbl);
        sub("Presets der Packages");
        java.util.List<String> pk = new java.util.ArrayList<>();
        for (String k : com.dan.timmendorf.data.Startdaten.PAKETE) pk.add(k.substring(k.lastIndexOf('.') + 1) + " · " + k.substring(0, k.lastIndexOf('.')));
        paketBox = new FComboBox(pk.toArray());
        presetBox = new FComboBox();
        paketBox.addActionListener(e -> { if (!fromScene) listePresets(null); });
        put(paketBox);
        put(presetBox);
        FButton an = new FButton("Anwenden"), unter = new FButton("Speichern unter …"), weg = new FButton("Löschen");
        an.setToolTipText("Alle Felder des Presets in das laufende Objekt der Szene schreiben (Wind und Wolken: die Wetterregie geht auf „von Hand“)");
        an.addActionListener(e -> presetAnwenden());
        unter.setToolTipText("Die Felder, wie sie jetzt in der Szene stehen, als eigenes Preset");
        unter.addActionListener(e -> presetSpeichern());
        weg.setToolTipText("Ein eigenes Preset löschen (mitgelieferte bleiben)");
        weg.addActionListener(e -> presetLoeschen());
        JPanel pg = grid(3);
        pg.add(an);
        pg.add(unter);
        pg.add(weg);
        put(pg);
        put(presetLbl);
        sub("Gemerkte Ansichten (Taste M merkt)");
        gemerktBox = new FComboBox();
        put(gemerktBox);
        mitUhr.setSelected(PREFS == null || PREFS.getBoolean("ansichtMitUhr", true));
        mitUhr.setToolTipText("Uhr, Lage und alle Regler gehören zur Ansicht (sonst nur die Kamera)");
        mitUhr.addActionListener(e -> { if (PREFS != null) PREFS.putBoolean("ansichtMitUhr", mitUhr.isSelected()); view.requestFocusInWindow(); });
        FButton merk = new FButton("Merken (M)"), gh = new FButton("Hin"), gl = new FButton("Löschen");
        merk.addActionListener(e -> ansichtMerken());
        gh.addActionListener(e -> gemerkteAnsicht());
        gl.addActionListener(e -> {
            int i = gemerktBox.getSelectedIndex();
            if (i < 0 || i >= gemerktListe.size()) return;
            long id = gemerktListe.get(i).id();
            hinten("Ansicht löschen", () -> { sp().loeschenAnsicht(id); return null; }, x -> listeGemerkt());
        });
        JPanel gg = grid(3);
        gg.add(merk);
        gg.add(gh);
        gg.add(gl);
        put(gg);
        put(mitUhr);
        put(gemerktLbl);
        view.setKeyAction(java.awt.event.KeyEvent.VK_M, () -> javax.swing.SwingUtilities.invokeLater(this::ansichtMerken));
        sub("Protokoll");
        put(protLbl);
        put(small("<html><body style='width:215px'>Schema TIMMENDORF auf PDBORCL, Teil 2 in db/sql (31 Tabellen TD_, Geometrien in UTM 32N, zehn Sichten "
                + "TD_V_ für QGIS); Teil 1, die Netze, in db/netz. Das Protokoll geht alle 5 s in TD_LOG (90 Tage), der Zustand alle 2 min und beim Ende "
                + "in TD_SITZUNG, dazu ein Vorschaubild. Ohne Datenbank in " + esc(com.dan.timmendorf.data.DateiSpeicher.ordner().getPath())
                + "; beim nächsten Verbinden übernommen.</body></html>"));
    }

    private String paket() { String s = gewaehlt(paketBox); return s == null ? null : s.substring(s.indexOf(" · ") + 3); }
    private String klasse() { String s = gewaehlt(paketBox); return s == null ? null : s.substring(0, s.indexOf(" · ")); }

    private void listePresets(String wahl) {
        String p = paket(), k = klasse();
        if (p == null) return;
        hinten("Presets lesen", () -> sp().presets(p, k), l -> {
            presetListe = l;
            fuellen(presetBox, l, wahl);
            Object o = com.dan.timmendorf.data.Zustand.objekt(r, k);
            txt(presetLbl, "<html><body style='width:215px'>" + l.size() + " Presets · " + (o == null ? "nicht in der Szene" : "in der Szene: " + o.getClass().getSimpleName()) + "</body></html>");
        });
    }

    private void presetAnwenden() {
        int i = presetBox.getSelectedIndex();
        if (i < 0 || i >= presetListe.size()) return;
        var pr = presetListe.get(i);
        Object o = com.dan.timmendorf.data.Zustand.objekt(r, pr.klasse());
        if (o == null) { txt(presetLbl, pr.klasse() + " läuft nicht in der Szene."); return; }
        if (o == r.meer.ocean || o == r.wetter.clouds) r.wiring.regie.hand();
        java.util.List<String> f = com.dan.store.Felder.schreiben(o, pr.felder());
        txt(presetLbl, "<html><body style='width:215px'>„" + esc(pr.name()) + "“ angewandt: " + (pr.felder().size() - f.size()) + " Felder"
                + (f.isEmpty() ? "" : ", nicht: " + esc(String.join(", ", f))) + "</body></html>");
        com.dan.store.Protokoll.info("Preset", pr.klasse() + " ← „" + pr.name() + "“");
        syncFromScene();
        view.requestFocusInWindow();
    }

    private void presetSpeichern() {
        String p = paket(), k = klasse();
        Object o = com.dan.timmendorf.data.Zustand.objekt(r, k);
        if (o == null) { txt(presetLbl, k + " läuft nicht in der Szene."); return; }
        String n = com.dan.foptionpane.FOptionPane.showInputDialog(this, "Name des Presets für " + k, "Timmendorf · Preset speichern", com.dan.foptionpane.FOptionPane.QUESTION_MESSAGE);
        if (n == null || n.isBlank()) return;
        var pr = new com.dan.timmendorf.data.Daten.PaketPreset(p, k, n.strip(), "Aus der Szene am " + LocalDate.now(), com.dan.timmendorf.data.Daten.EIGEN,
                com.dan.store.Felder.lesen(o));
        hinten("Preset speichern", () -> { sp().speichern(pr); return null; }, x -> listePresets(pr.name()));
    }

    private void presetLoeschen() {
        int i = presetBox.getSelectedIndex();
        if (i < 0 || i >= presetListe.size()) return;
        var pr = presetListe.get(i);
        hinten("Preset löschen", () -> { sp().loeschenPreset(pr.paket(), pr.klasse(), pr.name()); return null; }, x -> listePresets(null));
    }

    private void listeGemerkt() {
        hinten("Ansichten lesen", () -> sp().ansichten(), l -> {
            java.util.List<com.dan.timmendorf.data.Daten.Ansicht> g = new java.util.ArrayList<>();
            for (var a : l) if ("GEMERKT".equals(a.art())) g.add(a);
            gemerktListe = g;
            fuellen(gemerktBox, g, null);
            txt(gemerktLbl, "<html><body style='width:215px'>" + g.size() + " gemerkte von " + l.size() + " Ansichten in " + esc(sp().wo()) + "</body></html>");
        });
    }

    /** Ein Bild der Szene auf 320 px Breite als PNG (für gemerkte Ansichten und die Vorschau der Sitzung). */
    public static byte[] vorschau(java.awt.image.BufferedImage img) {
        try {
            java.awt.image.BufferedImage s = new java.awt.image.BufferedImage(320, (int) Math.round(320.0 * img.getHeight() / img.getWidth()), java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g2 = s.createGraphics();
            g2.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.drawImage(img, 0, 0, s.getWidth(), s.getHeight(), null);
            g2.dispose();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(s, "png", bo);
            return bo.toByteArray();
        } catch (Exception ex) {
            return null;
        }
    }

    /** Taste M (F41 A): die Ansicht mit Vorschau merken, auf Wunsch mit Uhr, Lage und allen Reglern. */
    private void ansichtMerken() {
        double[] k = com.dan.timmendorf.data.Zustand.kamera(r);
        long ms = (long) clock.epochMillis();
        boolean uhr = mitUhr.isSelected();
        String lage = r.wiring.regie.lage().label;
        String name = com.dan.timmendorf.data.Daten.text(ms) + " · " + lage;
        String z = uhr ? com.dan.store.Felder.alsText(com.dan.timmendorf.data.Zustand.erfassen(r), "Timmendorf · gemerkte Ansicht") : null;
        r.grabRequest = img -> {
            var a = new com.dan.timmendorf.data.Daten.Ansicht(0, name, null, "GEMERKT", k, null, r.fovy(), uhr, uhr ? ms : null, uhr ? lage : null, z, vorschau(img),
                    com.dan.timmendorf.data.Daten.EIGEN);
            long s = com.dan.store.Dienst.sitzung();
            hinten("Ansicht merken", () -> sp().speichern(a, s > 0 ? s : null), id -> { r.hudMeldung("Ansicht gemerkt: " + name); listeGemerkt(); });
        };
    }

    private void gemerkteAnsicht() {
        int i = gemerktBox.getSelectedIndex();
        if (i < 0 || i >= gemerktListe.size()) return;
        long id = gemerktListe.get(i).id();
        hinten("Ansicht lesen", () -> sp().ansicht(id), a -> {
            if (a.zustand() != null) Weiter.jetzt(r, a.zustand(), "Ansicht " + a.name(), chronik);
            else {
                double[] k = a.kamera();
                r.camera.stopFahrt();
                r.camera.stopMitfahrt();
                r.camera.mode = Camera.Mode.FREI;
                r.camera.place(k[0], k[1], k[2], k[3], k[4]);
                r.camera.snap();
            }
            r.hudMeldung("Ansicht: " + a.name());
            view.setMode(r.camera.mode);
            syncFromScene();
            view.requestFocusInWindow();
        });
    }

    /** Strg+S: den Zustand jetzt sichern (im Faden der Daten), mit Vorschaubild. */
    private void speichernJetzt() {
        datenNote = "sichere …";
        com.dan.store.Dienst.spaeter(() -> {
            com.dan.store.Dienst.sichern(false);
            datenNote = "gesichert " + String.format("%tT", System.currentTimeMillis());
            javax.swing.SwingUtilities.invokeLater(() -> r.hudMeldung("Gesichert in " + sp().wo()));
        });
    }

    /** Nach einem Wechsel des Speichers alle Listen neu lesen. */
    private void neuLesen() {
        if (paketBox == null) return;
        listePresets(null);
        listeGemerkt();
        buecherNeu();
        naechsteListe = 0;
        datenZeilen();
    }

    /** Die Regiebücher im Block REGIEBÜCHER: mitgelieferte und eigene aus dem Speicher. */
    private void buecherNeu() {
        if (regieBox == null) return;
        hinten("Regiebücher lesen", () -> {
            java.util.List<com.dan.timmendorf.scene.Regie.Book> l = new java.util.ArrayList<>();
            for (var rb : sp().regiebuecher()) {
                try { l.add(com.dan.timmendorf.scene.Regie.parse(rb.text())); } catch (RuntimeException ex) { com.dan.store.Protokoll.warnung("Regiebuch", rb.name() + ": " + ex.getMessage()); }
            }
            return l;
        }, l -> { if (!l.isEmpty()) fuellen(regieBox, l, gewaehlt(regieBox)); });
    }

    /** Ein Regiebuch im Editor (F43 A): Text, Prüfen, als eigenes speichern, eigenes löschen. */
    private void regiebuchEditor(String text, String name) {
        com.dan.fDialog.FDialog d = new com.dan.fDialog.FDialog(javax.swing.SwingUtilities.getWindowAncestor(this), "Regiebuch · " + (name == null ? "neu" : name), false);
        JPanel p = d.getComponentPane();
        p.setLayout(new BorderLayout(0, 6));
        com.dan.ftextarea.FTextArea ta = new com.dan.ftextarea.FTextArea(30, 100);
        ta.setText(text);
        ta.setCaretPosition(0);
        ta.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        javax.swing.JScrollPane sc = new javax.swing.JScrollPane(ta);
        JLabel st = small("Format: „m:ss befehl argumente | Text“; Kopf „Regiebuch: Name“, „Beschreibung: …“. Mitgelieferte speichert man unter eigenem Namen.");
        JPanel knoepfe = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0));
        knoepfe.setOpaque(false);
        FButton pr = new FButton("Prüfen"), sv = new FButton("Speichern"), del = new FButton("Löschen"), zu = new FButton("Schließen");
        pr.addActionListener(e -> {
            try {
                var b = com.dan.timmendorf.scene.Regie.parse(ta.getText());
                java.util.List<String> fehlt = new java.util.ArrayList<>();
                for (var z : b.zeilen()) if (z.befehl().equals("ansicht") && !r.regisseur.placeNamed(z.argumente())) fehlt.add("Ansicht " + z.argumente());
                st.setText(String.format(Locale.GERMAN, "„%s“: %d Stichworte, %d Fotos, %d:%02d%s", b.name(), b.zeilen().size(), b.fotos(), (int) b.length() / 60,
                        (int) b.length() % 60, fehlt.isEmpty() ? " · in Ordnung" : " · fehlt: " + String.join(", ", fehlt)));
            } catch (RuntimeException ex) { st.setText("Nicht lesbar: " + ex.getMessage()); }
        });
        sv.addActionListener(e -> {
            String t = ta.getText();
            String n;
            try { n = com.dan.timmendorf.scene.Regie.parse(t).name(); } catch (RuntimeException ex) { st.setText("Nicht lesbar: " + ex.getMessage()); return; }
            var rb = new com.dan.timmendorf.data.Daten.Regiebuch(n, t, null, null, com.dan.timmendorf.data.Daten.EIGEN);
            hinten("Regiebuch speichern", () -> { sp().speichern(rb); return null; }, x -> { st.setText("„" + n + "“ gespeichert in " + sp().wo()); buecherNeu(); });
        });
        del.addActionListener(e -> {
            String n;
            try { n = com.dan.timmendorf.scene.Regie.parse(ta.getText()).name(); } catch (RuntimeException ex) { st.setText("Nicht lesbar: " + ex.getMessage()); return; }
            hinten("Regiebuch löschen", () -> { sp().loeschenRegiebuch(n); return null; }, x -> { st.setText("„" + n + "“ gelöscht"); buecherNeu(); });
        });
        zu.addActionListener(e -> d.dispose());
        knoepfe.add(pr); knoepfe.add(sv); knoepfe.add(del); knoepfe.add(zu);
        p.add(sc, BorderLayout.CENTER);
        JPanel unten = new JPanel(new BorderLayout(0, 4));
        unten.setOpaque(false);
        unten.add(st, BorderLayout.NORTH);
        unten.add(knoepfe, BorderLayout.SOUTH);
        p.add(unten, BorderLayout.SOUTH);
        d.setPreferredDialogSize(new Dimension(980, 680));
        d.setSize(980, 680);
        d.setLocationRelativeTo(this);
        d.setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        d.setVisible(true);
    }

    private void verbinden() {
        datenNote = "verbinde …";
        hinten("Verbinden", Verbindung::konfig, k -> {
            com.dan.store.Db.Konfig kk = k;
            char[] neu = null;
            if (!k.hatPasswort()) {
                com.dan.ftextfield.FPasswordfield pw = new com.dan.ftextfield.FPasswordfield();
                JPanel p = new JPanel(new BorderLayout(0, 6));
                p.add(new JLabel("Passwort für " + k.kurz() + ":"), BorderLayout.NORTH);
                p.add(pw, BorderLayout.CENTER);
                if (com.dan.foptionpane.FOptionPane.showConfirmDialog(this, p, "Timmendorf · Datenbank", com.dan.foptionpane.FOptionPane.OK_CANCEL_OPTION)
                        != com.dan.foptionpane.FOptionPane.OK_OPTION || pw.getPassword().length == 0) { datenNote = ""; return; }
                neu = pw.getPassword();
                kk = k.mitPasswort(neu);
            }
            char[] eingabe = neu;
            com.dan.store.Db.Konfig kf = kk;
            hinten("Verbinden", () -> {
                String[] grund = {""};
                com.dan.timmendorf.data.DbSpeicher d = com.dan.timmendorf.data.Datenseite.verbinden(kf, grund);
                if (d == null) throw new com.dan.store.Ablage.Fehler(grund[0]);
                if (eingabe != null) try { com.dan.store.Db.passwortSpeichern(kf, eingabe); } catch (Exception ex) { com.dan.store.Protokoll.warnung("Daten", "Passwort nicht eingetragen: " + ex.getMessage()); }
                Verbindung.verbunden(kf);
                int st = Verbindung.einrichter().stufe(d.verbindung());
                return new Object[]{d, st};
            }, o -> {
                var d = (com.dan.timmendorf.data.DbSpeicher) o[0];
                int st = (Integer) o[1];
                if (st < Verbindung.VERSION) {
                    if (com.dan.foptionpane.FOptionPane.showConfirmDialog(this, d.wo() + ": Teil 2 ist noch nicht eingerichtet (Stufe " + st + ").\nJetzt einrichten? (löscht nie etwas)",
                            "Timmendorf · Datenbank", com.dan.foptionpane.FOptionPane.YES_NO_OPTION) != com.dan.foptionpane.FOptionPane.YES_OPTION) { d.schliessen(); datenNote = "nicht eingerichtet"; return; }
                    com.dan.timmendorf.data.Datenseite.setzen(d);
                    einrichter(false);
                    return;
                }
                com.dan.timmendorf.data.Datenseite.setzen(d);
                datenNote = "verbunden";
                neuLesen();
            });
        });
    }

    private void einrichter(boolean nurPruefen) {
        if (!(sp() instanceof com.dan.timmendorf.data.DbSpeicher d)) {
            com.dan.foptionpane.FOptionPane.showMessageDialog(this, "Erst mit der Datenbank verbinden („Verbinden …“).", "Timmendorf · Einrichter", com.dan.foptionpane.FOptionPane.ERROR_MESSAGE);
            return;
        }
        datenNote = nurPruefen ? "prüfe …" : "richte ein …";
        hinten(nurPruefen ? "Prüfen" : "Einrichten", () -> {
            var e = Verbindung.einrichter();
            return nurPruefen ? e.pruefen(d.verbindung(), Verbindung.skripte())
                    : e.einrichten(d.verbindung(), Verbindung.skripte(), (t, a) -> datenNote = String.format(Locale.GERMAN, "%.0f %% · %s", a * 100, t));
        }, er -> {
            Startbild.merkeEinrichter(er.kurz());
            datenNote = "";
            if (er.ok()) com.dan.store.Dienst.spaeter(com.dan.store.Dienst::neuBeginnen);
            com.dan.store.Protokoll.info("Einrichter", (nurPruefen ? "Prüfen: " : "Einrichten: ") + er.kurz());
            for (String z : er.zeilen()) if (!z.startsWith("ok") && !z.contains("alles in Ordnung")) com.dan.store.Protokoll.warnung("Einrichter", z);
            JLabel t = small("<html><body style='width:560px'>" + String.join("<br>", er.zeilen()) + "</body></html>");
            javax.swing.JScrollPane spn = new javax.swing.JScrollPane(t);
            spn.setPreferredSize(new Dimension(640, 420));
            com.dan.foptionpane.FOptionPane.showMessageDialog(this, spn, "Timmendorf · " + (nurPruefen ? "Prüfen: " : "Einrichten: ") + er.kurz(),
                    er.ok() ? com.dan.foptionpane.FOptionPane.INFORMATION_MESSAGE : com.dan.foptionpane.FOptionPane.WARNING_MESSAGE);
            neuLesen();
        });
    }

    private void uebernehmen() {
        if (!(sp() instanceof com.dan.timmendorf.data.DbSpeicher d)) {
            com.dan.foptionpane.FOptionPane.showMessageDialog(this, "Erst mit der Datenbank verbinden („Verbinden …“).", "Timmendorf · Übernehmen", com.dan.foptionpane.FOptionPane.ERROR_MESSAGE);
            return;
        }
        var von = new com.dan.timmendorf.data.DateiSpeicher(com.dan.timmendorf.data.DateiSpeicher.ordner());
        datenNote = "übernehme …";
        hinten("Übernehmen", () -> com.dan.timmendorf.data.Uebernahme.uebernehmen(von, d), z -> {
            datenNote = "übernommen";
            com.dan.foptionpane.FOptionPane.showMessageDialog(this, "Aus " + von.wo() + " nach " + d.wo() + ":\n" + z, "Timmendorf · Übernahme",
                    com.dan.foptionpane.FOptionPane.INFORMATION_MESSAGE);
            neuLesen();
        });
    }

    private void weiterWieZuletzt() {
        hinten("Weiter wie zuletzt", () -> {
            var s = sp().letzteSitzung(com.dan.store.Dienst.sitzung());
            if (s == null || s.zustand() == null) throw new com.dan.store.Ablage.Fehler("Keine gesicherte Sitzung in " + sp().wo());
            return s;
        }, s -> { Weiter.jetzt(r, s.zustand(), "Sitzung " + s.id(), chronik); view.setMode(r.camera.mode); syncFromScene(); });
    }

    /** Die Befunde und die Fachdaten zum Lesen (F46 A). */
    private void befundeZeigen() {
        hinten("Befunde lesen", () -> {
            var s = sp();
            StringBuilder b = new StringBuilder("<html><body style='width:640px'><b>Befunde</b><br>");
            for (var f : s.befunde())
                b.append(f.nr()).append(" · Phase ").append(f.phase() == null ? "–" : f.phase()).append(" · ").append(esc(f.thema())).append(" · ").append(f.art()).append(" · ")
                        .append(f.status()).append(f.behobenIn() == null ? "" : " in " + f.behobenIn()).append(": ").append(esc(f.text()))
                        .append(f.belege().isEmpty() ? "" : " [" + esc(String.join(", ", f.belege())) + "]").append("<br>");
            b.append("<br><b>Fachdaten</b><br>");
            for (var k : s.bootKlassen())
                b.append(esc(k.name())).append(String.format(Locale.GERMAN, ": %.1f m, %.0f kg, %.1f m², %d Punkte der Polare<br>", k.luea(), k.masse(), k.segel(), k.polare().size()));
            for (var g : s.regatten())
                b.append(esc(g.name())).append(String.format(Locale.GERMAN, ": %s, Bahn nach %.0f°, %.0f m, %d Runden, %d Tonnen<br>", g.klasse(), g.richtung(), g.laenge(), g.runden(), g.tonnen().size()));
            for (var z : s.zuendplaene())
                b.append(esc(z.name())).append(String.format(Locale.GERMAN, ": %s %s, %d Bomben, %.0f s<br>", z.datum(), z.beginn(), z.schuesse().size(), z.dauerS()));
            for (var f : s.sturmfluten())
                b.append(esc(f.name())).append(String.format(Locale.GERMAN, ": Scheitel %.2f m NHN (%s), %d Stunden im Fahrplan<br>", f.scheitelM(), f.klasse(), f.pegel().size()));
            for (var l : s.leuchtfeuer())
                b.append(esc(l.name())).append(": ").append(esc(l.kennung() == null ? "" : l.kennung())).append(String.format(Locale.GERMAN, ", %.1f m, %d Sektoren<br>", l.feuerhoehe(), l.sektoren().size()));
            for (var l : s.linien()) b.append(esc(l.name())).append(String.format(Locale.GERMAN, ": alle %d min, %d Abfahrten [G]<br>", l.takt(), l.abfahrten().size()));
            var er = s.ergebnisse(10);
            if (!er.isEmpty()) {
                b.append("<br><b>Ergebnisse (zuletzt)</b><br>");
                for (var e : er) b.append(esc(e.text())).append("<br>");
            }
            var ab = s.abbrueche(10);
            if (!ab.isEmpty()) {
                b.append("<br><b>Abbrüche am Brodtener Ufer (zuletzt)</b><br>");
                for (var a : ab) b.append(esc(a.text())).append("<br>");
            }
            return b.append("</body></html>").toString();
        }, html -> {
            JLabel t = small(html);
            javax.swing.JScrollPane spn = new javax.swing.JScrollPane(t);
            spn.setPreferredSize(new Dimension(720, 520));
            com.dan.foptionpane.FOptionPane.showMessageDialog(this, spn, "Timmendorf · Befunde und Fachdaten · " + sp().wo(), com.dan.foptionpane.FOptionPane.INFORMATION_MESSAGE);
        });
    }

    /** Jede Sekunde: die Zeilen von DATEN; alle 10 s Stand, Sitzungen und Protokoll aus dem Speicher. */
    private void datenZeilen() {
        if (sitzungBox == null) return;
        var c = chronik;
        long gs = com.dan.store.Dienst.gesichert();
        txt(datenLbl, "<html><body style='width:215px'>" + esc(sp().wo()) + (datenNote.isEmpty() ? "" : " · " + esc(datenNote))
                + "<br>Sitzung " + com.dan.store.Dienst.sitzung() + (gs > 0 ? ", gesichert " + String.format("%tR", gs) + " Uhr (Rechner)" : ", noch nicht gesichert")
                + "<br>" + esc(c == null ? "Chronik: startet mit der Szene" : c.describe())
                + (com.dan.timmendorf.data.Datenseite.einrichterStand.isEmpty() ? "" : "<br>Einrichter: " + esc(com.dan.timmendorf.data.Datenseite.einrichterStand))
                + (com.dan.timmendorf.data.Datenseite.uebernahmeStand.isEmpty() ? "" : "<br>Übernahme: " + esc(com.dan.timmendorf.data.Datenseite.uebernahmeStand))
                + (com.dan.store.Dienst.letzterFehler().isEmpty() ? "" : "<br>Zuletzt: " + esc(com.dan.store.Dienst.letzterFehler())) + "</body></html>");
        long now = System.currentTimeMillis();
        if (now < naechsteListe) return;
        naechsteListe = now + 10_000;
        com.dan.store.Dienst.spaeter(() -> {
            try {
                var sp = sp();
                var stand = sp.stand();
                var sl = sp.sitzungen(20);
                var prot = sp.letzteZeilen(6);
                String st = "<html><body style='width:215px'>" + esc(stand.kurz()) + "</body></html>";
                StringBuilder pb = new StringBuilder("<html><body style='width:215px'>");
                for (String z : prot) pb.append(esc(z.length() > 140 ? z.substring(0, 140) + " …" : z)).append("<br>");
                String ps = pb.append("</body></html>").toString();
                java.util.List<String> namen = new java.util.ArrayList<>();
                for (var x : sl)
                    namen.add("Sitzung " + x.id() + (x.id() == com.dan.store.Dienst.sitzung() ? " (diese)" : "") + " · " + String.format("%td.%<tm. %<tR", x.beginnMs())
                            + (x.szeneMs() != null ? " · Szene " + x.szeneText() : "") + (x.sauber() ? "" : " · nicht sauber beendet"));
                javax.swing.SwingUtilities.invokeLater(() -> {
                    if (popupOpen(this) || menuOpen()) return;
                    sitzungListe = sl;
                    fuellen(sitzungBox, namen, gewaehlt(sitzungBox));
                    txt(standLbl, st);
                    txt(protLbl, ps);
                    txt(sitzungLbl, "<html><body style='width:215px'>" + sl.size() + " Sitzungen · „Weiter wie zuletzt“ stellt die letzte gesicherte her</body></html>");
                });
            } catch (Exception ex) {
                javax.swing.SwingUtilities.invokeLater(() -> txt(standLbl, "Stand nicht lesbar: " + esc(String.valueOf(ex.getMessage()))));
            }
        });
    }

    private void wind(double speed, double fromDeg) {
        r.wiring.windSpeed = speed;
        r.wiring.windFromDeg = fromDeg;
        syncFromScene();
        view.requestFocusInWindow();
    }

    private void feuerwerk() {
        r.wiring.feuerwerk();
        r.hudMeldung("Feuerwerk: " + r.wiring.plan.label + " gezündet");
        labels();
    }

    private void reihenfolge() {
        java.util.Map<String, FCollapsiblePanel> m = new java.util.LinkedHashMap<>();
        for (Object[] b : bloecke) m.putIfAbsent(((FCollapsiblePanel) b[0]).getTitle(), (FCollapsiblePanel) b[0]);
        removeAll();
        boolean erst = true;
        for (String t : REIHENFOLGE) {
            FCollapsiblePanel p = m.remove(t);
            if (p == null) continue;
            if (!erst) add(Box.createVerticalStrut(6));
            add(p);
            erst = false;
        }
        for (FCollapsiblePanel p : m.values()) { add(Box.createVerticalStrut(6)); add(p); }
    }

    /** Für Prüfungen: die Titel der Blöcke, wie sie im Bedienfeld stehen. */
    public java.util.List<String> bloecke() {
        java.util.List<String> l = new java.util.ArrayList<>();
        for (Component c : getComponents()) if (c instanceof FCollapsiblePanel p) l.add(p.getTitle());
        return l;
    }

    private final java.util.Map<FCollapsiblePanel, Integer> hoehe = new java.util.HashMap<>();

    /**
     * Wie in Geiranger: FCollapsiblePanel merkt sich die Höhe des Inhalts beim Aufklappen. Wachsen danach Zeilen,
     * schneidet der Block unten ab. Darum alle halbe Sekunde nachmessen und offene Blöcke neu setzen.
     */
    private void nachmessen() {
        boolean changed = false;
        for (Object[] b : bloecke) {
            FCollapsiblePanel p = (FCollapsiblePanel) b[0];
            if (!p.isExpanded()) { hoehe.remove(p); continue; }
            JComponent body = (JComponent) b[1];
            body.invalidate();
            int h = body.getPreferredSize().height;
            Integer old = hoehe.put(p, h);
            if (old == null || old != h) { p.setExpanded(true, false); changed = true; }
        }
        if (changed) { revalidate(); repaint(); }
    }

    /** Ist irgendwo im Bedienfeld eine Auswahlliste aufgeklappt? */
    private static boolean popupOpen(java.awt.Container c) {
        for (Component k : c.getComponents()) {
            if (k instanceof javax.swing.JComboBox<?> b && b.isPopupVisible()) return true;
            if (k instanceof java.awt.Container cc && popupOpen(cc)) return true;
        }
        return false;
    }

    /** Ist irgendein Aufklappmenü offen (auch die Liste einer Auswahl)? Dann darf sich nichts neu auslegen. */
    public static boolean menuOpen() { return javax.swing.MenuSelectionManager.defaultManager().getSelectedPath().length > 0; }

    /** Text nur setzen, wenn er sich ändert (sonst legt Swing den Block bei jedem Takt neu aus). */
    private static void txt(JLabel l, String t) { if (!t.equals(l.getText())) l.setText(t); }

    private void ansicht(Ansichten.Ansicht a) {
        if (r.placeView(a)) {
            free.setSelected(r.camera.mode == Camera.Mode.FREI);
            orbit.setSelected(false);
        }
        view.requestFocusInWindow();
    }

    private void tasten() {
        StringBuilder an = new StringBuilder();
        for (Ansichten.Ansicht a : Ansichten.ALLE) an.append(an.length() > 0 ? ", " : "").append(a.key()).append(' ').append(a.name());
        com.dan.foptionpane.FOptionPane.showMessageDialog(this, "<html><body style='width:420px'>"
                + "<b>Szene</b><br>Links ziehen: drehen · rechts ziehen: verschieben · Rad, Bild↑/↓, +/−: zoomen<br>"
                + "Pfeile: drehen · W A S D: gleiten · Q/E: ab und auf (frei) · Umschalt: schneller<br>"
                + "F: frei fliegen · R: von selbst kreisen · Pos1: Grundansicht · H: HUD kurz, voll, aus<br>"
                + "Z: Feuerwerk zünden (Hülle) · O: mit dem Optimisten · B: Begleitboot · G: Spitze der Regatta<br>"
                + "Leertaste: Uhr anhalten und weiter · F12: Foto nach docs/status · F5: Shader neu laden<br>"
                + "M: Ansicht merken · P: Polare des Boots · Strg+S: Sitzung jetzt sichern<br>"
                + "Ansichten: " + an + "<br><br>"
                + "<b>Fenster</b><br>F1: diese Übersicht · F11: nur die Szene (Bedienfeld und Statuszeile weg und wieder da)</body></html>",
                "Timmendorf · Tasten", com.dan.foptionpane.FOptionPane.INFORMATION_MESSAGE);
    }

    /** Regler nach der Szene stellen (alle 0,5 s). */
    private void syncFromScene() {
        fromScene = true;
        int m = (int) Math.round(clock.hour() * 60) % (24 * 60);
        if (!time.getValueIsAdjusting()) time.setValue(m);
        if (!scaleSl.getValueIsAdjusting()) scaleSl.setValue((int) Math.round(r.renderScale * 100));
        if (!expSl.getValueIsAdjusting()) expSl.setValue((int) Math.round(r.exposureBias * 10));
        if (!pegelSl.getValueIsAdjusting()) pegelSl.setValue((int) Math.round(r.wiring.pegel * 100));
        if (!windSl.getValueIsAdjusting()) windSl.setValue((int) Math.round(r.wiring.windSpeed * 2));
        if (!windDirSl.getValueIsAdjusting()) windDirSl.setValue((int) Math.round(r.wiring.windFromDeg));
        fromScene = false;
        labels();
    }

    private void labels() {
        txt(dateLbl, clock.local().format(java.time.format.DateTimeFormatter.ofPattern("EEEE, d. MMMM u", Locale.GERMANY)));
        txt(timeLbl, "Uhrzeit " + clock.timeLabel());
        double[] s = clock.sun();
        double day = clock.local().toLocalDate().atStartOfDay(SceneClock.ZONE).toInstant().toEpochMilli();
        double up = Sun.crossing(day, clock.lat, clock.lon, true), down = Sun.crossing(day, clock.lat, clock.lon, false);
        txt(sunLbl, String.format(Locale.GERMAN, "<html><body style='width:215px'>Sonne %.1f° hoch, aus %03.0f° (%s) · Aufgang %s, Untergang %s</body></html>",
                s[0], s[1], compass(s[1]), hm(up), hm(down)));
        txt(camLbl, "<html><body style='width:215px'>" + esc(r.cameraLine()) + "</body></html>");
        txt(scaleLbl, String.format(Locale.GERMAN, "Auflösung %.0f %% (%s)", r.renderScale * 100, r.autoScale ? "geregelt, " + r.scaleSteps() + "× geändert" : "fest"));
        txt(qualLbl, "<html><body style='width:215px'>" + r.quality().summary() + "</body></html>");
        txt(pegelLbl, String.format(Locale.GERMAN, "Pegel %+.2f m NHN (von Hand)", r.wiring.pegel));
        txt(windLbl, String.format(Locale.GERMAN, "Wind in 10 m: %.1f m/s (Bft %d)", r.wiring.windSpeed, beaufort(r.wiring.windSpeed)));
        txt(windDirLbl, String.format(Locale.GERMAN, "aus %03.0f° (%s)", r.wiring.windFromDeg, compass(r.wiring.windFromDeg)));
        txt(segelLbl, "<html><body style='width:215px'>" + esc(r.wiring.segelZeile()) + "</body></html>");
        txt(feuerLbl, "<html><body style='width:215px'>" + esc(r.wiring.hullenZeile(r.camera.eye[0], r.camera.eye[2])) + "</body></html>");
        txt(flutLbl, "<html><body style='width:215px'>" + esc(r.sturmflutZeile()) + "</body></html>");
        txt(wetterLbl, "<html><body style='width:215px'>" + esc(r.wiring.wetterZeile() + " · " + r.wiring.jahr.describe()) + "</body></html>");
        txt(booteLbl, "<html><body style='width:215px'>" + esc(r.wiring.flotte.zeile()) + "</body></html>");
        txt(regattaLbl, "<html><body style='width:215px'>" + esc(r.wiring.flotte.regatta.describe()) + "</body></html>");
        txt(klangLbl, "<html><body style='width:215px'>" + esc(r.klang.describe()) + "</body></html>");
        txt(regieLbl, "<html><body style='width:215px'>" + esc(r.regisseur.zeile()) + "</body></html>");
    }

    private void technik() {
        txt(perfLbl, String.format(Locale.GERMAN, "<html><body style='width:215px'>%.0f Bilder/s · Grafikkarte %.2f ms · Prozessor %.2f ms<br>%s</body></html>",
                r.fps(), r.gpuMs(), r.cpuMs(), r.passTimes()));
        ComputeProbe.Result c = r.compute();
        txt(computeLbl, "<html><body style='width:215px'>" + (c == null ? "Compute-Test läuft …" : c.line()) + "</body></html>");
        txt(glLbl, "<html><body style='width:215px'>" + r.glInfo() + (r.reversedDepth() ? " · umgekehrte Tiefe" : "") + "</body></html>");
        txt(physLbl, "<html><body style='width:215px'>" + r.physik.describe() + "</body></html>");
        txt(gelLbl, "<html><body style='width:215px'>" + esc(r.terrain.describe()) + (r.terrain.measured() ? ""
                : "<br>Bühne von Phase 1: das Gelände kommt mit Phase 2 aus daten/gelaende.") + "</body></html>");
        txt(pkgLbl, "<html><body style='width:215px'>" + esc(Bibliothek.kurz().replace("!", "")) + ": " + String.join(", ", Bibliothek.pakete())
                + ".<br>Neu für Timmendorf: " + esc(Bibliothek.neuStand()) + " (Hüllen seit Phase 1). Die Prüfung <i>check-packages</i> läuft im Projekt Renderer nach jedem Übersetzen.</body></html>");
        txt(dbLbl, "<html><body style='width:215px'>" + esc(Verbindung.stand()) + "</body></html>");
        StringBuilder h = new StringBuilder("<html><body style='width:215px'>");
        for (String l : r.hudLines()) h.append(esc(l.startsWith("!") ? l.substring(1) : l)).append("<br>");
        txt(hudLbl, h.append("</body></html>").toString());
    }

    private static String esc(String t) { return t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

    static String hm(double epochMs) {
        if (Double.isNaN(epochMs)) return "—";
        return String.format("%tR", Instant.ofEpochMilli((long) epochMs).atZone(SceneClock.ZONE));
    }

    /** Beaufort-Stufe zur Windgeschwindigkeit in m/s (WMO-Grenzen). */
    static int beaufort(double v) {
        double[] g = {0.3, 1.6, 3.4, 5.5, 8.0, 10.8, 13.9, 17.2, 20.8, 24.5, 28.5, 32.7};
        int b = 0;
        while (b < g.length && v >= g[b]) b++;
        return b;
    }

    static String compass(double deg) {
        String[] n = {"Nord", "Nordnordost", "Nordost", "Ostnordost", "Ost", "Ostsüdost", "Südost", "Südsüdost",
                "Süd", "Südsüdwest", "Südwest", "Westsüdwest", "West", "Westnordwest", "Nordwest", "Nordnordwest"};
        return n[(int) Math.round(((deg % 360) + 360) % 360 / 22.5) % 16];
    }

    // ------------------------------------------------------------ Aufbau

    /** Ein Klappblock (FCollapsiblePanel); offen bleibt, was zuletzt offen war. */
    private void block(String title, boolean offen) {
        FCollapsiblePanel p = new FCollapsiblePanel() {
            @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
        };
        p.setTitle(title);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        JPanel inhalt = new JPanel();
        inhalt.setOpaque(false);
        inhalt.setLayout(new BoxLayout(inhalt, BoxLayout.Y_AXIS));
        inhalt.setBorder(BorderFactory.createEmptyBorder(6, 10, 10, 8));
        bloecke.add(new Object[]{p, inhalt});
        String key = "offen." + title;
        boolean auf = PREFS == null ? offen : PREFS.getBoolean(key, offen);
        if (!auf) zu.add(p);
        p.addFCollapsiblePanelListener((panel, expanded) -> {
            if (PREFS != null) try { PREFS.putBoolean(key, expanded); } catch (RuntimeException ex) { /* nicht gemerkt */ }
        });
        if (getComponentCount() > 0) add(Box.createVerticalStrut(6));
        add(p);
        target = inhalt;
    }

    /** Eine Zwischenüberschrift im Block. */
    private void sub(String text) {
        JLabel h = new JLabel(text);
        h.setForeground(Theme.accent());
        h.setFont(new Font("SansSerif", Font.BOLD, 11));
        h.setBorder(BorderFactory.createEmptyBorder(10, 0, 3, 0));
        put(h);
    }

    private void put(Component c) { target.add(row(c)); }

    /** Knöpfe in höchstens n Spalten, umbrechend (seit Phase 19 {@link Raster}; vorher eine einzige Zeile). */
    private static JPanel grid(int n) {
        JPanel p = new JPanel(new Raster(n));
        p.setOpaque(false);
        return p;
    }

    /** Eine Zeile, die mit ihrem Inhalt wächst. */
    private static JComponent row(Component c) {
        JPanel p = new JPanel(new BorderLayout()) {
            @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
        };
        p.setOpaque(false);
        p.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        p.add(c, BorderLayout.CENTER);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    static JLabel label(String t) {
        JLabel l = new JLabel(t);
        l.setForeground(Theme.ink());
        l.setFont(new Font("SansSerif", Font.PLAIN, 13));
        return l;
    }

    static JLabel small(String t) {
        JLabel l = new JLabel(t);
        l.setForeground(Theme.muted());
        l.setFont(new Font("SansSerif", Font.PLAIN, 11));
        return l;
    }

    // ------------------------------------------------------------ Scrollable: nur senkrecht
    @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
    @Override public int getScrollableUnitIncrement(java.awt.Rectangle rr, int o, int d) { return 16; }
    @Override public int getScrollableBlockIncrement(java.awt.Rectangle rr, int o, int d) { return 120; }
    @Override public boolean getScrollableTracksViewportWidth() { return true; }
    @Override public boolean getScrollableTracksViewportHeight() { return false; }
}
