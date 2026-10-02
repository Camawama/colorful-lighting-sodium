/* additional helper methods are added if the shader dev has made changes to support colored lighting */

uniform float colorfullighting_mod_injected_u_NightVibrancy;

// feel free to copy, edit, and use these functions in your own shaders, if the provided methods aren't usable for your purposes
vec4 cl_sampleSky(sampler2D lm, vec2 lmcoord) {
    // lightmap is 16x16, uses LINEAR REPEAT; must be 0.5 pixels in to get the correct value
    vec2 sampCoord = vec2(0.5 / 16.0, lmcoord.y);
    vec4 sky = texture2D(lm, sampCoord);

    return sky;
}

vec3 cl_sampleColor(sampler2D lm, vec3 tintColor) {
    vec3 sampleColor = clamp(tintColor, vec3(0.5 / 16.0), vec3(15.5 / 16.0));
    // the "vanilla" implementation of colorful lighting samples the block lighting per-channel
    return vec3(
        texture2D(lm, vec2(sampleColor.r, 1/16.0)).r,
        texture2D(lm, vec2(sampleColor.g, 1/16.0)).r,
        texture2D(lm, vec2(sampleColor.b, 1/16.0)).r
    );
}

vec4 cl_blendLight(sampler2D lm, vec2 lmcoord, vec3 tintColor) {
    vec3 sky = cl_sampleSky(lm, lmcoord).xyz;
    vec3 block = cl_sampleColor(lm, tintColor);

    float moonWashoutFactor = mix(1.0, 0.0, colorfullighting_mod_injected_u_NightVibrancy);
    float skyExposure = lmcoord.y;
    float effectiveSkyBrightness = sky.r * moonWashoutFactor * skyExposure;
    float washFactor = max(0.1, 1.0 - effectiveSkyBrightness);

    block = mix(vec3(length(block)), block, washFactor * 0.25 + 0.75);

    return vec4(sky + block * (max(0.1, 1.0 - sky.r) * 0.9 + 0.1), 1.0);
}
