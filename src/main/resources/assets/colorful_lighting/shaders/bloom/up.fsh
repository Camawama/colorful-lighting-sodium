#version 330 core
// Bloom: back up to twice the size, added to what is there: nine taps, a tent three pixels of the texture read wide
// (2.11.1; a ring of eight without its middle before). The last of these draws onto the game's picture, where u_Scale is the
// bloom's strength and u_Soft folds the sum into 0..1 (the picture cannot show more, and the pass is blended as a screen:
// what is bright already takes less of it, so a lamp keeps its texture).
in vec2 v_Uv;
out vec4 fragColor;
uniform sampler2D u_Tex;
// one pixel of the texture read
uniform vec2 u_Texel;
uniform float u_Scale;
uniform float u_Soft;

void main() {
    vec2 t = u_Texel;
    vec3 sum = texture(u_Tex, v_Uv).rgb * 4.0;
    sum += (texture(u_Tex, v_Uv + vec2(-t.x, 0.0)).rgb + texture(u_Tex, v_Uv + vec2(t.x, 0.0)).rgb
            + texture(u_Tex, v_Uv + vec2(0.0, -t.y)).rgb + texture(u_Tex, v_Uv + vec2(0.0, t.y)).rgb) * 2.0;
    sum += texture(u_Tex, v_Uv + vec2(-t.x, -t.y)).rgb + texture(u_Tex, v_Uv + vec2(t.x, -t.y)).rgb
            + texture(u_Tex, v_Uv + vec2(-t.x, t.y)).rgb + texture(u_Tex, v_Uv + vec2(t.x, t.y)).rgb;
    sum *= u_Scale / 16.0;
    if (u_Soft > 0.0) sum = 1.0 - exp(-sum);
    fragColor = vec4(sum, 1.0);
}
