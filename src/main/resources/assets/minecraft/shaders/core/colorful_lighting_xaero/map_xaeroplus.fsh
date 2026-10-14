#version 150

// map.fsh with XaeroPlus's transparent background (its xaeroplus/custom_map.fsh), for packs with XaeroPlus.

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;

uniform float Brightness;
uniform int WithLight;
uniform int TransparentBackground;

in vec2 texCoord0;

out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord0);
    if (color == vec4(0, 0, 0, 0))
        discard;
    if (TransparentBackground == 1) {
        // discard black even if alpha is >0 (XaeroPlus: the World Map background as a branch)
        if (color.r == 0.0 && color.g == 0.0 && color.b == 0.0) {
            discard;
        }
    }
    if (WithLight == 0) {
        fragColor = vec4(color.rgb * Brightness, 1);
        return;
    }
    // Xaero's pixel light: (9 + blockLight) / 24, 0 when unlit
    float light = color.a;
    vec3 litBrightness = vec3(max(light, Brightness));
    vec4 hue = texture(Sampler1, texCoord0);
    if (hue.a > 0.0 && light > 0.0) {
        float blockLight = max(light * 24.0 - 9.0, 0.0);
        vec3 colored = (9.0 + blockLight * hue.rgb / hue.a) / 24.0;
        litBrightness = max(mix(vec3(light), colored, hue.a), vec3(Brightness));
    }
    fragColor = vec4(color.rgb * litBrightness, 1);
}
