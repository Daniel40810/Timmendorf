package com.dan.timmendorf.ui;

import com.dan.fcombobox.FComboBox;
import com.dan.sail.Sail;
import com.dan.timmendorf.data.Daten;
import com.dan.timmendorf.data.Datenseite;
import com.dan.timmendorf.gl.Renderer;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Die Polare des gewählten Boots im kleinen Fenster (F45 A, Taste P): gerechnet aus {@code com.dan.sail} beim Wind der
 * Szene, dazu gestrichelt die Polare aus der Datenbank (TD_POLARE) beim nächsten Wind der Tabelle, und der Punkt, an dem
 * das Boot gerade segelt (wahrer Windwinkel, Fahrt). Rechts die Steuerbordseite, oben gegen den Wind.
 */
public final class PolarFenster {
    private PolarFenster() { }

    private static com.dan.fDialog.FDialog offen;

    /** Öffnet das Fenster oder holt es nach vorn. */
    public static void zeigen(Window owner, Renderer r) {
        if (offen != null && offen.isDisplayable()) { offen.toFront(); return; }
        com.dan.fDialog.FDialog d = new com.dan.fDialog.FDialog(owner, "Polare · Timmendorf", false);
        JPanel p = d.getComponentPane();
        p.setLayout(new BorderLayout(0, 6));
        p.setBackground(Theme.bg());
        List<Sail.Boat> boote = r.wiring.flotte.alleSegler();
        List<String> namen = new ArrayList<>();
        int j70 = 0;
        for (Sail.Boat b : boote) namen.add(b.label != null && !b.label.isEmpty() ? b.label : b.rig.name().equals(Sail.J70.name()) ? "J/70 " + (++j70) : b.rig.name());
        FComboBox wahl = new FComboBox(namen.toArray());
        JLabel info = ControlPanel.small(" ");
        Bild bild = new Bild(r, boote);
        wahl.addActionListener(e -> { bild.boot = Math.max(0, wahl.getSelectedIndex()); bild.dbFuer = null; });
        p.add(wahl, BorderLayout.NORTH);
        p.add(bild, BorderLayout.CENTER);
        p.add(info, BorderLayout.SOUTH);
        bild.info = info;
        d.setPreferredDialogSize(new Dimension(420, 520));
        d.setSize(420, 520);
        d.setLocationRelativeTo(owner);
        Timer t = new Timer(250, e -> bild.repaint());
        t.start();
        d.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) { t.stop(); offen = null; }
        });
        d.setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        offen = d;
        d.setVisible(true);
    }

    static final class Bild extends JComponent {
        final Renderer r;
        final List<Sail.Boat> boote;
        int boot;
        JLabel info;
        String dbFuer;
        List<Daten.Polarpunkt> db = List.of();
        double dbTws = Double.NaN;
        private final Sail rechner = new Sail();

        Bild(Renderer r, List<Sail.Boat> boote) { this.r = r; this.boote = boote; setPreferredSize(new Dimension(400, 400)); }

        /** Die Polare der Klasse aus der Datenbank (oder den Startdaten), im Faden der Daten gelesen. */
        private void ladeDb(Sail.Rig rig, double twsKn) {
            String key = rig.name() + "@" + Math.round(twsKn);
            if (key.equals(dbFuer)) return;
            dbFuer = key;
            com.dan.store.Dienst.spaeter(() -> {
                try {
                    for (Daten.BootKlasse k : Datenseite.speicher().bootKlassen()) {
                        if (!k.name().equals(rig.name())) continue;
                        double best = Double.NaN;
                        for (Daten.Polarpunkt p : k.polare()) if (Double.isNaN(best) || Math.abs(p.twsKn() - twsKn) < Math.abs(best - twsKn)) best = p.twsKn();
                        List<Daten.Polarpunkt> l = new ArrayList<>();
                        for (Daten.Polarpunkt p : k.polare()) if (p.twsKn() == best) l.add(p);
                        db = l;
                        dbTws = best;
                    }
                } catch (Exception e) { db = List.of(); }
            });
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Theme.bg());
            g.fillRect(0, 0, getWidth(), getHeight());
            if (boote.isEmpty()) return;
            Sail.Boat b = boote.get(Math.min(boot, boote.size() - 1));
            double ws = r.wiring.windSpeed, from = r.wiring.windFromDeg, twsKn = ws / 0.514444;
            double[][] pol = rechner.polar(b.rig, Math.max(0.5, ws));
            ladeDb(b.rig, twsKn);
            double vmax = 0.1;
            for (double[] p : pol) vmax = Math.max(vmax, p[1] / 0.514444);
            for (Daten.Polarpunkt p : db) vmax = Math.max(vmax, p.fahrtKn());
            double ring = vmax <= 4 ? 1 : vmax <= 10 ? 2 : vmax <= 20 ? 5 : 10;
            double rmax = Math.ceil(vmax / ring) * ring;
            int cx = 40, cy = getHeight() / 2, R = Math.min(getWidth() - 60, getHeight() / 2 - 20);
            g.setFont(new Font("SansSerif", Font.PLAIN, 10));
            g.setColor(Theme.line());
            for (double v = ring; v <= rmax + 1e-9; v += ring) {
                int rr = (int) (R * v / rmax);
                g.drawArc(cx - rr, cy - rr, 2 * rr, 2 * rr, -90, 180);
                g.drawString(String.format(Locale.GERMAN, "%.0f kn", v), cx + 3, cy - rr + 11);
            }
            for (int a = 0; a <= 180; a += 30) {
                double t = Math.toRadians(a);
                g.drawLine(cx, cy, cx + (int) (R * Math.sin(t)), cy - (int) (R * Math.cos(t)));
                g.drawString(a + "°", cx + (int) ((R + 10) * Math.sin(t)) - 8, cy - (int) ((R + 10) * Math.cos(t)) + 4);
            }
            // gerechnet
            Path2D path = new Path2D.Double();
            for (int i = 0; i < pol.length; i++) {
                double t = Math.toRadians(pol[i][0]), v = pol[i][1] / 0.514444 / rmax * R;
                double x = cx + v * Math.sin(t), y = cy - v * Math.cos(t);
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            g.setColor(Theme.SEE);
            g.setStroke(new BasicStroke(2f));
            g.draw(path);
            // aus der Datenbank
            if (!db.isEmpty()) {
                Path2D pd = new Path2D.Double();
                boolean erst = true;
                for (Daten.Polarpunkt p : db) {
                    double t = Math.toRadians(p.twa()), v = p.fahrtKn() / rmax * R;
                    double x = cx + v * Math.sin(t), y = cy - v * Math.cos(t);
                    if (erst) { pd.moveTo(x, y); erst = false; } else pd.lineTo(x, y);
                }
                g.setColor(Theme.GOLD);
                g.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1, new float[]{5, 4}, 0));
                g.draw(pd);
            }
            // der Punkt jetzt
            double twa = Math.abs(((b.headingDeg - from) % 360 + 540) % 360 - 180);
            double kn = b.speed / 0.514444, tt = Math.toRadians(twa), vv = Math.min(1.1, kn / rmax) * R;
            int px = cx + (int) (vv * Math.sin(tt)), py = cy - (int) (vv * Math.cos(tt));
            g.setColor(Theme.SEGEL);
            g.fillOval(px - 5, py - 5, 10, 10);
            g.setColor(Color.WHITE);
            g.drawOval(px - 5, py - 5, 10, 10);
            g.dispose();
            if (info != null)
                info.setText(String.format(Locale.GERMAN, "<html><body style='width:380px'>%s · Wind %.1f kn aus %.0f° · segelt %.0f° zum Wind mit %.1f kn, Krängung %.0f°"
                        + "<br>blau: gerechnet bei %.1f kn · gold gestrichelt: %s</body></html>", b.rig.name(), twsKn, from, twa, kn, Math.abs(b.heelDeg), twsKn,
                        db.isEmpty() ? "keine Polare in " + Datenseite.wo() : String.format(Locale.GERMAN, "TD_POLARE bei %.0f kn (%s)", dbTws, Datenseite.wo())));
        }
    }
}
