// common.glsl — gemeinsame Werte und der Platzhalter-Himmel (Phase 1, aus Geiranger über Brocken und Sylt).
// Raum: Szene in m, x Ost, y oben (y = 0 ist NHN), z Süd, Ursprung am Landende der Maritim Seebrücke (UTM 32 N, F2 A).
// Nordhalbkugel, 54,0° N: die Sonne läuft über Süden (+z), am 21. Juni steht sie mittags knapp 60° hoch, am 21. Dezember
// 13°; der Strand schaut nach Nordosten, im Sommer geht die Sonne über der Bucht auf und hinter dem Ort unter.
// Der Himmel hier ist ein Verlauf nach der Sonnenhöhe: Tag, Dämmerung mit Abendrot zur Sonne hin, Nacht. Er ist
// mit Absicht einfach; Phase 4 ersetzt skyColor() durch die Streuung aus com.dan.sky. Helligkeiten sind linear,
// Tag = 1; die Belichtung (uExposure) kommt vom Prozessor.
uniform mat4 uVP;
uniform mat4 uInvVP;
uniform vec3 uCam;
uniform vec3 uSun;          // Richtung zur Sonne (Einheitsvektor)
uniform float uTime;        // Laufzeit in s
uniform float uLevel;       // Wasserstand der See in m über NHN (Phase 1 fest, ab Phase 7a aus tide)
uniform int uReversed;      // 1 = umgekehrte Tiefe (0..1), sonst klassisch (-1..1)
// Ab Phase 3: Himmel aus com.dan.sky. uSkyMode = 1: Himmelsfarbe aus dem Rundumbild uPano, Sonnen- und Mondlicht aus
// uSunColor/uMoonColor, Dunst aus uFogDist; alles schon mit der Vorbelichtung uPre multipliziert. uSkyMode = 0: der
// Platzhalter-Himmel von Phase 1, Belichtung erst in der Nachbearbeitung. Phase 1 setzt immer 0.
uniform int uSkyMode;
uniform sampler2D uPano;
uniform vec3 uSunColor, uMoonColor, uMoonDir;
uniform float uFogDist, uPre;
uniform vec3 uSkyAmb;     // ab Phase 3: Himmelslicht als Bestrahlung/π (vorbelichtet), für das Umgebungslicht

// Tiefenwert für einen Punkt der Szene, passend zur Projektion.
float depthOf(vec3 p) {
    vec4 clip = uVP * vec4(p, 1.0);
    float z = clip.z / clip.w;
    return clamp(uReversed == 1 ? z : z * 0.5 + 0.5, 0.0, 1.0);
}
const float EARTH_R = 6371000.0;

// Sichtstrahl für einen Bildpunkt (vUV 0..1). Umgekehrte Tiefe: z = 1 ist die Nahebene, klassisch z = -1.
vec3 viewRay(vec2 uv) {
    vec2 ndc = uv * 2.0 - 1.0;
    float zn = uReversed == 1 ? 1.0 : -1.0, zm = uReversed == 1 ? 0.5 : 0.0;
    vec4 a = uInvVP * vec4(ndc, zn, 1.0);
    vec4 b = uInvVP * vec4(ndc, zm, 1.0);
    return normalize(b.xyz / b.w - a.xyz / a.w);
}

float sunVisible() { return smoothstep(-0.015, 0.01, uSun.y); }

vec3 panoSample(vec3 d) {
    float az = atan(d.x, -d.z) / 6.28318530718 + 0.5;
    float el = asin(clamp(d.y, -1.0, 1.0)) / 3.14159265359 + 0.5;
    return textureLod(uPano, vec2(az, el), 0.0).rgb;
}

// Farbe des Sonnenlichts: weiß am Tag, golden und schwächer tief am Himmel, aus unter der Kimm.
vec3 sunLight() {
    if (uSkyMode == 1) return uSunColor;
    float e = uSun.y;
    vec3 low = vec3(1.0, 0.55, 0.28), high = vec3(1.0, 0.95, 0.88);
    return mix(low, high, smoothstep(0.02, 0.35, e)) * 2.6 * sunVisible() * smoothstep(-0.02, 0.12, e);
}

// Helligkeit des Himmels nach der Sonnenhöhe in Grad (log10, lineare Stützstellen): Tag 1, Sonnenuntergang 0,1,
// bürgerliche Dämmerung 0,005, nautische 0,0003, Nacht ohne Mond 0,00005. Gleiche Tabelle wie Sun.skyBrightness().
float skyBrightness(float elevDeg) {
    const float E[6] = float[6](-18.0, -12.0, -6.0, 0.0, 10.0, 30.0);
    const float V[6] = float[6](-4.3, -3.5, -2.3, -1.0, -0.3, 0.0);
    float v = elevDeg <= E[0] ? V[0] : V[5];
    for (int i = 0; i < 5; i++)
        if (elevDeg > E[i] && elevDeg <= E[i + 1]) v = mix(V[i], V[i + 1], (elevDeg - E[i]) / (E[i + 1] - E[i]));
    return pow(10.0, v);
}

