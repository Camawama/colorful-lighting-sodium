uniform float colorfullighting_mod_injected_u_NightVibrancy;
uniform float u_ColoredLightingEnabled;
#ifdef CL_DYNAMIC_LIGHTS
uniform mat3 cl_dynInvView;

// --- dynamic lights (2.8.0; the slabs since 2.10.0): the external providers' lights, camera-relative ---
// cl_dynPos: xyz the light relative to the camera, w its level (0..31, drawn as at most 15) plus 32 when it falls off by straight distance (a
// round light) rather than by Manhattan distance (vanilla's diamond). cl_dynColor: rgb 0..1, w the reach in blocks.
// cl_dynDir: xyz a spot light's axis, w the cosine of half its full angle (1.0 for a point light). The inner, fully lit cone
// is 0.6 of the outer angle: its cosine is approximated from the outer's.
#define CL_DYN_MAX 64
uniform int cl_dynCount;
uniform vec4 cl_dynPos[CL_DYN_MAX];
uniform vec4 cl_dynColor[CL_DYN_MAX];
uniform vec4 cl_dynDir[CL_DYN_MAX];

// The slabs (2.10.0): per light a cube of cells round it holding where its light may go, in three 3D textures by reach (each
// a stack of slabs along z): rgb the tint picked up through filter blocks on the way, a how much longer the way through the
// blocks is than the straight way (the detour, as a share of the slab's half size; 1 where the light never gets). The light
// itself is the analytic falloff below, at the exact point; the slab only scales it: 1 in the open, less by the detour's share
// of the reach left at the point, 0 behind a wall. Sampled half a block out along the face's normal (trilinear), as vanilla's
// smooth lighting reads the blocks in front of a face. cl_dynSlab: xyz the slab's corner relative to the camera, w the class
// times 1000 plus the slot, or -1 for a light without one (nothing in its way then).
uniform int cl_dynSlabs;
uniform vec4 cl_dynSlab[CL_DYN_MAX];
uniform sampler3D cl_dynSlabTex0;
uniform sampler3D cl_dynSlabTex1;
uniform sampler3D cl_dynSlabTex2;
uniform vec3 cl_dynSlabSize;
uniform vec3 cl_dynSlabCount;

// The lights the fragments draw themselves (2.10.3): the first cl_dynFragSpots of the arrays, the spot lights. A cone is
// narrower than a block close to its light; taken at the vertices alone it lit whole block corners or none of them, so a
// flashlight's patch on the ground was jagged and all but gone from close up. The vertices take the lights after those.
uniform int cl_dynFragSpots;

#ifdef CL_BLACKLIGHT
// Blacklight (2.11.0): cl_dynUvBits says which of the lights are ultraviolet (a bit a light, the first 32 and the rest).
// What such a light shows is its own dim violet; how much of it reaches a point (0..1, the level left there over 15, less by
// what is in its way) is gathered in cl_uvAcc for the fragments, which make saturated colours glow by it (light_frag.glsl).
// cl_uvStrength: the config's strength, 0 while nothing ultraviolet is about.
uniform ivec2 cl_dynUvBits;
uniform float cl_uvStrength;
float cl_uvAcc = 0.0;
#endif

// Lights that absorb (2.12.0): cl_dynAbsBits says which of the lights they are (a bit a light, as cl_dynUvBits). What they
// take at a point, 0..1 a channel, is gathered in cl_absAcc; the block light and the sky light are taken down by it.
uniform ivec2 cl_dynAbsBits;
vec3 cl_absAcc = vec3(0.0);

vec3 cl_dynVisibility(vec4 s, vec3 at, float remaining) {
    int cls = int(s.w / 1000.0);
    float slot = s.w - float(cls) * 1000.0;
    float size = cls == 0 ? cl_dynSlabSize.x : cls == 1 ? cl_dynSlabSize.y : cl_dynSlabSize.z;
    float count = cls == 0 ? cl_dynSlabCount.x : cls == 1 ? cl_dynSlabCount.y : cl_dynSlabCount.z;
    vec3 local = clamp(at - s.xyz, vec3(0.5), vec3(size - 0.5));
    vec3 tc = vec3(local.xy / size, (slot * size + local.z) / (size * count));
    vec4 v = cls == 0 ? texture(cl_dynSlabTex0, tc) : cls == 1 ? texture(cl_dynSlabTex1, tc) : texture(cl_dynSlabTex2, tc);
    float detour = v.a * (size - 1.0) * 0.5;
    return v.rgb * clamp(1.0 - detour / max(remaining, 1.0e-3), 0.0, 1.0);
}

