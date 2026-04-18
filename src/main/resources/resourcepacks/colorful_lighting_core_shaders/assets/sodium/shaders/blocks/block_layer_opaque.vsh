#version 330 core

#import <sodium:include/fog.glsl>
#import <sodium:include/chunk_vertex.glsl>
#import <sodium:include/chunk_matrices.glsl>
#import <sodium:include/chunk_material.glsl>

out vec4 v_Color;
out vec2 v_TexCoord;

out float v_MaterialMipBias;
#ifdef USE_FRAGMENT_DISCARD
out float v_MaterialAlphaCutoff;
#endif

#ifdef USE_FOG
out float v_FragDistance;
#endif

uniform int u_FogShape;
uniform vec3 u_RegionOffset;
uniform float u_NightVibrancy;

uniform sampler2D u_LightTex; // The light map texture sampler

// --- COLORFUL LIGHTING START ---
vec4 _sample_lightmap_vanilla(sampler2D lightMap, ivec2 uv) {
    return texture(lightMap, clamp(uv / 256.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0)));
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

        const float divideBy255 = 0.00392156862;
        vec3 blockLightColor = vec3(float(red8)*divideBy255, float(green8)*divideBy255, float(blue8)*divideBy255);
        vec3 sky = _sample_lightmap_vanilla(lightMap, ivec2(0, int(skyLight4) << 4)).xyz;
        vec3 block = pow(blockLightColor, vec3(1.3));

        float moonWashoutFactor = mix(3.0, 0.0, u_NightVibrancy);
        float skyExposure = float(skyLight4) / 15.0;
        float effectiveSkyBrightness = sky.r * moonWashoutFactor * skyExposure;

        return vec4(sky + block * max(0.3, 1.0 - effectiveSkyBrightness), 1.0);
    }

    // Check if it's our compressed 16-bit non-compact format (bit 0 == 1)
    if ((packed_light & 0x1u) == 0x1u) {
        // Unpack from the 16-bit integer (actually the lower 16 bits of packed_light)
        uint skyLight4 = (packed_light >> 1) & 0xFu;
        uint red4 = (packed_light >> 5) & 0xFu;
        uint green4 = (packed_light >> 9) & 0xFu;
        uint blue3 = (packed_light >> 13) & 0x7u;

        // Expand to 8-bit equivalent
        float red8 = float(red4) / 15.0;
        float green8 = float(green4) / 15.0;
        float blue8 = float(blue3) / 7.0;

        vec3 blockLightColor = vec3(red8, green8, blue8);
        vec3 sky = _sample_lightmap_vanilla(lightMap, ivec2(0, int(skyLight4) << 4)).xyz;
        vec3 block = pow(blockLightColor, vec3(1.3));

        float moonWashoutFactor = mix(3.0, 0.0, u_NightVibrancy);
        float skyExposure = float(skyLight4) / 15.0;
        float effectiveSkyBrightness = sky.r * moonWashoutFactor * skyExposure;

        return vec4(sky + block * max(0.3, 1.0 - effectiveSkyBrightness), 1.0);
    }

    // Not our format, use vanilla lighting.
    return _sample_lightmap_vanilla(lightMap, uv);
}
// --- COLORFUL LIGHTING END ---

uvec3 _get_relative_chunk_coord(uint pos) {
    return uvec3(pos) >> uvec3(5u, 0u, 2u) & uvec3(7u, 3u, 7u);
}

vec3 _get_draw_translation(uint pos) {
    return _get_relative_chunk_coord(pos) * vec3(16.0);
}

void main() {
    _vert_init();

    vec3 translation = u_RegionOffset + _get_draw_translation(_draw_id);
    vec3 position = _vert_position + translation;

#ifdef USE_FOG
    v_FragDistance = getFragDistance(u_FogShape, position);
#endif

    gl_Position = u_ProjectionMatrix * u_ModelViewMatrix * vec4(position, 1.0);

    v_Color = _vert_color * _sample_lightmap(u_LightTex, _vert_tex_light_coord);
    v_TexCoord = _vert_tex_diffuse_coord;

    v_MaterialMipBias = _material_mip_bias(_material_params);
#ifdef USE_FRAGMENT_DISCARD
    v_MaterialAlphaCutoff = _material_alpha_cutoff(_material_params);
#endif
}