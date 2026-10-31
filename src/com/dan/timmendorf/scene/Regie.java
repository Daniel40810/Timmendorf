package com.dan.timmendorf.scene;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Phase 16: die Regiebücher als Text mit Blatt (aus Sylt Phase 15, dort aus Glacier und Rungholt). Die fünf
 * mitgelieferten stehen in {@code regie/*.txt} neben dieser Klasse. Ein Regiebuch ist eine Folge von Stichworten in
 * echten Sekunden seit dem Start: Datum und Uhrzeit, Zeitraffer, Wetterlage oder Regie, Ansichten und Fahrten, Sturmflut,
 * Feuerwerk, Regatta, Fotos und am Ende ein Blatt mit allen Fotos in {@code docs/status}. Was ein Stichwort tut, führt
 * die App über {@link Ctx} aus; so läuft dasselbe Regiebuch auch ohne Bild (Prüfung).
 * <p>
 * {@code warte} hält die Uhr des Regiebuchs an, bis die Szene so weit ist: eine Uhrzeit, der Startschuss der Regatta,
 * das erste Boot im Ziel, das Ende des Feuerwerks, das Ende einer Fahrt. Nach {@link #WARTE_HOECHSTENS} Sekunden geht
 * es mit einer Meldung weiter.
 * <ul>
 * <li><b>Ein Tag am Strand</b> (1.8.2026): Sonnenaufgang hinter der Seebrücke, der Optimist wird zu Wasser gelassen,
 * Strandkörbe am Vormittag, Mittag auf der Schleife, die Regatta, der Niendorfer Hafen, Abend am Brodtener Ufer, der
 * Mond über der Bucht, das Feuerwerk.</li>
 * <li><b>Die Regatta</b>: Startverfahren 5–4–1–0 vom Begleitboot, Start, Kreuz zur Luvtonne, Gennaker im Leegang, Ziel.</li>
 * <li><b>Die Sturmflut</b> (19. bis 23.10.2023): Ostwind, die Bucht füllt sich, Brecher an der Seebrücke, der Scheitel
 * an der Promenade, Abbrüche am Brodtener Ufer, der Morgen danach.</li>
 * <li><b>Ein Jahr an der Bucht</b> (August 2026 bis Juli 2027): Sommer, Herbststurm, Winter mit Eis, Frühjahr, wieder Sommer.</li>
 * <li><b>Mein Weg zum Strand</b>: zu Fuß über das Fußnetz von einem frei wählbaren Ort bis auf die Seebrücke (der Ort wird
 * nicht gespeichert).</li>
 * </ul>
 * Gestaltet [G]: Abläufe, Zeiten, Blickpunkte.
 */
public final class Regie {

    /** Was ein Regiebuch braucht; die App führt es aus (ohne Bild: die Prüfung). */
    public interface Ctx {
        void time(LocalDate date, double hour);
        void lapse(double x);
        /** Eine Wetterlage halten (Name wie im Bedienfeld). */
        void lage(String name);
        /** Die Wetterregie: frei, sturmtag oder hand. */
        void regie(String modus);
        /** Die Sturmflut 2023 nach dem Fahrplan: die Uhr auf den Tag (19. bis 23.10.2023) und die Stunde. */
        void sturmflut(LocalDate tag, double stunde);
        /** Das Feuerwerk: jetzt zünden (plan) oder „abend“ (wie im Bedienfeld: um 22:45 von selbst). */
        void feuerwerk(String wie);
        /** Die Regatta: ein neuer Start (das Startverfahren beginnt). */
        void regatta();
        void view(String name);
        void fahrt(String name);
        /** Ob die Szene so weit ist (für {@code warte}). */
        boolean ready(Warte w);
        void capture(String caption);
        void sheet(String name);
        void say(String text);
    }

    /** Worauf {@code warte} wartet: eine Uhrzeit (Stunden seit 1970) oder ein Ereignis. */
    public record Warte(String was, double hours) {
        @Override public String toString() {
            return was.equals("uhr") ? "Uhr " + java.time.Instant.ofEpochMilli((long) (hours * 3_600_000)).atZone(SceneClock.ZONE).toLocalDateTime().toString().replace('T', ' ') : was;
        }
    }

    /** Ein Stichwort: Sekunde (echte Zeit seit dem Start), Text, Handlung; bei {@code warte} die Bedingung. */
    public record Cue(double t, String text, Consumer<Ctx> action, Warte warte) { }

    /** Ein Regiebuch: Name, Beschreibung, Länge (s, ohne die Wartezeit), Stichworte (nach der Zeit geordnet), der Text, die Zeilen. */
    public record Book(String name, String about, double length, List<Cue> cues, String text, List<Zeile> zeilen) {
        @Override public String toString() { return name; }
        /** Wie viele Fotos das Regiebuch macht. */
        public int fotos() { return (int) zeilen.stream().filter(z -> z.befehl().equals("foto")).count(); }
    }

    /** Länger wartet ein {@code warte} nicht (echte Sekunden). */
    public static final double WARTE_HOECHSTENS = 600;

    // ------------------------------------------------------------------ Ablauf

    /** Bekommt den Text jedes Stichworts (der Kinomodus macht Untertitel daraus); null = niemand. */
    public volatile Consumer<String> beiText;

    private volatile Book book;
    private double t, waited, waitedTotal;
    private int next;
    private volatile String status = "";
    private int fired;
    /** Wie oft ein {@code warte} nicht kam (Prüfung: ohne Eingriff heißt auch: ohne Ausweichen). */
    public int ausgewichen;

    /** Regiebuch beginnen (das erste Stichwort sofort). */
    public void start(Book b, Ctx c) {
        book = b;
        t = 0;
        next = 0;
        fired = 0;
        ausgewichen = 0;
        waited = waitedTotal = 0;
        status = b.name() + " beginnt";
        step(0, c);
    }

    public void stop() {
        Book b = book;
        book = null;
        if (b != null) status = b.name() + " angehalten";
    }

    public boolean running() { return book != null; }

    public Book book() { return book; }

    /** Uhr des Regiebuchs (s, steht beim Warten). */
    public double time() { return t; }

    /** Wie viele Stichworte schon ausgeführt sind. */
    public int fired() { return fired; }

    /** Wie lange schon gewartet wurde (s, alle {@code warte} zusammen). */
    public double waitedTotal() { return waitedTotal; }

    /** Das {@code warte}, an dem das Regiebuch gerade steht, oder null. */
    public Warte waiting() {
        Book b = book;
        if (b == null || next >= b.cues().size()) return null;
        Cue q = b.cues().get(next);
        return q.warte() != null && q.t() <= t ? q.warte() : null;
    }

    /** Um dt echte Sekunden weiter, fällige Stichworte ausführen; an einem {@code warte} steht die Uhr. */
    public void step(double dt, Ctx c) {
        Book b = book;
        if (b == null) return;
        double d = Math.max(0, dt);
        if (next < b.cues().size() && b.cues().get(next).warte() != null && b.cues().get(next).t() <= t) {
            waited += d;
            waitedTotal += d;
        } else t += d;
        while (next < b.cues().size() && b.cues().get(next).t() <= t) {
            Cue q = b.cues().get(next);
            if (q.warte() != null) {
                if (!c.ready(q.warte()) && waited < WARTE_HOECHSTENS) {
                    t = q.t();
                    status = String.format(Locale.GERMAN, "%s · wartet auf %s", b.name(), q.warte());
                    return;
                }
                if (waited >= WARTE_HOECHSTENS) ausgewichen++;
                status = waited >= WARTE_HOECHSTENS ? b.name() + " · " + q.warte() + " kam nicht, weiter" : b.name() + " · " + q.warte() + ": weiter";
                waited = 0;
                next++;
                fired++;
                continue;
            }
            next++;
            if (!q.text().isEmpty()) {
                status = String.format(Locale.GERMAN, "%s · %s", b.name(), q.text());
                Consumer<String> h = beiText;
                if (h != null) h.accept(q.text());   // Untertitel im Kino
            }
            try { q.action().accept(c); } catch (RuntimeException e) { status = b.name() + " · Fehler bei „" + q.text() + "“: " + e.getMessage(); }
            fired++;
        }
        if (t >= b.length() && next >= b.cues().size()) {
            book = null;
            status = b.name() + " ist zu Ende (" + fired + " Stichworte)";
        }
    }

    /** „Ein Abend am Kliff · 21:40: das Kliff glüht · 1:10 von 4:20“ */
    public String describe() {
        Book b = book;
        if (b == null) return status.isEmpty() ? "kein Regiebuch" : status;
        return String.format(Locale.GERMAN, "%s · %d:%02d von %d:%02d", status, (int) t / 60, (int) t % 60, (int) b.length() / 60, (int) b.length() % 60);
    }

    // ------------------------------------------------------------------ die Regiebücher (als Text)

    /** Die fünf mitgelieferten Regiebücher aus den Textdateien neben dieser Klasse (regie/*.txt). */
    public static final String[] DATEIEN = {"tag.txt", "regatta.txt", "sturmflut.txt", "jahr.txt", "weg.txt"};

    public static List<Book> all() {
        List<Book> l = new ArrayList<>();
        for (String d : DATEIEN) l.add(mitgeliefert(d));
        return l;
    }

    /** Nach Name, auch ohne Umlaute und in Teilen („Tag“, „Regatta“, „Sturmflut“, „Jahr“, „Weg“). */
    public static Book byName(String n) {
        String k = plain(n);
        for (Book b : all()) if (plain(b.name()).equals(k)) return b;
        for (Book b : all()) if (plain(b.name()).contains(k)) return b;
        return null;
    }

    /** Ein Name ohne Umlaute, Leerzeichen, Bindestriche und Anführung, klein (für Ansichten, Fahrten, Lagen). */
    public static String plainName(String s) { return plain(s).replace("-", ""); }

    static String plain(String s) {
        return s.toLowerCase(Locale.ROOT).replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss").replace(" ", "").replace("„", "").replace("“", "");
    }

    /** Der Text eines mitgelieferten Regiebuchs. */
    public static String text(String datei) {
        try (java.io.InputStream in = Regie.class.getResourceAsStream("regie/" + datei)) {
            if (in == null) throw new IllegalStateException("Regiebuch regie/" + datei + " fehlt im Build");
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Regiebuch regie/" + datei + " nicht lesbar: " + e.getMessage(), e);
        }
    }

    private static Book mitgeliefert(String datei) { return parse(text(datei)); }

    /** Die Befehle der Stichworte (klein geschrieben im Text). */
    public static final List<String> BEFEHLE = List.of("zeit", "raffer", "lage", "regie", "sturmflut", "feuerwerk", "regatta", "ansicht", "fahrt",
            "warte", "foto", "blatt", "sage");

    /** Die Bedingungen von {@code warte}. */
    public static final List<String> WARTEN = List.of("uhr", "start", "ziel", "feuerwerk", "fahrt");

    /** Ein Stichwort, wie es im Text steht: Sekunde, Befehl, Argumente, Text fürs HUD. */
    public record Zeile(double sekunde, String befehl, String argumente, String text) { }

    /**
     * Liest ein Regiebuch aus Text. Kopfzeilen: {@code Regiebuch:}, {@code Beschreibung:}, {@code Länge:} (m:ss). Dann je
     * Zeile ein Stichwort: {@code m:ss befehl argumente | Text}. Zeilen zur selben Zeit ohne Text gehören zum Stichwort
     * davor, außer {@code warte}. Fehler nennen die Zeile.
     */
    public static Book parse(String text) {
        String name = null, about = "";
        double length = Double.NaN;
        List<Zeile> zeilen = new ArrayList<>();
        List<Warte> warten = new ArrayList<>();
        int no = 0;
        LocalDate[] day = {null};
        for (String raw : text.split("\\R")) {
            no++;
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String low = line.toLowerCase(Locale.ROOT);
            try {
                if (low.startsWith("regiebuch:")) name = line.substring(10).strip();
                else if (low.startsWith("beschreibung:")) about = line.substring(13).strip();
                else if (low.startsWith("länge:")) length = sekunden(line.substring(6).strip());
                else {
                    Zeile z = zeile(line);
                    if (z.befehl().equals("zeit")) day[0] = datum(z.argumente().split("\\s+")[0]);
                    if (z.befehl().equals("sturmflut")) day[0] = sturmTag(z.argumente());
                    if (z.befehl().equals("warte")) warten.add(warte(z.argumente(), day[0]));
                    else warten.add(null);
                    zeilen.add(z);
                }
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Zeile " + no + ": " + e.getMessage() + " („" + line + "“)");
            }
        }
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("Es fehlt die Zeile „Regiebuch: Name“");
        if (zeilen.isEmpty()) throw new IllegalArgumentException("Das Regiebuch hat keine Stichworte");
        Integer[] idx = new Integer[zeilen.size()];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        java.util.Arrays.sort(idx, java.util.Comparator.comparingDouble(i -> zeilen.get(i).sekunde()));
        List<Zeile> sorted = new ArrayList<>();
        List<Warte> sortedW = new ArrayList<>();
        for (int i : idx) { sorted.add(zeilen.get(i)); sortedW.add(warten.get(i)); }
        List<Cue> cues = new ArrayList<>();
        for (int i = 0; i < sorted.size(); ) {
            Zeile z = sorted.get(i);
            if (sortedW.get(i) != null) { cues.add(new Cue(z.sekunde(), z.text(), x -> { }, sortedW.get(i))); i++; continue; }
            List<Zeile> grp = new ArrayList<>(List.of(z));
            int k = i + 1;
            while (k < sorted.size() && sorted.get(k).sekunde() == z.sekunde() && sorted.get(k).text().isEmpty() && sortedW.get(k) == null) grp.add(sorted.get(k++));
            List<Consumer<Ctx>> acts = new ArrayList<>();
            for (Zeile g : grp) acts.add(handlung(g, name));
            cues.add(new Cue(z.sekunde(), z.text(), x -> { for (Consumer<Ctx> a : acts) a.accept(x); }, null));
            i = k;
        }
        if (Double.isNaN(length)) length = cues.get(cues.size() - 1).t() + 1;
        return new Book(name, about, length, cues, text, sorted);
    }

    /** „1:48“, „0:04“, „96“ oder „1:36.5“ → Sekunden. */
    static double sekunden(String s) {
        int c = s.indexOf(':');
        if (c < 0) return Double.parseDouble(s);
        return Integer.parseInt(s.substring(0, c)) * 60 + Double.parseDouble(s.substring(c + 1));
    }

    /** Sekunden → „m:ss“ (mit Zehnteln, wenn nötig). */
    public static String zeit(double s) {
        int m = (int) (s / 60);
        double r = s - m * 60;
        return Math.abs(r - Math.rint(r)) < 1e-6 ? String.format(Locale.ROOT, "%d:%02d", m, (int) Math.rint(r)) : String.format(Locale.ROOT, "%d:%04.1f", m, r);
    }

    /** „sturmflut [TT.MM.JJJJ] [hh:mm]“: der Tag (ohne Angabe der Scheitel, 20.10.2023). */
    static LocalDate sturmTag(String a) {
        for (String p : a.isEmpty() ? new String[0] : a.split("\\s+")) if (p.split("\\.").length == 3) return datum(p);
        return LocalDate.of(2023, 10, 20);
    }

    static LocalDate datum(String s) {
        String[] d = s.split("\\.");
        if (d.length != 3) throw new IllegalArgumentException("Datum TT.MM.JJJJ erwartet");
        return LocalDate.of(Integer.parseInt(d[2]), Integer.parseInt(d[1]), Integer.parseInt(d[0]));
    }

    /** „uhr 21:40“, „uhr 02.08. 05:30“, „start“ (Startschuss der Regatta), „ziel“ (das erste Boot im Ziel), „feuerwerk“ (es ist vorbei), „fahrt“ (die Fahrt ist zu Ende). */
    static Warte warte(String a, LocalDate day) {
        String[] p = a.strip().split("\\s+");
        String w = p[0].toLowerCase(Locale.ROOT).replace("ü", "ue").replace("ä", "ae");
        if (!WARTEN.contains(w)) throw new IllegalArgumentException("warte " + String.join("|", WARTEN) + " erwartet");
        if (!w.equals("uhr")) return new Warte(w, Double.NaN);
        if (day == null) throw new IllegalArgumentException("warte uhr vor dem ersten zeit");
        LocalDate d = day;
        String hm;
        if (p.length == 3) {
            String[] dm = p[1].split("\\.");
            d = LocalDate.of(day.getYear(), Integer.parseInt(dm[1]), Integer.parseInt(dm[0]));
            if (d.isBefore(day)) d = d.plusYears(1);
            hm = p[2];
        } else if (p.length == 2) hm = p[1];
        else throw new IllegalArgumentException("warte uhr [TT.MM.] hh:mm erwartet");
        double h = sekunden(hm) / 60;
        int sec = (int) Math.round(Math.max(0, Math.min(86_399, h * 3600)));
        long ms = java.time.ZonedDateTime.of(d, java.time.LocalTime.ofSecondOfDay(sec), SceneClock.ZONE).toInstant().toEpochMilli();
        return new Warte("uhr", ms / 3_600_000.0);
    }

    static Zeile zeile(String line) {
        String cmd = line, txt = "";
        int bar = line.indexOf(" | ");
        if (bar >= 0) { cmd = line.substring(0, bar).strip(); txt = line.substring(bar + 3).strip(); }
        String[] p = cmd.split("\\s+", 3);
        if (p.length < 2) throw new IllegalArgumentException("Zeit und Befehl erwartet");
        double t = sekunden(p[0]);
        String b = p[1].toLowerCase(Locale.ROOT).replace("ä", "ae").replace("ü", "ue");
        if (!BEFEHLE.contains(b)) throw new IllegalArgumentException("unbekannter Befehl „" + p[1] + "“, bekannt sind " + String.join(", ", BEFEHLE));
        return new Zeile(t, b, p.length > 2 ? p[2].strip() : "", txt);
    }

    /** Was ein Befehl tut; prüft die Argumente schon beim Lesen. */
    static Consumer<Ctx> handlung(Zeile z, String buch) {
        String a = z.argumente();
        switch (z.befehl()) {
            case "zeit": {
                String[] p = a.split("\\s+");
                if (p.length != 2) throw new IllegalArgumentException("zeit TT.MM.JJJJ hh:mm erwartet");
                LocalDate date = datum(p[0]);
                double h = sekunden(p[1]) / 60;
                return x -> x.time(date, h);
            }
            case "raffer": { double v = Double.parseDouble(a); return x -> x.lapse(v); }
            case "lage": { need(a, "lage Name"); return x -> x.lage(a); }
            case "regie": {
                String v = a.strip().toLowerCase(Locale.ROOT);
                if (!List.of("frei", "sturmtag", "hand").contains(v)) throw new IllegalArgumentException("regie frei|sturmtag|hand erwartet");
                return x -> x.regie(v);
            }
            case "sturmflut": {
                LocalDate tag = sturmTag(a);
                String[] p = a.isEmpty() ? new String[0] : a.split("\\s+");
                String hs = p.length == 0 ? "" : p[p.length - 1].contains(".") ? "" : p[p.length - 1];
                double h = hs.isEmpty() ? 23 : sekunden(hs) / (hs.contains(":") ? 60 : 1);
                return x -> x.sturmflut(tag, h);
            }
            case "feuerwerk": {
                String v = a.isEmpty() ? "jetzt" : a.strip().toLowerCase(Locale.ROOT);
                if (!List.of("jetzt", "abend").contains(v)) throw new IllegalArgumentException("feuerwerk [jetzt|abend] erwartet");
                return x -> x.feuerwerk(v);
            }
            case "regatta": return Ctx::regatta;
            case "ansicht": { need(a, "ansicht Name"); return x -> x.view(a); }
            case "fahrt": { need(a, "fahrt Name"); return x -> x.fahrt(a); }
            case "foto": { need(a, "foto Unterschrift"); return x -> x.capture(a); }
            case "blatt": { String n = a.isEmpty() ? buch : a; return x -> x.sheet(n); }
            case "sage": { need(a, "sage Text"); return x -> x.say(a); }
            default: throw new IllegalArgumentException(z.befehl());
        }
    }

    private static void need(String a, String form) { if (a.isEmpty()) throw new IllegalArgumentException(form + " erwartet"); }
}
