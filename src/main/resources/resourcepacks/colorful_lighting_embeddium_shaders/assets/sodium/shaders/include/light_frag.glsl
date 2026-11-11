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

#ifdef CL_BLACKLIGHT
// Blacklight (2.11.0): ultraviolet makes what is saturated glow in its own colour, and what is white a pale blue.
// The ultraviolet at a fragment is the most of: the spot lights the fragments take (cl_uvAcc), the point lights the vertices
// took (cl_v_Uv) and the blacklight blocks' field: a window of cells round the camera, r how much reaches a cell and g whether
// the cell is lit at all, so r over g is the light of the open cells in front of a face alone (BlacklightField).
in float cl_v_Uv;
uniform int cl_uvField;
uniform sampler3D cl_uvFieldTex;
uniform vec3 cl_uvFieldOrigin;
uniform float cl_uvFieldSize;

float cl_ultraviolet(vec3 n) {
    float uv = max(cl_uvAcc, cl_v_Uv);
    if (cl_uvField > 0) {
        vec3 tc = (cl_v_Wrel + n * 0.5 - cl_uvFieldOrigin) / cl_uvFieldSize;
        if (all(greaterThan(tc, vec3(0.0))) && all(lessThan(tc, vec3(1.0)))) {
            vec2 f = textureLod(cl_uvFieldTex, tc, 0.0).rg;
            if (f.g > 0.02) uv = max(uv, f.r / f.g);
        }
    }
    return clamp(uv, 0.0, 1.0);
}

// what the glow adds to the light of a fragment (the shader multiplies the block's texture by it): for a saturated colour
// as much as shows the colour at its full brightness, whatever the face's shade; for a white a pale blue
vec3 cl_blacklightGlow(float uv, vec4 tex) {
    vec3 vert = cl_v_Vert.rgb;
    vec3 albedo = tex.rgb * vert;
    float mx = max(albedo.r, max(albedo.g, albedo.b));
    float mn = min(albedo.r, min(albedo.g, albedo.b));
    if (mx <= 1.0e-3) return vec3(0.0);
    // the face's shade and ambient occlusion out of the brightness: a colour is as bright as its texture and tint make it
    float shade = max(vert.r, max(vert.g, vert.b));
    float value = mx / max(shade, 1.0e-3);
    float sat = (mx - mn) / mx;
    float vivid = smoothstep(0.45, 0.85, sat) * smoothstep(0.3, 0.65, value);
    float white = smoothstep(0.72, 0.92, mn / max(shade, 1.0e-3));
    return (vert * (vivid * 1.25 / mx) + vec3(0.55, 0.7, 1.0) * (white * 0.4)) * (uv * cl_uvStrength);
}
#endif

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
    vec4 col = v_Color;
    vec3 n = cl_v_Normal;
    float nn = dot(n, n);
    n = nn > 0.25 ? n * inversesqrt(nn) : vec3(0.0);
#ifdef CL_BLACKLIGHT
    cl_uvAcc = 0.0;
#if defined(CL_FRAG_ALBEDO)
    // the texture's slope here, where every fragment passes: the texture itself is read only under ultraviolet
    vec2 cl_dx = dFdx(v_TexCoord), cl_dy = dFdy(v_TexCoord);
#endif
#endif
    int spots = min(cl_dynFragSpots, cl_dynCount);
    if (spots > 0 && cl_v_Base.a >= 0.0) {
        vec3 spot = cl_dynamicLightRange(cl_v_Wrel, n, 0, spots) * 255.0;
        vec3 base = cl_v_Base.rgb;
        vec3 lit = max(base, spot);
        if (lit != base) {
            vec3 more = cl_lightmapAt(u_LightTex, cl_v_Base.a, lit) - cl_lightmapAt(u_LightTex, cl_v_Base.a, base);
            col.rgb += cl_v_Vert.rgb * max(more, vec3(0.0));
        }
    }
#if defined(CL_BLACKLIGHT) && defined(CL_FRAG_ALBEDO)
    if (cl_uvStrength > 0.0) {
        float uv = cl_ultraviolet(n);
        if (uv > 0.002) col.rgb += cl_blacklightGlow(uv, textureGrad(u_BlockTex, v_TexCoord, cl_dx, cl_dy));
    }
#endif
    return col;
}

#define v_Color cl_color
#endif