float sunElevDeg() { return degrees(asin(clamp(uSun.y, -1.0, 1.0))); }

// Farbe des Himmels in Richtung d, linear, absolut (Tag ≈ 1). Form und Helligkeit getrennt: die Form mischt Tag,
// Dämmerung und Nacht, die Helligkeit kommt aus skyBrightness(). Zur Sonne hin glüht der Horizont in der Dämmerung.
vec3 skyColorPlaceholder(vec3 d);
vec3 skyColor(vec3 d) { return uSkyMode == 1 ? panoSample(d) : skyColorPlaceholder(d); }

// Mondlicht (nur mit com.dan.sky).
vec3 moonLight() { return uSkyMode == 1 ? uMoonColor : vec3(0.0); }

vec3 skyColorPlaceholder(vec3 d) {
    float e = sunElevDeg();
    float B = skyBrightness(e);
    float day = smoothstep(-2.0, 12.0, e);
    float night = 1.0 - smoothstep(-16.0, -8.0, e);
    float tw = clamp(1.0 - day - night, 0.0, 1.0);
    float up = clamp(d.y, 0.0, 1.0);
    float hw = pow(1.0 - up, 4.0);
    vec2 sh = normalize(uSun.xz + vec2(1e-5));
    vec2 dh = normalize(d.xz + vec2(1e-5));
    // geklemmt: genau gegenüber der Sonne wird dot() minimal kleiner als −1, und pow(negativ, …) ergibt NaN
    // (schwarze Punkte auf einer Linie; Befund aus Haithabu, Phase 1)
    float toward = clamp(dot(sh, dh) * 0.5 + 0.5, 0.0, 1.0);
    vec3 zen = vec3(0.16, 0.34, 0.72) * day + vec3(0.10, 0.17, 0.42) * tw + vec3(0.05, 0.08, 0.20) * night;
    vec3 hor = vec3(0.62, 0.74, 0.88) * day + mix(vec3(0.28, 0.30, 0.46), vec3(0.95, 0.52, 0.26), pow(toward, 3.0)) * tw
             + vec3(0.07, 0.09, 0.18) * night;
    vec3 c = mix(zen, hor, hw);
    // tief stehende Sonne am Tag: warmer Saum zur Sonne
    c += vec3(0.9, 0.55, 0.30) * pow(toward, 6.0) * hw * day * (1.0 - smoothstep(4.0, 20.0, e)) * 0.6;
    // Dämmerungsglühen dicht über der Kimm
    c += vec3(1.0, 0.42, 0.14) * tw * pow(toward, 4.0) * pow(1.0 - up, 14.0) * 1.2;
    c *= B / 0.32;
    float cs = dot(d, uSun);
    float vis = sunVisible();
    c += vec3(1.0, 0.8, 0.55) * pow(max(cs, 0.0), 180.0) * 0.5 * vis * B / 0.32;
    c += vec3(30.0, 26.0, 20.0) * mix(vec3(1.0, 0.45, 0.2), vec3(1.0), smoothstep(0.0, 0.2, uSun.y)) * smoothstep(0.99996, 0.999989, cs) * vis;
    return c;
}

// Dunst zur Ferne hin: Phase 1 fest (60 km, ein klarer Sommertag an der Ostsee [G]), mit com.dan.sky aus der Sichtweite
// (Koschmieder). Der Dunst nimmt mit der Höhe ab (Skalenhöhe 8 km [G]): gezählt wird die Strecke in der mittleren Höhe
// des Wegs, damit der Blick vom Brodtener Ufer und aus der Vogelschau nicht im Dunst verschwindet.
// Seit Phase 3 (com.dan.sky): die Farbe des Dunsts aus dem Rundumbild knapp über der Kimm, in der Helligkeit begrenzt,
// damit die Sonnenscheibe und ihr Glanz nicht als heller Fleck aufs Gelände fallen. Seit Phase 4 aus der Mip-Stufe 4
// (32 × 16, rund 11° je Texel): mit der feinen Stufe zeichneten die Wolken an der Kimm senkrechte Streifen ins Gelände.
vec3 hazeColor(vec3 dir) {
    if (uSkyMode != 1) return skyColor(normalize(vec3(dir.x, 0.0, dir.z) + vec3(0.0, 1e-4, 0.0)));
    vec2 h = normalize(dir.xz + vec2(1e-5));
    float az = atan(h.x, -h.y) / 6.28318530718 + 0.5;
    float el = asin(0.03) / 3.14159265359 + 0.5;
    vec3 c = textureLod(uPano, vec2(az, el), 4.0).rgb;
    float cap = 3.0 * dot(uSkyAmb, vec3(0.2126, 0.7152, 0.0722)) + 1e-6, l = dot(c, vec3(0.2126, 0.7152, 0.0722));
    return l > cap ? c * (cap / l) : c;
}

vec3 aerial(vec3 col, vec3 dir, float dist) {
    float hm = max(0.0, uCam.y + dir.y * dist * 0.5);
    float f = 1.0 - exp(-dist * exp(-hm / 8000.0) / (uSkyMode == 1 ? uFogDist : 60000.0));
    return mix(col, hazeColor(dir), f);
}
