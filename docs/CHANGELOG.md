Changelog

Planned
- port to all major Forge and NeoForge versions (1.20.2 through 26.2), Fabric later
- full Sodium support alongside Embeddium
- keep Valkyrien Skies compatibility on every version where VS exists
- add Sable / Create Aeronautics compatibility alongside Valkyrien Skies where possible

2026-11-11 (2.12.0)
- Lights that absorb light, for dynamic light providers: a light whose colour carries `DynamicLightSink.ABSORB` takes
  light away where an ordinary light of its level, shape and reach would have added it: the block light by its red, green
  and blue (`ABSORB | 0xFFFFFF` takes everything), and the sky's light by the most of them. A point or a beam, with walls
  in its way as any dynamic light. Drawn where the shaders draw the dynamic lights (Embeddium's terrain, Flywheel) and on
  entities; on the paths that bake dynamic lights into meshes or the lightmap it is no light at all. It is never light
  for anything that asks how bright a place is. Lively Lighting 0.8.0 sends one for a definition whose colour is
  `"absorb"`.
- Bloom for items that give light: a glowstone in the hand, a torch in an item frame, a lantern on the ground or in a
  mob's hand glow as their blocks do. The bloom finds light sources by the block a pixel shows, and an item is no block:
  the place and size of every such item is now noted as it is drawn (an item that gives no light costs one lookup by its
  type), and a bright pixel within that ball counts as a light source. The hand is drawn after the level, over a cleared
  depth, so it gets a pass of its own, only in frames where it holds something that gives light.
- Bloom through glass on ships: a ship's lamp behind the ship's glass is followed through the glass as in the world.
- Blacklight on more of what is drawn: armor (a dyed leather tunic glows in its dye), flat items in the hand, on the
  ground and in frames, and decals, through the vanilla shaders that fold the light into the vertex colour (the colour
  before its light rides in the varying `normal`, which their fragments do not read, with a mark no other vertex shader
  can leave there); particles, by their tint alone (their texture is mostly a grey shape); the hand, which is drawn in the
  camera's own space half a block before it; and what Flywheel draws (Create's contraptions and Vanillin's block
  entities), whose fragments now gather the ultraviolet of the lights and of the blacklight blocks' field as the
  terrain's do. Not lit: Distant Horizons' LODs, and what other mods draw with shaders of their own.
- `/cl bloom` and `/cl blacklight` say only what they are asked.

2026-11-11 (2.11.3)
- Bloom through glass: a lamp cased in glass had no glow with any graphics mode but Fabulous, where the glass owns the
  pixel. Glass and panes are now in the bloom's volume as blocks that are seen through, and a pixel that shows one is
  followed on through glass alone to a light source behind it. A lamp behind a window with air between glows through it
  only with Fabulous graphics: the volume cannot tell air from a wall.
- `/cl bloom on|off` and `/cl blacklight on|off` answer "Bloom on", "Bloom off", "Blacklight on", "Blacklight off", and
  the commands without an argument say only whether they are on.
- The bloom's block values are kept in a map that two threads may read (the ships' look of 2.11.2 read it from the client
  thread beside the volume's worker).

2026-11-11 (2.11.2)
- Bloom on Valkyrien Skies ships: a lamp on a ship had no glow. The bloom finds light sources by a pixel's place in the
  world, and a ship's blocks are kept far from where the ship is drawn. The nearest ships that carry a light source (four
  at most, within 160 blocks) now each get a cube of 64 cells in the ship's own block grid, and a pixel that matches
  nothing in the world is looked up there through the ship's transform as it is drawn that frame. A ship that moves or
  turns costs its matrix alone; its cube is read again every ten ticks, or at once when one of its blocks changes
  (0.13 ms a read in the test). A ship longer than 64 blocks glows in the part round the camera.
  `-Dcolorfullighting.bloomOnShips=false` for the way before. Harness `cl_bloom`: a glowstone on a ship 13 of 255
  brighter beside it and 9 above with the bloom on, as one in the world.

2026-11-11 (2.11.1)
- Fixed: a light's field cut off in a straight edge at a chunk border, until `/cl purge`. A chunk's pass spreads the
  chunk's own light sources, and a source whose cell holds its light already spreads nothing. Light that had spilled into
  the neighbouring chunk was therefore lost for good when that chunk left the tracked area and came back empty while the
  source's chunk stayed (the player about a render distance away and back), or when a lamp was placed beside a chunk that
  was not loaded yet. A chunk's pass now also carries light over its four borders: every lit cell on either side whose
  opposite cell holds less than the light could give it spreads again. A border in order costs the comparison of its
  cells; a chunk whose blocks arrive after its pass ran gets its pass again. `-Dcolorfullighting.stitchBorders=false`
  for the way before. Harness `cl_border`: a torch three blocks from a chunk border, the player sent away by the tracked
  area's half width and back; with the switch off the cells east of the border hold nothing where vanilla has 11, with it
  on they hold the torch's light again.
- Blacklight on entities: mobs, players, items in frames and block entities such as banners and beds glow under
  ultraviolet as blocks do (a lime sheep as lime concrete). The game's entity shaders work it out: the vertex shader takes
  the ultraviolet at each vertex from the blacklight blocks' field and the ultraviolet dynamic lights, with what is in
  their way, and the fragment shader adds the glow by the texture's own color. Only shaders that hand the lightmap's color
  to their fragments and multiply the texture by it are touched (the entity render types; 13 vertex and 10 fragment
  shaders in the test pack); the vertex format and the packed light are as they were. The uniforms go to a program once a
  frame and only while something ultraviolet is about. Not lit: the hand, armor, and what other mods draw with shaders of
  their own. Client config `blacklightEntities` (on); `-Dcolorfullighting.entityBlacklight=false` leaves the shaders
  alone.
- Fixed: a blacklight boxed in with tinted glass still made the room glow. The blacklight blocks' field was stopped by
  full solid blocks only; now by anything that lets no light through.
- Bloom: the glow wavered a little as the camera or a light moved. The picture has no soft edges, so a lamp's outline
  moves a whole pixel column at a time and the glow's strength stepped with it. Two changes: the pyramid's way down reads
  thirteen taps instead of five (and nine on the way up), so a bright spot weighs the same wherever it lies on the pixel
  grid, and each frame's glow is blended with the frame before, read where the pixel's place in the world was on the
  screen then, over about a thirtieth of a second (client config `bloomSmoothing`, on;
  `-Dcolorfullighting.bloomSmoothSeconds`). Measured with the camera moved sideways a third of a pixel at a time
  (harness `cl_bloom`, `shimmer*`): the unevenness of the glow above a glowstone 1.03 percent of the glow before and 0.53
  with the wider taps, beside it 1.71 and 1.13. What the blending over frames adds does not show in that measure (the
  test waits between steps).

