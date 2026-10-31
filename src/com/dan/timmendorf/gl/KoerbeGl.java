package com.dan.timmendorf.gl;

import com.dan.beachchair.BeachChair;
import com.dan.beachchair.gl.BeachChairGl;
import com.dan.timmendorf.scene.Bauwerke;
import com.dan.timmendorf.scene.Site;
import com.jogamp.opengl.GL4;

import java.util.BitSet;
import java.util.List;
import java.util.Locale;

/**
 * Seit Phase 20: die Strandkörbe aus {@code com.dan.beachchair} (nach dem Foto Strandkorb.jpg) statt der Kästen in den
 * Bauten. Die Plätze kommen aus {@link Bauwerke#koerbe}, gezeichnet wird mit {@link BeachChairGl}: nah, mittel und fern
 * nach dem Abstand, dazu die Schatten der nahen Körbe auf dem Sand. Die Stellung folgt dem Tag: morgens schließen die
 * Vermieter auf, abends zu, Körbe mit Gästen ({@code Menschen.besetzt}) sind immer offen, nachmittags sind mehr Lehnen
 * hinten und Fußstützen heraus; bei Regen und Sturm bleiben die meisten zu.
 */
final class KoerbeGl {
    final BeachChair korb = Bauwerke.korb();
    final BeachChairGl gl = new BeachChairGl();
    private List<BeachChair.Chair> stuehle = List.of();
    private boolean ok;
    private long schluessel = Long.MIN_VALUE;
    String info = "";

    void init(GL4 g, List<Bauwerke.Korb> plaetze, BeachChair.Quality q) {
        try {
            korb.quality = q;
            gl.init(g, korb);
            gebaut = q;
            stuehle = Bauwerke.stuehle(plaetze, korb);
            gl.setChairs(stuehle);
            ok = true;
            info = gl.info();
        } catch (RuntimeException e) {
            info = "!Strandkörbe nicht verfügbar: " + e.getMessage();
            System.err.println("Timmendorf: " + info);
        }
    }

    boolean ok() { return ok; }

    /** Die Stufe, mit der die Netze gebaut sind. */
    private BeachChair.Quality gebaut;

    /** Andere Qualitätsstufe ({@code korb.quality}, gesetzt in Renderer.setQuality): Netze und Reichweiten neu (im Faden der Grafik). */
    void stufe(GL4 g) {
        if (!ok || korb.quality == gebaut) return;
        gebaut = korb.quality;
        gl.setChair(g, korb);
    }

    /**
     * Stellungen nach Stunde, Wetter und Gästen; nur neu, wenn sich etwas davon ändert (Viertelstunde, Tag, Regen, Sturm,
     * Belegung).
     */
    void stellen(java.time.LocalDate tag, double stunde, boolean regen, double wind, BitSet besetzt) {
        if (!ok) return;
        int viertel = (int) Math.floor(stunde * 4);
        long k = ((long) tag.getDayOfYear() * 100 + viertel) * 4 + (regen ? 1 : 0) + (wind > 13.9 ? 2 : 0);
        k = k * 31 + (besetzt == null ? 0 : besetzt.hashCode());
        if (k == schluessel) return;
        schluessel = k;
        double offen = Bauwerke.offen(stunde, regen, wind), bequem = Bauwerke.bequem(stunde);
        for (int i = 0; i < stuehle.size(); i++) {
            BeachChair.Chair c = stuehle.get(i);
            boolean gast = besetzt != null && besetzt.get(i);
            korb.pose(c, gast ? 1 : offen, bequem);
            if (gast) c.closed = false;
        }
        gl.touch();
    }

    /**
     * Schatten und Körbe ins HDR-Ziel (mit Tiefe, nach dem Gelände). Licht wie die Tiere: {@code L} aus
     * {@code Unwetter.licht} (Sonne und Mond vorbelichtet, Himmel oben und am Horizont), Dunst aus der Sicht. Ohne
     * {@code com.dan.sky} ein einfaches Licht aus der Richtung der Sonne.
     */
    void zeichnen(GL4 g, float[] vp, double[] auge, double[][] L, double fog, double[] sonne, double schatten, Gelaende grund) {
        if (!ok) return;
        BeachChairGl.View v = new BeachChairGl.View();
        v.viewProj = vp;
        v.eye = auge.clone();
        v.earthRadius = Site.EARTH_R;
        BeachChairGl.Light l = new BeachChairGl.Light();
        if (L != null) {
            l.sunDir = L[0].clone();
            l.sunColor = L[1].clone();
            l.moonDir = L[2].clone();
            l.moonColor = L[3].clone();
            l.skyTop = L[4].clone();
            l.skyHorizon = L[5].clone();
            l.fogColor = L[5].clone();
            l.fogDistance = fog;
        } else {
            l.sunDir = sonne.clone();
            double s = Math.PI * 0.9 * schatten;
            l.sunColor = new double[]{s, s * 0.97, s * 0.92};
            l.skyTop = new double[]{0.30, 0.38, 0.52};
            l.skyHorizon = new double[]{0.45, 0.52, 0.62};
            l.fogColor = l.skyHorizon.clone();
            l.fogDistance = fog;
        }
        gl.drawShadows(g, v, l, grund::hoehe);
        gl.draw(g, v, l);
    }

    /** Höhe des Geländes für die Schatten. */
    interface Gelaende { double hoehe(double x, double z); }

    /** „Strandkörbe: 8 412 (nah 31, mittel 702, fern 7 679), 140 Schatten · 7 380 offen“ */
    String describe() {
        if (!ok) return info;
        int offen = 0;
        for (BeachChair.Chair c : stuehle) if (!c.closed) offen++;
        return gl.describe() + String.format(Locale.GERMAN, " · %,d offen", offen);
    }

    void dispose(GL4 g) { if (ok) gl.dispose(g); }

    /** Zahl der Körbe. */
    int zahl() { return stuehle.size(); }

    static BeachChair.Quality stufe(int o) { return BeachChair.Quality.values()[Math.max(0, Math.min(3, o))]; }
}
