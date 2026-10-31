package com.dan.timmendorf.gl;

import com.dan.cloud.Clouds;
import com.dan.cloud.gl.CloudGl;
import com.dan.sky.Sky;
import com.dan.sky.gl.SkyGl;
import com.dan.timmendorf.app.Bibliothek;
import com.dan.timmendorf.camera.Ansichten;
import com.dan.timmendorf.camera.Camera;
import com.dan.timmendorf.scene.Bauten;
import com.dan.timmendorf.scene.Bewuchs;
import com.dan.timmendorf.scene.Buehne;
import com.dan.timmendorf.scene.Daten;
import com.dan.timmendorf.scene.Physik;
import com.dan.timmendorf.scene.SceneClock;
import com.dan.timmendorf.scene.Site;
import com.dan.timmendorf.scene.Sun;
import com.dan.timmendorf.scene.Terrain;
import com.dan.timmendorf.wiring.Wiring;
import com.jogamp.common.nio.Buffers;
import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL4;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLEventListener;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Die Engine auf der Grafikkarte, übernommen aus Sylt (F7 A) im Stand von dessen Phase 1 und entschlackt; seit Phase 4 das
 * Grundbild mit den Packages aus {@code Renderer.jar} (aus Sylt Phase 3 und 10). Je Bild:
 * <ol>
 * <li><b>Himmel</b>: {@code com.dan.sky} (Streuung, Sonne, Mond, Sterne) in einen HDR-Puffer (RGBA16F, umgekehrte
 * 32F-Tiefe), dazu das Rundumbild mit Wolken (Spiegelung, Dunst, Himmelslicht) und die Schattenkarte der Wolken
 * ({@link Wetter}); ohne {@code com.dan.sky} der Verlauf aus Phase 1 ({@code sky.frag}).</li>
 * <li><b>Gelände</b>: Kern, Hauptraster und Fernring aus {@link Terrain} als CDLOD ({@link TerrainLod}), im Kern bis 1 m
 * Punktabstand, mit Erdkrümmung; Farben aus dem Luftbild DOP20 2025 (im Kern zu 1 m, im Hauptraster zu 10 m), die Klassen
 * je Meter für Strand, nassen Sand und Steilufer; Wolkenschatten; zuschaltbar die Marken. Ohne {@code daten/gelaende} die
 * Bühne ({@link Buehne}).</li>
 * <li><b>Bauten</b>: die 34 000 Häuser aus LOD2 mit Baustoffen ({@link Bauten}, {@code bauten.vert/frag}).</li>
 * <li><b>Meer</b>: die Ostsee aus {@code com.dan.ocean} mit dem Gelände als Grund ({@link Meer}); ohne Compute der
 * Platzhalter aus Phase 1 ({@code meer.frag}).</li>
 * <li><b>Wolken</b>: {@code com.dan.cloud} nach dem Gelände mit seiner Tiefe.</li>
 * <li><b>Nachbearbeitung</b>: Belichtung aus der Augenanpassung an Himmel und Bild ({@link Belichtung}), Filmkurve.</li>
 * <li><b>HUD</b>: Tempo (Bilder/s, Zeit der Grafikkarte je Durchgang, Prozessor), Uhr, Sonne, Kamera, Ort in UTM.</li>
 * </ol>
 * Geblieben aus Sylt und Brocken: Qualitätsstufen mit Auflösungsregelung, Zeitmessung jedes Durchgangs auf der Grafikkarte
 * (Zeitstempel, ein Bild versetzt gelesen), Compute-Test beim Start, HUD kurz/voll/aus, Foto (F12), Shader neu laden
 * (F5), Standbilder ohne Fenster ({@code tools.Tempo}), der Faden der Physik ({@link Physik}) mit festem Schritt und der
 * Kinomodus ({@code com.dan.kino}) als Anschluss für die Regiebücher in Phase 16. Neu in Timmendorf: die Hüllen der fünf
 * neuen Packages laufen im Faden der Physik ({@link Wiring#step}), das Feuerwerk zündet auf Taste Z. Im Bildlauf werden keine Dateien gelesen.
 * <p>
 * Seit Phase 12 der Durchgang <b>Lichter</b>: die Spiegelung der Leuchten und Bomben im Wasser ({@code spiegel.frag},
 * {@code lamp.glsl}), das Feuerwerk ({@code FireworkGl}: Spiegelung nur auf dem Wasser, dann Rauch, Schweife, Sterne)
 * und die Köpfe aller Leuchten als Lichtpunkte ({@code LampGl.drawHeads}); das Licht der Leuchten und Bomben auf Gelände
 * und Häuser rechnen deren Shader selbst ({@code lampIrradiance}).
 */
public final class Renderer implements GLEventListener {
    public static final int MIN_MAJOR = 4, MIN_MINOR = 3;
    public static final String VERSION = "Phase 21";
    /** Phase des Stands (für Ansichten und Bedienfeld). */
    public static final int PHASE = 17;
    public static final String[] PASSES = {"Himmel", "Gelände", "Bauten", "Bewuchs", "Boote", "Menschen", "Meer", "Wolken", "Lichter", "Nachbearbeitung", "HUD"};
    private static final int MARKS = PASSES.length + 1;
    /** Nah- und Fernebene (m): bis über den Fernring. */
    static final double NEAR = 0.3, FAR = 120_000;

    public final SceneClock clock;
    public final Camera camera = new Camera();
    /** Der Faden der Physik; Phase 1 mit den Hüllen der neuen Packages. */
    public final Physik physik = new Physik();
    /** Das Gelände (Kern, Hauptraster, Fernring; Phase 1 aus der Bühne) und seine Detailstufen. */
    public final Terrain terrain;
    public final TerrainLod lod = new TerrainLod();
    /** Die Verdrahtung der Packages (Phase 1: Hüllen von cloth, sail, wake, firework, route). */
    public final Wiring wiring;
    /** Seit Phase 16: der Kinomodus (com.dan.kino) mit den Regiebüchern ({@link Regisseur}). */
    public final com.dan.kino.Kino kino = new com.dan.kino.Kino();
    /** Seit Phase 16: Regiebücher, Fahrten, Fotos und Blatt, das Kino über dem Bild. */
    public final Regisseur regisseur = new Regisseur(this);
    /** Seit Phase 4: Himmel und Wolken, die See, die Häuser aus LOD2. */
    public final Wetter wetter = new Wetter();
    public final Meer meer = new Meer();
    public final Bauten bauten;
    final BautenGl bautenGl = new BautenGl();
    /** Seit Phase 5a: Bäume, Sträucher, Strandhafer, Schilf und Gras. */
    public final Bewuchs bewuchs;
    final BewuchsGl bewuchsGl = new BewuchsGl();
    /** Seit Phase 8a: Segelboote, Passat und Fähren ({@code com.dan.sail}, {@link com.dan.timmendorf.scene.Flotte}). */
    final BooteGl booteGl = new BooteGl();
    /** Seit Phase 10a: die Menschen ({@code com.dan.figure}). */
    final MenschenGl menschenGl = new MenschenGl();
    private final Belichtung belichtung = new Belichtung();
    /** Seit Phase 9: Lichtpunkte der Leuchtfeuer ({@code com.dan.beacon}, BeaconGl.drawGlare). */
    final com.dan.beacon.gl.BeaconGl beaconGl = new com.dan.beacon.gl.BeaconGl();
    /** Seit Phase 9: das Brodtener Ufer aus {@code com.dan.cliff} im Geländeshader (Wandkarte und Schichtkarte). */
    final com.dan.cliff.gl.CliffGl cliffGl = new com.dan.cliff.gl.CliffGl();
    public final com.dan.cliff.Cliff brodten = com.dan.cliff.Cliff.brodtenerUfer();
    private com.dan.cliff.CliffMap brodtenKarte;
    /** Brodtener Ufer: Kante der Wand- und Schichtkarte (m), Zellen. */
    public static final double KLIFF_X0 = 4200, KLIFF_Z0 = 300, KLIFF_ZELLE = 5;
    public static final int KLIFF_N = 520;
    /** Seit Phase 9: die Plätze der Strandkörbe; seit Phase 20 zeichnet sie {@code com.dan.beachchair} (KoerbeGl), ob sie stehen. */
    private java.util.List<com.dan.timmendorf.scene.Bauwerke.Korb> koerbe = java.util.List.of();
    private boolean koerbeDa;
    final KoerbeGl koerbeGl = new KoerbeGl();
    /** Seit Phase 20: der Flugsand am Strand wie in Sylt ({@code dune} mit dem Wind aus {@code wind}) und ob die Körner gezeigt werden. */
    public final com.dan.timmendorf.scene.Sand sand;
    final SandGl sandGl = new SandGl();
    public volatile boolean showKoerner = true;
    /** Seit Phase 21: Relief und Korn des Strands am Auge ({@code com.dan.beach}, gebacken aus sand_dunes_gltf.zip), ob gezeigt. */
    public final com.dan.beach.Beach strand = com.dan.beach.Beach.ostsee();
    final com.dan.beach.gl.BeachGl strandGl = new com.dan.beach.gl.BeachGl();
    public volatile boolean showStrand = true;
    /** Textureinheit des Strands im Geländeshader (frei: 0 bis 15 sind sonst belegt bis auf 1). */
    static final int STRAND_UNIT = 1;
    /** Seit Phase 20: Nässe des Sands nach Regen 0..1, in Szenenzeit (nass in rund 5 min, trocken in rund 3 h) [G, aus Sylt]. */
    private double sandNass;
    private String bauwerkeInfo = "";
    /** Luftbild im Kern (1 m, sRGB) und im Hauptraster (10 m), Klassen im Kern (R8UI); 0, wenn die Daten fehlen. */
    private int luftKernTex, luftHauptTex, klasseTex;
    /** Ebenen zum Abschalten (Bedienfeld). */
    public volatile boolean showClouds = true, showOcean = true, showBauten = true, showLuftbild = true, showBewuchs = true, showBoote = true,
            /** Seit Phase 9: Bauwerke am Wasser, Strandkörbe (in der Saison), Lichtpunkte der Feuer, Brodtener Ufer aus cliff. */
            showBauwerke = true, showKoerbe = true, showFeuer = true, showKliff = true;
    /** Belichtung im Bild messen (sonst nur aus dem Himmel geschätzt). */
    public volatile boolean bildMessung = !Boolean.getBoolean("timmendorf.belichtung.schaetzen");
    /** Anpassung des Auges: 0 = feste Belichtung des Tags, 1 = volle Anpassung an das Bild (wie com.dan.sky). */
    public volatile double adaptation = 0.85;
    /** Gewicht der Bildmessung gegen den Himmel (Rest) und ihr Faktor (mittleres Grau statt Mittel), aus Brocken. */
    public static final double BILD_FOLGT = 0.75, BILD_GAIN = 2.0;
    /** Seit Phase 15: Anteil des linearen Mittels, unter den die gemessene Leuchtdichte nicht fällt. */
    public static final double HELL_ANTEIL = 0.25;
    /** Wie stark eine dunkle Decke die Anpassung mitnimmt (Exponent auf com.dan.cloud.brightnessFactor). */
    public static final double STORM_ADAPT = 0.6;
    /** Dunst ohne com.dan.sky (wie common.glsl: 60 km), m. */
    static final double FOG_DISTANCE = 60_000;
    private double adaptLum = -1, viewLum = -1, viewTimer, cloudShade = 1;
    private boolean frameSky;

    private volatile Quality quality = Quality.MITTEL;
    public volatile double renderScale = quality.renderScale;
    /** Auflösung nach der Zeit der Grafikkarte regeln (nie unter {@link #MIN_SCALE}, nie über der Stufe). */
    public volatile boolean autoScale = true;
    public volatile double targetFps = 60;
    public static final double MIN_SCALE = 0.5;
    private long scaleNanos;
    private int scaleSteps;
    /** HUD an, kurz oder voll (H). */
    /** Seit Phase 19 beim Start aus (Daniel, 31.10.); H schaltet kurz → voll → aus. */
    public volatile boolean showHud = false, hudFull;
    /** Die Marken zur Ausrichtung (UTM-Raster, Höhenlinien, Wasserlinie, Fernfeld, Kern, Orte); seit Phase 4 aus (M). */
    public volatile boolean showMarks = Boolean.getBoolean("timmendorf.marken");
    /** Die See zeigen (seit Phase 4 com.dan.ocean, sonst der Platzhalter). */
    public volatile boolean showMeer = true;
    /** Szene steht (für Standbilder und die Leertaste im Kino später). */
    public volatile boolean frozen;
    /**
     * Seit Phase 13: Wetter, Jahr, Leben – Regen, Seenebel und Gewitter ({@link Unwetter}), Eis auf der Bucht und Schneefall
     * ({@code com.dan.season}), Möwen, Schwäne, Kormorane und der seltene Seehund ({@link com.dan.timmendorf.scene.Leben}).
     */
    public final Unwetter unwetter = new Unwetter();
    public final com.dan.timmendorf.scene.Leben leben;
    final LebenGl lebenGl = new LebenGl();
    final com.dan.season.gl.IceGl eisGl = new com.dan.season.gl.IceGl();
    final com.dan.season.gl.SnowfallGl schneeGl = new com.dan.season.gl.SnowfallGl();
    private boolean eisOk, schneeOk;
    /** Seit Phase 17: Ohrenquallen (com.dan.jelly), im Sommer unter der Seebrücke und in der ganzen Bucht. */
    public final com.dan.jelly.Bloom quallen = com.dan.jelly.Bloom.summer();
    final com.dan.jelly.gl.JellyGl quallenGl = new com.dan.jelly.gl.JellyGl();
    private boolean quallenOk;
    public volatile boolean showLeben = true, showJahr = true;
    /**
     * Seit Phase 14: die Sturmflut 2023 – Abbrüche am Brodtener Ufer ({@link com.dan.timmendorf.scene.Abbruch}, als
     * Höhenänderung im Geländeshader) und Brecher an den Pfählen der Seebrücken ({@code com.dan.spray}).
     */
    public final com.dan.timmendorf.scene.Abbruch abbruch;
    public final com.dan.spray.Spray brecher = com.dan.spray.Spray.gale();
    /** Seit Phase 15: der Klang der Szene ({@code com.dan.soundscape}); Ton an im Bedienfeld, KLANG. */
    public final Klang klang;
    final com.dan.spray.gl.SprayGl brecherGl = new com.dan.spray.gl.SprayGl();
    private boolean brecherOk;
    private int korrTex, korrVersion = -1, brecherZahl;
    private java.util.List<double[]> pfaehle = java.util.List.of();
    private double brecherTakt, pfahlTakt;
    public volatile boolean showSturmflut = true;
    /** Seit Phase 12: Licht der Leuchten und Bomben (Puffer für die Shader), Lichtpunkte aller Leuchten, das Feuerwerk. */
    final com.dan.lamp.gl.LampGl lampGl = new com.dan.lamp.gl.LampGl(), kopfGl = new com.dan.lamp.gl.LampGl();
    final com.dan.firework.gl.FireworkGl fwGl = new com.dan.firework.gl.FireworkGl();
    /** Seit Phase 12: Abend an (Leuchten, Spiegelung, Feuerwerk). */
    public volatile boolean showLichter = true, showFeuerwerk = true;
    private java.util.List<com.dan.lamp.Lamp> festAuswahl = java.util.List.of();
    private double lampK;
    private int lichtZahl, kopfZahl;
    /** Belichtung von Hand in Blenden. */
    public volatile double exposureBias;
    public volatile boolean photoRequest;
    /** Ein Bild abholen (einmal, im GL-Faden), etwa für Werkzeuge. */
    public volatile Consumer<BufferedImage> grabRequest;
    /** Ordner und Name des nächsten Fotos (ohne Angabe docs/status und {@code Timmendorf_Datum_Zeit}). */
    public volatile File photoDir;
    public volatile String photoName;

    private final Shader skyShader = new Shader("Himmel", null, "fullscreen.vert", "sky.frag"),
            terrainShader = new Shader("Gelände", null, "terrain.vert", "terrain.frag"),
            meerShader = new Shader("Meer", null, "fullscreen.vert", "meer.frag"),
            post = new Shader("Nachbearbeitung", null, "fullscreen.vert", "post.frag"),
            overlay = new Shader("Überlagerung", null, "fullscreen.vert", "overlay.frag"),
            lumShader = new Shader("Belichtung", null, "fullscreen.vert", "lum.frag"),
            bautenShader = new Shader("Bauten", null, "bauten.vert", "bauten.frag"),
            spiegelShader = new Shader("Spiegelung", null, "fullscreen.vert", "spiegel.frag");
    private final Shader[] all = {skyShader, terrainShader, meerShader, post, overlay, lumShader, bautenShader, spiegelShader};
    private final HudOverlay hud = new HudOverlay();
    private int emptyVao, hdrTex, depthTex, hdrFbo, hdrColorFbo, hdrW, hdrH, surfW = 1, surfH = 1, defaultFbo;
    private boolean reversedZ, ready;
    private final int[][] stamp = new int[2][MARKS];
    private final double[] passMs = new double[MARKS - 1];
    private int frame;
    private long lastNanos, statsNanos;
    private int statsFrames;
    private double fps, gpuMs, cpuMs, worstMs;
    private volatile String glInfo = "", error;
    private volatile ComputeProbe.Result compute;
    private volatile boolean reloadShaders;
    private Consumer<String> statusListener = s -> { };
    private Consumer<String> errorListener = s -> { };
    private volatile String photoMsg = "", meldung = "";
    private volatile long photoMsgUntil, meldungUntil;
    /** Arbeiten aus anderen Fäden, im Bildlauf vor dem Schritt ausgeführt. */
    private final java.util.concurrent.ConcurrentLinkedQueue<Runnable> imBild = new java.util.concurrent.ConcurrentLinkedQueue<>();

    public Renderer(SceneClock clock) { this(clock, Terrain.load(Terrain.defaultDir())); }

    public Renderer(SceneClock clock, Terrain terrain) {
        this.clock = clock;
        this.terrain = terrain;
        this.wiring = new Wiring(clock);
        wiring.setTerrain(terrain);
        leben = new com.dan.timmendorf.scene.Leben(terrain::height);   // seit Phase 13
        quallen.bed = terrain::height;   // seit Phase 17
        quallen.surface = this::wasser;
        quallen.seed = 2026;
        // seit Phase 14: die Abbrüche der Sturmflut am Brodtener Ufer (2,5-m-Zellen über dem Ausschnitt des Kliffs)
        abbruch = new com.dan.timmendorf.scene.Abbruch(terrain::height, KLIFF_X0, KLIFF_Z0, 2.5, (int) Math.round(KLIFF_ZELLE * KLIFF_N / 2.5), 2023);
        brecher.seed = 2023;
        physik.add(new Physik.Aufgabe() {
            public void step(double t, double dt) { wiring.step(dt); }
            public String name() { return "Hüllen"; }
        });
        lod.use(terrain);
        bauten = Bauten.laden(Daten.ordner());
        bewuchs = Bewuchs.laden(Daten.ordner(), terrain);
        bewuchsGl.use(bewuchs, terrain);
        // seit Phase 9: Wand- und Schichtkarte des Brodtener Ufers (einmal, auf dem Prozessor)
        brodtenKarte = com.dan.cliff.CliffMap.build(brodten, terrain::height, KLIFF_X0, KLIFF_Z0, KLIFF_ZELLE, KLIFF_N, 6, 40);
        brodten.strata = com.dan.cliff.StrataMap.build(terrain::height, KLIFF_X0, KLIFF_Z0, KLIFF_ZELLE, KLIFF_N, KLIFF_N, 45, null, null, 0.2);
        cliffGl.strataUnit = 3;
        koerbe = wiring.koerbe;
        // seit Phase 20: Flugsand (Windfeld über dem Kern und Feld um das Auge rechnen im Hintergrund)
        sand = new com.dan.timmendorf.scene.Sand(terrain::height, terrain::artAt, () -> wiring.pegel);
        System.out.println(String.format(Locale.GERMAN, "Timmendorf: Brodtener Ufer · Wand %.1f %%, Geröll %.1f %% · %s · %,d Strandkörbe",
                brodtenKarte.wallShare * 100, brodtenKarte.talusShare * 100, brodten.strata.describe(), koerbe.size()));
        System.out.println("Timmendorf: Bewuchs " + bewuchs.describe());
        wetter.sky.latDeg = Site.LAT;
        wetter.sky.lonDeg = Site.LON;
        wetter.sky.epochMillis = clock.epochMillis();
        // das Rauschen der Wolken (8 MB, 0,5 bis 3 s) schon jetzt im Hintergrund rechnen (aus Rungholt)
        Thread nt = new Thread(() -> { com.dan.cloud.Noise.base(); com.dan.cloud.Noise.detail(); com.dan.cloud.Noise.weather(); }, "wolken-rauschen");
        nt.setDaemon(true);
        nt.start();
        setQuality(quality);
        camera.waterLevel = wiring.pegel;
        camera.groundLevel = groundHeight(camera.eye[0], camera.eye[2]);
        camera.targetGround = groundHeight(camera.tx, camera.tz);
        camera.snap();
        klang = new Klang(this);   // seit Phase 15, zuletzt: hängt sich an Flotte, Leben, Gewitter und Abbrüche
        klang.s.quality = stufe(com.dan.soundscape.Soundscape.Quality.MEDIUM, quality.ordinal());   // seit Phase 19
    }

    /**
     * Seit Phase 15: die Pfähle der Seebrücken {x, z, Fuß} für Klang und Brecher. Sie entstehen mit den Bauwerken beim Start
     * der Grafik; ohne Grafik (Hörproben, Prüfung) einmal hier gerechnet.
     */
    public synchronized java.util.List<double[]> pfaehle() {
        if (pfaehle.isEmpty() && !pfaehleGerechnet) {
            pfaehleGerechnet = true;
            int[] vorher = wiring.bauwerke.zahl.clone();
            pfaehle = new java.util.ArrayList<>(wiring.bauwerke.netz(terrain::height, wiring.fuss).pfaehle);
            System.arraycopy(vorher, 0, wiring.bauwerke.zahl, 0, vorher.length);   // die Zähler gehören dem Bau der Grafik
        }
        return pfaehle;
    }

    private boolean pfaehleGerechnet;

    /** Für Prüfungen und Werkzeuge: Uhr am Start, Gelände aus daten/gelaende (sonst die Bühne). */
    public Renderer() { this(new SceneClock()); }

    public void setStatusListener(Consumer<String> l) { statusListener = l; }
    public void setErrorListener(Consumer<String> l) { errorListener = l; }
    public String glInfo() { return glInfo; }
    public String error() { return error; }
    public double fps() { return fps; }
    public double gpuMs() { return gpuMs; }
    public double cpuMs() { return cpuMs; }
    public boolean reversedDepth() { return reversedZ; }
    public ComputeProbe.Result compute() { return compute; }
    public Quality quality() { return quality; }
    public void requestShaderReload() { reloadShaders = true; }
    public double[] lastPassTimes() { return passMs.clone(); }
    public long frames() { return frame; }
    public double takeWorstFrameMs() { double w = worstMs; worstMs = 0; return w; }
    public double fovy() { return Camera.FOVY; }

    /** Alle Shader-Programme mit ihren Dateien (für die Prüfung). */
    public String[][] shaderFiles() {
        String[][] f = new String[all.length][];
        for (int i = 0; i < f.length; i++) f[i] = all[i].files();
        return f;
    }

    public void setQuality(Quality q) {
        quality = q;
        renderScale = q.renderScale;
        lod.lodRange = q.terrainRange;
        lod.finest = q.terrainFine;
        lod.finestKern = q.terrainKern;
        wiring.setQuality(q.ordinal());
        meer.ocean.quality = q.ocean;
        wetter.clouds.quality = q.clouds;
        // seit Phase 13
        unwetter.rain.quality = com.dan.rain.Rain.Quality.values()[q.ordinal()];
        unwetter.fog.quality = com.dan.fog.Fog.Quality.values()[q.ordinal()];
        unwetter.blitz.quality = com.dan.lightning.Lightning.Quality.values()[q.ordinal()];
        if (leben != null) leben.stufe(q.ordinal());
        quallen.quality = com.dan.jelly.Bloom.Quality.values()[q.ordinal()];
        // seit Phase 19 durchgängig (Prüfteil Schliff): „Mittel“ bleibt, wie es bis Phase 18 auf allen Stufen war, Niedrig eine
        // Stufe darunter, Hoch und Ultra darüber. Teile, die ihr Netz nur einmal bauen (Steilufer), nehmen sie beim nächsten Bau.
        int o = q.ordinal();
        brecher.quality = stufe(com.dan.spray.Spray.Quality.MEDIUM, o);
        brodten.quality = stufe(com.dan.cliff.Cliff.Quality.MEDIUM, o);
        wiring.flotte.sail.quality = stufe(com.dan.sail.Sail.Quality.MEDIUM, Math.min(o, 1) + Math.max(0, o - 2));     // Niedrig, Mittel, Mittel, Hoch: die Regatta rechnet viel
        wiring.flotte.regattaSail.quality = wiring.flotte.sail.quality;
        for (com.dan.timmendorf.scene.Leuchtfeuer.Licht l : wiring.leuchtfeuer.lichter) l.beacon().quality = stufe(com.dan.beacon.Beacon.Quality.MEDIUM, o);
        if (leben != null) {
            leben.vogel.quality = stufe(com.dan.flock.Flock.Quality.HIGH, o);
            leben.seehund.seal.quality = stufe(com.dan.seal.Seal.Quality.MEDIUM, o);
        }
        if (klang != null) klang.s.quality = stufe(com.dan.soundscape.Soundscape.Quality.MEDIUM, o);
        // seit Phase 21: Relief des Strands bis 60, 120, 200 oder 350 m um das Auge
        strand.quality = com.dan.beach.Beach.Quality.values()[Math.max(0, Math.min(3, o))];
        // seit Phase 20: Strandkörbe (die Netze baut der nächste Bild neu) und Flugsand wie in Sylt nach der Stufe
        koerbeGl.korb.quality = KoerbeGl.stufe(o);
        if (sand != null) sand.stufe(o);
    }

    /** Seit Phase 19: die Stufe eines Teils aus seiner Stufe auf „Mittel“ (o = 1) und der Stufe der Szene (0 bis 3), begrenzt. */
    static <E extends Enum<E>> E stufe(E mittel, int o) {
        E[] v = mittel.getDeclaringClass().getEnumConstants();
        return v[Math.max(0, Math.min(v.length - 1, mittel.ordinal() + o - 1))];
    }

    /** Höhe des Grunds am Ort: das Gelände (ohne daten/gelaende die Bühne). */
    public double groundHeight(double x, double z) { return terrain.height(x, z); }

    /** Stellt die Kamera auf eine Ansicht nach Namen. */
    public boolean placeView(Ansichten.Ansicht a) {
        camera.waterLevel = wiring.pegel;
        boolean ok = Ansichten.apply(a, camera, PHASE, this::groundHeight);
        if (ok) hudMeldung("Ansicht " + a);
        return ok;
    }

    /**
     * Seit Phase 12: ein Standort am Strand, s m längs der Küste von der Seebrücke (positiv nach Südost, Richtung Niendorf),
     * seit Phase 13 vor dem nächsten Strandkorb bis 12 m zur See auf trockenem Sand; dazu Blick (Grad) und Neigung zum Ponton
     * auf 180 m Höhe. {x, z, Blick, Neigung}.
     */
    public double[] strandPlatz(double s) {
        double px = Site.SCHLEIFE.x(), pz = Site.SCHLEIFE.z(), l = Math.hypot(px, pz);
        double ux = px / l, uz = pz / l, kx = -uz, kz = ux;          // längs der Seebrücke, längs der Küste
        if (kz < 0) { kx = -kx; kz = -kz; }                          // nach Südost (z nach Süden)
        double x = kx * s, z = kz * s;
        // seit Phase 13: am Strand vor dem nächsten Strandkorb, 10 m zur See (Befund Phase 12: landwärts gesucht stand die
        // Kamera zwischen den Häusern)
        com.dan.timmendorf.scene.Bauwerke.Korb best = null;
        double bd = 250;
        for (com.dan.timmendorf.scene.Bauwerke.Korb k : wiring.koerbe) {
            double d = Math.hypot(k.x() - x, k.z() - z);
            if (d < bd) { bd = d; best = k; }
        }
        if (best != null) {
            double a = Math.toRadians(best.richtung());
            x = best.x(); z = best.z();
            for (int i = 0; i < 12 && groundHeight(x + Math.sin(a), z - Math.cos(a)) > wiring.pegel + 0.4; i++) { x += Math.sin(a); z -= Math.cos(a); }
        } else for (int i = 0; i < 60 && groundHeight(x, z) < wiring.pegel + 0.6; i++) { x -= ux * 5; z -= uz * 5; }
        double[] p = wiring.ponton;
        double dx = p[0] - x, dz = p[1] - z, d = Math.hypot(dx, dz);
        // Neigung: die Kamera zählt sie nach unten positiv (Befund Phase 12: die Bilder schauten in den Boden)
        return new double[]{x, z, Math.toDegrees(Math.atan2(dx, -dz)), -Math.toDegrees(Math.atan2(180 - groundHeight(x, z) - 1.7, d)) * 0.75};
    }

    /**
     * Seit Phase 14: die Kamera zur Sturmflut: am Strand mit Blick auf die Seebrücke (Brecher), oder auf der Promenade am
     * Landende (Wasser auf der Promenade).
     */
    public void zurSturmflut(boolean promenade) {
        double[] q = strandPlatz(promenade ? 40 : 220);
        double x = q[0], z = q[1];
        if (promenade) {   // 25 m landwärts vom Strandkorb, Blick über den Strand zur See
            double l = Math.hypot(Site.SCHLEIFE.x(), Site.SCHLEIFE.z());
            x -= Site.SCHLEIFE.x() / l * 25;
            z -= Site.SCHLEIFE.z() / l * 25;
        }
        double dx = Site.SCHLEIFE.x() * 0.5 - x, dz = Site.SCHLEIFE.z() * 0.5 - z;
        camera.place(x, groundHeight(x, z) + 1.7, z, Math.toDegrees(Math.atan2(dx, -dz)), 2);
        hudMeldung(promenade ? "Promenade am Landende, Scheitel der Sturmflut" : "Am Strand, Blick auf die Seebrücke");
    }

    /**
     * Seit Phase 20: „Sandsturm am Strand“ wie in Sylt: Wind Bft 9 auflandig (aus der Richtung, in die die Körbe schauen,
     * 25° schräg), trocken, Flugsand und Körner an; die Kamera steht zwischen den Körben südöstlich der Seebrücke und schaut
     * mit dem Wind schräg über den Strand.
     */
    public void sandsturm() {
        wiring.regie.hand();   // von Hand (die Regie hielte sonst ihre Lage)
        double[] q = strandPlatz(260);
        double see = 60;
        com.dan.timmendorf.scene.Bauwerke.Korb best = null;
        double bd = 1e9;
        for (com.dan.timmendorf.scene.Bauwerke.Korb k : wiring.koerbe) {
            double d = Math.hypot(k.x() - q[0], k.z() - q[1]);
            if (d < bd) { bd = d; best = k; }
        }
        if (best != null) see = best.richtung();
        wiring.windSpeed = 22;
        wiring.windFromDeg = (see + 25 + 360) % 360;
        sand.regen = 0;
        sandNass = 0;
        sand.an = true;
        showKoerner = true;
        // zwischen den Reihen landwärts vom Platz am Wasser, Blick längs der Reihen und etwas mit dem Wind (zum Land hin)
        double a = Math.toRadians(see), x = q[0] - Math.sin(a) * 14, z = q[1] + Math.cos(a) * 14;
        camera.place(x, groundHeight(x, z) + 1.6, z, see - 105, 4);
        hudMeldung(String.format(Locale.GERMAN, "Sandsturm am Strand: Bft 9 aus %03.0f° (auflandig), trockener Sand", wiring.windFromDeg));
    }

    /** Seit Phase 20: Windfeld und Flugsand um das Auge sofort, Körner 3 s vorlaufen (für Standbilder). */
    public void sandJetzt() {
        camera.snap();
        sand.bereit(camera.eye, wiring.windSpeed, wiring.windFromDeg, 3);
    }

    /** Seit Phase 12: die Kamera an den Strand mit Blick zum Ponton (Knopf „Zum Abend“). */
    public void zumFeuerwerk() {
        double[] q = strandPlatz(320);
        camera.place(q[0], groundHeight(q[0], q[1]) + 1.7, q[1], q[2], q[3]);
        hudMeldung("Am Strand, Blick zum Feuerwerk");
    }

    /** Eine Meldung für 5 s im HUD (und in der Statuszeile). */
    public void hudMeldung(String text) {
        meldung = text;
        meldungUntil = System.currentTimeMillis() + 5000;
    }

    /**
     * Belichtung: seit Phase 4 aus der Augenanpassung an Himmel und Bild ({@code com.dan.sky}, gemessen mit
     * {@link Belichtung}, aus Brocken), ohne Himmel aus der Sonnenhöhe des Platzhalters; dazu die Blenden von Hand.
     */
    public double exposure() {
        if (wetter.skyOk && adaptLum > 0) return Sky.State.exposureFor(adaptLum, adaptation) * Math.pow(2, exposureBias);
        double b = Sun.skyBrightness(clock.sun()[0]);
        return 0.6 * Math.pow(b, -0.85) * Math.pow(2, exposureBias);
    }

    /** Augenanpassung neu (nach Sprüngen der Uhr oder der Kamera). */
    public void readapt() {
        viewLum = -1;
        adaptLum = -1;
        belichtung.reset();
        if (wetter.skyOk) adapt(wetter.state(), 1);
    }

    /** Wolkenschatten am Auge (1 = Sonne). */
    public double cloudShade() { return cloudShade; }
    public boolean skyRunning() { return wetter.skyOk; }
    public boolean cloudsRunning() { return wetter.cloudsOk; }
    public boolean oceanRunning() { return meer.ok; }

    private void adapt(Sky.State st, double dt) {
        viewTimer -= dt;
        if (viewLum <= 0 || viewTimer <= 0) {
            viewLum = st.viewLuminance(camera.fwd.clone(), new double[]{0, 1, 0}, fovy(), surfW / (double) Math.max(1, surfH));
            viewTimer = 0.2;
        }
        double gemessen = bildMessung ? belichtung.lum() : -1;
        // seit Phase 15 (Befund Phase 14: Nachtbilder an der Promenade weiß): helle Flächen halten die Anpassung hoch,
        // auch wenn der Rest des Bilds schwarz ist; ein Viertel des linearen Mittels ist die Untergrenze [G]
        if (gemessen > 0 && belichtung.hell() > 0) gemessen = Math.max(gemessen, HELL_ANTEIL * belichtung.hell());
        double target = gemessen > 0
                ? Math.pow(Math.max(1e-14, st.adaptation), 1 - BILD_FOLGT) * Math.pow(Math.max(1e-14, gemessen * BILD_GAIN), BILD_FOLGT)
                : Math.sqrt(Math.max(1e-14, st.adaptation) * Math.max(1e-14, viewLum))
                * (wetter.cloudsOk && showClouds ? Math.pow(wetter.clouds.brightnessFactor(), STORM_ADAPT) : 1);
        if (adaptLum <= 0 || frozen || dt > 0.5) adaptLum = target;
        else adaptLum = Math.exp(Math.log(adaptLum) + (Math.log(target) - Math.log(adaptLum)) * Math.min(1, dt / 1.0));
    }

    /** Kinomodus verlassen (Esc im Kino); das Regiebuch läuft im Fenster weiter. */
    public void kinoVerlassen() { frozen = false; kino.abbrechen(); }

    /** Eine Arbeit im Faden des Bildlaufs (vor dem nächsten Schritt). */
    public void imBild(Runnable r) { imBild.add(r); }

    // ------------------------------------------------------------------ GL

    @Override
    public void init(GLAutoDrawable d) {
        initGl(d.getGL().getGL4(), d.getContext().getGLVersionNumber().getMajor(), d.getContext().getGLVersionNumber().getMinor(),
                d.getContext().getDefaultDrawFramebuffer());
    }

    /** Start auf einer Grafikkarte ohne Fenster-Hülle (für {@link #init} und Prüfstände): Version, Bildpuffer des Ziels. */
    public void initGl(GL4 gl, int major, int minor, int targetFbo) {
        String renderer = gl.glGetString(GL.GL_RENDERER), vendor = gl.glGetString(GL.GL_VENDOR), version = gl.glGetString(GL.GL_VERSION);
        glInfo = renderer + " · OpenGL " + major + "." + minor;
        System.out.println("Timmendorf: " + vendor + " · " + renderer + " · " + version);
        if (major < MIN_MAJOR || (major == MIN_MAJOR && minor < MIN_MINOR)) {
            fail("Die Grafikkarte meldet OpenGL " + major + "." + minor + " (" + renderer + ", Treiber " + version
                    + "). Timmendorf braucht OpenGL " + MIN_MAJOR + "." + MIN_MINOR + ".\nBitte den Grafiktreiber aktualisieren.");
            return;
        }
        gl.setSwapInterval(1);
        defaultFbo = targetFbo;
        reversedZ = (major > 4 || minor >= 5) || gl.isExtensionAvailable("GL_ARB_clip_control");
        if (reversedZ) gl.glClipControl(GL4.GL_LOWER_LEFT, GL4.GL_ZERO_TO_ONE);
        try {
            for (Shader s : all) s.build(gl);
        } catch (RuntimeException e) {
            fail(e.getMessage());
            return;
        }
        int[] v = new int[1];
        gl.glGenVertexArrays(1, v, 0);
        emptyVao = v[0];
        for (int[] s : stamp) gl.glGenQueries(MARKS, s, 0);
        compute = ComputeProbe.run(gl);
        System.out.println("Timmendorf: " + compute.line());
        lod.upload(gl, terrain);
        System.out.println("Timmendorf: Gelände " + terrain.describe());
        // Phase 4: Himmel und Wolken, See, Luftbild, Bauten, Belichtung (jedes für sich; was nicht startet, fehlt)
        wetter.init(gl, errorListener);
        wiring.cloudsDrawn = wetter.cloudsOk;
        meer.init(gl, terrain, compute.ok(), errorListener);
        luftbildTexturen(gl);
        bautenGl.init(gl);
        int[] zaehler = new int[3];
        com.dan.timmendorf.scene.Baunetz fest = bauten.netz(zaehler, wiring.bauwerke::ersetzt);
        com.dan.timmendorf.scene.Baunetz bw = wiring.bauwerke.netz(terrain::height, wiring.fuss);
        bautenGl.upload(gl, BautenGl.FEST, fest);
        bautenGl.upload(gl, BautenGl.HERDE, bw);   // der dritte Puffer (in Sylt die Herde) trägt hier die Bauwerke
        if (wiring.lichter.laternen == 0) wiring.lichter.laternen(bw.lichter);   // seit Phase 12: die Laternen leuchten
        leben.laternen(bw.lichter);   // seit Phase 13: Sitzplätze der Vögel
        pfaehle = new java.util.ArrayList<>(bw.pfaehle);   // seit Phase 14: Brecher an den Pfählen
        int[] bz = wiring.bauwerke.zahl;
        bauwerkeInfo = String.format(Locale.GERMAN, "Bauwerke: %d Brücken, %d Stege, %,d Pfähle, %d + %d Laternen, %d DLRG-Türme, %d Kutter · %,d Dreiecke",
                bz[0], bz[1], bz[2], bz[3], bz[4], bz[5], bz[6], bw.dreiecke());
        System.out.println("Timmendorf: " + bauwerkeInfo);
        System.out.println(String.format(Locale.GERMAN, "Timmendorf: Bauten %s · %s · Dächer: %d Reet, %d grau, %d rot", bauten.describe(), bautenGl.info,
                zaehler[0], zaehler[1], zaehler[2]));
        bautenGl.upload(gl, BautenGl.KOERBE, new com.dan.timmendorf.scene.Baunetz());   // seit Phase 20 leer: die Körbe zeichnet KoerbeGl
        koerbeGl.init(gl, koerbe, KoerbeGl.stufe(quality.ordinal()));
        System.out.println("Timmendorf: " + koerbeGl.info);
        sandGl.init(gl);   // seit Phase 20
        try {   // seit Phase 21
            strandGl.init(gl);
            System.out.println("Timmendorf: " + strandGl.info() + " · " + strand.describe());
        } catch (RuntimeException e) {
            System.err.println("Timmendorf: Strand (beach) nicht verfügbar: " + e.getMessage());
        }
        bewuchsGl.init(gl);
        booteGl.init(gl);
        menschenGl.init(gl);
        try {
            beaconGl.init(gl);
        } catch (RuntimeException e) {
            System.err.println("Timmendorf: Leuchtfeuer (Lichtpunkte) nicht verfügbar: " + e.getMessage());
        }
        try {
            cliffGl.init(gl);
            cliffGl.upload(gl, brodtenKarte);
        } catch (RuntimeException e) {
            System.err.println("Timmendorf: Brodtener Ufer (cliff) nicht verfügbar: " + e.getMessage());
        }
        try {
            lampGl.init(gl);
            kopfGl.init(gl);
            kopfGl.limit = 4000;
            kopfGl.minPixels = 0.7;
        } catch (RuntimeException e) {
            System.err.println("Timmendorf: Leuchten (lamp) nicht verfügbar: " + e.getMessage());
        }
        // seit Phase 13: Unwetter, Tiere, Eis und Schneefall
        unwetter.init(gl, errorListener);
        unwetter.blitz.onStroke = (b, k, x, y, z, cd) -> wetter.sky.flash(x - camera.eye[0], y - camera.eye[1], z - camera.eye[2],
                Math.min(1, cd / 2e8), wetter.sky.time(), 0.5);
        lebenGl.init(gl);
        try { brecherGl.init(gl); brecherOk = true; } catch (RuntimeException e) { System.err.println("Timmendorf: Brecher (spray) nicht verfügbar: " + e.getMessage()); }
        try { quallenGl.init(gl); quallenOk = true; } catch (RuntimeException e) { System.err.println("Timmendorf: Quallen (jelly) nicht verfügbar: " + e.getMessage()); }
        try { eisGl.init(gl); eisOk = true; } catch (RuntimeException e) { System.err.println("Timmendorf: Eis (season) nicht verfügbar: " + e.getMessage()); }
        try { schneeGl.init(gl); schneeOk = true; } catch (RuntimeException e) { System.err.println("Timmendorf: Schneefall (season) nicht verfügbar: " + e.getMessage()); }
        regisseur.initGl(gl);   // seit Phase 16: Balken, Blenden, Titel
        try {
            fwGl.init(gl);
        } catch (RuntimeException e) {
            System.err.println("Timmendorf: Feuerwerk (FireworkGl) nicht verfügbar: " + e.getMessage());
        }
        belichtung.init(gl);
        physik.start();
        lastNanos = System.nanoTime();
        statsNanos = lastNanos;
        camera.snap();
        ready = true;
    }

    @Override
    public void reshape(GLAutoDrawable d, int x, int y, int w, int h) {
        surfW = Math.max(1, d.getSurfaceWidth());
        surfH = Math.max(1, d.getSurfaceHeight());
    }

    /** Uhr, Physik und Kamera um dt weiterrechnen (vom Bildlauf aufgerufen; Standbilder rufen es mit festem dt). */
    public void step(double dt) {
        for (Runnable r; (r = imBild.poll()) != null; ) {
            try { r.run(); } catch (RuntimeException e) { System.err.println("Timmendorf: Arbeit im Bildlauf: " + e); }
        }
        clock.step(dt);
        regisseur.schritt(dt);   // seit Phase 16: fällige Stichworte des Regiebuchs, dann das Kino
        physik.advance(dt);
        camera.groundLevel = groundHeight(camera.eye[0], camera.eye[2]);
        camera.waterLevel = wiring.pegel;
        camera.targetGround = Math.max(groundHeight(camera.tx, camera.tz), wiring.pegel);
        camera.step(dt);
        // Phase 4: Himmel, Wolken und See bekommen ihre Eingänge aus der Verdrahtung, dann ein Schritt
        boolean cloudsShown = wetter.cloudsOk && showClouds;
        wiring.cloudsDrawn = cloudsShown;
        wiring.feed(wetter.sky, camera.eye[1]);
        wiring.feed(wetter.clouds);
        wiring.feed(meer.ocean);
        meer.ocean.step(dt);
        if (wetter.skyOk) {
            Sky.State st = wetter.sky.step(dt);
            wetter.clouds.step(dt);
            cloudShade = cloudsShown ? Clouds.shadow(wetter.clouds.snapshot(), camera.eye[0], camera.eye[2], Wetter.keyDir(st)) : 1;
            adapt(st, dt);
        }
        // seit Phase 14: Abbrüche nach der Uhr, Brecher an den Pfählen
        abbruch.stand(showSturmflut ? com.dan.timmendorf.scene.Sturmflut.stundeRoh(clock.epochMillis()) : -1);
        brecherSchritt(Math.min(0.1, dt * Math.max(0, clock.lapse)));
        // seit Phase 13: Regen, Seenebel, Gewitter; die Tiere (Szenenzeit, höchstens 0,1 s je Schritt)
        unwetter.schritt(dt, wiring, wetter.clouds, cloudsShown, camera.eye, terrain::height);
        double sceneDt = Math.min(0.1, dt * Math.max(0, clock.lapse));
        // seit Phase 20: Flugsand wie in Sylt; Regen macht den Sand nass, der Auflauf folgt dem Seegang, Schnee bindet ihn
        if (unwetter.regnet()) sandNass += (1 - sandNass) * Math.min(1, Math.max(dt, sceneDt) / 300);
        else sandNass -= sandNass * Math.min(1, sceneDt / (3 * 3600));
        sand.regen = 0.06 * sandNass;
        sand.auflauf = 0.3 + 0.7 * wiring.seeHs;
        sand.schnee = showJahr ? wiring.jahr.schnee() : 0;
        sand.schritt(dt, camera.eye, wiring.windSpeed, wiring.windFromDeg);
        if (showLeben && sceneDt > 0) {
            double sonne = clock.sun()[0];
            com.dan.season.Year.State js = wiring.jahr.state();
            // das Revier folgt dem Blick: 150 m vor dem Auge (Befund Phase 13: camera.tx stand im freien Flug still)
            leben.schritt(sceneDt, camera.eye, camera.eye[0] + camera.fwd[0] * 150, camera.eye[2] + camera.fwd[2] * 150, wiring.windSpeed, wiring.windFromDeg, Wiring.umgebungsLux(sonne), sonne, clock.date(),
                    clock.hour(), wiring.pegel, js != null && js.ice() > 0.03 ? js.iceDepth() : 0, wiring.seeHs);
            // seit Phase 17: die Quallen, ihr Feld liegt 20 m vor dem Auge (sie zählen nur in der Nähe)
            quallen.dayOfYear = clock.date().getDayOfYear();
            quallen.waterTemp = js != null ? js.waterTemp() : 17;
            quallen.enabled = js == null || js.ice() < 0.03;
            quallen.windSpeed = wiring.windSpeed;
            quallen.windFromDeg = wiring.windFromDeg;
            quallen.hs = wiring.seeHs;
            quallen.waterLevel = wiring.pegel;
            quallen.step(sceneDt, camera.eye[0] + camera.fwd[0] * 20, camera.eye[2] + camera.fwd[2] * 20);
        }
        // seit Phase 15: der Klang hört an der Kamera zu
        klang.feed(dt);
    }

    @Override
    public void display(GLAutoDrawable d) {
        displayGl(d.getGL().getGL4(), d.getSurfaceWidth(), d.getSurfaceHeight(), d.getContext().getDefaultDrawFramebuffer());
    }

    /** Ein Bild in das Ziel targetFbo der Größe w × h (für {@link #display} und Prüfstände). */
    public void displayGl(GL4 gl, int width, int height, int targetFbo) {
        if (!ready) {
            gl.glClearColor(0.06f, 0.075f, 0.08f, 1);
            gl.glClear(GL.GL_COLOR_BUFFER_BIT);
            return;
        }
        if (reloadShaders) {
            reloadShaders = false;
            try {
                for (Shader s : all) s.build(gl);
                hudMeldung("Shader neu geladen");
                System.out.println("Shader neu geladen.");
            } catch (RuntimeException e) {
                System.err.println(e.getMessage());
                errorListener.accept(e.getMessage());
            }
        }
        defaultFbo = targetFbo;
        readMarks(gl);
        long now = System.nanoTime();
        double rawMs = (now - lastNanos) / 1e6;
        if (frame > 2 && rawMs > worstMs) worstMs = rawMs;
        double dt = Math.min(0.1, (now - lastNanos) / 1e9);
        lastNanos = now;
        if (!frozen) step(dt);

        surfW = Math.max(1, width);
        surfH = Math.max(1, height);
        regulateScale(now);
        int w = Math.max(64, (int) Math.round(surfW * renderScale)), h = Math.max(64, (int) Math.round(surfH * renderScale));
        if (w != hdrW || h != hdrH) resizeTargets(gl, w, h);
        renderFrame(gl, w, h);
        regisseur.bild(gl, targetFbo, surfW, surfH, now);   // seit Phase 16: Fotos des Regiebuchs (ohne HUD und Balken), dann das Kino; F12 mit
        if (photoRequest) { photoRequest = false; takePhoto(gl, surfW, surfH); }
        Consumer<BufferedImage> gr = grabRequest;
        if (gr != null) { grabRequest = null; try { gr.accept(readDefault(gl, surfW, surfH)); } catch (RuntimeException e) { System.err.println("Timmendorf: Bild nicht abgeholt: " + e); } }
        cpuMs = cpuMs * 0.95 + (System.nanoTime() - now) / 1e6 * 0.05;
        frame++;
        stats(now, w, h);
    }

    void renderFrame(GL4 gl, int w, int h) {
        mark(gl, 0);
        frameSky = wetter.skyOk;
        Sky.State st = frameSky ? wetter.state() : null;
        if (frameSky && adaptLum <= 0) adapt(st, 1);
        double pre = frameSky ? exposure() : 1;
        double[] sp = clock.sun();
        double[] sun = frameSky ? st.sunDir : Sun.direction(sp[0], sp[1]);
        float[] proj = Mat4.perspective(fovy(), w / (double) h, NEAR, FAR, reversedZ);
        float[] vp = Mat4.mul(proj, camera.view());
        float[] inv = Mat4.invert(vp);
        boolean cloudsNow = frameSky && wetter.cloudsOk && showClouds;
        Clouds.Snapshot cs = wetter.clouds.snapshot();
        CloudGl.Light cl = null;
        // seit Phase 13: Ringe des Regens, Blitze hochladen, das Blitzlicht am Auge
        unwetter.hochladen(gl, camera.eye);

        // 1 Himmel: com.dan.sky ins Bild, dann das Rundumbild mit Wolken und die Wolkenschatten (aus Sylt Phase 3);
        //   ohne com.dan.sky der Verlauf aus Phase 1
        gl.glBindFramebuffer(GL.GL_FRAMEBUFFER, hdrFbo);
        gl.glViewport(0, 0, w, h);
        gl.glClearDepth(reversedZ ? 0 : 1);
        gl.glClearColor(0, 0, 0, 1);
        gl.glDepthMask(true);
        gl.glClear(GL.GL_COLOR_BUFFER_BIT | GL.GL_DEPTH_BUFFER_BIT);
        gl.glDisable(GL.GL_DEPTH_TEST);
        gl.glDisable(GL.GL_BLEND);
        gl.glBindVertexArray(emptyVao);
        if (frameSky) {
            wetter.skyGl.update(gl, st);
            gl.glBindFramebuffer(GL.GL_FRAMEBUFFER, hdrFbo);
            gl.glViewport(0, 0, w, h);
            SkyGl.View sv = new SkyGl.View();
            sv.viewProj = vp;
            sv.invViewProj = inv;
            sv.reversedZ = reversedZ;
            sv.width = w;
            sv.height = h;
            sv.fovyDeg = fovy();
            sv.preExposure = pre;
            sv.time = clock.t();
            wetter.skyGl.draw(gl, st, sv);
            if (wetter.skyCheck.isEmpty() && frame > 30) wetter.skyCheck = wetter.skyGl.selfCheck(gl, st);
            gl.glBindVertexArray(emptyVao);
            if (cloudsNow) {
                cl = wetter.cloudLight(st, pre);
                unwetter.wolken(cl, pre);   // seit Phase 13: Blitzlicht in den Wolken
                wetter.cloudGl.update(gl, cs, cl, camera.eye.clone());
                if (wetter.cloudCheck.isEmpty() && frame > 40) wetter.cloudCheck = wetter.cloudGl.selfCheck(gl, cs, cl);
            }
            gl.glBindFramebuffer(GL.GL_FRAMEBUFFER, wetter.panoFbo);
            gl.glViewport(0, 0, Wetter.PANO_W, Wetter.PANO_H);
            wetter.skyGl.drawPanorama(gl, st, pre);
            if (cloudsNow) wetter.cloudGl.drawPanorama(gl, cs, cl, camera.eye.clone());
            unwetter.rundum(gl, Wetter.PANO_W, Wetter.PANO_H, camera.eye.clone(), pre, wiring.visibilityKm);   // seit Phase 13: Blitze spiegeln
            gl.glBindTexture(GL.GL_TEXTURE_2D, wetter.panoTex);
            gl.glGenerateMipmap(GL.GL_TEXTURE_2D);
            gl.glBindTexture(GL.GL_TEXTURE_2D, 0);
            gl.glBindFramebuffer(GL.GL_FRAMEBUFFER, hdrFbo);
            gl.glViewport(0, 0, w, h);
            gl.glBindVertexArray(emptyVao);
        } else {
            common(gl, skyShader, vp, inv, sun);
            gl.glDrawArrays(GL.GL_TRIANGLES, 0, 3);
        }
        mark(gl, 1);

        // seit Phase 12: die Lichter dieses Bilds (vor Gelände und Häusern, die sie lesen)
        lichterVorbereiten(gl, pre);

        // 2 Gelände: Kern, Hauptraster und Fernring als CDLOD; Luftbild im Kern (Einheit 14) und im Hauptraster (5),
        //   Klassen je Meter (12), Wolkenschatten (13), seit Phase 9 das Brodtener Ufer (3, 4, 15), mit Marken
        gl.glEnable(GL.GL_DEPTH_TEST);
        gl.glDepthMask(true);
        gl.glDepthFunc(reversedZ ? GL.GL_GREATER : GL.GL_LESS);
        common(gl, terrainShader, vp, inv, sun);
        lod.bind(gl, terrainShader);
        bindMarks(gl, terrainShader);
        bindTex(gl, 12, klasseTex);
        bindTex(gl, 13, cloudsNow ? wetter.cloudGl.shadowTexture() : 0);
        bindTex(gl, 14, luftKernTex);
        bindTex(gl, 5, luftHauptTex);
        terrainShader.set(gl, "uKlasse", 12);
        terrainShader.set(gl, "uCloudShadow", 13);
        terrainShader.set(gl, "uLuftKern", 14);
        terrainShader.set(gl, "uLuftHaupt", 5);
        // seit Phase 9: Brodtener Ufer (Wandkarte Einheit 4, Schichtkarte 3, Rauschen 15)
        boolean kliff = showKliff && cliffGl.ready();
        if (kliff) cliffGl.bind(gl, terrainShader.id(), brodten, 4);
        terrainShader.set(gl, "uCliffOn", kliff ? 1 : 0);
        // seit Phase 21: Relief und Korn des Strands (com.dan.beach); immer gebunden, damit der Sampler eine eigene Einheit hat
        strandGl.bind(gl, terrainShader.id(), strand, STRAND_UNIT, showStrand && strandGl.ready());
        terrainShader.set(gl, "uLuftOn", (showLuftbild && luftKernTex != 0 ? 1 : 0) | (showLuftbild && luftHauptTex != 0 ? 2 : 0) | (klasseTex != 0 ? 4 : 0));
        double[] cr = cloudsNow ? wetter.cloudGl.shadowRect() : new double[]{0, 0, 1};
        terrainShader.set(gl, "uCloudRect", (float) cr[0], (float) cr[1], (float) Math.max(1, cr[2]), cloudsNow ? 1f : 0f);
        lampenBinden(gl, terrainShader);
        korrBinden(gl);
        terrainShader.set(gl, "uSchnee", (float) (showJahr ? wiring.jahr.schnee() : 0), (float) (showJahr ? wiring.jahr.reif() : 0), (float) wiring.pegel, 0f);
        lod.draw(gl, terrainShader, camera.eye, vp);
        gl.glBindVertexArray(emptyVao);
        mark(gl, 2);

        // 3 Bauten: die Häuser aus LOD2 (in hdrFbo, mit Tiefe)
        if (showBauten && bautenGl.ok) drawBauten(gl, vp, inv, sun, pre, cloudsNow, cr);
        // seit Phase 20: die Strandkörbe aus com.dan.beachchair (erst ihre Schatten auf dem Sand, dann die Körbe)
        koerbeDa = com.dan.timmendorf.scene.Bauwerke.saison(clock.date()) && showKoerbe && showBauwerke;
        if (koerbeDa && koerbeGl.ok()) drawKoerbe(gl, vp, st, pre, sun);
        mark(gl, 3);

        // 3a Bewuchs (Phase 5a): Bäume aus com.dan.tree, Halme aus com.dan.reed (in hdrFbo, mit Tiefe)
        if (showBewuchs && bewuchsGl.ok()) drawBewuchs(gl, vp, sun, pre, cloudsNow, cr);
        mark(gl, 4);

        // 3b Boote (Phase 8a): Segelboote, Kiter, Passat, Fähren (in hdrFbo, mit Tiefe; die See deckt ab, was unter Wasser liegt)
        if (showBoote && booteGl.ok) drawBoote(gl, vp, sun);
        mark(gl, 5);

        // 3c Menschen (Phase 10a): Spaziergänger, Badegäste, Zuschauer aus com.dan.figure (in hdrFbo, mit Tiefe)
        if (wiring.showMenschen && menschenGl.ok && wiring.menschen != null) drawMenschen(gl, vp, sun);
        // seit Phase 13: Möwen, Schwäne, Kormorane, Seehund (nach dem Gelände, vor der See)
        if (showLeben && frameSky) {
            lebenGl.draw(gl, leben, vp, camera.eye, reversedZ, licht(st, pre), Wetter.fogDistance(st), cloudShade, frame);
            gl.glBindFramebuffer(GL.GL_FRAMEBUFFER, hdrFbo);
            gl.glViewport(0, 0, w, h);
            gl.glBindVertexArray(emptyVao);
        }
        mark(gl, 6);

        // 4 Meer: com.dan.ocean mit dem Gelände als Grund (nach dem Gelände, mit Tiefentest); ohne Compute der Platzhalter,
        //   der die Tiefe des Geländes liest (Einheit 6)
        meer.pegelMaske(gl, terrain, wiring.pegel);   // seit Phase 14: die See läuft bei Sturmflut über Strand und Promenade
        if (showMeer && meer.ok && showOcean) {
            gl.glEnable(GL.GL_DEPTH_TEST);
            gl.glDepthMask(true);
            final double preM = pre;
            meer.zusatz = ol -> unwetter.see(ol, preM);   // seit Phase 13: Ringe des Regens, Blitzlicht
            meer.draw(gl, vp, camera.eye, reversedZ, st, pre, frameSky ? wetter.panoTex : 0, cloudsNow ? wetter.cloudGl.shadowTexture() : 0,
                    cr, lod.heightTexture(), sun, sunLight(sp[0]), FOG_DISTANCE, wiring.wake, wiring.flotte.rumpfe(camera.eye[0], camera.eye[2], 8, 3000));
            if (meer.check.isEmpty() && frame > 30) meer.check = meer.oceanGl.selfCheck(gl, meer.ocean);
            gl.glBindFramebuffer(GL.GL_FRAMEBUFFER, hdrFbo);
            gl.glViewport(0, 0, w, h);
            gl.glBindVertexArray(emptyVao);
            // seit Phase 13: Eis auf der Bucht (com.dan.season), knapp über dem Wasser, wo der Grund flacher liegt als die Eisgrenze
            if (showJahr && eisOk && frameSky) drawEis(gl, vp, w, h, st, pre);
            // seit Phase 17: Ohrenquallen durch die Oberfläche (ohne Tiefe zu schreiben)
            if (showLeben && quallenOk && frameSky) drawQuallen(gl, vp, st, pre);
        } else if (showMeer) {
            gl.glDisable(GL.GL_DEPTH_TEST);
            gl.glBindFramebuffer(GL.GL_FRAMEBUFFER, hdrColorFbo);
            gl.glViewport(0, 0, w, h);
            common(gl, meerShader, vp, inv, sun);
            lod.bind(gl, meerShader);
            meerShader.set(gl, "uKorrOn", 0);
            gl.glActiveTexture(GL.GL_TEXTURE6);
            gl.glBindTexture(GL.GL_TEXTURE_2D, depthTex);
            gl.glActiveTexture(GL.GL_TEXTURE0);
            meerShader.set(gl, "uDepth", 6);
            gl.glDrawArrays(GL.GL_TRIANGLES, 0, 3);
            gl.glActiveTexture(GL.GL_TEXTURE6);
            gl.glBindTexture(GL.GL_TEXTURE_2D, 0);
            gl.glActiveTexture(GL.GL_TEXTURE0);
        }
        // seit Phase 20: die Körner des Flugsands (durchscheinend, mit Tiefentest, ohne Tiefe zu schreiben; aus Sylt)
        if (showKoerner && sand.an && sand.feld() != null && frameSky) {
            gl.glBindFramebuffer(GL.GL_FRAMEBUFFER, hdrFbo);
            gl.glViewport(0, 0, w, h);
            gl.glEnable(GL.GL_DEPTH_TEST);
            gl.glDepthFunc(reversedZ ? GL.GL_GREATER : GL.GL_LESS);
            sandGl.draw(gl, vp, camera.eye, sand, st, pre, sun, wetter, showClouds, sunLight(sp[0]), FOG_DISTANCE);
            gl.glDepthMask(true);
            gl.glBindVertexArray(emptyVao);
        }
        mark(gl, 7);

        // 5 Wolken nach der Szene mit ihrer Tiefe (ins Farbziel ohne Tiefe: die Tiefe wird gelesen; aus Brocken)
        gl.glDisable(GL.GL_DEPTH_TEST);
        if (cloudsNow) {
            gl.glBindFramebuffer(GL.GL_FRAMEBUFFER, hdrColorFbo);
            gl.glViewport(0, 0, w, h);
            CloudGl.View cv = new CloudGl.View();
            cv.viewProj = vp;
            cv.invViewProj = inv;
            cv.eye = camera.eye.clone();
            cv.reversedZ = reversedZ;
            cv.width = w;
            cv.height = h;
            cv.depthTexture = depthTex;
            wetter.cloudGl.draw(gl, cs, cl, cv);
            gl.glBindVertexArray(emptyVao);
        }
        // seit Phase 13: Seenebel (über das Bild mit der Tiefe), Regen und Schnee, Blitze (mit einer Kopie der Tiefe)
        if (frameSky) drawUnwetter(gl, vp, inv, w, h, st, pre);
        if (frameSky && brecherOk && !brecher.parcels().isEmpty()) drawBrecher(gl, vp, w, h, st, pre);   // seit Phase 14
        // seit Phase 9: Lichtpunkte der Leuchtfeuer (com.dan.beacon) mit der Tiefe der Szene
        if (showFeuer && beaconGl.ready()) drawFeuer(gl, vp, inv, w, h, pre);
        mark(gl, 8);

        // 5a seit Phase 12, Lichter: Spiegelung im Wasser, Feuerwerk, Lichtpunkte der Leuchten
        drawLichter(gl, vp, inv, w, h, sun);
        // Belichtung im Bild messen (alle 0,2 s, gelesen ohne Warten beim nächsten Mal; seit Phase 12 mit dem Feuerwerk)
        if (frameSky && bildMessung) {
            gl.glBindFramebuffer(GL.GL_FRAMEBUFFER, hdrColorFbo);
            belichtung.step(gl, lumShader, hdrTex, emptyVao, pre, frozen);
        }
        mark(gl, 9);

        // 6 Nachbearbeitung ins Fenster (mit com.dan.sky ist das Bild schon vorbelichtet)
        gl.glBindFramebuffer(GL.GL_FRAMEBUFFER, defaultFbo);
        gl.glViewport(0, 0, surfW, surfH);
        post.use(gl);
        gl.glActiveTexture(GL.GL_TEXTURE0);
        gl.glBindTexture(GL.GL_TEXTURE_2D, hdrTex);
        post.set(gl, "uHdr", 0);
        post.set(gl, "uExposure", frameSky ? 1f : (float) exposure());
        post.set(gl, "uScotopic", (float) (frameSky ? Wetter.scotopic(st) : 0));
        gl.glBindVertexArray(emptyVao);
        gl.glDrawArrays(GL.GL_TRIANGLES, 0, 3);
        mark(gl, 10);

        // 7 HUD (vorher abschicken: die Grafikkarte arbeitet, während das HUD im selben Faden gemalt wird; aus Brocken)
        gl.glFlush();
        if (showHud && !kino.laeuft() && !regisseur.fotoJetzt()) {   // seit Phase 16: nicht im Kino und nicht auf den Fotos des Regiebuchs
            hud.refresh(gl, 250, this::paintHud, frozen);
            hud.draw(gl, overlay, emptyVao, 12, Math.max(0, surfH - hud.height - 12), surfW, surfH);
        }
        gl.glBindVertexArray(0);
        gl.glUseProgram(0);
        mark(gl, 11);
    }

    private static void bindTex(GL4 gl, int unit, int tex) {
        gl.glActiveTexture(GL.GL_TEXTURE0 + unit);
        gl.glBindTexture(GL.GL_TEXTURE_2D, tex);
        gl.glActiveTexture(GL.GL_TEXTURE0);
    }

    /** Seit Phase 4: die Häuser zeichnen (in hdrFbo, mit Tiefe); Licht wie das Gelände, nachts zum Teil erleuchtete Fenster. */
    private void drawBauten(GL4 gl, float[] vp, float[] inv, double[] sun, double pre, boolean cloudsNow, double[] cr) {
        gl.glEnable(GL.GL_DEPTH_TEST);
        gl.glDepthMask(true);
        gl.glDepthFunc(reversedZ ? GL.GL_GREATER : GL.GL_LESS);
        gl.glDisable(GL.GL_BLEND);
        common(gl, bautenShader, vp, inv, sun);
        bindTex(gl, 13, cloudsNow ? wetter.cloudGl.shadowTexture() : 0);
        bautenShader.set(gl, "uCloudShadow", 13);
        bautenShader.set(gl, "uCloudRect", (float) cr[0], (float) cr[1], (float) Math.max(1, cr[2]), cloudsNow ? 1f : 0f);
        double e = clock.sun()[0];
        bautenShader.set(gl, "uNight", (float) smooth(-1, -6, e));
        // erleuchtetes Fenster rund 15 cd/m², warm [G]
        double k = (frameSky ? pre : exposure()) / Sky.CD_PER_UNIT;
        bautenShader.set(gl, "uWarm", (float) (15 * k), (float) (15 * k * 0.72), (float) (15 * k * 0.42));
        // seit Phase 9: Laterne des Alten Leuchtturms (Glas wie die Quermarke, Platz Q); Platz K bleibt frei
        com.dan.beacon.Beacon alt = wiring.leuchtfeuer.alter;
        com.dan.beacon.Beacon.Snapshot as = alt.snapshot();
        double[] ad = {camera.eye[0] - alt.x, camera.eye[1] - alt.y, camera.eye[2] - alt.z};
        double al = Math.max(1e-6, Math.sqrt(ad[0] * ad[0] + ad[1] * ad[1] + ad[2] * ad[2]));
        double[] lens = as.lit() ? as.lensLuminance(ad[0] / al, ad[1] / al, ad[2] / al) : new double[3];
        double glow = as.lit() ? as.lensGlow() : 0;
        bautenShader.set(gl, "uTurmQ", (float) alt.x, (float) alt.z, (float) alt.y, (float) com.dan.timmendorf.scene.Bauwerke.ALT_LINSE);
        bautenShader.set(gl, "uLensQ", (float) (lens[0] * k), (float) (lens[1] * k), (float) (lens[2] * k));
        bautenShader.set(gl, "uGlowQ", (float) (glow * k), (float) (glow * k * 0.85), (float) (glow * k * 0.6));
        bautenShader.set(gl, "uTurmK", 0f, 0f, -1000f, 0f);
        bautenShader.set(gl, "uLensK", 0f, 0f, 0f);
        bautenShader.set(gl, "uGlowK", 0f, 0f, 0f);
        // Leuchten der Laternen: seit Phase 12 LED 4 000 K, rund 2 000 cd/m² (2 500 lm in einer Kugel von 0,34 m), an mit dem
        // Dämmerungsschalter [G]; Anteil erleuchteter Fenster nach der Uhrzeit (com.dan.lamp.Windows)
        double[] led = com.dan.lamp.Lamp.Type.LED_4000K.rgb();
        double an = wiring.lichter.an() ? 2000 * k : 0;
        bautenShader.set(gl, "uLampe", (float) (led[0] * an), (float) (led[1] * an), (float) (led[2] * an));
        bautenShader.set(gl, "uFenster", (float) com.dan.timmendorf.scene.Lichter.fenster(wiring.stunde()));
        lampenBinden(gl, bautenShader);
        bautenShader.set(gl, "uSchnee", (float) (showJahr ? wiring.jahr.schnee() : 0), (float) (showJahr ? wiring.jahr.reif() : 0),
                (float) (showJahr ? wiring.jahr.dach() : 0), 0f);   // seit Phase 13
        // seit Phase 10a: Autos und Busse im Umkreis von 2 km, jedes Bild neu
        com.dan.timmendorf.scene.Verkehr vk = wiring.verkehr;
        boolean verkehr = vk != null && wiring.showVerkehr;
        if (verkehr) bautenGl.upload(gl, BautenGl.VERKEHR, vk.netz(camera.eye[0], camera.eye[2], 2000, wiring.stunde() * 3600));
        bautenGl.draw(gl, true, false, showBauwerke, verkehr);
        gl.glBindVertexArray(emptyVao);
    }

    /**
     * Seit Phase 20: die Strandkörbe (com.dan.beachchair) in hdrFbo mit Tiefe. Stellung nach Stunde, Regen, Wind und den
     * Gästen in den Körben; Licht wie die Tiere (Unwetter.licht), ohne com.dan.sky aus der Richtung der Sonne.
     */
    private void drawKoerbe(GL4 gl, float[] vp, Sky.State st, double pre, double[] sun) {
        koerbeGl.stufe(gl);
        com.dan.timmendorf.scene.Menschen m = wiring.menschen;
        koerbeGl.stellen(clock.date(), wiring.stunde(), unwetter.regnet(), wiring.windSpeed, m != null && wiring.showMenschen ? m.besetzt : null);
        gl.glEnable(GL.GL_DEPTH_TEST);
        gl.glDepthMask(true);
        gl.glDepthFunc(reversedZ ? GL.GL_GREATER : GL.GL_LESS);
        boolean himmel = frameSky && st != null;
        koerbeGl.zeichnen(gl, vp, camera.eye, himmel ? licht(st, pre) : null, himmel ? Wetter.fogDistance(st) : FOG_DISTANCE, sun, cloudShade, this::groundHeight);
        gl.glBindVertexArray(emptyVao);
    }

    /**
     * Seit Phase 9: die Leuchtfeuer als Lichtpunkte (Kern und Schleier nach Stiles-Holladay aus {@code BeaconGl}), je
     * Feuer die Beleuchtungsstärke am Auge aus seiner Lichtstärke in diese Richtung, gedämpft nach der Sicht.
     */
    private void drawFeuer(GL4 gl, float[] vp, float[] inv, int w, int h, double pre) {
        double k = (frameSky ? pre : exposure()) / Sky.CD_PER_UNIT, sigma = 3.0 / Math.max(500, wiring.visibilityKm * 1000);
        java.util.List<com.dan.beacon.gl.BeaconGl.Glare> g = new java.util.ArrayList<>();
        for (com.dan.timmendorf.scene.Leuchtfeuer.Licht l : wiring.leuchtfeuer.lichter) {
            com.dan.beacon.Beacon b = l.beacon();
            com.dan.beacon.Beacon.Snapshot s = b.snapshot();
            if (!s.lit()) continue;
            double[] e = s.illuminanceAt(camera.eye[0], camera.eye[1], camera.eye[2], sigma);
            if (e[0] + e[1] + e[2] < 1e-9) continue;
            g.add(new com.dan.beacon.gl.BeaconGl.Glare(b.x, b.y, b.z, e, 1, 2.5));
        }
        com.dan.beacon.gl.BeaconGl.View v = new com.dan.beacon.gl.BeaconGl.View();
        v.viewProj = vp;
        v.invViewProj = inv;
        v.eye = camera.eye.clone();
        v.reversedZ = reversedZ;
        v.width = w;
        v.height = h;
        v.fovyDeg = fovy();
        v.depthTexture = depthTex;
        v.targetFbo = hdrColorFbo;
        v.preExposure = k;
        for (int i = 0; i < g.size(); i += 8) beaconGl.drawGlare(gl, v, g.subList(i, Math.min(g.size(), i + 8)));
        feuerZahl = g.size();
        gl.glBindVertexArray(emptyVao);
    }

    private volatile int feuerZahl;

    /** Seit Phase 14: das Feld der Abbrüche an den Geländeshader (Einheit 2), hochgeladen, wenn es sich geändert hat. */
    private void korrBinden(GL4 gl) {
        int v = abbruch.version();
        boolean an = showSturmflut && abbruch.abbrueche() > 0;
        if (an && v != korrVersion) {
            int n = abbruch.n;
            if (korrTex == 0) {
                korrTex = Gpu.texture(gl, n, n, GL4.GL_R32F, false);
                gl.glBindTexture(GL.GL_TEXTURE_2D, korrTex);
                gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR);
                gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR);
                gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE);
                gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE);
            }
            float[] k;
            synchronized (abbruch) { k = abbruch.korr.clone(); }
            gl.glBindTexture(GL.GL_TEXTURE_2D, korrTex);
            gl.glTexSubImage2D(GL.GL_TEXTURE_2D, 0, 0, 0, n, n, GL4.GL_RED, GL.GL_FLOAT, Buffers.newDirectFloatBuffer(k));
            gl.glBindTexture(GL.GL_TEXTURE_2D, 0);
            korrVersion = v;
        }
        bindTex(gl, 2, an ? korrTex : 0);
        terrainShader.set(gl, "uKorr", 2);
        terrainShader.set(gl, "uKorrRect", (float) abbruch.x0, (float) abbruch.z0, (float) (abbruch.n * abbruch.zelle), (float) (abbruch.n * abbruch.zelle));
        terrainShader.set(gl, "uKorrOn", an && korrTex != 0 ? 1 : 0);
    }

    /**
     * Seit Phase 14: Brecher an den Pfählen der Seebrücken: bei Seegang über 0,8 m und hohem Wasser schlagen die Kämme an
     * die Pfähle bis 300 m um das Auge; je höher See und Pegel, desto öfter und höher [G].
     */
    private void brecherSchritt(double dt) {
        if (!brecherOk || dt <= 0) return;
        double hs = wiring.seeHs, lv = wiring.pegel, reach = hs - 0.8;
        boolean an = showSturmflut && reach > 0 && !pfaehle.isEmpty();
        pfahlTakt -= dt;
        java.util.List<double[]> nah = new java.util.ArrayList<>();
        if (an) for (double[] p : pfaehle) if (Math.hypot(p[0] - camera.eye[0], p[1] - camera.eye[2]) < 300 && p[2] < lv - 0.3) nah.add(p);
        if (an && !nah.isEmpty()) {
            if (brecher.sea == null) brecher.sea = new com.dan.spray.Sea() {
                @Override public double height(double x, double z) { return wiring.pegel; }
                @Override public double[] velocity(double x, double z) { return new double[3]; }
                @Override public double level() { return wiring.pegel; }
            };
            brecher.windSpeed = wiring.windSpeed;
            brecher.windFromDeg = wiring.windFromDeg;
            brecher.waveHeight = hs;
            brecher.focusX = camera.eye[0];
            brecher.focusZ = camera.eye[2];
            brecher.focusRadius = 250;
            brecher.strength = 1;
            double rate = Math.min(10, nah.size() * 0.15 * reach * (1 + Math.max(0, lv - 0.5)));
            brecherTakt += dt * rate;
            java.util.concurrent.ThreadLocalRandom rnd = java.util.concurrent.ThreadLocalRandom.current();
            while (brecherTakt >= 1) {
                brecherTakt -= 1;
                double[] p = nah.get(rnd.nextInt(nah.size()));
                brecher.impact(p[0], lv, p[1], 4 + rnd.nextDouble() * 12 * hs, 3 + 2.5 * hs + rnd.nextDouble() * 3);
                brecherZahl++;
            }
        } else brecherTakt = 0;
        if (an || !brecher.parcels().isEmpty()) brecher.step(dt);
    }

    /** Seit Phase 13: Licht für Nebel, Regen und Tiere (vorbelichtet, im Blitz der Blitz). */
    private double[][] licht(Sky.State st, double pre) {
        double a = Math.atan2(camera.fwd[0], -camera.fwd[2]);
        return Unwetter.licht(st, pre, wetter.ambient(st, showClouds), cloudShade, new double[]{Math.sin(a), 0.03, -Math.cos(a)}, unwetter.blitzE, unwetter.blitzDir);
    }

    /** Seit Phase 13: Nebel, Regen, Schneefall und Blitze (nach den Wolken). */
    private void drawUnwetter(GL4 gl, float[] vp, float[] inv, int w, int h, Sky.State st, double pre) {
        double schnee = showJahr ? wiring.jahr.schneefall() : 0;
        boolean faellt = schnee > 0.02 && schneeOk;
        if (!(unwetter.nebelt() || unwetter.regnet() || unwetter.blitzt() || faellt)) return;
        double[][] L = licht(st, pre);
        if (unwetter.nebelt()) {
            gl.glBindFramebuffer(GL.GL_FRAMEBUFFER, hdrColorFbo);
            gl.glViewport(0, 0, w, h);
            unwetter.zeichneNebel(gl, inv, camera.eye, reversedZ, w, h, depthTex, L, frozen, frame);
        }
        // Regen nur, wo es nicht schneit (Schnee fällt unter 2,5 °C als Flocken)
        boolean regnet = unwetter.regnet() && wiring.jahr.state().airTemp() > 0.5;
        if (regnet || unwetter.blitzt() || faellt) {
            int kopie = unwetter.tiefe(gl, hdrFbo, w, h);
            double unter = wetter.cloudsOk && showClouds ? Math.min(1, wetter.clouds.type.overcastBrightness * 1.6) : 1;
            if (regnet) unwetter.zeichneRegen(gl, vp, inv, camera.eye, camera.fwd, reversedZ, fovy(), w, h, kopie, L, unter, Wetter.fogDistance(st));
            unwetter.zeichneBlitze(gl, vp, inv, camera.eye, reversedZ, fovy(), w, h, hdrFbo, kopie, pre, wiring.visibilityKm);
            if (faellt) {
                gl.glBindFramebuffer(GL.GL_FRAMEBUFFER, hdrFbo);
                gl.glViewport(0, 0, w, h);
                gl.glDepthFunc(reversedZ ? GL.GL_GREATER : GL.GL_LESS);
                com.dan.season.gl.SeasonGl.View sv = seasonView(vp, w, h);
                double wr = Math.toRadians(wiring.windFromDeg);
                schneeGl.draw(gl, sv, seasonLight(L, st), schnee, new double[]{-Math.sin(wr), Math.cos(wr)}, wiring.windSpeed, clock.t());
            }
        }
        gl.glBindFramebuffer(GL.GL_FRAMEBUFFER, hdrColorFbo);
        gl.glViewport(0, 0, w, h);
        gl.glDisable(GL.GL_BLEND);
        gl.glDisable(GL.GL_DEPTH_TEST);
        gl.glDepthMask(true);
        gl.glBindVertexArray(emptyVao);
    }

    /** Seit Phase 14: die Gischt der Brecher (wie in Rungholt: Licht wie der Regen, Tiefe als Kopie). */
    private void drawBrecher(GL4 gl, float[] vp, int w, int h, Sky.State st, double pre) {
        brecherGl.update(gl, brecher);
        int kopie = unwetter.tiefe(gl, hdrFbo, w, h);
        gl.glBindFramebuffer(GL.GL_FRAMEBUFFER, hdrFbo);
        gl.glViewport(0, 0, w, h);
        double[][] L = licht(st, pre);
        com.dan.spray.gl.SprayGl.View v = new com.dan.spray.gl.SprayGl.View();
        v.viewProj = vp;
        v.eye = camera.eye.clone();
        v.forward = camera.fwd.clone();
        v.reversedZ = reversedZ;
        v.near = NEAR;
        v.far = FAR;
        v.fovyDeg = fovy();
        v.width = w;
        v.height = h;
        v.depthTexture = kopie;
        com.dan.spray.gl.SprayGl.Light l = new com.dan.spray.gl.SprayGl.Light();
        l.sunDir = L[0];
        l.sunColor = L[1];
        l.moonDir = L[2];
        l.moonColor = L[3];
        l.skyTop = L[4];
        l.skyHorizon = L[5];
        l.fogDistance = Wetter.fogDistance(st);
        gl.glDepthFunc(reversedZ ? GL.GL_GREATER : GL.GL_LESS);
        brecherGl.draw(gl, v, l, 1);
        gl.glDisable(GL.GL_BLEND);
        gl.glDepthMask(true);
        gl.glBindFramebuffer(GL.GL_FRAMEBUFFER, hdrColorFbo);
        gl.glViewport(0, 0, w, h);
        gl.glBindVertexArray(emptyVao);
    }

    /** Seit Phase 14: steht die Maske der See auf dem Pegel (für Standbilder)? */
    public boolean seeMaskeFertig() { return meer.maskeFertig(); }

    /** Seit Phase 14: eine Zeile für das HUD (Sturmflut, Abbrüche, Brecher, Maske der See). */
    public String sturmflutZeile() {
        return com.dan.timmendorf.scene.Sturmflut.zeile(wiring.sturmflutStunde) + " · " + abbruch.describe()
                + String.format(Locale.GERMAN, " · Brecher: %,d an %d Pfählen, %d Bündel in der Luft · See verbunden bis %.2f m", brecherZahl, pfaehle.size(),
                brecher.parcels().size(), meer.maskBis());
    }

    private com.dan.season.gl.SeasonGl.View seasonView(float[] vp, int w, int h) {
        com.dan.season.gl.SeasonGl.View sv = new com.dan.season.gl.SeasonGl.View();
        sv.viewProj = vp;
        sv.eye = camera.eye.clone();
        sv.reversedZ = reversedZ;
        sv.width = w;
        sv.height = h;
        sv.fovyDeg = fovy();
        return sv;
    }

    private com.dan.season.gl.SeasonGl.Light seasonLight(double[][] L, Sky.State st) {
        com.dan.season.gl.SeasonGl.Light sl = new com.dan.season.gl.SeasonGl.Light();
        sl.sunDir = L[0];
        sl.sunColor = L[1];
        sl.moonDir = L[2];
        sl.moonColor = L[3];
        sl.sky = L[4];
        sl.fogColor = L[5];
        sl.fogDistance = Wetter.fogDistance(st);
        return sl;
    }

    /** Seit Phase 17: die Ohrenquallen nach der See, mit dem Licht unter Wasser. */
    private void drawQuallen(GL4 gl, float[] vp, Sky.State st, double pre) {
        if (quallen.visibleCount() == 0) return;
        double[][] L = licht(st, pre);
        com.dan.jelly.gl.JellyGl.View v = new com.dan.jelly.gl.JellyGl.View();
        v.viewProj = vp;
        v.eye = camera.eye.clone();
        com.dan.jelly.gl.JellyGl.Light l = new com.dan.jelly.gl.JellyGl.Light();
        l.sunDir = L[0];
        l.sunColor = L[1];
        l.moonDir = L[2];
        l.moonColor = L[3];
        l.sky = L[4];
        l.fogColor = L[5];
        l.fogDistance = Wetter.fogDistance(st);
        gl.glEnable(GL.GL_DEPTH_TEST);
        gl.glDepthFunc(reversedZ ? GL.GL_GREATER : GL.GL_LESS);
        quallenGl.draw(gl, quallen, v, l);
        gl.glBindVertexArray(emptyVao);
    }

    /** Seit Phase 13: Eis auf der Bucht, wo der Grund flacher liegt als die Eisgrenze des Jahrs. */
    private void drawEis(GL4 gl, float[] vp, int w, int h, Sky.State st, double pre) {
        com.dan.season.Year.State js = wiring.jahr.state();
        com.dan.season.gl.IceGl.Input in = new com.dan.season.gl.IceGl.Input();
        in.bedTexture = lod.heightTexture();
        in.bedRect = new double[]{Terrain.X0, Terrain.Z0, Terrain.N * Terrain.CELL};
        in.bedScale = Terrain.HMAX - Terrain.HMIN;
        in.bedOffset = Terrain.HMIN;
        in.waterLevel = wiring.pegel;
        in.thickness = js.ice();
        in.iceDepth = js.iceDepth();
        in.snow = js.snowDepth() * js.snowCover();
        in.frost = js.frost();
        double wr = Math.toRadians(wiring.windFromDeg);
        in.windTo = new double[]{-Math.sin(wr), Math.cos(wr)};
        if (!com.dan.season.gl.IceGl.any(in)) return;
        gl.glDepthFunc(reversedZ ? GL.GL_GREATER : GL.GL_LESS);
        eisGl.draw(gl, seasonView(vp, w, h), seasonLight(licht(st, pre), st), in);
        gl.glBindFramebuffer(GL.GL_FRAMEBUFFER, hdrFbo);
        gl.glViewport(0, 0, w, h);
        gl.glBindVertexArray(emptyVao);
    }

    /**
     * Seit Phase 12: welche Leuchten dieses Bild rechnet. Alle 15 Bilder die wichtigsten festen (nach I/d² zum Auge), jedes
     * Bild dazu ein Licht je Bombe; alle 30 Bilder die Lichtpunkte aller brennenden Leuchten bis 4 000. Das Feuerwerk wird
     * hier, unter seiner Sperre, für die Karte vorbereitet (die Physik rechnet es im eigenen Faden).
     */
    private void lichterVorbereiten(GL4 gl, double pre) {
        lampK = 0;
        lichtZahl = 0;
        // das Feuerwerk für die Karte vorbereiten – auch ohne Leuchten (Befund Phase 12: ohne LampGl fehlte es ganz)
        com.dan.firework.Firework f = showFeuerwerk && showLichter ? wiring.firework : null;
        java.util.List<com.dan.firework.Firework.Light> bomben = java.util.List.of();
        if (f != null) {
            synchronized (f) {
                bomben = f.lights();
                if (fwGl.ready()) {
                    fwGl.waterY = wiring.pegel;
                    fwGl.waterTolerance = Math.max(0.5, wiring.seeHs * 1.2);
                    fwGl.prepare(f, camera.eye.clone());
                }
            }
        }
        if (!lampGl.ready() || !showLichter) return;
        com.dan.timmendorf.scene.Lichter li = wiring.lichter;
        com.dan.lamp.Lamps.Quality q = li.fest.quality;
        if (frame % 15 == 0) festAuswahl = li.an() ? li.fest.important(camera.eye[0], camera.eye[1], camera.eye[2], q.gpuLamps) : java.util.List.of();
        if (frame % 30 == 0 && kopfGl.ready()) {
            if (li.an()) kopfGl.prepare(li.fest, camera.eye.clone());
            else kopfGl.prepare(new com.dan.lamp.Lamps(), camera.eye.clone());
            kopfGl.upload(gl);
            kopfZahl = kopfGl.count();
        }
        com.dan.lamp.Lamps b = new com.dan.lamp.Lamps();
        b.quality = q;
        b.all().addAll(festAuswahl);
        for (com.dan.firework.Firework.Light l : bomben)
            if (l.cd() > 1) b.all().add(com.dan.lamp.Lamp.point(l.x(), l.y(), l.z(), l.cd(), l.r(), l.g(), l.b()));
        if (b.size() == 0) return;
        lampGl.prepare(b, camera.eye.clone());
        lampGl.upload(gl);
        lichtZahl = lampGl.count();
        lampK = (frameSky ? pre : exposure()) / Sky.CD_PER_UNIT;
    }

    /** Seit Phase 12: Leuchten an einen Shader mit {@code lamp.glsl} (Zahl, Puffer, Vorbelichtung, Reichweite). */
    private void lampenBinden(GL4 gl, Shader s) {
        if (lampK > 0 && lichtZahl > 0) lampGl.bind(gl, s.id());
        else s.set(gl, "uLampCount", 0);
        s.set(gl, "uLampK", (float) lampK);
        s.set(gl, "uLampRange", (float) quality.lampRange);
    }

    /**
     * Seit Phase 12, Durchgang „Lichter“: (1) die Leuchten und Bomben im Wasser (Tiefe → Ort; GGX mit der Rauigkeit der See aus
     * dem Wind), (2) das Feuerwerk – Spiegelung nur auf dem Wasser, dann Rauch, Schweife und Sterne mit der Tiefe der Szene,
     * (3) die Köpfe der Leuchten als Lichtpunkte.
     */
    private void drawLichter(GL4 gl, float[] vp, float[] inv, int w, int h, double[] sun) {
        if (!showLichter) return;
        double pxPerRad = h / (2 * Math.tan(Math.toRadians(fovy()) / 2));
        if (lampK > 0 && lichtZahl > 0 && showMeer) {
            gl.glBindFramebuffer(GL.GL_FRAMEBUFFER, hdrColorFbo);
            gl.glViewport(0, 0, w, h);
            gl.glDisable(GL.GL_DEPTH_TEST);
            gl.glEnable(GL.GL_BLEND);
            gl.glBlendFunc(GL.GL_ONE, GL.GL_ONE);
            common(gl, spiegelShader, vp, inv, sun);
            lod.bind(gl, spiegelShader);
            spiegelShader.set(gl, "uKorrOn", 0);
            bindTex(gl, 6, depthTex);
            spiegelShader.set(gl, "uDepth", 6);
            spiegelShader.set(gl, "uAlpha", (float) com.dan.lamp.Wet.seaAlpha(wiring.windSpeed));
            spiegelShader.set(gl, "uWaterTol", (float) Math.max(0.5, wiring.seeHs * 1.2));
            lampenBinden(gl, spiegelShader);
            spiegelShader.set(gl, "uLampRange", (float) (quality.lampRange * 4));
            gl.glBindVertexArray(emptyVao);
            gl.glDrawArrays(GL.GL_TRIANGLES, 0, 3);
            bindTex(gl, 6, 0);
            gl.glDisable(GL.GL_BLEND);
        }
        double k = (frameSky ? exposure() : exposure()) / Sky.CD_PER_UNIT;
        com.dan.firework.Firework f = showFeuerwerk ? wiring.firework : null;
        if (f != null && fwGl.ready()) {
            synchronized (f) { fwGl.upload(gl); }
            com.dan.firework.gl.FireworkGl.View fv = new com.dan.firework.gl.FireworkGl.View();
            fv.viewProj = vp;
            fv.invViewProj = inv;
            fv.eye = camera.eye.clone();
            fv.pxPerRad = pxPerRad;
            fv.preExposure = k;
            fv.depthTexture = depthTex;
            fv.reversedZ = reversedZ;
            fv.width = w;
            fv.height = h;
            if (showMeer) {
                gl.glBindFramebuffer(GL.GL_FRAMEBUFFER, hdrColorFbo);
                gl.glViewport(0, 0, w, h);
                fwGl.drawReflection(gl, fv);
            }
            gl.glBindFramebuffer(GL.GL_FRAMEBUFFER, hdrFbo);
            gl.glViewport(0, 0, w, h);
            gl.glDepthFunc(reversedZ ? GL.GL_GREATER : GL.GL_LESS);
            fv.depthTexture = 0;
            fwGl.draw(gl, fv);
        }
        if (kopfGl.ready() && kopfZahl > 0 && wiring.lichter.an()) {
            gl.glBindFramebuffer(GL.GL_FRAMEBUFFER, hdrFbo);
            gl.glViewport(0, 0, w, h);
            gl.glDepthFunc(reversedZ ? GL.GL_GREATER : GL.GL_LESS);
            com.dan.lamp.gl.LampGl.View lv = new com.dan.lamp.gl.LampGl.View();
            lv.viewProj = vp;
            lv.eye = camera.eye.clone();
            lv.pxPerRad = pxPerRad;
            lv.preExposure = k;
            kopfGl.drawHeads(gl, lv);
        }
        gl.glDisable(GL.GL_DEPTH_TEST);
        gl.glBindVertexArray(emptyVao);
    }

    /** Seit Phase 12: eine Zeile für das HUD (Lichter, Feuerwerk, Zuschauer). */
    public String abendZeile() {
        com.dan.firework.Firework f = wiring.firework;
        String fw = f == null ? "" : String.format(Locale.GERMAN, " · Feuerwerk %.0f s: %,d Sterne, %,d Rauchwolken (%d Lichter an die Karte)", wiring.feuerwerkZeit(),
                fwGl.stars(), fwGl.smokes(), lichtZahl);
        return wiring.lichter.describe() + String.format(Locale.GERMAN, " · %d mit Licht, %,d Lichtpunkte · Fenster %.0f %%", lichtZahl, kopfZahl,
                100 * com.dan.timmendorf.scene.Lichter.fenster(wiring.stunde())) + fw;
    }

    /** Seit Phase 10a: die Menschen; Licht wie die Boote (vorbelichtet). */
    private void drawMenschen(GL4 gl, float[] vp, double[] sun) {
        double[] sunC, sky;
        double fog;
        if (frameSky) {
            Sky.State st = wetter.state();
            double p = exposure();
            sunC = Wetter.scale(st.sunColor, p);
            sky = Wetter.scale(wetter.ambient(st, showClouds), p / Math.PI);
            fog = Wetter.fogDistance(st);
        } else {
            sunC = sunLight(clock.sun()[0]);
            sky = new double[]{0.12, 0.15, 0.2};
            fog = FOG_DISTANCE;
        }
        gl.glEnable(GL.GL_DEPTH_TEST);
        gl.glDepthMask(true);
        gl.glDepthFunc(reversedZ ? GL.GL_GREATER : GL.GL_LESS);
        gl.glDisable(GL.GL_BLEND);
        menschenGl.draw(gl, wiring.menschen.alle(), vp, camera.eye, sun, sunC, sky, fog, showClouds ? cloudShade : 1);
        gl.glBindVertexArray(emptyVao);
    }

    /** Seit Phase 8a: die Boote; Licht wie der Bewuchs (vorbelichtet). */
    private void drawBoote(GL4 gl, float[] vp, double[] sun) {
        double[] sunC, sky;
        double fog;
        if (frameSky) {
            Sky.State st = wetter.state();
            double p = exposure();
            sunC = Wetter.scale(st.sunColor, p / Math.PI);
            sky = Wetter.scale(wetter.ambient(st, showClouds), p / Math.PI);
            fog = Wetter.fogDistance(st);
        } else {
            sunC = Wetter.scale(sunLight(clock.sun()[0]), 1 / Math.PI);
            sky = new double[]{0.12, 0.15, 0.2};
            fog = FOG_DISTANCE;
        }
        gl.glEnable(GL.GL_DEPTH_TEST);
        gl.glDepthMask(true);
        gl.glDepthFunc(reversedZ ? GL.GL_GREATER : GL.GL_LESS);
        gl.glDisable(GL.GL_BLEND);
        booteGl.draw(gl, wiring.flotte, vp, camera.eye, this::wasser, physik.time(), sun, sunC, sky, fog, wiring.pegel);
        gl.glBindVertexArray(emptyVao);
    }

    /** Seit Phase 8a: Höhe des Wassers am Ort (m NHN): die See aus {@code com.dan.ocean}, ohne sie der Pegel. */
    public double wasser(double x, double z) {
        return meer.ok ? meer.ocean.heightAt(x, z) : wiring.pegel;
    }

    /** Seit Phase 8a: im Optimisten mitfahren (Schaustück „Mit dem Optimisten“, Taste O). */
    public void mitOptimist() {
        com.dan.sail.Sail.Boat b = wiring.flotte.opti;
        camera.startMitfahrt(() -> com.dan.timmendorf.scene.Flotte.imBoot(b, wasser(b.x, b.z)));
        hudMeldung("Mit dem Optimisten (O): Wende, Krängung, das Segel schlägt über · R oder F beendet");
    }

    /** Seit Phase 8a: im Begleitboot neben einem Boot (Taste B: Optimist, G: die Spitze der Regatta). */
    public void begleitboot(boolean regatta) {
        com.dan.timmendorf.scene.Flotte f = wiring.flotte;
        com.dan.sail.Sail.Boat b = regatta ? f.regattaSpitze() : f.opti;
        camera.startMitfahrt(() -> com.dan.timmendorf.scene.Flotte.begleitboot(b, wasser(b.x, b.z)));
        hudMeldung(regatta ? "Begleitboot der Regatta (G): Nr. " + b.label + " · R oder F beendet" : "Begleitboot des Optimisten (B) · R oder F beendet");
    }

    /** Seit Phase 5a: der Bewuchs; Licht wie die Bauten (vorbelichtet, Sonne als Bestrahlung/π, Himmel als Bestrahlung/π). */
    private void drawBewuchs(GL4 gl, float[] vp, double[] sun, double pre, boolean cloudsNow, double[] cr) {
        double[] sunC, sky;
        double fog;
        if (frameSky) {
            Sky.State st = wetter.state();
            double p = exposure();
            sunC = Wetter.scale(st.sunColor, p / Math.PI);
            sky = Wetter.scale(wetter.ambient(st, showClouds), p / Math.PI);
            fog = Wetter.fogDistance(st);
        } else {
            sunC = Wetter.scale(sunLight(clock.sun()[0]), 1 / Math.PI);
            sky = new double[]{0.12, 0.15, 0.2};
            fog = FOG_DISTANCE;
        }
        bewuchsGl.draw(gl, vp, camera.eye, reversedZ, quality, clock.date().getDayOfYear(), wiring.windSpeed, wiring.windFromDeg, clock.t() % 10_000,
                sun, sunC, sky, fog, cloudsNow ? wetter.cloudGl.shadowTexture() : 0, cr);
        gl.glBindVertexArray(emptyVao);
    }

    /**
     * Seit Phase 4: das Luftbild DOP20 2025 als Texturen (sRGB, mit Mip-Stufen): im Kern zu 1 m aus
     * {@code daten/kern/luftbild.rgbi} (ohne Infrarot), im Hauptraster zu 10 m aus {@code daten/gelaende/luftbild_haupt.rgb};
     * dazu die Klassen je Meter ({@code daten/kern/klasse.u8}, R8UI). Was fehlt, bleibt 0 (dann die Farben je Art).
     */
    private void luftbildTexturen(GL4 gl) {
        File d = Daten.ordner();
        int KN = Terrain.KN, N = Terrain.N;
        try {
            File f = new File(d, "kern/luftbild.rgbi");
            if (f.length() == (long) KN * KN * 4) {
                byte[] rgbi = java.nio.file.Files.readAllBytes(f.toPath());
                ByteBuffer rgb = Buffers.newDirectByteBuffer(KN * KN * 3);
                for (int i = 0; i < KN * KN; i++) rgb.put(rgbi[i * 4]).put(rgbi[i * 4 + 1]).put(rgbi[i * 4 + 2]);
                rgb.rewind();
                luftKernTex = rgbTexture(gl, KN, rgb);
            }
            File g = new File(d, "gelaende/luftbild_haupt.rgb");
            if (g.length() == (long) N * N * 3) luftHauptTex = rgbTexture(gl, N, ByteBuffer.wrap(java.nio.file.Files.readAllBytes(g.toPath())));
            File k = new File(d, "kern/klasse.u8");
            if (k.length() == (long) KN * KN) {
                int[] tex = new int[1];
                gl.glGenTextures(1, tex, 0);
                gl.glBindTexture(GL.GL_TEXTURE_2D, tex[0]);
                gl.glTexStorage2D(GL.GL_TEXTURE_2D, 1, GL4.GL_R8UI, KN, KN);
                gl.glPixelStorei(GL.GL_UNPACK_ALIGNMENT, 1);
                gl.glTexSubImage2D(GL.GL_TEXTURE_2D, 0, 0, 0, KN, KN, GL4.GL_RED_INTEGER, GL.GL_UNSIGNED_BYTE, ByteBuffer.wrap(java.nio.file.Files.readAllBytes(k.toPath())));
                gl.glPixelStorei(GL.GL_UNPACK_ALIGNMENT, 4);
                gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_NEAREST);
                gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_NEAREST);
                gl.glBindTexture(GL.GL_TEXTURE_2D, 0);
                klasseTex = tex[0];
            }
        } catch (java.io.IOException | OutOfMemoryError e) {
            System.err.println("Timmendorf: Luftbild nicht geladen: " + e);
        }
        System.out.println("Timmendorf: Luftbild " + (luftKernTex != 0 ? "im Kern zu 1 m" : "im Kern fehlt") + ", " + (luftHauptTex != 0 ? "im Hauptraster zu 10 m" : "im Hauptraster fehlt")
                + ", Klassen " + (klasseTex != 0 ? "je Meter" : "fehlen"));
    }

    private static int rgbTexture(GL4 gl, int n, ByteBuffer rgb) {
        int levels = 1 + (int) Math.floor(Math.log(n) / Math.log(2));
        int[] tex = new int[1];
        gl.glGenTextures(1, tex, 0);
        gl.glBindTexture(GL.GL_TEXTURE_2D, tex[0]);
        gl.glTexStorage2D(GL.GL_TEXTURE_2D, levels, GL.GL_SRGB8_ALPHA8, n, n);
        gl.glPixelStorei(GL.GL_UNPACK_ALIGNMENT, 1);
        gl.glTexSubImage2D(GL.GL_TEXTURE_2D, 0, 0, 0, n, n, GL.GL_RGB, GL.GL_UNSIGNED_BYTE, rgb);
        gl.glPixelStorei(GL.GL_UNPACK_ALIGNMENT, 4);
        gl.glGenerateMipmap(GL.GL_TEXTURE_2D);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR_MIPMAP_LINEAR);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR);
        gl.glTexParameterf(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAX_ANISOTROPY_EXT, 8f);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE);
        gl.glBindTexture(GL.GL_TEXTURE_2D, 0);
        return tex[0];
    }

    /** Sonnenlicht des Platzhalter-Himmels (wie sunLight in common.glsl), linear (aus Rungholt). */
    public static double[] sunLight(double elevDeg) {
        double e = Math.sin(Math.toRadians(elevDeg));
        double vis = smooth(-0.015, 0.01, e), t = smooth(0.02, 0.35, e), f = 2.6 * vis * smooth(-0.02, 0.12, e);
        return new double[]{f, (0.55 + 0.40 * t) * f, (0.28 + 0.60 * t) * f};
    }

    static double smooth(double a, double b, double x) {
        double t = Math.max(0, Math.min(1, (x - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }

    private void bindMarks(GL4 gl, Shader s) {
        s.set(gl, "uMarks", showMarks ? 1 : 0);
        rect(gl, s, "uMarkHaupt", Site.FERNFELD);
        rect(gl, s, "uMarkKern", Site.KERN);
        float[] pts = points();
        s.vec4Array(gl, "uPoints", pts);
        s.set(gl, "uPointCount", pts.length / 4);
        s.set(gl, "uGridOff", (float) Math.floorMod((long) Site.E0, 5000L), (float) Math.floorMod(-(long) Site.N0, 5000L));
    }

    private static void rect(GL4 gl, Shader s, String u, Site.Fenster f) {
        double[] r = f.scene();
        s.set(gl, u, (float) r[0], (float) r[1], (float) r[2], (float) r[3]);
    }

    /** Die Orte als vec4 (x, z, Halbmesser m, Art) für marken.glsl. */
    static float[] points() {
        Site.Ort[] o = Site.ORTE;
        float[] p = new float[Math.min(16, o.length) * 4];
        for (int i = 0; i < p.length / 4; i++) {
            p[i * 4] = (float) o[i].x();
            p[i * 4 + 1] = (float) o[i].z();
            p[i * 4 + 2] = o[i].art() == Site.Art.SEEZEICHEN ? 9f : 22f;
            p[i * 4 + 3] = o[i].art().ordinal();
        }
        return p;
    }

    private void common(GL4 gl, Shader s, float[] vp, float[] inv, double[] sun) {
        s.use(gl);
        // seit Phase 4: mit com.dan.sky Himmel aus dem Rundumbild, Sonne und Mond vorbelichtet, Dunst aus der Sicht
        boolean sm = frameSky && s != skyShader;
        s.set(gl, "uSkyMode", sm ? 1 : 0);
        if (sm) {
            Sky.State st = wetter.state();
            double pre = exposure();
            gl.glActiveTexture(GL.GL_TEXTURE7);
            gl.glBindTexture(GL.GL_TEXTURE_2D, wetter.panoTex);
            gl.glActiveTexture(GL.GL_TEXTURE0);
            s.set(gl, "uPano", 7);
            s.set(gl, "uSunColor", Wetter.scale(st.sunColor, pre));
            s.set(gl, "uMoonColor", Wetter.scale(st.moonColor, pre));
            s.set(gl, "uMoonDir", st.moonDir);
            s.set(gl, "uFogDist", (float) Wetter.fogDistance(st));
            s.set(gl, "uPre", (float) pre);
            s.set(gl, "uSkyAmb", Wetter.scale(wetter.ambient(st, showClouds), pre / Math.PI));
        }
        s.mat(gl, "uVP", vp);
        s.mat(gl, "uInvVP", inv);
        s.set(gl, "uCam", camera.eye);
        s.set(gl, "uSun", sun);
        s.set(gl, "uTime", (float) (clock.t() % 10_000));
        s.set(gl, "uLevel", (float) wiring.pegel);
        s.set(gl, "uReversed", reversedZ ? 1 : 0);
    }

    private void resizeTargets(GL4 gl, int w, int h) {
        Gpu.deleteFbos(gl, hdrFbo, hdrColorFbo);
        Gpu.deleteTextures(gl, hdrTex, depthTex);
        hdrTex = Gpu.texture(gl, w, h, GL.GL_RGBA16F, false);
        depthTex = Gpu.texture(gl, w, h, GL4.GL_DEPTH_COMPONENT32F, false);
        gl.glBindTexture(GL.GL_TEXTURE_2D, depthTex);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_NEAREST);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_NEAREST);
        gl.glBindTexture(GL.GL_TEXTURE_2D, 0);
        hdrFbo = Gpu.fbo(gl, hdrTex, depthTex);
        hdrColorFbo = Gpu.fbo(gl, hdrTex, 0);
        hdrW = w;
        hdrH = h;
    }

    // ------------------------------------------------------------------ Zeitmessung (Tempo)

    private void mark(GL4 gl, int i) { gl.glQueryCounter(stamp[frame & 1][i], GL4.GL_TIMESTAMP); }

    /** Liest die Zeitstempel des vorigen Bilds, sobald sie da sind (kein Warten im Bildlauf). */
    private void readMarks(GL4 gl) {
        if (frame < 2) return;
        int o = (frame - 1) & 1;
        int[] av = new int[1];
        gl.glGetQueryObjectiv(stamp[o][MARKS - 1], GL4.GL_QUERY_RESULT_AVAILABLE, av, 0);
        if (av[0] == 0) return;
        long[] t = new long[MARKS];
        for (int i = 0; i < MARKS; i++) gl.glGetQueryObjecti64v(stamp[o][i], GL4.GL_QUERY_RESULT, t, i);
        for (int i = 0; i < MARKS - 1; i++) passMs[i] = passMs[i] * 0.9 + Math.max(0, t[i + 1] - t[i]) / 1e6 * 0.1;
        gpuMs = gpuMs * 0.9 + Math.max(0, t[MARKS - 1] - t[0]) / 1e6 * 0.1;
    }

    /** „Himmel 0,10 · Gelände 0,60 · …“ in ms (gleitendes Mittel). */
    public String passTimes() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < passMs.length; i++)
            sb.append(i > 0 ? " · " : "").append(PASSES[i]).append(' ').append(String.format(Locale.GERMAN, "%.2f", passMs[i]));
        return sb.toString();
    }

    /** Die n teuersten Durchgänge: „Gelände 1,20 · Meer 0,40“. */
    public String topPasses(int n) {
        Integer[] idx = new Integer[passMs.length];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (a, b) -> Double.compare(passMs[b], passMs[a]));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(n, idx.length); i++)
            sb.append(i > 0 ? " · " : "").append(PASSES[idx[i]]).append(' ').append(String.format(Locale.GERMAN, "%.2f", passMs[idx[i]]));
        return sb.toString();
    }

    private void stats(long now, int w, int h) {
        statsFrames++;
        if (now - statsNanos < 500_000_000L) return;
        fps = statsFrames / ((now - statsNanos) / 1e9);
        statsFrames = 0;
        statsNanos = now;
        statusListener.accept(statusLine(w, h));
    }

    /** Auflösung nach der Zeit der Grafikkarte regeln (siehe {@link #autoScale}). */
    private void regulateScale(long now) {
        if (!autoScale || frame < 60 || now - scaleNanos < 1_500_000_000L) return;
        scaleNanos = now;
        double budget = 1000 / Math.max(10, targetFps), top = quality.renderScale, s = renderScale;
        double want = s * Math.sqrt(budget * 0.9 / Math.max(0.1, gpuMs));
        double next = s;
        if (gpuMs > budget * 1.05) next = Math.max(MIN_SCALE, Math.max(want, s - 0.15));
        else if (gpuMs < budget * 0.7 && s < top) next = Math.min(top, Math.min(want, s + 0.05));
        next = Math.round(next * 20) / 20.0;
        if (Math.abs(next - s) >= 0.045) { renderScale = next; scaleSteps++; }
    }

    /** Wie oft die Regelung die Auflösung geändert hat. */
    public int scaleSteps() { return scaleSteps; }

    /** Für Prüfungen: die Regelung mit vorgegebener Grafikkartenzeit und Bildnummer. */
    public void regulateForTest(double gpuMsNow, long nowNanos) {
        gpuMs = gpuMsNow;
        frame = Math.max(frame, 60);
        regulateScale(nowNanos);
    }

    public String statusLine(int w, int h) {
        String s = String.format(Locale.GERMAN, "%s · %.0f Bilder/s · Grafikkarte %.2f ms · Prozessor %.2f ms · %d×%d (%s%s) · %s",
                clock.label(), fps, gpuMs, cpuMs, w, h, quality.label, autoScale ? String.format(Locale.GERMAN, ", %.0f %%", renderScale * 100) : "", glInfo);
        long nowMs = System.currentTimeMillis();
        if (nowMs < meldungUntil) s = meldung + " · " + s;
        if (nowMs < photoMsgUntil) s = photoMsg + " · " + s;
        return s;
    }

    // ------------------------------------------------------------------ HUD

    BufferedImage paintHud() {
        String[] lines = hudFull ? hudLines() : hudShort();
        Font font = new Font(Font.MONOSPACED, Font.PLAIN, 12);
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D pg = probe.createGraphics();
        java.awt.FontMetrics fm = pg.getFontMetrics(font);
        int tw = 0;
        for (String l : lines) tw = Math.max(tw, fm.stringWidth(l.startsWith("!") ? l.substring(1) : l));
        pg.dispose();
        int w = Math.max(200, tw + 22), lh = 17, h = 16 + lines.length * lh;
        BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = im.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(14, 20, 22, 190));
        g.fillRoundRect(0, 0, w - 1, h - 1, 10, 10);
        g.setColor(new Color(127, 182, 198, 225));   // Ostsee, wie das Werkbuch
        g.setStroke(new BasicStroke(2f));
        g.drawLine(8, 1, 46, 1);
        g.setColor(new Color(217, 199, 158, 225));   // Sand
        g.drawLine(48, 1, 64, 1);
        g.setColor(new Color(238, 240, 234, 225));   // Segeltuch
        g.drawLine(66, 1, 80, 1);
        g.setFont(font);
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            boolean warn = l.startsWith("!");
            if (warn) l = l.substring(1);
            g.setColor(i == 0 ? new Color(233, 236, 234) : warn ? new Color(224, 163, 63) : new Color(186, 196, 196));
            g.drawString(l, 10, 18 + i * lh);
        }
        g.dispose();
        return im;
    }

    /** Das kurze HUD: Tempo, Zeiten der Durchgänge, Uhr und Sonne, Kamera. */
    String[] hudShort() {
        java.util.List<String> l = new java.util.ArrayList<>();
        String q = autoScale ? String.format(Locale.GERMAN, "%s %.0f %%", quality.label, renderScale * 100) : quality.label;
        l.add(String.format(Locale.GERMAN, "%.0f Bilder/s · Grafikkarte %.1f ms · Prozessor %.1f ms · %s %d×%d", fps, gpuMs, cpuMs, q, hdrW, hdrH));
        l.add(passTimes());
        double[] sp = clock.sun();
        l.add(String.format(Locale.GERMAN, "%s · Sonne %.0f° aus %03.0f° · Pegel %+.2f m NHN", clock.label(), sp[0], sp[1], wiring.pegel));
        l.add(cameraLine() + " · H: volles HUD, F1: Tasten");
        if (System.currentTimeMillis() < meldungUntil) l.add(meldung);
        if (error != null) l.add("!" + error.split("\n")[0]);
        return l.toArray(new String[0]);
    }

    /** „Kreisen · Auge 3,9 m über NHN, 1,7 m über Grund · Blick 050° · Kern · 4 m bis Maritim Seebrücke“ */
    public String cameraLine() {
        return String.format(Locale.GERMAN, "%s · Auge %.1f m über NHN, %.1f m über Grund · Blick %03.0f° · %s · %s", camera.modeName(), camera.eye[1],
                camera.eye[1] - Math.max(camera.groundLevel, wiring.pegel), camera.heading(), Buehne.zone(camera.eye[0], camera.eye[2]),
                Buehne.naechsterOrt(camera.eye[0], camera.eye[2]));
    }

    /** Das volle HUD (H): alles, was die Phase weiß. */
    public String[] hudLines() {
        double[] sp = clock.sun();
        ComputeProbe.Result c = compute;
        double[] ll = Site.latLon(camera.eye[0], camera.eye[2]);
        double[] utm = Site.toUtm(camera.eye[0], camera.eye[2]);
        double kimm = Site.horizon(camera.eye[1] - wiring.pegel);
        return new String[]{
                String.format(Locale.GERMAN, "%.0f Bilder/s   Grafikkarte %.2f ms   Prozessor %.2f ms   Budget %.1f ms", fps, gpuMs, cpuMs, quality.budgetMs()),
                passTimes(),
                (c == null ? "!Compute-Test läuft …" : (c.ok() ? "" : "!") + c.line()),
                String.format(Locale.GERMAN, "%s · Sonne %.1f° / %03.0f° · Belichtung %.3g%s", clock.label(), sp[0], sp[1], exposure(),
                        wetter.skyOk ? (bildMessung ? " (Auge, im Bild gemessen)" : " (Auge, aus dem Himmel)") : " (Platzhalter-Himmel)"),
                wetter.describe(showClouds, cloudShade)[0],
                wetter.describe(showClouds, cloudShade)[1],
                wiring.wetterZeile(),
                meer.describe(),
                (bautenGl.ok ? "" : "!") + "Bauten: " + bauten.describe() + " · " + bautenGl.info,
                (bewuchs.baeume.isEmpty() ? "!" : "") + "Bewuchs: " + bewuchs.describe() + " · " + bewuchsGl.info,
                bauwerkeInfo,
                (koerbeGl.ok() ? "" : "!") + (koerbeDa ? koerbeGl.describe() : String.format(Locale.GERMAN, "Strandkörbe: %,d (nicht in der Saison)", koerbe.size())),
                (sandGl.ok ? "" : "!") + sand.zeile(camera.eye[0], camera.eye[2]),
                (strandGl.ready() ? "" : "!") + (showStrand ? strand.describe() : "Strand aus dem Asset: aus"),
                wiring.leuchtfeuer.describe() + String.format(Locale.GERMAN, " · %d Lichtpunkte", feuerZahl) + (beaconGl.ready() ? "" : " · !ohne BeaconGl"),
                (cliffGl.ready() ? "" : "!") + String.format(Locale.GERMAN, "Brodtener Ufer (cliff, Geschiebemergel): Wand %.1f %%, Geröll %.1f %% der Karte, Oberkante bis %.1f m%s",
                        brodtenKarte.wallShare * 100, brodtenKarte.talusShare * 100, brodten.strata.topMax, showKliff ? "" : " · aus"),
                String.format(Locale.GERMAN, "Luftbild: Kern %s, Hauptraster %s, Klassen %s", luftKernTex != 0 ? "1 m" : "fehlt", luftHauptTex != 0 ? "10 m" : "fehlt", klasseTex != 0 ? "je Meter" : "fehlen"),
                wiring.segelZeile(),
                wiring.hullenZeile(camera.eye[0], camera.eye[2]),
                abendZeile() + (lampGl.ready() ? "" : " · !ohne LampGl") + (fwGl.ready() ? "" : " · !ohne FireworkGl"),
                unwetter.describe(camera.eye)[0],
                unwetter.describe(camera.eye)[1],
                unwetter.describe(camera.eye)[2],
                wiring.jahr.describe() + (eisOk ? "" : " · !ohne Eis") + (schneeOk ? "" : " · !ohne Schneefall"),
                sturmflutZeile() + (brecherOk ? "" : " · !ohne Brecher"),
                leben.describe() + " · " + lebenGl.describe(),
                quallen.describe() + " · " + (quallenOk ? quallenGl.info() : "!ohne JellyGl"),
                klang.describe(),
                regisseur.regie.running() ? regisseur.zeile() : "Regie: " + regisseur.regie.describe(),
                wiring.flotte.zeile(),
                wiring.flotte.regatta.describe() + (wiring.flotte.letzte().isEmpty() ? "" : " · zuletzt " + wiring.flotte.letzte()),
                booteGl.info,
                wiring.menschen == null ? "Menschen: noch nicht" : wiring.menschen.describe() + " · " + menschenGl.info,
                wiring.verkehr == null ? "Verkehr: noch nicht" : wiring.verkehr.describe(wiring.stunde() * 3600) + " · " + wiring.verkehrQuelle,
                physik.describe(),
                (terrain.measured() ? "" : "!") + "Gelände: " + terrain.describe(),
                String.format(Locale.GERMAN, "Gelände %d Knoten, %,d Punkte · fein bis %d m, im Kern %d m · Grund hier %.1f m über NHN · %s",
                        lod.nodes, lod.vertices, Quality.spacing(lod.finest), Quality.spacing(lod.finestKern), camera.groundLevel,
                        Terrain.ART[terrain.artAt(camera.eye[0], camera.eye[2])]),
                cameraLine(),
                String.format(Locale.GERMAN, "UTM 32 N  E %.0f  N %.0f · %s · Kimm %.1f km", utm[0], utm[1], Site.dms(ll[0], ll[1]), kimm / 1000),
                "Renderer.jar: " + Bibliothek.kurz(),
                "Neu für Timmendorf: " + Bibliothek.neuStand(),
                String.format(Locale.GERMAN, "%s · %d×%d · %s", quality.label, hdrW, hdrH, reversedZ ? "umgekehrte Tiefe" : "klassische Tiefe"),
                glInfo};
    }

    // ------------------------------------------------------------------ Bilder

    private void takePhoto(GL4 gl, int w, int h) {
        try {
            BufferedImage img = readDefault(gl, w, h);
            File dir = photoDir != null ? photoDir : new File("docs/status");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("Ordner " + dir + " lässt sich nicht anlegen");
            String name = photoName != null ? photoName : "Timmendorf_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            photoName = null;
            File f = new File(dir, name + ".png");
            javax.imageio.ImageIO.write(img, "png", f);
            photoMsg = "Foto gespeichert: " + f.getPath();
            System.out.println(photoMsg);
        } catch (Exception e) {
            photoMsg = "Foto fehlgeschlagen: " + e.getMessage();
            System.err.println(photoMsg);
        }
        photoMsgUntil = System.currentTimeMillis() + 5000;
    }

    /** Liest das fertige Fensterbild (nach Nachbearbeitung und HUD). */
    BufferedImage readDefault(GL4 gl, int w, int h) {
        ByteBuffer bb = Buffers.newDirectByteBuffer(w * h * 4);
        gl.glBindFramebuffer(GL.GL_READ_FRAMEBUFFER, defaultFbo);
        gl.glReadBuffer(defaultFbo == 0 ? GL.GL_BACK : GL.GL_COLOR_ATTACHMENT0);
        gl.glPixelStorei(GL.GL_PACK_ALIGNMENT, 1);
        gl.glReadPixels(0, 0, w, h, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, bb);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            int o = (h - 1 - y) * w * 4;
            for (int x = 0; x < w; x++) {
                int i = o + x * 4;
                row[x] = ((bb.get(i) & 255) << 16) | ((bb.get(i + 1) & 255) << 8) | (bb.get(i + 2) & 255);
            }
            img.setRGB(0, y, w, 1, row, 0, w);
        }
        return img;
    }

    /** Für Werkzeuge: das letzte Bild als Bild (im GL-Faden aufrufen). */
    public BufferedImage grab(GLAutoDrawable d) {
        GL4 gl = d.getGL().getGL4();
        defaultFbo = d.getContext().getDefaultDrawFramebuffer();
        return readDefault(gl, d.getSurfaceWidth(), d.getSurfaceHeight());
    }

    // ------------------------------------------------------------------ Ende

    private void fail(String msg) {
        error = msg;
        System.err.println(msg);
        errorListener.accept(msg);
    }

    @Override
    public void dispose(GLAutoDrawable d) {
        physik.stop();
        klang.dispose();   // seit Phase 15
        regisseur.dispose(d.getGL().getGL4());   // seit Phase 16
        GL4 gl = d.getGL().getGL4();
        for (Shader s : all) s.dispose(gl);
        hud.dispose(gl);
        wetter.dispose(gl);
        meer.dispose(gl);
        quallenGl.dispose(gl);
        bautenGl.dispose(gl);
        koerbeGl.dispose(gl);   // seit Phase 20
        sandGl.dispose(gl);
        strandGl.dispose(gl);   // seit Phase 21
        menschenGl.dispose(gl);
        if (cliffGl.ready()) cliffGl.dispose(gl);
        bewuchsGl.dispose(gl);
        booteGl.dispose(gl);
        belichtung.dispose(gl);
        Gpu.deleteTextures(gl, luftKernTex, luftHauptTex, klasseTex);
        lod.dispose(gl);
        unwetter.dispose(gl);   // seit Phase 13
        if (brecherOk) brecherGl.dispose(gl);
        Gpu.deleteTextures(gl, korrTex);
        lebenGl.dispose(gl);
        Gpu.deleteFbos(gl, hdrFbo, hdrColorFbo);
        Gpu.deleteTextures(gl, hdrTex, depthTex);
        if (emptyVao != 0) gl.glDeleteVertexArrays(1, new int[]{emptyVao}, 0);
        for (int[] s : stamp) if (s[0] != 0) gl.glDeleteQueries(MARKS, s, 0);
        ready = false;
    }
}
