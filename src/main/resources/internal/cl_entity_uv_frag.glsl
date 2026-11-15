// --- Colorful Lighting: blacklight on entities (2.11.1), the fragments' part ---
// The light's alpha is 1 plus the ultraviolet that reaches the fragment (the vertex shader's cl_entityUv). Ultraviolet makes
// what is saturated glow in its own colour, and what is white a pale blue, as the terrain shaders do for blocks.
// Fluorescence (2.14.0): what glows under ultraviolet whatever its own colour (a scorpion). Which texture does is told a
// draw at a time: cl_fluor holds the glow's colour and its strength (0 for everything else, which then pays one
// comparison where ultraviolet reaches), cl_fluorMask the part of the texture that glows: its brightness from x to y, and
// a hue z (0..1, below 0 for any) within w of it.
uniform vec4 cl_fluor;
uniform vec4 cl_fluorMask;

vec3 cl_fluorGlow(vec3 c) {
    float mx = max(c.r, max(c.g, c.b));
    float mn = min(c.r, min(c.g, c.b));
    float luma = dot(c, vec3(0.299, 0.587, 0.114));
    float part = 1.0;
    if (cl_fluorMask.x > 0.0) part *= smoothstep(cl_fluorMask.x - 0.03, cl_fluorMask.x + 0.03, luma);
    if (cl_fluorMask.y < 1.0) part *= 1.0 - smoothstep(cl_fluorMask.y - 0.03, cl_fluorMask.y + 0.03, luma);
    if (cl_fluorMask.z >= 0.0 && part > 0.0) {
        float d = mx - mn;
        float hue = 0.0;
        if (d > 1.0e-4) {
            hue = mx == c.r ? (c.g - c.b) / d : mx == c.g ? 2.0 + (c.b - c.r) / d : 4.0 + (c.r - c.g) / d;
            hue = fract(hue / 6.0);
        }
        float away = abs(fract(hue - cl_fluorMask.z + 0.5) - 0.5);
        part *= (1.0 - smoothstep(cl_fluorMask.w, cl_fluorMask.w + 0.03, away)) * smoothstep(0.06, 0.18, mx > 1.0e-3 ? d / mx : 0.0);
    }
    // the texture's own shading stays readable in the glow; a dark body still glows
    return cl_fluor.rgb * (cl_fluor.a * part * mix(0.5, 1.0, luma));
}

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
        if (cl_fluor.a > 0.0) lit += cl_fluorGlow(albedo.rgb) * uv;
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