2026-11-10 (2.11.0)
- Blacklight: a light color of its own. A block whose color in `light/emitters.json` is `"uv"` (also `"blacklight"`,
  `"ultraviolet"`, with `;level` as any color) is a blacklight: what shows of the light itself is a dim violet, and the
  blocks around it glow in their own color, the more saturated the stronger (lime, orange, yellow, cyan and magenta light
  up; white, gray, stone and wood barely change). The glow falls off with distance and is stopped by walls as any light:
  the blacklights' reach is propagated through the blocks into a field of its own, sampled by the terrain shaders. No
  block is a blacklight unless a resource pack says so. A provider's dynamic light is one with the color
  `DynamicLightSink.BLACKLIGHT` (Lively Lighting 0.7.0 sends it for a flashlight whose color is `uv`). Drawn on blocks,
  on Embeddium's terrain shaders; entities and items are not lit by it, and it stands down under an Oculus shader pack
  (the violet light stays). Client config `blacklight` (on), `blacklightStrength` (1.0), `/cl blacklight on|off`.
- Bloom, off by default (`/cl bloom on|off`, client config `bloom`, `bloomStrength` 1.0, `bloomThreshold` 0.3;
  `/cl bloom` alone shows what the pass costs): light sources glow softly past their edges in the color of their light,
  and so does what glows under a blacklight. It is a pass over the finished picture and changes nothing about how the
  world is drawn: the vertex format, the packed light and the terrain shaders' light are as they were. What may glow is
  decided per pixel, not by brightness alone: the pass finds the block behind a pixel from the depth and looks it up in
  a volume of the light sources round the camera (256 by 128 by 256 blocks, kept up to date as blocks change), so a lit
  white wall or a bright texture that emits nothing never blooms. A full block of light level 14 or 15 (glowstone, sea
  lantern, froglights, shroomlight, a lit redstone lamp) glows over its whole face from a quarter of the threshold:
  colored light deepens such a block's own texture, and by brightness alone glowstone hardly passed. The glow is a
  screen blend, so it brightens without burning a lamp's texture to white. Not drawn under an Oculus shader pack.
- Bloom, what it does not cover yet: the hand and held items, entities and dropped items that carry a dynamic light,
  Distant Horizons' LODs, light sources further than the volume reaches, and the emissive parts of blocks that emit no
  light. With Fabulous graphics a lamp behind stained glass or under water glows in the color it is seen in; with the
  other graphics modes the pixel belongs to the glass and does not glow.
- Bloom reads the level's own view and projection, captured at the sky, particle and weather stages. Forge 1.20.1 hands
  the stage after the level the projection as its pose, and a pass that unprojects the depth with it finds no block
  where the picture shows one.
- For testing: `-Dcolorfullighting.bloomGain` (2.0, the glow of a light source), `bloomUvGain` (1.5, of what glows under
  blacklight), `bloomUnit` (0.2, the strength at `bloomStrength` 1) and `bloomDebug` (1 paints the pixels matched to a
  light source red and the block grid the pass sees; 2 the height and the distance).
- Harness `cl_bloom` (uhhh clean): the glowstone 71 of 255 brighter with the bloom on, the air beside it 10 and above it
  5; a lit white and a lit lime wall within 0.5; lime concrete 172 brighter under two blacklight blocks and light gray
  concrete unchanged; the pass 0.075 ms a frame on the CPU, the frame about 1.5 ms with or without it. A
  redstone torch, a soul torch and a torch by themselves before a white wall color it as 2.10.6 does, within 1 of 255.

2026-11-10 (2.10.6)
- A provider's point light can lose a level over more or less than a block (`DynamicLightSink.point` with `blocksPerLevel`,
  a default method that falls back to the plain `point`): as bright at its source, reaching `level * blocksPerLevel` blocks.
  The terrain and Flywheel shaders, the entities' exact light and the lightmap path all take it (it travels in a point
  light's unused direction), and the slabs measure what is left of the light in blocks as before. A point light's copy in
  a ship's own space is divided by the ship's scale, so it lasts the same blocks of the world there. Lively Lighting 0.6.8
  gives the lamps of a scaled Valkyrien Skies ship this way. `-Dcolorfullighting.pointStretch=false` takes every point
  light at a level per block, as before. Where the lights are propagated through the light engine instead (no shader
  path), a stretched light keeps a level per block.
- The slab worker's queue gives urgency and size a head start instead of a rank. The queue was ordered by urgency, then by
  slab class, the smallest first, so while the worker was full of small slabs the largest were never rebuilt: with 100
  point lights and 100 beams walking at once (32 + 16 + 4 slabs held) the class of the long beams had no build in ten
  seconds and their paths stayed where the beams had been. A job is now passed by at most 32 jobs asked for after it per
  step of urgency and 8 per slab class. The same scene: 2194, 957 and 177 builds in ten seconds for the three classes, each
  waiting 116 to 134 ms on average, the frame rate as before (515 frames a second against 616 to 663 with no light).
  `-Dcolorfullighting.slabQueueAging=false` ranks the queue as before.

2026-11-10 (2.10.5)
- A moving structure's filter block takes the share of each cell it covers (`VoxelOcclusion.BlockWriter.cover`, the fourth
  byte of a cell of the block volume). A ship's stained glass was written to whole cells, so the colour behind it moved a
  block at a time however slowly the ship went. A partly covered cell lets through what its share leaves, the shares of
  several blocks in one cell add up, and the propagation takes partly covered cells one after another on a light's way as
  one block crossed in parts: a wall of glass half a block off the grid lies in two layers of cells, and the product of
  the two would let a quarter of the stopped colours through. `-Dcolorfullighting.partialCells=false` writes such a block
  whole from half a cell on, as before. Blocks that stop light are whole cells as they were. Lively Lighting 0.6.7 gives
  its ships' glass this way.

2026-11-10 (2.10.4)
- A provider sets how often a moving light's slab follows it (`DynamicLightField.setUpdateInterval`, 1 to 10 ticks;
  `-Dcolorfullighting.slabUpdateTicks` is where it starts; 1, as before, is every tick the light moved). A slab's first build
  never waits, and a change of the blocks round a light is taken at once. With 64 lights moving, the slab thread took 95% of a
  core at 1, 34% at 4 and 15% at 10. Lively Lighting 0.6.6 sets it from `client.light_path_update_ticks`.
