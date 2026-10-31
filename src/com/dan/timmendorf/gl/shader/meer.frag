// meer.frag — die Ostsee als Platzhalter (aus Sylt Phase 1, bis ocean in Phase 4): eine Wasserfläche im Pegel uLevel mit
// Erdkrümmung (der Strahl trifft die gekrümmte Fläche, daher liegt die Kimm richtig unter dem Horizont des Auges),
// Farbe nach der Wassertiefe über dem Grund (terrain_height.glsl), Spiegelung des Himmels nach Fresnel (Schlick),
// Glitzer der Sonne aus einem Rauschen der Normale [G] und flacher Schaum an der Wasserlinie. Zeichnet ins Farbziel ohne
// Tiefe und liest die Tiefe des Geländes (uDepth): liegt das Gelände mehr als einen kleinen Spielraum vor der Wasserfläche,
// bleibt es stehen; Land erkennt die feinste Stufe der Höhe. Befund Phase 1: mit dem Tiefentest allein flimmerten am
// flachen Strand Streifen, weil Wasser und Sand dort auf Zentimeter gleich hoch liegen und die Detailstufen das Netz um
// Zentimeter heben und senken.
#include "common.glsl"
#include "terrain_height.glsl"
in vec2 vUV;
out vec4 frag;
uniform sampler2D uDepth;     // Tiefe des Geländes (32F, umgekehrt oder klassisch nach uReversed)

float h2(vec2 i) { return fract(sin(dot(i, vec2(127.1, 311.7))) * 43758.5453); }
float vn(vec2 x) {
    vec2 i = floor(x), f = x - i;
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(h2(i), h2(i + vec2(1, 0)), f.x), mix(h2(i + vec2(0, 1)), h2(i + vec2(1, 1)), f.x), f.y);
}

void main() {
    vec3 d = viewRay(vUV);
    // Strahl gegen die gekrümmte Wasserfläche y = uLevel − r²/2R' (r waagerecht vom Auge): a t² + b t + c = 0
    float R = EARTH_R / 0.87;
    float hh = dot(d.xz, d.xz);
    float a = hh / (2.0 * R), b = d.y, c = uCam.y - uLevel;
    float t;
    if (a < 1e-12) t = b < 0.0 ? -c / b : -1.0;
    else {
        float disc = b * b - 4.0 * a * c;
        if (disc < 0.0) discard;
        float s = sqrt(disc);
        t = (-b - s) / (2.0 * a);
        if (t <= 0.0) t = (-b + s) / (2.0 * a);
    }
    if (!(t > 0.0) || c < 0.0) discard;
    vec3 w = uCam + d * t;
    float dist = t;
    // Land: die feinste Stufe der Höhe liegt über dem Wasser
    float bedFein = heightAt(w.xz, -8.0);
    if (bedFein > uLevel) discard;
    // Gelände davor (mit Spielraum, der mit der Entfernung wächst)?
    float z = texelFetch(uDepth, ivec2(gl_FragCoord.xy), 0).r;
    bool leer = uReversed == 1 ? z <= 0.0 : z >= 1.0;
    if (!leer) {
        vec4 tw = uInvVP * vec4(vUV * 2.0 - 1.0, uReversed == 1 ? z : z * 2.0 - 1.0, 1.0);
        float dTer = distance(tw.xyz / tw.w, uCam);
        if (dTer < dist - (0.08 + dist * 0.003)) discard;
    }
    // Wassertiefe über dem Grund (ohne Krümmung: der Grund dort hat seine eigene Höhe)
    float depth = max(uLevel - terrainHeight(w.xz, dist), 0.0);

    // Normale: zwei Rauschlagen, in der Ferne glatt; je Bildpunkt gemittelt, damit nichts körnig glitzert [G]
    vec2 q = w.xz;
    float pxm = dist * 0.0012;                                     // Meter je Bildpunkt, grob
    float k1 = (1.0 - smoothstep(200.0, 4000.0, dist)) / (1.0 + pxm / 3.0), k2 = (1.0 - smoothstep(30.0, 600.0, dist)) / (1.0 + pxm);
    vec2 g = vec2(vn(q / 9.0 + uTime * 0.07) - vn(q / 9.0 + vec2(3.1, 0.0) - uTime * 0.05),
                  vn(q / 7.0 - uTime * 0.06) - vn(q / 7.0 + vec2(0.0, 5.3) + uTime * 0.04)) * 0.35 * k1
           + vec2(vn(q / 1.7 + uTime * 0.3) - 0.5, vn(q / 1.3 - uTime * 0.25) - 0.5) * 0.25 * k2;
    vec3 n = normalize(vec3(g.x, 1.0, g.y));
    vec3 v = -d;
    float fres = 0.02 + 0.98 * pow(1.0 - max(dot(n, v), 0.0), 5.0);
    vec3 r = reflect(d, n);
    vec3 sky = skyColor(vec3(r.x, max(r.y, 0.01), r.z));
    float B = skyBrightness(sunElevDeg());
    // Körperfarbe: flach türkisgrün über hellem Sand, tief ein dunkles Blaugrün des Brackwassers [G nach Seebruecke1–3 und Segeln1–3]
    vec3 shallow = vec3(0.07, 0.19, 0.18), deep = vec3(0.012, 0.035, 0.050);
    vec3 body = mix(shallow, deep, smoothstep(0.5, 8.0, depth)) * (B / 0.32) * (0.35 + 0.65 * max(uSun.y, 0.0));
    vec3 col = mix(body, sky, fres);
    // Glitzer der Sonne: in der Ferne breiter und schwächer (viele Facetten in einem Bildpunkt)
    vec3 hv = normalize(uSun + v);
    float shin = mix(400.0, 60.0, smoothstep(100.0, 8000.0, dist));
    col += sunLight() * pow(max(dot(n, hv), 0.0), shin) * (shin / 400.0) * 3.0;
    // Schaum an der Wasserlinie (Platzhalter bis surf): ein Saum, wo das Wasser flach wird
    float foam = (1.0 - smoothstep(0.0, 0.5, depth)) * (0.5 + 0.5 * vn(q / 3.0 + vec2(uTime * 0.2, 0.0)));
    col = mix(col, vec3(0.85, 0.88, 0.86) * B / 0.32 * (0.35 + 0.65 * max(uSun.y, 0.05)), foam * 0.8 * (1.0 - smoothstep(500.0, 3000.0, dist)));
    col = aerial(col, d, dist);
    frag = vec4(col, 1.0);
}
