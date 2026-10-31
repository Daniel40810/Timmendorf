// bauten.frag — Baustoffe der Türme und Bauten (aus Sylt; Timmendorf seit Phase 4, Bauwerke am Wasser seit Phase 9). Muster aus dem Ort: u längs der Wand (aus der Normale),
// Höhe über dem Grund des Bauwerks (Merkmal); in der Ferne gehen die Muster in ihr Mittel über (Bildpunkt in m).
// Licht wie das Gelände: Sonne und Mond vorbelichtet, Himmelslicht (uSkyAmb), Wolkenschatten; Glas spiegelt den Himmel
// nach Fresnel; Fenster der Häuser leuchten nachts zum Teil (uWarm), die Laternen nach dem Leuchtfeuer (uLamp*).
#include "common.glsl"
#include "/com/dan/lamp/gl/lamp.glsl"
uniform float uLampK;            // seit Phase 12: Leuchten (com.dan.lamp, LampGl): cd/m² → vorbelichtet; 0 = aus
uniform float uLampRange;        // bis zu diesem Abstand vom Auge rechnen (m)
uniform vec4 uSchnee;            // seit Phase 13: Schnee am Boden (m), Reif 0..1, Schnee auf den Dächern (m), frei
uniform float uFenster;          // seit Phase 12: Anteil erleuchteter Fenster (com.dan.lamp.Windows nach der Uhrzeit)
in vec3 vWorld;
in vec3 vNormal;
flat in uint vAttr;
out vec4 frag;

uniform sampler2D uCloudShadow;
uniform vec4 uCloudRect;        // x, z der Ecke, Kantenlänge, an
uniform float uNight;           // 0 Tag … 1 Nacht (erleuchtete Fenster)
uniform vec3 uWarm;             // Leuchtdichte eines erleuchteten Fensters (vorbelichtet)
uniform vec4 uTurmQ, uTurmK;    // x, z, Höhe des Brennpunkts (m NHN), halbe Höhe der Linse
uniform vec3 uLensQ, uLensK;    // Leuchtdichte der Linse zum Auge (vorbelichtet)
uniform vec3 uGlowQ, uGlowK;    // Leuchtdichte der übrigen Laterne (Streulicht, vorbelichtet)
uniform vec3 uLampe;            // seit Phase 9: Leuchtdichte einer Leuchte (Laternen, vorbelichtet); seit Phase 12 LED 4 000 K, an mit dem Dämmerungsschalter

const float PI = 3.14159265;

// Albedo je Baustoff (linear) [G nach Leuchturm1–4, Kampen1–4 und dem Ortsbild]; Reihenfolge wie Netz.java
const vec3 ALB[28] = vec3[28](
    vec3(0.31, 0.060, 0.035),   //  0 Backstein
    vec3(0.72, 0.72, 0.69),     //  1 Putz
    vec3(0.19, 0.13, 0.075),    //  2 Reet
    vec3(0.060, 0.065, 0.072),  //  3 Dach grau
    vec3(0.30, 0.065, 0.035),   //  4 Dach rot
    vec3(0.13, 0.40, 0.22),     //  5 Kupfer (Patina)
    vec3(0.02, 0.025, 0.03),    //  6 Glas Quermarke
    vec3(0.02, 0.025, 0.03),    //  7 Glas Kampen
    vec3(0.025),                //  8 Eisen
    vec3(0.22, 0.19, 0.14),     //  9 Holz, verwittert
    vec3(0.19, 0.17, 0.155),    // 10 Granit
    vec3(0.51, 0.30, 0.13),     // 11 Klinker
    vec3(0.43, 0.35, 0.24),     // 12 Kies
    vec3(0.75, 0.74, 0.70),     // 13 Segeltuch
    vec3(0.58, 0.46, 0.26),     // 14 Korb
    vec3(0.020, 0.018, 0.016),  // 15 Fell
    vec3(0.60, 0.57, 0.50),     // 16 Fell weiß
    vec3(0.80, 0.80, 0.78),     // 17 Lack weiß
    vec3(0.015),                // 18 Lack schwarz
    vec3(0.02, 0.025, 0.03),    // 19 Fenster
    vec3(0.02, 0.08, 0.05),     // 20 Tür
    vec3(0.70, 0.70, 0.68),     // 21 Holz weiß
    vec3(0.60, 0.02, 0.02),     // 22 Rot
    vec3(0.80, 0.60, 0.02),     // 23 Gelb
    vec3(0.42, 0.16, 0.05),     // 24 Geschiebelehm
    vec3(0.62, 0.56, 0.45),     // 25 Kaolinsand
    vec3(0.52, 0.43, 0.28),     // 26 Flugsand
    vec3(0.75, 0.72, 0.62));    // 27 Leuchte (seit Phase 9)

