// --- Colorful Lighting: blacklight on entities (2.11.1), the vertices' part ---
// How much ultraviolet reaches a vertex, 0..1: the blacklight blocks' field round the camera and the ultraviolet ones of the
// dynamic lights, with what is in their way (the uniforms of the terrain shaders' light.glsl, uploaded once a frame). It
// rides to the fragments in the lightmap colour's alpha, as 1 plus the amount.
#define CL_DYN_MAX 64
uniform mat3 cl_dynInvView;
uniform int cl_dynCount;
uniform vec4 cl_dynPos[CL_DYN_MAX];
uniform vec4 cl_dynColor[CL_DYN_MAX];
uniform vec4 cl_dynDir[CL_DYN_MAX];
uniform int cl_dynSlabs;
uniform vec4 cl_dynSlab[CL_DYN_MAX];
uniform sampler3D cl_dynSlabTex0;
uniform sampler3D cl_dynSlabTex1;
uniform sampler3D cl_dynSlabTex2;
uniform vec3 cl_dynSlabSize;
uniform vec3 cl_dynSlabCount;
uniform ivec2 cl_dynUvBits;
uniform float cl_uvStrength;
uniform int cl_uvField;
uniform sampler3D cl_uvFieldTex;
uniform vec3 cl_uvFieldOrigin;
uniform float cl_uvFieldSize;

float cl_entityUvVisibility(vec4 s, vec3 at, float remaining) {
    int cls = int(s.w / 1000.0);
    float slot = s.w - float(cls) * 1000.0;
    float size = cls == 0 ? cl_dynSlabSize.x : cls == 1 ? cl_dynSlabSize.y : cl_dynSlabSize.z;
    float count = cls == 0 ? cl_dynSlabCount.x : cls == 1 ? cl_dynSlabCount.y : cl_dynSlabCount.z;
    vec3 local = clamp(at - s.xyz, vec3(0.5), vec3(size - 0.5));
    vec3 tc = vec3(local.xy / size, (slot * size + local.z) / (size * count));
    vec4 v = cls == 0 ? textureLod(cl_dynSlabTex0, tc, 0.0) : cls == 1 ? textureLod(cl_dynSlabTex1, tc, 0.0) : textureLod(cl_dynSlabTex2, tc, 0.0);
    float detour = v.a * (size - 1.0) * 0.5;
    return max(v.r, max(v.g, v.b)) * clamp(1.0 - detour / max(remaining, 1.0e-3), 0.0, 1.0);
}

// rel: the vertex relative to the camera along the world's axes (2.12.0: the game's own inverse view rotation gives it for
// entities, so a view another mod has turned, a gravity mod's, is followed; a particle's position is that already)
float cl_entityUv(vec3 rel) {
    if (cl_uvStrength <= 0.0) return 0.0;
    float uv = 0.0;
    if (cl_uvField > 0) {
        vec3 tc = (rel - cl_uvFieldOrigin) / cl_uvFieldSize;
        if (all(greaterThan(tc, vec3(0.0))) && all(lessThan(tc, vec3(1.0)))) {
            vec2 f = textureLod(cl_uvFieldTex, tc, 0.0).rg;
            if (f.g > 0.02) uv = f.r / f.g;
        }
    }
    if (cl_dynUvBits.x != 0 || cl_dynUvBits.y != 0) {
        for (int i = 0; i < cl_dynCount; i++) {
            if ((((i < 32 ? cl_dynUvBits.x : cl_dynUvBits.y) >> (i & 31)) & 1) == 0) continue;
            vec4 p = cl_dynPos[i];
            vec3 d = rel - p.xyz;
            float range = cl_dynColor[i].w;
            float dist2 = dot(d, d);
            if (dist2 > range * range) continue;
            float falloff = floor(p.w / 32.0);
            float level = p.w - falloff * 32.0;
            vec4 dir = cl_dynDir[i];
            float l, remaining;
            if (dir.w < 1.0) {
                float dist = sqrt(dist2);
                float cosA = dist > 1.0e-4 ? dot(d, dir.xyz) / dist : 1.0;
                float innerCos = 1.0 - (1.0 - dir.w) * 0.36;
                float cone = cosA <= dir.w ? 0.0 : innerCos <= dir.w ? 1.0 : clamp((cosA - dir.w) / (innerCos - dir.w), 0.0, 1.0);
                if (cone <= 0.0) continue;
                remaining = range - dist;
                l = level * (remaining / range) * cone;
            } else {
                float stretch = dir.x > 0.0 ? dir.x : 1.0;
                remaining = level * stretch - (falloff > 0.5 ? sqrt(dist2) : abs(d.x) + abs(d.y) + abs(d.z));
                l = remaining / stretch;
            }
            if (l <= 0.0) continue;
            float a = min(l, 15.0) / 15.0;
            vec4 s = cl_dynSlab[i];
            if (cl_dynSlabs > 0 && s.w >= 0.0) a *= cl_entityUvVisibility(s, rel, remaining);
            uv = max(uv, a);
        }
    }
    return clamp(uv * cl_uvStrength, 0.0, 1.0);
}

// What ultraviolet adds to a tint alone (2.12.0, particles: their texture is mostly a grey shape and the tint is the colour):
// the tint's own colour by how saturated it is, a pale blue for a white.
vec3 cl_tintGlow(vec3 tint) {
    float mx = max(tint.r, max(tint.g, tint.b));
    float mn = min(tint.r, min(tint.g, tint.b));
    if (mx <= 1.0e-3) return vec3(0.0);
    float vivid = smoothstep(0.45, 0.85, (mx - mn) / mx) * smoothstep(0.25, 0.6, mx);
    float white = smoothstep(0.72, 0.92, mn);
    return tint * (vivid * 1.25 / mx) + tint * vec3(0.55, 0.7, 1.0) * (white * 0.4);
}
// --- end of Colorful Lighting's part ---
