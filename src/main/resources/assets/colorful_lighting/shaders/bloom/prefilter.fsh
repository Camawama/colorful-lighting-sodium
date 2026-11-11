#version 330 core
// Bloom (2.11.0), the first pass: what of the finished picture glows, at half its size.
// A pixel glows when the block it shows emits light: its place in the world comes from the depth, and a volume of cells round
// the camera says what each block emits (rgb the light's colour; a, in sixteenths, its level in eight steps plus 8 for a
// lamp all through; EmitterVolume). How much it glows is the block's level times how bright the pixel itself is, so a
// torch's flame glows and its stick does not. A lamp all through (glowstone, a sea lantern) glows from its darker pixels too:
// coloured light deepens a lamp's own texture, and the whole block is the light there.
// Under blacklight a pixel glows too when it is a saturated colour shown brightly: that is what ultraviolet lit up.
in vec2 v_Uv;
out vec4 fragColor;

uniform sampler2D u_Color;
uniform sampler2D u_Depth;
uniform ivec2 u_Size;
// clip space to the place relative to the camera along the world's axes
uniform mat4 u_InvViewProj;

uniform int u_Volume;
uniform sampler3D u_Emitters;
uniform vec3 u_VolumeSize;
// the camera's place modulo the volume's size (the volume wraps), and the corner of what it holds relative to the camera
uniform vec3 u_CamInVolume;
uniform vec3 u_WindowMin;
uniform float u_Threshold;
uniform float u_Gain;
// 1 or 2: what this pass sees instead of the glow (Bloom.DEBUG)
uniform int u_Debug;

uniform float u_UvStrength;
uniform float u_UvGain;
uniform int u_UvField;
uniform sampler3D u_UvFieldTex;
uniform vec3 u_UvFieldOrigin;
uniform float u_UvFieldSize;
uniform int u_UvLights;
uniform vec4 u_UvPos[4];
uniform vec4 u_UvDir[4];

vec3 glowAt(ivec2 px) {
    px = clamp(px, ivec2(0), u_Size - 1);
    float depth = texelFetch(u_Depth, px, 0).r;
    if (depth >= 1.0) return vec3(0.0);
    vec3 col = texelFetch(u_Color, px, 0).rgb;
    float mx = max(col.r, max(col.g, col.b));
    if (mx < 0.08 && u_Debug == 0) return vec3(0.0);
    vec2 ndc = (vec2(px) + 0.5) / vec2(u_Size) * 2.0 - 1.0;
    vec4 h = u_InvViewProj * vec4(ndc, depth * 2.0 - 1.0, 1.0);
    vec3 rel = h.xyz / h.w;
    float dist = length(rel);
    vec3 dir = rel / max(dist, 1.0e-4);
    vec3 glow = vec3(0.0);

    if (u_Debug > 0) {
        vec3 inside = rel + dir * (0.03 + dist * 0.002);
        vec3 cell = fract(inside + u_CamInVolume);
        float hit = u_Volume > 0 && texelFetch(u_Emitters, ivec3(mod(floor(inside + u_CamInVolume), u_VolumeSize)), 0).a > 0.0 ? 1.0 : 0.0;
        return u_Debug == 1 ? vec3(hit, cell.x, cell.z) : vec3(hit, cell.y, dist / 32.0);
    }

    if (u_Volume > 0) {
        // a little way into what the pixel shows: a full block's face lies on its cell's side
        vec3 inside = rel + dir * (0.03 + dist * 0.002);
        vec3 w = inside - u_WindowMin;
        if (all(greaterThanEqual(w, vec3(0.0))) && all(lessThan(w, u_VolumeSize))) {
            vec4 e = texelFetch(u_Emitters, ivec3(mod(floor(inside + u_CamInVolume), u_VolumeSize)), 0);
            if (e.a > 0.0) {
                // how bright the pixel is: half by its luminance, half by its strongest channel, so a deep red or blue lamp
                // still glows and a lamp's brown frame does not. A lamp shines more than the picture can show, so its glow
                // is taken at more than the pixel's own colour (u_Gain); the last pass folds that back into the picture.
                int a = int(e.a * 15.0 + 0.5);
                float from = (a & 8) != 0 ? u_Threshold * 0.25 : u_Threshold;
                float bright = smoothstep(from, from + 0.4, mix(dot(col, vec3(0.299, 0.587, 0.114)), mx, 0.5));
                vec3 tint = e.rgb / max(max(e.r, max(e.g, e.b)), 0.06);
                glow += mix(col, tint * mx, 0.3) * (bright * (float(a & 7) / 7.0) * u_Gain);
            }
        }
    }

    if (u_UvStrength > 0.0) {
        float uv = 0.0;
        if (u_UvField > 0) {
            // the open cell in front of what the pixel shows
            vec3 tc = (rel - dir * 0.5 - u_UvFieldOrigin) / u_UvFieldSize;
            if (all(greaterThan(tc, vec3(0.0))) && all(lessThan(tc, vec3(1.0)))) {
                vec2 f = textureLod(u_UvFieldTex, tc, 0.0).rg;
                if (f.g > 0.02) uv = f.r / f.g;
            }
        }
        for (int i = 0; i < u_UvLights; i++) {
            vec3 to = rel - u_UvPos[i].xyz;
            float d = length(to);
            float a = clamp(1.0 - d / max(u_UvPos[i].w, 1.0), 0.0, 1.0);
            float cone = u_UvDir[i].w;
            if (cone < 1.0) a *= smoothstep(cone, mix(cone, 1.0, 0.25), dot(to / max(d, 1.0e-4), u_UvDir[i].xyz));
            uv = max(uv, a);
        }
        if (uv > 0.0) {
            float mn = min(col.r, min(col.g, col.b));
            float vivid = smoothstep(0.5, 0.85, (mx - mn) / mx) * smoothstep(0.45, 0.85, mx);
            // the picture shows the blacklight's falloff already: here it only says whether the pixel is under one
            glow += col * (vivid * min(uv * 4.0, 1.0) * u_UvStrength * u_UvGain);
        }
    }
    return glow;
}

void main() {
    // the four pixels this one covers, each judged by itself: a flame one pixel wide neither flickers nor is lost
    ivec2 base = ivec2(gl_FragCoord.xy) * 2;
    vec3 sum = glowAt(base) + glowAt(base + ivec2(1, 0)) + glowAt(base + ivec2(0, 1)) + glowAt(base + ivec2(1, 1));
    fragColor = vec4(sum * 0.25, 1.0);
}