- The slabs' work is counted per class for a stress test (`DynamicLightField.statsRaw`, `resetStats`, `lightsHeld`): builds,
  their time and the longest, checks that found nothing to do, a job's wait for the worker, and the uploads with their time on
  the render thread.

2026-11-10 (2.10.3)
- Particles take the drawn dynamic lights on whichever thread asks. With AsyncParticles a particle's light is read on its tick
  workers, where the dynamic lights were left out: the particles of a block broken in a red dynamic light began at the vanilla
  light level in white and were then drawn with the stored coloured light alone, black in a dark place. A sample of the client's
  level off the render thread now adds the frame's dynamic lights as the render thread's samples do
  (`-Dcolorfullighting.liveSamplesOffThread=false` for the way before).
- An entity is lit by the dynamic lights at its own place, not by the eight cells round it (`DynamicLightsCompat.exactEntityLight`,
  `-Dcolorfullighting.entityExactLights=false`). Each cell took a light as it is at the cell's centre, so a narrow cone covered
  one of them now and then: a flashlight's holder turning slowly was dark with lit moments, and a kart under its headlights
  flickered as it drove. Every light is now worked out at the entity's probe and its middle, in its colour, times what its slab
  leaves of it there.
- A provider names the lights an entity is not lit by (`setEntityOwnLights`: a beam's holder, a kart and its headlights), and a
  carrier's glow has its light's colour (`setEntityGlowColor`; white before). A spot never lights what sits at its origin.
- The colour behind stained glass follows a light inside its cell. Since 2.10.0 a slab's tint was the tint of the way from the
  light's cell and changed only when the light entered another cell, so the red patch behind a pane stood still and then jumped
  as a light came nearer. A cell's tint is now the tints of the cells the straight line to the light's exact place came through,
  and a slab with a filter block in its box is worked out again when its light has moved a fifth of a block
  (`-Dcolorfullighting.slabSubCell=false`).
- Slabs are built on the mod's own worker thread, the most urgent and the smallest first
  (`-Dcolorfullighting.slabWorker=common` for one batch at a time on the common pool): a rebuild waited behind the batch before
  it and behind whatever else the pack had queued on that pool.
- The terrain's fragments draw the spot lights (`FragmentSpots`, `include/light_frag.glsl`;
  `-Dcolorfullighting.spotPerFragment=false` for the way before, `-Dcolorfullighting.spotPerFragmentMax`, 24, for the most
  spots a fragment takes). The Embeddium terrain shaders took every dynamic light at the vertices, and a cone is narrower than
  a block close to its light: a flashlight held close to the ground lit whole block corners or none (a jagged patch, or no
  patch at all from eye height), and a headlight's edge was stepped on one side. Embeddium's own terrain fragment shader is
  wrapped when its source is loaded: the vertices hand on their place, face and stored light, the fragment adds what the
  lightmap makes of the first `cl_dynFragSpots` lights at its own place, and the vertices leave those lights out. Point lights
  stay with the vertices.
- The slabs are bound at the texture units 24 to 26 (`-Dcolorfullighting.slabTextureUnit`; 12 to 14 before). Chunks Fade In
  keeps its sky texture at unit 13, so a terrain program held a 2D and a 3D sampler on one unit, which a driver may refuse to
  draw with: it drew while the vertices alone read the slabs, and no terrain at all once the fragments read them. A sampler of
  another mod on one of the slabs' units is now named in the log.
- A spot's slab is pruned from the light's exact place with a skirt of cells round its cone
  (`-Dcolorfullighting.slabExactPrune=false`); it was pruned from the cell's centre, up to 0.87 blocks from where the drawn
  cone starts.
- `-Dcolorfullighting.spotFragDebug=true` writes each terrain program's shader sources as the driver holds them to
  `cl-shaderdump` in the game folder, logs the units of its samplers and draws the fragments' inputs in bands.
- The block volume is handed to its readers whole (`VoxelOcclusion.Snapshot`, `pinLatest`/`unpin`). Two arrays took turns, and a
  slab build still reading one could see it filled for the next build. A provider of moving blocks can ask for the next build at
  once (`externalBlocksMoved`): Lively Lighting 0.6.5 does for a ship with lamps that moved or turned.
- A level that is left forgets its slabs and its block volume (`ClientEventListener.onLevelUnload`); the two methods had no caller.

2026-11-09 (2.10.2)
- The log no longer holds "Could not open GLSL import colorful_lighting:shaders/include/colored_light.glsl" for the shaders the game loads
  before its first resource reload. Those come from vanilla's own pack alone, where the include can't be found: the import failed with a
  warning each and the functions were put in after the failure. The functions are now written into such a shader directly, and imported
  as before once the game's resources hold the include (`Preprocessor.coloredLightImport`). Nothing drawn changes.
- Sixteen slabs for lights that reach between 15 and 24 blocks, eight before (`DynamicLightField.WANT_SLOTS`): Lively Lighting 0.6.4 draws
  up to sixteen merged lights, and one without a slab is drawn with nothing in its way.
- The mesh path (the lightmap renderer and shader packs) shows a point light above level 15: the open cells within a block of its cell
  per level above 15, three blocks at most, shine in full as well, round corners no further than a lamp's light goes
  (`DynamicLightsCompat.spreadEmitter`). The engine's light has sixteen levels, so until now the light's own cell gave 15 however strong
  the light was, and a merged light was no brighter there than one torch.
- The Embeddium shader loader no longer uses the `ResourceLocation` constructor Forge marks for removal.

2026-11-09 (2.10.1)
- A point light handed in through the provider API may have a level above 15, up to 31 (`DynamicLightSink.point`): the light itself
  stays at 15 for the blocks above it, a core of full light, and reaches that much farther, with the same falloff, slab and tint as
  any other light. Lively Lighting 0.6.3 draws lights near each other as one such merged light. Until now the level was cut to 15 where
  it was taken, on its way to the shaders and in the field, so such a light was an ordinary one of 15. The mesh path (the lightmap
  renderer and shader packs) still takes a level of at most 15. Spot lights are unchanged.

