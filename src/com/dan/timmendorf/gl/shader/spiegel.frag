// spiegel.frag — seit Phase 12: die Lichter der Stadt und die Bomben des Feuerwerks im Wasser. Wo die Szene das Wasser
// zeigt (Tiefe → Ort, Höhe am Pegel), die Spiegelung jeder Leuchte nach GGX auf einer glatten Fläche mit der Rauigkeit
// der See aus dem Wind (Cox und Munk, com.dan.lamp.Wet): so entstehen die langen Lichtstraßen über der Bucht. Additiv in
// das Farbziel (vorbelichtet).
#include "common.glsl"
#include "terrain_height.glsl"
#include "/com/dan/lamp/gl/lamp.glsl"
in vec2 vUV;
out vec4 frag;
uniform sampler2D uDepth;
uniform float uLampK;
uniform float uAlpha;        // GGX-α der See
uniform float uWaterTol;     // Abweichung der Wellen vom Pegel (m)
uniform float uLampRange;
void main() {
    float dz = textureLod(uDepth, vUV, 0.0).r;
    if (uReversed == 1 ? dz <= 0.0 : dz >= 1.0) discard;
    vec4 q = uInvVP * vec4(vUV * 2.0 - 1.0, uReversed == 1 ? dz : dz * 2.0 - 1.0, 1.0);
    vec3 p = q.xyz / q.w;
    if (abs(p.y - uLevel) > uWaterTol) discard;
    vec3 toEye = uCam - p;
    float dist = length(toEye);
    if (dist > uLampRange) discard;
    // seit Phase 13: nur, wo Wasser über dem Grund steht (Befund Phase 13: der nasse Sand am Spülsaum glänzte wie ein Spiegel)
    if (terrainHeight(p.xz, dist) > uLevel - 0.15) discard;
    p.y = uLevel;
    vec3 c = lampSpecular(p, vec3(0.0, 1.0, 0.0), toEye / dist, uAlpha, 0.02) * uLampK;
    c = aerial(c, -toEye / dist, dist) - aerial(vec3(0.0), -toEye / dist, dist);   // nur die Dämpfung, kein Dunst dazu
    frag = vec4(max(c, vec3(0.0)), 0.0);
}
