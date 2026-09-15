layout(binding = 1) uniform sampler2D tex_light;

uniform float colorfullighting_mod_injected_u_NightVibrancy;

vec4 minecraft_sample_vanilla_lightmap(sampler2D lightMap, ivec2 uv) {
    return texture(tex_light, clamp(uv / 256.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0)));
}

vec4 sampleLight(ivec2 uv) {
    int leastSignificantShort = uv.x;
    int mostSignificantShort = uv.y;
    int red8 = (leastSignificantShort >> 0) & 0xFF;
    int green8 = (leastSignificantShort >> 8) & 0xFF;
    int skyLight4 = (mostSignificantShort >> 0) & 0xF;
    int blue8 = (mostSignificantShort >> 4) & 0xFF;
    int alpha4 = (mostSignificantShort >> 12) & 0xF;
    if(alpha4 != 0xF) {
        return minecraft_sample_vanilla_lightmap(tex_light, uv);
    }

    vec3 sky = minecraft_sample_vanilla_lightmap(tex_light, ivec2(0, skyLight4 << 4)).xyz;
    vec3 block = vec3(
        minecraft_sample_vanilla_lightmap(tex_light, ivec2(red8, 0)).r,
        minecraft_sample_vanilla_lightmap(tex_light, ivec2(green8, 0)).r,
        minecraft_sample_vanilla_lightmap(tex_light, ivec2(blue8, 0)).r
    );
    float moonWashoutFactor = mix(1.0, 0.0, colorfullighting_mod_injected_u_NightVibrancy);
    float skyExposure = float(skyLight4) / 16.0;
    float effectiveSkyBrightness = sky.r * moonWashoutFactor * skyExposure;
    float washFactor = max(0.1, 1.0 - effectiveSkyBrightness);

    block = mix(vec3(length(block)), block, washFactor * 0.25 + 0.75);

    return vec4(sky + block * (max(0.1, 1.0 - sky.r) * 0.9 + 0.1), 1.0);
}
