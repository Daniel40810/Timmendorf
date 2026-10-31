package com.dan.timmendorf.test;

import com.dan.timmendorf.app.Bibliothek;
import com.dan.timmendorf.camera.Ansichten;
import com.dan.timmendorf.camera.Camera;
import com.dan.timmendorf.gl.Mat4;
import com.dan.timmendorf.gl.Quality;
import com.dan.timmendorf.gl.Renderer;
import com.dan.timmendorf.gl.TerrainLod;
import com.dan.timmendorf.scene.Buehne;
import com.dan.timmendorf.scene.Geo;
import com.dan.timmendorf.scene.SceneClock;
import com.dan.timmendorf.scene.Site;
import com.dan.timmendorf.scene.Sun;
import com.dan.timmendorf.scene.Terrain;
import com.dan.timmendorf.wiring.Wiring;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Prüfung der App ohne Grafikkarte und ohne Datenbank (Ant-Target {@code pruefung}), Stand Phase 1: Raum und Orte,
 * die Bühne gegen OSM-Küste, GEBCO und die Lage der Orte, das Gelände (16-Bit-Werte, Raster, Höhe gleich Bühne), die
 * Auswahl der Detailstufen, Ansichten und Kamera, Uhr und Sonne am Start, die Verdrahtung der Hüllen von cloth, sail,
 * wake, firework und route, Renderer.jar mit den neuen Packages und ihren Prüfklassen, der Klassenpfad ohne Avatar, die
 * Shader-Dateien und die Regel aus dem Werkbuch, Abschnitt 5 (kein Laufzeitcode liest C:\Daten).
 * Endet mit „ERGEBNIS: alles in Ordnung“ oder der Zahl der Fehler (Rückgabewert 1).
 */
public final class Pruefung {
    private static int bad, n;

