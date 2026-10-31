package com.dan.timmendorf.scene;

import com.dan.beacon.Beacon;
import com.dan.beacon.Characteristic;
import com.dan.beacon.LightColour;
import com.dan.beacon.SectorTable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Die Leuchtfeuer der Szene (seit Phase 9) aus {@code com.dan.beacon}: das Leuchtfeuer Travemünde im Maritim
 * ({@link Beacon#travemuende()}), das Molenfeuer Nordermole ({@link Beacon#nordermole()}), das Hafenfeuer Niendorf rot und
 * grün ({@link Beacon#niendorf()}), der Alte Leuchtturm dunkel oder wie bis 1972 ({@link Beacon#alterLeuchtturm}) und alle
 * übrigen Feuer aus OpenStreetMap ({@code daten/bauwerke}: das Leitfeuer der Trave Iso WRG 4s und die gelben Festfeuer an
 * der Trave) mit ihren Kennungen und Sektoren aus den Seezeichen-Schlüsseln. Ein Feuer, dessen Kennung sich nicht ändert
 * (F), rechnet nur einmal je Sekunde neu.
 */
public final class Leuchtfeuer {
    /** Ein Feuer mit Lage; {@code wechselt}: Kennung mit Takt (jedes Bild). */
    public record Licht(Beacon beacon, boolean wechselt, String quelle) { }

    public final List<Licht> lichter = new ArrayList<>();
    public final Beacon maritim, nordermole, alter, niendorfRot, niendorfGruen;
    /** Alter Leuchtturm: wie bis 1972 (an) oder dunkel wie heute. */
    private volatile boolean historisch;
    private double ruhig;
    private double lux = 0;

    /** Hafenfeuer Niendorf an den Molenköpfen (DOP20) [G]. */
    static final double[] NIENDORF_NORD = {1766, 692}, NIENDORF_SUED = {1758, 768};

    public Leuchtfeuer(Bauwerke b) {
        maritim = Beacon.travemuende();
        double[] mt = {Site.TRAVEMUENDE.x(), Site.TRAVEMUENDE.z()};
        maritim.x = mt[0];
        maritim.z = mt[1];
        add(maritim, true, "Wikipedia");
        Bauwerke.Feuer nm = b.feuer.stream().filter(f -> f.name().equals("Nordermole")).findFirst().orElse(null);
        nordermole = Beacon.nordermole();
        nordermole.x = nm != null ? nm.x() : 7117;
        nordermole.z = nm != null ? nm.z() : 4142;
        add(nordermole, true, "OSM");
        niendorfRot = Beacon.niendorf();
        niendorfRot.x = NIENDORF_NORD[0];
        niendorfRot.z = NIENDORF_NORD[1];
        add(niendorfRot, false, "geschätzt");
        niendorfGruen = Beacon.niendorf();
        niendorfGruen.name = "Hafenfeuer Niendorf (grün)";
        niendorfGruen.set("F G", SectorTable.all(LightColour.G, 3));
        niendorfGruen.x = NIENDORF_SUED[0];
        niendorfGruen.z = NIENDORF_SUED[1];
        add(niendorfGruen, false, "geschätzt");
        alter = Beacon.alterLeuchtturm(true);
        alter.x = Bauwerke.ALT_X;
        alter.z = Bauwerke.ALT_Z;
        alter.y = Bauwerke.ALT_FEUER;
        alter.automatic = false;
        alter.manualOn = false;
        alter.snapOn(false);
        add(alter, false, "geschätzt");
        for (Bauwerke.Feuer f : b.feuer) {
            if (f.name().equals("Nordermole")) continue;
            Beacon o = ausOsm(f);
            if (o != null) add(o, !o.characteristic().text.startsWith("F "), "OSM");
        }
    }

    private void add(Beacon b, boolean wechselt, String q) { lichter.add(new Licht(b, wechselt, q)); }

    /**
     * Ein Feuer aus den Seezeichen-Schlüsseln von OSM: je Licht „Kennung Farbe Wiederkehr Höhe Tragweite von-bis“; die
     * Lichter werden zu einer Kennung mit allen Farben und einer Sektorentafel (Peilung von See wie im Verzeichnis).
     */
    static Beacon ausOsm(Bauwerke.Feuer f) {
        if (f.lichter().isEmpty()) return null;
        String art = null, periode = null;
        double hoehe = 10;
        StringBuilder farben = new StringBuilder(), tafel = new StringBuilder();
        double reichweite = 5;
        boolean sektoren = false;
        for (String l : f.lichter()) {
            String[] t = l.split(" ");
            if (t.length < 6) continue;
            if (art == null) art = t[0];
            String c = kuerzel(t[1]);
            if (farben.indexOf(c) < 0) farben.append(c);
            if (!t[2].equals("-")) periode = t[2];
            try { hoehe = Double.parseDouble(t[3]); } catch (NumberFormatException ignored) { }
            double r = 5;
            try { r = Double.parseDouble(t[4]); } catch (NumberFormatException ignored) { }
            reichweite = Math.max(reichweite, r);
            String sek = t[5];
            if (sek.matches("[0-9.]+-[0-9.]+")) {
                sektoren = true;
                tafel.append(tafel.length() > 0 ? ", " : "").append(c).append(' ').append(sek).append(' ').append(r);
            }
        }
        if (art == null) return null;
        Beacon b = new Beacon();
        b.name = f.name().isEmpty() ? "Feuer" : f.name();
        b.optic = Beacon.Optic.DRUM;
        b.lampRadius = 0.016;   // wie die Voreinstellung: dieselbe Strahlverfolgung für alle (Lens.trace merkt sie sich)
        b.x = f.x();
        b.z = f.z();
        b.y = hoehe;
        String text = art + " " + farben + (periode != null ? " " + periode + "s" : "");
        SectorTable st = sektoren ? SectorTable.parse(tafel.toString(), reichweite)
                : SectorTable.all(LightColour.valueOf(farben.substring(0, 1).equals("B") ? "BU" : farben.substring(0, 1)), reichweite);
        try {
            b.set(text, st);
        } catch (RuntimeException e) {
            b.set("F W", SectorTable.all(LightColour.W, reichweite));
        }
        return b;
    }

    private static String kuerzel(String farbe) {
        return switch (farbe.toLowerCase(Locale.ROOT)) {
            case "red" -> "R";
            case "green" -> "G";
            case "yellow" -> "Y";
            case "blue" -> "Bu";
            default -> "W";
        };
    }

    public boolean historisch() { return historisch; }

    /** Alter Leuchtturm: wie bis 1972 (an) oder dunkel wie heute. */
    public void setHistorisch(boolean h) {
        historisch = h;
        alter.manualOn = h;
    }

    /** Je Bild: Umgebungslicht (lx am Boden), Zeitschritt (s). Feste Feuer rechnen einmal je Sekunde. */
    public void step(double dt, double ambientLux) {
        lux = ambientLux;
        ruhig += dt;
        boolean alle = ruhig >= 1;
        for (Licht l : lichter) {
            if (!l.wechselt() && !alle && l.beacon() != alter) continue;
            l.beacon().ambientLux = ambientLux;
            l.beacon().step(l.wechselt() || l.beacon() == alter ? dt : ruhig);
        }
        if (alle) ruhig = 0;
    }

    /** Wie viele gerade brennen. */
    public int brennen() {
        int n = 0;
        for (Licht l : lichter) if (l.beacon().isOn()) n++;
        return n;
    }

    /** „Leuchtfeuer: 31 (5 mit Takt), 31 an · Travemünde Fl WR 4s 114,7 m · Alter Leuchtturm dunkel“ */
    public String describe() {
        long w = lichter.stream().filter(Licht::wechselt).count();
        return String.format(Locale.GERMAN, "Leuchtfeuer: %d (%d mit Takt), %d an bei %.0f lx · %s %s %.1f m · Alter Leuchtturm %s", lichter.size(), w, brennen(), lux,
                maritim.name, maritim.characteristic().text, maritim.y, historisch ? "wie bis 1972" : "dunkel");
    }

    /** Für die Prüfung: Kennung gelesen? */
    static boolean gueltig(String text) { return Characteristic.tryParse(text) != null; }
}
