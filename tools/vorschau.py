#!/usr/bin/env python3
"""
Timmendorf · Vorschauvideo (Phase 19).

Setzt aus den Bildern in docs/bilder/ und der Bildfolge docs/status/Feuerwerk/ ein kurzes Video zusammen:
Titel, ein Tag an der Bucht vom Sonnenaufgang über Seebrücke, Regatta, Niendorf und Vogelschau bis zum
Steilufer, dann das Jahr (Seenebel, Gewitter, Sturmflut, Winter), der Abend und das Feuerwerk, Abspann.
Jedes Bild mit sachtem Zoom, Überblendung zwischen den Bildern. Ohne Ton.

    python3 tools/vorschau.py                 # video/Timmendorf_Vorschau.mp4 (1280 x 720, ~60 s) und
                                              # docs/bilder/vorschau.mp4 (960 x 540, klein für GitHub)

Braucht ffmpeg mit libx264 und Pillow (die Linux-Umgebung neben NetBeans hat beides). Die Bilder selbst kommen
von der Grafikkarte (Regiebücher, Wetterbilder, Bildfolge, Fotos mit F12); dieses Skript rechnet nichts davon neu.
"""
import os
import subprocess
import sys
import tempfile

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
B = os.path.join(ROOT, "docs", "bilder")
F = os.path.join(ROOT, "docs", "status", "Feuerwerk")
W, H, FPS = 1280, 720, 30
DAUER, BLENDE = 3.6, 0.8

# (Bild, Untertitel)
FOLGE = [
    ("sonnenaufgang.jpg", "Sonnenaufgang neben der Maritim Seebrücke"),
    ("schleife.jpg", "Die Seebrücke mit der Schleife"),
    ("unter_der_bruecke.jpg", "Unter der Seebrücke"),
    ("regatta.jpg", "Die Regatta: J/70 vor dem Start"),
    ("regatta_feld.jpg", "Die Kreuz zur Luvtonne"),
    ("niendorf.jpg", "Niendorf mit dem Bewuchs aus dem bDOM"),
    ("vogelschau.jpg", "Vogelschau über das Brodtener Ufer nach Travemünde"),
    ("steilufer.jpg", "Buchen am Brodtener Ufer im Herbst"),
    ("jahr_hafen.jpg", "Sommerabend im Niendorfer Hafen"),
    ("seenebel.jpg", "Seenebel an der Seebrücke"),
    ("gewitter.jpg", "Gewitter über der Bucht"),
    ("sturmflut.jpg", "Sturmflut 2023: Brecher vor dem Steilufer"),
    ("winter.jpg", "Schnee im Kurpark"),
    ("abendrot.jpg", "Abendrot über der Lübecker Bucht"),
    (os.path.join(F, "01_22-05.png"), "1. August, 22:05 – die Promenade füllt sich"),
    (os.path.join(F, "04_22-45.png"), "22:45 – das Feuerwerk"),
    (os.path.join(F, "06_22-48.png"), ""),
    (os.path.join(F, "07_22-49.png"), "Über Timmendorfer Strand"),
]


def schrift(groesse, fett=False):
    for f in (["Lato-Bold.ttf"] if fett else ["Lato-Regular.ttf"]) + ["DejaVuSans.ttf"]:
        for d in ("/usr/share/fonts/truetype/lato", "/usr/share/fonts/truetype/dejavu", "C:/Windows/Fonts"):
            p = os.path.join(d, f)
            if os.path.exists(p):
                return ImageFont.truetype(p, groesse)
    return ImageFont.load_default()