    public static void main(String[] args) throws Exception {
        long t0 = System.nanoTime();
        System.out.println("Timmendorf " + Renderer.VERSION + " · Prüfung ohne Grafikkarte");

        teil("Raum und Orte (F2 A)");
        check(Site.SEEBRUECKE.x() == 0 && Site.SEEBRUECKE.z() == 0, "Ursprung am Landende der Maritim Seebrücke (E 616 826, N 5 985 081)");
        double[] ll = Geo.UTM32N.inverse(Site.E0, Site.N0), en = Geo.UTM32N.forward(ll[0], ll[1]);
        check(Math.abs(en[0] - Site.E0) < 0.001 && Math.abs(en[1] - Site.N0) < 0.001, "UTM hin und zurück auf 1 mm");
        check(Math.abs(ll[0] - 54.0008) < 0.002 && Math.abs(ll[1] - 10.7823) < 0.003, f("Ursprung bei %s", Site.dms(ll[0], ll[1])));
        double sl = Math.hypot(Site.SCHLEIFE.x(), Site.SCHLEIFE.z());
        check(sl > 150 && sl < 300, f("Schleife %.0f m vom Landende (OSM-Umriss; 427 m sind die Länge mit dem Rundweg)", sl));
        for (Site.Ort o : new Site.Ort[]{Site.SEEBRUECKE, Site.SCHLEIFE, Site.WOLKENLOS, Site.NIENDORF, Site.SEEHOTEL})
            check(Site.KERN.contains(o.x(), o.z()), o.name() + " liegt im Kern");
        for (Site.Ort o : Site.ORTE) check(Site.FERNFELD.contains(o.x(), o.z()), o.name() + " liegt im Fernfeld");
        check(Site.KERN.breite() == 4500 && Site.KERN.hoehe() == 3700, "Kern 4,5 × 3,7 km (F1 A)");
        check(Site.FERNFELD.breite() == 18_000 && Site.FERNFELD.hoehe() == 14_000, "Fernfeld 18 × 14 km, Haffkrug bis Travemünde (F1 A)");
        check(Site.KUESTE.length > 200, f("Küste aus OSM: %d Punkte", Site.KUESTE.length));
        check(Math.abs(Site.horizon(30) / 1000 - 21.0) < 0.6, f("Kimm aus 30 m: %.1f km", Site.horizon(30) / 1000));

        teil("Küste und Bühne");
        check(Site.kuestenAbstand(Site.E0 - 300, Site.N0 - 300) > 0, "südwestlich der Seebrücke ist Land (Timmendorf)");
        check(Site.kuestenAbstand(Site.E0 + 600, Site.N0 + 600) < 0, "nordöstlich der Seebrücke ist See (Bucht)");
        check(Site.kuestenAbstand(Site.HEMMELSDORF.e(), Site.HEMMELSDORF.n()) > 0, "der Hemmelsdorfer See liegt hinter der Küste");
        check(Math.abs(Site.kuestenAbstand(Site.E0, Site.N0)) < 15, f("Landende der Seebrücke %.1f m von der OSM-Küste", Site.kuestenAbstand(Site.E0, Site.N0)));
        double fehler = 0;
        java.util.Random rk = new java.util.Random(3);
        for (int i = 0; i < 3000; i++) {
            double e = Site.FERNFELD.eMin() + rk.nextDouble() * Site.FERNFELD.breite(), nn = Site.FERNFELD.nMin() + rk.nextDouble() * Site.FERNFELD.hoehe();
            fehler = Math.max(fehler, Math.abs(Buehne.FELD.abstand(e, nn) - Site.kuestenAbstand(e, nn)));
        }
        check(fehler < 3, f("Raster des Küstenabstands weicht höchstens %.2f m ab (10-m-Zellen)", fehler));
        double h1 = hoeheQuer(-1000), h3 = hoeheQuer(-3000), h8 = hoeheQuer(-8000);
        check(h1 < -3 && h1 > -12 && h3 < -10 && h8 < -16 && h8 > -22, f("Bucht vor der Seebrücke: 1 km %.1f m, 3 km %.1f m, 8 km %.1f m (GEBCO bis −22 m)", h1, h3, h8));
        double rf = hoeheQuer(-160), rn = hoeheQuer(-115);
        check(rf > rn && rf > -3, f("Sandriff 160 m vor dem Strand %.1f m, davor die Rinne %.1f m (im Luftbild bis 3 m sichtbar)", rf, rn));
        double st = hoeheQuer(20), pr = hoeheQuer(60);
        check(st > 0.5 && st < 2.2 && pr > 2 && pr < 6, f("Strand 20 m landwärts %.1f m, Promenade %.1f m NHN", st, pr));
        double[] see = Site.fromUtm(617_300, 5_981_500);
        check(Buehne.height(see[0], see[1]) < -1, f("Hemmelsdorfer See: Grund %.1f m (Wasser im Pegel 0)", Buehne.height(see[0], see[1])));
        double kl = 0;
        for (double nn = 5_982_400; nn < 5_984_400; nn += 25) {
            double[] p = Site.fromUtm(622_000, nn);
            kl = Math.max(kl, Buehne.height(p[0], p[1]));
        }
        check(kl > 14 && kl < 30, f("Brodtener Ufer bei E 622 000: bis %.1f m NHN", kl));
        double steil = 0;
        for (double nn = 5_982_400; nn < 5_984_400; nn += 1) {
            double[] p = Site.fromUtm(622_000, nn), q = Site.fromUtm(622_000, nn + 1);
            steil = Math.max(steil, Math.abs(Buehne.height(q[0], q[1]) - Buehne.height(p[0], p[1])));
        }
        check(steil > 0.6, f("das Steilufer ist steil: bis %.1f m je m", steil));

        teil("Gelände");
        check(Terrain.decode(Terrain.encode(0)) < 0.002 && Terrain.decode(Terrain.encode(0)) > -0.002, "NHN auf 2 mm");
        check(Math.abs(Terrain.decode(Terrain.encode(95.6)) - 95.6) < 0.002 && Math.abs(Terrain.decode(Terrain.encode(-22)) + 22) < 0.002, "Stufe unter 2,3 mm von −50 bis +100 m");
        check(Terrain.N * Terrain.CELL >= Site.FERNFELD.breite() && Terrain.KN * Terrain.KCELL >= Site.KERN.breite(), "Raster decken Fernfeld und Kern");
        long tt = System.nanoTime();
        Terrain t = Terrain.ausBuehne();
        System.out.printf(Locale.GERMAN, "    Bühne gerechnet in %.1f s: %s%n", (System.nanoTime() - tt) / 1e9, t.describe());
        check(!t.measured() && t.hasKern(), "ohne daten/gelaende: Bühne, der Kern aus der Bühne zu 1 m");
        double[] k = Site.KERN.scene();
        check(Terrain.KX0 <= k[0] && Terrain.KZ0 <= k[1] && Terrain.KX0 + Terrain.KN >= k[2] && Terrain.KZ0 + Terrain.KN >= k[3], "Kernraster deckt das Kernfenster");
        double kw = 0;
        java.util.Random rnd0 = new java.util.Random(11);
        for (int i = 0; i < 2000; i++) {
            int ci = 200 + rnd0.nextInt(Terrain.KN - 400), cj = 200 + rnd0.nextInt(Terrain.KN - 400);
            double x = Terrain.kcx(ci), z = Terrain.kcz(cj);
            kw = Math.max(kw, Math.abs(t.height(x, z) - Buehne.height(x, z)));
        }
        check(kw < 0.01, f("Kern trifft die Bühne an den Zellmitten auf %.4f m", kw));
        double worst = 0;
        java.util.Random rnd = new java.util.Random(7);
        for (int i = 0; i < 2000; i++) {
            int ci = 10 + rnd.nextInt(Terrain.N - 20), cj = 10 + rnd.nextInt(Terrain.N - 20);
            double x = Terrain.cx(ci), z = Terrain.cz(cj);
            if (Terrain.fernShare(x, z) > 0 || t.kernShare(x, z) > 0) continue;
            worst = Math.max(worst, Math.abs(t.height(x, z) - Buehne.height(x, z)));
        }
        check(worst < 0.01, f("Gelände trifft die Bühne an den Zellmitten auf %.4f m", worst));
        double[] r2 = new double[2];
        t.range(k[0], k[1], 4500, r2);
        check(r2[0] < -2 && r2[1] > 8, f("Höhenbereich im Kern %.1f bis %.1f m", r2[0], r2[1]));
        double[] sh = t.shares();
        check(sh[Terrain.SEE] > 0.2 && sh[Terrain.STRAND] > 0 && sh[Terrain.KLIFF] > 0 && sh[Terrain.SIEDLUNG] > 0 && sh[Terrain.ACKER] > 0 && sh[Terrain.SCHILF] > 0,
                "Arten: Wasser, Strand, Steilufer, Siedlung, Acker, Schilf vorhanden");

        teil("Detailstufen (CDLOD)");
        TerrainLod lod = new TerrainLod(t);
        Camera cam = new Camera();
        cam.groundLevel = t.height(cam.eye[0], cam.eye[2]);
        cam.snap();
        float[] vp = Mat4.mul(Mat4.perspective(Camera.FOVY, 16 / 9.0, 0.3, 120_000, true), cam.view());
        for (Quality ql : Quality.values()) {
            lod.lodRange = ql.terrainRange;
            lod.finest = ql.terrainFine;
            lod.finestKern = ql.terrainKern;
            lod.collect(cam.eye, vp);
            check(lod.nodes > 20 && lod.nodes < 4000, f("%s: %d Knoten, %,d Punkte", ql.label, lod.nodes, lod.vertices));
        }
        check(TerrainLod.ROOT / (1 << (TerrainLod.LEVELS - 1)) / TerrainLod.DIM == 1.0, "feinste Stufe 1 m Punktabstand (Kern)");

        teil("Ansichten und Kamera");
        for (Ansichten.Ansicht a : Ansichten.ALLE) {
            Camera c = new Camera();
            c.waterLevel = 0;
            Ansichten.apply(a, c, Renderer.PHASE, t::height);
            c.snap();
            double g = Math.max(t.height(c.eye[0], c.eye[2]), 0);
            boolean drin = Site.FERNFELD.contains(c.eye[0], c.eye[2]);
            check(drin && c.eye[1] >= g + Math.min(a.eyeAboveGround(), Camera.MIN_EYE) - 0.05,
                    f("%s: Auge %.1f m über NHN, %.1f m über Grund/Wasser, Blick %03.0f°, %s", a, c.eye[1], c.eye[1] - g, c.heading(), Buehne.zone(c.eye[0], c.eye[2])));
        }
        Ansichten.Ansicht sp = Ansichten.byName("Spuelsaum");
        double gs = t.height(sp.x(), sp.z());
        check(Math.abs(gs) < 0.8, f("Spülsaum an der Wasserlinie: Grund %+.2f m", gs));
        Ansichten.Ansicht br = Ansichten.byName("BrodtenerUfer");
        check(t.height(br.x(), br.z()) > 10, f("Brodtener Ufer steht oben: Grund %.1f m", t.height(br.x(), br.z())));
        check(Ansichten.byName("Seebruecke") != null && Ansichten.byName("7") != null && Ansichten.byName("HemmelsdorferSee") != null, "Namen ohne Umlaute und Tasten");
        Camera h = new Camera();
        h.snap();
        check(Site.kuestenAbstand(h.eye[0] + Site.E0, Site.N0 - h.eye[2]) < 0 && h.heading() > 190 && h.heading() < 260,
                f("Grundansicht von See auf Seebrücke und Ort, Blick %03.0f°", h.heading()));

        teil("Uhr und Sonne am Start");
        SceneClock clock = new SceneClock();
        double[] s = clock.sun();
        check(clock.label().contains("25.7.2026") && clock.label().contains("19:30") && clock.zoneLabel().equals("MESZ"), "Start " + clock.label());
        check(s[0] > 8 && s[0] < 22 && s[1] > 260 && s[1] < 300, f("Sonne am Start %.1f° hoch aus %03.0f° (im Westen hinter dem Ort)", s[0], s[1]));
        double day = LocalDate.of(2026, 6, 21).atStartOfDay(SceneClock.ZONE).toInstant().toEpochMilli();
        double up = Sun.crossing(day, Site.LAT, Site.LON, true), down = Sun.crossing(day, Site.LAT, Site.LON, false);
        String dn = String.format("%tR", java.time.Instant.ofEpochMilli((long) down).atZone(SceneClock.ZONE));
        String an = String.format("%tR", java.time.Instant.ofEpochMilli((long) up).atZone(SceneClock.ZONE));
        check(dn.compareTo("21:40") > 0 && dn.compareTo("22:05") < 0 && an.compareTo("04:40") > 0 && an.compareTo("05:05") < 0,
                "21.6.: Aufgang " + an + ", Untergang " + dn + " MESZ");
        double[] sa = Sun.position(up + 1_800_000, Site.LAT, Site.LON);
        check(sa[1] > 35 && sa[1] < 75, f("im Juni geht die Sonne über der Bucht auf (eine halbe Stunde danach aus %03.0f°)", sa[1]));

        teil("Verdrahtung der Hüllen");
        Wiring w = new Wiring(clock);
        w.setTerrain(t);
        for (int i = 0; i < 240; i++) w.step(1 / 60.0);
        double[] tip = w.flagge.point(w.flagge.nu - 1, w.flagge.nv - 1), base = w.flagge.point(0, 0);
        check(tip[0] < base[0] && tip[2] > base[2], f("Seewind aus Nordost: die Flagge weht nach Südwest (Δx %.1f, Δz %.1f m)", tip[0] - base[0], tip[2] - base[2]));
        double[] fr = w.sail.forces(w.opti, 0);
        check(Double.isFinite(fr[0]) && fr[3] > 0, f("Optimist: scheinbarer Wind %.1f m/s unter %.0f°", fr[3], fr[4]));
        check(Math.abs(w.optiWake.x - w.opti.x) < 1e-9 && w.optiWake.speed == w.opti.speed, "Kielwasser folgt dem Optimisten");
        check(Buehne.height(w.opti.x, w.opti.z) < -1, f("der Optimist schwimmt (Grund %.1f m)", Buehne.height(w.opti.x, w.opti.z)));
        check(Buehne.height(w.ponton[0], w.ponton[1]) < -1, f("der Ponton liegt im Wasser (Grund %.1f m)", Buehne.height(w.ponton[0], w.ponton[1])));
        // seit Phase 12 folgt das Feuerwerk der Uhr der Szene: die Vorführung zünden und die Uhr mitlaufen lassen
        w.plan = Wiring.Plan.VORFUEHRUNG;
        com.dan.firework.Firework fw = w.feuerwerk();
        int[] knall = new int[1];
        fw.events = new com.dan.firework.Firework.Events() {
            @Override public void onBurst(com.dan.firework.Firework.Shell sh, double x, double y, double z, double st, double tt) { knall[0]++; }
        };
        for (int i = 0; i < 40 * 60; i++) { clock.setEpochMillis(clock.epochMillis() + 1000.0 / 60); w.step(1 / 60.0); }
        check(knall[0] == 12, f("Feuerwerk über dem Ponton (Vorführung, mit der Uhr): %d von 12 Zerlegungen", knall[0]));
        if (w.wegeQuelle.startsWith("TD_NET_FUSS")) check(w.wege.nodeCount() > 1000, "Wege: " + w.wegeQuelle);
        else check(w.wege.nodeCount() == 2 && Math.abs(w.wege.totalLength() - Math.hypot(Site.SCHLEIFE.x(), Site.SCHLEIFE.z())) < 1, "Wege: Landende bis Schleife (" + w.wegeQuelle + ")");
        System.out.println("    " + w.segelZeile());
        System.out.println("    " + w.hullenZeile(0, 0));
        // seit Phase 9: Boote und Bauwerke auf dem gemessenen Gelände, wenn es da ist (die Bühne hat keine Rinne und keinen Hafen)
        Terrain gemessen = Terrain.load(Terrain.defaultDir());
        Terrain tb = gemessen.measured() ? gemessen : t;
        w.setTerrain(tb);
        boote(w, tb);
        bauwerke(w, tb);
        strand(w, tb);
        menschen(tb);
        abend(tb);
        wetter(tb);
        sturmflut(tb);
        klang(tb);
        regie(tb);
        quallen(tb);
        datenseite(tb);
        schliff();

        teil("Renderer.jar, Klassenpfad und Shader");
        List<String> pk = Bibliothek.pakete();
        check(Bibliothek.fehlend().isEmpty(), "Renderer.jar: " + Bibliothek.kurz());
        for (String neu : Bibliothek.NEU) check(pk.contains(neu), "neu: " + neu);
        String cp = System.getProperty("java.class.path");
        check(!cp.contains("Avatar.jar"), "Avatar.jar ist nicht im Klassenpfad (30.10.)");
        check(!cp.contains("Nature.jar"), "Nature.jar ist nicht im Klassenpfad (Phase 5: tree und grass im Renderer)");
        int ir = cp.indexOf("Renderer.jar"), ifs = cp.indexOf("FStyle.jar");
        check(ir >= 0 && ifs >= 0 && ir < ifs, "Renderer.jar steht vor FStyle.jar (FStyle bringt ältere Kopien von ground, forest, river, road)");
        Renderer rd = new Renderer(clock, t);
        for (String[] prog : rd.shaderFiles())
            for (String file : prog)
                check(Renderer.class.getResource("/com/dan/timmendorf/gl/shader/" + file) != null, "Shader " + file + " im Jar");
        for (String c : new String[]{"com.dan.cloth.demo.ClothCheck", "com.dan.sail.demo.SailCheck", "com.dan.wake.demo.WakeCheck",
                "com.dan.firework.demo.FireworkCheck", "com.dan.route.demo.RouteCheck", "com.dan.tree.demo.TreeCheck", "com.dan.tree.demo.GrassCheck", "com.dan.lamp.demo.LampCheck",
                "com.dan.soundscape.demo.SoundscapeCheck", "com.dan.jelly.demo.JellyCheck"}) {
            Process p = new ProcessBuilder(javaBin(), "-Dstdout.encoding=UTF-8", "-cp", cp, c).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            check(p.waitFor() == 0 && out.contains("Alles in Ordnung."), c + ": " + (out.contains("Alles in Ordnung.") ? "Alles in Ordnung." : out.strip()));
        }

        teil("Grundbild (Phase 4): Himmel, Wolken, See, Häuser");
        rd.wiring.feed(rd.wetter.sky, 30);
        rd.wiring.feed(rd.wetter.clouds);
        rd.wiring.feed(rd.meer.ocean);
        com.dan.sky.Sky.State ss = rd.wetter.sky.updateNow();
        double[] sunNow = rd.clock.sun();
        check(Math.abs(ss.sunAlt - sunNow[0]) < 1.0, f("com.dan.sky am Start: Sonne %.1f° hoch (Uhr der Szene %.1f°)", ss.sunAlt, sunNow[0]));
        check(rd.meer.ocean.swellHeight > 0.3 && rd.meer.ocean.swellHeight < 0.7 && rd.meer.ocean.windFromDeg == 45,
                f("Ostsee: Wind %.1f m/s aus %03.0f°, Seegang %.2f m, %.1f s", rd.meer.ocean.windSpeed, rd.meer.ocean.windFromDeg, rd.meer.ocean.swellHeight, rd.meer.ocean.swellPeriod));
        check(rd.wetter.clouds.oktas > 1 && rd.wetter.clouds.oktas < 5, f("Wolken: %s", rd.wetter.clouds.describe()));
        byte[] binnen = com.dan.timmendorf.gl.Meer.binnen(t);
        int bi = (int) ((Site.HEMMELSDORF.x() - Terrain.X0) / Terrain.CELL), bj = (int) ((Site.HEMMELSDORF.z() - Terrain.Z0) / Terrain.CELL);
        int si = (int) ((Site.SCHLEIFE.x() * 8 - Terrain.X0) / Terrain.CELL), sj = (int) ((Site.SCHLEIFE.z() * 8 - Terrain.Z0) / Terrain.CELL);
        check(binnen[sj * Terrain.N + si] == 0 && (!t.measured() || binnen[bj * Terrain.N + bi] == 0),
                "Maske der Senken: die Bucht und der Hemmelsdorfer See bekommen Wasser im Pegel");
        if (rd.bauten.haeuser.isEmpty()) System.out.println("    (daten/bauten fehlt: keine Häuser)");
        else {
            check(rd.bauten.haeuser.size() > 30_000, "Häuser: " + rd.bauten.describe());
            com.dan.timmendorf.scene.Bauten.Haus hh = rd.bauten.hoechstes();
            double[] hu = Site.toUtm(hh.x(), hh.z());
            check(hh.hoehe() > 100 && Math.hypot(hu[0] - Site.TRAVEMUENDE.e(), hu[1] - Site.TRAVEMUENDE.n()) < 80,
                    f("höchstes Haus: First %.1f m NHN bei O %.0f, N %.0f (Maritim Travemünde mit dem Leuchtfeuer)", hh.hoehe(), hu[0], hu[1]));
            double club = 0;
            for (com.dan.timmendorf.scene.Bauten.Haus bh : rd.bauten.haeuser)
                if (Math.hypot(bh.x() - Site.CLUBHOTEL.x(), bh.z() - Site.CLUBHOTEL.z()) < 60) club = Math.max(club, bh.hoeheUeberGrund());
            check(club > 40, f("Maritim ClubHotel: %.1f m über Grund", club));
            int[] z = new int[3];
            com.dan.timmendorf.scene.Baunetz bn = rd.bauten.netz(z);
            check(bn.dreiecke() > 500_000 && z[1] > 0 && z[2] > 0, f("Netz der Häuser: %,d Dreiecke, Dächer %d Reet, %d grau, %d rot", bn.dreiecke(), z[0], z[1], z[2]));
        }

        teil("Bewuchs (Phase 5a): Bäume, Promenade, Halme");
        com.dan.timmendorf.scene.Bewuchs bw = rd.bewuchs;
        if (bw.baeume.isEmpty()) System.out.println("    (" + bw.quelle + ": kein Bewuchs)");
        else {
            check(bw.baeume.size() > 100_000, bw.describe());
            for (com.dan.timmendorf.scene.Bewuchs.Gruppe g : com.dan.timmendorf.scene.Bewuchs.Gruppe.values())
                for (String a : g.arten) check(com.dan.tree.Tree.art(a) != null, "Art für " + g.name + ": " + a + " (eigentlich " + g.eigentlich + ")");
            com.dan.timmendorf.scene.Bewuchs nochmal = com.dan.timmendorf.scene.Bewuchs.laden(com.dan.timmendorf.scene.Daten.ordner(), t);
            check(nochmal.baeume.size() == bw.baeume.size() && nochmal.baeume.get(nochmal.baeume.size() / 2).equals(bw.baeume.get(bw.baeume.size() / 2)),
                    "gleiche Daten, gleiche Bäume");
            if (com.dan.timmendorf.scene.Netz.da(com.dan.timmendorf.scene.Netz.ordner(), com.dan.timmendorf.scene.Netz.Art.FUSS)) {
                int prom = bw.anDerPromenade(com.dan.timmendorf.scene.Netz.laden(com.dan.timmendorf.scene.Netz.ordner(), com.dan.timmendorf.scene.Netz.Art.FUSS));
                check(prom > 100, f("die Promenade hat Bäume: %d über 5 m bis 15 m neben ihr", prom));
            }
            double[] bsp = {Site.SEEBRUECKE.x(), Site.SEEBRUECKE.z()};
            double hafer = 0, schilf = 0;
            for (int i = -400; i <= 400; i += 4)
                for (int j = -300; j <= 300; j += 4) hafer += bw.dichte(bsp[0] + i, bsp[1] + j, com.dan.timmendorf.scene.Bewuchs.Halm.STRANDHAFER, t);
            for (int i = -800; i <= 800; i += 8)
                for (int j = -600; j <= 600; j += 8) schilf += bw.dichte(Site.HEMMELSDORF.x() + i, Site.HEMMELSDORF.z() + j, com.dan.timmendorf.scene.Bewuchs.Halm.SCHILF, t);
            check(hafer > 0, f("Strandhafer am Strand der Seebrücke (Summe der Dichte %.0f)", hafer));
            check(schilf > 0, f("Schilf am Hemmelsdorfer See (Summe der Dichte %.0f)", schilf));
        }

        teil("Aufbereitete Daten (Phase 2)");
        File inh = new File(com.dan.timmendorf.scene.Daten.ordner(), com.dan.timmendorf.scene.Daten.INHALT);
        if (inh.isFile()) {
            List<String> falsch = com.dan.timmendorf.tools.Inhalt.pruefen(com.dan.timmendorf.scene.Daten.ordner());
            check(falsch.isEmpty(), "daten/INHALT.txt: " + (falsch.isEmpty() ? "alle Prüfsummen stimmen" : String.join(", ", falsch.subList(0, Math.min(4, falsch.size())))));
            Terrain g = Terrain.load(Terrain.defaultDir());
            check(g.measured() && g.hasKern(), "Gelände aus daten/gelaende: " + g.source);
            double sb = g.roh(0, 0), gsh = g.roh(Site.SEEHOTEL.x(), Site.SEEHOTEL.z()), gsl = g.roh(Site.SCHLEIFE.x(), Site.SCHLEIFE.z());
            check(sb > -1.5 && sb < 3.5, f("Landende der Seebrücke: Grund %.2f m NHN", sb));
            check(gsl < -1 && gsl > -6, f("Schleife über Wasser: Grund %.2f m NHN", gsl));
            check(gsh > 0.5 && gsh < 8, f("Maritim Seehotel: Grund %.1f m NHN", gsh));
            double[] u3 = Site.toUtm(Site.SCHLEIFE.x() * 15, Site.SCHLEIFE.z() * 15);
            double[] b3 = Site.fromUtm(u3[0], u3[1]);
            check(g.roh(b3[0], b3[1]) < -8, f("Bucht 3 km vor der Seebrücke: %.1f m NHN", g.roh(b3[0], b3[1])));
            File nu = new File(com.dan.timmendorf.scene.Daten.ordner(), "alkis/" + com.dan.timmendorf.tools.AlkisImport.KERN);
            check(nu.length() == (long) Terrain.KN * Terrain.KN, "ALKIS-Nutzung im Kern: " + nu.length() + " Bytes");
            for (String d : new String[]{"kern/bewuchs.u8", "kern/klasse.u8", "kern/luftbild.rgbi", "bauten/bauten.bin", "gelaende/quelle.u8"})
                check(new File(com.dan.timmendorf.scene.Daten.ordner(), d).isFile(), "daten/" + d + " da");
        } else System.out.println("    (daten/INHALT.txt fehlt: ant aufbereitung ist noch nicht gelaufen; die App zeigt die Bühne)");

        teil("Netze aus Overpass (Phase 3, Datei-Abbild des Oracle-Netzes)");
        File nd = com.dan.timmendorf.scene.Netz.ordner();
        if (com.dan.timmendorf.scene.Netz.da(nd, com.dan.timmendorf.scene.Netz.Art.FUSS)) netze(nd);
        else System.out.println("    (daten/netz fehlt: ant aufbereitung -Dschritt=netz+inhalt)");

        teil("Regel aus Abschnitt 5: die App liest nie aus C:\\Daten");
        File src = new File("src");
        if (src.isDirectory()) {
            int treffer = 0;
            try (Stream<Path> wege = Files.walk(src.toPath())) {
                for (Path p : (Iterable<Path>) wege.filter(x -> x.toString().endsWith(".java"))::iterator) {
                    String txt = Files.readString(p, StandardCharsets.UTF_8);
                    boolean tool = p.toString().replace('\\', '/').contains("/timmendorf/tools/");
                    if (!tool && txt.replace("\\\\", "\\").toLowerCase(Locale.ROOT).contains("c:\\daten") && txt.contains("new File(\"C:"))
                        treffer++;
                }
            }
            check(treffer == 0, "kein Laufzeitcode öffnet C:\\Daten (nur Werkzeuge in com.dan.timmendorf.tools, ab Phase 2)");
        } else System.out.println("    (src nicht gefunden, übersprungen)");

        System.out.printf(Locale.GERMAN, "%n%d Prüfungen in %.1f s%n", n, (System.nanoTime() - t0) / 1e9);
        System.out.println(bad == 0 ? "ERGEBNIS: alles in Ordnung" : "ERGEBNIS: " + bad + " Fehler");
        System.exit(bad == 0 ? 0 : 1);
    }

