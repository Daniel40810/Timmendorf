// overlay.frag — ein fertiges Bild (vormultipliziert) über das Ziel legen: das HUD (aus Kīlauea über Lighthouse).
// Eingaben: uTex (RGBA, vormultipliziert), uFlipY (1 = Bild von oben nach unten, wie aus Java2D), uGain.
// Mischung außen: ONE, ONE_MINUS_SRC_ALPHA.
in vec2 vUV;
out vec4 frag;
uniform sampler2D uTex;
uniform int uFlipY = 0;
uniform float uGain = 1.0;

void main() {
    if (vUV.x > 1.0 || vUV.y > 1.0) discard;
    vec2 uv = uFlipY == 1 ? vec2(vUV.x, 1.0 - vUV.y) : vUV;
    frag = textureLod(uTex, uv, 0.0) * uGain;
}
