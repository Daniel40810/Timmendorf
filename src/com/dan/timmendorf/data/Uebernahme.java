package com.dan.timmendorf.data;

import com.dan.store.Ablage;
import com.dan.store.Felder;
import com.dan.store.Protokoll;
import com.dan.store.Zahl;

import java.io.File;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Übernahme aus dem Datei-Rückfall in die Datenbank (F37 A, beim Verbinden und im Block DATEN): die Chronik (Läufe der
 * Regiebücher, Ergebnisse der Regatta, Abbrüche, Ereignisse) und die eigenen Regiebücher, Ansichten und Presets. Je
 * Datei steht ihre Prüfsumme in TD_UEBERNAHME; dieselbe Datei kommt kein zweites Mal. Die CSV der Chronik heißen danach
 * {@code *.uebernommen}, damit neue Zeilen in einer neuen Datei beginnen. Die Nummern der Sitzungen aus den Dateien gibt
 * es in der Datenbank nicht; die Zeilen kommen ohne Sitzung hinein.
 */
public final class Uebernahme {
    private Uebernahme() { }

    static String summe(File f) throws Ablage.Fehler {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            StringBuilder b = new StringBuilder();
            for (byte x : md.digest(Files.readAllBytes(f.toPath()))) b.append(String.format("%02x", x));
            return b.toString();
        } catch (Exception e) {
            throw new Ablage.Fehler(f + " nicht lesbar: " + e.getMessage(), e);
        }
    }

    /** Ob es in den Dateien etwas zu übernehmen gibt. */
    public static boolean etwasDa(DateiSpeicher d) {
        for (File f : d.chronikDateien()) if (f.isFile()) return true;
        return !d.eigeneDateien().isEmpty();
    }

    /** Was übernommen wurde, als Text („3 Läufe, 20 Ergebnisse …“). */
    public static String uebernehmen(DateiSpeicher von, DbSpeicher nach) throws Ablage.Fehler {
        int la = 0, er = 0, ab = 0, ev = 0, rb = 0, an = 0, pr = 0, schon = 0;
        // Chronik
        File f = von.laeufe.f;
        if (f.isFile() && !nach.uebernommen(summe(f))) {
            String sum = summe(f);
            List<Daten.Regielauf> l = von.regielaeufe(Integer.MAX_VALUE);
            java.util.Collections.reverse(l);
            for (Daten.Regielauf x : l) { nach.regielauf(x, null); la++; }
            fertig(nach, f, "Lauf", sum, l.size());
        } else if (f.isFile()) schon++;
        f = von.ergebnisse.f;
        if (f.isFile() && !nach.uebernommen(summe(f))) {
            String sum = summe(f);
            List<Daten.Ergebnis> l = von.ergebnisse(Integer.MAX_VALUE);
            java.util.Collections.reverse(l);
            nach.ergebnisse(l, null);
            er = l.size();
            fertig(nach, f, "Ergebnis", sum, l.size());
        } else if (f.isFile()) schon++;
        f = von.abbrueche.f;
        if (f.isFile() && !nach.uebernommen(summe(f))) {
            String sum = summe(f);
            List<Daten.Abbruch> l = von.abbrueche(Integer.MAX_VALUE);
            java.util.Collections.reverse(l);
            for (Daten.Abbruch x : l) { nach.abbruch(x, null); ab++; }
            fertig(nach, f, "Abbruch", sum, l.size());
        } else if (f.isFile()) schon++;
        f = von.ereignisse.f;
        if (f.isFile() && !nach.uebernommen(summe(f))) {
            String sum = summe(f);
            List<LinkedHashMap<String, String>> l = von.ereignisseAlle();
            for (LinkedHashMap<String, String> m : l) {
                double w = Zahl.lies(m.get("WERT")), w2 = Zahl.lies(m.get("WERT2"));
                nach.ereignis(m.get("ART"), m.get("QUELLE"), m.get("TEXT"), Double.isNaN(w) ? null : w, Double.isNaN(w2) ? null : w2, null);
                ev++;
            }
            fertig(nach, f, "Ereignis", sum, l.size());
        } else if (f.isFile()) schon++;
        // eigene Einträge, je Datei
        for (Daten.Regiebuch r : von.regiebuecher()) {
            if (!Daten.EIGEN.equals(r.herkunft())) continue;
            File rf = new File(new File(von.dir(), "regiebuecher"), com.dan.store.DateiAblage.dateiname(r.name()));
            String sum = rf.isFile() ? summe(rf) : Startdaten.sha256(r.text());
            if (nach.uebernommen(sum)) { schon++; continue; }
            nach.speichern(r);
            nach.uebernahme(rf.getPath(), "Regiebuch", sum, 1);
            rb++;
        }
        for (Daten.Ansicht a : von.ansichten()) {
            if (!Daten.EIGEN.equals(a.herkunft())) continue;
            File af = new File(new File(von.dir(), "ansichten"), a.id() + ".txt");
            String sum = summe(af);
            if (nach.uebernommen(sum)) { schon++; continue; }
            nach.speichern(von.ansicht(a.id()), null);
            nach.uebernahme(af.getPath(), "Ansicht", sum, 1);
            an++;
        }
        for (Daten.PaketPreset p : von.presets(null, null)) {
            if (!Daten.EIGEN.equals(p.herkunft())) continue;
            String sum = Startdaten.sha256(p.paket() + "." + p.klasse() + "." + p.name() + "\n" + Felder.alsText(p.felder(), ""));
            if (nach.uebernommen(sum)) { schon++; continue; }
            nach.speichern(p);
            nach.uebernahme(p.paket() + "/" + p.klasse() + "/" + p.name(), "Preset", sum, p.felder().size());
            pr++;
        }
        String t = String.format(Locale.GERMAN, "%d Läufe, %d Ergebnisse, %d Abbrüche, %d Ereignisse, %d Regiebücher, %d Ansichten, %d Presets (%d schon da)",
                la, er, ab, ev, rb, an, pr, schon);
        Protokoll.info("Übernahme", t + " aus " + von.wo() + " nach " + nach.wo());
        return t;
    }

    private static void fertig(DbSpeicher nach, File f, String art, String sum, int zeilen) throws Ablage.Fehler {
        nach.uebernahme(f.getPath(), art, sum, zeilen);
        File neu = new File(f.getPath() + ".uebernommen");
        for (int i = 2; neu.exists(); i++) neu = new File(f.getPath() + "." + i + ".uebernommen");
        if (!f.renameTo(neu)) Protokoll.warnung("Übernahme", f + " nicht umbenannt (die Prüfsumme verhindert ein zweites Mal)");
    }
}