float hash1(float n) { return fract(sin(n) * 43758.5453); }
float hash2(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float vnoise(vec2 x) {
    vec2 i = floor(x), f = x - i;
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash2(i), hash2(i + vec2(1, 0)), f.x), mix(hash2(i + vec2(0, 1)), hash2(i + vec2(1, 1)), f.x), f.y);
}
// Muster der Größe s sichtbar? (1 nah, 0 wenn ein Bildpunkt größer ist als ein Viertel davon)
float sicht(float s, float pxm) { return 1.0 - smoothstep(0.12 * s, 0.4 * s, pxm); }

// Mauerwerk im Läuferverband: Stein 0,25 m × Schicht h, Fuge 1 cm; Steine streuen in der Farbe
vec3 mauer(vec3 stein, vec3 fuge, float u, float hy, float pxm, float seed) {
    float sch = 0.0833, row = floor(hy / sch);
    float uu = u / 0.25 + 0.5 * mod(row, 2.0);
    vec2 f = vec2(fract(uu), fract(hy / sch));
    float fx = min(f.x, 1.0 - f.x) * 0.25, fy = min(f.y, 1.0 - f.y) * sch;
    float m = 1.0 - smoothstep(0.004, 0.008, min(fx, fy));
    float var = 0.78 + 0.44 * hash2(vec2(floor(uu), row) + seed);
    vec3 nah = mix(stein * var, fuge, m);
    vec3 mittel = mix(stein, fuge, 0.16);
    return mix(mittel, nah, sicht(0.08, pxm));
}

