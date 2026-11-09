#include "flywheel:internal/light_lut.glsl"
#include "colorful_lighting:colored_light_types.glsl"

// SSBOs need GLSL 430; flywheel compiles with the highest GLSL the context can offer, so on
// older contexts (GL 4.1 Macs and the like) the same section data is read through a buffer
// texture instead — same buffer object, same indexing, texelFetch instead of an array access.
// FlywheelCompat.isTextureFallback makes the identical decision on the Java side (upload/bind),
// and GlProgramMixin points the sampler at its texture unit.
#if __VERSION__ >= 430
layout(std430, binding = 8) restrict readonly buffer ColoredLightSections {
    int coloredLightSections[];
};
#else
uniform isamplerBuffer _cl_coloredLightSections;
#endif

vec4 minecraft_sample_vanilla_lightmap(sampler2D lightMap, ivec2 uv) {
    return texture(lightMap, clamp(uv / 256.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0)));
}

int ivec2ToInt(ivec2 data) {
    return data.y << 16 | (data.x & 0xFFFF);
}

ColoredLightIntegerData unpackColoredLightData(int packedData) {
    return ColoredLightIntegerData(
        ivec3(
            packedData, // red
            (packedData >> 8), // green
            (packedData >> 20) // blue
        ) & ivec3(0xFF),
        (packedData >> 16) & 0xF, // sky 4
        (packedData >> 28) & 0xF // alpha 4
    );
}

bool isPackedDataColored(int packedData) {
    return ((packedData >> 28) & 0xF) == 0xF;
}

// An instance's light as vanilla's (block, sky) / 16, the way flw_vertexLight wants it. A visual that relights its
// instances from LevelRenderer.getLightColor (every block entity visual) gets Colorful Lighting's packed light there,
// not vanilla's: read as vanilla's it is garbage (the sky half comes out negative and is dropped). The world's shaders
// replace that light anyway, but an embedded visual keeps it: Valkyrien Skies draws a ship's block entities embedded,
// and Create's shafts and cogs on a ship drew black by day.
vec2 cl_vanillaVertexLight(ivec2 instanceLight) {
    int packedData = ivec2ToInt(instanceLight);
    if (!isPackedDataColored(packedData)) return vec2(instanceLight) / 256.0;
    ColoredLightIntegerData data = unpackColoredLightData(packedData);
    float block = float(max(data.r8g8b8.r, max(data.r8g8b8.g, data.r8g8b8.b))) / 17.0;
    return vec2(block, float(data.skyLight4)) / 16.0;
}

ColoredLightFloatData coloredLightData_integerToFloat(ColoredLightIntegerData data) {
    return ColoredLightFloatData(
        data.r8g8b8 / 255.0,
        data.skyLight4 / 15.0,
        data.alpha4 / 15.0
    );
}

// The moon's night vibrancy (the star brightness times the phase's vibrancy), set on every program bind by
// GlProgramMixin; 0 when nothing sets it.
uniform float _cl_nightVibrancy;

// --- dynamic lights (2.8.0; the slabs since 2.10.0): the external providers' lights, camera-relative ---
// cl_dynPos: xyz the light relative to the camera, w its level (0..31, drawn as at most 15) plus 32 when it falls off by straight distance (a
// round light) rather than by Manhattan distance (vanilla's diamond). cl_dynColor: rgb 0..1, w the reach in blocks.
// cl_dynDir: xyz a spot light's axis, w the cosine of half its full angle (1.0 for a point light). The inner, fully lit cone
// is 0.6 of the outer angle: its cosine is approximated from the outer's.
#define CL_DYN_MAX 64
uniform int cl_dynCount;
uniform vec4 cl_dynPos[CL_DYN_MAX];
uniform vec4 cl_dynColor[CL_DYN_MAX];
uniform vec4 cl_dynDir[CL_DYN_MAX];

// The slabs (2.10.0): per light a cube of cells round it holding where its light may go, in three 3D textures by reach (each
// a stack of slabs along z): rgb the tint picked up through filter blocks on the way, a how much longer the way through the
// blocks is than the straight way (the detour, as a share of the slab's half size; 1 where the light never gets). The light
// itself is the analytic falloff below, at the exact point; the slab only scales it: 1 in the open, less by the detour's share
// of the reach left at the point, 0 behind a wall. Sampled half a block out along the face's normal (trilinear), as vanilla's
// smooth lighting reads the blocks in front of a face. cl_dynSlab: xyz the slab's corner relative to the camera, w the class
// times 1000 plus the slot, or -1 for a light without one (nothing in its way then).
uniform int cl_dynSlabs;
uniform vec4 cl_dynSlab[CL_DYN_MAX];
uniform sampler3D cl_dynSlabTex0;
uniform sampler3D cl_dynSlabTex1;
uniform sampler3D cl_dynSlabTex2;
uniform vec3 cl_dynSlabSize;
uniform vec3 cl_dynSlabCount;

