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
// Light sources on ships (2.11.2): a cube of 64 cells a ship in one texture, stacked along z, in the ship's own block grid;
// u_ShipFromRel takes a place relative to the camera into a ship's cube (the ship as it is drawn this frame).
uniform int u_Ships;
uniform mat4 u_ShipFromRel[4];
uniform sampler3D u_ShipEmitters;
// Items that give light (2.12.0), as they were drawn: a ball each, its middle relative to the camera and its radius, and the
// colour of its light with what a cell's alpha would be (ItemGlows). A bright pixel in a ball is a light source.
uniform int u_Glows;
uniform vec4 u_GlowPos[12];
uniform vec4 u_GlowColor[12];
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

// The glow of the frame before (2.11.1): this pass's own output then, read where the pixel's place in the world was on the
// screen (u_PrevViewProj over the place relative to that frame's camera, u_CamDelta further on). u_Keep is the share of it
// that stays, 0 for none.
uniform sampler2D u_History;
uniform mat4 u_PrevViewProj;
uniform vec3 u_CamDelta;
uniform float u_Keep;

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

    if (u_Volume > 0 || u_Ships > 0 || u_Glows > 0) {
        // a little way into what the pixel shows: a full block's face lies on its cell's side
        vec3 inside = rel + dir * (0.03 + dist * 0.002);
        vec3 w = inside - u_WindowMin;
        vec4 e = vec4(0.0);
        if (u_Volume > 0 && all(greaterThanEqual(w, vec3(0.0))) && all(lessThan(w, u_VolumeSize))) {
            e = texelFetch(u_Emitters, ivec3(mod(floor(inside + u_CamInVolume), u_VolumeSize)), 0);
            if (e.a <= 0.0 && e.r > 0.9 && e.g > 0.9 && e.b > 0.9) {
                // glass (2.11.3): what the pixel shows lies behind it. On through glass alone, half a block a step, to a light
                // source; air or anything else ends the way (a wall cannot be told from air here).
                vec3 at = inside;
                e = vec4(0.0);
                for (int s = 0; s < 8; s++) {
                    at += dir * 0.5;
                    vec3 through = at - u_WindowMin;
                    if (any(lessThan(through, vec3(0.0))) || any(greaterThanEqual(through, u_VolumeSize))) break;
                    vec4 next = texelFetch(u_Emitters, ivec3(mod(floor(at + u_CamInVolume), u_VolumeSize)), 0);
                    if (next.a > 0.0) {
                        e = next;
                        break;
                    }
                    if (!(next.r > 0.9 && next.g > 0.9 && next.b > 0.9)) break;
                }
            }
        }
        // nothing in the world there: a block of a ship, looked up in the ship's own grid
        for (int i = 0; i < u_Ships && e.a <= 0.0; i++) {
            vec3 cell = (u_ShipFromRel[i] * vec4(inside, 1.0)).xyz;
            if (all(greaterThanEqual(cell, vec3(0.0))) && all(lessThan(cell, vec3(64.0)))) {
                e = texelFetch(u_ShipEmitters, ivec3(floor(cell)) + ivec3(0, 0, 64 * i), 0);
                if (e.a <= 0.0 && e.r > 0.9 && e.g > 0.9 && e.b > 0.9) {
                    // glass on the ship (2.12.0): on through glass alone, as in the world, along the ship's own axes
                    vec3 along = (u_ShipFromRel[i] * vec4(dir, 0.0)).xyz * 0.5;
                    e = vec4(0.0);
                    for (int s = 0; s < 8; s++) {
                        cell += along;
                        if (any(lessThan(cell, vec3(0.0))) || any(greaterThanEqual(cell, vec3(64.0)))) break;
                        vec4 next = texelFetch(u_ShipEmitters, ivec3(floor(cell)) + ivec3(0, 0, 64 * i), 0);
                        if (next.a > 0.0) {
                            e = next;
                            break;
                        }
                        if (!(next.r > 0.9 && next.g > 0.9 && next.b > 0.9)) break;
                    }
                }
            }
        }
        if (e.a <= 0.0) e = vec4(0.0);
        // no block: an item that gives light, where it was drawn
        for (int i = 0; i < u_Glows && e.a <= 0.0; i++) {
            vec3 off = rel - u_GlowPos[i].xyz;
            if (dot(off, off) < u_GlowPos[i].w * u_GlowPos[i].w) e = u_GlowColor[i];
        }
        {
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
    vec3 glow = sum * 0.25;
    if (u_Keep > 0.0) {
        float depth = texelFetch(u_Depth, clamp(base, ivec2(0), u_Size - 1), 0).r;
        vec2 ndc = (vec2(base) + 1.0) / vec2(u_Size) * 2.0 - 1.0;
        vec4 h = u_InvViewProj * vec4(ndc, depth * 2.0 - 1.0, 1.0);
        vec4 before = u_PrevViewProj * vec4(h.xyz / h.w + u_CamDelta, 1.0);
        if (before.w > 1.0e-4) {
            vec2 at = before.xy / before.w * 0.5 + 0.5;
            if (all(greaterThan(at, vec2(0.0))) && all(lessThan(at, vec2(1.0)))) {
                glow = mix(glow, texture(u_History, at).rgb, u_Keep);
            }
        }
    }
    fragColor = vec4(glow, 1.0);
}