void main() {
    uint mat = vAttr & 63u;
    bool win = (vAttr & 64u) != 0u;
    float seed = float((vAttr >> 7) & 511u);
    float ground = float(vAttr >> 16) / 20.0 - 100.0;
    vec3 toEye = uCam - vWorld;
    float dist = length(toEye);
    vec3 V = toEye / max(dist, 1e-4);
    vec3 n = normalize(vNormal);
    if (dot(n, V) < 0.0) n = -n;
    vec2 t2 = length(n.xz) > 0.15 ? normalize(vec2(-n.z, n.x)) : vec2(1.0, 0.0);
    float u = dot(vWorld.xz, t2);
    float hy = vWorld.y - ground;
    float pxm = max(length(fwidth(vWorld)), 1e-4);
    vec3 alb = ALB[min(mat, 27u)];
    float spec = 0.0, shin = 40.0, glass = 0.0;
    vec3 emit = vec3(0.0);
    bool wand = abs(n.y) < 0.35;

    if (mat == 0u || mat == 11u) {
        vec3 fuge = mat == 0u ? vec3(0.36, 0.34, 0.31) : vec3(0.45, 0.42, 0.38);
        vec3 st = alb * (0.85 + 0.3 * hash1(seed));
        alb = wand ? mauer(st, fuge, u, hy, pxm, seed) : st;
    } else if (mat == 1u) {
        // Putz: weiß, manche Häuser cremefarben oder hellgrau
        float s = hash1(seed * 1.7);
        alb *= s < 0.6 ? vec3(1.0) : s < 0.8 ? vec3(1.0, 0.96, 0.86) : vec3(0.86, 0.87, 0.88);
        alb *= 0.93 + 0.1 * mix(vnoise(vWorld.xz * 1.3 + vWorld.y), 0.5, 1.0 - sicht(1.0, pxm));
        alb *= 0.9 + 0.1 * smoothstep(0.0, 0.5, hy);      // Spritzwasser am Sockel
    } else if (mat == 2u) {
        // Reet: Halme in Fallrichtung des Dachs, älteres Reet grauer, an der Traufe Moos [G]
        vec2 fall = length(n.xz) > 0.05 ? normalize(n.xz) : vec2(1.0, 0.0);
        float quer = dot(vWorld.xz, vec2(-fall.y, fall.x));
        float halm = vnoise(vec2(quer * 30.0, vWorld.y * 2.5)) * 0.6 + vnoise(vec2(quer * 6.0, vWorld.y * 0.8)) * 0.4;
        float alt = hash1(seed * 2.3);
        alb = mix(alb, vec3(0.14, 0.13, 0.11), alt * 0.6);
        alb *= mix(1.0, 0.7 + 0.6 * halm, sicht(0.15, pxm));
        alb = mix(alb, vec3(0.06, 0.09, 0.04), 0.35 * (1.0 - smoothstep(0.0, 1.2, hy - 2.2)) * alt);
    } else if (mat == 3u || mat == 4u) {
        // Ziegel und Pfannen: Reihen nach der Höhe, Steine streuen
        float row = floor(vWorld.y / 0.12);
        float s = hash1(seed * 3.1);
        if (mat == 3u) alb *= 0.7 + 0.7 * s; else alb *= mix(vec3(1.0), vec3(0.75, 0.9, 1.0), s * 0.6);
        float linie = smoothstep(0.82, 0.95, fract(vWorld.y / 0.12));
        float var = 0.88 + 0.24 * hash2(vec2(floor(dot(vWorld.xz, vec2(-n.z, n.x)) / 0.3), row));
        alb *= mix(1.0, var * (1.0 - 0.35 * linie), sicht(0.12, pxm));
        spec = 0.04;
    } else if (mat == 5u) {
        // Kupfer mit Patina, Schlieren nach unten
        alb *= 0.85 + 0.3 * vnoise(vec2(u * 4.0, vWorld.y * 0.7));
        spec = 0.08; shin = 25.0;
    } else if (mat == 6u || mat == 7u) {
        // Laterne: Sprossen (Quermarke senkrecht und schräg, weiß; Kampen Rauten, schwarz), dahinter die Linse
        vec4 T = mat == 6u ? uTurmQ : uTurmK;
        vec2 d = vWorld.xz - T.xy;
        float ang = atan(d.x, -d.y);
        float arc = ang * length(d);
        float steg;
        if (mat == 6u) {
            float f = fract(degrees(ang) / 45.0 - 0.5 + 8.0);
            float v = clamp((vWorld.y - (T.z - 0.5)) / 1.0, 0.0, 1.0);
            steg = max(1.0 - smoothstep(0.02, 0.05, min(f, 1.0 - f)),
                       max(1.0 - smoothstep(0.01, 0.025, abs(f - v)), 1.0 - smoothstep(0.01, 0.025, abs(f - (1.0 - v)))));
        } else {
            float a1 = fract((arc + vWorld.y) / 0.48), a2 = fract((arc - vWorld.y) / 0.48);
            steg = max(1.0 - smoothstep(0.03, 0.07, min(a1, 1.0 - a1)), 1.0 - smoothstep(0.03, 0.07, min(a2, 1.0 - a2)));
        }
        steg *= sicht(0.3, pxm);
        vec3 lens = mat == 6u ? uLensQ : uLensK, glow = mat == 6u ? uGlowQ : uGlowK;
        float inLens = 1.0 - smoothstep(T.w * 0.8, T.w * 1.2, abs(vWorld.y - T.z));
        emit = (glow + lens * inLens) * (1.0 - steg);
        alb = mix(vec3(0.03), mat == 6u ? ALB[17] : ALB[18], steg);
        glass = 1.0 - steg;
        spec = 0.3 * (1.0 - steg); shin = 300.0;
    } else if (mat == 8u || mat == 18u) {
        spec = 0.12; shin = 60.0;
    } else if (mat == 9u || mat == 21u) {
        // Holz: Maserung längs (u), Bretter
        float g = vnoise(vec2(u * 3.0, vWorld.y * 40.0 + seed));
        alb *= mix(1.0, 0.8 + 0.4 * g, sicht(0.05, pxm));
    } else if (mat == 10u) {
        alb *= mix(1.0, 0.75 + 0.5 * hash2(floor(vWorld.xz * 40.0) + floor(vWorld.y * 40.0)), sicht(0.03, pxm));
    } else if (mat == 12u) {
        alb *= mix(1.0, 0.7 + 0.6 * hash2(floor(vWorld.xz * 30.0)), sicht(0.04, pxm)) * (0.9 + 0.2 * vnoise(vWorld.xz * 0.5));
    } else if (mat == 13u) {
        // Segeltuch: Streifen 8 cm, Farbe je Korb; in Timmendorf meist blau-weiß (Strandkorb1–2), dazu dunkelblau, grün,
        // rot, gelb [G]
        float s = hash1(seed * 5.3);
        vec3 farbe = s < 0.62 ? vec3(0.03, 0.12, 0.45) : s < 0.74 ? vec3(0.02, 0.04, 0.15) : s < 0.84 ? vec3(0.04, 0.30, 0.10)
                   : s < 0.93 ? vec3(0.55, 0.04, 0.04) : vec3(0.75, 0.55, 0.03);
        float st = step(0.5, fract(u / 0.16));
        alb = mix(mix(farbe, ALB[13], 0.45), mix(farbe, ALB[13], st), sicht(0.16, pxm));
    } else if (mat == 14u) {
        float w = step(0.5, fract(u / 0.05)) == step(0.5, fract(vWorld.y / 0.05)) ? 1.0 : 0.82;
        alb *= mix(0.91, w, sicht(0.05, pxm)) * (0.9 + 0.2 * hash1(seed));
    } else if (mat == 15u || mat == 16u) {
        alb *= 0.8 + 0.4 * vnoise(vec2(u * 25.0, vWorld.y * 25.0));
    } else if (mat == 17u) {
        alb *= 0.94 + 0.06 * vnoise(vec2(u * 2.0, vWorld.y * 0.3));   // Laufspuren am Rundturm
        spec = 0.05; shin = 30.0;
    } else if (mat == 19u) {
        glass = 1.0; spec = 0.3; shin = 200.0;
    } else if (mat == 27u) {
        emit = uLampe;
    } else if (mat == 20u) {
        alb *= mix(1.0, 0.8 + 0.4 * step(0.1, fract(u / 0.12)), sicht(0.12, pxm));
    }

    // Fenster der Häuser: Geschosse je 2,9 m, Fenster 1,2 × 1,2 m alle 2,7 m, weißer Rahmen; nachts zum Teil erleuchtet
    if (win && wand && hy > 0.6) {
        float st = floor((hy - 0.3) / 2.9), fy = (hy - 0.3) - st * 2.9;
        float fu = fract(u / 2.7 + hash1(seed) * 0.7);
        float inY = step(0.95, fy) * step(fy, 2.15), inU = step(0.28, fu) * step(fu, 0.72);
        float rahmen = inY * inU * (1.0 - step(1.02, fy) * step(fy, 2.08) * step(0.30, fu) * step(fu, 0.70));
        float scheibe = inY * inU - rahmen;
        float s = sicht(1.2, pxm);
        if (s > 0.0) {
            vec3 wa = mix(alb, vec3(0.75), rahmen);
            wa = mix(wa, vec3(0.02, 0.025, 0.03), scheibe);
            alb = mix(alb, wa, s);
            glass = max(glass, scheibe * s);
            float an = step(hash2(vec2(floor(u / 2.7 + hash1(seed) * 0.7), st) + seed * 0.37), uFenster);
            emit += uWarm * uNight * an * scheibe * s * (0.6 + 0.8 * hash1(seed + st));
        }
        alb = mix(alb * 0.86, alb, s);   // in der Ferne dunkeln die Fenster die Wand im Mittel
    }

    // seit Phase 13: Schnee auf Dächern und Decks (Flächen nach oben), Reif
    if (uSchnee.z > 0.002 || uSchnee.x > 0.002) {
        float oben = smoothstep(0.35, 0.7, n.y) * (1.0 - glass);
        float s = max(smoothstep(0.003, 0.04, uSchnee.z) * step(2.0, hy), smoothstep(0.003, 0.05, uSchnee.x) * step(hy, 2.0));
        alb = mix(alb, vec3(0.86, 0.88, 0.92), s * oben);
    }
    alb = mix(alb, vec3(0.72, 0.76, 0.80), 0.25 * uSchnee.y * (1.0 - glass));
    float cloud = uCloudRect.w > 0.0 ? textureLod(uCloudShadow, (vWorld.xz - uCloudRect.xy) / uCloudRect.z, 0.0).r : 1.0;
    float ao = 0.70 + 0.30 * smoothstep(0.0, 1.8, hy);
    vec3 col;
    float ndl = max(dot(n, uSun), 0.0);
    vec3 H = normalize(uSun + V);
    float sp = spec * pow(max(dot(n, H), 0.0), shin) * step(0.0, dot(n, uSun)) * (shin + 8.0) / 8.0;
    if (uSkyMode == 1) {
        vec3 direct = (sunLight() * (ndl * (1.0 - glass * 0.9) + sp) + moonLight() * max(dot(n, uMoonDir), 0.0)) * cloud;
        vec3 amb = uSkyAmb * (0.55 + 0.45 * n.y) * ao;
        col = alb * (direct / PI + amb) + sunLight() * cloud * sp * (1.0 - length(alb) * 0.5) / PI;
    } else {
        float B = skyBrightness(sunElevDeg());
        vec3 amb = vec3(0.30, 0.38, 0.52) * B / 0.32 * (0.55 + 0.45 * n.y) * ao;
        col = alb * (sunLight() * ndl * cloud + amb) + sunLight() * cloud * sp;
    }
    if (glass > 0.0) {
        vec3 r = reflect(-V, n);
        float F = 0.04 + 0.96 * pow(1.0 - max(dot(n, V), 0.0), 5.0);
        vec3 refl = skyColor(normalize(vec3(r.x, max(r.y, 0.02), r.z))) * 0.9;
        col = mix(col, refl, F * glass);
    }
    // seit Phase 12: Licht der Leuchten und der Bomben auf Wände und Dächer
    if (uLampK > 0.0 && uLampCount > 0 && dist < uLampRange) col += alb * lampIrradiance(vWorld, n) / PI * uLampK * (1.0 - glass * 0.9);
    col += emit;
    col = aerial(col, -V, dist);
    frag = vec4(col, 1.0);
}
