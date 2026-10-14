#version 150 core

// Colorful Lighting replacement for Distant Horizons' terrain fragment shader (DH 3.1.2 & 3.2.0).
// Recolours the block-light contribution of each LOD fragment using the remembered colour volumes,
// then applies DH's dithered near fade so the hole around the vanilla render distance matches.
// Under DH 3.2 with textured LODs enabled it also replicates DH's block-texture tile modulation.

in vec3 vertexWorldPos;   // camera-relative
in float vertexYPos;
in vec4 vertexAlbedo;
in vec2 vertexLightCoord; // x = sky, y = block
in vec3 vBlockPos;        // DH 3.2 texture UVs
flat in uint vNormalIndex;
flat in uint vTextureTileId;

out vec4 fragColor;

uniform sampler2D uLightMap;      // vanilla lightmap, bound by DH on unit 0
uniform sampler3D uClVolumeNear;  // remembered colour, 4 blocks/texel
uniform sampler3D uClVolumeFar;   // remembered colour, 16 blocks/texel
uniform sampler3D uClVolumeUltra; // remembered colour, 64 blocks/texel
uniform vec3 uClCameraPos;
uniform vec3 uClNearMin;          // world-space min corner of the near window
uniform float uClNearInvSize;     // 1 / window size in blocks
uniform vec3 uClFarMin;
uniform float uClFarInvSize;
uniform vec3 uClUltraMin;
uniform float uClUltraInvSize;
uniform int uClDebugMode;         // 0 off, 1 volume debug view
uniform int uClHasNormals;        // DH 3.2+: irisData.y carries every quad's face index
uniform vec3 uClHalfTexel;        // half a texel of the near/far/ultra volume, in blocks
uniform float uClNightVibrancy;   // same value the in-world shaders get (night factor x moon phase)

uniform float uClipDistance;     // near fade start, from DH's render params

// DH 3.2 textured LODs. uClTexturedLods gates the whole path: under 3.1.2 the irisData
// attribute array is disabled, so vTextureTileId reads the integer-attribute default (0,0,0,1)
// = tile 256, and sampling would be garbage.
uniform sampler2D uClBlockAtlas; // DH's block texture atlas, bound by DH on unit 1
uniform int uClTexturedLods;

/**
 * Texture coordinate for this fragment, matching how DH bakes block textures (DH 3.2's frag).
 * Normal index order is EDhDirection: 0 down, 1 up, 2 north, 3 south, 4 west, 5 east.
 */
vec2 blockFaceUv()
{
    vec3 pos = fract(vBlockPos);
    switch (vNormalIndex)
    {
        case 0u: return vec2(pos.x, 1.0 - pos.z); // down
        case 1u: return vec2(pos.x, pos.z); // up
        case 2u: return vec2(1.0 - pos.x, 1.0 - pos.y); // north
        case 3u: return vec2(pos.x, 1.0 - pos.y); // south
        case 4u: return vec2(pos.z, 1.0 - pos.y); // west
        default: return vec2(1.0 - pos.z, 1.0 - pos.y); // east
    }
}

/**
 * The face's outward normal (EDhDirection order), or zero where DH doesn't send it (3.1.2).
 */
vec3 faceNormal()
{
    if (uClHasNormals == 0) return vec3(0.0);
    switch (vNormalIndex)
    {
        case 0u: return vec3(0.0, -1.0, 0.0);
        case 1u: return vec3(0.0, 1.0, 0.0);
        case 2u: return vec3(0.0, 0.0, -1.0);
        case 3u: return vec3(0.0, 0.0, 1.0);
        case 4u: return vec3(-1.0, 0.0, 0.0);
        default: return vec3(1.0, 0.0, 0.0);
    }
}

// DH's 4x4 Bayer matrix, for the same dithered fade its own shader uses
float bayerMatrix4x4(vec2 st)
{
    int x = int(mod(st.x, 4.0));
    int y = int(mod(st.y, 4.0));
    float bayer4x4[16] = float[16](
        0.0,  8.0,  2.0, 10.0,
        12.0, 4.0, 14.0,  6.0,
        3.0, 11.0,  1.0,  9.0,
        15.0, 7.0, 13.0,  5.0
    );
    return bayer4x4[y * 4 + x] / 16.0;
}

