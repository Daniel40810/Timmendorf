// bauten.vert — Türme und Bauten (seit Phase 10): Häuser aus LOD2, Quermarkenfeuer, Kampener Leuchtturm, Treppe,
// Wege, Strandkörbe, Galloways. Je Ecke Ort (float), Normale (Bytes) und ein Merkmal (Netz.java: Baustoff 6 Bit,
// Fenster 1 Bit, Kennzahl 9 Bit, Grund 16 Bit in 1/20 m über −100 m). Erdkrümmung wie das Gelände.
#include "common.glsl"
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec4 aNrm;
layout(location = 2) in uint aAttr;
out vec3 vWorld;
out vec3 vNormal;
flat out uint vAttr;

void main() {
    vWorld = aPos;
    vNormal = aNrm.xyz;
    vAttr = aAttr;
    vec2 dh = aPos.xz - uCam.xz;
    gl_Position = uVP * vec4(aPos.x, aPos.y - dot(dh, dh) / (2.0 * EARTH_R), aPos.z, 1.0);
}
