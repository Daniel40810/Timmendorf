package com.dan.timmendorf.tools;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Vergleich zweier Vergleichsblätter aus dem Rückfluss (Phase 18): Feld für Feld die mittlere Abweichung je Kanal
 * (0–255), der Anteil der Pixel mit mehr als {@value #SPUERBAR} Stufen und die größte Abweichung; dazu ein Bild mit
 * vorher, nachher und der Abweichung (zehnfach, rot) je Feld und ein Bericht als Text.
 * <pre>
 * Bildvergleich projekt vorher.png nachher.png ausgabe.png [spalten×zeilen] [kopfzeile px] [nur felder, etwa 6,7,8]
 * </pre>
 * Ein Feld <b>stimmt</b>, wenn die mittlere Abweichung unter {@value #MITTEL_GRENZE} liegt und höchstens
 * {@value #ANTEIL_GRENZE} % der Pixel spürbar abweichen [G: TAA-Zittern und Rauschen der Wolken bleiben darunter].
 * Mit „nur“ werden die Felder genannt, in denen eine Änderung gewollt ist; sie werden gezeigt, aber nicht bewertet.
 */
public final class Bildvergleich {
    private Bildvergleich() { }

    static final int SPUERBAR = 16;
    static final double MITTEL_GRENZE = 2.0, ANTEIL_GRENZE = 1.0;

    /** Raster der Blätter je Projekt: Spalten, Zeilen, Kopfzeile in Pixeln. */
    static int[] raster(String projekt) {
        return switch (projekt.toLowerCase(Locale.ROOT)) {
            case "thunder" -> new int[]{5, 3, 34};
            case "lighthouse" -> new int[]{4, 3, 0};
            case "haithabu" -> new int[]{4, 2, 34};
            default -> new int[]{1, 1, 0};
        };
    }

    public static void main(String[] a) throws Exception {
        String projekt = a[0];
        BufferedImage v = ImageIO.read(new File(a[1])), n = ImageIO.read(new File(a[2]));
        File out = new File(a[3]);
        int[] r = raster(projekt);
        if (a.length > 4 && !a[4].equals("-")) { String[] s = a[4].split("[x×]"); r[0] = Integer.parseInt(s[0]); r[1] = Integer.parseInt(s[1]); }
        if (a.length > 5 && !a[5].equals("-")) r[2] = Integer.parseInt(a[5]);
        String gewollt = a.length > 6 ? "," + a[6] + "," : ",";
        if (v.getWidth() != n.getWidth() || v.getHeight() != n.getHeight()) {
            System.out.printf("FEHLER Größe vorher %d×%d, nachher %d×%d%n", v.getWidth(), v.getHeight(), n.getWidth(), n.getHeight());
            System.exit(1);
        }
        int cols = r[0], rows = r[1], top = r[2];
        int cw = v.getWidth() / cols, ch = (v.getHeight() - top) / rows;
        int sw = Math.min(cw, 480), sh = sw * ch / cw;
        BufferedImage bild = new BufferedImage(3 * sw, 30 + cols * rows * (sh + 24), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = bild.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setColor(new Color(20, 22, 26));
        g.fillRect(0, 0, bild.getWidth(), bild.getHeight());
        g.setColor(new Color(235, 230, 220));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
        g.drawString(projekt + " · links vorher, Mitte nachher, rechts die Abweichung ×10", 10, 21);
        StringBuilder bericht = new StringBuilder();
        bericht.append(String.format(Locale.GERMAN, "%s · %s gegen %s · %d × %d Felder zu %d × %d%n", projekt, a[1], a[2], cols, rows, cw, ch));
        int fehler = 0, feld = 0;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                feld++;
                int x0 = col * cw, y0 = top + row * ch;
                BufferedImage diff = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_RGB);
                double sum = 0;
                long spuer = 0;
                int max = 0;
                for (int y = 0; y < ch; y++) {
                    for (int x = 0; x < cw; x++) {
                        int p = v.getRGB(x0 + x, y0 + y), q = n.getRGB(x0 + x, y0 + y);
                        int d = Math.max(Math.abs(((p >> 16) & 255) - ((q >> 16) & 255)), Math.max(Math.abs(((p >> 8) & 255) - ((q >> 8) & 255)), Math.abs((p & 255) - (q & 255))));
                        sum += (Math.abs(((p >> 16) & 255) - ((q >> 16) & 255)) + Math.abs(((p >> 8) & 255) - ((q >> 8) & 255)) + Math.abs((p & 255) - (q & 255))) / 3.0;
                        if (d > SPUERBAR) spuer++;
                        max = Math.max(max, d);
                        int grau = (((q >> 16) & 255) + ((q >> 8) & 255) + (q & 255)) / 12;
                        int rot = Math.min(255, grau + d * 10);
                        diff.setRGB(x, y, (rot << 16) | (grau << 8) | grau);
                    }
                }
                double mittel = sum / ((double) cw * ch), anteil = 100.0 * spuer / ((double) cw * ch);
                boolean soll = gewollt.contains("," + feld + ",");
                boolean ok = mittel < MITTEL_GRENZE && anteil <= ANTEIL_GRENZE;
                if (!ok && !soll) fehler++;
                String zeile = String.format(Locale.GERMAN, "%-8s Feld %2d: mittlere Abweichung %.2f, spürbar %.2f %%, größte %d", soll ? "GEWOLLT" : ok ? "ok" : "ANDERS", feld, mittel, anteil, max);
                bericht.append(zeile).append('\n');
                System.out.println(zeile);
                int yy = 30 + (feld - 1) * (sh + 24);
                g.drawImage(v.getSubimage(x0, y0, cw, ch), 0, yy + 24, sw, sh, null);
                g.drawImage(n.getSubimage(x0, y0, cw, ch), sw, yy + 24, sw, sh, null);
                g.drawImage(diff, 2 * sw, yy + 24, sw, sh, null);
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
                g.setColor(soll ? new Color(120, 180, 240) : ok ? new Color(140, 210, 150) : new Color(240, 120, 110));
                g.drawString(zeile, 8, yy + 17);
            }
        }
        g.dispose();
        File dir = out.getAbsoluteFile().getParentFile();
        if (dir != null) dir.mkdirs();
        ImageIO.write(bild, "png", out);
        String ergebnis = fehler == 0 ? "ERGEBNIS: alle Felder stimmen" : "ERGEBNIS: " + fehler + " Felder anders";
        bericht.append(ergebnis).append('\n');
        File txt = new File(out.getPath().replaceAll("\\.png$", "") + ".txt");
        try (PrintWriter w = new PrintWriter(txt, StandardCharsets.UTF_8)) { w.print(bericht); }
        System.out.println(ergebnis + " · Bild " + out.getPath() + " · Bericht " + txt.getPath());
        System.exit(fehler == 0 ? 0 : 1);
    }
}
