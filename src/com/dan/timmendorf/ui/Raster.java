package com.dan.timmendorf.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;

/**
 * Phase 19 (Daniel, 31.10.: „Knöpfe so anordnen, dass man den Text lesen kann“): ein Raster für Knöpfe im Bedienfeld,
 * das umbricht. Bis Phase 18 lag jede Gruppe in einem {@code GridLayout(1, n)} – einer einzigen Zeile, die sich bei mehr
 * Knöpfen als n nur weiter teilte (DATEN: acht Knöpfe in einer Zeile). Jetzt höchstens {@code maxSpalten} Spalten, und nur
 * so viele, wie die breiteste Beschriftung ganz hineinpasst; der Rest bricht in neue Zeilen um. Alle Zellen gleich breit.
 */
public final class Raster implements LayoutManager {
    /** Nutzbare Breite, solange das Bedienfeld noch nicht ausgelegt ist (Bedienfeld 360 px abzüglich Rand und Leiste). */
    public static final int BREITE_VORGABE = 320;
    final int maxSpalten, hgap, vgap;

    public Raster(int maxSpalten) { this(maxSpalten, 6, 4); }

    Raster(int maxSpalten, int hgap, int vgap) {
        this.maxSpalten = Math.max(1, maxSpalten);
        this.hgap = hgap;
        this.vgap = vgap;
    }

    /** Spalten für diese Breite: so viele, dass die breiteste Beschriftung ganz hineinpasst, höchstens maxSpalten. */
    public int spalten(Container p, int breite) {
        int n = p.getComponentCount();
        if (n == 0) return 1;
        int w = 0;
        for (Component c : p.getComponents()) w = Math.max(w, noetig(c));
        int s = Math.min(maxSpalten, n);
        while (s > 1 && s * w + (s - 1) * hgap > breite) s--;
        return s;
    }

    /**
     * Breite, die ein Knopf braucht, damit sein Text ganz zu lesen ist: Text plus 30 px Rand (die Knöpfe aus FStyle wünschen
     * sich gut 50 px Innenabstand; damit fiele fast alles auf eine Spalte), höchstens seine bevorzugte Breite.
     */
    public static int noetig(Component c) {
        int pref = c.getPreferredSize().width;
        if (c instanceof javax.swing.AbstractButton b && b.getText() != null && !b.getText().startsWith("<html>"))
            return Math.min(pref, b.getFontMetrics(b.getFont()).stringWidth(b.getText()) + 30);
        return pref;
    }

    private int breite(Container p) {
        Insets in = p.getInsets();
        int w = p.getWidth() - in.left - in.right;
        return w > 0 ? w : BREITE_VORGABE;
    }

    @Override public Dimension preferredLayoutSize(Container p) {
        int n = p.getComponentCount();
        Insets in = p.getInsets();
        if (n == 0) return new Dimension(in.left + in.right, in.top + in.bottom);
        int s = spalten(p, breite(p)), z = (n + s - 1) / s, h = 0, w = 0;
        for (Component c : p.getComponents()) { h = Math.max(h, c.getPreferredSize().height); w = Math.max(w, c.getPreferredSize().width); }
        return new Dimension(in.left + in.right + s * w + (s - 1) * hgap, in.top + in.bottom + z * h + (z - 1) * vgap);
    }

    @Override public Dimension minimumLayoutSize(Container p) {
        Dimension d = preferredLayoutSize(p);
        return new Dimension(0, d.height);
    }

    @Override public void layoutContainer(Container p) {
        int n = p.getComponentCount();
        if (n == 0) return;
        Insets in = p.getInsets();
        int b = breite(p), s = spalten(p, b), h = 0;
        for (Component c : p.getComponents()) h = Math.max(h, c.getPreferredSize().height);
        int zw = (b - (s - 1) * hgap) / s;
        // die Höhe kam aus der Vorgabebreite; passt sie zur echten Breite nicht, einmal neu auslegen lassen
        int z = (n + s - 1) / s, soll = in.top + in.bottom + z * h + (z - 1) * vgap;
        if (soll > p.getHeight() && p instanceof javax.swing.JComponent jc) javax.swing.SwingUtilities.invokeLater(jc::revalidate);
        for (int i = 0; i < n; i++) {
            int sp = i % s, ze = i / s;
            p.getComponent(i).setBounds(in.left + sp * (zw + hgap), in.top + ze * (h + vgap), zw, h);
        }
    }

    @Override public void addLayoutComponent(String name, Component c) { }
    @Override public void removeLayoutComponent(Component c) { }
}