vec3 cl_dynVisibility(vec4 s, vec3 at, float remaining) {
    int cls = int(s.w / 1000.0);
    float slot = s.w - float(cls) * 1000.0;
    float size = cls == 0 ? cl_dynSlabSize.x : cls == 1 ? cl_dynSlabSize.y : cl_dynSlabSize.z;
    float count = cls == 0 ? cl_dynSlabCount.x : cls == 1 ? cl_dynSlabCount.y : cl_dynSlabCount.z;
    vec3 local = clamp(at - s.xyz, vec3(0.5), vec3(size - 0.5));
    vec3 tc = vec3(local.xy / size, (slot * size + local.z) / (size * count));
    vec4 v = cls == 0 ? texture(cl_dynSlabTex0, tc) : cls == 1 ? texture(cl_dynSlabTex1, tc) : texture(cl_dynSlabTex2, tc);
    float detour = v.a * (size - 1.0) * 0.5;
    return v.rgb * clamp(1.0 - detour / max(remaining, 1.0e-3), 0.0, 1.0);
}

vec3 cl_dynamicLight(vec3 wrel, vec3 normal) {
    vec3 acc = vec3(0.0);
    vec3 at = wrel + normal * 0.5;
    for (int i = 0; i < cl_dynCount; i++) {
        vec4 p = cl_dynPos[i];
        vec3 d = wrel - p.xyz;
        vec4 c = cl_dynColor[i];
        float range = c.w;
        float dist2 = dot(d, d);
        if (dist2 > range * range) continue;
        float falloff = floor(p.w / 32.0);
        float level = p.w - falloff * 32.0;
        vec4 dir = cl_dynDir[i];
        // l: the level left here; remaining: the blocks of reach left here (a detour that long takes the rest)
        float l, remaining;
        if (dir.w < 1.0) {
            float dist = sqrt(dist2);
            float cosA = dist > 1.0e-4 ? dot(d, dir.xyz) / dist : 1.0;
            float innerCos = 1.0 - (1.0 - dir.w) * 0.36;
            float cone = cosA <= dir.w ? 0.0 : innerCos <= dir.w ? 1.0 : clamp((cosA - dir.w) / (innerCos - dir.w), 0.0, 1.0);
            if (cone <= 0.0) continue;
            remaining = range - dist;
            l = level * (remaining / range) * cone;
        } else if (falloff > 0.5) {
            remaining = level - sqrt(dist2);
            l = remaining;
        } else {
            remaining = level - (abs(d.x) + abs(d.y) + abs(d.z));
            l = remaining;
        }
        if (l <= 0.0) continue;
        vec3 light = c.rgb * (min(l, 15.0) / 15.0);
        vec4 s = cl_dynSlab[i];
        if (cl_dynSlabs > 0 && s.w >= 0.0) light *= cl_dynVisibility(s, at, remaining);
        acc = max(acc, light);
    }
    return acc;
}

// The in-world composite of sky and colored block light: the same as cl_blendLight (the chunk shaders), the vanilla core
// shaders' sample_lightmap_colored and the Distant Horizons LODs. The block light is washed toward grey under bright sky
// and under moonlight, then scaled by max(0.1, 1 - sky) * 0.9 + 0.1. Before 2.7.6 this read sky + block * max(0.1, 1 - sky),
// an older composite: a Flywheel-drawn chest by a torch drew about 5% darker by day than the same chest from vanilla's
// renderer (pack harness chest_flw, a double chest and a torch, 0.941 and 0.956 of vanilla's brightness).
vec4 mixColoredLightWithLightMap(sampler2D lightMap, ColoredLightFloatData data) {
    vec2 skyUv = clamp(vec2(0.5 / 16.0, (data.skyLight * 15.0 + 0.5) / 16.0), vec2(0.5 / 16.0), vec2(15.5 / 16.0));
    vec3 sky = texture(lightMap, skyUv).xyz;
    vec3 block = vec3(
        texture(lightMap, clamp(vec2((data.lightColor.r * 15.0 + 0.5) / 16.0, 0.5 / 16.0), vec2(0.5 / 16.0), vec2(15.5 / 16.0))).r,
        texture(lightMap, clamp(vec2((data.lightColor.g * 15.0 + 0.5) / 16.0, 0.5 / 16.0), vec2(0.5 / 16.0), vec2(15.5 / 16.0))).r,
        texture(lightMap, clamp(vec2((data.lightColor.b * 15.0 + 0.5) / 16.0, 0.5 / 16.0), vec2(0.5 / 16.0), vec2(15.5 / 16.0))).r
    );
    float moonWashoutFactor = 1.0 - _cl_nightVibrancy;
    float skyExposure = data.skyLight * 15.0 / 16.0;
    float effectiveSkyBrightness = sky.r * moonWashoutFactor * skyExposure;
    float washFactor = max(0.1, 1.0 - effectiveSkyBrightness);
    block = mix(vec3(length(block)), block, washFactor * 0.25 + 0.75);
    return vec4(sky + block * (max(0.1, 1.0 - sky.r) * 0.9 + 0.1), 1.0);
}