def karte(pfad, zeilen, symbol=True):
    im = Image.new("RGB", (W, H), (11, 14, 20))
    d = ImageDraw.Draw(im)
    y = 170
    if symbol:
        s = Image.open(os.path.join(B, "symbol.png")).convert("RGBA").resize((180, 180), Image.LANCZOS)
        im.paste(s, ((W - 180) // 2, 110), s)
        y = 330
    for text, g, fett, farbe in zeilen:
        f = schrift(g, fett)
        w = d.textlength(text, font=f)
        d.text(((W - w) / 2, y), text, font=f, fill=farbe)
        y += int(g * 1.5)
    im.save(pfad)


def untertitel(src, text, ziel):
    im = Image.open(src).convert("RGB")
    s = max(W / im.width, H / im.height)
    im = im.resize((round(im.width * s), round(im.height * s)), Image.LANCZOS)
    x, y = (im.width - W) // 2, (im.height - H) // 2
    im = im.crop((x, y, x + W, y + H))
    if text:
        d = ImageDraw.Draw(im, "RGBA")
        f = schrift(26)
        w = d.textlength(text, font=f)
        d.rounded_rectangle(((W - w) / 2 - 16, H - 70, (W + w) / 2 + 16, H - 28), 8, fill=(0, 0, 0, 140))
        d.text(((W - w) / 2, H - 66), text, font=f, fill=(240, 240, 235))
    im.save(ziel)


def main():
    tmp = tempfile.mkdtemp(prefix="vorschau_")
    bilder = []
    t0 = os.path.join(tmp, "00.png")
    karte(t0, [("Timmendorfer Strand", 54, True, (240, 236, 225)),
               ("Das Projekt des Segels", 30, False, (200, 170, 90)),
               ("Echtzeit-3D · Java 21 · OpenGL 4.3", 22, False, (150, 160, 175))])
    bilder.append(t0)
    for i, (f, text) in enumerate(FOLGE, 1):
        src = f if os.path.isabs(f) else os.path.join(B, f)
        z = os.path.join(tmp, "%02d.png" % i)
        untertitel(src, text, z)
        bilder.append(z)
    ende = os.path.join(tmp, "99.png")
    karte(ende, [("Timmendorfer Strand", 44, True, (240, 236, 225)),
                 ("Alle Bilder in Echtzeit gerechnet (AMD Radeon), Stufe Mittel", 22, False, (170, 175, 185)),
                 ("Geobasisdaten © GeoBasis-DE/LVermGeo SH · © OpenStreetMap-Mitwirkende", 20, False, (130, 135, 145))])
    bilder.append(ende)

    n = len(bilder)
    frames = int(DAUER * FPS)
    args = ["ffmpeg", "-y", "-hide_banner", "-loglevel", "error"]
    for b in bilder:
        args += ["-i", b]
    flt = []
    for i in range(n):
        zoom = "1" if i in (0, n - 1) else "1+0.06*on/%d" % frames
        flt.append("[%d:v]scale=%d:%d,zoompan=z='%s':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=%d:s=%dx%d:fps=%d,format=yuv420p,setsar=1[v%d]"
                   % (i, W * 2, H * 2, zoom, frames, W, H, FPS, i))
    last = "v0"
    for i in range(1, n):
        off = i * (DAUER - BLENDE)
        out = "x%d" % i
        flt.append("[%s][v%d]xfade=transition=fade:duration=%.2f:offset=%.2f[%s]" % (last, i, BLENDE, off, out))
        last = out
    os.makedirs(os.path.join(ROOT, "video"), exist_ok=True)
    gross = os.path.join(ROOT, "video", "Timmendorf_Vorschau.mp4")
    subprocess.run(args + ["-filter_complex", ";".join(flt), "-map", "[%s]" % last, "-c:v", "libx264", "-preset", "veryfast",
                           "-crf", "20", "-pix_fmt", "yuv420p", "-movflags", "+faststart", gross], check=True)
    klein = os.path.join(B, "vorschau.mp4")
    subprocess.run(["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-i", gross, "-vf", "scale=960:540", "-c:v", "libx264",
                    "-preset", "veryfast", "-crf", "28", "-pix_fmt", "yuv420p", "-movflags", "+faststart", klein], check=True)
    for p in (gross, klein):
        print("%s  %.1f MB" % (os.path.relpath(p, ROOT), os.path.getsize(p) / 1e6))


if __name__ == "__main__":
    sys.exit(main())
