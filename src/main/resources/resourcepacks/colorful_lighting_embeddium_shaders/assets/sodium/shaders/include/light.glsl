uniform float colorfullighting_mod_injected_u_NightVibrancy;
uniform float u_ColoredLightingEnabled;
#ifdef CL_DYNAMIC_LIGHTS
uniform mat3 cl_dynInvView;

// --- dynamic lights (2.8.0): the external providers' lights, per vertex, camera-relative ---
// cl_dynPos: xyz the light relative to the camera, w its level (0..15) plus 32 when it falls off by straight distance
// (a round light) rather than by Manhattan distance (vanilla's diamond). cl_dynColor: rgb 0..1, w the reach in blocks.
// cl_dynDir: xyz a spot light's axis, w the cosine of half its full angle (1.0 for a point light). The inner, fully lit
// cone is 0.6 of the outer angle: its cosine is approximated from the outer's.
#define CL_DYN_MAX 64
uniform int cl_dynCount;
uniform vec4 cl_dynPos[CL_DYN_MAX];
uniform vec4 cl_dynColor[CL_DYN_MAX];
uniform vec4 cl_dynDir[CL_DYN_MAX];

// Transmission (2.8.1): cl_dynTransmission 1 samples cl_dynOcc, a 64-block cube round the camera (origin cl_dynOccOrigin,
// camera-relative) holding what each block lets through, per channel: air 1, an opaque block 0, stained glass its filter
// colour. The march from the point lit toward the light multiplies the light by every block it crosses (one sample a block),
// so a wall stops the light and glass tints it, as Colorful Lighting's engine does for block light. The first and last 0.6
// blocks are skipped (the lit block's own face, the lamp's own block).
uniform int cl_dynTransmission;
uniform sampler3D cl_dynOcc;
uniform vec3 cl_dynOccOrigin;

vec3 cl_dynTransmit(vec3 from, vec3 to) {
    vec3 seg = to - from;
    float len = length(seg);
    if (len < 1.5) return vec3(1.0);
    vec3 dir = seg / len;
    float start = 0.6, end = len - 0.6;
    int steps = int(clamp(ceil(end - start), 1.0, 32.0));
    vec3 through = vec3(1.0);
    for (int s = 0; s < steps; s++) {
        float t = start + (end - start) * (float(s) + 0.5) / float(steps);
        vec3 w = from + dir * t;
        vec3 tc = (w - cl_dynOccOrigin) / 64.0;
        if (any(lessThan(tc, vec3(0.0))) || any(greaterThan(tc, vec3(1.0)))) continue;
        through *= texture(cl_dynOcc, tc).rgb;
        if (max(max(through.r, through.g), through.b) <= 0.01) return vec3(0.0);
    }
    return through;
}

vec3 cl_dynamicLight(vec3 wrel) {
    vec3 acc = vec3(0.0);
    for (int i = 0; i < cl_dynCount; i++) {
        vec4 p = cl_dynPos[i];
        vec3 d = wrel - p.xyz;
        vec4 c = cl_dynColor[i];
        float range = c.w;
        float dist2 = dot(d, d);
        if (dist2 > range * range) continue;
        float falloff = floor(p.w / 32.0);
        float level = p.w - falloff * 32.0;
        vec4 dir = cl_dynDir[i];
        float l;
        if (dir.w < 1.0) {
            float dist = sqrt(dist2);
            float cosA = dist > 1.0e-4 ? dot(d, dir.xyz) / dist : 1.0;
            if (cosA <= dir.w) continue;
            float innerCos = 1.0 - (1.0 - dir.w) * 0.36;
            float cone = innerCos <= dir.w ? 1.0 : clamp((cosA - dir.w) / (innerCos - dir.w), 0.0, 1.0);
            l = level * (1.0 - dist / range) * cone;
        } else if (falloff > 0.5) {
            l = level - sqrt(dist2);
        } else {
            l = level - (abs(d.x) + abs(d.y) + abs(d.z));
        }
        if (l <= 0.0) continue;
        vec3 lit = c.rgb * (min(l, 15.0) / 15.0);
        if (cl_dynTransmission > 0) {
            lit *= cl_dynTransmit(wrel, p.xyz);
            if (max(max(lit.r, lit.g), lit.b) <= 0.0) continue;
        }
        acc = max(acc, lit);
    }
    return acc;
}
#endif

// --- COLORFUL LIGHTING START ---
vec4 _sample_lightmap_vanilla(sampler2D lightMap, ivec2 uv) {
    return texture(lightMap, clamp(uv / 256.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0)));
}

