Changelog

Planned
- port to all major Forge and NeoForge versions (1.20.2 through 26.2), Fabric later
- full Sodium support alongside Embeddium
- keep Valkyrien Skies compatibility on every version where VS exists
- add Sable / Create Aeronautics compatibility alongside Valkyrien Skies where possible

2026-10-13
- Fixed Distant Horizons LODs showing square patches lit brighter than the terrain round them at night (the owner, with DH 3.3.3: "I see some weird patches where the terrain is lit different").
  - Cause: a remembered section (any 16-block section that held some colored light, a cave's glow lichen under a hill included) is drawn with the remembered light instead of DH's own, and that composite added the lightmap's darkest texel (block 0, sky 0) on top of the sky light, which already carries it. Every unlit part of such a section came out a step brighter than DH's own LODs beside it.
  - Fix: in colorful_dh_terrain.frag the block part leaves that floor out at level 0 and takes it back in as the level rises, so unlit parts of a remembered section match DH's LODs and bright light is as before.
- Fixed colored light losing brightness when its chunk turns into an LOD (the owner: "when the chunk turns to an LOD, the colored light loses some of its brightness").
  - Cause: (1) an LOD face lies on the boundary between its solid block (no light inside) and the lit air in front of it, and the colour volumes were read right on it, so linear filtering averaged the two texels: about half the level where a texel boundary meets the ground. (2) The LOD copy of the in-world composite was never updated when the in-world shaders changed in September (cl_blendLight: block light times (max(0.1, 1 - sky) x 0.9 + 0.1) with the night-vibrancy wash), so LODs drew block light weaker than the chunks they replace (about half by day).
  - Fix: with DH 3.2+ (every LOD quad carries its face's direction) each volume is read half a texel in front of the face; the LOD composite is now the in-world one, night vibrancy included (new uniforms uClHasNormals, uClHalfTexel, uClNightVibrancy, set in DhTerrainColorShaderProgram). DH 3.1.2 (no face directions) reads where it did.
  - Checked: the shader pair compiles and links in the pack harness (light_dh_shader). Not measured against a DH world in the harness.
- Fixed dragon's breath particles drawn red (the owner: "Dragon breath particles are appearing as red for some reason").
  - Cause: Amendments (dragon_breath_emissive, on by default) gives DragonBreathParticle a getLightColor that rebuilds the light as LightTexture.pack(max(LightTexture.block(light), 10), LightTexture.sky(light)), a round trip that turns a colored value into red light at level 10 with no sky light. Measured in the pack harness (light_dragon): every breath particle's light was R160 G0 B0, in the dark and beside a torch alike, against block 10 / sky 15 with colored lighting off.
  - Fix: compat mixin AmendmentsDragonBreathMixin (applied only with Amendments, after its override): a colored value keeps its colour, raised to at least white level 10 while Amendments' option is on (CLPackedLight.maxWithLightLevel); a vanilla value is left to Amendments.
- The errors Distant Horizons throws in the owner's uhhh clean pack ("Cannot invoke ClientLevelModule.isRendering() because this.clientside is null", 17,000 lines) are not Colorful Lighting's: Chunks Fade In 3.0.25's DH mixin (chunksfadein_dh.mixins.json, iris.IrisGlDhMetaRendererMixin_forge, callback avoidClear) fails its injection on DH 3.3.3 while DH binds its renderers, DH's render setup stops there and its client level never starts. Update Chunks Fade In to a build made for DH 3.3, or leave it out while DH is in.

2026-10-12
- Added colour helpers for Lively Lighting's client half (compat/dynamiclights/DynamicLightsCompat: entityLightColor, itemLightColor, lampLightColor, fireLightColor, defaultLightColor; public static, called by reflection). Lively Lighting now tells its clients what each of its light blocks stands for (the holder and the slot its light comes from, a lamp, a burning ship) and answers the block light colour API with that source's colour as these give it.
  - Why: Colorful Lighting guessed each such block's colour from the coloured entity within 8 blocks, or from the light stored round its mirror across a ship's boundary, and fell back to white when neither found anything: a fast holder's trail ("the trail loses the color"), a light fading after it was put away, two ships' lamps merged into one light, the dim edge of a world lamp's light on a ship.
  - No change to the engine, the API or the config. Without Lively Lighting's client half the guess is as before.
- Fixed smooth lighting looking blocky since 2026-10-10 ("visual separation between blocks with light ... almost like smooth lighting is off").
  - Cause: the 2026-10-10 fix for light glowing round blockers capped every neighbouring sample at one level above the face's own cell, corners included, and at nothing when that cell was dark. In open air a corner cell is legitimately two levels brighter (light reaches the face's cell from it through an edge cell), and a cell next to a dark one can hold a level. So each face cut its corners differently from the face beside it, and the shared corners no longer matched: a step at every block edge.
  - Fix: an edge cell counts up to one level above the face's own cell, a corner up to one level above the brighter of its two edge cells. Light loses at least a level per block in this engine, so in open air (and water, leaves) nothing is ever cut and smooth lighting is exactly as before 2026-10-10; only light that came round a blocker is.
  - Measured in pack harness cl_smooth (a sea lantern among pillars, a slab and stairs; a soul lantern; a redstone torch; glowstone under water; each seen straight down and at a slant): with the option on and off, and against the mod as it was before 2026-10-10 (commit ed73ad2), the pictures are identical except on the animated light blocks and the water surface. The beacon in tinted glass and the shut door keep the 2026-10-10 result (cl_glass: floor round the glass brightest pixel 1, 98 with the option off; cl_door: dark side 2, 25 with it off).
- Fixed Create's shafts, cogwheels and other Flywheel-drawn parts rendering black on Valkyrien Skies ships by day (the owner: "Flywheel rendered blocks (like create shaft, etc) are rendered as completely black on a valkyrien skies ship"; found and measured by the pack's render work).
  - Cause: a block entity visual takes its instance light from LevelRenderer.getLightColor, which returns Colorful Lighting's packed colored light for a real level, and Colorful Lighting's copies of the instance shaders still read it as vanilla's packed light (the sky half came out negative and was dropped). In the world nobody saw it, because the fragment shader replaces that light with the colored light; Valkyrien Skies draws a ship's block entities as an embedded visual, whose branch keeps it: no sky light, and by day no block light either, so black.
  - Fix: the instance shaders (create rotating and scrolling, flywheel oriented and transformed) turn packed colored light back into vanilla's block and sky light first (cl_vanillaVertexLight in colored_light.glsl). Contraptions and world visuals are unchanged.
  - Measured in pack harness vsdraw_create (a shaft and cogwheel line on planks, on a static ship and in the world, by day): the ship's shaft was pure black before (luminance 0, the world's 107.9); now its luminance is 111.6 against 117.6 for the same line in the world (without Colorful Lighting: 99.9 and 101.6).
