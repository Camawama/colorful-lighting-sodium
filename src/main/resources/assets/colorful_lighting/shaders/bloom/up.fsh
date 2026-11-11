#version 330 core
// Bloom (2.11.0): back up to twice the size, eight taps in a ring, added to what is there. The last of these draws onto the
// game's picture, where u_Scale is the bloom's strength and u_Soft folds the sum into 0..1 (the picture cannot show more, and
// the pass is blended as a screen: what is bright already takes less of it, so a lamp keeps its texture).
in vec2 v_Uv;
out vec4 fragColor;
uniform sampler2D u_Tex;
// one pixel of the texture read
uniform vec2 u_Texel;
uniform float u_Scale;
uniform float u_Soft;

void main() {
    vec2 hp = u_Texel * 0.5;
    vec3 sum = texture(u_Tex, v_Uv + vec2(-hp.x * 2.0, 0.0)).rgb;
    sum += texture(u_Tex, v_Uv + vec2(-hp.x, hp.y)).rgb * 2.0;
    sum += texture(u_Tex, v_Uv + vec2(0.0, hp.y * 2.0)).rgb;
    sum += texture(u_Tex, v_Uv + vec2(hp.x, hp.y)).rgb * 2.0;
    sum += texture(u_Tex, v_Uv + vec2(hp.x * 2.0, 0.0)).rgb;
    sum += texture(u_Tex, v_Uv + vec2(hp.x, -hp.y)).rgb * 2.0;
    sum += texture(u_Tex, v_Uv + vec2(0.0, -hp.y * 2.0)).rgb;
    sum += texture(u_Tex, v_Uv + vec2(-hp.x, -hp.y)).rgb * 2.0;
    sum *= u_Scale / 12.0;
    if (u_Soft > 0.0) sum = 1.0 - exp(-sum);
    fragColor = vec4(sum, 1.0);
}