2026-11-09 (2.10.0)
- The dynamic light field holds where each light's light may go, not how bright (`DynamicLightField`): per light a cube of cells round its
  cell (a slab) with, per cell, the detour the way through the blocks takes over the straight way (0 in the open, unreachable behind a wall)
  and the tint picked up through filters; the shaders and the CPU samples compute each light's own falloff at the exact point (2.8.0's
  analytic light, round or diamond, a spot's cone, at the light's position for the frame) and scale it by the slab: 1 in the open, less by
  the detour's share of the reach left at the point, 0 where the way is too long. In the open the light is exactly the smooth, round light
  of 2.8.0 again, moving with its source every frame; a wall still stops it, a corner still wraps and fades, glass still tints. 2.9.x
  stored summed light levels per cell and interpolated them, which drew a faceted light that stepped and flickered as it moved. Slabs are
  rebuilt on a worker only when their light enters another cell, a spot turns more than 12 degrees or the blocks in their box change (hashed),
  never for a level, colour or sub-block move; kept in three 3D textures by reach (up to 15, 24 and 48 blocks; 32, 8 and 4 slabs; units 12,
  13 and 14, each a stack of slabs along z). `cl_dynSlab[]`, `cl_dynSlabTex0..2`, `cl_dynSlabSize`, `cl_dynSlabCount`, `cl_dynSlabs`; the two
  summed field textures, their blend and the 64 flag in `cl_dynPos.w` are gone. The grid's straight way is the 26-neighbour path with nothing
  in the way, so no slack is needed and the open is exactly open.
- A spot light no longer lights two blocks round itself whatever the direction (2.9.1): it put a blob of light on the ground round a kart and
  on a player holding a flashlight. What carries a light is lit through the carrier glow instead.
- The carrier glow (`DynamicLightsCompat.setEntityGlow`, `entityGlow`): the dynamic lighting mod says how bright an entity carrying a light is
  drawn at least (Camafy 0.1.85's `LightSource.glow`); `EntityRendererMixin` raises the entity's light to it, with nothing round it lit.

2026-11-09 (2.9.1)
- The field's levels are the light's own falloff by the straight distance (Manhattan for a diamond light, Euclidean for a round one or a spot)
  wherever the path through the blocks is no longer than that, and the path's length only where it is (round a corner): a round light is round
  again and the gradient is the analytic one, while a wall still stops it and a corner still wraps. The search is a shortest-path one over six
  neighbours (diamond) or twenty-six (round, spots; a diagonal step needs its face neighbours open), carrying the filter transmittance along the
  way. 2.9.0's grid-path levels made a round light octagonal and the gradient stepped.
- Two field textures in turn (units 12 and 13): the shaders blend the tick before's with the latest by the frame's partial tick
  (`cl_dynFieldPrev`, `cl_dynFieldBlend`), so a moving light slides instead of stepping twenty times a second.
- A spot light lights everything within two blocks of itself whatever the direction, fading to its cone a block farther (the field, the straight-line shaders and the CPU samples):
  a kart's own body under its headlights flickered between lit and unlit as the cells the lamps sat in changed with the sub-block position.

2026-11-09 (2.9.0)
- Dynamic light propagated through the blocks (`DynamicLightField`): the external providers' lights are spread cell by cell from each light the way
  the engine spreads a lamp's light (a level lost per block along the path, Manhattan for a diamond light, straight for a round one; stopped by opaque
  blocks, tinted per channel by filter blocks, round corners), on a worker whenever a light moved or changed or the blocks were rebuilt under it, and
  uploaded as a 96-block RGBA8 3D texture on unit 12 (the box that changed) for the terrain and Flywheel shaders, which sample it trilinearly half a
  block out along the face's normal, as smooth lighting reads the blocks in front of a face. This replaces 2.8.1's straight-line march from each
  vertex to each light, which gave hard shadows with jagged edges and black behind every wall where Minecraft's own light wraps a corner and fades
  (Lively Lighting's client module, the owner's screenshots of 2026-11-09). A spot light is propagated the same and cut by its cone where it is
  written; a light whose cell is outside the cube, and points outside the cube, keep the straight-line falloff (no occlusion). The CPU samples
  (entities, block entities, particles) read the same array.
- The block volume (`VoxelOcclusion`) is 96 blocks a side (was 64: at 30 blocks a wall could lie outside it and stop nothing), reads chunk sections
  directly, is rebuilt the tick after a block changes in view (`ClientLevelBlockChangeMixin`) as well as every half second, no longer goes to the GPU,
  and takes the blocks of moving structures from an external `BlockFiller` (`DynamicLightsCompat.setExternalBlockFiller`; Lively Lighting gives it
  Valkyrien Skies ships' blocks, so a ship's hull stops its lamps' light and its glass tints it).
- Terrain vertices carry their face in the packed light's top nibble (8 plus the face's 3D index; 15 still means coloured light with no face known),
  written by the smooth and flat light pipelines while a dynamic light provider is registered and no shader pack is active
  (`-Dcolorfullighting.meshFacing=false` leaves it off); the shaders turn it with the model-view rotation, so a ship's faces point where the ship does.
- `DynamicLightsCompat.blockLightColor(BlockState)`: the colour the mod gives a block state's light, for the providers' lamps.

