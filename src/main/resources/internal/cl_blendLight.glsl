varying vec3 colorful_lighting_color;

uniform float colorfullighting_mod_injected_u_NightVibrancy;

// long method name to try to avoid name conflicts
vec4 colorful_lighting_blendLight(sampler2D lm, vec2 readCoord) {
    vec2 sampCoord = vec2(1/16.0, readCoord.y); // lightmap is 16x16, uses LINEAR REPEAT; must be 0.5 pixels in to get the correct value
    vec3 sky = texture2D(lm, sampCoord).xyz;

    vec3 sampleColor = clamp(colorful_lighting_color, vec3(0.5/16.0), vec3(15.5/16.0));
    vec3 block = vec3(
        texture2D(lm, vec2(sampleColor.r, 1/16.0)).r,
        texture2D(lm, vec2(sampleColor.g, 1/16.0)).r,
        texture2D(lm, vec2(sampleColor.b, 1/16.0)).r
    );

    float moonWashoutFactor = mix(1.0, 0.0, colorfullighting_mod_injected_u_NightVibrancy);
    float skyExposure = lmcoord.y;
    float effectiveSkyBrightness = sky.r * moonWashoutFactor * skyExposure;
    float washFactor = max(0.1, 1.0 - effectiveSkyBrightness);

    block = mix(vec3(length(block)), block, washFactor * 0.25 + 0.75);

    return vec4(sky + block * (max(0.1, 1.0 - sky.r) * 0.9 + 0.1), 1.0);
}