// the lights from..to-1 of the arrays at a point
vec3 cl_dynamicLightRange(vec3 wrel, vec3 normal, int from, int to) {
    vec3 acc = vec3(0.0);
    vec3 at = wrel + normal * 0.5;
    for (int i = from; i < to; i++) {
        vec4 p = cl_dynPos[i];
        vec3 d = wrel - p.xyz;
        vec4 c = cl_dynColor[i];
        float range = c.w;
        float dist2 = dot(d, d);
        if (dist2 > range * range) continue;
        float falloff = floor(p.w / 32.0);
        float level = p.w - falloff * 32.0;
        vec4 dir = cl_dynDir[i];
        // l: the level left here; remaining: the blocks of reach left here (a detour that long takes the rest)
        float l, remaining;
        if (dir.w < 1.0) {
            float dist = sqrt(dist2);
            float cosA = dist > 1.0e-4 ? dot(d, dir.xyz) / dist : 1.0;
            float innerCos = 1.0 - (1.0 - dir.w) * 0.36;
            float cone = cosA <= dir.w ? 0.0 : innerCos <= dir.w ? 1.0 : clamp((cosA - dir.w) / (innerCos - dir.w), 0.0, 1.0);
            if (cone <= 0.0) continue;
            remaining = range - dist;
            l = level * (remaining / range) * cone;
        } else {
            // a point light's direction is unused: its x is the blocks a level lasts when that isn't 1 (2.10.6)
            float stretch = dir.x > 0.0 ? dir.x : 1.0;
            remaining = level * stretch - (falloff > 0.5 ? sqrt(dist2) : abs(d.x) + abs(d.y) + abs(d.z));
            l = remaining / stretch;
        }
        if (l <= 0.0) continue;
        vec3 light = c.rgb * (min(l, 15.0) / 15.0);
        vec4 s = cl_dynSlab[i];
        if (cl_dynSlabs > 0 && s.w >= 0.0) light *= cl_dynVisibility(s, at, remaining);
        if ((((i < 32 ? cl_dynAbsBits.x : cl_dynAbsBits.y) >> (i & 31)) & 1) != 0) {
            // a light that absorbs (2.12.0): what it would have added is taken away instead
            cl_absAcc = max(cl_absAcc, light);
            continue;
        }
#ifdef CL_BLACKLIGHT
        if ((((i < 32 ? cl_dynUvBits.x : cl_dynUvBits.y) >> (i & 31)) & 1) != 0) {
            cl_uvAcc = max(cl_uvAcc, max(light.r, max(light.g, light.b)) / max(max(c.r, max(c.g, c.b)), 1.0e-3));
        }
#endif
        acc = max(acc, light);
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

vec4 _sample_lightmap_dyn(sampler2D lightMap, ivec2 uv, vec3 wrel, mat3 toWorld);

vec4 _sample_lightmap(sampler2D lightMap, ivec2 uv) {
    return _sample_lightmap_dyn(lightMap, uv, vec3(0.0), mat3(1.0));
}

#ifdef CL_DYNAMIC_LIGHTS
// the face a vertex belongs to, from the top nibble of its packed light (8 plus the face's index since 2.9.0; 15: not known)
vec3 cl_facingNormal(uint nib) {
    if (nib >= 14u) return vec3(0.0);
    uint f = nib & 7u;
    if (f == 0u) return vec3(0.0, -1.0, 0.0);
    if (f == 1u) return vec3(0.0, 1.0, 0.0);
    if (f == 2u) return vec3(0.0, 0.0, -1.0);
    if (f == 3u) return vec3(0.0, 0.0, 1.0);
    if (f == 4u) return vec3(-1.0, 0.0, 0.0);
    return vec3(1.0, 0.0, 0.0);
}

// for the fragments' spot lights: the vertex's own light before the lightmap (rgb 0..255 with the vertices' dynamic lights
// in, a the sky nibble; -1: none to add to) and its face along the world's axes
vec4 cl_baseLight = vec4(0.0, 0.0, 0.0, -1.0);
vec3 cl_faceNormal = vec3(0.0);
#endif

// wrel: the vertex relative to the camera, along the world's axes (the dynamic lights are given that way); toWorld turns
// the vertex's face the same way (a ship's faces point where the ship does)
vec4 _sample_lightmap_dyn(sampler2D lightMap, ivec2 uv, vec3 wrel, mat3 toWorld) {
    uint packed_light;
    #ifdef USE_VERTEX_COMPRESSION
    packed_light = (uint(uv.y) << 16) | uint(uv.x);
    #else
    // In non-compact mode, the 16-bit light data is split into two 8-bit values.
    // We need to reconstruct the 16-bit value here.
    packed_light = (uint(uv.y) << 8) | uint(uv.x);
    #endif
    uint nib = (packed_light >> 28) & 0xFu;

    vec3 dyn = vec3(0.0);
#ifdef CL_DYNAMIC_LIGHTS
    {
        // the face always (2.11.0): the blacklight field is read in front of it with no light about
        vec3 n = cl_facingNormal(nib);
        if (dot(n, n) > 0.5) n = normalize(toWorld * n);
        cl_faceNormal = n;
        if (cl_dynCount > 0) dyn = cl_dynamicLightRange(wrel, n, cl_dynFragSpots, cl_dynCount) * 255.0;
    }
#endif
    uvec3 dyn8 = uvec3(clamp(dyn, 0.0, 255.0));
    // what the lights that absorb take here: of the block light a channel, and of the sky light by the most of them
    uvec3 abs8 = uvec3(0u);
    uint absSky = 0u;
#ifdef CL_DYNAMIC_LIGHTS
    abs8 = uvec3(clamp(cl_absAcc * 255.0, 0.0, 255.0));
    absSky = uint(max(cl_absAcc.r, max(cl_absAcc.g, cl_absAcc.b)) * 15.0 + 0.5);
#endif

    // Our format (compact): the top nibble is 15, or 8 plus the vertex's face.
    if (nib >= 8u) {
        uint red8 = (packed_light >> 0) & 0xFFu;
        uint green8 = (packed_light >> 8) & 0xFFu;
        uint skyLight4 = (packed_light >> 16) & 0xFu;
        uint blue8 = (packed_light >> 20) & 0xFFu;
        if (u_ColoredLightingEnabled < 0.5) {
            // Engine disabled: render stale colored-format vertices as plain vanilla light
            uint block8 = max(max(red8, green8), blue8);
            return _sample_lightmap_vanilla(lightMap, ivec2(int(block8), int(skyLight4) << 4));
        }

        uvec3 lit8 = max(uvec3(red8, green8, blue8), dyn8);
        lit8 -= min(lit8, abs8);
        skyLight4 -= min(skyLight4, absSky);
#ifdef CL_DYNAMIC_LIGHTS
        cl_baseLight = vec4(vec3(lit8), float(skyLight4));
#endif
        return _sample_colored_common(lightMap, skyLight4, lit8.r, lit8.g, lit8.b);
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
        uvec3 lit8 = max(uvec3(uint(red8 * 255.0), uint(green8 * 255.0), uint(blue8 * 255.0)), dyn8);
        lit8 -= min(lit8, abs8);
        skyLight4 -= min(skyLight4, absSky);
#ifdef CL_DYNAMIC_LIGHTS
        cl_baseLight = vec4(vec3(lit8), float(skyLight4));
#endif

        return _sample_colored_common(lightMap, skyLight4, lit8.r, lit8.g, lit8.b);
    }

    // Not our format: vanilla light, raised by the dynamic lights where they reach
    uint block8 = uint(clamp(uv.x, 0, 255));
    uint sky4 = uint(clamp(uv.y, 0, 255)) >> 4;
    uvec3 lit8 = max(uvec3(block8), dyn8);
    lit8 -= min(lit8, abs8);
    sky4 -= min(sky4, absSky);
#ifdef CL_DYNAMIC_LIGHTS
    cl_baseLight = vec4(vec3(lit8), float(sky4));
#endif
    if (max(max(dyn8.r, dyn8.g), dyn8.b) > 0u || max(max(abs8.r, abs8.g), abs8.b) > 0u) {
        return _sample_colored_common(lightMap, sky4, lit8.r, lit8.g, lit8.b);
    }
    return _sample_lightmap_vanilla(lightMap, uv);
}
// --- COLORFUL LIGHTING END ---