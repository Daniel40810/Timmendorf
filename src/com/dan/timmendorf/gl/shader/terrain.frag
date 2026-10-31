// terrain.frag — das Gelände (aus Sylt, seit Phase 4 das Grundbild): Farbe aus dem Luftbild DOP20 2025 (im Kern zu 1 m,
// uLuftKern; im Hauptraster zu 10 m, uLuftHaupt; sRGB-Texturen, also linear gelesen), wo es fehlt nach der Art des Grunds
// (Terrain.ART). Die Klassen je Meter (uKlasse, aus DOP20, ALKIS, bDOM) nehmen die Dächer aus dem Bild (die Häuser aus
// LOD2 stehen darauf; ihr Dach im Luftbild liegt etwas versetzt) und geben dem Strand helleren, dem Spülsaum nassen
// Sand. Steile Hänge seit Phase 9 am Brodtener Ufer aus com.dan.cliff (Cliff.brodtenerUfer), sonst nach Neigung [G]. Licht: mit
// com.dan.sky Sonne und Mond vorbelichtet, Himmelslicht aus uSkyAmb, Wolkenschatten aus com.dan.cloud (uCloudShadow);
// sonst der Platzhalter aus Phase 1. Dazu zuschaltbar die Marken (marken.glsl). Unter dem Wasser zeichnet com.dan.ocean.
// Seit Phase 12 abends das Licht der Leuchten und des Feuerwerks (com.dan.lamp, lamp.glsl). Seit Phase 21 am Auge Relief
// und Korn des Strands aus com.dan.beach (beach.glsl, gebacken aus dem Sand-Asset): trocken und zertreten oben, glatt am Wasser.
#include "common.glsl"
#include "terrain_height.glsl"
#include "marken.glsl"
#include "/com/dan/cliff/gl/cliff.glsl"
#include "/com/dan/lamp/gl/lamp.glsl"
#include "/com/dan/beach/gl/beach.glsl"
uniform float uLampK;            // seit Phase 12: Leuchten (com.dan.lamp, LampGl): cd/m² → vorbelichtet; 0 = aus
uniform float uLampRange;        // bis zu diesem Abstand vom Auge rechnen (m)
uniform vec4 uSchnee;            // seit Phase 13 (com.dan.season): Schnee am Boden (m), Reif 0..1, Pegel, frei
uniform int uCliffOn;            // seit Phase 9: Brodtener Ufer aus com.dan.cliff (Wand- und Schichtkarte, CliffGl.bind)
in vec3 vWorld;
in vec3 vNormal;
in float vDist;
out vec4 frag;

uniform usampler2D uKlasse;      // Klassen im Kern zu 1 m (R8UI, Zeilen von Nord nach Süd wie das Kernraster)
uniform sampler2D uLuftKern;     // Luftbild im Kern (sRGB, 1 m)
uniform sampler2D uLuftHaupt;    // Luftbild im Hauptraster (sRGB, 10 m; schwarz, wo keine Kachel liegt)
uniform int uLuftOn;             // Bit 0 Kern, Bit 1 Hauptraster, Bit 2 Klassen
uniform sampler2D uCloudShadow;  // Wolkenschatten (com.dan.cloud), Draufsicht
uniform vec4 uCloudRect;         // x, z der Ecke, Kantenlänge, an

vec3 srgb(vec3 c) { return pow(c, vec3(2.2)); }

float hash2(vec2 i) { return fract(sin(dot(i, vec2(127.1, 311.7))) * 43758.5453); }
float vnoise(vec2 x) {
    vec2 i = floor(x), f = x - i;
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash2(i), hash2(i + vec2(1, 0)), f.x), mix(hash2(i + vec2(0, 1)), hash2(i + vec2(1, 1)), f.x), f.y);
}
// Flecken der Größe s (m); in der Ferne (Bildpunkt pxm m) zum Mittel 0,5
float fleck(vec2 p, float s, float pxm) { return mix(vnoise(p / s), 0.5, smoothstep(0.15 * s, 0.6 * s, pxm)); }