    /** Höhe der Bühne s m landwärts der Küste (negativ: seewärts), quer zur Küste an der Seebrücke. */
    static double hoeheQuer(double s) {
        double[] u = Site.toUtm(0, 0);
        double h = 5, ge = (Site.kuestenAbstand(u[0] + h, u[1]) - Site.kuestenAbstand(u[0] - h, u[1])) / (2 * h),
                gn = (Site.kuestenAbstand(u[0], u[1] + h) - Site.kuestenAbstand(u[0], u[1] - h)) / (2 * h), l = Math.hypot(ge, gn);
        double d = Site.kuestenAbstand(u[0], u[1]);
        double[] xz = Site.fromUtm(u[0] + (s - d) * ge / l, u[1] + (s - d) * gn / l);
        return Buehne.height(xz[0], xz[1]);
    }

    static String javaBin() { return new File(System.getProperty("java.home"), "bin/java").getPath(); }

    static String f(String fmt, Object... a) { return String.format(Locale.GERMAN, fmt, a); }

    /** Phase 3: die vier Netze, ihre Kanten, Längen und die Prüfwege aus daten/netz. */
    static void netze(File nd) throws Exception {
        java.util.Map<String, com.dan.timmendorf.scene.Netz> alle = new java.util.HashMap<>();
        for (com.dan.timmendorf.scene.Netz.Art a : com.dan.timmendorf.scene.Netz.Art.values()) {
            com.dan.timmendorf.scene.Netz n = com.dan.timmendorf.scene.Netz.laden(nd, a);
            alle.put(a.oracle, n);
            int ohne = 0, falsch = 0, doppelt = 0;
            java.util.Set<String> lage = new java.util.HashSet<>();
            for (com.dan.timmendorf.scene.Netz.Knoten k : n.knoten) if (!lage.add(Math.round(k.e() * 100) + "_" + Math.round(k.n() * 100))) doppelt++;
            for (com.dan.timmendorf.scene.Netz.Kante k : n.kanten) {
                if (n.knoten(k.von()) == null || n.knoten(k.nach()) == null) { ohne++; continue; }
                double[] p = k.punkte();
                double l = 0;
                for (int i = 3; i < p.length; i += 3) l += Math.sqrt(Math.pow(p[i] - p[i - 3], 2) + Math.pow(p[i + 1] - p[i - 2], 2) + Math.pow(p[i + 2] - p[i - 1], 2));
                com.dan.timmendorf.scene.Netz.Knoten a0 = n.knoten(k.von()), b0 = n.knoten(k.nach());
                if (Math.abs(l - k.laenge()) > 0.02 || Math.hypot(p[0] - a0.e(), p[1] - a0.n()) > 0.02 || Math.hypot(p[p.length - 3] - b0.e(), p[p.length - 2] - b0.n()) > 0.02) falsch++;
            }
            check(!n.knoten.isEmpty() && ohne == 0 && falsch == 0 && (a == com.dan.timmendorf.scene.Netz.Art.SEE || doppelt * 100 < n.knoten.size()),
                    f("%s: Kanten an ihren Knoten, Längen über die Stützpunkte (%d ohne Knoten, %d falsch, %d Knoten doppelt)", n, ohne, falsch, doppelt));
        }
        int gleich = 0, alleWege = 0;
        for (String l : Files.readAllLines(new File(nd, "pruefwege.tsv").toPath(), StandardCharsets.UTF_8)) {
            String[] s = l.split("\t", -1);
            if (s[0].equals("netz")) continue;
            com.dan.timmendorf.scene.Netz n = alle.get(s[0]);
            com.dan.route.Route g = n.route();
            com.dan.route.Route.Path p = g.shortest(g.indexOf(Integer.parseInt(s[2])), g.indexOf(Integer.parseInt(s[3])), com.dan.route.Route.distance());
            double soll = Double.parseDouble(s[4]);
            alleWege++;
            boolean ok = soll < 0 ? !p.found() : p.found() && Math.abs(p.length() - soll) < 0.05;
            if (ok) gleich++;
            if (!ok || s[1].contains("Wolkenlos")) check(ok, f("Prüfweg %s: route %.1f m, Datei %.1f m", s[1], p.found() ? p.length() : -1, soll));
        }
        check(gleich == alleWege && alleWege >= 8, f("alle %d Prüfwege mit route so lang wie in pruefwege.tsv (gegen Oracle in Phase 3b)", alleWege));
    }

    /** Seit Phase 8: die Boote der Szene (Flotte, Regatta, Fähren, Passat, Kielwasser). */
    static void boote(Wiring w, Terrain t) {
        teil("Boote (Phase 8a): Flotte, Regatta, Fähren, Kielwasser" + (t.measured() ? " · Gelände gemessen" : " · Gelände der Bühne"));
        com.dan.timmendorf.scene.Flotte fl = w.flotte;
        String[] namen = {"Segelschule", "Optimist", "Fahrtenyacht A", "Fahrtenyacht B", "Gaffelschoner", "Hobie-Kats", "Kiter"};
        double[][][] wege = {com.dan.timmendorf.scene.Flotte.SCHULE, com.dan.timmendorf.scene.Flotte.OPTI, com.dan.timmendorf.scene.Flotte.YACHT_A,
                com.dan.timmendorf.scene.Flotte.YACHT_B, com.dan.timmendorf.scene.Flotte.SCHONER, com.dan.timmendorf.scene.Flotte.HOBIE, com.dan.timmendorf.scene.Flotte.KITE};
        double[] need = {1.2, 1.2, 2.5, 2.5, 3.6, 1.0, 0.6};
        for (int k = 0; k < wege.length; k++) {
            double shallow = 99;
            for (double[] q : wege[k]) shallow = Math.min(shallow, -t.height(q[0], q[1]));
            check(!t.measured() || shallow >= need[k], f("%s: alle Wegpunkte im Wasser, mindestens %.1f m tief (braucht %.1f m)", namen[k], shallow, need[k]));
        }
        com.dan.sail.Regatta.Course c = fl.regatta.course;
        double mind = 99;
        for (double[] m : c.marks()) mind = Math.min(mind, -t.height(m[0], m[1]));
        check(Math.abs(c.axisDeg - 45) < 5 && c.length > 1500 && c.length < 2000 && (!t.measured() || mind > 5),
                f("Regattabahn aus TD_NET_SEE: nach %03.0f° (Seewind), %.0f m bis Luv, Linie %.0f m, Tonnen in %.1f m Wasser oder tiefer", c.axisDeg, c.length, c.line, mind));
        com.dan.timmendorf.scene.Flotte.Faehre fe = fl.faehren.get(0);
        double dryRoute = 0, minRoute = 99;
        for (double s = 150; s < fe.total(); s += 50) {
            double[] q = fe.at(s);
            if (!Site.FERNFELD.contains(q[0], q[1])) continue;
            double g = t.height(q[0], q[1]);
            minRoute = Math.min(minRoute, -g);
            if (g > -1) dryRoute += 50;
        }
        check(!t.measured() || dryRoute == 0, f("Weg der Fähren: Trave, Nordmole, Fahrwasser nach Nordost, %.1f km im Fernfeld, überall Wasser (mindestens %.1f m im Raster)", fe.total() / 1000, minRoute));
        check(fl.passat.furled && (!t.measured() || t.height(fl.passat.x, fl.passat.z) < -5), f("Passat am Priwall, Segel festgemacht, Grund %.1f m", t.height(fl.passat.x, fl.passat.z)));
        // eine Minute Startverfahren, dann zwei Minuten Rennen
        fl.regatta.start(60);
        double x0 = fl.opti.x, z0 = fl.opti.z;
        int[] sig = {0};
        java.util.List<String> zeilen = new java.util.ArrayList<>();
        fl.meldung = zeilen::add;
        com.dan.sail.Regatta.Listener ls = fl.regatta.listener;
        fl.regatta.listener = new com.dan.sail.Regatta.Listener() {
            @Override public void onSignal(String what, double tt) { sig[0]++; }
        };
        long t1 = System.nanoTime();
        int gennaker = 0;
        for (int i = 0; i < 180 * 30; i++) {
            w.step(1 / 30.0);
            for (com.dan.sail.Regatta.Entry e : fl.regatta.entries) if (e.leg == com.dan.sail.Regatta.Leg.PRESTART && e.boat.downwind) gennaker++;
        }
        check(gennaker == 0, f("vor dem Start setzt niemand den Gennaker (%d Schritte mit, Befund aus Phase 8)", gennaker));
        double rechen = (System.nanoTime() - t1) / 1e9;
        fl.regatta.listener = ls;
        int dry = 0, beating = 0;
        for (com.dan.sail.Sail.Boat b : fl.alleSegler()) if (t.measured() && t.height(b.x, b.z) > -0.2) dry++;
        for (com.dan.sail.Regatta.Entry e : fl.regatta.entries) if (e.leg == com.dan.sail.Regatta.Leg.BEAT) beating++;
        check(fl.regatta.toGun() < 0 && sig[0] >= 2 && beating >= 15, f("Regatta: Signale gegeben, gestartet, %d von 20 auf der ersten Kreuz (%s)", beating, fl.regatta.describe()));
        check(dry == 0, f("nach 3 Minuten: alle %d Segler im Wasser", fl.alleSegler().size()));
        double moved = Math.hypot(fl.opti.x - x0, fl.opti.z - z0);
        check(moved > 60 && fl.wenden() + fl.halsen() > 10, f("der Optimist segelt (%.0f m in 3 min), in der Flotte %d Wenden und %d Halsen", moved, fl.wenden(), fl.halsen()));
        check(fe.dir != 0 || fe.s > 0, f("Fähre: %s, %.0f m auf ihrem Weg, %.1f kn", fe.zustand(), fe.s, fe.speed / com.dan.sail.Sail.KN));
        check(rechen < 60, f("3 min Flotte in %.1f s gerechnet (%.0f %% eines Kerns)", rechen, rechen / 180 * 100));
        // Kielwasser
        double env = 0;
        double hr = Math.toRadians(fl.opti.headingDeg);
        for (double back = 2; back < 15; back += 0.5)
            env = Math.max(env, w.wake.envelope(w.optiWake, fl.opti.x - Math.sin(hr) * back + Math.cos(hr) * 2, fl.opti.z + Math.cos(hr) * back + Math.sin(hr) * 2));
        check(w.wake.sources().size() == fl.alleSegler().size() + fl.faehren.size() && (env > 0 || fl.opti.speed < 0.3),
                f("Kielwasser: %d Quellen (alle Segler und Fähren), hinter dem Optimisten bis %.0f mm", w.wake.sources().size(), env * 1000));
        // Kamera und Formen
        double[] pose = com.dan.timmendorf.scene.Flotte.imBoot(fl.opti, w.pegel), beg = com.dan.timmendorf.scene.Flotte.begleitboot(fl.opti, w.pegel);
        check(Math.hypot(pose[0] - fl.opti.x, pose[2] - fl.opti.z) < 2 && pose[1] - w.pegel > 0.3 && pose[1] - w.pegel < 1.5 && Math.hypot(beg[0] - fl.opti.x, beg[2] - fl.opti.z) < 30,
                f("Mitfahrt im Optimisten: Auge %.1f m über dem Wasser; Begleitboot %.0f m daneben", pose[1] - w.pegel, Math.hypot(beg[0] - fl.opti.x, beg[2] - fl.opti.z)));
        java.util.List<double[]> rm = fl.rumpfe(0, 0, 8, 3000);
        check(rm.size() <= 8 && (rm.size() < 2 || Math.hypot(rm.get(0)[0], rm.get(0)[1]) <= Math.hypot(rm.get(rm.size() - 1)[0], rm.get(rm.size() - 1)[1])),
                f("die See spart die %d nächsten Rümpfe aus", rm.size()));
        float[] ff = new com.dan.timmendorf.scene.FaehrenForm().build(fe, w.pegel), ps = new com.dan.sail.BoatShape().build(fl.passat, w.pegel);
        boolean fin = true;
        for (float v : ff) fin &= Float.isFinite(v);
        check(fin && ff.length / 27 > 100 && ps.length / 27 > 500, f("Formen: Fähre %d Dreiecke, Passat mit gepackten Segeln %d Dreiecke", ff.length / 27, ps.length / 27));
        for (String z : zeilen.subList(0, Math.min(4, zeilen.size()))) System.out.println("    · " + z);
        System.out.println("    " + fl.zeile());
    }

