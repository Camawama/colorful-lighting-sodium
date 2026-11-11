#version 330 core
// Bloom (2.11.0): to half the size, five taps (the middle and the four corners half a pixel out).
in vec2 v_Uv;
out vec4 fragColor;
uniform sampler2D u_Tex;
// one pixel of the texture read
uniform vec2 u_Texel;

void main() {
    vec2 hp = u_Texel * 0.5;
    vec3 sum = texture(u_Tex, v_Uv).rgb * 4.0;
    sum += texture(u_Tex, v_Uv - hp).rgb;
    sum += texture(u_Tex, v_Uv + hp).rgb;
    sum += texture(u_Tex, v_Uv + vec2(hp.x, -hp.y)).rgb;
    sum += texture(u_Tex, v_Uv - vec2(hp.x, -hp.y)).rgb;
    fragColor = vec4(sum * 0.125, 1.0);
}
