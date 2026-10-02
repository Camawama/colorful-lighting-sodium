Changelog

Planned
- port to all major Forge and NeoForge versions (1.20.2 through 26.2), Fabric later
- full Sodium support alongside Embeddium
- keep Valkyrien Skies compatibility on every version where VS exists
- add Sable / Create Aeronautics compatibility alongside Valkyrien Skies where possible

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
