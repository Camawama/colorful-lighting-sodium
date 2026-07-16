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

    float moonWashoutFactor = mix(1.0, 0.0, u_NightVibrancy);
    float skyExposure = float(sl4) / 16.0;
    float effectiveSkyBrightness = sky.r * moonWashoutFactor * skyExposure;
    float washFactor = max(0.1, 1.0 - effectiveSkyBrightness);

    block = mix(vec3(length(block)), block, washFactor * 0.25 + 0.75);

    return vec4(sky + block * (max(0.1, 1.0 - sky.r) * 0.9 + 0.1), 1.0);
}

vec4 _sample_lightmap(sampler2D lightMap, ivec2 uv) {
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

        return _sample_colored_common(lightMap, skyLight4, red8, green8, blue8);
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
            uint(red8 * 255.0),
            uint(green8 * 255.0),
            uint(blue8 * 255.0)
        );
    }

    // Not our format, use vanilla lighting.
    return _sample_lightmap_vanilla(lightMap, uv);
}
// --- COLORFUL LIGHTING END ---