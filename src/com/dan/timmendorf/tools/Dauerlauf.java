package com.dan.timmendorf.tools;

import com.dan.store.Ablage;
import com.dan.store.Felder;
import com.dan.timmendorf.camera.Ansichten;
import com.dan.timmendorf.data.DateiSpeicher;
import com.dan.timmendorf.data.Speicher;
import com.dan.timmendorf.data.Zustand;
import com.dan.timmendorf.gl.Quality;
import com.dan.timmendorf.gl.Renderer;
import com.dan.timmendorf.scene.Regie;
import com.dan.timmendorf.scene.SceneClock;
import com.dan.timmendorf.wiring.Wetterregie;
import com.jogamp.opengl.GLCapabilities;
import com.jogamp.opengl.GLDrawableFactory;
import com.jogamp.opengl.GLOffscreenAutoDrawable;
import com.jogamp.opengl.GLProfile;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Phase 19: der Dauerlauf (nach Haithabu und Lighthouse). Die ganze Szene läuft ohne Fenster so lange wie angegeben
 * (Vorgabe 30 Minuten) mit Physik im eigenen Faden durch Tage, Nächte und Monate: alle Ansichten, die fünf Regiebücher
 * (an zufälliger Stelle), Feuerwerk, Sturmflut, Blitze, die Lagen der Wetterregie, Wind von Hand, Regatta mit neuem
 * Start, Mitfahrt im Optimisten und im Begleitboot, Kamerafahrten, Monatssprünge, Zeitraffer, alle vier
 * Qualitätsstufen mit der Regelung der Auflösung, und jede Minute der ganze Zustand hin und zurück samt Sicherung in eine
 * Dateiablage. Gemessen werden Bilder je Sekunde, das schlechteste Bild, der Speicher nach dem Aufräumen und jede
 * Ausnahme in irgendeinem Faden.
 * <p>
 * Bestanden heißt: keine Ausnahme, kein Grafikfehler, der Speicher nach der fünften Minute nicht mehr als 25 %
 * gewachsen (plus 64 MB), kein Bild über 2 s (die zwei Sekunden nach einem Stufenwechsel, einem Sprung der Uhr oder dem
 * Start eines Regiebuchs zählen getrennt und dürfen bis 8 s brauchen), auf „Mittel“ im Mittel mindestens 30 Bilder/s
 * (F32) und am Ende ist der Zustand noch herstellbar. Letzte Zeile: „Dauerlauf bestanden“ oder „Dauerlauf NICHT
 * bestanden“ mit dem Grund. Fotos und Blätter der Regiebücher gehen in einen Ordner auf Zeit.
 * <pre>
 * Dauerlauf [Minuten] [Breite] [Datei]
 * </pre>
 */
public final class Dauerlauf {
    private Dauerlauf() { }

    static final List<String> FEHLER = new ArrayList<>();

    public static void main(String[] a) throws Exception {
        double minuten = a.length > 0 ? Double.parseDouble(a[0].replace(',', '.')) : 30;
        int width = a.length > 1 ? Integer.parseInt(a[1]) : 1600;
        File out = new File(a.length > 2 ? a[2] : "docs/status/Dauerlauf_Phase" + Renderer.PHASE + ".txt");
        if (out.getAbsoluteFile().getParentFile() != null) out.getAbsoluteFile().getParentFile().mkdirs();
        int height = width * 9 / 16;
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> { synchronized (FEHLER) { FEHLER.add("Faden " + t.getName() + ": " + e); } e.printStackTrace(); });
        PrintWriter pw = new PrintWriter(Files.newBufferedWriter(out.toPath(), StandardCharsets.UTF_8));
        java.util.function.Consumer<String> say = s -> { System.out.println(s); pw.println(s); pw.flush(); };
        say.accept(String.format(Locale.GERMAN, "Timmendorf · Dauerlauf · %s · %.0f Minuten · %d×%d · %s", Renderer.VERSION, minuten, width, height,
                java.time.LocalDateTime.now().withNano(0)));
        say.accept("Rechner: " + System.getProperty("os.name") + " · Java " + System.getProperty("java.version") + " · " + Runtime.getRuntime().availableProcessors()
                + " Kerne · Xmx " + Runtime.getRuntime().maxMemory() / 1048576 + " MB");