vec4 _sample_colored_common(sampler2D lightMap, uint sl4, uint red8, uint green8, uint blue8) {
    vec3 sky = _sample_lightmap_vanilla(lightMap, ivec2(0, int(sl4) << 4)).xyz;
    vec3 block = vec3(
        _sample_lightmap_vanilla(lightMap, ivec2(int(red8), 0)).r,
        _sample_lightmap_vanilla(lightMap, ivec2(int(green8), 0)).r,
        _sample_lightmap_vanilla(lightMap, ivec2(int(blue8), 0)).r
    );

    float moonWashoutFactor = mix(1.0, 0.0, colorfullighting_mod_injected_u_NightVibrancy);
    float skyExposure = float(sl4) / 16.0;
    float effectiveSkyBrightness = sky.r * moonWashoutFactor * skyExposure;
    float washFactor = max(0.1, 1.0 - effectiveSkyBrightness);

    block = mix(vec3(length(block)), block, washFactor * 0.25 + 0.75);

    return vec4(sky + block * (max(0.1, 1.0 - sky.r) * 0.9 + 0.1), 1.0);
}

vec4 _sample_lightmap_dyn(sampler2D lightMap, ivec2 uv, vec3 wrel);

vec4 _sample_lightmap(sampler2D lightMap, ivec2 uv) {
    return _sample_lightmap_dyn(lightMap, uv, vec3(0.0));
}

// wrel: the vertex relative to the camera, along the world's axes (the dynamic lights are given that way)
vec4 _sample_lightmap_dyn(sampler2D lightMap, ivec2 uv, vec3 wrel) {
    vec3 dyn = vec3(0.0);
#ifdef CL_DYNAMIC_LIGHTS
    if (cl_dynCount > 0) dyn = cl_dynamicLight(wrel) * 255.0;
#endif
    uvec3 dyn8 = uvec3(clamp(dyn, 0.0, 255.0));

    uint packed_light;
    #ifdef USE_VERTEX_COMPRESSION
    packed_light = (uint(uv.y) << 16) | uint(uv.x);
    #else
    // In non-compact mode, the 16-bit light data is split into two 8-bit values.
    // We need to reconstruct the 16-bit value here.
    packed_light = (uint(uv.y) << 8) | uint(uv.x);
    #endif

    // Check for our magic number in the highest 4 bits.
    if (((packed_light >> 28) & 0xFu) == 0xFu) {
        // It's our format (Compact), unpack the full color data.
        uint red8 = (packed_light >> 0) & 0xFFu;
        uint green8 = (packed_light >> 8) & 0xFFu;
        uint skyLight4 = (packed_light >> 16) & 0xFu;
        uint blue8 = (packed_light >> 20) & 0xFFu;
        if (u_ColoredLightingEnabled < 0.5) {
            // Engine disabled: render stale colored-format vertices as plain vanilla light
            uint block8 = max(max(red8, green8), blue8);
            return _sample_lightmap_vanilla(lightMap, ivec2(int(block8), int(skyLight4) << 4));
        }

        return _sample_colored_common(lightMap, skyLight4, max(red8, dyn8.r), max(green8, dyn8.g), max(blue8, dyn8.b));
    }

    // Check if it's our compressed 16-bit non-compact format (bit 0 == 1)
    if ((packed_light & 0x1u) == 0x1u) {
        // Unpack from the 16-bit integer (actually the lower 16 bits of packed_light)
        uint skyLight4 = (packed_light >> 1) & 0xFu;
        uint red4 = (packed_light >> 5) & 0xFu;
        uint green4 = (packed_light >> 9) & 0xFu;
        uint blue3 = (packed_light >> 13) & 0x7u;
        if (u_ColoredLightingEnabled < 0.5) {
            // Engine disabled: render stale colored-format vertices as plain vanilla light
            uint block4 = max(max(red4, green4), (blue3 * 15u) / 7u);
            return _sample_lightmap_vanilla(lightMap, ivec2(int(block4) << 4, int(skyLight4) << 4));
        }

        // Expand to 8-bit equivalent
        float red8 = float(red4) / 15.0;
        float green8 = float(green4) / 15.0;
        float blue8 = float(blue3) / 7.0;

        return _sample_colored_common(
            lightMap,
            skyLight4,
            // cast to uint, for consistency
            max(uint(red8 * 255.0), dyn8.r),
            max(uint(green8 * 255.0), dyn8.g),
            max(uint(blue8 * 255.0), dyn8.b)
        );
    }

    // Not our format: vanilla light, raised by the dynamic lights where they reach
    if (max(max(dyn8.r, dyn8.g), dyn8.b) > 0u) {
        uint block8 = uint(clamp(uv.x, 0, 255));
        uint sky4 = uint(clamp(uv.y, 0, 255)) >> 4;
        return _sample_colored_common(lightMap, sky4, max(block8, dyn8.r), max(block8, dyn8.g), max(block8, dyn8.b));
    }
    return _sample_lightmap_vanilla(lightMap, uv);
}
// --- COLORFUL LIGHTING END ---