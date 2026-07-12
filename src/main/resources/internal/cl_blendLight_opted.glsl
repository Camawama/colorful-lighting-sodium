/* additional helper methods are added if the shader dev has made changes to support colored lighting */


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
    vec4 sky = cl_sampleSky(lm, lmcoord);
    vec3 block = cl_sampleColor(lm, tintColor);

    float wash = max(0.1, 1.0 - max(sky.r, max(sky.g, sky.b)));
    return vec4(sky.rgb + block * wash, 1.0);
}