// get packed colored light from the section buffer
int getColoredLightFromBuffer(ivec3 blockPos) {
    uint lightSectionIndex;
    if (_flw_chunkCoordToSectionIndex(blockPos >> 4, lightSectionIndex)) {
        return -1;
    }
    ivec3 blockPosRelative = ivec3((blockPos & 0xF) + 1);
    int index = (blockPosRelative.x + blockPosRelative.z * 18 + blockPosRelative.y * 18 * 18);
    #if __VERSION__ >= 430
    return coloredLightSections[lightSectionIndex * 18 * 18 * 18 + index];
    #else
    return texelFetch(_cl_coloredLightSections, int(lightSectionIndex) * 18 * 18 * 18 + index).r;
    #endif
}

// sample ColoredLightFloatData at a given blockPos
ColoredLightFloatData sampleLightColor(ivec3 blockPos, ivec2 instanceLight) {
    int fetchedLight = getColoredLightFromBuffer(blockPos);
    if(fetchedLight == -1) { // no light in buffer for this section
        return coloredLightData_integerToFloat(unpackColoredLightData(ivec2ToInt(instanceLight)));
    }
    else {
        ColoredLightFloatData data = coloredLightData_integerToFloat(unpackColoredLightData(fetchedLight));
        vec2 light;
        flw_lightFetch(blockPos, light);
        data.skyLight = light.y;
        return data;
    }
}

// linear ColoredLightFloatData interpolation
ColoredLightFloatData mixLight(ColoredLightFloatData a, ColoredLightFloatData b, float t) {
    return ColoredLightFloatData(
        mix(a.lightColor, b.lightColor, t),
        mix(a.skyLight, b.skyLight, t),
        a.alpha
    );
}

// trilinear ColoredLightFloatData interpolation
/*ColoredLightFloatData sampleTrilinearLightColor(vec3 pos, ivec2 instanceLight) {
    ivec3 corner = ivec3(round(pos))-ivec3(1);
    ColoredLightFloatData c000 = sampleLightColor(ivec3(corner.x + 0, corner.y + 0, corner.z + 0), instanceLight);
    ColoredLightFloatData c100 = sampleLightColor(ivec3(corner.x + 1, corner.y + 0, corner.z + 0), instanceLight);
    ColoredLightFloatData c101 = sampleLightColor(ivec3(corner.x + 1, corner.y + 0, corner.z + 1), instanceLight);
    ColoredLightFloatData c001 = sampleLightColor(ivec3(corner.x + 0, corner.y + 0, corner.z + 1), instanceLight);
    ColoredLightFloatData c010 = sampleLightColor(ivec3(corner.x + 0, corner.y + 1, corner.z + 0), instanceLight);
    ColoredLightFloatData c110 = sampleLightColor(ivec3(corner.x + 1, corner.y + 1, corner.z + 0), instanceLight);
    ColoredLightFloatData c111 = sampleLightColor(ivec3(corner.x + 1, corner.y + 1, corner.z + 1), instanceLight);
    ColoredLightFloatData c011 = sampleLightColor(ivec3(corner.x + 0, corner.y + 1, corner.z + 1), instanceLight);

    vec3 xyz = pos - (corner + vec3(0.5));
    xyz = mix(xyz, pos - (floor(pos) + vec3(0.5)), vec3(1.0));

    ColoredLightFloatData c00 = mixLight(c000, c100, xyz.x);
    ColoredLightFloatData c10 = mixLight(c010, c110, xyz.x);
    ColoredLightFloatData c01 = mixLight(c001, c101, xyz.x);
    ColoredLightFloatData c11 = mixLight(c011, c111, xyz.x);

    ColoredLightFloatData c0 = mixLight(c00, c10, xyz.y);
    ColoredLightFloatData c1 = mixLight(c01, c11, xyz.y);

    return mixLight(c0, c1, xyz.z);
}*/

ColoredLightFloatData vertexLightColor(ivec2 instanceLight, vec3 vertexPos) {
    ivec3 blockPos = ivec3(floor(vertexPos));
    return sampleLightColor(blockPos, instanceLight);
    /*if (getColoredLightFromBuffer(blockPos) == -1) {
        // light provided by instance
        return sampleLightColor(blockPos, instanceLight);
    }
    else {
        // light provided by uniform buffer object (so we can sample multiple positions and do trilinear interpolation)
        return sampleTrilinearLightColor(vertexPos, instanceLight);
    }*/
}