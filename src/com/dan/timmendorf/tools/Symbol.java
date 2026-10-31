package com.dan.timmendorf.tools;

import com.dan.timmendorf.ui.AppIcon;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 19 (aus Haithabu Phase 17): das Programmsymbol mit Goldkante (F31 A) als Dateien: die PNGs in allen Größen ins Jar
 * ({@code src/com/dan/timmendorf/ui/icon/timmendorf_NN.png}) und {@code icon/Timmendorf.ico} für die Verknüpfung
 * unter Windows (ICO mit PNG-Einträgen, 16 bis 256 Pixel), dazu {@code docs/bilder/symbol.png} für die README.
 * <pre>
 * Symbol [projektordner]
 * </pre>
 */
public final class Symbol {
    private Symbol() { }

    public static void main(String[] a) throws IOException {
        File base = new File(a.length > 0 ? a[0] : ".");
        File res = new File(base, "src/com/dan/timmendorf/ui/icon"), ico = new File(base, "icon"), bilder = new File(base, "docs/bilder");
        res.mkdirs();
        ico.mkdirs();
        bilder.mkdirs();
        List<BufferedImage> imgs = new ArrayList<>();
        for (int s : AppIcon.SIZES) {
            BufferedImage im = AppIcon.paint(s);
            ImageIO.write(im, "png", new File(res, "timmendorf_" + s + ".png"));
            imgs.add(im);
        }
        ImageIO.write(AppIcon.paint(256), "png", new File(bilder, "symbol.png"));
        schreibeIco(new File(ico, "Timmendorf.ico"), imgs);
        System.out.println("Symbol: " + AppIcon.SIZES.length + " PNGs nach " + res + ", " + new File(ico, "Timmendorf.ico") + " (" + new File(ico, "Timmendorf.ico").length() + " Bytes)");
    }

    /** ICO-Datei mit PNG-Einträgen (seit Windows Vista; 256 Pixel müssen als PNG stehen). */
    public static void schreibeIco(File f, List<BufferedImage> imgs) throws IOException {
        List<byte[]> png = new ArrayList<>();
        for (BufferedImage im : imgs) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            ImageIO.write(im, "png", o);
            png.add(o.toByteArray());
        }
        int n = imgs.size(), offset = 6 + 16 * n;
        ByteBuffer b = ByteBuffer.allocate(offset).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) 0).putShort((short) 1).putShort((short) n);
        for (int i = 0; i < n; i++) {
            int s = imgs.get(i).getWidth();
            b.put((byte) (s >= 256 ? 0 : s)).put((byte) (s >= 256 ? 0 : s)).put((byte) 0).put((byte) 0);
            b.putShort((short) 1).putShort((short) 32).putInt(png.get(i).length).putInt(offset);
            offset += png.get(i).length;
        }
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(f))) {
            out.write(b.array());
            for (byte[] p : png) out.write(p);
        }
    }

    /** Liest die Größen aus einer ICO-Datei (für die Prüfung). */
    public static int[] icoGroessen(File f) throws IOException {
        byte[] d = java.nio.file.Files.readAllBytes(f.toPath());
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getShort() != 0 || b.getShort() != 1) throw new IOException("keine ICO-Datei");
        int n = b.getShort();
        int[] s = new int[n];
        for (int i = 0; i < n; i++) {
            int w = b.get() & 255;
            b.position(b.position() + 7);
            int len = b.getInt(), off = b.getInt();
            if (off + len > d.length) throw new IOException("Eintrag " + i + " reicht über das Dateiende");
            if (!(d[off] == (byte) 0x89 && d[off + 1] == 'P' && d[off + 2] == 'N' && d[off + 3] == 'G')) throw new IOException("Eintrag " + i + " ist kein PNG");
            s[i] = w == 0 ? 256 : w;
        }
        return s;
    }
}