        GLProfile prof = GLProfile.get(GLProfile.GL4);
        GLCapabilities caps = new GLCapabilities(prof);
        caps.setOnscreen(false);
        caps.setFBO(true);
        caps.setDepthBits(24);
        GLOffscreenAutoDrawable d = GLDrawableFactory.getFactory(prof).createOffscreenAutoDrawable(null, caps, null, width, height);
        d.setAutoSwapBufferMode(false);
        SceneClock clock = new SceneClock();
        clock.lapse = 60;
        Renderer r = new Renderer(clock);
        File fotos = Files.createTempDirectory("timmendorf-dauerlauf-fotos").toFile();
        r.photoDir = fotos;
        r.showHud = true;
        r.autoScale = true;
        r.targetFps = 60;
        r.setQuality(Quality.MITTEL);
        d.addGLEventListener(r);
        d.display();
        if (r.error() != null) { say.accept("FEHLER Grafik: " + r.error()); say.accept("Dauerlauf NICHT bestanden"); pw.close(); System.exit(2); }
        say.accept("Grafik: " + r.glInfo());
        List<Regie.Book> buecher = Regie.all();
        Quality[] qs = Quality.values();
        Random rnd = new Random(2026);
        File daten = Files.createTempDirectory("timmendorf-dauerlauf").toFile();
        Speicher ds = new DateiSpeicher(daten);
        long sid = ds.sitzungBeginnen(Renderer.VERSION, r.glInfo(), r.quality().name());
        String[] fahrten = {"seebruecke", "promenade", "drohne", "moewe", "weg"};