2026-11-09 (2.8.1)
- Transmission for the shader path's dynamic lights (`VoxelOcclusion`, `cl_dynTransmit`): a 64-block cube round the camera of what each block lets
  through per channel (air 1, an opaque block 0, a filter block its filter colour), rebuilt on a worker every half second or when the camera
  leaves its middle, uploaded as an RGBA 3D texture on unit 12; the terrain and Flywheel shaders march from the lit point toward each light through
  it, one sample a block, multiplying the light by what they cross: a wall stops it, stained glass tints it (the first and last 0.6 blocks skipped:
  the lit face, the lamp's own block). The same volume filters the render-thread samples (entities, block entities, particles) on the CPU. On
  when a provider asks (`ShaderDynamicLights.setTransmission`; Lively Lighting's `client.transmission`).
- External point lights propagated by the engine (`setExternalPropagation`, on by default): while meshes bake dynamic light, a point light's cell is a
  virtual emitter of its colour at its level that the emission lookups and the chunk source discovery see, so its light spreads through air and stops
  at walls like a block's, is baked into meshes, sampled for entities and read by shader packs as block light; cells that change tell the engine as
  block light changes, and the straight-line path leaves those lights out. Spot lights stay on the straight-line path.
- `DynamicLightsCompat.of(LevelAccessor)` for the engine's lookups.
- Three faults in the shader path, found by looking at the picture (the samples the numeric tests read go through the CPU twin, which was
  right): the volume's texture unit is selected through `GlStateManager` (a raw `glActiveTexture` left vanilla's tracking believing another
  unit was active, and the atlas and the lightmap were then bound on the wrong units: whole sections with garbage light); the inverse view
  rotation is the transpose of the view rotation the game builds for the frame (pitch about X, then yaw plus 180 about Y), not the camera's
  quaternion (used as such, every vertex turned half round the camera and the lights landed behind the player: a torch lighting far
  rectangles and not the ground at the player's feet); and the volume uploads with a reset unpack state, in the layout the shaders sample
  (vanilla's 16-wide lightmap upload every frame leaves `GL_UNPACK_ROW_LENGTH` at 16, which scrambled the 64-wide slices and made the
  volume solid everywhere: a black field under a held torch).

2026-11-09 (2.8.0)
- Dynamic light provider API (`api.dynamic.DynamicLightProvider`, `DynamicLightSink`; `DynamicLightsCompat.registerExternalProvider`):
  another mod hands over its dynamic lights every tick (and every frame for the shader path) as point lights or spot cones with a
  level, colour, reach and falloff (vanilla's diamond or round). They are mixed into every light sample (entities, block entities,
  particles, chunk meshes with rebuilds round a light that moved), mirrored into the shipyards of Valkyrien Skies ships nearby so
  ship meshes take them, and Flywheel's coloured light buffer is recollected for the sections they touch. Lively Lighting 0.5.0's
  client module is the first provider.
- The shader path for those lights: `cl_dynamicLight` in the Embeddium terrain vertex shaders (`light.glsl`, through eye space
  and the inverse view rotation so a ship's vertices find their world place) and per fragment in Flywheel's `common.frag`, fed by
  three vec4 arrays uploaded per pass (`ShaderDynamicLights`; at most 64 lights, nearest the camera). When a provider asks for it
  (`setExternalMeshLighting(false)`) meshes stop baking the lights and the shaders draw them: a moving light rebuilds nothing. Off
  under an Oculus shader pack (the pack's programs replace the terrain shaders): the lights then go through the packed light the
  pack decodes, as before. The `CL_DYNAMIC_LIGHTS` define is compiled in once a provider is registered.

2026-11-08 (2.7.6)
- Fixed double chests drawn by Vanillin (Flywheel's visuals for vanilla's block entities) about a third darker than vanilla's renderer draws them, by day and under a torch alike (reported with Flywheel's indirect backend, the same under instancing; right with the backend off). A single chest was never affected.
  - Cause: Vanillin's chest visual combines a double chest's two lights the way vanilla's BrightnessCombiner does, through LightTexture.block, sky and pack, a round trip (rule 2 of CLPackedLight) that drops the sky nibble of a colored value: the chest's instance held f8809990 where the world said f88f9999, so it was lit by its block light alone. Vanilla's own combiner was already covered by BrightnessCombinerMixin.
  - Fix: while Vanillin's ChestVisual relights (updateLight), the colored answers LevelRenderer.getLightColor gives it are kept and the light it then sets on each instance is replaced by their colored max (compat/vanillin/VanillinChestLight; VanillinChestVisualMixin, applied only where Vanillin's ChestVisual exists; ColoredLitInstanceMixin on Flywheel's ColoredLitInstance.light). Nothing else is touched.
  - Also: the Flywheel shaders' composite of sky and colored block light (mixColoredLightWithLightMap in colored_light.glsl) was an older formula, sky + block x max(0.1, 1 - sky); it is now the in-world one (cl_blendLight, the vanilla core shaders, the DH LODs): the block light washed toward grey under bright sky and moonlight and scaled by max(0.1, 1 - sky) x 0.9 + 0.1. The night vibrancy reaches every Flywheel program as the uniform _cl_nightVibrancy, set on each bind (GlProgramMixin, FlywheelCompat.nightVibrancy). A Flywheel-drawn chest by a torch was about 5% darker than vanilla's by day on the first formula.
  - Measured in the pack harness (chest_flw, the main pack, the 3% patch on the closed chest with the backend on over the same with it off): a double chest by a torch in the rig 0.722 before, 1.008 after; a copy of the reporter's save (a double chest by a torch at 11000 ticks) 0.722 before, 1.022 after; the rig's single chest 1.013, as before.

2026-10-31 (2.7.5)
- Deep water fades from the vanilla sea into the Distant Horizons sea again, with no ring at the edge (reported: a clear separation between the render-distance water and the LOD water).
  - Cause: 2.7.3's dhLodsStartAtVanillaFade (for the moss carpets that showed as full LOD cubes) started every LOD outright at DH's vanilla-fade start, (near clip + 16) x 1.5, 98 blocks at 8 chunks on a 21:9 window. DH's vanilla fade measures each vanilla pixel by its own distance, and on deep water the vanilla sea floor seen through the surface is farther than the fade's end, so the moment an LOD sea was there the vanilla floor was swapped for it: a sharp ring 98 blocks out. DH's own program brings its LODs in dithered from its near clip (65 blocks) to the fade start, so that swap happened gradually; it isn't Distant Horizons' own look (builds before 2.7.3, and DH with neither Colorful Lighting nor Chunks Fade In nor Camafy's clip, showed no ring).
  - Fix: new client option dhTranslucentLodsKeepDhFade (on): with dhLodsStartAtVanillaFade on, translucent LODs (water, ice, glass: the LOD colour's alpha under 1) keep DH's own dithered start from its near clip, uniform uClWaterClip; opaque LODs (land, carpets' cells, the sea floor) still start at the vanilla fade, so moss carpets, slabs and iron bars stay clear. Camafy 0.1.67 does the same in Chunks Fade In's patch of DH's own program, which draws when colored LODs are off.
  - Tried first and dropped: dithering every LOD in across the vanilla fade (98 to 124 blocks): it left a brighter dithered band in place of the ring.
  - Checked (pack harness owner_water in a copy of a survival save, the main pack, 3440x1441, noon, 35 blocks up looking 12 degrees down over the sea): with the option off (2.7.4's start) the ring sits 98 blocks out, the water inside it a quarter darker than past it (mean colour 26,38,94 against 34,48,121 across the edge at its top); with it on there is no edge (34,48,120 against 34,49,122), the same as builds before 2.7.3 and as DH with neither clip; pictures owner_water-sea-base / -cl_water_off / -clips_off and the south view. dh_nonfull on 2.7.5 with Camafy 0.1.67: 0 at every distance to 96 blocks with this program bound and unbound (glass pane included), 0 "Uniform name not found", no problems.
- The colored LOD program was paying for another mod's mistake: Chunks Fade In set its per-LOD fade uniform at a place in DH's own program for every LOD buffer even while this program drew them, an OpenGL error each time (GL_INVALID_OPERATION "Invalid component count", 2.95 ms a frame in a survival world, 114 fps where 207 were possible). Fixed in Camafy 0.1.67 (render.chunksFadeInLodUniformsOnDhProgramOnly); nothing changed here for it.

2026-10-30 (2.7.4)
- No LOD is drawn while the camera is in water, lava or powder snow, or blind (reported: Distant Horizons' LODs showed through the water from under it, as if transparent).
  - Cause: with the camera in a fluid or blind, Distant Horizons 3.3 calls its fog state "special" and fogs every LOD whole in one fog colour, but the sky behind them isn't drawn in that colour, so the far shore stood out as pale or dark shapes through the water. Vanilla's own fog ends inside the LODs' start (96 blocks under water at most), so vanilla shows nothing of that land.
  - Fix: new client option dhLodsHiddenInFluids (on): while DH's fog state is special (DhFogState mirrors DH 3.3.3's own test: the camera's fog type, a modded fluid at the camera's block, Blindness or Darkness) the colored LOD program's clip is set past every LOD, so DH draws none and the frame shows what is behind them. Camafy 0.1.64 does the same for DH's own program (render.distantHorizonsLodsHiddenInFluids).
  - Checked (pack harness dh_underwater, the main pack, Java 21): LOD-only stone towers 200 to 390 blocks beyond a sheet of water, at noon, dusk and midnight. Above the water the towers show as before with the option on (dusk: 3% of their pixels changed, the noise there); under the water the option ran (39,230 LOD passes drawn empty by this program, 5,766 by Camafy's hook on DH's own) and the pictures match those with DH's renderer off. The reported pale LODs were not reproduced in that flat rig: DH's special fog drew the towers within 1 to 3 levels of the colour behind them even with the option off (an ocean beyond the render distance and the weather of the report remain untested). dh_nonfull still passes with this build (0 at every distance to 96 blocks, 0 "Uniform name not found").

2026-10-29
- Colored light on Distant Horizons' LODs is on by itself whenever Distant Horizons is installed (reported: the compat had to be turned on by hand with '/cl dh on' even with Distant Horizons installed).
  - Cause: the client option dhLodColor defaulted to false, and every existing config held that false whether anyone had chosen it or not, so only '/cl dh on' turned it on (and a modpack's shipped config kept it off for its players).
  - Fix: the option is now dhLodColorMode = AUTO | ON | OFF (default AUTO: on whenever Distant Horizons is installed). The old boolean isn't read (its false was indistinguishable from a choice), so an install that wants it off says so once more with '/cl dh off', which writes OFF; '/cl dh on' writes ON, '/cl dh auto' AUTO. '/cl dh' shows the config's mode beside the state. Without Distant Horizons nothing of it runs, as before.
- Colored LODs start where Distant Horizons starts fading the vanilla terrain into them, not at DH's near clip (reported: a moss carpet, iron bars or any other non-full block looked like a full block a little way off).
  - Cause: the override program (which replaces DH's own while colored LOD lighting is on) copied DH's near fade: no LOD inside nearClipPlane + 16, dithered in to 1.5 times that. DH's near clip is a near-plane distance (the overdraw distance divided by the frustum's corner factor, 2.07 on a 21:9 window) compared with the radial distance, so at 8 chunks LODs were drawn under the vanilla terrain from 65 to 128 blocks, and an LOD cell (a whole block) showed through the air of every carpet, slab, fence or pane there.
  - Fix: new client option dhLodsStartAtVanillaFade (on): no LOD before (nearClipPlane + 16) x 1.5, DH's vanilla-fade start, clipped outright (uniform uClHardClip), kept a chunk inside the render distance. Camafy 0.1.63 does the same for DH's own program.
  - Checked (pack harness dh_nonfull, the main pack, 3440x1441, 8 chunks): with this program bound a moss carpet's cell was 6% LOD at 72 blocks and 46% at 80; now 0 at every distance to 96 blocks, the same as before at 112 (DH's vanilla fade), no Distant Horizons renderer errors. With the new default the option dhLodColorMode replaced dhLodColor in the log as "Incorrect key dhLodColorMode was corrected from null to its default, AUTO" and the program bound at startup.

2026-10-18
- Fixed the portal particle black when spawned and fading to purple (a portal particle spawned by command was completely black, then faded to purple before its time was up, where it should always be purple). The same bug started flames black in the open by day and gave the enchantment table's glyphs, the glow particles and lava's embers a wrong sky light.
  - Cause: five vanilla particles override getLightColor with bit arithmetic on vanilla's packed light (j = light & 255 for the block byte, k = light >> 16 & 255 for the sky byte, one of them raised with age, then j | k << 16). PortalParticle and EnchantmentTableParticle raise the sky byte (purple at once under the open sky, a dark start only where there is no sky), FlameParticle and GlowParticle raise the block byte, LavaParticle sets it full. On a colored value (R8 G8 B8 + sky4 + the 0xF marker, CLPackedLight) that math takes the red channel for the block light and sky4 | (blue & 0xF) << 4 for the sky, drops the colour and the marker, and hands the renderer a vanilla-format value: by day with no block light a portal particle read sky 15 >> 4 = 0, so it was black, and only brightened as the age ramp reached 240 (sky 15). Measured in the pack harness (portal_light, forty portal and forty flame particles 3 blocks in front of the camera at noon in the open): with 2.7.1 the portal particles' light was vanilla block 0 / sky 0 for their first ticks and the flames' the same; without Colorful Lighting both read sky 15 from the first tick (brightness 1.0 at every tick). With 2.7.2 the portal particles read CL sky 15 from their first tick and the flames sky 15 with their own white light growing from 20 to 255 over their life, brightness 1.0 throughout; in a closed stone room (no sky, no block light) the portal particle keeps vanilla's own dark start, sky 0 rising to 14 over its life, and at midnight it reads sky 15 (dim only because the night sky is, True Darkness included). Screenshots portal_light-open_day.png: the flames orange from the first frame where 2.7.1 drew them black.
  - Fix: compat mixins on the five classes (mixin/compat/vanilla/PortalParticleLightMixin, EnchantmentTableParticleLightMixin, FlameParticleLightMixin, GlowParticleLightMixin, LavaParticleLightMixin; compat/vanilla/ParticleLightRamps): when the inherited value is colored, the same ramp is done in the colored format (the sky level raised by the same share, capped at 15, colour kept; or white light of the same level added to the colour), so a portal particle by day is purple from its first tick and a flame beside a red lamp stays reddish as it brightens. A vanilla-format value (colored lighting off, an unsupported level) is left to vanilla's own math. The reverse portal and nautilus particles inherit the fix. New client option particleLightRamps (on).

2026-10-13
- Fixed Distant Horizons LODs showing square patches lit brighter than the terrain round them at night (with DH 3.3.3: patches where the terrain is lit differently).
  - Cause: a remembered section (any 16-block section that held some colored light, a cave's glow lichen under a hill included) is drawn with the remembered light instead of DH's own, and that composite added the lightmap's darkest texel (block 0, sky 0) on top of the sky light, which already carries it. Every unlit part of such a section came out a step brighter than DH's own LODs beside it.
  - Fix: in colorful_dh_terrain.frag the block part leaves that floor out at level 0 and takes it back in as the level rises, so unlit parts of a remembered section match DH's LODs and bright light is as before.
- Fixed colored light losing some of its brightness when its chunk turns into an LOD.
  - Cause: (1) an LOD face lies on the boundary between its solid block (no light inside) and the lit air in front of it, and the colour volumes were read right on it, so linear filtering averaged the two texels: about half the level where a texel boundary meets the ground. (2) The LOD copy of the in-world composite was never updated when the in-world shaders changed in September (cl_blendLight: block light times (max(0.1, 1 - sky) x 0.9 + 0.1) with the night-vibrancy wash), so LODs drew block light weaker than the chunks they replace (about half by day).
  - Fix: with DH 3.2+ (every LOD quad carries its face's direction) each volume is read half a texel in front of the face; the LOD composite is now the in-world one, night vibrancy included (new uniforms uClHasNormals, uClHalfTexel, uClNightVibrancy, set in DhTerrainColorShaderProgram). DH 3.1.2 (no face directions) reads where it did.
  - Checked: the shader pair compiles and links in the pack harness (light_dh_shader). Not measured against a DH world in the harness.
- Fixed dragon's breath particles drawn red.
  - Cause: Amendments (dragon_breath_emissive, on by default) gives DragonBreathParticle a getLightColor that rebuilds the light as LightTexture.pack(max(LightTexture.block(light), 10), LightTexture.sky(light)), a round trip that turns a colored value into red light at level 10 with no sky light. Measured in the pack harness (light_dragon): every breath particle's light was R160 G0 B0, in the dark and beside a torch alike, against block 10 / sky 15 with colored lighting off.
  - Fix: compat mixin AmendmentsDragonBreathMixin (applied only with Amendments, after its override): a colored value keeps its colour, raised to at least white level 10 while Amendments' option is on (CLPackedLight.maxWithLightLevel); a vanilla value is left to Amendments.
- The errors Distant Horizons throws beside Chunks Fade In 3.0.25 ("Cannot invoke ClientLevelModule.isRendering() because this.clientside is null", 17,000 lines) are not Colorful Lighting's: Chunks Fade In 3.0.25's DH mixin (chunksfadein_dh.mixins.json, iris.IrisGlDhMetaRendererMixin_forge, callback avoidClear) fails its injection on DH 3.3.3 while DH binds its renderers, DH's render setup stops there and its client level never starts. Update Chunks Fade In to a build made for DH 3.3, or leave it out while DH is in.

2026-10-12
- Added colour helpers for Lively Lighting's client half (compat/dynamiclights/DynamicLightsCompat: entityLightColor, itemLightColor, lampLightColor, fireLightColor, defaultLightColor; public static, called by reflection). Lively Lighting now tells its clients what each of its light blocks stands for (the holder and the slot its light comes from, a lamp, a burning ship) and answers the block light colour API with that source's colour as these give it.
  - Why: Colorful Lighting guessed each such block's colour from the coloured entity within 8 blocks, or from the light stored round its mirror across a ship's boundary, and fell back to white when neither found anything: a fast holder's trail ("the trail loses the color"), a light fading after it was put away, two ships' lamps merged into one light, the dim edge of a world lamp's light on a ship.
  - No change to the engine, the API or the config. Without Lively Lighting's client half the guess is as before.
- Fixed smooth lighting looking blocky since 2026-10-10 ("visual separation between blocks with light ... almost like smooth lighting is off").
  - Cause: the 2026-10-10 fix for light glowing round blockers capped every neighbouring sample at one level above the face's own cell, corners included, and at nothing when that cell was dark. In open air a corner cell is legitimately two levels brighter (light reaches the face's cell from it through an edge cell), and a cell next to a dark one can hold a level. So each face cut its corners differently from the face beside it, and the shared corners no longer matched: a step at every block edge.
  - Fix: an edge cell counts up to one level above the face's own cell, a corner up to one level above the brighter of its two edge cells. Light loses at least a level per block in this engine, so in open air (and water, leaves) nothing is ever cut and smooth lighting is exactly as before 2026-10-10; only light that came round a blocker is.
  - Measured in pack harness cl_smooth (a sea lantern among pillars, a slab and stairs; a soul lantern; a redstone torch; glowstone under water; each seen straight down and at a slant): with the option on and off, and against the mod as it was before 2026-10-10 (commit ed73ad2), the pictures are identical except on the animated light blocks and the water surface. The beacon in tinted glass and the shut door keep the 2026-10-10 result (cl_glass: floor round the glass brightest pixel 1, 98 with the option off; cl_door: dark side 2, 25 with it off).
- Fixed Create's shafts, cogwheels and other Flywheel-drawn parts rendering black on Valkyrien Skies ships by day.
  - Cause: a block entity visual takes its instance light from LevelRenderer.getLightColor, which returns Colorful Lighting's packed colored light for a real level, and Colorful Lighting's copies of the instance shaders still read it as vanilla's packed light (the sky half came out negative and was dropped). In the world nobody saw it, because the fragment shader replaces that light with the colored light; Valkyrien Skies draws a ship's block entities as an embedded visual, whose branch keeps it: no sky light, and by day no block light either, so black.
  - Fix: the instance shaders (create rotating and scrolling, flywheel oriented and transformed) turn packed colored light back into vanilla's block and sky light first (cl_vanillaVertexLight in colored_light.glsl). Contraptions and world visuals are unchanged.
  - Measured in pack harness vsdraw_create (a shaft and cogwheel line on planks, on a static ship and in the world, by day): the ship's shaft was pure black before (luminance 0, the world's 107.9); now its luminance is 111.6 against 117.6 for the same line in the world (without Colorful Lighting: 99.9 and 101.6).
- Fixed a ConcurrentModificationException on the light propagator thread ("Colored light propagator pass failed"): the 2026-10-10 change that re-checks a block it can't read during a decrease called the main-thread bookkeeping (the view area and the ship regions) from the propagator thread. The re-check now runs on the main thread.
- The "queue is stalled with waiting chunks NEAR the player" warning says that the client holds no data for the missing neighbours. In the reported log it came after the game's main thread had stopped on a Valkyrien Skies error during a jump into Genesis space, so no more chunks could arrive.
- Removed the META-INF and data folders that had appeared in the project root (an unpacked Create jar from a test session, not part of the mod).

- Xaero's maps (the minimap's light color didn't update for things like filters, it didn't work with Xaero's World Map, and cave mode should show colored light too):
  - Fixed the minimap keeping an old color: a beacon walled in blue glass afterwards stayed white; one set inside the glass by command was blue and stayed blue after a glass was broken.
    - Cause: the minimap redraws a pixel only when its blocks or light levels change. Stained glass changes neither, only the light's color, and that color settles on the propagator thread after Xaero has already redrawn the chunk and marked it clean.
    - Fix: each minimap tile keeps the color its pixels were drawn with, and a pixel whose color changed is redrawn (XaeroMinimapWriterMixin, XaeroMinimapTileMixin); and when colored light changes in a chunk, Xaero's maps are told the way Xaero learns of block and light updates, by marking the chunk not clean (XaeroChunkRefresh, from ColoredLightEngine.onLightUpdate).
    - Measured in pack harness cl_xmini (the reported steps, the minimap's own colour 3 blocks from the beacon at night): beacon then blue glass #afb9c5 (white) before, #4b4fc5 (blue) now; glass then beacon by command blue both; one glass broken: blue on every side before, now white on the open side and blue-white elsewhere; colors switched off: white.
  - Added Xaero's World Map: the full-screen map at every zoom, and the minimap when it shows World Map chunks, which it does again (the 2026-10-10 switch that made the minimap draw its own chunks is gone: one place records the colors, and the minimap doesn't do the World Map's work a second time).
    - A color is read only when the World Map's own writer reads a pixel of a chunk loaded round the player (MapWriter.loadPixel), at the cell it took the pixel's block light from, once the colored light there is fully propagated; never for a place the player hasn't been. When the World Map draws the tile, the colors go into the region's record (RegionColors), saved beside its region files as <x>_<z>.clcolors (compressed; a region with one lit beacon is about 130 bytes). A place keeps the colors it had when the player was last there, and they change only when the World Map itself updates that chunk.
    - Measured in pack harness cl_xworld (the main pack, with XaeroPlus; a beacon in blue glass on white concrete at night, blue pixels round it on the full-screen map): 2577 with the colors, 12 without; the same after going 2000 blocks away and moving the map back (2672), and after the colors were written out, forgotten and read back from their file (2672); zoomed out to the second and third texture levels 70 and 13 (0 without); the minimap on World Map chunks 2508 (832 without, its blue frame). The World Map recorded the colors 9 to 11 ticks after the light settled; reading a color took 0.4-0.6 microseconds, on lit pixels only.
    - Drawn by Colorful Lighting's copies of Xaero's map shaders (assets/minecraft/shaders/core/colorful_lighting_xaero; with XaeroPlus a variant that keeps its transparent background): beside each World Map texture with colors in its footprint, a texture of the light's hue per pixel on unit 1 (WorldMapColorTextures) tints the block-lit part of the pixel with Xaero's own formula. Without colors the picture is exactly Xaero's. Zoomed out, the hue is averaged over the blocks a pixel covers.
  - Cave mode on the minimap's own chunks shows colored light (its brightness is block light; cl_xmini: a room lit by a beacon in red glass, #b85358 with the colors, white #b8c3cf before). With Xaero's "legible cave maps", which shade by depth, the color shows as a tint by the light's strength (not measured).
  - In cave mode the minimap draws its own chunks while xaeroMinimapColors is on, as it does when the World Map's cave layers can't be used, so caves show colored light with the World Map installed too; on the surface it stays on World Map chunks. The World Map's own cave layers stay grey-lit in singleplayer: there it builds them from the world save, and its writer wrote only a few cave chunks, all with block light 0 (pack harness cl_xcave), so no color is ever read for them.
    - Measured in cl_xcave (the main pack): on the surface the minimap shows World Map chunks; in a red-lit cave room it switches to its own chunks and shows red (#b85358, white before); with xaeroMinimapColors off it is back on World Map cave chunks; back on the surface, World Map chunks again. Frame rate in the cave 280 fps with its own chunks against 276 on World Map cave chunks (10 s each).
  - New client option xaeroWorldMapColors (default true). xaeroMinimapColors now covers only the chunks the minimap draws itself.

2026-10-10
- Fixed light glowing round blocks that stop it: a beacon walled in tinted glass lit the floor under and round the glass (the known issue of 2026-07-14), and a shut door lit a block-wide patch of the floor and walls on its dark side ("the light seeping under the door appears too bright and large of an area").
  - Cause: no light got through, but smooth lighting blends each corner of a face from the four cells round it and fills unlit ones with the dimmest lit one. One lit cell next to a dark one therefore lit the whole corner: the beacon's own cell (15) beside the dark glass, or the door's lit cell beside the dark room.
  - Fix: a neighbouring cell now counts only as far as its light could reach the face (SodiumAoFaceDataMixin with Embeddium, PackedLightData.blend with vanilla rendering). Emissive blocks keep their glow. (The bound as first written here was wrong and made smooth lighting blocky; see 2026-10-12.)
  - Recommendation for doors: the bright seep is removed rather than redrawn. Light through a shut door is now only what its filter lets through (filters.json, spruce 12 of 15). A faint one-level strip shows where the dark side has some light.
  - New client option smoothLightStaysBehindBlockers (default true; false gives the old blend).
- Fixed colored light going straight through tinted glass in the CamaCraft pack (a beacon covered on each face with tinted glass still lit the whole room behind the glass).
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
- Fixed a held light turning red next to a Valkyrien Skies ship (close to a ship, the light an enchanted item emits suddenly turned red), and one-block ships tinted orange or red at night. Lively Lighting places a held light both in the world and, projected, in the shipyard of a ship beside it; each of the two took its hue from the light stored round its mirror in the other space, so each other's light, and the dim red fringe of either, scaled to full, made both pure red (shiplight_dark: 15,0,0 for every light along a row of ships). A light block in the world now takes a hue from a ship only when a real ship lamp (a light-emitting block that isn't itself a dynamic light block, or one an API provider colours, such as a burning ship's light) stands within 3 blocks of its mirror, from the brightest open cell round that lamp whose brightest channel is at least 4 (DynamicLightsCompat.sampleShipMirrorHue / hueRoundLamp), so it never takes the hue of its own projection; a light block in a shipyard (a world lamp lighting the ship, another ship's lamps through their world projections) still takes the world's light where the ship is, ignoring cells dimmer than 4. shiplight_dark after: every light along the row white (#666666 at level 6).
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