    /** Seit Phase 9: Bauwerke am Wasser, Strandkörbe, Leuchtfeuer, Brodtener Ufer. */
    static void bauwerke(Wiring w, Terrain t) {
        teil("Bauwerke (Phase 9): Seebrücken, Wolkenlos, DLRG, Kutter, Strandkörbe, Leuchtfeuer, Brodtener Ufer");
        com.dan.timmendorf.scene.Bauwerke b = w.bauwerke;
        System.out.println("    " + b.describe());
        boolean osm = !b.quelle.startsWith("ohne");
        com.dan.timmendorf.scene.Bauwerke.Bauwerk neu = b.bauwerke.stream().filter(x -> x.name().equals("Neue Seebrücke")).findFirst().orElse(null),
                ssb = b.bauwerke.stream().filter(x -> x.name().equals("Seeschlösschenbrücke")).findFirst().orElse(null);
        if (osm) {
            double maxx = -1e9;
            if (neu != null) for (double[] q : neu.p()) maxx = Math.max(maxx, q[0]);
            check(neu != null && neu.flaeche() && maxx > 170 && maxx < 220, f("Maritim Seebrücke aus OSM als Fläche mit der Schleife bis x = %.0f m", maxx));
            check(ssb != null && Math.abs(ssb.p()[0][0] - 720) < 80, "Seeschlösschenbrücke mit dem Teehaus Wolkenlos (OSM)");
            check(b.wachen.size() >= 10 && b.feuer.size() >= 10, f("aus OSM: %d Wachen und Türme der DLRG, %d Leuchtfeuer", b.wachen.size(), b.feuer.size()));
        } else System.out.println("    (daten/bauwerke fehlt: ant aufbereitung -Dschritt=bauwerke+inhalt; nur die Seebrücke Niendorf von Hand)");
        com.dan.timmendorf.scene.Baunetz n = b.netz(t::height, w.fuss);
        boolean fin = true;
        for (int i = 0; i < n.ecken(); i++) fin &= Float.isFinite(n.x(i)) && Float.isFinite(n.y(i)) && Float.isFinite(n.z(i));
        double[] hy = n.hoehen();
        int[] z = b.zahl;
        check(fin && n.dreiecke() > 5000 && hy[1] > 30 && hy[1] < 32, f("Netz: %,d Dreiecke, %,d Pfähle, %d + %d Laternen, %d Türme, %d Kutter, höchster Punkt %.1f m (Alter Leuchtturm 31 m)",
                n.dreiecke(), z[2], z[3], z[4], z[5], z[6], hy[1]));
        check(!osm || z[2] > 1000, f("Pfähle unter den Brücken: %,d", z[2]));
        if (t.measured()) {
            double tief = -99;
            for (double[] k : com.dan.timmendorf.scene.Bauwerke.KUTTER) tief = Math.max(tief, t.height(k[0], k[1]));
            check(tief < -0.5, f("Kutter im Niendorfer Hafen: Grund an allen Liegeplätzen höchstens %.1f m NHN", tief));
            double gs = t.height(com.dan.timmendorf.scene.Bauwerke.WOLKENLOS_X, com.dan.timmendorf.scene.Bauwerke.WOLKENLOS_Z);
            check(gs < -0.5, f("Wolkenlos steht über Wasser (Grund %.1f m)", gs));
        }
        java.util.List<com.dan.timmendorf.scene.Bauwerke.Korb> ks = b.koerbe(t::height, (x, zz) -> t.artAt(x, zz) == 3);
        int naheSb = 0, zurSee = 0;
        double ymin = 99, ymax = -99;
        for (com.dan.timmendorf.scene.Bauwerke.Korb k : ks) {
            if (Math.hypot(k.x(), k.z()) < 400) naheSb++;
            ymin = Math.min(ymin, k.y());
            ymax = Math.max(ymax, k.y());
            double a = Math.toRadians(k.richtung());
            double[] u0 = Site.toUtm(k.x(), k.z()), u1 = Site.toUtm(k.x() + Math.sin(a) * 5, k.z() - Math.cos(a) * 5);
            if (Site.kuestenAbstand(u1[0], u1[1]) < Site.kuestenAbstand(u0[0], u0[1])) zurSee++;
        }
        check(!t.measured() || (ks.size() > 2000 && naheSb > 50 && ymin >= 0.5 && ymax <= 3.2 && zurSee > ks.size() * 0.97),
                f("Strandkörbe: %,d, davon %d an der Seebrücke, auf %.1f bis %.1f m NHN, %.0f %% schauen zur See; %s Juli, %s Dezember", ks.size(), naheSb, ymin, ymax,
                        100.0 * zurSee / Math.max(1, ks.size()), com.dan.timmendorf.scene.Bauwerke.saison(java.time.LocalDate.of(2026, 7, 25)) ? "stehen im" : "fehlen im",
                        com.dan.timmendorf.scene.Bauwerke.saison(java.time.LocalDate.of(2026, 12, 1)) ? "stehen im" : "fehlen im"));
        com.dan.timmendorf.scene.Leuchtfeuer lf = w.leuchtfeuer;
        lf.step(5, 10_000);
        int tag = lf.brennen();
        lf.step(5, 0.01);
        int nacht = lf.brennen();
        boolean altDunkel = !lf.alter.isOn();
        lf.setHistorisch(true);
        lf.step(5, 0.01);
        boolean altAn = lf.alter.isOn();
        lf.setHistorisch(false);
        lf.step(5, 0.01);
        check(tag == 0 && nacht == lf.lichter.size() - 1 && altDunkel && altAn && Math.abs(lf.maritim.y - 114.7) < 0.01,
                f("Leuchtfeuer: am Tag %d an, nachts %d von %d (der Alte Leuchtturm dunkel, historisch an); %s", tag, nacht, lf.lichter.size(), lf.describe()));
        double sp = lf.maritim.characteristic().period;
        check(sp == 4 && lf.nordermole.characteristic().text.startsWith("Oc"), "Kennungen: Maritim " + lf.maritim.characteristic().text + ", Nordermole " + lf.nordermole.characteristic().text);
        com.dan.cliff.Cliff c = com.dan.cliff.Cliff.brodtenerUfer();
        com.dan.cliff.CliffMap m = com.dan.cliff.CliffMap.build(c, t::height, com.dan.timmendorf.gl.Renderer.KLIFF_X0, com.dan.timmendorf.gl.Renderer.KLIFF_Z0,
                com.dan.timmendorf.gl.Renderer.KLIFF_ZELLE, com.dan.timmendorf.gl.Renderer.KLIFF_N, 6, 40);
        check(!t.measured() || (m.wallShare > 0.0005 && m.wallShare < 0.1), f("Brodtener Ufer (cliff): Wand %.2f %%, Geröll %.2f %% der Karte (%.0f × %.0f m)", m.wallShare * 100, m.talusShare * 100,
                com.dan.timmendorf.gl.Renderer.KLIFF_ZELLE * com.dan.timmendorf.gl.Renderer.KLIFF_N, com.dan.timmendorf.gl.Renderer.KLIFF_ZELLE * com.dan.timmendorf.gl.Renderer.KLIFF_N));
    }

    /**
     * Seit Phase 20: die Strandkörbe aus com.dan.beachchair (nach dem Foto Strandkorb.jpg) und der Flugsand am Strand wie in
     * Sylt (com.dan.dune mit dem Wind aus com.dan.wind).
     */
    static void strand(Wiring w, Terrain t) {
        teil("Strandkörbe, Strand und Flugsand (Phase 20, 21): beachchair, beach, dune, wind");
        com.dan.beachchair.BeachChair korb = com.dan.timmendorf.scene.Bauwerke.korb();
        System.out.println("    " + korb.describe());
        check(korb.type == com.dan.beachchair.BeachChair.Type.OSTSEE && Math.abs(korb.seatHeight - com.dan.timmendorf.scene.Bauwerke.KORB_SITZ) < 1e-9,
                f("Ostseekorb, Sitz %.2f m wie die Badegäste ihn nehmen", korb.seatHeight));
        java.util.List<com.dan.timmendorf.scene.Bauwerke.Korb> ks = w.koerbe;
        if (ks.isEmpty()) {   // auf der Bühne ohne Strand: eine Reihe zur Probe
            ks = new java.util.ArrayList<>();
            for (int i = 0; i < 40; i++) ks.add(new com.dan.timmendorf.scene.Bauwerke.Korb(i * 3.2, 1.5, 100, 45, i));
        }
        java.util.List<com.dan.beachchair.BeachChair.Chair> st = com.dan.timmendorf.scene.Bauwerke.stuehle(ks, korb);
        boolean gleich = st.size() == ks.size();
        for (int i = 0; i < st.size() && gleich; i++) gleich = st.get(i).x == ks.get(i).x() && st.get(i).headingDeg == ks.get(i).richtung();
        check(gleich, f("%,d Körbe an den Plätzen aus Phase 9, Blick zur See", st.size()));
        double[] anteil = new double[3];
        double[] std = {12, 23, 12};
        java.util.BitSet bes = new java.util.BitSet();
        for (int i = 0; i < st.size(); i += 5) bes.set(i);
        for (int k = 0; k < 3; k++) {
            double offen = com.dan.timmendorf.scene.Bauwerke.offen(std[k], k == 2, 5);
            int n = 0, gastZu = 0;
            for (int i = 0; i < st.size(); i++) {
                com.dan.beachchair.BeachChair.Chair c = st.get(i);
                boolean gast = k == 0 && bes.get(i);
                korb.pose(c, gast ? 1 : offen, com.dan.timmendorf.scene.Bauwerke.bequem(std[k]));
                if (!c.closed) n++;
                if (gast && c.closed) gastZu++;
            }
            anteil[k] = n / (double) Math.max(1, st.size());
            if (k == 0) check(gastZu == 0, "Körbe mit Gästen sind offen");
        }
        check(anteil[0] > 0.8 && anteil[1] == 0 && anteil[2] < anteil[0] * 0.75,
                f("offen: mittags %.0f %%, nachts %.0f %%, mittags im Regen %.0f %%", anteil[0] * 100, anteil[1] * 100, anteil[2] * 100));
        double[] h = korb.mesh(com.dan.beachchair.BeachChair.Lod.NEAR).bounds();
        check(Math.abs(h[4] - korb.height) < 0.01, f("Korb %.2f m hoch, nah %d, fern %d Dreiecke", h[4], korb.mesh(com.dan.beachchair.BeachChair.Lod.NEAR).triangles(),
                korb.mesh(com.dan.beachchair.BeachChair.Lod.FAR).triangles()));

        // seit Phase 21: Relief und Korn des Strands aus dem Asset (com.dan.beach)
        com.dan.beach.Beach bs = com.dan.beach.Beach.ostsee();
        System.out.println("    " + bs.describe());
        double hz = bs.amplitude(1, 1), hn = bs.amplitude(0, 0);
        check(com.dan.beach.Beach.maps().n >= 512 && hz > 0.015 && hz < 0.04 && hn < hz * 0.2,
                f("Strand aus dem Asset: zertreten %.1f cm, nass und ruhig %.1f cm; tiefe Sonne im Relief ×%.2f", hz * 100, hn * 100,
                        com.dan.beach.Beach.sunFactor(bs.slopeRms(1, 1), Math.sin(Math.toRadians(8)))));

        // Flugsand: auf trockenem Strand bei Sturm auflandig, nass nach Regen nicht, bei Schnee nicht
        double[] pk = null;
        for (com.dan.timmendorf.scene.Bauwerke.Korb k : w.koerbe) {
            double a = Math.toRadians(k.richtung()), x = k.x() + Math.sin(a) * 4, z = k.z() - Math.cos(a) * 4;
            if (t.artAt(x, z) == Terrain.STRAND && t.height(x, z) > 1.2) { pk = new double[]{x, t.height(x, z) + 1.6, z, k.richtung()}; break; }
        }
        if (pk == null) { System.out.println("    (kein trockener Strand im Gelände: Flugsand nur mit daten/gelaende geprüft)"); return; }
        com.dan.timmendorf.scene.Sand sand = new com.dan.timmendorf.scene.Sand(t::height, t::artAt, () -> 0.0);
        sand.dune.quality = com.dan.dune.Dune.Quality.MEDIUM;
        sand.koerner().budget = sand.dune.grainBudget();
        long t0 = System.nanoTime();
        sand.bereit(pk, 22, pk[3], 2);
        double ms = (System.nanoTime() - t0) / 1e6;
        double q = sand.flussBei(pk[0], pk[2]);
        System.out.println("    " + sand.zeile(pk[0], pk[2]));
        check(q > 1e-4 && sand.koerner().count > 100, f("Bft 9 auflandig auf trockenem Strand: %.0f g/(m·s), %d Körner (Wind und Feld %.1f s)", q * 1000, sand.koerner().count, ms / 1000));
        sand.regen = 0.06;
        sand.baueFeld(pk[0], pk[2]);
        double qn = sand.flussBei(pk[0], pk[2]);
        sand.regen = 0;
        sand.schnee = 0.05;
        sand.baueFeld(pk[0], pk[2]);
        double qs = sand.flussBei(pk[0], pk[2]);
        check(qn == 0 && qs == 0, f("nach Regen %.0f, bei Schnee %.0f g/(m·s)", qn * 1000, qs * 1000));
        sand.schnee = 0;
        sand.baueFeld(pk[0], pk[2]);
        double qw = 0;
        com.dan.dune.FluxField ff = sand.feld();
        for (int i = -40; i <= 40; i += 2) {
            double a = Math.toRadians(pk[3]), x = pk[0] + Math.sin(a) * i, z = pk[2] - Math.cos(a) * i;
            if (t.height(x, z) < 0.05) qw = Math.max(qw, Math.max(0, ff.fluxAt(x, z)));
        }
        check(qw == 0, "unter dem Pegel fliegt nichts");
    }

