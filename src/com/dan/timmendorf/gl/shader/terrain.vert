// terrain.vert — Gelände aus dem Viererbaum (CDLOD, aus Geiranger). Ein Gitter je Knoten, Lage und Größe je Instanz.
// Eingaben: aGrid (0..1 im Knoten), aNode (x0, z0, Kante in m, Stufe). Höhe aus terrain_height.glsl.
// Geomorphing: im äußeren Teil ihres Bereichs gleiten die ungeraden Punkte auf die Lage der gröberen Stufe; Höhe und
// Mip-Stufe hängen nur von der Endlage ab, darum passen Nachbarknoten. Ausgaben: vWorld, vNormal (grob), vDist.
// Die Feinform (Granit aus cliff, Moor aus ground) kommt in Phase 8 hier dazu.
#include "common.glsl"
#include "terrain_height.glsl"
layout(location = 0) in vec2 aGrid;
layout(location = 1) in vec4 aNode;
uniform float uGridDim;
out vec3 vWorld;
out vec3 vNormal;
out float vDist;

void main() {
    vec2 w = aNode.xy + aGrid * aNode.z;
    float hc = heightAt(w, 3.0);
    float d0 = distance(vec3(w.x, hc, w.y), uCam);
    float end = uLodRange.x * exp2(aNode.w), start = end * uLodRange.y;
    float morph = clamp((d0 - start) / (end * 0.97 - start), 0.0, 1.0);
    vec2 frac = fract(aGrid * uGridDim * 0.5) * 2.0 / uGridDim;
    w -= frac * aNode.z * morph;
    hc = heightAt(w, 3.0);
    float d = distance(vec3(w.x, hc, w.y), uCam);
    float h = terrainHeight(w, d);
    vec3 world = vec3(w.x, h, w.y);
    float e = clamp(d / (64.0 * uLodRange.z), 1.0, 400.0);
    float hx0 = terrainHeight(w - vec2(e, 0.0), d), hx1 = terrainHeight(w + vec2(e, 0.0), d);
    float hz0 = terrainHeight(w - vec2(0.0, e), d), hz1 = terrainHeight(w + vec2(0.0, e), d);
    vNormal = normalize(vec3(hx0 - hx1, 2.0 * e, hz0 - hz1));
    vWorld = world;
    vDist = d;
    // Erdkrümmung mit normaler Lichtbrechung: in 10 km liegt der Boden 6,8 m tiefer als seine Höhe sagt, in 100 km 680 m
    vec2 dh = world.xz - uCam.xz;
    gl_Position = uVP * vec4(world.x, world.y - dot(dh, dh) / (2.0 * EARTH_R / 0.87), world.z, 1.0);
}
