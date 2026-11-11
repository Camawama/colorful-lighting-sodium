// --- Colorful Lighting: blacklight on entities (2.11.1), the fragments' part ---
// The light's alpha is 1 plus the ultraviolet that reaches the fragment (the vertex shader's cl_entityUv). Ultraviolet makes
// what is saturated glow in its own colour, and what is white a pale blue, as the terrain shaders do for blocks.
vec4 cl_entityLit(vec4 albedo, vec4 light) {
    float uv = clamp(light.a - 1.0, 0.0, 1.0);
    vec3 lit = albedo.rgb * light.rgb;
    if (uv > 0.0) {
        float mx = max(albedo.r, max(albedo.g, albedo.b));
        float mn = min(albedo.r, min(albedo.g, albedo.b));
        if (mx > 1.0e-3) {
            float vivid = smoothstep(0.45, 0.85, (mx - mn) / mx) * smoothstep(0.25, 0.6, mx);
            float white = smoothstep(0.72, 0.92, mn);
            lit += (albedo.rgb * (vivid * 1.25 / mx) + albedo.rgb * vec3(0.55, 0.7, 1.0) * (white * 0.4)) * uv;
        }
    }
    return vec4(lit, albedo.a * min(light.a, 1.0));
}

// The shaders whose vertices fold the light into the colour (2.12.0: armor, flat items, decals): the vertex shader sends the
// colour before its light and 2 plus the ultraviolet in the varying "normal", which their fragments otherwise leave alone. A
// vertex shader that isn't Colorful Lighting's leaves a w there that is never 2 to 3, and nothing glows.
vec3 cl_entityGlow(vec3 tex, vec4 carrier) {
    float uv = carrier.a - 2.0;
    if (uv <= 0.0 || uv > 1.0) return vec3(0.0);
    vec3 albedo = tex * clamp(carrier.rgb, 0.0, 1.0);
    float mx = max(albedo.r, max(albedo.g, albedo.b));
    float mn = min(albedo.r, min(albedo.g, albedo.b));
    if (mx <= 1.0e-3) return vec3(0.0);
    float vivid = smoothstep(0.45, 0.85, (mx - mn) / mx) * smoothstep(0.25, 0.6, mx);
    float white = smoothstep(0.72, 0.92, mn);
    return (albedo * (vivid * 1.25 / mx) + albedo * vec3(0.55, 0.7, 1.0) * (white * 0.4)) * uv;
}
// --- end of Colorful Lighting's part ---