// Farben je Art [G nach Strandkorb1–2, Promenade1–2, Kurpark1–2, Brodten1–3; Phase 2 eicht sie am DOP20]. Albedo linear:
// trockener heller Ostseesand rund 0,4, nasser 0,2, Acker und Wiese 0,1–0,15.
vec3 artColor(uint a, vec2 p, float pxm) {
    float f = fleck(p, 6.0, pxm), g = fleck(p + 17.0, 25.0, pxm), h = fleck(p + 41.0, 90.0, pxm);
    if (a == 1u) return srgb(vec3(0.62, 0.57, 0.46));                                        // Wasser: Sand unter Wasser
    if (a == 2u) return srgb(mix(vec3(0.42, 0.44, 0.28), vec3(0.52, 0.50, 0.33), f));         // Schilf und Ufer
    if (a == 3u) return srgb(mix(vec3(0.74, 0.69, 0.58), vec3(0.79, 0.75, 0.66), f));         // Strand: heller Ostseesand
    if (a == 4u) return srgb(mix(vec3(0.54, 0.49, 0.39), vec3(0.58, 0.53, 0.42), f));         // nasser Sand, Spülsaum
    if (a == 5u) return srgb(mix(vec3(0.58, 0.50, 0.38), vec3(0.50, 0.44, 0.34), g));         // Steilufer: Geschiebemergel
    if (a == 6u) return srgb(mix(mix(vec3(0.46, 0.50, 0.26), vec3(0.62, 0.55, 0.34), h), vec3(0.50, 0.48, 0.30), 0.3 * f)); // Acker
    if (a == 7u) return srgb(mix(vec3(0.16, 0.24, 0.13), vec3(0.21, 0.28, 0.15), f));         // Wald: Buchen, Kiefern
    if (a == 8u) return srgb(mix(vec3(0.24, 0.32, 0.18), vec3(0.30, 0.36, 0.21), f));         // Gebüsch, Knick
    if (a == 9u) return srgb(mix(vec3(0.40, 0.52, 0.28), vec3(0.47, 0.56, 0.32), f));         // Wiese, Park
    if (a == 10u) return srgb(mix(vec3(0.56, 0.50, 0.46), vec3(0.42, 0.44, 0.34), g));        // Siedlung: Dächer, Gärten
    if (a == 11u) return srgb(vec3(0.66, 0.63, 0.58));                                        // Weg, Promenade
    return srgb(vec3(0.50, 0.52, 0.40));
}

