package com.dan.timmendorf.ui;

import com.dan.store.Felder;
import com.dan.store.Protokoll;
import com.dan.timmendorf.data.Chronik;
import com.dan.timmendorf.data.Daten;
import com.dan.timmendorf.data.Zustand;
import com.dan.timmendorf.gl.Quality;
import com.dan.timmendorf.gl.Renderer;

import javax.swing.Timer;
import java.util.List;
import java.util.Map;

/**
 * „Weiter wie zuletzt“ (F38–F40 A, aus Sylt): einen gesicherten Zustand herstellen, sobald die Szene ihre ersten Bilder
 * hat (höchstens 60 s warten). Uhr, Zeitraffer, Wetterregie, Kamera, alle Regler und die laufende Wettfahrt; das Brodtener
 * Ufer, die Sturmflut und das Feuerwerk folgen der Uhr. Ein Regiebuch, das lief, beginnt man im Block REGIEBÜCHER neu
 * (die Stelle steht in der Sitzung und im HUD).
 */
public final class Weiter {
    private Weiter() { }

    private static boolean bereit(Renderer r) { return r.frames() > 10; }

    /** Wartet höchstens 60 s auf die Szene, dann stellt es her. */
    public static void anwenden(Renderer r, Daten.Sitzung s, Chronik chronik) {
        if (s == null || s.zustand() == null) return;
        spaeter(r, () -> jetzt(r, s.zustand(), "Sitzung " + s.id(), chronik));
    }

    /** Ein Regiebuch im Kino, sobald die Szene steht (höchstens 60 s warten). */
    public static void kino(Renderer r, com.dan.timmendorf.scene.Regie.Book b) { spaeter(r, () -> r.regisseur.kino(b)); }

    private static void spaeter(Renderer r, Runnable x) {
        long bis = System.currentTimeMillis() + 60_000;
        Timer t = new Timer(250, null);
        t.addActionListener(e -> {
            if (!bereit(r) && System.currentTimeMillis() < bis) return;
            t.stop();
            x.run();
        });
        t.start();
    }

    /** Sofort herstellen (die Szene steht schon; aus dem Swing-Faden). Auch für gemerkte Ansichten mit Uhr. */
    public static void jetzt(Renderer r, String zustand, String woher, Chronik chronik) {
        Map<String, String> z = Felder.ausText(zustand);
        Quality q = Zustand.qualitaet(z);
        if (q != null && q != r.quality()) r.setQuality(q);
        if (z.containsKey("app.ton") && !r.klang.offline) r.klang.setOn(Boolean.parseBoolean(z.get("app.ton")));
        if (r.regisseur.regie.running()) r.regisseur.stop();
        List<String> f = Zustand.anwenden(r, z);
        if (chronik != null) chronik.hergestellt();
        String rest = z.containsKey("regiebuch.name") ? " · „" + z.get("regiebuch.name") + "“ lief bei " + z.getOrDefault("regiebuch.sekunde", "?") + " s: Block REGIEBÜCHER" : "";
        Protokoll.info("Sitzung", "Weiter wie zuletzt (" + woher + "): " + z.size() + " Werte" + (f.isEmpty() ? "" : ", nicht hergestellt: " + String.join(", ", f)));
        r.hudMeldung("Weiter wie zuletzt · " + z.getOrDefault("uhr.text", "") + (z.containsKey("regatta.zeit") ? " · die Wettfahrt segelt weiter" : "") + rest);
    }
}
