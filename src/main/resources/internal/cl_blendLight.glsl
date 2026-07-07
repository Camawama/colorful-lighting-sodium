varying vec3 colorful_lighting_color;

// long method name to try to avoid name conflicts
vec4 colorful_lighting_blendLight(sampler2D lm, vec2 readCoord) {
    vec2 sampCoord = vec2(1/16.0, readCoord.y); // lightmap is 16x16, uses LINEAR REPEAT; must be 0.5 pixels in to get the correct value
    vec4 sky = texture2D(lm, sampCoord);

    vec3 sampleColor = clamp(colorful_lighting_color, vec3(0.5/16.0), vec3(15.5/16.0));
    vec3 block = vec3(
        texture2D(lm, vec2(sampleColor.r, 0)).r,
        texture2D(lm, vec2(sampleColor.g, 0)).r,
        texture2D(lm, vec2(sampleColor.b, 0)).r
    );

    float wash = max(0.1, 1.0 - max(sky.r, max(sky.g, sky.b)));

    return vec4(sky.rgb + block * wash, 1.0);
}
