// sky.frag — Himmel als Verlauf (Phase 1, Platzhalter bis com.dan.sky in Phase 3; aus Geiranger).
// Eingaben: common.glsl. Raum: Sichtstrahl je Bildpunkt. Ausgabe: HDR-Farbe linear. Tiefe: keine (zuerst gezeichnet).
#include "common.glsl"
in vec2 vUV;
out vec4 frag;
void main() {
    vec3 d = viewRay(vUV);
    frag = vec4(skyColor(vec3(d.x, max(d.y, 0.0), d.z)), 1.0);
}