    /** Seit Phase 10a: Menschen und Verkehr zu verschiedenen Zeiten (eigene Uhr, eigene Verdrahtung). */
    static void menschen(Terrain t) {
        teil("Menschen und Verkehr (Phase 10a): route, figure");
        SceneClock c = new SceneClock(java.time.LocalDate.of(2026, 7, 25), 15.5);
        Wiring w = new Wiring(c);
        w.setTerrain(t);
        com.dan.timmendorf.scene.Menschen m = w.menschen;
        com.dan.timmendorf.scene.Verkehr v = w.verkehr;
        java.util.Set<String> namen = new java.util.HashSet<>();
        boolean blick = false;
        for (com.dan.route.Crowd.Goal g : m.crowd.goals) { namen.add(g.name()); if (g.name().equals("Schleife")) blick = !Double.isNaN(g.lookX()); }
        boolean fuss = w.wege.nodeCount() > 1000;
        check(!fuss || (namen.containsAll(java.util.List.of("Seebrücke", "Schleife", "Wolkenlos", "Niendorfer Hafen", "Promenade")) && blick),
                f("Ziele im Fußnetz: %d (%s), auf der Schleife mit Blick zur Regatta", m.crowd.goals.size(), String.join(", ", new java.util.TreeSet<>(namen))));
        long t0 = System.nanoTime();
        for (int i = 0; i < 6000; i++) { m.step(0.1); v.step(0.1); }
        double ms = (System.nanoTime() - t0) / 1e6 / 6000;
        int[] mv = m.crowd.moving();
        boolean finit = true, amBoden = true;
        for (com.dan.figure.Person p : m.alle()) {
            finit &= Double.isFinite(p.x) && Double.isFinite(p.y) && Double.isFinite(p.z);
            if (p.sit == 0 && p.lie == 0 && p.wet == 0) { double g = t.height(p.x, p.z); amBoden &= p.y > g - 1.0 && p.y < Math.max(g, 0) + 4; }   // auf Seebrücken über dem Wasser
        }
        check(!fuss || (m.crowd.count() > 800 && mv[0] > 500 && mv[1] < mv[0] / 10 && m.crowd.arrivals() > 100),
                f("Juli 15:30, nach 10 min: %s", m.describe()));
        check(finit && amBoden && m.zahl[0] > 1000 && m.zahl[1] > 300 && m.zahl[3] > 5, f("Figuren: %,d, alle mit Ort, die Stehenden auf dem Grund oder einem Deck; %.2f ms je Schritt (0,1 s)", m.alle().size(), ms));
        check(ms < 8, f("ein Schritt der Menschen und Autos in %.2f ms", ms));
        int[] am = v.autos.moving();
        boolean strassen = v.autos.g.nodeCount() > 1000;
        check(!strassen || (am[0] > 30 && v.busse.size() == 2 && v.busse.get(0).stops.length >= 5 && !v.busse(12 * 3600).isEmpty() && v.busse(3 * 3600).isEmpty()),
                f("%s", v.describe(15.5 * 3600)));
        c.set(java.time.LocalDate.of(2026, 7, 26), 3.0);
        for (int i = 0; i < 3000; i++) m.step(0.1);
        int nachtGaenger = m.crowd.count(), nachtStrand = m.zahl[0] + m.zahl[1] + m.zahl[2];
        c.set(java.time.LocalDate.of(2027, 1, 16), 15.0);
        for (int i = 0; i < 3000; i++) m.step(0.1);
        int winter = m.crowd.count(), winterStrand = m.zahl[0] + m.zahl[1] + m.zahl[2];
        check(nachtStrand == 0 && winterStrand == 0 && nachtGaenger < 100 && (!fuss || winter < 300),
                f("nachts um 3 Uhr %d auf den Wegen und niemand am Strand; im Januar um 15 Uhr %d auf den Wegen, Strand leer", nachtGaenger, winter));
    }

    /** Seit Phase 12: der Abend des 1. August 2026 – Lichter, Mond, Feuerwerk nach der Uhr, Zuschauer. */
    static void abend(Terrain t) {
        teil("Der Abend (Phase 12): lamp, firework, Zuschauer, Mondaufgang");
        java.time.LocalDate tag = java.time.LocalDate.of(2026, 8, 1);
        SceneClock c = new SceneClock(tag, 14.0);
        Wiring w = new Wiring(c);
        w.setTerrain(t);
        com.dan.timmendorf.scene.Baunetz n = w.bauwerke.netz(t::height, w.fuss);
        w.lichter.laternen(n.lichter);
        com.dan.timmendorf.scene.Lichter li = w.lichter;
        double luxTag = Wiring.umgebungsLux(c.sun()[0]);
        li.step(1, luxTag, w.stunde());
        boolean tagAus = !li.an();
        c.set(tag, 22.5);
        li.step(1, Wiring.umgebungsLux(c.sun()[0]), w.stunde());
        boolean nachtAn = li.an();
        boolean strassen = w.verkehr != null && w.verkehr.autos.g.nodeCount() > 1000;
        check(li.laternen == n.lichter.size() && li.laternen > 100 && (!strassen || li.strassen > 500) && tagAus && nachtAn,
                f("%s; um 14 Uhr aus, um 22:30 an", li.describe()));
        double gl = li.fest.groundLux(-37, t.height(-37, 15), 15), ef = com.dan.timmendorf.scene.Lichter.fenster(21), en = com.dan.timmendorf.scene.Lichter.fenster(3);
        check(ef > 0.4 && en < 0.1, f("Fenster: um 21 Uhr %.0f %% hell, um 3 Uhr %.0f %%; am Landende der Seebrücke %.1f lx", ef * 100, en * 100, gl));
        // Mond
        com.dan.sky.Sky sky = new com.dan.sky.Sky();
        sky.latDeg = c.latDeg();
        sky.lonDeg = c.lonDeg();
        sky.eyeHeight = 2;
        c.set(tag, 22 + 20 / 60.0);
        sky.epochMillis = c.epochMillis();
        com.dan.sky.Sky.State st = sky.step(0);
        check(st.moonAlt > 1 && st.moonAlt < 8 && st.moonAz > 85 && st.moonAz < 115 && st.moonFraction > 0.85,
                f("22:20: Mond %.1f° über dem Horizont im Azimut %.0f° (über der Bucht), %.0f %% beleuchtet; Sonne %.1f°", st.moonAlt, st.moonAz, st.moonFraction * 100, st.sunAlt));
        // Feuerwerk nach der Uhr
        c.set(tag, 22 + 20 / 60.0);
        double a0 = w.andrang();
        c.set(tag, 22.70);
        w.feuerwerkSchritt();
        boolean vorher = w.firework == null;
        c.set(tag, 22.75 + 125 / 3600.0);
        long t0 = System.nanoTime();
        for (int i = 0; i < 20 && w.firework != null && Math.abs(w.firework.time() - w.feuerwerkZeit()) > 0.02; i++) w.feuerwerkSchritt();
        if (w.firework == null) w.feuerwerkSchritt();
        for (int i = 0; i < 20 && w.firework != null && Math.abs(w.firework.time() - w.feuerwerkZeit()) > 0.02; i++) w.feuerwerkSchritt();
        double ms = (System.nanoTime() - t0) / 1e6;
        com.dan.firework.Firework f = w.firework;
        boolean laeuft = f != null && Math.abs(f.time() - 125) < 0.1 && !f.stars().isEmpty();
        double a1 = w.andrang();
        com.dan.lamp.Lamps b = li.bild(f, com.dan.lamp.Lamps.Quality.HIGH);
        int bomben = b.size() - li.fest.size();
        check(vorher && laeuft && bomben > 0, f("22:42 noch nichts; 22:47:05 läuft es seit %.1f s (%,d Sterne, %d Bomben als Licht, eingeholt in %.0f ms)",
                f == null ? 0 : f.time(), f == null ? 0 : f.stars().size(), bomben, ms));
        double eStrand = 0;
        if (f != null) { double[] p = w.ponton; double[] e = f.illuminance(p[0] - 300, 1.5, p[1] + 300, 0, 0, 0); eStrand = e[0] + e[1] + e[2]; }
        c.set(tag, 22.75 + 20 / 3600.0);
        w.feuerwerkSchritt();
        boolean zurueck = w.firework != null && w.firework.time() <= 20.05;
        c.set(tag, 23.10);
        w.feuerwerkSchritt();
        boolean weg = w.firework == null;
        check(zurueck && weg, "springt die Uhr zurück, beginnt es von vorn; um 23:06 ist es vorbei");
        check(a0 > 0.4 && a0 < 0.8 && a1 == 1 && w.andrang() == 0 && eStrand > 0.05,
                f("Andrang: um 22:20 %.0f %%, während des Feuerwerks %.0f %%, danach 0; Licht der Bomben am Strand %.2f lx", a0 * 100, a1 * 100, eStrand));
        // Zuschauer
        com.dan.timmendorf.scene.Menschen m = w.menschen;
        c.set(tag, 22.0);
        for (int i = 0; i < 600 * 2; i++) {   // 22:00 bis 22:40 in Schritten von 2 s
            c.setEpochMillis(c.epochMillis() + 2000);
            m.zuschauer = w.andrang();
            m.step(2.0);
        }
        int[] z = m.feuerwerk();
        check(z[0] < 30 || (z[1] > 200 && z[2] > 100), f("um 22:40: %s", m.describe()));
        c.set(tag, 23.0);
        for (int i = 0; i < 300; i++) { m.zuschauer = w.andrang(); m.step(2.0); c.setEpochMillis(c.epochMillis() + 2000); }
        int[] z2 = m.feuerwerk();
        check(z2[1] < z[1] / 4 + 5 && z2[2] == 0, f("um 23:10 schauen noch %d, am Strand %d", z2[1], z2[2]));
    }