- Fixed a ConcurrentModificationException on the light propagator thread ("Colored light propagator pass failed"): the 2026-10-10 change that re-checks a block it can't read during a decrease called the main-thread bookkeeping (the view area and the ship regions) from the propagator thread. The re-check now runs on the main thread.
- The "queue is stalled with waiting chunks NEAR the player" warning says that the client holds no data for the missing neighbours. In the owner's log it came after the game's main thread had stopped on a Valkyrien Skies error during a jump into Genesis space, so no more chunks could arrive.
- Removed the META-INF and data folders that had appeared in the project root (an unpacked Create jar from a test session, not part of the mod).

- Xaero's maps (the owner: the minimap's light color "doesn't update for things like filters", "I also am not satisfied with the fact that it doesn't work with Xaero's World Map", "it would be ideal if, with cave mode, colored light could also be visible"):
  - Fixed the minimap keeping an old color: a beacon walled in blue glass afterwards stayed white; one set inside the glass by command was blue and stayed blue after a glass was broken.
    - Cause: the minimap redraws a pixel only when its blocks or light levels change. Stained glass changes neither, only the light's color, and that color settles on the propagator thread after Xaero has already redrawn the chunk and marked it clean.
    - Fix: each minimap tile keeps the color its pixels were drawn with, and a pixel whose color changed is redrawn (XaeroMinimapWriterMixin, XaeroMinimapTileMixin); and when colored light changes in a chunk, Xaero's maps are told the way Xaero learns of block and light updates, by marking the chunk not clean (XaeroChunkRefresh, from ColoredLightEngine.onLightUpdate).
    - Measured in pack harness cl_xmini (the owner's steps, the minimap's own colour 3 blocks from the beacon at night): beacon then blue glass #afb9c5 (white) before, #4b4fc5 (blue) now; glass then beacon by command blue both; one glass broken: blue on every side before, now white on the open side and blue-white elsewhere; colors switched off: white.
  - Added Xaero's World Map: the full-screen map at every zoom, and the minimap when it shows World Map chunks, which it does again (the 2026-10-10 switch that made the minimap draw its own chunks is gone: one place records the colors, and the minimap doesn't do the World Map's work a second time).
    - A color is read only when the World Map's own writer reads a pixel of a chunk loaded round the player (MapWriter.loadPixel), at the cell it took the pixel's block light from, once the colored light there is fully propagated; never for a place the player hasn't been. When the World Map draws the tile, the colors go into the region's record (RegionColors), saved beside its region files as <x>_<z>.clcolors (compressed; a region with one lit beacon is about 130 bytes). A place keeps the colors it had when the player was last there, and they change only when the World Map itself updates that chunk.
    - Measured in pack harness cl_xworld (the owner's main pack, with XaeroPlus; a beacon in blue glass on white concrete at night, blue pixels round it on the full-screen map): 2577 with the colors, 12 without; the same after going 2000 blocks away and moving the map back (2672), and after the colors were written out, forgotten and read back from their file (2672); zoomed out to the second and third texture levels 70 and 13 (0 without); the minimap on World Map chunks 2508 (832 without, its blue frame). The World Map recorded the colors 9 to 11 ticks after the light settled; reading a color took 0.4-0.6 microseconds, on lit pixels only.
    - Drawn by Colorful Lighting's copies of Xaero's map shaders (assets/minecraft/shaders/core/colorful_lighting_xaero; with XaeroPlus a variant that keeps its transparent background): beside each World Map texture with colors in its footprint, a texture of the light's hue per pixel on unit 1 (WorldMapColorTextures) tints the block-lit part of the pixel with Xaero's own formula. Without colors the picture is exactly Xaero's. Zoomed out, the hue is averaged over the blocks a pixel covers.
  - Cave mode on the minimap's own chunks shows colored light (its brightness is block light; cl_xmini: a room lit by a beacon in red glass, #b85358 with the colors, white #b8c3cf before). With Xaero's "legible cave maps", which shade by depth, the color shows as a tint by the light's strength (not measured).
  - In cave mode the minimap draws its own chunks while xaeroMinimapColors is on, as it does when the World Map's cave layers can't be used, so caves show colored light with the World Map installed too; on the surface it stays on World Map chunks. The World Map's own cave layers stay grey-lit in singleplayer: there it builds them from the world save, and its writer wrote only a few cave chunks, all with block light 0 (pack harness cl_xcave), so no color is ever read for them.
    - Measured in cl_xcave (the owner's main pack): on the surface the minimap shows World Map chunks; in a red-lit cave room it switches to its own chunks and shows red (#b85358, white before); with xaeroMinimapColors off it is back on World Map cave chunks; back on the surface, World Map chunks again. Frame rate in the cave 280 fps with its own chunks against 276 on World Map cave chunks (10 s each).
  - New client option xaeroWorldMapColors (default true). xaeroMinimapColors now covers only the chunks the minimap draws itself.

2026-10-10
- Fixed light glowing round blocks that stop it: a beacon walled in tinted glass lit the floor under and round the glass (the known issue of 2026-07-14), and a shut door lit a block-wide patch of the floor and walls on its dark side ("the light seeping under the door appears too bright and large of an area").
  - Cause: no light got through, but smooth lighting blends each corner of a face from the four cells round it and fills unlit ones with the dimmest lit one. One lit cell next to a dark one therefore lit the whole corner: the beacon's own cell (15) beside the dark glass, or the door's lit cell beside the dark room.
  - Fix: a neighbouring cell now counts only as far as its light could reach the face (SodiumAoFaceDataMixin with Embeddium, PackedLightData.blend with vanilla rendering). Emissive blocks keep their glow. (The bound as first written here was wrong and made smooth lighting blocky; see 2026-10-12.)
  - Recommendation for doors: the bright seep is removed rather than redrawn. Light through a shut door is now only what its filter lets through (filters.json, spruce 12 of 15). A faint one-level strip shows where the dark side has some light.
  - New client option smoothLightStaysBehindBlockers (default true; false gives the old blend).
- Fixed colored light going straight through tinted glass in the CamaCraft pack ("if you put a beacon down and cover each face with tinted glass, there is still some visual light"). In the pack's test instance this lit the whole room behind the glass.
  - Cause: the pack's resources give Lighting Utils' redstone tinted glass a filter "white;15" when unpowered. The propagator treated an absorption of 15 as blocking only where the block's shape covers the face and let light through with the usual cost of 1 elsewhere. Glass has no occluding shape, so the 15 never applied.
  - Fix: an absorption of 15 now stops light, as the filters.json docs say (LightPropagator).
- Fixed colored light left behind when many blocks change at once (TNT, /fill, mass breaking), and removed the explosion purge that hid it.
  - The purge rebuilt the 3x3 chunks round every exploded chunk half a second later, so every light nearby went dark and came back: the flashing round TNT.
  - Measured in pack harness cl_linger (a TNT chain among 16 colored lamps, glass and walls; single TNT; /fill removing and placing walls; a grid broken in one tick), every cell against a full recompute. Without the purge, the old engine left 23 to 922 cells lit after the chain and 881 after the single TNT.
  - Cause: when a block changes, the engine has to take out the light its cell had and let in what the neighbours give. How much to take out was read at the wrong moment. The main thread's stored light lags the propagator thread, so light still being spread when the block changed was missed. By the time the propagator got to the change, another change's decrease could already have cleared the cell, so a lamp blown up by TNT read as dark and its light was never taken out.
  - Fix, all in the engine:
    - The decrease now starts from the brightest of three values: the light stored at the cell when its change is handled, the propagator's latest value for it, and, for a light source, its own brightness recorded in all channels the instant its block was replaced (LevelChunkMixin, ColoredLightEngine.onBlockReplacing). Taking out too much is safe, because what is still lit spreads back in.
    - All channels, because a source's color can change while it stands without the engine being told. Lively Lighting's flashing TNT light blocks take the color of whatever entity is nearest when they are relit (dropped lamp items, for one). Taken out by their last color, the other channels of the light they had spread stayed behind as a dim fringe.
    - A decrease no longer stops when a block can't be read while the main thread rewrites its section. It carries on and re-checks the block later.
    - Blocks that only change colored light (stained or tinted glass broken or placed, any block with a filter or absorber) now reach the engine at all. Vanilla fires no light check for them, so the light beyond broken stained glass stayed tinted.
  - After the fix (with the all-channel start), 5 runs: 0 differing cells in every case. The one exception was 3 dim cells (level 1, 7 blocks up where the TNT flies) after the chain in 1 of the 4 runs with Lively Lighting; it was 0 without it. Before the all-channel start, the chain left 17 to 240 such cells in 4 of 9 runs.
  - The lamps 22 blocks from the chain never dropped in any frame; with the purge they went dark for 73 frames. The worst frame during the chain was 29 to 52 ms, against 160 ms with the purge.
  - /cl purge (all or chunk) stays as a manual tool. The other automatic resets stay too, because they are needed: switching colored light on or off, a resource reload, Distant Horizons LOD colors.
- Fixed Immersive Portals again: a dimension seen through a portal was lit white until the player had been there.
  - Cause: a refactor on 2026-09-05 moved the per-tick pass that hands the portal's chunks of every non-current level to that level's colored engine into the current level's tick. There it returned straight away, so remote levels never got any coverage.
  - Fix: the pass runs from the client tick again and visits every level the client has chunks in (ImmersivePortalsCompat.clientTick, a weak set of levels).
- Added Xaero's Minimap compatibility: with the minimap's Lighting on, the block-lit part of a minimap pixel takes the color of the light there, per channel with Xaero's own brightness formula. Nothing changes by day.
  - The mixin applies only when Xaero's MinimapWriter has every member it uses (checked in the class bytes), and its injections are optional.
  - Pixels under water or glass, glowing blocks, cave mode and the light overlay keep Xaero's colors.
  - With Xaero's World Map installed, the minimap normally draws the World Map's chunks, whose textures keep only a light level. While xaeroMinimapColors is on, the minimap draws its own chunks instead (as if its minimap_display_world_map_chunks option were off). The World Map screen itself stays grey-lit. (Superseded on 2026-10-12: the World Map has the colors too and the minimap uses its chunks again.)
  - New client option xaeroMinimapColors (default true).
- API docs: the "Depending on the mod" section is rewritten with what is true. The mod is on no public Maven repository; a mod compiles against a built jar (libs/ + flatDir), the thin -api jar (compile only), or the local Maven coordinates from publishToMavenLocal.
- Fixed the light of a server-side dynamic lighting mod (Lively Lighting's light blocks) shining orange when nothing colours it: a held enchanted item, a glowing mob, a world lamp's light projected onto a Valkyrien Skies ship. With no coloured entity near and no ship lamp to take a hue from, such a light block fell through to the automatic emitter colour, which reads the light block's texture: vanilla's light item icon, a yellow bulb (15,13,9). Its dim edge then went red, since light loses a level per channel per block. It is plain white now, unless emitters.json names minecraft:light (Config.getColorEmission). Pack harness shiplight_dark, an enchanted sword's light with no ship within 120 blocks: #665533 before, white after.
- Fixed a held light turning red next to a Valkyrien Skies ship (the owner: "when I get close to the ship all of a sudden the light my enchanted item emits turns red"), and one-block ships tinted orange or red at night. Lively Lighting places a held light both in the world and, projected, in the shipyard of a ship beside it; each of the two took its hue from the light stored round its mirror in the other space, so each other's light, and the dim red fringe of either, scaled to full, made both pure red (shiplight_dark: 15,0,0 for every light along a row of ships). A light block in the world now takes a hue from a ship only when a real ship lamp (a light-emitting block that isn't itself a dynamic light block, or one an API provider colours, such as a burning ship's light) stands within 3 blocks of its mirror, from the brightest open cell round that lamp whose brightest channel is at least 4 (DynamicLightsCompat.sampleShipMirrorHue / hueRoundLamp), so it never takes the hue of its own projection; a light block in a shipyard (a world lamp lighting the ship, another ship's lamps through their world projections) still takes the world's light where the ship is, ignoring cells dimmer than 4. shiplight_dark after: every light along the row white (#666666 at level 6).
- Fixed colored light filtering on ships: a beacon ship in a shell of red stained glass lit the world white, where the same beacon in the world lights it red. The hue was the stored light at the lamp's own cell, white; it is now the brightest open cell round the lamp outside the ship's own blocks, red beyond the glass (VsCompat.insideShipBlocks). shiplight_beacon: the ground round the ship #bbbbbb before.
- Fixed a NullPointerException in LevelRenderer.getLightColor for a Level that has no colored engine of its own: VMod's schematic preview bakes a pasted ship's mesh against such a level off-thread, and the preview thread died (no preview drawn). Such levels keep vanilla lighting.

2026-10-07
- Fixed vanilla light corruption: Standard vanilla lights (like full-bright blocks) will no longer incorrectly render with a red tint.
- Improved light blending: Vanilla lighting now blends seamlessly into custom colored lights without color distortion in the corners.
- Fixed Create contraptions rendering rotating, scrolling, and oriented Flywheel parts (shafts, cogs, belts, etc.) pitch black when moving: `rotating.vert`, `scrolling.vert`, and `oriented.vert` now transform vertex positions by `modelMatrix` when embedded (`#ifdef FLW_EMBEDDED`) so that world-space colored lighting is sampled at the part's actual position rather than untransformed contraption-local coordinates.
- Fixed doors, moving chests, and train block entities rendering completely dark on moving contraptions: `CreateCompat` previously hardcoded `0` for `VirtualRenderWorld`, ignoring `externalPackedLight`. It now queries `VirtualRenderWorld`'s external light and combines it with any internal emission or block light on the contraption, restoring full colorful lighting on moving chests, sliding doors, and train carriages.
- Fixed daytime light texture z-fighting/shimmering on moving Create contraptions: replaced quantized integer sky/block sampling in `colored_light.glsl` with continuous floating-point lightmap coordinates, and smoothly blended colored light with Flywheel's vanilla lightmap sample in `common.frag` when block light is present, eliminating abrupt branch transitions across moving polygon faces.
- Fixed Flywheel static visuals (shafts, cogs, kinetic blocks, etc.) not relighting when light sources are placed or broken: `ColoredLightEngine.onLightUpdate` now notifies Flywheel's `VisualizationManagerImpl` via `FlywheelCompat.onLightUpdate`, ensuring all visuals in affected sections are marked dirty and scheduled for `updateLight()` when light changes.

2026-10-03
- fixed Flywheel dropping to 'off' for the rest of a session (every Create part then drawn the slow vanilla way) the first time a contraption carrying a turning part came into view (a gantry carriage's pinion, a shaft or cog on a bearing, a train's bogey): the log said "Failed to compile pipeline/instancing/create_instance_rotating/flywheel_material_default_embedded.vert: undefined variable "modelMatrix"", then "Flywheel backend fell back from 'flywheel:instancing' to 'flywheel:off'". Our copy of Flywheel's internal common.vert sets modelMatrix for every embedded (contraption) shader, but only our transformed.vert declared it, so every other instance type (Create's rotating, scrolling, oriented) failed in the embedded context; it is now declared in api_impl.vert, ahead of every instance shader. Pipelines compile when first needed, so only sessions that drew such a contraption hit it
- the copies of Flywheel's internal vertex shaders are taken per Flywheel shader family and only used when Flywheel's own files match: the old copies were Flywheel 1.0.5's, which under 1.0.6 (the version Camafy makes the pack load since 2026-10-02) left flw_vertexId counting from the start of the shared vertex buffer instead of the model (Vanillin's sign text picks glyph corners with it). The Flywheel overrides moved out of the core shader pack into colorful_lighting_flywheel_shaders plus colorful_lighting_flywheel_shaders_fw105 (Flywheel 1.0.4, 1.0.5, 1.0.6 betas) or _fw106 (1.0.6 builds 280 on), picked by comparing Flywheel's common.vert and api_impl.vert with reference copies (internal/flywheel_reference_*); a Flywheel matching neither gets no overrides and keeps its own lighting instead of failing to compile
- fixed lag by Flywheel-rendered objects (Create's contraptions and parts) while a light moves near them, e.g. a torch held with a server-side dynamic lighting mod such as Lively Lighting, which moves real light blocks: every light change makes Flywheel recollect each light section it tracks within a section of the change, and the colored light of each one was sampled block by block across its 18x18x18 box, leaving the cached section on nearly every row (about a thousand concurrent-map lookups a section), then sampled again when the engine's own propagation landed a frame or two later, part of it on Flywheel's frame-plan worker while the render thread wrote the same storage. The box is now sampled with one lookup per section it touches (ColoredLightEngine.sampleSectionBoxPacked, the same values), a request only marks the section, and the upload collects marked sections on the render thread, each at most once a tick (so at most a tick late), under the storage's lock. Pack harness flywheel_held_light (walking with a torch past two turning plates and a line of cogwheels at night): colored collections 101 a second at 95 us each, a third of them on the worker, down to 54 a second at 38 us; with Camafy 0.1.25's Flywheel fix, the render thread waits 1.2 ms a second for Flywheel instead of 21.8

2026-10-02 (later)
- Create contraptions (bearings, windmills, pistons, gantries) drawn black or tinted solid with the nearest lamp's colour: the cause is not Colorful Lighting but the Flywheel version a pack ends up with. Vanillin 1.1.3 bundles Flywheel 1.0.4 under its own artifact name, so Forge loads that copy instead of the 1.0.5 Create 6.0.8 ships (or a newer one another mod bundles), and on Flywheel 1.0.4 Create 6.0.8's contraptions get no light at all (pitch black even without Colorful Lighting). With Flywheel 1.0.5/1.0.6 they render correctly, with or without Colorful Lighting. Fix for packs: put a Flywheel 1.0.5+ jar in the mods folder (a top-level flywheel-forge-1.20.1-1.0.6-280.jar wins over every bundled copy, Vanillin's included; checked in the CamaCraft pack), or use a Vanillin that bundles 1.0.5+
- flywheel-rendered objects now take their sky light from Flywheel's own light for each fragment (the instance's light, or an embedded visual's per-fragment light) and only their block light from the colour; the per-vertex sky lookup read whatever cell a vertex fell into and drew contraptions with almost no sky. Same result as before on static parts and on Flywheel 1.0.5+, and on Flywheel 1.0.4 contraptions are no longer lamp-coloured by day
- build: the local Acedium dev jar (implementation "a:acedium:1.0") is only used when it is in libs/; unconditional it broke every build on a machine without it

2026-10-02
- fixed light-emitting liquids from mods (e.g. Flow Fun!) lighting the world plain white with autoEmitterColors on: a liquid block has no real model (most mods' fluid blocks only name water's grey texture as their particle) and is tinted by its fluid type, not a block color provider. Liquids now take their color from the fluid's still texture under the fluid type's tint (Enchanted Water purple, Liquid Experience green, Soul Essence teal instead of white); configured emitters.json colors still win
- automatic liquid colors follow the block state: a burning liquid glows in its flames' color and Flow Fun!'s Liquid Redstone in its power's, and a change between two states that only differ in color is relit (the old and new state are each judged as themselves, not by what the level already holds)
- added "/cl debug autocolor <namespace>": the light color of every light-emitting block state of a mod, in the log

2026-09-05
- fixed colored light being wiped and fully re-propagated (all chunks re-meshing) after walking through an Immersive Portals portal
- added True Darkness compatibility: its darkening now follows the dimension being rendered, so a dark Nether no longer blacks out the Overworld seen through an Immersive Portals portal
- fixed a brief flash of uncolored, washed-out lighting right after arriving in a dimension through an Immersive Portals portal with Distant Horizons colored LODs on (the LOD colour volume now switches dimension immediately)

2026-08-07
- fixed fps stutter with Distant Horizons colored LODs: light changes now patch the color volume incrementally instead of re-uploading all of it 4 times per second
- fixed block break particles flashing uncolored for one frame near colored light sources with Async Particles installed
- added a developer API for other mods (light sampling, dynamic light color providers, packed-format helpers, custom level integration; see docs/API.md)
- fixed colored light updates stopping permanently (until /cl purge) when the light engine thread died; the engine now logs the crash and restarts itself
- added "/cl debug queue" for light engine diagnostics
- fixed particles rendering black in daylight and uncolored white near lights with Async Particles installed (this also fixes all the Pretty Rain weather particles)
- added Subtle Effects compatibility (splash, droplet and ripple particles now take colored light)

2026-08-06
- improved Immersive Portals compatibility: colored light now renders correctly in remote dimensions seen through portals
- fixed dynamic light state bleeding between dimensions by making it per-level
- improved Distant Horizons LOD color accuracy and shader performance
- fixed light intensity in Distant Horizons LOD chunks near colored light sources
- fixed black/invisible lava in Distant Horizons LOD chunks in the Nether

2026-08-05
- fixed crash on world join when Distant Horizons is not installed
- fixed pitch-black terrain with Nvidium/Acedium installed
- added colored terrain lighting under Nvidium/Acedium (hue fades slightly to vanilla on sky-lit faces)
- fixed skylight washing out colored terrain light way too much under Nvidium/Acedium
- fixed colored light under Nvidium/Acedium tinting its whole light field at full saturation instead of fading to darkness with distance
- added night vibrancy (moon cycle) support to colored terrain light under Nvidium/Acedium
- fixed the Nvidium/Acedium night vibrancy barely responding to the moon cycle (vanilla star brightness caps at 0.5, which halved the effect)
- removed the whole-world refresh flash when night vibrancy changes under Nvidium/Acedium; only chunks with colored light re-mesh, in the background
- fixed the level tick handler running twice per tick, wasting frame time
- fixed the NBT light-rule scan re-serializing every tracked block entity 20 times a second; now scans at 2Hz (server-pushed changes still apply instantly)
- fixed redundant shader re-binding in the Distant Horizons LOD override costing frame time
- fixed invisible LOD water caused by the shader re-binding fix
- added Distant Horizons 3.2.0-b support for colored LOD lighting, including its new textured LODs
- fixed far-away LODs glowing at full light intensity across whole chunks near colored light sources; distant glow now matches the area's actual overall brightness
- fixed dark Epic Fight mobs (zombies, skeletons, players and other patched models)
- fixed striped light glitches on HBM Modernized machines
- added full colored lighting on HBM Modernized machines (auto-disables if a future HBM update changes its shaders, falling back to plain correct brightness)
- fixed dark fluid inside the Flopper mod's floppers
- added automatic light colors sampled from block textures for modded light sources (autoEmitterColors config, on by default)
- added "auto" color option in emitters.json for blocks that change color by position (e.g. Better End aurora crystals)
- made water light filtering more realistic: subtle per-block tint that deepens with distance
- added biome-tinted water filtering: swamp water filters light differently than ocean water
- added "multiply" filter mode and "biome_water" color in filters.json

2026-08-04
- fixed dark held and dropped items with Flerovium installed
- fixed dark block-breaking cracks overlay with Flerovium installed
- fixed dark particles with AsyncParticles installed

2026-08-03
- fixed crash with the Starlight (Create fix fork) mod installed
- fixed Flywheel rendering and made the Flywheel compat more reliable
- fixed the light propagation thread not shutting down when leaving a world
- added detection of which levels support colored lighting

2026-07-15 to 2026-07-19
- reduced memory usage during chunk light baking
- fixed rendering without Embeddium installed
- removed internal global state for better stability across worlds and dimensions
- split the bundled resource packs
- started groundwork for baseline Sodium support

2026-07-14
- fixed crash when riding boats with the Wakes mod installed
- added support for OpenGL 4.3 and lower (Flywheel compat no longer requires GL 4.5+)
- known issue: beacon enclosed in tinted glass still leaks light at the corners
- known issue: shut doors bleed light around the edges and bottom on the dark side

2026-07-13
- added Valkyrien Skies compatibility: colored lighting works on ships
- added colored dynamic lighting on ships
- fixed Lively Lighting compat: light color crosses between world and ships correctly
- added automatic shader patchers for BSL and Complementary shader packs
- added "/cl off" to load unpatched shader packs

2026-07-09 to 2026-07-12
- sped up the light engine (faster chunk light loading, new config-controlled algorithm)
- fixed a ConcurrentModificationException in the engine
- re-implemented the NBT system for block entity light colors
- updated entity light definitions

2026-07-07 to 2026-07-08
- tweaked light colors

2026-07-04 to 2026-07-05
- added automatic shader pack patching for compatibility
- finished dynamic lighting mod compat (held and dropped item light)
- fixed the shader pack patcher
- fixed lighting inconsistency between Embeddium and vanilla rendering

2026-07-02 to 2026-07-03
- fixed doors and trapdoors: light blocks and blends correctly as they open and close
- fixed registry load times
- fixed Flywheel backend rendering
- fixed resource pack reload on boot
- fixed wrong light values for two blocks
- reduced lag, added a config for light update speed

April to May 2026
- colors wash out more in direct sunlight
- changed the default glowstone color
- fixed stairs lighting
- fixed shading and smoothing
- fixed light attenuation
