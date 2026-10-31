// post.frag — HDR-Bild in das Fensterbild (aus Geiranger): Belichtung, Filmkurve (ACES nach Narkowicz),
// Gamma, feines Rauschen gegen Stufen im Himmelsverlauf, leichte Randabdunklung. Eingaben: uHdr (RGBA16F), uExposure;
// ab Phase 4 uScotopic: nachts sieht das Auge mit den Stäbchen (farblos, ins Blaue verschoben), aus com.dan.sky;
// (Befund aus Rungholt, Phase 11) nur im Dunkeln des Bilds, Helles behält seine Farbe.
in vec2 vUV;
out vec4 frag;
uniform sampler2D uHdr;
uniform float uExposure;
uniform float uScotopic;

vec3 aces(vec3 x) {
    const float a = 2.51, b = 0.03, c = 2.43, d = 0.59, e = 0.14;
    return clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0, 1.0);
}

void main() {
    vec3 hdr = texture(uHdr, vUV).rgb * uExposure;
    float ys = dot(hdr, vec3(0.02, 0.62, 0.36));
    // Befund aus Rungholt: was im Bild hell ist (ab Phase 11 die Lampen der Seebrücke und die Fenster an der Promenade), sehen die Zapfen weiter in Farbe;
    // nur das Dunkle wird farblos und blau
    float lum = dot(hdr, vec3(0.2126, 0.7152, 0.0722));
    hdr = mix(hdr, ys * vec3(0.62, 0.78, 1.08), uScotopic * (1.0 - smoothstep(0.12, 0.7, lum)));
    vec2 q = vUV - 0.5;
    hdr *= 1.0 - 0.22 * dot(q, q);
    vec3 c = pow(aces(hdr), vec3(1.0 / 2.2));
    float n = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453);
    c += (n - 0.5) / 255.0;
    frag = vec4(c, 1.0);
}
