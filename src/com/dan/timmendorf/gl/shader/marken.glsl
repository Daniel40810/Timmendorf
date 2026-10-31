// marken.glsl — die Marken zur Ausrichtung (seit Phase 1, aus Brocken): das 1-km-Raster in UTM (alle 5 km kräftiger),
// Höhenlinien alle 5 m (alle 10 m kräftiger), die Wasserlinie NHN 0 (blau), der Rand des Fernfelds (weiß), der Kern
// (gold), der Ursprung am Landende der Seebrücke mit Nordpfeil, die Orte als Ringe (weiß Seezeichen, holzbraun Brücken,
// hell Orte, blau Strand, rot Steilufer).
// Linien stehen in Bildpunkten fest und blenden aus, wo sie im Bild enger als rund 12 Bildpunkte liegen; steht das Auge
// an einem Ort oder am Ursprung, blendet dessen Marke aus (Befunde Phase 1).
uniform int uMarks;
uniform vec4 uMarkHaupt, uMarkKern;
uniform vec2 uGridOff;     // Ostwert E0 und Nordwert −N0 des Ursprungs, je modulo 5 km: das Raster liegt auf ganzen UTM-Kilometern
uniform vec4 uPoints[16];
uniform int uPointCount;

float gridLine(float v, float step, float w) {
    float f = abs(fract(v / step + 0.5) - 0.5) * step;
    float px = fwidth(v);
    float fade = 1.0 - smoothstep(step / 24.0, step / 8.0, px);
    return (1.0 - smoothstep(w * 0.5 * px, (w * 0.5 + 1.0) * px, f)) * fade;
}

float rectLine(vec2 p, vec4 r, float w) {
    vec2 px = fwidth(p);
    float dx = min(abs(p.x - r.x), abs(p.x - r.z)) / max(px.x, 1e-4);
    float dz = min(abs(p.y - r.y), abs(p.y - r.w)) / max(px.y, 1e-4);
    bool inX = p.x >= r.x - px.x && p.x <= r.z + px.x, inZ = p.y >= r.y - px.y && p.y <= r.w + px.y;
    float a = inZ ? 1.0 - smoothstep(w * 0.5, w * 0.5 + 1.0, dx) : 0.0;
    float b = inX ? 1.0 - smoothstep(w * 0.5, w * 0.5 + 1.0, dz) : 0.0;
    return max(a, b);
}

vec3 markColor(float art) {
    if (art < 0.5) return vec3(1.0, 1.0, 1.0);        // Seezeichen
    if (art < 1.5) return vec3(0.85, 0.66, 0.46);     // Brücke
    if (art < 2.5) return vec3(0.95, 0.93, 0.89);     // Ort
    if (art < 3.5) return vec3(0.45, 0.70, 0.95);     // Strand
    return vec3(0.88, 0.45, 0.25);                    // Steilufer
}

// Marken über die Farbe col am Ort p (x, z) mit Höhe h; B = Helligkeit des Himmels (skyBrightness), vDist = Abstand.
vec3 applyMarks(vec3 col, vec2 p, float h, float dist, float B) {
    if (uMarks != 1) return col;
    vec2 u = p + uGridOff;
    float g1 = gridLine(u.x, 1000.0, 1.0) + gridLine(u.y, 1000.0, 1.0);
    float g5 = gridLine(u.x, 5000.0, 2.0) + gridLine(u.y, 5000.0, 2.0);
    float h50 = gridLine(h, 5.0, 1.0), h100 = gridLine(h, 10.0, 1.6);
    float nhn = 1.0 - smoothstep(1.0, 2.5, abs(h) / max(fwidth(h), 1e-4));
    float near = 1.0 - smoothstep(20000.0, 40000.0, dist);
    vec3 ink = vec3(0.62, 0.75, 0.81) * B / 0.32;
    col = mix(col, ink * 0.55, clamp(g1, 0.0, 1.0) * 0.35 * near);
    col = mix(col, ink, clamp(g5, 0.0, 1.0) * 0.45 * (1.0 - smoothstep(30000.0, 60000.0, dist)));
    col = mix(col, ink * 0.8, max(h50 * 0.25, h100 * 0.5) * (1.0 - smoothstep(4000.0, 8000.0, dist)) * step(0.3, h));
    col = mix(col, vec3(0.45, 0.75, 1.3) * B / 0.32, nhn * (1.0 - smoothstep(8000.0, 16000.0, dist)));
    col = mix(col, vec3(1.6) * B / 0.32, rectLine(p, uMarkHaupt, 2.5));
    col = mix(col, vec3(2.0, 1.45, 0.62) * B / 0.32, rectLine(p, uMarkKern, 2.5));
    float cross = max(1.0 - smoothstep(1.5, 4.5, abs(p.x)), 1.0 - smoothstep(1.5, 4.5, abs(p.y))) * (1.0 - step(60.0, length(p)))
                * (1.0 - smoothstep(4.0, 12.0, length(fwidth(p))));
    vec2 a = p - vec2(0.0, -150.0);
    float arrow = step(0.0, a.y + 90.0) * step(a.y, 0.0) * step(abs(a.x), (a.y + 90.0) * 0.35);
    col = mix(col, vec3(2.0, 1.45, 0.62) * B / 0.32, max(cross, arrow) * smoothstep(80.0, 250.0, length(uCam.xz)));
    for (int i = 0; i < uPointCount; i++) {
        vec4 q = uPoints[i];
        float d = length(p - q.xy), w = max(q.z, fwidth(d) * 3.0);
        float ring = 1.0 - smoothstep(w, w + fwidth(d) * 1.5, d);
        float hole = 1.0 - smoothstep(w * 0.7, w * 0.7 + fwidth(d) * 1.5, d);
        float hier = smoothstep(q.z * 1.5, q.z * 4.0, length(uCam.xz - q.xy));
        col = mix(col, markColor(q.w) * 1.4 * B / 0.32, ring * (1.0 - hole * 0.75) * hier);
    }
    return col;
}