void main()
{
    vec3 absPos = vec3(vertexWorldPos.x + uClCameraPos.x, vertexYPos, vertexWorldPos.z + uClCameraPos.z);

    // Prefer the finest volume that covers this fragment. A small margin keeps linear filtering
    // from reading clamped edge texels.
    // Each volume is read half a texel IN FRONT of the face: an LOD face lies on the boundary
    // between the solid block (no light inside) and the lit air before it, and at a texel
    // boundary linear filtering averaged the two, so a remembered light showed at about half
    // its level once its chunk turned into an LOD (the owner's "loses some of its brightness").
    vec3 normal = faceNormal();
    vec3 nearCoord = (absPos + normal * uClHalfTexel.x - uClNearMin) * uClNearInvSize;
    vec3 farCoord = (absPos + normal * uClHalfTexel.y - uClFarMin) * uClFarInvSize;
    vec3 ultraCoord = (absPos + normal * uClHalfTexel.z - uClUltraMin) * uClUltraInvSize;
    bool inNear = all(greaterThan(nearCoord, vec3(0.005))) && all(lessThan(nearCoord, vec3(0.995)));
    bool inFar = all(greaterThan(farCoord, vec3(0.005))) && all(lessThan(farCoord, vec3(0.995)));
    bool inUltra = all(greaterThan(ultraCoord, vec3(0.005))) && all(lessThan(ultraCoord, vec3(0.995)));
    vec4 stored = inNear ? texture(uClVolumeNear, nearCoord)
                : inFar ? texture(uClVolumeFar, farCoord)
                : inUltra ? texture(uClVolumeUltra, ultraCoord)
                : vec4(0.0);

    // Alpha band: 0 = nothing remembered, ~0.5 = remembered, up to 1.0 = remembered + absorbing.
    // Presence is the band scaled back to 0..1; a remembered texel filtering against an empty
    // neighbour ramps 0.5 -> 0 and never reaches the absorption band, so data frontiers cannot
    // fake absorption. The HUE uses the un-premultiplied colour (dividing by presence undoes the
    // darkening linear filtering causes against empty texels), so it stays true across fade ramps.
    float presence = clamp(stored.a * 2.0, 0.0, 1.0);
    vec3 net = presence > 0.001 ? stored.rgb / presence : vec3(0.0);
    float netPeak = max(net.r, max(net.g, net.b));
    vec3 hue = netPeak > 0.001 ? net / netPeak : vec3(1.0);

    // The hue is used as-is (no saturation weighting): with the channelwise neutral-curve
    // formula below, a white hue is already neutral and a near-white hue (the "vanilla" colour,
    // RGB 230/225/218) keeps its subtle warmth — a saturation wash flattened it to pure white.
    float colorWeight = smoothstep(0.1, 1.0, presence);
    vec3 tint = mix(vec3(1.0), hue, colorWeight);

    // Lightmap axes: u = block light, v = sky light (MC's layout). DH's standard.vert names the
    // meta nibbles the other way around ("skyLight" = high nibble) but stays self-consistent; in
    // truth the high nibble (vertexLightCoord.x) is BLOCK light. Trusting DH's names here put the
    // sky lookup on the block axis and painted black patches wherever colour was remembered.
    const float LIGHT0 = 0.5 / 16.0;

    // Where the memory is confident, the remembered level IS the LOD's block light. DH bakes LOD
    // light per chunk per vertex, which cuts light fields flat at chunk boundaries and bleeds
    // through thin occluders like closed doors (both verified with DH alone, no CL). The colour
    // memory holds the engine's true net result — shape, blocking, and absorption included — so
    // trusting it outright replaces those artifacts with the real light field at the volume's
    // resolution. DH's own bake only shows through where nothing is remembered (colorWeight
    // fades to it at memory frontiers). This subsumes the earlier "lift" and absorber cap:
    // missing bakes are filled, stale bakes are corrected DOWN too, and absorbed areas render
    // dark simply because their remembered net level is dark.
    //
    // The level uses the PREMULTIPLIED peak, unlike the hue: it must FADE across the filter ramp
    // into unremembered space. The un-premultiplied value held the source's full brightness across
    // the whole ramp and then snapped off at the presence threshold, which stamped a light's glow
    // as a section-sized plateau cut off flat at the boundary (the "half a diamond" artifact).
    // peak 0..1 maps back to a light level: stored bytes are nibble*17, so nibble/16 = peak*0.9375.
    // The boost compensates the levels being cluster AVERAGES, which read a couple of levels
    // dimmer than the true in-world peaks; multiplicative so zeros stay zero (an additive boost
    // would halo every glow's fringe).
    // The upper clamp must be the brightest texel's CENTER (15.5/16, the true "level 15"
    // coordinate), never 1.0: DH's lightmap copy is GL_NEAREST + GL_REPEAT (NativeImage.upload
    // with blur/clamp false), so u = 1.0 wraps around to texel 0 = block light ZERO. The boost
    // saturates every brightly remembered fragment to the clamp, so clamping at 1.0 painted
    // whole nether lava oceans with the DARK texel (near-invisible black lava) with a
    // bright/black speckle frontier where the volume filtered across the saturation threshold.
    const float LIGHT15 = 15.5 / 16.0;
    const float LEVEL_BOOST = 1.5;
    float peak = max(stored.r, max(stored.g, stored.b));
    float rememberedLevel = clamp(peak * 0.9375 * LEVEL_BOOST + LIGHT0, 0.0, LIGHT15);
    float blockCoord = mix(vertexLightCoord.x, rememberedLevel, colorWeight);

    vec3 combined = texture(uLightMap, vec2(blockCoord, vertexLightCoord.y)).rgb;
    vec3 skyOnly = texture(uLightMap, vec2(LIGHT0, vertexLightCoord.y)).rgb;

    // Match the in-world core shaders: they apply block light CHANNELWISE through the lightmap's
    // red curve (a neutral brightness ramp), not by tinting the warm vanilla block colour — that
    // is why a pure white colored light looks white in-world. Multiplying the warm lightmap by the
    // hue instead rendered white light orange on LODs. Per channel, sample the neutral curve at
    // that channel's own level (hue-scaled), then composite the same way the core shader does.
    vec3 channelCoord = mix(vec3(LIGHT0), vec3(blockCoord), tint);
    vec3 blockColored = vec3(
        texture(uLightMap, vec2(channelCoord.r, LIGHT0)).r,
        texture(uLightMap, vec2(channelCoord.g, LIGHT0)).r,
        texture(uLightMap, vec2(channelCoord.b, LIGHT0)).r);
    // The lightmap's darkest texel (block 0, sky 0) is a floor every sample above includes, and
    // skyOnly already carries it: adding it again lit every remembered section at night, unlit
    // parts included, a 16x16 patch brighter than DH's own LODs around it (2026-10-12 screenshot).
    // It fades out as the level rises, so bright light still matches the in-world shaders.
    float lightFloor = texture(uLightMap, vec2(LIGHT0, LIGHT0)).r;
    blockColored = max(blockColored - lightFloor * (1.0 - (channelCoord - LIGHT0) / (LIGHT15 - LIGHT0)), 0.0);
    // Same wash and composite as the in-world shaders (cl_blendLight since 2026-09): the LOD
    // copy still had the older composite and drew coloured light dimmer than the chunks it replaced.
    float effectiveSkyBrightness = skyOnly.r * (1.0 - uClNightVibrancy) * vertexLightCoord.y;
    float washFactor = max(0.1, 1.0 - effectiveSkyBrightness);
    blockColored = mix(vec3(length(blockColored)), blockColored, washFactor * 0.25 + 0.75);
    vec3 colored = skyOnly + blockColored * (max(0.1, 1.0 - skyOnly.r) * 0.9 + 0.1);
    vec3 light = mix(combined, colored, colorWeight);

    fragColor = vec4(light, 1.0) * vertexAlbedo;

    // DH 3.2 block texture tiles: each tile stores a color ratio relative to the LOD's flat
    // color (128 = keep base color). Identical math to DH's own frag, applied after our light
    // tint the same way DH applies it after its lighting.
    if (uClTexturedLods != 0 && vTextureTileId != 0u)
    {
        ivec2 tileOrigin = ivec2(int(vTextureTileId % 256u), int(vTextureTileId / 256u)) * 16;
        ivec2 texelPos = tileOrigin + ivec2(clamp(blockFaceUv() * 16.0, 0.0, 15.0));
        vec4 tile = texelFetch(uClBlockAtlas, texelPos, 0);
        vec3 clampedColor = clamp(fragColor.rgb * (tile.rgb * 2.0), 0.0, 1.0);
        fragColor.rgb = mix(fragColor.rgb, clampedColor, tile.a);
    }

    if (uClDebugMode == 1)
    {
        // dark red: outside every window; dim blue: in a window but nothing remembered;
        // otherwise: the raw remembered colour
        if (!inNear && !inFar && !inUltra) fragColor = vec4(0.3, 0.0, 0.0, 1.0);
        else if (presence < 0.05) fragColor = vec4(0.05, 0.05, 0.3, 1.0);
        else fragColor = vec4(net, 1.0);
    }
    else if (uClDebugMode == 2)
    {
        // geometry check: RGB stripes from the absolute world position on a 16-block grid.
        // Correct geometry looks like a smooth 3D checker glued to the terrain; garbage
        // geometry looks like noise or screen-space smears.
        fragColor = vec4(fract(absPos / 16.0), 1.0);
    }
    else if (uClDebugMode == 3)
    {
        // albedo only: should look like an unlit color map of the terrain
        fragColor = vec4(vertexAlbedo.rgb, 1.0);
    }
    else if (uClDebugMode == 4)
    {
        // vanilla lightmap only: DH's own lighting with no albedo and no recolouring
        fragColor = vec4(combined, 1.0);
    }
    else if (uClDebugMode == 5)
    {
        // light coords: red = block light amount (high nibble), green = sky light amount (low nibble)
        fragColor = vec4(vertexLightCoord.x, vertexLightCoord.y, 0.0, 1.0);
    }

    // DH's dithered near fade (ditherDhFade default): LODs dissolve where vanilla chunks take over
    float viewDist = length(vertexWorldPos);
    float worldNoise = bayerMatrix4x4(gl_FragCoord.xy) + 0.001;
    float fadeStep = smoothstep(uClipDistance, uClipDistance * 1.5, viewDist);
    if (fadeStep <= worldNoise)
    {
        discard;
    }
}