void main() {
    vec2 p = vWorld.xz;
    // Normale im Bildpunkt aus der Höhe (fein in der Nähe, gröber in der Ferne), nie enger als das Raster an diesem Ort
    float kern = uKernOn == 1 ? smoothstep(0.0, uKNaht, insideOf(p, uKernGrid, uKernN)) : 0.0;
    float e = clamp(vDist * 0.003, mix(uGrid.z * 0.8, 1.0, kern), 80.0);
    float lvl = heightLevel(vDist) - 1.0;
    float hx = heightAt(p + vec2(e, 0.0), lvl) - heightAt(p - vec2(e, 0.0), lvl);
    float hz = heightAt(p + vec2(0.0, e), lvl) - heightAt(p - vec2(0.0, e), lvl);
    vec3 n = normalize(mix(vNormal, normalize(vec3(-hx, 2.0 * e, -hz)), 1.0 - smoothstep(4000.0, 12000.0, vDist)));
    float pxm = length(fwidth(p));

    // Art weich an den Rändern: die vier nächsten Zellen, bilinear gewichtet
    vec3 alb = vec3(0.0);
    float sandW = 0.0;   // seit Phase 21: Anteil Strand und Spülsaum (Art 3 und 4) für com.dan.beach
    {
        vec2 u = (p - uGrid.xy) / uGrid.z - 0.5;
        ivec2 i = ivec2(floor(u));
        vec2 f = u - vec2(i);
        bool drin = insideDist(p) > 0.0;
        for (int b = 0; b <= 1; b++)
            for (int c = 0; c <= 1; c++) {
                ivec2 q = clamp(i + ivec2(c, b), ivec2(0), ivec2(int(uGridN) - 1));
                uint a = drin ? texelFetch(uSource, q, 0).r : (vWorld.y < 0.0 ? 1u : 9u);
                float wt = (c == 0 ? 1.0 - f.x : f.x) * (b == 0 ? 1.0 - f.y : f.y);
                alb += artColor(a, p, pxm) * wt;
                if (a == 3u || a == 4u) sandW += wt;
            }
    }
    // Luftbild: im Kern zu 1 m, sonst im Hauptraster zu 10 m (nur wo eine Kachel lag); Albedo ≈ 0,85 · Bild [G]
    float inK = insideOf(p, uKernGrid, uKernN);
    bool luftK = (uLuftOn & 1) != 0 && inK > 0.0;
    vec3 luft = vec3(0.0);
    float luftW = 0.0;
    if ((uLuftOn & 2) != 0 && insideDist(p) > 0.0) {
        vec3 c = texture(uLuftHaupt, gridUV(p)).rgb;
        if (dot(c, vec3(1.0)) > 0.004) { luft = c; luftW = 1.0; }
    }
    if (luftK) {
        vec3 c = texture(uLuftKern, kernUV(p)).rgb;
        float wk = smoothstep(0.0, 60.0, inK);
        if (dot(c, vec3(1.0)) > 0.004) { luft = mix(luft, c, luftW > 0.0 ? wk : 1.0); luftW = max(luftW, wk); }
    }
    if (luftW > 0.0) {
        vec3 a = luft * 0.85 * (0.92 + 0.16 * fleck(p, 1.7, pxm));
        // Klassen je Meter: Dächer aus dem Bild nehmen (Pflaster und Gärten), Strand heller, Spülsaum nass [G]
        if ((uLuftOn & 4) != 0 && inK > 0.5 && vDist < 2500.0) {
            ivec2 q = clamp(ivec2(floor((p - uKernGrid.xy) / uKernGrid.z)), ivec2(0), ivec2(int(uKernN) - 1));
            uint k = texelFetch(uKlasse, q, 0).r;
            float nah = 1.0 - smoothstep(1500.0, 2500.0, vDist);
            if (k == 10u) a = mix(a, srgb(mix(vec3(0.46, 0.46, 0.42), vec3(0.36, 0.42, 0.28), fleck(p, 4.0, pxm))), 0.75 * nah);
            if (k == 3u) a = mix(a, a * 1.08, nah);
            if (k == 4u) a = mix(a, a * 0.82, nah);
        }
        alb = mix(alb, a, luftW);
    }
    // seit Phase 9: im Rechteck der Karte das Brodtener Ufer aus com.dan.cliff (Geschiebemergel, Narbe, Runsen, Findlinge,
    // Geröll am Fuß); die Ableitungen im Bild stehen außerhalb jeder ungleichmäßigen Verzweigung
    float imKliff = 0.0;
    if (uCliffOn == 1) {
        vec2 r = (p - uCliffMapRect.xy) / uCliffMapRect.z;
        imKliff = smoothstep(0.0, 0.03, min(min(r.x, r.y), min(1.0 - r.x, 1.0 - r.y)));
        CliffSample cs = cliffMaterial(vWorld, n, 0.0, 0.0, max(pxm, 0.02));
        float k = cs.rock * imKliff;
        alb = mix(alb, cs.albedo, k);
        n = normalize(mix(n, cs.normal, k));
    }
    // steiler Hang außerhalb der Karte (Dünen, Kanten im Kern): Mergel nach Neigung [G]
    float wand = smoothstep(0.75, 0.45, n.y) * (1.0 - imKliff);
    if (wand > 0.0) {
        float bandRausch = fleck(vec2(p.x + p.y, vWorld.y * 6.0), 2.0, pxm);
        vec3 mergel = srgb(mix(vec3(0.62, 0.54, 0.42), vec3(0.52, 0.45, 0.35), bandRausch));
        vec3 sand = srgb(mix(vec3(0.72, 0.66, 0.54), vec3(0.66, 0.60, 0.49), bandRausch));
        alb = mix(alb, mix(sand, mergel, smoothstep(2.0, 5.0, vWorld.y)), wand);
    }
    // seit Phase 21: Relief und Korn des Strands am Auge (com.dan.beach): über dem Pegel (uSchnee.z) trocken ab 0,9 m,
    // zertreten auf dem oberen Strand mit den Körben und den Wegen zum Wasser [G]; unter Schnee nichts
    float sunK = 1.0;
    sandW *= (1.0 - wand) * (1.0 - imKliff) * (1.0 - smoothstep(0.002, 0.06, uSchnee.x));
    {   // ohne Verzweigung: die Textur braucht ihre Ableitungen im Bild
        float ueber = vWorld.y - uSchnee.z;
        BeachSample bs = beachSurface(p, n, pxm, vDist, smoothstep(0.15, 0.9, ueber), smoothstep(0.6, 1.4, ueber), uSun);
        n = normalize(mix(n, bs.normal, sandW));
        alb *= mix(1.0, bs.albedo, sandW);
        sunK = mix(1.0, bs.sun, sandW);
    }
    // Fernring: außerhalb des Hauptrasters eine Mischfarbe nach der Höhe
    float fern = 1.0 - smoothstep(0.0, uNaht, insideDist(p));
    alb = mix(alb, mix(srgb(vec3(0.66, 0.61, 0.50)), srgb(vec3(0.40, 0.46, 0.28)), smoothstep(1.0, 5.0, vWorld.y)), fern);

    // seit Phase 13: Schnee auf flachem Grund über dem Spülsaum (wo die Wellen laufen, bleibt er nicht), Reif auf allem
    if (uSchnee.x > 0.002 || uSchnee.y > 0.05) {
        float flach = smoothstep(0.55, 0.85, n.y), trocken = smoothstep(uSchnee.z + 0.3, uSchnee.z + 0.9, vWorld.y);
        float decke = smoothstep(0.002, 0.06, uSchnee.x) * flach * trocken * (0.85 + 0.15 * vnoise(p * 0.7));
        alb = mix(alb, vec3(0.86, 0.88, 0.92), decke);
        alb = mix(alb, vec3(0.72, 0.76, 0.80), 0.35 * uSchnee.y * trocken * (1.0 - decke));
    }
    float cloud = uCloudRect.w > 0.0 ? textureLod(uCloudShadow, (p - uCloudRect.xy) / uCloudRect.z, 0.0).r : 1.0;
    vec3 col;
    float B;
    if (uSkyMode == 1) {
        vec3 direct = (sunLight() * max(dot(n, uSun), 0.0) * sunK + moonLight() * max(dot(n, uMoonDir), 0.0)) * cloud;
        vec3 amb = uSkyAmb * (0.6 + 0.4 * n.y);
        col = alb * (direct / 3.14159265 + amb);
        B = 0.32 * dot(sunLight() * cloud * max(uSun.y, 0.0) / 3.14159265 + uSkyAmb + moonLight() * max(uMoonDir.y, 0.0) / 3.14159265, vec3(0.2126, 0.7152, 0.0722));
    } else {
        vec3 L = sunLight();
        float sun = max(dot(n, uSun), 0.0) * sunK;
        B = skyBrightness(sunElevDeg());
        vec3 amb = vec3(0.30, 0.38, 0.52) * B / 0.32 * (0.55 + 0.45 * n.y);
        col = alb * (L * sun * cloud + amb);
    }
    // seit Phase 12: Licht der Leuchten und der Bomben (lampIrradiance in lx, Albedo/π → cd/m²)
    if (uLampK > 0.0 && uLampCount > 0 && vDist < uLampRange) col += alb * lampIrradiance(vWorld, n) / 3.14159265 * uLampK;
    col = applyMarks(col, p, vWorld.y, vDist, B);
    vec3 v = vWorld - uCam;
    float dist = length(v);
    col = aerial(col, v / max(dist, 1e-3), dist);
    frag = vec4(col, 1.0);
}