    /** Seit Phase 13: Wetterregie mit den Lagen der Bucht, Seegang, Regen, Nebel, Gewitter, das Jahr, die Tiere. */
    static void wetter(Terrain t) {
        teil("Wetter, Jahr, Leben (Phase 13): weather, rain, fog, lightning, season, flock, seal");
        SceneClock c = new SceneClock(java.time.LocalDate.of(2026, 7, 14), 15.0);
        Wiring w = new Wiring(c);
        w.setTerrain(t);
        com.dan.timmendorf.wiring.Wetterregie rg = w.regie;
        check(rg.lage() == com.dan.timmendorf.wiring.Wetterregie.Lage.SOMMERABEND && Math.abs(w.windSpeed - 5.5) < 0.3 && Math.abs(w.windFromDeg - 45) < 3,
                f("Vorgabe wie bis Phase 12: %s", w.wetterZeile()));
        rg.lage(com.dan.timmendorf.wiring.Wetterregie.Lage.LANDREGEN);
        boolean regen = w.regen > 1 && w.oktas > 7 && w.visibilityKm < 12;
        rg.lage(com.dan.timmendorf.wiring.Wetterregie.Lage.SEENEBEL);
        boolean nebel = w.nebel > 0.5 && w.windSpeed < 4;
        rg.lage(com.dan.timmendorf.wiring.Wetterregie.Lage.GEWITTER);
        boolean gewitter = w.gewitter >= 2 && w.regen > 5;
        rg.lage(com.dan.timmendorf.wiring.Wetterregie.Lage.WINTERTAG);
        boolean frost = w.temperatur < 0;
        check(regen && nebel && gewitter && frost, "Lagen: Landregen nass und grau, Seenebel still, Gewitter mit Starkregen, Wintertag mit Frost");
        double[] ne = com.dan.timmendorf.wiring.Wetterregie.seegang(11, 50), sw = com.dan.timmendorf.wiring.Wetterregie.seegang(11, 230),
                so = com.dan.timmendorf.wiring.Wetterregie.seegang(5.5, 45);
        check(ne[0] > 0.8 && ne[0] < 1.6 && sw[0] < 0.35 && Math.abs(so[0] - 0.56) < 0.15,
                f("Seegang nach Streichlänge: 11 m/s aus Nordost %.2f m / %.1f s, vom Land %.2f m, Sommerabend %.2f m / %.1f s", ne[0], ne[1], sw[0], so[0], so[1]));
        rg.frei();
        double t0 = c.epochMillis();
        java.util.Set<String> phasen = new java.util.TreeSet<>();
        for (int i = 0; i < 24 * 20; i++) {   // zehn Tage in Schritten von 30 min
            c.setEpochMillis(t0 + i * 1_800_000.0);
            rg.schritt(1800);
            phasen.add(rg.snapshot().phase().label);
        }
        check(phasen.size() >= 3, "freie Regie über 10 Tage: " + String.join(", ", phasen));
        // das Jahr
        java.time.LocalDate[] win = com.dan.timmendorf.tools.Wetterbilder.winter();
        rg.lage(com.dan.timmendorf.wiring.Wetterregie.Lage.WINTERTAG);
        c.set(win[0], 11.0);
        com.dan.season.Year.State ws = w.jahr.step();
        double schnee = w.jahr.schnee();
        c.set(win[1], 13.0);
        w.jahr.step();
        double eis = w.jahr.eis();
        rg.lage(com.dan.timmendorf.wiring.Wetterregie.Lage.SOMMERABEND);
        c.set(java.time.LocalDate.of(2026, 7, 25), 15.0);
        w.jahr.step();
        boolean sommer = w.jahr.schnee() == 0 && w.jahr.eis() == 0;
        check(schnee > 0.03 && eis > 0.02 && sommer, f("Jahr (season): am %s %.0f cm Schnee, am %s %.0f cm Eis in der Bucht; im Juli nichts", win[0], schnee * 100, win[1], eis * 100));
        // Regen, Nebel, Gewitter auf dem Prozessor
        com.dan.timmendorf.gl.Unwetter u = new com.dan.timmendorf.gl.Unwetter();
        com.dan.cloud.Clouds cl = com.dan.cloud.Clouds.fair();
        double[] eye = {0, 2, 120};
        rg.lage(com.dan.timmendorf.wiring.Wetterregie.Lage.SEENEBEL);
        for (int i = 0; i < 300; i++) u.schritt(0.1, w, cl, false, eye, t::height);
        double sichtNebel = u.fog.visibilityAt(eye[0], eye[1], eye[2]);
        boolean fog = u.fog.snapshot().any() && sichtNebel < 2000;
        rg.lage(com.dan.timmendorf.wiring.Wetterregie.Lage.GEWITTER);
        w.feed(cl);
        for (int i = 0; i < 3000; i++) u.schritt(0.1, w, cl, false, eye, t::height);
        check(fog && u.rain.rate > 5 && u.blitz.flashes() > 0, f("Seenebel: Sicht %.0f m; Gewitter: %.0f mm/h, %d Blitze in 5 min, %d Erdblitze", sichtNebel,
                u.rain.rate, u.blitz.flashes(), u.blitz.groundFlashes()));
        // Tiere
        com.dan.timmendorf.scene.Leben lb = new com.dan.timmendorf.scene.Leben(t::height);
        int[] n = new int[com.dan.flock.Species.values().length];
        for (int i = 0; i < 1200; i++) lb.schritt(0.05, new double[]{2080, 4, 700}, 2080, 800, 4, 45, 20_000, 30, java.time.LocalDate.of(2026, 7, 25), 15, 0, 0, 0.4);
        boolean wasser = true;
        for (com.dan.flock.Flock.Gull g : lb.vogel.gulls()) {
            n[g.species.ordinal()]++;
            if (g.state == com.dan.flock.Flock.State.SWIM) wasser &= t.height(g.pos[0], g.pos[2]) < -0.3;
        }
        check(n[com.dan.flock.Species.MOEWE.ordinal()] >= 8 && n[com.dan.flock.Species.SCHWAN.ordinal()] > 0 && wasser, f("%s; wer schwimmt, schwimmt im Wasser", lb.describe()));
    }

    /** Seit Phase 14: die Sturmflut 2023 – Fahrplan gegen die belegten Pegel, Verdrahtung, Maske der See, Abbrüche, Brecher. */
    static void sturmflut(Terrain t) {
        teil("Sturmflut 2023 (Phase 14): tide, spray, cliff");
        com.dan.timmendorf.scene.Sturmflut S = null;
        double max = 0, hmax = 0;
        for (double h = 0; h <= com.dan.timmendorf.scene.Sturmflut.STUNDEN; h += 0.1) {
            double p = com.dan.timmendorf.scene.Sturmflut.pegel(h);
            if (p > max) { max = p; hmax = h; }
        }
        double d125 = com.dan.timmendorf.scene.Sturmflut.stundenUeber(1.25), d150 = com.dan.timmendorf.scene.Sturmflut.stundenUeber(1.50),
                d175 = com.dan.timmendorf.scene.Sturmflut.stundenUeber(1.75);
        check(Math.abs(max - 1.77) < 0.005 && Math.abs(d125 - 26) < 2 && Math.abs(d150 - 15) < 2 && Math.abs(d175 - 3) < 1.5,
                f("Fahrplan Travemünde: Scheitel %.2f m NHN am %s (belegt 1,77 m, später Abend des 20.); über 1,25 / 1,50 / 1,75 m %.0f / %.0f / %.0f h (Lübeck-Bauhof belegt 26 / 15 / 3 h)",
                        max, com.dan.timmendorf.scene.Sturmflut.zeit(hmax), d125, d150, d175));
        SceneClock c = new SceneClock(java.time.LocalDate.of(2023, 10, 20), 23.0);
        Wiring w = new Wiring(c);
        w.setTerrain(t);
        w.sturmflutSchritt();
        double pg = w.pegel, wd = w.windFromDeg, ws = w.windSpeed, hs = w.seeHs;
        c.set(java.time.LocalDate.of(2023, 10, 24), 12.0);
        w.sturmflutSchritt();
        check(Math.abs(pg - 1.77) < 0.01 && wd > 60 && wd < 110 && ws > 18 && hs > 1.5 && w.pegel == 0 && Double.isNaN(w.sturmflutStunde),
                f("Verdrahtung am Scheitel: Pegel %.2f m, Wind %.0f m/s aus %03.0f°, Seegang %.2f m; danach Pegel wieder %.2f m", pg, ws, wd, hs, w.pegel));
        byte[] m0 = com.dan.timmendorf.gl.Meer.binnen(t), m1 = com.dan.timmendorf.gl.Meer.binnen(t, 1.92);
        int see0 = 0, see1 = 0, nass = 0;
        for (int k = 0; k < m0.length; k++) { if (m0[k] == 0) see0++; if (m1[k] == 0) see1++; }
        for (int i = -300; i <= 300; i += 10) for (int k = -100; k <= 100; k += 10) { double g = t.height(i, k); if (g > 0.3 && g < 1.77) nass++; }
        check(see1 > see0 && (!t.measured() || nass > 5), f("Maske der See bei 1,92 m: %,d Zellen verbunden statt %,d; um die Seebrücke %d Proben zwischen 0,3 und 1,77 m (Strand und Promenade unter Wasser)",
                see1, see0, nass));
        com.dan.timmendorf.scene.Abbruch ab = new com.dan.timmendorf.scene.Abbruch(t::height, com.dan.timmendorf.gl.Renderer.KLIFF_X0, com.dan.timmendorf.gl.Renderer.KLIFF_Z0, 2.5,
                (int) Math.round(com.dan.timmendorf.gl.Renderer.KLIFF_ZELLE * com.dan.timmendorf.gl.Renderer.KLIFF_N / 2.5), 2023);
        boolean vorher = !ab.stand(-1) && ab.abbrueche() == 0;
        ab.stand(com.dan.timmendorf.scene.Sturmflut.STUNDEN);
        int n = ab.abbrueche();
        double vol = ab.volumen(), rueck = ab.groessterRueckgang();
        float korrMin = 0, korrMax = 0;
        for (float v : ab.korr) { korrMin = Math.min(korrMin, v); korrMax = Math.max(korrMax, v); }
        ab.stand(-1);
        boolean zurueck = ab.abbrueche() == 0;
        check(!t.measured() || (vorher && n >= 5 && vol > 100 && rueck <= 5 && korrMin < -2 && korrMax > 0.3 && zurueck),
                f("Brodtener Ufer: %d Abbrüche während der Sturmflut, %,.0f m³, Kante bis %.1f m zurück, Höhe %.1f bis %+.1f m; vor der Sturmflut unverändert", n, vol, rueck, korrMin, korrMax));
        com.dan.spray.Spray sp = com.dan.spray.Spray.gale();
        sp.sea = new com.dan.spray.Sea() {
            @Override public double height(double x, double z) { return 1.77; }
            @Override public double[] velocity(double x, double z) { return new double[3]; }
            @Override public double level() { return 1.77; }
        };
        sp.windSpeed = 22;
        sp.windFromDeg = 85;
        sp.waveHeight = 2.4;
        sp.impact(100, 1.77, -40, 20, 9);
        for (int i = 0; i < 20; i++) sp.step(0.05);
        check(!sp.parcels().isEmpty(), f("Brecher (spray): ein Schlag an einem Pfahl gibt %d Bündel Gischt", sp.parcels().size()));
    }

    /** Seit Phase 15: der Klang der Szene ohne Lautsprecher (Klang offline). */
    static void klang(Terrain t) {
        teil("Klang (Phase 15): soundscape mit Bucht, Hafen, Regatta, Feuerwerk, Promenade");
        SceneClock c = new SceneClock(java.time.LocalDate.of(2026, 7, 25), 10.0);
        c.lapse = 1;
        Renderer r = new Renderer(c, t);
        com.dan.timmendorf.gl.Klang k = r.klang;
        k.offline = true;
        k.s.autoGain = false;
        k.s.quality = com.dan.soundscape.Soundscape.Quality.MEDIUM;
        r.wiring.feuerwerkAbends = false;
        // am Spülsaum: die Brandung der Ostsee als Linie
        r.placeView(com.dan.timmendorf.camera.Ansichten.byName("Spülsaum"));
        lauf(r, 6);
        double nl = k.s.surfLineNearest(), lf = k.s.surfLineDb();
        check(k.stuecke > 100 && nl < 80 && lf > 45 && lf < 78, f("Brandung am Spülsaum: Linie aus %d Stücken, nächstes %.0f m, %.0f dB am Ohr (%.0f W/m vergehen), %d Schwälle",
                k.stuecke, nl, lf, k.wattsHier, k.schwaelle));
        check(k.s.flaps.size() >= 1 && k.s.rigs.size() == 2 && k.s.hums.size() == r.wiring.flotte.faehren.size(),
                f("Quellen: %d Flaggen (Strand und DLRG), %d Häfen mit Masten, %d Diesel der Fähren", k.s.flaps.size(), k.s.rigs.size(), k.s.hums.size()));
        check(k.s.footing == 2 || !t.measured(), "Belag am Spülsaum: Sand (" + k.s.footing + ")");
        // im Niendorfer Hafen bei Westwind: die Fallen schlagen
        r.wiring.regie.lage(com.dan.timmendorf.wiring.Wetterregie.Lage.WESTWIND);
        r.placeView(com.dan.timmendorf.camera.Ansichten.byName("Niendorfer Hafen"));
        long f0 = k.s.rigSlaps();
        lauf(r, 10);
        long fallen = k.s.rigSlaps() - f0;
        check(fallen > 30, f("Niendorfer Hafen bei %.0f m/s: %d Schläge der Fallen in 10 s an %d Masten", r.wiring.windSpeed, fallen, com.dan.timmendorf.gl.Klang.MASTEN_NIENDORF));
        r.wiring.regie.lage(com.dan.timmendorf.wiring.Wetterregie.Lage.SOMMERABEND);
        // auf dem Deck der Seebrücke: Holz unter den Füßen
        r.placeView(com.dan.timmendorf.camera.Ansichten.byName("Schleife"));
        r.camera.place(r.camera.eye[0], com.dan.timmendorf.scene.Bauwerke.DECK + 1.7, r.camera.eye[2], 230, 4);
        lauf(r, 0.5);
        check(k.s.footing == 1 || !t.measured(), "Belag auf der Schleife: Holzdeck (" + k.s.footing + ")");
        // Signale der Regatta und das Fährhorn
        int s0 = k.schuesse, h0 = k.hoerner;
        r.wiring.flotte.signale.onSignal("Start", 0);
        r.wiring.flotte.hupe.horn(r.wiring.flotte.faehren.get(0), 5);
        check(k.schuesse == s0 + 1 && k.hoerner == h0 + 1, "Startschuss und Fährhorn kommen beim Klang an");
        // das Feuerwerk vom Strand: Abschüsse, Zerlegungen, Laufzeit
        c.set(r.wiring.feuerwerkTag, r.wiring.feuerwerkUhr);
        r.zumFeuerwerk();
        r.wiring.feuerwerk();
        int a0 = k.abschuesse, z0 = k.zerlegungen;
        lauf(r, 30);
        double[] p = r.wiring.ponton;
        double d = Math.hypot(Math.hypot(p[0] - r.camera.eye[0], p[1] - r.camera.eye[2]), 90), dt = d / k.s.soundSpeed();
        check(k.abschuesse > a0 && k.zerlegungen > z0 && dt > 0.8 && dt < 3, f("Feuerwerk in 30 s: %d Abschüsse, %d Zerlegungen, %d Knistern; vom Strand %.0f m, der Knall kommt %.1f s nach dem Bild",
                k.abschuesse - a0, k.zerlegungen - z0, k.knistern, d, dt));
        // Rechenzeit
        long t0 = System.nanoTime();
        lauf(r, 5);
        double ms = (System.nanoTime() - t0) / 1e6 / 5;
        System.out.println("    " + k.describe());
        check(k.s.msPerSecond() < 250, f("Rechenzeit des Klangs (32 kHz): %.0f ms je s Ton; Szene und Klang zusammen %.0f ms je s", k.s.msPerSecond(), ms));
        k.dispose();
    }

