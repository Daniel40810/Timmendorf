// lum.frag — Sylt Phase 3 (aus Brocken, dort aus Geiranger Phase 8): Belichtungsmessung im Bild. Je Texel der Messkarte (64 × 64) der Logarithmus der mittleren
// Leuchtdichte von 16 Proben des HDR-Bilds (vorbelichtet); die Mip-Stufen mitteln daraus das ganze Bild (geometrisches
// Mittel), Belichtung.java liest den Wert einen Schritt später ohne zu warten. Die Mitte zählt doppelt. Seit Timmendorf Phase 15 im
// dritten Kanal das lineare Mittel (je Probe höchstens 8 nach der Vorbelichtung): helle Flächen halten die Anpassung, wie im Auge.
in vec2 vUV;
out vec4 frag;
uniform sampler2D uHdr;
void main() {
    vec2 t = 1.0 / vec2(64.0);
    float s = 0.0, lin = 0.0;
    for (int j = 0; j < 4; j++)
        for (int i = 0; i < 4; i++) {
            vec3 c = textureLod(uHdr, vUV + (vec2(i, j) - 1.5) * 0.25 * t, 0.0).rgb;
            float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
            s += log(max(1e-7, l));
            lin += min(max(l, 0.0), 8.0);   // seit Timmendorf Phase 15: das lineare Mittel, Spitzen (Lampenköpfe, Sterne) gekappt
        }
    vec2 q = vUV - 0.5;
    float w = 1.0 + 1.0 * (1.0 - smoothstep(0.1, 0.35, length(q)));
    frag = vec4(s / 16.0 * w, w, lin / 16.0 * w, 1.0);
}
