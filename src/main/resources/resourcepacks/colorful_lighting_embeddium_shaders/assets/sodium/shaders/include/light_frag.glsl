// --- COLORFUL LIGHTING: the spot lights, drawn by the fragments (2.10.3) ---
// Put into Embeddium's terrain fragment shader after include/light.glsl (FragmentSpots). The vertices light a face with what
// reaches its corners, which is right for a lamp and wrong for a beam: a cone is narrower than a block close to its light, so
// it lit whole corners or none. Here each fragment takes the first cl_dynFragSpots lights at its own place and adds what the
// lightmap makes of them over the vertex's own light to the colour the vertices computed. The shader's v_Color reads the
// result (the define at the end), so its own code stays as it is.
#ifdef CL_DYNAMIC_LIGHTS
#define CL_FRAG_LIGHT
in vec3 cl_v_Wrel;
in vec3 cl_v_Normal;
in vec4 cl_v_Base;
in vec4 cl_v_Vert;

vec4 cl_color;

// _sample_colored_common for light that is not whole numbers: the sky nibble, the block light's three channels of 255
vec3 cl_lightmapAt(sampler2D lightMap, float sky4, vec3 rgb8) {
    float lo = 0.5 / 16.0, hi = 15.5 / 16.0;
    vec3 sky = texture(lightMap, vec2(lo, clamp(sky4 / 16.0, lo, hi))).xyz;
    vec3 at = clamp(rgb8 / 256.0, vec3(lo), vec3(hi));
    vec3 block = vec3(texture(lightMap, vec2(at.r, lo)).r, texture(lightMap, vec2(at.g, lo)).r, texture(lightMap, vec2(at.b, lo)).r);
    float moonWashoutFactor = mix(1.0, 0.0, colorfullighting_mod_injected_u_NightVibrancy);
    float effectiveSkyBrightness = sky.r * moonWashoutFactor * (sky4 / 16.0);
    float washFactor = max(0.1, 1.0 - effectiveSkyBrightness);
    block = mix(vec3(length(block)), block, washFactor * 0.25 + 0.75);
    return sky + block * (max(0.1, 1.0 - sky.r) * 0.9 + 0.1);
}

vec4 cl_litColor() {
#ifdef CL_FRAG_DEBUG
    // the vertices' colour stays read, so the uniforms behind it stay in the program (Embeddium binds them by name)
    vec4 keep = v_Color * 0.004;
    int band = (int(gl_FragCoord.x) / 400) % 4;
    if (band == 0) return keep + vec4(float(cl_dynFragSpots) * 0.5, float(cl_dynCount) * 0.25, cl_v_Base.a < 0.0 ? 1.0 : 0.0, 1.0);
    if (band == 1) return keep + vec4(cl_dynamicLightRange(cl_v_Wrel, cl_v_Normal, 0, cl_dynCount), 1.0);
    if (band == 2) return keep + vec4(fract(cl_v_Wrel), 1.0);
    return keep + vec4(cl_v_Normal * 0.5 + 0.5, 1.0);
#endif
    int spots = min(cl_dynFragSpots, cl_dynCount);
    if (spots <= 0 || cl_v_Base.a < 0.0) return v_Color;
    vec3 n = cl_v_Normal;
    float nn = dot(n, n);
    n = nn > 0.25 ? n * inversesqrt(nn) : vec3(0.0);
    vec3 spot = cl_dynamicLightRange(cl_v_Wrel, n, 0, spots) * 255.0;
    vec3 base = cl_v_Base.rgb;
    vec3 lit = max(base, spot);
    if (lit == base) return v_Color;
    vec3 more = cl_lightmapAt(u_LightTex, cl_v_Base.a, lit) - cl_lightmapAt(u_LightTex, cl_v_Base.a, base);
    return vec4(v_Color.rgb + cl_v_Vert.rgb * max(more, vec3(0.0)), v_Color.a);
}

#define v_Color cl_color
#endif