    /** sec Sekunden Szene mit Klang in Schritten von 1/30 s (Physik ohne Faden). */
    static void lauf(Renderer r, double sec) {
        for (double x = 0; x < sec; x += 1 / 30.0) {
            r.step(1 / 30.0);
            r.physik.drain();
            r.klang.s.render(1 / 30.0);
        }
    }

    /** Seit Phase 16: die Regiebücher lesen, jede Ansicht, Fahrt und Lage finden, „Mein Weg zum Strand“ ohne Bild bis zum Ende. */
    /** Seit Phase 17: die Datenseite ohne Datenbank (Skripte, Startdaten, Zustand hin und zurück, Dateien wie Oracle). */
    static void datenseite(Terrain t) throws Exception {
        teil("Datenseite (Phase 17): Skripte, Startdaten, Zustand, Dateien");
        java.io.File dir = com.dan.timmendorf.data.Verbindung.skripte();
        java.util.List<com.dan.store.Skript.Anweisung> t01 = com.dan.store.Skript.zerlegen(java.nio.file.Files.readString(new java.io.File(dir, "01_tabellen.sql").toPath()));
        int tab = 0, trg = 0;
        for (var a : t01) { String o = com.dan.store.Skript.objekt(a.sql()); if (o != null && o.startsWith("TABLE")) tab++; if (o != null && o.startsWith("TRIGGER")) trg++; }
        check(tab == 31 && trg == 30, f("db/sql/01_tabellen.sql: %d Tabellen, %d Trigger, %d Spalten (Teil 2)", tab, trg, com.dan.store.Skript.spalten(t01).size()));
        java.util.Map<String, Integer> soll = com.dan.timmendorf.data.Verbindung.zahlen(dir), ist = com.dan.timmendorf.data.StartdatenSql.zahlen(
                com.dan.timmendorf.data.StartdatenSql.Alles.aus(t.measured() ? t : null));
        boolean gleich = true;
        for (var e : ist.entrySet()) if (!e.getValue().equals(soll.get(e.getKey()))) { gleich = false; System.out.println("    " + e.getKey() + ": Skript " + soll.get(e.getKey()) + ", Code " + e.getValue()); }
        check(gleich, "04_startdaten.sql passt zum Code (sonst ant startdaten): " + com.dan.timmendorf.data.Startdaten.zusammenfassung());
        SceneClock c = new SceneClock(java.time.LocalDate.of(2026, 8, 1), 14.2);
        Renderer r = new Renderer(c, t);
        r.klang.offline = true;
        r.wiring.flotte.regatta.start(300);
        for (int i = 0; i < 4000; i++) { r.step(0.1); r.physik.drain(); }
        var z1 = com.dan.timmendorf.data.Zustand.erfassen(r);
        String text = com.dan.store.Felder.alsText(z1, "Prüfung");
        Renderer r2 = new Renderer(new SceneClock(java.time.LocalDate.of(2026, 1, 15), 9), t);
        r2.klang.offline = true;
        java.util.List<String> fehl = com.dan.timmendorf.data.Zustand.anwenden(r2, com.dan.store.Felder.ausText(text));
        r2.step(0.01);
        r2.physik.drain();
        var z2 = com.dan.timmendorf.data.Zustand.erfassen(r2);
        int anders = 0;
        for (var e : z1.entrySet()) {
            if (e.getKey().startsWith("uhr.") || e.getKey().startsWith("kamera.") || e.getKey().startsWith("regatta.") || e.getKey().startsWith("sky.")) continue;
            double va = zahl(e.getValue()), vb = zahl(z2.get(e.getKey()));
            if (!Double.isNaN(va) && Math.abs(va - vb) <= 1e-6 * Math.max(1, Math.abs(va))) continue;   // nur Rundung im Text
            if (!e.getValue().equals(z2.get(e.getKey()))) { anders++; if (anders <= 5) System.out.println("    anders: " + e.getKey() + " = " + e.getValue() + " → " + z2.get(e.getKey())); }
        }
        var g1 = r.wiring.flotte.regatta;
        var g2 = r2.wiring.flotte.regatta;
        boolean regatta = g1.race() == g2.race() && Math.abs(g1.toGun() - g2.toGun()) < 0.5 && g1.entries.size() == g2.entries.size()
                && Math.hypot(g1.entries.get(3).boat.x - g2.entries.get(3).boat.x, g1.entries.get(3).boat.z - g2.entries.get(3).boat.z) < 2 && g1.entries.get(3).leg == g2.entries.get(3).leg;
        check(fehl.isEmpty() && anders == 0 && Math.abs(r2.clock.epochMillis() - r.clock.epochMillis()) < 1000 && regatta,
                f("Zustand mit %d Werten hin und zurück (%d anders, %d nicht hergestellt); Wettfahrt %d bei %.0f s nach dem Start, Boot 4 im %s", z1.size(), anders,
                        fehl.size(), g2.race(), -g2.toGun(), g2.entries.get(3).leg));
        java.io.File tmp = java.nio.file.Files.createTempDirectory("timmendorf_datei").toFile();
        var ds = new com.dan.timmendorf.data.DateiSpeicher(tmp);
        long sid = ds.sitzungBeginnen("Prüfung", "", "MITTEL");
        ds.sitzungSichern(sid, new com.dan.store.Ablage.Sicherung(text, com.dan.timmendorf.data.Zustand.spalten(z1)));
        var l = ds.letzteSitzung(-1);
        check(l != null && com.dan.store.Felder.ausText(l.zustand()).equals(z1) && l.szeneMs() != null, "Ohne Datenbank: Sitzung mit Zustand in " + tmp.getName() + " und zurück");
        var pf = com.dan.store.Felder.lesen(com.dan.ocean.Ocean.gale());
        ds.speichern(new com.dan.timmendorf.data.Daten.PaketPreset("com.dan.ocean", "Ocean", "~Prüfung Böe", "Prüfung", com.dan.timmendorf.data.Daten.EIGEN, pf));
        var pr = ds.presets("com.dan.ocean", "Ocean").stream().filter(x -> x.name().equals("~Prüfung Böe")).findFirst().orElse(null);
        check(pr != null && pr.felder().equals(pf) && "Prüfung".equals(pr.beschreibung()), "Ohne Datenbank: eigenes Preset Ocean mit " + pf.size() + " Feldern, Name mit Umlaut zurück");
        var ab = com.dan.timmendorf.data.Chronik.abbruchFuer(1, r.abbruch.plan().isEmpty() ? new com.dan.timmendorf.scene.Abbruch.Ereignis(70, 4800, 900, 0.6, -0.8, 30, 2.5)
                : r.abbruch.plan().get(0), 1_697_580_000_000L, 100);
        ds.abbruch(ab, sid);
        var abz = ds.abbrueche(1).get(0);
        check(Math.abs(abz.flaeche().flaecheM2() - ab.flaeche().flaecheM2()) < 0.5 && abz.kante() != null, f("Abbruch als CSV mit WKT (%.0f m²) und Kante zurück", abz.flaeche().flaecheM2()));
        check(ds.stand().regiebuecher() == 5 && ds.bootKlassen().size() == com.dan.sail.Sail.RIGS.size(), "Stand ohne Datenbank: " + ds.stand().kurz());
        r.klang.dispose();
        r2.klang.dispose();
    }

    /** Seit Phase 17: Ohrenquallen unter der Seebrücke (com.dan.jelly) auf dem Gelände der Szene. */
    static void quallen(Terrain t) {
        teil("Ohrenquallen (Phase 17): Jahr, Seebrücke, Ufer");
        com.dan.jelly.Bloom b = com.dan.jelly.Bloom.summer();
        b.seed = 2026;
        b.bed = t::height;
        b.waterLevel = 0.0;
        b.dayOfYear = 237;
        b.waterTemp = 19;
        b.windSpeed = 3;
        b.windFromDeg = 240;
        double px = Site.SCHLEIFE.x() * 0.45, pz = Site.SCHLEIFE.z() * 0.45;   // Mitte der Seebrücke
        for (int i = 0; i < 300; i++) b.step(0.1, px, pz);
        int nah = b.countNear(px, pz, 15);
        check(nah > 5, f("25. August, Mitte der Seebrücke: %d Quallen in 15 m · %s", nah, b.describe()));
        boolean ueberLand = false;
        for (com.dan.jelly.Bloom.Jelly j : b.all())
            if (b.visible(j) && !j.stranded() && t.height(j.x, j.z) > b.waterLevel - 0.1) ueberLand = true;
        check(!ueberLand, "keine schwimmende Qualle über Strand oder Promenade");
        b.dayOfYear = 20;
        b.waterTemp = 3;
        b.step(0.1, px, pz);
        check(b.visibleCount() == 0, "im Januar keine");
        // auflandiger Wind am Strand: Nordost 10 m/s zwei Stunden, das Feld am Spülsaum neben der Seebrücke
        b.dayOfYear = 237;
        b.waterTemp = 19;
        b.windSpeed = 10;
        b.windFromDeg = 60;
        double sx = Site.SCHLEIFE.x() * 0.15, sz = Site.SCHLEIFE.z() * 0.15;
        for (int i = 0; i < 1440; i++) b.step(5, sx, sz);
        System.out.println(f("    Nordost 10 m/s, 2 h am Spülsaum: %d gestrandet, %d liegen noch", b.strandings(), b.strandedCount()));
        boolean amGrund = true;
        for (com.dan.jelly.Bloom.Jelly j : b.all()) if (b.visible(j) && j.stranded() && Math.abs(j.y - t.height(j.x, j.z)) > 0.05) amGrund = false;
        check(amGrund, "gestrandete liegen auf dem Sand");
    }

    static void regie(Terrain t) {
        teil("Regie und Kino (Phase 16): fünf Regiebücher, Ansichten, Fahrten, Kino");
        java.util.List<com.dan.timmendorf.scene.Regie.Book> bs = com.dan.timmendorf.scene.Regie.all();
        int fotos = 0, stich = 0;
        for (var b : bs) { fotos += b.fotos(); stich += b.cues().size(); }
        check(bs.size() == 5, f("%d Regiebücher gelesen (%s), %d Stichworte, %d Fotos", bs.size(), String.join(", ", bs.stream().map(com.dan.timmendorf.scene.Regie.Book::name).toList()), stich, fotos));
        SceneClock c = new SceneClock(java.time.LocalDate.of(2026, 7, 25), 16.0);
        c.lapse = 1;
        Renderer r = new Renderer(c, t);
        r.klang.offline = true;
        com.dan.timmendorf.gl.Regisseur rg = r.regisseur;
        rg.ohneBlatt = true;
        java.util.Set<String> fahrten = java.util.Set.of("seebruecke", "promenade", "drohne", "moewe", "optimist", "begleitboot", "regatta", "weg");
        java.util.List<String> fehlt = new java.util.ArrayList<>();
        for (var b : bs)
            for (var z : b.zeilen()) {
                String a = z.argumente();
                switch (z.befehl()) {
                    case "ansicht" -> { if (!rg.placeNamed(a)) fehlt.add(b.name() + ": Ansicht " + a); }
                    case "fahrt" -> { if (!fahrten.contains(com.dan.timmendorf.scene.Regie.plainName(a))) fehlt.add(b.name() + ": Fahrt " + a); }
                    case "lage" -> { if (com.dan.timmendorf.gl.Regisseur.lageNachName(a) == null) fehlt.add(b.name() + ": Lage " + a); }
                    default -> { }
                }
            }
        check(fehlt.isEmpty(), "jede Ansicht, Fahrt und Lage der Regiebücher gibt es" + (fehlt.isEmpty() ? "" : ": fehlt " + String.join("; ", fehlt)));
        var sf = rg.seebrueckenFahrt();
        double[] e = sf.pose(1);
        check(Math.hypot(e[0] - Site.SCHLEIFE.x(), e[2] - Site.SCHLEIFE.z()) < 12 && e[1] > com.dan.timmendorf.scene.Bauwerke.DECK + 1,
                f("Fahrt Seebrücke: %.0f m in %.0f s bis zur Schleife, Auge %.1f m NHN auf dem Deck", sf.length, sf.duration, e[1]));
        var df = rg.drohnenFahrt();
        check(df.length > 1500, f("Fahrt Drohne: %.0f m vor der Küste in %.0f s", df.length, df.duration));
        var wf = rg.wegFahrt();
        check(wf != null || !t.measured(), "Mein Weg zum Strand: " + rg.wegInfo);
        // „Mein Weg zum Strand“ ohne Bild bis zum Ende
        var weg = com.dan.timmendorf.scene.Regie.byName("Weg");
        rg.start(weg);
        int n = 0;
        while (rg.regie.running() && n < 20_000) { r.step(0.1); r.physik.drain(); rg.bildlos(); n++; }
        check(!rg.regie.running() && rg.regie.ausgewichen == 0 && rg.fotosVerlangt == weg.fotos(),
                f("„%s“ ohne Bild: %d Schritte, %.0f s gewartet, %d Fotos, %d Mal ausgewichen · %s", weg.name(), n, rg.regie.waitedTotal(), rg.fotosVerlangt, rg.regie.ausgewichen, rg.regie.describe()));
        // das Kino: Vorspann, Film, Abspann
        r.kino.beginnen("Timmendorfer Strand", "Prüfung", "ohne Bild");
        boolean vorspann = r.kino.laeuft();
        r.kino.beenden(java.util.List.of("Prüfung", "Ende"));
        int k = 0;
        while (r.kino.laeuft() && k < 2000) { r.kino.schritt(0.1); k++; }
        check(vorspann && !r.kino.laeuft(), f("Kino: Vorspann, Abspann, nach %.1f s vorbei", k * 0.1));
        r.klang.dispose();
    }

