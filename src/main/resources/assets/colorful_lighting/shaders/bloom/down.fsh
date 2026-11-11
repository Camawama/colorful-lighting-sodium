#version 330 core
// Bloom: to half the size. Thirteen taps over a square of six by six pixels of the texture read (2.11.1): the middle, the
// four pixel corners round it, and eight points two pixels out, each a bilinear read of four pixels. Five taps (2.11.0) let
// a bright spot weigh differently by where it lay on the pixel grid, and the glow of a lamp wavered as the camera moved.
in vec2 v_Uv;
out vec4 fragColor;
uniform sampler2D u_Tex;
// one pixel of the texture read
uniform vec2 u_Texel;

void main() {
    vec2 t = u_Texel;
    vec3 inner = texture(u_Tex, v_Uv + vec2(-t.x, -t.y)).rgb + texture(u_Tex, v_Uv + vec2(t.x, -t.y)).rgb
            + texture(u_Tex, v_Uv + vec2(-t.x, t.y)).rgb + texture(u_Tex, v_Uv + vec2(t.x, t.y)).rgb;
    vec3 middle = texture(u_Tex, v_Uv).rgb;
    vec3 corners = texture(u_Tex, v_Uv + vec2(-2.0 * t.x, -2.0 * t.y)).rgb + texture(u_Tex, v_Uv + vec2(2.0 * t.x, -2.0 * t.y)).rgb
            + texture(u_Tex, v_Uv + vec2(-2.0 * t.x, 2.0 * t.y)).rgb + texture(u_Tex, v_Uv + vec2(2.0 * t.x, 2.0 * t.y)).rgb;
    vec3 edges = texture(u_Tex, v_Uv + vec2(-2.0 * t.x, 0.0)).rgb + texture(u_Tex, v_Uv + vec2(2.0 * t.x, 0.0)).rgb
            + texture(u_Tex, v_Uv + vec2(0.0, -2.0 * t.y)).rgb + texture(u_Tex, v_Uv + vec2(0.0, 2.0 * t.y)).rgb;
    fragColor = vec4(inner * 0.125 + middle * 0.125 + corners * 0.03125 + edges * 0.0625, 1.0);
}