        long start = System.nanoTime(), ende = start + (long) (minuten * 60e9), lastMinute = start, lastAct = start;
        long frames = 0, switchUntil = 0, heap5 = -1, heapEnd = -1;
        // seit Phase 19b: jeder Sprung (eigene Handlung, Schnitt oder Foto des Regiebuchs, Zustand gelesen) gibt 3 s Nachsicht;
        // langsame Bilder außerhalb davon werden mit ihrer Lage aufgeschrieben, damit sich die Ursache finden lässt
        final long NACHSICHT = 3_000_000_000L;
        int lastEv = r.regisseur.ereignisse, letzteTat = -1;
        long letzteTatZeit = start;
        List<String> langsam = new ArrayList<>();
        int nLangsam = 0;
        double worst = 0, worstAll = 0, worstSwitch = 0, sumFpsMittel = 0;
        int nMittel = 0, minute = 0, aktionen = 0, buchStarts = 0, feuerwerke = 0, stufen = 0, zustaende = 0, blitze = 0, sturmfluten = 0, fahrtenN = 0;
        while (System.nanoTime() < ende) {
            long t0 = System.nanoTime();
            d.display();
            long t1 = System.nanoTime();
            double ms = (t1 - t0) / 1e6;
            frames++;
            int ev = r.regisseur.ereignisse;
            if (ev != lastEv) { lastEv = ev; switchUntil = Math.max(switchUntil, t1 + NACHSICHT); }
            if (frames > 30 && (t1 < switchUntil || t0 < switchUntil)) worstSwitch = Math.max(worstSwitch, ms);
            else if (frames > 30) {
                worst = Math.max(worst, ms);
                worstAll = Math.max(worstAll, ms);
                if (ms > 1000) {
                    nLangsam++;
                    if (langsam.size() < 30) langsam.add(String.format(Locale.GERMAN, "  %.0f ms · Minute %d · %s · %s · letzte Handlung %d vor %.1f s · %s",
                            ms, minute + 1, r.quality().label, clock.label(), letzteTat, (t1 - letzteTatZeit) / 1e9, r.regisseur.zeile()));
                }
            }
            if (r.error() != null) { synchronized (FEHLER) { FEHLER.add("Grafik: " + r.error()); } break; }
            // alle 12 s etwas tun
            if (t1 - lastAct > 12_000_000_000L) {
                lastAct = t1;
                int k = aktionen++ % 15;
                Runnable tat = switch (k) {
                    case 0, 6, 12 -> () -> r.placeView(Ansichten.ALLE.get(rnd.nextInt(Ansichten.ALLE.size())));
                    case 1 -> () -> {
                        Regie.Book b = buecher.get(rnd.nextInt(buecher.size()));
                        r.regisseur.start(b);
                    };
                    case 2 -> () -> { r.zumFeuerwerk(); r.wiring.feuerwerk(); };
                    case 3 -> () -> r.unwetter.blitzVonHand(r.camera.eye.clone(), r.camera.fwd.clone(), rnd.nextBoolean());
                    case 4 -> () -> r.setQuality(qs[rnd.nextInt(qs.length)]);
                    case 5 -> () -> r.wiring.regie.lage(Wetterregie.Lage.values()[rnd.nextInt(Wetterregie.Lage.values().length)]);
                    case 7 -> () -> { r.wiring.windSpeed = 1 + rnd.nextDouble() * 14; r.wiring.windFromDeg = rnd.nextDouble() * 360; };
                    case 8 -> () -> { clock.set(LocalDate.of(2026, 1 + rnd.nextInt(12), 1 + rnd.nextInt(28)), rnd.nextDouble() * 24); clock.lapse = new double[]{1, 60, 600, 3600}[rnd.nextInt(4)]; };
                    case 9 -> () -> r.zurSturmflut(rnd.nextBoolean());
                    case 10 -> () -> { if (rnd.nextBoolean()) r.mitOptimist(); else r.begleitboot(rnd.nextBoolean()); };
                    case 11 -> () -> r.wiring.flotte.neuerStart();
                    case 13 -> () -> r.regisseur.fahrtJetzt(fahrten[rnd.nextInt(fahrten.length)]);
                    default -> () -> { r.regisseur.stop(); r.camera.home(); };
                };
                r.imBild(tat);
                letzteTat = k;
                letzteTatZeit = t1;
                switchUntil = Math.max(switchUntil, System.nanoTime() + NACHSICHT);
                switch (k) {
                    case 1 -> buchStarts++;
                    case 2 -> feuerwerke++;
                    case 3 -> blitze++;
                    case 4 -> stufen++;
                    case 9 -> sturmfluten++;
                    case 13 -> fahrtenN++;
                    default -> { }
                }
            }
            // jede Minute: Zustand hin und zurück, Sicherung, Speicher, Zeile
            if (t1 - lastMinute > 60_000_000_000L) {
                lastMinute = t1;
                minute++;
                LinkedHashMap<String, String> z = Zustand.erfassen(r);
                String text = Felder.alsText(z, "Dauerlauf");
                final List<String>[] fehl = new List[1];
                r.imBild(() -> fehl[0] = Zustand.anwenden(r, Felder.ausText(text)));
                d.display();
                switchUntil = Math.max(switchUntil, System.nanoTime() + NACHSICHT);
                if (fehl[0] != null && !fehl[0].isEmpty()) synchronized (FEHLER) { FEHLER.add("Minute " + minute + ": Zustand nicht ganz hergestellt: " + fehl[0]); }
                ds.sitzungSichern(sid, new Ablage.Sicherung(text, Zustand.spalten(z)));
                zustaende++;
                System.gc();
                Thread.sleep(50);
                long heap = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1048576;
                if (minute == 5) heap5 = heap;
                heapEnd = heap;
                double fps = r.fps();
                if (r.quality() == Quality.MITTEL) { sumFpsMittel += fps; nMittel++; }
                say.accept(String.format(Locale.GERMAN, "Minute %2d · %s · %s · %.0f Bilder/s · schlechtestes Bild %.0f ms (nach Wechseln %.0f ms) · Auflösung %.0f %% · Speicher %d MB · %s",
                        minute, clock.label(), r.quality().label, fps, worst, worstSwitch, r.renderScale * 100, heap, r.regisseur.zeile()));
                worst = 0;
            }
        }
        // Ende: Zustand noch einmal, Physik anhalten, aufräumen
        LinkedHashMap<String, String> z = Zustand.erfassen(r);
        final List<String>[] fehl = new List[1];
        r.imBild(() -> fehl[0] = Zustand.anwenden(r, z));
        d.display();
        boolean zustandOk = fehl[0] != null && fehl[0].isEmpty();
        ds.sitzungBeenden(sid);
        r.physik.stop();
        d.destroy();
        double mittel = nMittel > 0 ? sumFpsMittel / nMittel : Double.NaN;
        say.accept(String.format(Locale.GERMAN, "Gesamt: %d Bilder · %d Aktionen (%d Regiebücher, %d Feuerwerke, %d Blitze, %d Sturmfluten, %d Fahrten, %d Stufenwechsel) · %d Zustände · Speicher 5. Minute %d MB, Ende %d MB · schlechtestes Bild %.0f ms, nach Wechseln %.0f ms · Mittel auf „Mittel“ %.0f Bilder/s",
                frames, aktionen, buchStarts, feuerwerke, blitze, sturmfluten, fahrtenN, stufen, zustaende, heap5, heapEnd, worstAll, worstSwitch, mittel));
        if (nLangsam > 0) {
            say.accept(String.format(Locale.GERMAN, "Langsame Bilder über 1 s ohne Sprung davor: %d (Handlungen: 0/6/12 Ansicht, 1 Regiebuch, 2 Feuerwerk, 3 Blitz, 4 Stufe, 5 Lage, 7 Wind, 8 Uhr, 9 Sturmflut, 10 Mitfahrt, 11 Regatta, 13 Fahrt, 14 Grundansicht)", nLangsam));
            langsam.forEach(say);
        }
        List<String> gruende = new ArrayList<>();
        synchronized (FEHLER) { gruende.addAll(FEHLER); }
        if (heap5 > 0 && heapEnd > heap5 * 1.25 + 64) gruende.add(String.format(Locale.GERMAN, "Speicher gewachsen: %d → %d MB", heap5, heapEnd));
        if (worstAll > 2000) gruende.add(String.format(Locale.GERMAN, "ein Bild brauchte %.0f ms", worstAll));
        if (worstSwitch > 8000) gruende.add(String.format(Locale.GERMAN, "nach einem Wechsel brauchte ein Bild %.0f ms", worstSwitch));
        if (nMittel > 0 && mittel < 30) gruende.add(String.format(Locale.GERMAN, "auf „Mittel“ nur %.0f Bilder/s (F32: nie unter 30)", mittel));
        if (!zustandOk) gruende.add("Zustand am Ende nicht herstellbar: " + fehl[0]);
        say.accept(gruende.isEmpty() ? "Dauerlauf bestanden" : "Dauerlauf NICHT bestanden: " + String.join("; ", gruende));
        pw.close();
        System.exit(gruende.isEmpty() ? 0 : 1);
    }
}