    /** Phase 19 (Schliff): HUD beim Start aus, Knöpfe brechen um, Rotbuche und junge Kiefer im Bewuchs (F24). */
    static void schliff() {
        teil("Schliff (Phase 19): HUD, Bedienfeld, Baumarten");
        com.dan.timmendorf.gl.Renderer r0 = new com.dan.timmendorf.gl.Renderer(new com.dan.timmendorf.scene.SceneClock());
        check(!r0.showHud && com.dan.timmendorf.data.Zustand.OHNE_RENDERER.contains("showHud"), "HUD beim Start aus, „Weiter wie zuletzt“ holt es nicht zurück");
        // Raster: acht Knöpfe des Blocks DATEN in zwei Spalten, nicht mehr in einer Zeile
        javax.swing.JPanel p = new javax.swing.JPanel(new com.dan.timmendorf.ui.Raster(2));
        for (String t : new String[]{"Verbinden …", "Ohne Datenbank", "Einrichten", "Prüfen", "Speichern (Strg+S)", "Weiter wie zuletzt", "Übernehmen", "Befunde …"})
            p.add(new com.dan.fbutton.FButton(t));
        p.setSize(com.dan.timmendorf.ui.Raster.BREITE_VORGABE, 400);
        p.doLayout();
        boolean lesbar = true;
        for (java.awt.Component c : p.getComponents()) lesbar &= c.getWidth() >= com.dan.timmendorf.ui.Raster.noetig(c);
        int sp = ((com.dan.timmendorf.ui.Raster) p.getLayout()).spalten(p, com.dan.timmendorf.ui.Raster.BREITE_VORGABE);
        check(sp == 2 && lesbar && p.getComponent(7).getY() > p.getComponent(0).getY(), "Block DATEN: acht Knöpfe in " + sp + " Spalten, jeder Text ganz lesbar (vorher alle in einer Zeile)");
        // Baumarten: Rotbuche und Kiefer aus dem Renderer, Auswahl nach Höhe
        java.util.Map<String, float[]> hs = new java.util.HashMap<>();
        java.util.function.Function<String, float[]> h = key -> hs.computeIfAbsent(key, kk -> {
            com.dan.tree.Tree.Art a = com.dan.tree.Tree.art(kk);
            if (a == null) return null;
            com.dan.tree.TreeSpecies sp2 = a.create();
            float[] o = new float[sp2.variants.length];
            for (int v = 0; v < o.length; v++) o[v] = sp2.load(v).height();
            return o;
        });
        check(h.apply("rotbuche") != null && h.apply("kiefer") != null, String.format(Locale.GERMAN, "Rotbuche (%s m) und junge Kiefer (%s m) im Renderer",
                java.util.Arrays.toString(h.apply("rotbuche")), java.util.Arrays.toString(h.apply("kiefer"))));
        int buche = 0, laub = 0, kiefer = 0, kieferHoch = 0, n = 0;
        java.util.Random rnd = new java.util.Random(19);
        for (int i = 0; i < 2000; i++) {
            int k = rnd.nextInt(Integer.MAX_VALUE);
            var lw = new com.dan.timmendorf.scene.Bewuchs.Baum(0, 0, 8 + rnd.nextFloat() * 20, com.dan.timmendorf.scene.Bewuchs.Gruppe.LAUBWALD, k);
            int[] w = com.dan.timmendorf.gl.BewuchsGl.waehle(lw, h);
            if (lw.gruppe().arten[w[0]].equals("rotbuche")) buche++; else laub++;
            var jung = new com.dan.timmendorf.scene.Bewuchs.Baum(0, 0, 1 + rnd.nextFloat() * 4, com.dan.timmendorf.scene.Bewuchs.Gruppe.NADELWALD, k);
            if (jung.gruppe().arten[com.dan.timmendorf.gl.BewuchsGl.waehle(jung, h)[0]].equals("kiefer")) kiefer++;
            var hoch = new com.dan.timmendorf.scene.Bewuchs.Baum(0, 0, 18, com.dan.timmendorf.scene.Bewuchs.Gruppe.NADELWALD, k);
            if (hoch.gruppe().arten[com.dan.timmendorf.gl.BewuchsGl.waehle(hoch, h)[0]].equals("kiefer")) kieferHoch++;
            n++;
        }
        check(Math.abs(buche / (double) n - 0.7) < 0.05, String.format(Locale.GERMAN, "Laubwald 8–28 m: %.0f %% Rotbuchen, der Rest Laubbäume aus dem Bestand", 100.0 * buche / n));
        check(kiefer == n && kieferHoch == 0, String.format(Locale.GERMAN, "Nadelwald: junge Bäume (1–5 m) alle Kiefern, 18 m hohe keine (die Jungkiefern reichen bis %.1f m)",
                h.apply("kiefer")[0] * com.dan.timmendorf.gl.BewuchsGl.MAX_SKALA));
        var jungeBuche = new com.dan.timmendorf.scene.Bewuchs.Baum(0, 0, 9, com.dan.timmendorf.scene.Bewuchs.Gruppe.LAUBWALD, 0);
        int[] wb = com.dan.timmendorf.gl.BewuchsGl.waehle(jungeBuche, h);
        check(wb[0] == 0 && h.apply("rotbuche")[wb[1]] < 12, "Eine 9 m hohe Buche nimmt eine junge Variante (" + com.dan.tree.europeanbeech.EuropeanBeech.VARIANTS[wb[1]] + ")");
        // 19c: Qualitätsstufen durchgängig: jedes Teil mit eigener Stufe wechselt sie zwischen Niedrig und Ultra
        r0.setQuality(com.dan.timmendorf.gl.Quality.NIEDRIG);
        java.util.Map<String, String> lo = new java.util.TreeMap<>();
        sammeln(r0).forEach((k2, v2) -> lo.put(k2, v2.toString()));
        r0.setQuality(com.dan.timmendorf.gl.Quality.MITTEL);
        java.util.Map<String, String> mi = new java.util.TreeMap<>();
        sammeln(r0).forEach((k2, v2) -> mi.put(k2, v2.toString()));
        r0.setQuality(com.dan.timmendorf.gl.Quality.ULTRA);
        java.util.List<String> fest = new java.util.ArrayList<>();
        int teile = 0;
        // abgeleitet (Momentaufnahmen, gebaute Parameter) oder je Bild gesetzt (Halme in BewuchsGl, SprayGl aus der Gischt), Straßengraph ohne Bild
        // (der Graph der Autos und derselbe Graph unter jeder Buslinie, verkehr.busse[i].g – die Busse gibt es nur mit daten/netz)
        java.util.Set<String> aus = java.util.Set.of("r.bewuchsGl.halme.quality (Reed)", "r.brecherGl.quality (SprayGl)");
        for (var e : sammeln(r0).entrySet()) {
            String k2 = e.getKey();
            if (k2.contains("snapshot") || k2.contains(".state.") || k2.contains("lastBuilt") || aus.contains(k2)
                    || k2.startsWith("r.wiring.verkehr.") && k2.endsWith(".g.quality (Route)")) continue;
            teile++;
            if (java.util.Objects.equals(lo.get(k2), e.getValue().toString())) fest.add(k2.replace(".quality", ""));
        }
        check(fest.isEmpty() && teile >= 18, teile + " Teile mit eigener Stufe, jedes wechselt zwischen Niedrig und Ultra" + (fest.isEmpty() ? "" : "; fest: " + fest));
        check("MEDIUM".equals(mi.get("r.brecher.quality (Spray)")) && "HIGH".equals(mi.get("r.leben.vogel.quality (Flock)")) && "MEDIUM".equals(mi.get("r.klang.s.quality (Soundscape)")),
                "„Mittel“ bleibt wie bis Phase 18 (Gischt mittel, Möwen hoch, Klang mittel)");
        r0.setQuality(com.dan.timmendorf.gl.Quality.MITTEL);
        // 19b: Programmsymbol und Verknüpfung (wie Haithabu Phase 17)
        boolean png = true;
        for (int sz : com.dan.timmendorf.ui.AppIcon.SIZES) png &= com.dan.timmendorf.ui.AppIcon.class.getResource("icon/timmendorf_" + sz + ".png") != null;
        check(png, "Symbol: PNGs in " + com.dan.timmendorf.ui.AppIcon.SIZES.length + " Größen im Jar (ant symbol)");
        try {
            int[] g = com.dan.timmendorf.tools.Symbol.icoGroessen(new java.io.File("icon/Timmendorf.ico"));
            check(java.util.Arrays.equals(g, com.dan.timmendorf.ui.AppIcon.SIZES), "icon/Timmendorf.ico mit PNG-Einträgen " + java.util.Arrays.toString(g));
        } catch (java.io.IOException e) { check(false, "icon/Timmendorf.ico: " + e.getMessage()); }
        java.awt.image.BufferedImage b256 = com.dan.timmendorf.ui.AppIcon.paint(256);
        int gold = b256.getRGB(128, 3), mitte = b256.getRGB(128, 128);
        check(((gold >> 16) & 255) > 150 && ((gold >> 8) & 255) > 110 && (gold & 255) < 110 && (mitte >>> 24) == 255 && (b256.getRGB(0, 0) >>> 24) < 255,
                "Goldkante am Rand, deckend in der Mitte, runde Ecken (F31 A)");
        try {
            String bat = java.nio.file.Files.readString(java.nio.file.Path.of("Timmendorf.bat")), ps1 = java.nio.file.Files.readString(java.nio.file.Path.of("Verknuepfung.ps1"));
            java.util.jar.Manifest mf;
            try (var in = new java.io.FileInputStream("manifest.mf")) { mf = new java.util.jar.Manifest(in); }
            String opens = mf.getMainAttributes().getValue("Add-Opens");
            check(bat.contains("dist\\Timmendorf.jar") && bat.contains("\r\n") && bat.contains("sun.awt.windows=ALL-UNNAMED") && ps1.contains("icon\\Timmendorf.ico")
                            && ps1.contains("sun.awt.windows=ALL-UNNAMED") && opens != null && opens.contains("java.desktop/sun.awt.windows")
                            && "ALL-UNNAMED".equals(mf.getMainAttributes().getValue("Enable-Native-Access")),
                    "Timmendorf.bat (CRLF), Verknuepfung.ps1 mit Symbol, Manifest öffnet sun.awt.windows für JOGL");
        } catch (java.io.IOException e) { check(false, "Startdateien: " + e.getMessage()); }
    }

    /** Phase 19: alle Felder „quality“ (Aufzählung) im Objektgraphen der Szene, bis 6 Ebenen tief. */
    static java.util.Map<String, Object> sammeln(Object root) {
      java.util.Map<String, Object> out = new java.util.TreeMap<>(); java.util.IdentityHashMap<Object,Boolean> seen = new java.util.IdentityHashMap<>();
      java.util.Deque<Object[]> q = new java.util.ArrayDeque<>(); q.add(new Object[]{root, "r", 0});
      while (!q.isEmpty()) { Object[] e = q.poll(); Object o = e[0]; String path=(String)e[1]; int d=(Integer)e[2];
        if (o == null || seen.containsKey(o)) continue; seen.put(o, true);
        Class<?> c = o.getClass(); if (!c.getName().startsWith("com.dan.")) continue;
        for (Class<?> k = c; k != null && k.getName().startsWith("com.dan."); k = k.getSuperclass())
          for (java.lang.reflect.Field f : k.getDeclaredFields()) { if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            try { f.setAccessible(true); Object v = f.get(o);
              if (v == null) continue;
              if (f.getName().equals("quality") && v instanceof Enum<?> en) out.put(path + "." + f.getName() + " (" + c.getSimpleName() + ")", en);
              else if (d < 6 && v.getClass().getName().startsWith("com.dan.") && !(v instanceof Enum)) q.add(new Object[]{v, path + "." + f.getName(), d + 1});
              else if (d < 6 && v instanceof java.util.Collection<?> col) { int i=0; for (Object x : col) { if (i++ > 3) break; if (x != null && x.getClass().getName().startsWith("com.dan.")) q.add(new Object[]{x, path + "." + f.getName() + "[" + (i-1) + "]", d + 1}); } }
            } catch (Throwable t) { }
          }
      }
      return out;
    }

    static void teil(String t) { System.out.println("\n— " + t); }

    static void check(boolean c, String what) {
        n++;
        if (!c) bad++;
        System.out.println((c ? "  ok    " : "  FEHLER ") + what);
    }

    private Pruefung() { }

    private static double zahl(String s) {
        try { return com.dan.store.Zahl.lies(s); } catch (RuntimeException e) { return Double.NaN; }
    }
}
