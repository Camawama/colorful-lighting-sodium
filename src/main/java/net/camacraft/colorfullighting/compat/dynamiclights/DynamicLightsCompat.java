package net.camacraft.colorfullighting.compat.dynamiclights;

import net.camacraft.colorfullighting.ColorfulLighting;
import net.camacraft.colorfullighting.common.ColoredLightEngine;
import net.camacraft.colorfullighting.common.Config;
import net.camacraft.colorfullighting.common.accessors.BlockStateAccessor;
import net.camacraft.colorfullighting.common.accessors.LevelAccessor;
import net.camacraft.colorfullighting.common.accessors.mixin.LevelAttachments;
import net.camacraft.colorfullighting.common.api.ApiProviderRegistry;
import net.camacraft.colorfullighting.common.util.ColorRGB4;
import net.camacraft.colorfullighting.compat.sodium.SodiumCompat;
import net.camacraft.colorfullighting.compat.valkyrienskies.VsCompat;
import net.camacraft.colorfullighting.mixin.compat.sodium.SodiumWorldRendererAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.camacraft.colorfullighting.common.config.VariantList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Compatibility with dynamic lighting mods (Torcy, AtomicStryker's Dynamic Lights,
 * SodiumDynamicLights / "DynamicLights Reforged", Lively Lighting). Their light reaches the colored
 * pipeline two ways, and both are covered so the mechanism of the installed version never matters:
 * <ul>
 *   <li><b>Light blocks</b>: Lively Lighting (server-only) places minecraft:light blocks, which the
 *       colored engine already propagates; this class only supplies their color, inferred from the
 *       nearby entity that caused them. minecraft:light placed by any other mod and the
 *       dynamiclights:lit_* blocks shipped in AtomicStryker's 1.20.1 jar are colored the same way.
 *       Lively Lighting also projects light across the Valkyrien Skies world/ship boundary (held
 *       lights onto ships, ship lamps into the world, world lamps onto passing ships, ship to
 *       ship); those blocks resolve their color through ship-mirrored entity sources
 *       ({@link #addColorSource}) or the stored light at the block's mirrored position
 *       ({@link #sampleShipMirrorHue}).</li>
 *   <li><b>Client lighting</b>: the client-only mods light the world around luminous entities without
 *       persistent light data the engine could read, so the colored pipeline recreates their light:
 *       luminous entities (held/equipped/dropped light items, burning entities) are tracked each
 *       client tick and sampled with the LambDynamicLights falloff inside
 *       {@link ColoredLightEngine#sampleLightColor}, which every render path funnels through. Terrain
 *       around moved sources is remeshed by this class, so no help from the mods is needed. With
 *       SodiumDynamicLights its own tracked source list is used so its config keeps being respected,
 *       and SodiumDynamicLightsMixin suppresses its vanilla-format brightness boost, which would
 *       corrupt the packed colored coords.</li>
 * </ul>
 *
 * <p>One instance per level (a {@link LevelAttachments} slot, like the other compats): several
 * ClientLevels can be live at once (Immersive Portals), and a global snapshot let one dimension's
 * entity lights tint another's samples at overlapping coordinates — and the entity-id keyed caches
 * could collide across levels, since entity network ids are only unique per level. Mod detection
 * and config-derived data stay static. The per-tick snapshot arrays remain volatile: they are read
 * from chunk-build workers and the light-propagator thread.
 */
public final class DynamicLightsCompat {
    /** LambDynamicLights falloff radius; matches SodiumDynamicLights so color aligns with its brightness. */
    private static final double MAX_RADIUS = 7.75;
    private static final double MAX_RADIUS_SQUARED = MAX_RADIUS * MAX_RADIUS;
    /** Sections within this many blocks of a changed source get remeshed (light radius rounded up). */
    private static final int REBUILD_RANGE = 8;
    /** A tracked source must move this far (squared) before its surroundings are remeshed. */
    private static final double REBUILD_MOVE_THRESHOLD_SQUARED = 0.5 * 0.5;
    /**
     * A colored entity within this distance of a dynamic light block lends it its color (nearest
     * wins). Must cover how far Lively Lighting can put a light block from its cause: merged
     * clusters average entity positions (up to ~5.2 blocks off with the default 6-block merge
     * cell), and the in-wall anchor search can displace the block up to lightLevel/2 ≈ 7 blocks.
     * With the old 4.5 radius those blocks resolved no entity and fell back to white.
     */
    private static final double DYNAMIC_BLOCK_COLOR_RADIUS_SQUARED = 8.0 * 8.0;
    /**
     * A colored entity this close to a Valkyrien Skies ship's world bounds also gets a color source
     * at its shipyard-space mirror position. Lively Lighting anchors a held light's block IN the
     * shipyard (up to the light's level ≈ 15 blocks from the holder), so without the mirror those
     * anchors could never resolve the entity and fell back to white.
     */
    private static final double ENTITY_SHIP_MIRROR_RANGE = 16.0;
    /**
     * A dynamic light block this close to a ship's world bounds samples the ship's stored colored
     * light at its shipyard mirror (see {@link #sampleShipMirrorHue}). Ship-lamp projections land
     * inside the ship's world bounds, at most ~2 blocks of anchor displacement outside them.
     */
    private static final double BLOCK_SHIP_MIRROR_RANGE = 8.0;
    /**
     * A projected light block only takes a hue from the other space when a real lamp (a light-emitting block that
     * is not itself a dynamic light block) stands this close (blocks, per axis) to its mirrored position: the lamp
     * it re-emits. Without one, the stored light there is only the projection's own counterpart (a held light is
     * placed in the world AND in the shipyard of a ship beside it), and the two coloured each other.
     */
    private static final int MIRROR_LAMP_RADIUS = 3;
    /** How far (blocks, per axis) round that lamp the open cells are searched for the colour its light leaves with. */
    private static final int MIRROR_HUE_RADIUS = 4;
    /**
     * The least a stored colour's brightest channel must be to give a hue. Light loses one level per channel per
     * block, so a warm light's dim fringe keeps only red (15,13,9 at level 2 is 2,0,0): scaled to full, such a
     * sample made the projection pure red.
     */
    private static final int MIRROR_HUE_MIN = 4;

    /** Light-emitting blocks placed by dynamic lighting mods, colored by the entity that caused them. */
    private static final Set<ResourceLocation> DYNAMIC_LIGHT_BLOCK_IDS = Set.of(
            ResourceLocation.fromNamespaceAndPath("minecraft", "light"),
            ResourceLocation.fromNamespaceAndPath("dynamiclights", "lit_air"),
            ResourceLocation.fromNamespaceAndPath("dynamiclights", "lit_cave_air"),
            ResourceLocation.fromNamespaceAndPath("dynamiclights", "lit_water")
    );
    /**
     * Resolved to Block instances once at load complete. isDynamicLightBlock runs for every light
     * source the propagator visits, so it must not hash a ResourceLocation. Blocks belonging to
     * absent mods simply never resolve.
     */
    private static volatile Set<Block> dynamicLightBlocks = Collections.emptySet();

    /**
     * A dynamic light. {@code falloff}: 2 is the LambDynamicLights curve of SodiumDynamicLights' sources (fixed 7.75 radius),
     * 0 a level lost per block of Manhattan distance, 1 per block of straight distance (external providers, 2.8.0);
     * {@code range} the reach for 0 and 1; a spot has {@code outerCos} below 1 (the cosine of half its full angle),
     * {@code innerCos} where it is fully lit, and a direction. {@code level} may be fractional while a light fades.
     * A point light has no direction: its {@code dx} holds the blocks it takes to lose a level when that isn't 1
     * ({@link #stretch()}, 2.10.6), which goes to the shaders in the direction's place as it is.
     */
    record DynamicSource(double x, double y, double z, int luminance, ColorRGB4 color, float level, int falloff, float range,
                                 double dx, double dy, double dz, float outerCos, float innerCos, boolean external, long key, boolean propagatedFlag) {
        DynamicSource(double x, double y, double z, int luminance, ColorRGB4 color) {
            this(x, y, z, luminance, color, luminance, 2, (float) MAX_RADIUS, 0, 0, 0, 1f, 1f, false, 0L, false);
        }

        boolean isSpot() {
            return outerCos < 1f;
        }

        /** The blocks a point light takes to lose a level: 1 unless its provider said otherwise (a lamp on a scaled ship). */
        double stretch() {
            return outerCos >= 1f && dx > 0.0 ? dx : 1.0;
        }

        /** The same light, marked as propagated by the engine (left out of the straight-line path). */
        DynamicSource propagated() {
            return new DynamicSource(x, y, z, luminance, color, level, falloff, range, dx, dy, dz, outerCos, innerCos, external, key, true);
        }
    }
    private static final DynamicSource[] NO_SOURCES = new DynamicSource[0];
    // written on this level's tick, read from chunk-build worker threads
    private volatile DynamicSource[] entitySources = NO_SOURCES;

    // ---- external providers (2.8.0): other mods' dynamic lights, Lively Lighting's client module first ----
    private static final java.util.concurrent.CopyOnWriteArrayList<net.camacraft.colorfullighting.api.dynamic.DynamicLightProvider> EXTERNAL = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** Whether external lights are baked into chunk meshes (with rebuilds), or left to the terrain shaders. */
    private static volatile boolean externalMeshLighting = true;
    /** Last remesh anchor per external light (its owner key), so terrain updates as it moves. Client thread only. */
    private Map<Long, DynamicSource> externalAnchors = new HashMap<>();
    /** How far (blocks) an external light near a ship is mirrored into the ship's shipyard so the ship's meshes take it. */
    private static final double EXTERNAL_SHIP_MIRROR_RANGE = 16.0;
    /** Sections remeshed for external lights per tick, nearest the camera first; the rest wait (a lit ship's lamps cross a block every tick). */
    private static volatile int externalRebuildBudget = 48;
    /** Sections that waited: remeshed on later ticks under the budget. Client thread only. */
    private Set<Long> externalPending = new HashSet<>();
    /**
     * Whether external point lights are propagated by the engine as virtual emitters while meshes bake them (2.8.1): a light's
     * cell counts as a lamp of its colour, its light spreads through air and stops at walls like a block's, and is baked into
     * meshes, sampled for entities and read by shader packs as any block light. Spot lights stay on the straight-line path.
     */
    private static volatile boolean externalPropagation = true;
    /** The virtual emitters in force: cell -> colour at level, read by the engine's emission lookups on any thread. */
    private volatile Map<Long, ColorRGB4> virtualEmitters = Collections.emptyMap();

    /**
     * Registers who adds the blocks of moving structures (a Valkyrien Skies ship's, in the shipyard) to the block volume the
     * dynamic lights are propagated through (2.9.0); null for none. One at a time.
     */
    public static void setExternalBlockFiller(@Nullable VoxelOcclusion.BlockFiller filler) {
        VoxelOcclusion.setExternalFiller(filler);
    }

    /**
     * The block filler's structures moved enough to matter (a ship with lamps turned or went a part of a block): the block
     * volume is rebuilt on the next tick instead of within ten. Cheap to call often; one build runs at a time.
     *
     * @since 2.10.3
     */
    public static void externalBlocksMoved() {
        VoxelOcclusion.requestRebuild();
    }

    /** The colour Colorful Lighting gives a block state's light, as 0xRRGGBB (white when it has none). For the providers' lamps. */
    public static int blockLightColor(net.minecraft.world.level.block.state.BlockState state) {
        return rgb8(Config.getLightColor(state));
    }

    /** Whether terrain meshes carry each vertex's face in its light's top nibble (2.9.0); written on the client tick, read by the mesher threads. */
    private static volatile boolean facingInMeshes;

    /**
     * The bits a terrain vertex's packed light carries for its face (8 plus the face's 3D index, in the top nibble, where the
     * coloured format's marker 15 was), or 0 while meshes carry no faces: without a dynamic light provider, under an Oculus
     * shader pack, or with {@code -Dcolorfullighting.meshFacing=false}. The shaders sample the dynamic light field half a
     * block out along the face; a nibble of 15 still reads as coloured light with no face known.
     */
    public static int facingBits(net.minecraft.core.Direction face) {
        return facingInMeshes && face != null ? (8 | face.get3DDataValue()) << 28 : 0;
    }

    /**
     * A point light may lose a level over more or less than a block (2.10.6, {@code DynamicLightSink.point} with
     * {@code blocksPerLevel}); {@code -Dcolorfullighting.pointStretch=false} takes every point light at a level per block.
     */
    private static final boolean POINT_STRETCH = !"false".equalsIgnoreCase(System.getProperty("colorfullighting.pointStretch"));
    private static final float MIN_STRETCH = 1f / 32f, MAX_STRETCH = 32f;
    private static final boolean MESH_FACING_ALLOWED = !"false".equalsIgnoreCase(System.getProperty("colorfullighting.meshFacing"));

    public static void setExternalPropagation(boolean value) {
        externalPropagation = value;
    }

    public static boolean externalPropagation() {
        return externalPropagation;
    }

    /** The virtual emitter at a cell of this level, or null: the engine's emission lookups ask. */
    @Nullable
    public ColorRGB4 virtualEmitterAt(BlockPos pos) {
        Map<Long, ColorRGB4> m = virtualEmitters;
        return m.isEmpty() ? null : m.get(pos.asLong());
    }

    /** The virtual emitters of a level in a chunk, for the engine's source discovery. */
    public void forEachVirtualEmitterIn(net.minecraft.world.level.ChunkPos chunk, java.util.function.Consumer<BlockPos> out) {
        Map<Long, ColorRGB4> m = virtualEmitters;
        if (m.isEmpty()) return;
        for (long key : m.keySet()) {
            BlockPos p = BlockPos.of(key);
            if ((p.getX() >> 4) == chunk.x && (p.getZ() >> 4) == chunk.z) out.accept(p);
        }
    }

    @Nullable
    public static DynamicLightsCompat of(LevelAccessor level) {
        net.minecraft.world.level.Level mc = level.getLevel();
        return mc instanceof LevelAttachments a ? a.colorfullighting$getDynamicLights() : null;
    }

    /** The remesh budget per tick for external lights (Lively Lighting's client.lightmap_rebuilds_per_tick). */
    public static void setExternalRebuildBudget(int sectionsPerTick) {
        externalRebuildBudget = Math.max(1, sectionsPerTick);
    }

    /** Registers a provider of dynamic lights; asked every tick of every client level, and every frame by the shader path. */
    public static void registerExternalProvider(net.camacraft.colorfullighting.api.dynamic.DynamicLightProvider provider) {
        if (provider != null && !EXTERNAL.contains(provider)) EXTERNAL.add(provider);
    }

    public static boolean hasExternalProviders() {
        return !EXTERNAL.isEmpty();
    }

    /** Whether external lights are applied to chunk meshes (true) or only to entities, block entities and particles (false). */
    public static void setExternalMeshLighting(boolean meshes) {
        externalMeshLighting = meshes;
    }

    public static boolean externalMeshLighting() {
        return externalMeshLighting;
    }

    /** Whether the terrain shaders can draw dynamic lights now: Embeddium's pipeline with our shaders, no shader pack. */
    public static boolean shaderDynamicLightsAvailable() {
        return net.camacraft.colorfullighting.compat.dynamiclights.ShaderDynamicLights.available();
    }

    /** Collects every external provider's lights at a moment of the frame (1 = the tick's positions). */
    static void collectExternal(ClientLevel level, float partialTick, java.util.function.Consumer<DynamicSource> out) {
        if (EXTERNAL.isEmpty()) return;
        net.camacraft.colorfullighting.api.dynamic.DynamicLightSink sink = new net.camacraft.colorfullighting.api.dynamic.DynamicLightSink() {
            @Override
            public void point(long ownerKey, double x, double y, double z, float lvl, int rgb, int falloff, float range) {
                point(ownerKey, x, y, z, lvl, rgb, falloff, range, 1f);
            }

            @Override
            public void point(long ownerKey, double x, double y, double z, float lvl, int rgb, int falloff, float range, float blocksPerLevel) {
                if (lvl <= 0f) return;
                // the blocks a level lasts (2.10.6, a lamp on a scaled ship); 1 is stored as no stretch at all
                float stretch = POINT_STRETCH && blocksPerLevel > 0f && !Float.isInfinite(blocksPerLevel) ? Math.max(MIN_STRETCH, Math.min(MAX_STRETCH, blocksPerLevel)) : 1f;
                if (Math.abs(stretch - 1f) < 1.0e-3f) stretch = 1f;
                // a level above 15 (2.10.1, a merged light): full light for the blocks above 15 and that much more reach
                out.accept(new DynamicSource(x, y, z, Math.min(15, Math.max(1, Math.round(lvl))), colorOf(rgb), Math.min(ShaderDynamicLights.MAX_LEVEL, lvl), falloff == 1 ? 1 : 0,
                        Math.max(1f, range > 0 ? range : lvl * stretch), stretch == 1f ? 0 : stretch, 0, 0, 1f, 1f, true, ownerKey, false));
            }

            @Override
            public void spot(long ownerKey, double x, double y, double z, double dirX, double dirY, double dirZ, float outerDegrees, float innerDegrees,
                             float lvl, int rgb, float range) {
                if (lvl <= 0f) return;
                double len = Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
                if (len < 1.0e-6) return;
                float outer = (float) Math.cos(Math.toRadians(Math.max(1f, outerDegrees) * 0.5));
                float inner = (float) Math.cos(Math.toRadians(Math.max(0.5f, Math.min(innerDegrees, outerDegrees)) * 0.5));
                out.accept(new DynamicSource(x, y, z, Math.min(15, Math.max(1, Math.round(lvl))), colorOf(rgb), Math.min(15f, lvl), 1,
                        Math.max(1f, range > 0 ? range : 32f), dirX / len, dirY / len, dirZ / len, outer, Math.max(outer, inner), true, ownerKey, false));
            }
        };
        for (net.camacraft.colorfullighting.api.dynamic.DynamicLightProvider provider : EXTERNAL) {
            try {
                provider.collect(level, partialTick, sink);
            } catch (RuntimeException e) {
                ColorfulLighting.LOGGER.warn("A dynamic light provider failed; its lights are skipped this tick", e);
            }
        }
    }

    private static ColorRGB4 colorOf(int rgb) {
        if (rgb < 0) return Config.defaultColor;
        if (rgb == net.camacraft.colorfullighting.api.dynamic.DynamicLightSink.BLACKLIGHT) return ColorRGB4.UV;
        if ((rgb & net.camacraft.colorfullighting.api.dynamic.DynamicLightSink.ABSORB) != 0) return ColorRGB4.absorbing((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
        return ColorRGB4.fromRGB8((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
    }

    /**
     * One external light at a moment of the frame, for the shader path: its key (its slab in the {@link DynamicLightField}) and
     * x, y, z, level, r, g, b (0..1), range, direction x, y, z, the cosines of the outer and inner half angles, the falloff,
     * and 1 for a blacklight (2.11.0).
     */
    public record FrameLight(long key, float[] v) {}

    /** The external lights at a moment of the frame, for the shader path: positions, colours, shapes. */
    public static List<FrameLight> externalLightsForFrame(ClientLevel level, float partialTick) {
        List<FrameLight> out = new ArrayList<>();
        // one gathering a frame, shared with the entities' exact light (2.10.3)
        for (DynamicSource s : frameSources(level, partialTick)) {
            out.add(new FrameLight(s.key, new float[]{(float) s.x, (float) s.y, (float) s.z, s.level, s.color.red4 / 15f,
                    s.color.green4 / 15f, s.color.blue4 / 15f, s.range, (float) s.dx, (float) s.dy, (float) s.dz, s.outerCos, s.innerCos, s.falloff,
                    s.color == ColorRGB4.UV ? 1f : 0f, s.color.absorbs ? 1f : 0f}));
        }
        return out;
    }

    private record ColorSource(double x, double y, double z, ColorRGB4 color) {}
    private static final ColorSource[] NO_COLOR_SOURCES = new ColorSource[0];
    // colored entities snapshotted each tick, read from the light-propagator thread to color
    // dynamic light blocks without ever touching the live (non-thread-safe) entity lists off-thread
    private volatile ColorSource[] blockColorSources = NO_COLOR_SOURCES;
    // set once dynamic light blocks are known to exist, so vanilla worlds skip the per-tick color scan
    private volatile boolean trackBlockColors;

    /**
     * Entity NBT serialized at most once per tick, and only for entity types whose config has
     * an NBT rule. resolveEntityColor runs for every rendered entity, sometimes twice a tick, and
     * Entity#saveWithoutId is far too expensive to repeat. Keyed by entity network id, which is
     * only unique within one level — a reason this cache must not be global.
     */
    private final Map<Integer, CompoundTag> entityNbtCache = new HashMap<>();
    private static final CompoundTag NO_NBT = new CompoundTag();
    private static boolean loggedEntitySaveFailure = false;

    /**
     * Dynamic light blocks whose color did NOT come from a nearby entity, mapped to the packed hue
     * that actually resolved ({@code -1} when nothing did and the block fell back to white). A
     * block's color is resolved only when it propagates, which races ship-region propagation on
     * world join — the mirror sample reads nothing yet and the block would stick white forever.
     * clientTick rechecks these and re-propagates a block when the hue that would resolve now
     * differs. Written from the light-propagator thread, iterated on the client thread.
     */
    private final Map<BlockPos, Integer> shipMirrorHuesUsed = new ConcurrentHashMap<>();
    private static final int SHIP_HUE_RECHECK_INTERVAL_TICKS = 10;
    private int recheckCounter;

    private static SodiumDynamicLightsHook sdlHook;
    private static boolean trackEntities;
    /** Last remesh anchor per tracked entity id, so terrain updates as sources move. Client thread only. */
    private Map<Integer, DynamicSource> trackedAnchors = new HashMap<>();

    public static void init() {
        Set<Block> resolved = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (ResourceLocation id : DYNAMIC_LIGHT_BLOCK_IDS) {
            Block block = ForgeRegistries.BLOCKS.getValue(id);
            if (block != null && block != Blocks.AIR) resolved.add(block);
        }
        dynamicLightBlocks = resolved;

        if (ModList.get().isLoaded("torcy")) {
            trackEntities = true;
            ColorfulLighting.LOGGER.info("Torcy detected!");
        }
        if (ModList.get().isLoaded("dynamiclights")) {
            trackEntities = true;
            ColorfulLighting.LOGGER.info("AtomicStryker's Dynamic Lights detected!");
        }
        if (ModList.get().isLoaded("sodiumdynamiclights")) {
            sdlHook = SodiumDynamicLightsHook.tryCreate();
            if (sdlHook != null) {
                ColorfulLighting.LOGGER.info("SodiumDynamicLights (DynamicLights Reforged) detected!");
            } else {
                // fall back to our own tracking so its dynamic light still gets colored
                trackEntities = true;
                ColorfulLighting.LOGGER.warn("SodiumDynamicLights detected, but hooking its light sources failed; falling back to own tracking");
            }
        }
    }

    /**
     * Refreshes this level's dynamic light sources for the tick: snapshots SodiumDynamicLights'
     * tracked sources and/or scans luminous entities, into an immutable array so light sampling on
     * chunk-build threads never touches live collections. Called once per tick of this level.
     */
    public void clientTick(ClientLevel level) {
        facingInMeshes = MESH_FACING_ALLOWED && !EXTERNAL.isEmpty() && !ShaderDynamicLights.shaderPackActive();
	    ColoredLightEngine engine = ((LevelAttachments) level).colorfullighting$getEngine();
	    if (engine == null || !ColoredLightEngine.isEnabled()) {
            entitySources = NO_SOURCES;
            blockColorSources = NO_COLOR_SOURCES;
            trackedAnchors = new HashMap<>();
            shipMirrorHuesUsed.clear();
            return;
        }

        recheckShipMirrorHues(engine, level);

        if (ShaderDynamicLights.transmission()) VoxelOcclusion.tick(level);
        if (sdlHook == null && !trackEntities && !trackBlockColors && EXTERNAL.isEmpty()) return;

        entityNbtCache.clear();

        List<DynamicSource> sources = new ArrayList<>();
        if (sdlHook != null) {
            for (Object source : sdlHook.getSources()) {
                int luminance = sdlHook.getLuminance(source);
                if (luminance <= 0) continue;
                sources.add(new DynamicSource(
                        sdlHook.getX(source), sdlHook.getY(source), sdlHook.getZ(source),
                        Math.min(luminance, 15),
                        resolveSourceColor(source)
                ));
            }
        }
        // colored entities are only needed to color dynamic light blocks; skip the scan otherwise
        List<ColorSource> colors = (trackEntities || trackBlockColors) ? new ArrayList<>() : null;
        if (trackEntities) {
            collectTrackedEntities(level, sources, colors);
        } else if (colors != null) {
            collectBlockColors(level, colors);
        }
        if (!EXTERNAL.isEmpty()) collectExternalSources(level, sources);
        entitySources = sources.isEmpty() ? NO_SOURCES : sources.toArray(NO_SOURCES);
        blockColorSources = (colors == null || colors.isEmpty()) ? NO_COLOR_SOURCES : colors.toArray(NO_COLOR_SOURCES);
        // the propagated field for the shader path (2.9.0): the external lights at their tick positions, through the blocks
        if (ShaderDynamicLights.transmission() && !externalMeshLighting) DynamicLightField.tick(level, fieldLights(entitySources));
    }

    /** The external lights as the field takes them (the straight-line copies the lightmap path mirrors into shipyards are left out by the cube). */
    private static List<DynamicLightField.Light> fieldLights(DynamicSource[] sources) {
        List<DynamicLightField.Light> out = new ArrayList<>();
        for (DynamicSource s : sources) {
            if (!s.external || s.level <= 0f) continue;
            out.add(new DynamicLightField.Light(s.key, s.x, s.y, s.z, Math.min(ShaderDynamicLights.MAX_LEVEL, s.level), s.falloff, Math.max(1f, s.range), s.isSpot(), s.dx, s.dy, s.dz, s.outerCos));
        }
        return out;
    }

    /** Who says how bright an entity carrying a light is drawn at least (the provider's carrier glow); 0 for nothing. */
    private static volatile java.util.function.ToIntFunction<Entity> entityGlow;

    /**
     * Sets who answers {@link #entityGlow}: the dynamic lighting mod, from its lights' carriers (a kart under its headlights is drawn
     * at least that bright, with nothing round it lit).
     *
     * @since 2.10.0
     */
    public static void setEntityGlow(@Nullable java.util.function.ToIntFunction<Entity> glow) {
        entityGlow = glow;
    }

    /** The block light (0..15) an entity is drawn with at least, from the lights it carries; 0 for none. Render thread. */
    public static int entityGlow(Entity entity) {
        java.util.function.ToIntFunction<Entity> g = entityGlow;
        if (g == null) return 0;
        try {
            return Math.max(0, Math.min(15, g.applyAsInt(entity)));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Who says which lights an entity carries itself (their keys), so its own beam never lights it; null for none. */
    private static volatile java.util.function.Function<Entity, long[]> entityOwnLights;
    /** Who says how bright and in which colour an entity carrying a light is drawn at least: {@code level << 24 | 0xRRGGBB}, 0 for nothing. */
    private static volatile java.util.function.ToIntFunction<Entity> entityGlowColor;

    /**
     * Sets who answers which lights are an entity's own (the keys the provider gives them): a flashlight's holder, a kart under its
     * headlights and whoever rides it. An entity's exact light ({@link #exactEntityLight}) leaves those out; what lights the carrier
     * is its glow.
     *
     * @since 2.10.3
     */
    public static void setEntityOwnLights(@Nullable java.util.function.Function<Entity, long[]> own) {
        entityOwnLights = own;
    }

    /**
     * Sets who answers {@link #entityGlowColor}: as {@link #setEntityGlow}, with the colour of the light the carrier is lit by.
     *
     * @since 2.10.3
     */
    public static void setEntityGlowColor(@Nullable java.util.function.ToIntFunction<Entity> glow) {
        entityGlowColor = glow;
    }

    /** The glow an entity is drawn with at least: {@code level (0..15) << 24 | 0xRRGGBB}; 0 for none. White when only a level is known. */
    public static int entityGlowColor(Entity entity) {
        java.util.function.ToIntFunction<Entity> g = entityGlowColor;
        if (g == null) {
            int level = entityGlow(entity);
            return level <= 0 ? 0 : level << 24 | 0xFFFFFF;
        }
        try {
            int packed = g.applyAsInt(entity);
            int level = Math.max(0, Math.min(15, packed >>> 24));
            return level == 0 ? 0 : level << 24 | (packed & 0xFFFFFF);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    // ---- live samples off the render thread (2.10.3) ----

    /** Switch: {@code -Dcolorfullighting.liveSamplesOffThread=false} leaves worker threads' samples without the drawn lights, as 2.10.2. */
    private static final boolean LIVE_OFF_THREAD = !"false".equalsIgnoreCase(System.getProperty("colorfullighting.liveSamplesOffThread", "true"));
    private static final boolean DEBUG_LIVE = Boolean.getBoolean("colorfullighting.debugLiveSamples");

    private static final class LiveSample {
        int depth;
        final float[] vis = new float[3];
    }

    private static final ThreadLocal<LiveSample> LIVE = ThreadLocal.withInitial(LiveSample::new);
    /** How many threads are inside a live sample now, so mesh builders skip the thread-local while there is none. */
    private static final java.util.concurrent.atomic.AtomicInteger liveSamplers = new java.util.concurrent.atomic.AtomicInteger();
    private static final Map<String, java.util.concurrent.atomic.AtomicLong> liveThreads = new ConcurrentHashMap<>();

    /**
     * Marks the samples this thread takes until {@link #endLiveSample} as live ones: the light of something drawn this frame (a
     * particle ticked on a worker thread), which takes the lights the shaders draw as the render thread's samples do. Without it a
     * sample off the render thread is a chunk mesh's and leaves them out: particles refreshed on AsyncParticles' workers went black
     * in a dynamic light. False on the render thread and with the switch off (nothing to end then).
     */
    public static boolean beginLiveSample() {
        if (!LIVE_OFF_THREAD || com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()) return false;
        LIVE.get().depth++;
        liveSamplers.incrementAndGet();
        debugNote("live");
        return true;
    }

    /** Counts a happening by its name and thread while {@code -Dcolorfullighting.debugLiveSamples=true}. */
    public static void debugNote(String what) {
        if (!DEBUG_LIVE) return;
        String name = what + "@" + Thread.currentThread().getName().replaceAll("[0-9]+", "#");
        liveThreads.computeIfAbsent(name, k -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
    }

    public static void endLiveSample() {
        LiveSample live = LIVE.get();
        if (live.depth > 0) live.depth--;
        liveSamplers.decrementAndGet();
    }

    /** For tests ({@code -Dcolorfullighting.debugLiveSamples=true}): what was counted, as {@code what@thread} with digits as #. */
    public static Map<String, Long> liveSampleThreads() {
        Map<String, Long> out = new HashMap<>();
        liveThreads.forEach((name, n) -> out.put(name, n.get()));
        return out;
    }

    // ---- an entity's exact light (2.10.3) ----

    /** Switch: {@code -Dcolorfullighting.entityExactLights=false} lights entities from the eight cells round them, as 2.10.2. */
    private static final boolean ENTITY_EXACT = !"false".equalsIgnoreCase(System.getProperty("colorfullighting.entityExactLights", "true"));
    /** What sits this near a spot light's origin is its carrier or beside it: lit by the carrier's glow, never by the cone's edge. */
    private static final double SPOT_NEAR = 0.75;
    /** Render thread: the stored sample being taken is an entity's, whose drawn lights are added exactly afterwards. */
    private static boolean entitySampling;
    private static DynamicSource[] frameSources = NO_SOURCES;
    private static long frameSourcesFrame = -1;
    private static float frameSourcesPartialTick = Float.NaN;
    private static ClientLevel frameSourcesLevel;
    private static final float[] EXACT_VIS = new float[3];
    private static final float[] EXACT_ACC = new float[3];
    private static final float[] EXACT_DARK = new float[3];
    /** The sky light levels the absorbing lights took at the entity last sampled by {@link #exactEntityLight} (render thread). */
    private static int exactSkyTaken;

    public static int exactSkyTaken() {
        return exactSkyTaken;
    }

    /** The providers' lights at a moment of the frame, at their world positions; gathered once per frame. Render thread. */
    private static DynamicSource[] frameSources(ClientLevel level, float partialTick) {
        long frame = ShaderDynamicLights.frame();
        if (frame != frameSourcesFrame || level != frameSourcesLevel || partialTick != frameSourcesPartialTick) {
            List<DynamicSource> list = new ArrayList<>();
            collectExternal(level, partialTick, list::add);
            frameSources = list.isEmpty() ? NO_SOURCES : list.toArray(new DynamicSource[0]);
            frameSourcesFrame = frame;
            frameSourcesLevel = level;
            frameSourcesPartialTick = partialTick;
        }
        return frameSources;
    }

    /** Forgets the frame's lights (the level went away). */
    public static void forgetFrameSources() {
        frameSources = NO_SOURCES;
        frameSourcesLevel = null;
        frameSourcesFrame = -1;
    }

    /**
     * Begins an entity's stored light sample: the drawn lights are left out of it and added by {@link #exactEntityLight}. False off
     * the render thread, with the switch off or with no provider (nothing to end then).
     */
    public static boolean beginEntitySample() {
        if (!ENTITY_EXACT || EXTERNAL.isEmpty() || !com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()) return false;
        entitySampling = true;
        return true;
    }

    public static void endEntitySample() {
        entitySampling = false;
    }

    /**
     * An entity's light with the drawn lights added where the entity is (2.10.3). Its stored sample blends the eight cells round its
     * probe, and each cell took a light as it is at the cell's centre: a narrow cone covered one of them now and then, so a
     * flashlight's holder turning slowly, or a kart under its headlights, flickered. Here every light is worked out at the probe
     * itself and at the body's middle (the brighter counts), in its colour, times what its slab leaves of it there. The entity's
     * own lights are left out ({@link #setEntityOwnLights}), and a spot never lights what sits at its origin.
     */
    public static net.camacraft.colorfullighting.common.util.ColorRGB8 exactEntityLight(Entity entity, float partialTick, net.minecraft.world.phys.Vec3 probe,
                                                                                         net.camacraft.colorfullighting.common.util.ColorRGB8 stored) {
        if (!(entity.level() instanceof ClientLevel level)) return stored;
        DynamicSource[] lights = frameSources(level, partialTick);
        if (lights.length == 0) return stored;
        long[] own = null;
        java.util.function.Function<Entity, long[]> owner = entityOwnLights;
        if (owner != null) {
            try {
                own = owner.apply(entity);
            } catch (RuntimeException ignored) {
            }
        }
        net.minecraft.world.phys.Vec3 feet = entity.getPosition(partialTick);
        double px = probe.x, py = probe.y, pz = probe.z;
        double mx = feet.x, my = feet.y + entity.getBbHeight() * 0.5, mz = feet.z;
        // a shipyard resident (an item frame on a ship) is lit where it is in the world
        LevelAttachments attachments = (LevelAttachments) level;
        if (VsCompat.hasShipMirrors(attachments)) {
            double[] w = VsCompat.shipyardToWorld(attachments, px, py, pz);
            if (w != null) {
                double[] m = VsCompat.shipyardToWorld(attachments, mx, my, mz);
                px = w[0];
                py = w[1];
                pz = w[2];
                if (m != null) {
                    mx = m[0];
                    my = m[1];
                    mz = m[2];
                } else {
                    mx = px;
                    my = py;
                    mz = pz;
                }
            }
        }
        // points the engine propagates are in the stored sample already
        boolean pointsStored = externalMeshLighting && externalPropagation;
        boolean slabs = !externalMeshLighting && ShaderDynamicLights.transmission() && DynamicLightField.active();
        float[] acc = EXACT_ACC;
        acc[0] = stored.red;
        acc[1] = stored.green;
        acc[2] = stored.blue;
        float[] dark = EXACT_DARK;
        dark[0] = dark[1] = dark[2] = 0f;
        for (DynamicSource s : lights) {
            if (s.color.absorbs) {
                // a light that absorbs (2.12.0): what it takes at the entity's middle
                addExact(s, mx, my, mz, slabs, dark);
                continue;
            }
            if (pointsStored && !s.isSpot() && s.level >= 1f) continue;
            if (own != null && owns(own, s.key)) continue;
            addExact(s, px, py, pz, slabs, acc);
            addExact(s, mx, my, mz, slabs, acc);
        }
        acc[0] = Math.max(0f, acc[0] - dark[0]);
        acc[1] = Math.max(0f, acc[1] - dark[1]);
        acc[2] = Math.max(0f, acc[2] - dark[2]);
        exactSkyTaken = Math.round(Math.max(dark[0], Math.max(dark[1], dark[2])) / 17f);
        int r = Math.min(255, Math.round(acc[0])), g = Math.min(255, Math.round(acc[1])), b = Math.min(255, Math.round(acc[2]));
        if (r == stored.red && g == stored.green && b == stored.blue) return stored;
        return net.camacraft.colorfullighting.common.util.ColorRGB8.fromRGB8(r, g, b);
    }

    private static boolean owns(long[] own, long key) {
        for (long k : own) if (k == key) return true;
        return false;
    }

    /** One light at one exact place, max'd per channel into {@code acc} (0..255). */
    private static void addExact(DynamicSource s, double x, double y, double z, boolean slabs, float[] acc) {
        double dx = x - s.x, dy = y - s.y, dz = z - s.z;
        double d2 = dx * dx + dy * dy + dz * dz;
        if (d2 > (double) s.range * s.range) return;
        double level, remaining;
        if (s.isSpot()) {
            double d = Math.sqrt(d2);
            if (d < SPOT_NEAR) return;
            remaining = s.range - d;
            double cos = (dx * s.dx + dy * s.dy + dz * s.dz) / d;
            double cone = cos <= s.outerCos ? 0.0 : s.innerCos <= s.outerCos ? 1.0 : Math.min(1.0, (cos - s.outerCos) / (s.innerCos - s.outerCos));
            if (cone <= 0.0) return;
            level = s.level * (remaining / s.range) * cone;
        } else {
            double stretch = s.stretch();
            remaining = s.level * stretch - (s.falloff == 1 ? Math.sqrt(d2) : Math.abs(dx) + Math.abs(dy) + Math.abs(dz));
            level = remaining / stretch;
        }
        if (level <= 0.0) return;
        float scale = (float) (Math.min(15.0, level) / 15.0) * 17f;
        float vr = 1f, vg = 1f, vb = 1f;
        float[] vis = EXACT_VIS;
        if (slabs && DynamicLightField.visibility(s.key, (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z), remaining, vis)) {
            vr = vis[0];
            vg = vis[1];
            vb = vis[2];
        }
        acc[0] = Math.max(acc[0], s.color.red4 * scale * vr);
        acc[1] = Math.max(acc[1], s.color.green4 * scale * vg);
        acc[2] = Math.max(acc[2], s.color.blue4 * scale * vb);
    }

    /**
     * Scans the client world for luminous entities and schedules remeshes around the ones that
     * appeared, changed, moved or vanished — client-lighting mods leave no light data or chunk
     * updates behind for the engine to react to, so this drives both the light and its updates.
     */
    private void collectTrackedEntities(ClientLevel level, List<DynamicSource> sources, @Nullable List<ColorSource> colors) {
        Map<Integer, DynamicSource> anchors = new HashMap<>();
        Set<Long> sectionsToRebuild = null;

        for (Entity entity : level.entitiesForRendering()) {
            if (entity.isSpectator()) continue;

            ColorRGB4 resolved = resolveEntityColor(entity);
            if (resolved != null && colors != null) {
                addColorSource(colors, entity, resolved);
            }

            int luminance = getEntityLuminance(entity);
            if (luminance <= 0) continue;

            ColorRGB4 color = resolved != null ? resolved : Config.defaultColor;
            DynamicSource source = new DynamicSource(entity.getX(), entity.getEyeY(), entity.getZ(), luminance, color);
            sources.add(source);

            DynamicSource anchor = trackedAnchors.remove(entity.getId());
            if (anchor == null) {
                sectionsToRebuild = addSectionsAround(sectionsToRebuild, source);
            } else if (anchor.luminance != source.luminance || !anchor.color.equals(color) || movedFar(anchor, source)) {
                sectionsToRebuild = addSectionsAround(sectionsToRebuild, anchor);
                sectionsToRebuild = addSectionsAround(sectionsToRebuild, source);
            } else {
                source = anchor; // keep the old anchor so slow drift still accumulates to a remesh
            }
            anchors.put(entity.getId(), source);
        }

        // sources that vanished this tick (dropped item picked up, torch put away, entity unloaded)
        for (DynamicSource gone : trackedAnchors.values()) {
            sectionsToRebuild = addSectionsAround(sectionsToRebuild, gone);
        }

        trackedAnchors = anchors;
        scheduleRebuilds(level, sectionsToRebuild);
    }

    /**
     * Snapshots colored entities for coloring dynamic light blocks placed by server-side mods
     * (Lively Lighting), without the tracking/remesh work that client-lighting mods need.
     */
    private void collectBlockColors(ClientLevel level, List<ColorSource> colors) {
        for (Entity entity : level.entitiesForRendering()) {
            if (entity.isSpectator()) continue;
            ColorRGB4 color = resolveEntityColor(entity);
            if (color != null) addColorSource(colors, entity, color);
        }
    }

    /**
     * Records a colored entity for this tick — at its own position, and, when Valkyrien Skies ships
     * are around, at its mirror position in the other coordinate space: Lively Lighting projects
     * light across the world/ship boundary (a held torch anchors a light block in the shipyard, an
     * item frame mounted on a ship projects one out into the world), and those blocks can only
     * resolve their causing entity through a source in their own space.
     */
    private static void addColorSource(List<ColorSource> colors, Entity entity, ColorRGB4 color) {
        double x = entity.getX(), y = entity.getY(), z = entity.getZ();
        colors.add(new ColorSource(x, y, z, color));
		
		LevelAttachments attachments = (LevelAttachments) entity.level();
        if (!VsCompat.hasShipMirrors(attachments)) return;

        double[] world = VsCompat.shipyardToWorld(attachments, x, y, z);
        if (world != null) {
            // shipyard resident (item frame on a ship): mirror out into the world
            colors.add(new ColorSource(world[0], world[1], world[2], color));
        } else {
            // world entity near a ship: mirror into each such ship's shipyard
            VsCompat.forEachShipyardMirror(attachments, x, y, z, ENTITY_SHIP_MIRROR_RANGE,
                    (mx, my, mz) -> colors.add(new ColorSource(mx, my, mz, color)));
        }
    }

    /**
     * The external providers' lights this tick (2.8.0): each at its world position and, near a Valkyrien Skies ship, mirrored
     * into that ship's shipyard so the ship's meshes take it (a lamp on a ship lighting the ship itself, a torch held beside
     * one lighting its hull); a light that appeared, moved more than half a block, changed level or vanished remeshes the
     * sections round it, unless the terrain shaders draw external lights (then meshes are left alone).
     */
    private void collectExternalSources(ClientLevel level, List<DynamicSource> sources) {
        Map<Long, DynamicSource> anchors = new HashMap<>();
        Set<Long> sectionsToRebuild = null;
        boolean meshes = externalMeshLighting;
        LevelAttachments attachments = (LevelAttachments) level;
        boolean mirrors = VsCompat.hasShipMirrors(attachments);
        List<DynamicSource> collected = new ArrayList<>();
        collectExternal(level, 1.0f, collected::add);
        // virtual emitters: while meshes bake, point lights become lamps the engine propagates (occluded, coloured); the
        // cells that changed since last tick are told to the engine as block light changes
        boolean propagate = meshes && externalPropagation;
        Map<Long, ColorRGB4> emitters = propagate ? new HashMap<>() : Collections.<Long, ColorRGB4>emptyMap();
        if (propagate) {
            for (DynamicSource source : collected) {
                if (source.isSpot() || source.level < 1f || source.color.absorbs) continue;
                float scale = Math.min(15f, source.level) / 15f;
                ColorRGB4 at = ColorRGB4.fromRGB4(Math.round(source.color.red4 * scale), Math.round(source.color.green4 * scale), Math.round(source.color.blue4 * scale));
                int cellX = (int) Math.floor(source.x), cellY = (int) Math.floor(source.y), cellZ = (int) Math.floor(source.z);
                emitters.merge(BlockPos.asLong(cellX, cellY, cellZ), at, ColorRGB4::max);
                // a light above 15 (a merged light): the open cells a block away per level above 15 shine in full too
                if (source.level >= 16f) spreadEmitter(level, emitters, cellX, cellY, cellZ, Math.min(MAX_EMITTER_SPREAD, (int) (source.level - 15f)), at);
            }
        }
        Map<Long, ColorRGB4> before = virtualEmitters;
        if (!before.isEmpty() || !emitters.isEmpty()) {
            virtualEmitters = emitters;
            ColoredLightEngine engine = ((LevelAttachments) level).colorfullighting$getEngine();
            if (engine != null) {
                for (Map.Entry<Long, ColorRGB4> e : before.entrySet()) {
                    ColorRGB4 now = emitters.get(e.getKey());
                    if (now == null || !now.equals(e.getValue())) engine.onBlockLightPropertiesChanged(BlockPos.of(e.getKey()));
                }
                for (Map.Entry<Long, ColorRGB4> e : emitters.entrySet()) {
                    if (!e.getValue().equals(before.get(e.getKey()))) engine.onBlockLightPropertiesChanged(BlockPos.of(e.getKey()));
                }
            }
        }
        for (DynamicSource collectedSource : collected) {
            // a propagated point light is in the storage: the straight-line path must not add it again (through walls)
            final DynamicSource source = propagate && !collectedSource.isSpot() && collectedSource.level >= 1f ? collectedSource.propagated() : collectedSource;
            sources.add(source);
            if (mirrors) {
                double[] world = VsCompat.shipyardToWorld(attachments, source.x, source.y, source.z);
                if (world == null) {
                    // a ship's own space is the world's divided by its scale: a point light's copy there lasts as many of the
                    // ship's blocks as it lasts blocks of the world (2.10.6; a lamp on its own scaled ship comes out at a level a block)
                    VsCompat.forEachScaledShipyardMirror(attachments, source.x, source.y, source.z, Math.max(EXTERNAL_SHIP_MIRROR_RANGE, source.range), (mx, my, mz, shipScale) -> {
                        boolean scaled = POINT_STRETCH && !source.isSpot() && shipScale > 0.0 && Math.abs(shipScale - 1.0) > 1.0e-3;
                        double stretch = scaled ? Math.max(MIN_STRETCH, Math.min(MAX_STRETCH, source.stretch() / shipScale)) : source.stretch();
                        sources.add(new DynamicSource(mx, my, mz, source.luminance, source.color, source.level, source.falloff,
                                scaled ? (float) Math.max(1.0, source.range / shipScale) : source.range,
                                scaled ? (Math.abs(stretch - 1.0) < 1.0e-3 ? 0 : stretch) : source.dx, source.dy, source.dz, source.outerCos, source.innerCos, true, source.key, source.propagatedFlag));
                    });
                }
            }
            if (!meshes || source.propagatedFlag) continue; // the engine remeshes what a propagated light changes
            long key = source.key;
            DynamicSource anchor = externalAnchors.remove(key);
            DynamicSource kept = source;
            if (anchor == null) {
                sectionsToRebuild = addSectionsAround(sectionsToRebuild, source);
            } else if (anchor.luminance != source.luminance || !anchor.color.equals(source.color) || movedFar(anchor, source)) {
                sectionsToRebuild = addSectionsAround(sectionsToRebuild, anchor);
                sectionsToRebuild = addSectionsAround(sectionsToRebuild, source);
            } else {
                kept = anchor;
            }
            anchors.put(key, kept);
        }
        if (meshes) {
            for (DynamicSource gone : externalAnchors.values()) sectionsToRebuild = addSectionsAround(sectionsToRebuild, gone);
        } else if (!externalAnchors.isEmpty()) {
            // the shaders took over: the meshes that still carry baked lights are rebuilt once without them
            for (DynamicSource gone : externalAnchors.values()) sectionsToRebuild = addSectionsAround(sectionsToRebuild, gone);
        }
        externalAnchors = anchors;
        scheduleRebuildsBudgeted(level, sectionsToRebuild);
    }

    /** The farthest a propagated light above 15 spreads its full light (blocks): each cell is a lamp the engine propagates. */
    private static final int MAX_EMITTER_SPREAD = 3;

    /**
     * A propagated light above level 15 (2.10.2): the engine's light has sixteen levels, so the light's cell alone gave 15
     * however strong the light was, and a merged light was no brighter on this path than a single torch. The cells within
     * {@code depth} steps of the light's cell, reached through cells that neither stop nor tint light, become lamps of the
     * full colour as well: full light out to as many blocks as the level is above 15, up to {@link #MAX_EMITTER_SPREAD},
     * and round corners no further than a lamp's light goes.
     */
    private static void spreadEmitter(ClientLevel level, Map<Long, ColorRGB4> emitters, int x, int y, int z, int depth, ColorRGB4 color) {
        if (depth <= 0) return;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        Set<Long> seen = new HashSet<>();
        List<Long> ring = new ArrayList<>();
        long start = BlockPos.asLong(x, y, z);
        seen.add(start);
        ring.add(start);
        for (int step = 0; step < depth && !ring.isEmpty(); step++) {
            List<Long> next = new ArrayList<>();
            for (long cell : ring) {
                for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
                    long to = BlockPos.offset(cell, direction);
                    if (!seen.add(to)) continue;
                    cursor.set(to);
                    net.minecraft.world.level.block.state.BlockState state = level.getBlockState(cursor);
                    if (state.canOcclude() || state.getLightBlock(level, cursor) > 0
                            || state.getBlock() instanceof net.minecraft.world.level.block.BeaconBeamBlock) continue;
                    emitters.merge(to, color, ColorRGB4::max);
                    next.add(to);
                }
            }
            ring = next;
        }
    }

    /**
     * Remeshes under the per-tick budget, nearest the camera first; what doesn't fit waits for the next ticks. Without the
     * budget 32 lamps on spinning ships asked for a thousand sections a tick and the client stalled (2026-11-09).
     */
    private void scheduleRebuildsBudgeted(ClientLevel level, @Nullable Set<Long> sections) {
        Set<Long> all = externalPending;
        if (sections != null) all.addAll(sections);
        if (all.isEmpty()) return;
        int budget = externalRebuildBudget;
        if (all.size() <= budget) {
            externalPending = new HashSet<>();
            scheduleRebuilds(level, all);
            return;
        }
        net.minecraft.world.phys.Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        List<Long> sorted = new ArrayList<>(all);
        sorted.sort((a, b) -> Double.compare(sectionDistance(a, cam), sectionDistance(b, cam)));
        Set<Long> now = new HashSet<>(sorted.subList(0, budget));
        Set<Long> later = new HashSet<>(sorted.subList(budget, sorted.size()));
        // what waits too long is dropped: the light has moved on and its sections will be asked for again if it matters
        if (later.size() > budget * 40) later = new HashSet<>(sorted.subList(budget, budget * 41));
        externalPending = later;
        scheduleRebuilds(level, now);
    }

    private static double sectionDistance(long key, net.minecraft.world.phys.Vec3 cam) {
        SectionPos p = SectionPos.of(key);
        double dx = p.minBlockX() + 8 - cam.x, dy = p.minBlockY() + 8 - cam.y, dz = p.minBlockZ() + 8 - cam.z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static boolean movedFar(DynamicSource anchor, DynamicSource now) {
        double dx = now.x - anchor.x, dy = now.y - anchor.y, dz = now.z - anchor.z;
        return dx * dx + dy * dy + dz * dz > REBUILD_MOVE_THRESHOLD_SQUARED;
    }

    private static Set<Long> addSectionsAround(Set<Long> sections, DynamicSource source) {
        if (sections == null) sections = new HashSet<>();
        int reach = source.external ? (int) Math.ceil(Math.min(source.isSpot() ? source.range : Math.min(source.range, (float) (source.level * source.stretch())), 48f)) : REBUILD_RANGE;
        int minX = SectionPos.blockToSectionCoord((int) Math.floor(source.x - reach));
        int maxX = SectionPos.blockToSectionCoord((int) Math.floor(source.x + reach));
        int minY = SectionPos.blockToSectionCoord((int) Math.floor(source.y - reach));
        int maxY = SectionPos.blockToSectionCoord((int) Math.floor(source.y + reach));
        int minZ = SectionPos.blockToSectionCoord((int) Math.floor(source.z - reach));
        int maxZ = SectionPos.blockToSectionCoord((int) Math.floor(source.z + reach));
        for (int x = minX; x <= maxX; x++)
            for (int y = minY; y <= maxY; y++)
                for (int z = minZ; z <= maxZ; z++)
                    sections.add(SectionPos.asLong(x, y, z));
        return sections;
    }

    private void scheduleRebuilds(ClientLevel clientLevel, @Nullable Set<Long> sections) {
        if (sections == null) return;
        // This level's own accessor, not the client's current one: with Immersive Portals the
        // remeshes must land on the renderer of the level the sources live in.
        LevelAccessor level = ((LevelAttachments) clientLevel).colorfullighting$getAccessor();
        // The accessor above reaches the right renderer for any level; the Sodium fast path only
        // knows the CURRENT dimension's renderer, so gate it (same rule as engine.onLightUpdate).
        var renderer = Minecraft.getInstance().levelRenderer;
        boolean sodiumFastPath = SodiumCompat.isSodiumLoaded() && Minecraft.getInstance().level == clientLevel;
        int minSectionY = level.getMinSectionY();
        int maxSectionY = level.getMaxSectionY();

        var flywheel = ((LevelAttachments) clientLevel).colorfullighting$getFlywheelCompat();
        for (long key : sections) {
            SectionPos pos = SectionPos.of(key);
            if (pos.y() < minSectionY || pos.y() > maxSectionY) continue;
            level.setSectionDirty(pos.x(), pos.y(), pos.z());
            if (sodiumFastPath && renderer instanceof SodiumWorldRendererAccessor sodiumRenderer) {
                sodiumRenderer.scheduleRebuild(pos.x(), pos.y(), pos.z(), false);
            }
            // Flywheel's coloured light buffer for the section is collected again too (block entities and contraptions there)
            if (flywheel != null) {
                try {
                    flywheel.onLightUpdate(pos.x(), pos.y(), pos.z(), key, clientLevel);
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    /**
     * Per-channel max of the stored light color and the contribution of tracked dynamic light
     * sources at the given block. No-op while nothing is tracked. Thread-safe.
     */
    public ColorRGB4 maxWithDynamicLight(int blockX, int blockY, int blockZ, ColorRGB4 base) {
        int packed = base.red4 << 8 | base.green4 << 4 | base.blue4;
        int result = maxWithDynamicLightPacked(blockX, blockY, blockZ, packed);
        if (result == packed) return base;
        return ColorRGB4.fromRGB4((result >>> 8) & 0x0F, (result >>> 4) & 0x0F, result & 0x0F);
    }

    /**
     * Allocation-free equivalent of {@link #maxWithDynamicLight}, taking and returning a packed 12-bit
     * {@code r << 8 | g << 4 | b}. Sits on the chunk-build hot path, so it must not allocate.
     */
    /** The render thread's scratch for a slab sample (the only thread that takes the slabs here). */
    private static final float[] SLAB_SAMPLE = new float[3];

    public int maxWithDynamicLightPacked(int blockX, int blockY, int blockZ, int base) {
        DynamicSource[] sources = entitySources;
        if (sources.length == 0) return base;

        double x = blockX + 0.5, y = blockY + 0.5, z = blockZ + 0.5;
        int r = (base >>> 8) & 0x0F, g = (base >>> 4) & 0x0F, b = base & 0x0F;
        // external lights reach meshes only while they are baked (the shader path draws them otherwise); the render
        // thread's samples (entities, block entities, particles) always take them
        boolean onRenderThread = com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread();
        // a worker thread inside a live sample (2.10.3: a particle's light on AsyncParticles' workers) counts as the render thread
        LiveSample live = !onRenderThread && liveSamplers.get() > 0 ? LIVE.get() : null;
        if (live != null && live.depth == 0) live = null;
        boolean liveHere = onRenderThread || live != null;
        boolean meshesTakeExternal = externalMeshLighting || liveHere;
        // an entity's stored sample leaves the drawn lights to exactEntityLight (2.10.3)
        boolean exactAfter = onRenderThread && entitySampling;
        // render-thread samples (entities, block entities, particles) scale each light by its slab in the field the shaders sample
        // (2.10.0: where its light may go through the blocks); a light without a slab shines through everything
        boolean slabs = liveHere && !externalMeshLighting && ShaderDynamicLights.transmission() && DynamicLightField.active();
        float[] vis = live != null ? live.vis : SLAB_SAMPLE;
        for (DynamicSource source : sources) {
            if (source.color.absorbs) continue; // a light that absorbs adds nothing here (2.12.0)
            double dx = x - source.x, dy = y - source.y, dz = z - source.z;
            double distanceSquared = dx * dx + dy * dy + dz * dz;
            double level;
            // the blocks of reach the light has left here: a detour that long through the blocks takes the rest
            double remaining = 0.0;
            if (source.external) {
                if (!meshesTakeExternal || source.propagatedFlag || exactAfter) continue;
                if (distanceSquared > (double) source.range * source.range) continue;
                if (source.isSpot()) {
                    double d = Math.sqrt(distanceSquared);
                    remaining = source.range - d;
                    if (d < 1.0e-6) level = source.level;
                    else {
                        double cos = (dx * source.dx + dy * source.dy + dz * source.dz) / d;
                        double cone = cos <= source.outerCos ? 0.0 : source.innerCos <= source.outerCos ? 1.0 : Math.min(1.0, (cos - source.outerCos) / (source.innerCos - source.outerCos));
                        if (cone <= 0.0) continue;
                        level = source.level * (remaining / source.range) * cone;
                    }
                } else {
                    double stretch = source.stretch();
                    remaining = source.level * stretch - (source.falloff == 1 ? Math.sqrt(distanceSquared) : Math.abs(dx) + Math.abs(dy) + Math.abs(dz));
                    level = remaining / stretch;
                }
            } else {
                if (distanceSquared > MAX_RADIUS_SQUARED) continue;
                level = (1.0 - Math.sqrt(distanceSquared) / MAX_RADIUS) * source.luminance;
            }
            if (level <= 0.0) continue;
            float scale = (float) (Math.min(15.0, level) / 15.0);
            float vr = 1f, vg = 1f, vb = 1f;
            if (slabs && source.external && DynamicLightField.visibility(source.key, blockX, blockY, blockZ, remaining, vis)) {
                vr = vis[0];
                vg = vis[1];
                vb = vis[2];
            }
            r = Math.max(r, Math.round(source.color.red4 * scale * vr));
            g = Math.max(g, Math.round(source.color.green4 * scale * vg));
            b = Math.max(b, Math.round(source.color.blue4 * scale * vb));
        }
        return r << 8 | g << 4 | b;
    }

    public static boolean isDynamicLightBlock(Block block) {
        Set<Block> blocks = dynamicLightBlocks;
        return !blocks.isEmpty() && blocks.contains(block);
    }

    /**
     * Color for a light block placed by a dynamic lighting mod, inferred from the nearest entity that
     * resolves to one (mirrored across the Valkyrien Skies world/ship boundary, see
     * {@link #addColorSource}), else from the colored light stored at the block's ship-mirrored
     * position (see {@link #sampleShipMirrorHue}). Null when nothing resolves (callers fall back to
     * plain white light).
     */
    @Nullable
    public static ColorRGB4 getDynamicBlockLightColor(LevelAccessor level, BlockPos lightBlockPos) {
        DynamicLightsCompat instance = ((LevelAttachments) level).colorfullighting$getDynamicLights();
        if (instance == null) return null;
        return instance.dynamicBlockLightColor(level, lightBlockPos);
    }

    @Nullable
    private ColorRGB4 dynamicBlockLightColor(LevelAccessor level, BlockPos lightBlockPos) {
        // Runs on the light-propagator thread, so it must never touch the live entity lists: read the
        // per-tick snapshot instead. Seeing a dynamic light block also arms the colored-entity scan,
        // which stays off for vanilla worlds that never place these blocks.
        ColorSource[] sources = blockColorSources;
        if (sources.length == 0) trackBlockColors = true;

        double x = lightBlockPos.getX() + 0.5, y = lightBlockPos.getY() + 0.5, z = lightBlockPos.getZ() + 0.5;
        ColorRGB4 bestColor = null;
        double bestDistanceSquared = DYNAMIC_BLOCK_COLOR_RADIUS_SQUARED;
        for (ColorSource source : sources) {
            double dx = x - source.x, dy = y - source.y, dz = z - source.z;
            double distanceSquared = dx * dx + dy * dy + dz * dz;
            if (distanceSquared >= bestDistanceSquared) continue;
            bestColor = source.color;
            bestDistanceSquared = distanceSquared;
        }
        if (bestColor != null) {
            // color now tracks the entity; a stale mirror entry must not keep re-propagating it
            shipMirrorHuesUsed.remove(lightBlockPos);
            return bestColor;
        }

        ColorRGB4 hue = sampleShipMirrorHue(((LevelAttachments) level).colorfullighting$getEngine(), level, x, y, z);
        if (VsCompat.isAvailable()) {
            // remember what resolved (not gated on hasShipMirrors: on world join blocks propagate
            // before the first tick publishes any mirror) so recheckShipMirrorHues can re-propagate
            // the block once the ship's light exists
            shipMirrorHuesUsed.put(lightBlockPos.immutable(), hue == null ? -1 : packHue(hue));
        }
        return hue;
    }

    /**
     * Re-propagates dynamic light blocks whose ship-mirror hue has changed since their color was
     * resolved. Covers the world-join race (the block propagated before the ship's region light or
     * even the mirrors existed and stuck white) and ships that move or rotate while keeping a
     * projected block in place. Runs on the client thread every {@link #SHIP_HUE_RECHECK_INTERVAL_TICKS}
     * ticks; re-propagation re-resolves the color and re-stores the entry.
     */
    private void recheckShipMirrorHues(ColoredLightEngine engine, ClientLevel level) {
        if (shipMirrorHuesUsed.isEmpty()) return;
        if (++recheckCounter < SHIP_HUE_RECHECK_INTERVAL_TICKS) return;
        recheckCounter = 0;

        Iterator<Map.Entry<BlockPos, Integer>> iterator = shipMirrorHuesUsed.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, Integer> entry = iterator.next();
            BlockPos pos = entry.getKey();
            if (!isDynamicLightBlock(level.getBlockState(pos).getBlock())) {
                iterator.remove();
                continue;
            }
            ColorRGB4 hue = sampleShipMirrorHue(engine, ((LevelAttachments) level).colorfullighting$getAccessor(), pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            int packed = hue == null ? -1 : packHue(hue);
            if (packed != entry.getValue()) {
                engine.onBlockLightPropertiesChanged(pos);
            }
        }
    }

    private static int packHue(ColorRGB4 color) {
        return color.red4 << 8 | color.green4 << 4 | color.blue4;
    }

    /**
     * Color for a light block Lively Lighting projected across the Valkyrien Skies world/ship
     * boundary from a *block* lamp — a ship's lantern lighting the world around it, a world lamp
     * post lighting a passing ship's hull, or one ship's lamps lighting another. There is no entity
     * to take the color from, so the colored light this engine has already stored round the lamp
     * in the other space is sampled instead (see {@link #hueRoundLamp}). The sample is scaled to a
     * full-intensity hue — the light block's own (already distance-dimmed) emission scales it back
     * down in Config.getColorEmission.
     *
     * <p>A world light block takes a hue only from a real ship lamp near its mirror (2026-10-10).
     * Before, both sides took the stored light right at the mirror, whatever it came from: a held
     * light (an enchanted sword, no color of its own) is placed both in the world and, projected,
     * in the shipyard of a ship beside it, and each of the two took its hue from the other one's
     * light. Light loses a level per channel per block, so the dim fringe of either was red only,
     * scaled up to pure red, and the pair then held each other red ("the light my enchanted item
     * emits turns red" near a ship). A world light block now looks for a ship lamp (see
     * {@link #hueRoundLamp}), so it never takes its hue from a projection; a shipyard light block
     * (a world lamp lighting the ship, or another ship's lamps through their world projections) still
     * takes the world's own light where the ship is, the brightest channel at least
     * {@link #MIRROR_HUE_MIN}: the lamp lighting it can be 15 blocks away, and the world light there
     * no longer holds any of the ship's own projections' hues.
     */
    @Nullable
    private static ColorRGB4 sampleShipMirrorHue(ColoredLightEngine engine, LevelAccessor level, double x, double y, double z) {
		LevelAttachments attachments = (LevelAttachments) level;
        if (!VsCompat.hasShipMirrors(attachments)) return null;

        int[] max = new int[3];
        double[] world = VsCompat.shipyardToWorld(attachments, x, y, z);
        if (world != null) {
            sampleNeighborhoodMax(engine, world[0], world[1], world[2], max);
        } else {
            // per-channel max over the mirrors: light from several ships blends like light does
            VsCompat.forEachShipyardMirror(attachments, x, y, z, BLOCK_SHIP_MIRROR_RANGE,
                    (mx, my, mz) -> hueRoundLamp(engine, level, attachments, mx, my, mz, max));
        }
        return fullHue(max[0], max[1], max[2]);
    }

    /** A stored colour scaled up to a full-intensity hue (its brightest channel 15), null for black. */
    @Nullable
    private static ColorRGB4 fullHue(int r, int g, int b) {
        int brightest = Math.max(r, Math.max(g, b));
        if (brightest == 0) return null;
        if (brightest == 15) return ColorRGB4.fromRGB4(r, g, b);
        float scale = 15.0f / brightest;
        return ColorRGB4.fromRGB4(Math.round(r * scale), Math.round(g * scale), Math.round(b * scale));
    }

    /**
     * The color a lamp near a mirrored position sends out, max-merged into {@code max}; false (nothing merged) when
     * no real lamp stands within {@link #MIRROR_LAMP_RADIUS} of it. First a cheap look at the stored light in the 3x3x3
     * blocks round the mirror (the mirror comes from the client's ship transform while the block was placed with the
     * server's of an earlier tick, so on a moving ship it lands a block or two off): nothing bright there, no lamp near.
     * Then the nearest light-emitting block that is not itself a dynamic light block, and round it the open cells
     * (air, or a dynamic light block's cell) within {@link #MIRROR_HUE_RADIUS}: the brightest one whose brightest
     * channel reaches {@link #MIRROR_HUE_MIN}, outside the ship's own blocks when there is one (a beacon in a shell of
     * red glass lights the world red, as the same beacon in the world does: its open neighbours inside the shell are
     * none, the air beyond the glass is red; before, the stored light at the beacon's own cell, white, was taken).
     */
    private static boolean hueRoundLamp(ColoredLightEngine engine, LevelAccessor level, LevelAttachments attachments,
                                        double x, double y, double z, int[] max) {
        int blockX = (int) Math.floor(x), blockY = (int) Math.floor(y), blockZ = (int) Math.floor(z);
        int peak = 0;
        for (int dx = -1; dx <= 1 && peak < MIRROR_HUE_MIN; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    peak = Math.max(peak, peakOf(engine.sampleLightColorPacked(blockX + dx, blockY + dy, blockZ + dz)));
                }
            }
        }
        if (peak < MIRROR_HUE_MIN) return false;

        // the nearest real lamp
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int lampX = 0, lampY = 0, lampZ = 0, lampDistance = Integer.MAX_VALUE;
        for (int dx = -MIRROR_LAMP_RADIUS; dx <= MIRROR_LAMP_RADIUS; dx++) {
            for (int dy = -MIRROR_LAMP_RADIUS; dy <= MIRROR_LAMP_RADIUS; dy++) {
                for (int dz = -MIRROR_LAMP_RADIUS; dz <= MIRROR_LAMP_RADIUS; dz++) {
                    int distance = dx * dx + dy * dy + dz * dz;
                    if (distance >= lampDistance) continue;
                    BlockStateAccessor state = level.getBlockState(cursor.set(blockX + dx, blockY + dy, blockZ + dz));
                    if (state == null || state.isAir() || state.getLightEmission() <= 0) continue;
                    // a dynamic light block is a lamp only when an API provider colours it on its own (Lively
                    // Lighting's light of a burning ship: fire's colour), never by a mirror of its own
                    if (isDynamicLightBlock(state.getBlock()) && (!ApiProviderRegistry.hasBlockProviders()
                            || ApiProviderRegistry.getBlockColor(level.getLevel(), cursor, state.getBlockState()) == null)) continue;
                    lampX = blockX + dx;
                    lampY = blockY + dy;
                    lampZ = blockZ + dz;
                    lampDistance = distance;
                }
            }
        }
        if (lampDistance == Integer.MAX_VALUE) return false;

        // the color its light leaves with: the brightest open cell round it, outside the ship's blocks if any is
        int bestOut = 0, bestOutPeak = 0, bestIn = 0, bestInPeak = 0;
        for (int dx = -MIRROR_HUE_RADIUS; dx <= MIRROR_HUE_RADIUS; dx++) {
            for (int dy = -MIRROR_HUE_RADIUS; dy <= MIRROR_HUE_RADIUS; dy++) {
                for (int dz = -MIRROR_HUE_RADIUS; dz <= MIRROR_HUE_RADIUS; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    int cx = lampX + dx, cy = lampY + dy, cz = lampZ + dz;
                    int packed = engine.sampleLightColorPacked(cx, cy, cz);
                    int cellPeak = peakOf(packed);
                    if (cellPeak < MIRROR_HUE_MIN || (cellPeak <= bestOutPeak && cellPeak <= bestInPeak)) continue;
                    BlockStateAccessor state = level.getBlockState(cursor.set(cx, cy, cz));
                    if (state == null || !(state.isAir() || isDynamicLightBlock(state.getBlock()))) continue;
                    if (VsCompat.insideShipBlocks(attachments, cx + 0.5, cy + 0.5, cz + 0.5)) {
                        if (cellPeak > bestInPeak) {
                            bestIn = packed;
                            bestInPeak = cellPeak;
                        }
                    } else if (cellPeak > bestOutPeak) {
                        bestOut = packed;
                        bestOutPeak = cellPeak;
                    }
                }
            }
        }
        int chosen = bestOutPeak > 0 ? bestOut : bestIn;
        if (chosen == 0) return false;
        max[0] = Math.max(max[0], (chosen >>> 8) & 0x0F);
        max[1] = Math.max(max[1], (chosen >>> 4) & 0x0F);
        max[2] = Math.max(max[2], chosen & 0x0F);
        return true;
    }

    /**
     * Per-channel max of the stored colored light in the 3x3x3 blocks around a mirrored position, over the cells
     * whose brightest channel reaches {@link #MIRROR_HUE_MIN}. A single-point sample was too brittle: the mirror comes
     * from the client's ship transform while the block was placed with the server's transform of an earlier tick, so
     * on a moving ship it lands up to a block or two off — often inside an opaque hull block (stored light 0).
     */
    private static void sampleNeighborhoodMax(ColoredLightEngine engine, double x, double y, double z, int[] max) {
        int blockX = (int) Math.floor(x), blockY = (int) Math.floor(y), blockZ = (int) Math.floor(z);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int packed = engine.sampleLightColorPacked(blockX + dx, blockY + dy, blockZ + dz);
                    if (peakOf(packed) < MIRROR_HUE_MIN) continue;
                    max[0] = Math.max(max[0], (packed >>> 8) & 0x0F);
                    max[1] = Math.max(max[1], (packed >>> 4) & 0x0F);
                    max[2] = Math.max(max[2], packed & 0x0F);
                }
            }
        }
    }

    private static int peakOf(int packed) {
        return Math.max((packed >>> 8) & 0x0F, Math.max((packed >>> 4) & 0x0F, packed & 0x0F));
    }

    /** Luminance an entity should cast on its own (held/equipped/dropped light items, fire). */
    private static int getEntityLuminance(Entity entity) {
        int luminance = 0;
        if (entity instanceof ItemEntity itemEntity) {
            luminance = getStackLuminance(itemEntity.getItem());
        } else if (entity instanceof LivingEntity living) {
            for (ItemStack stack : living.getAllSlots()) {
                luminance = Math.max(luminance, getStackLuminance(stack));
                if (luminance >= 15) break;
            }
        }
        if (entity.isOnFire()) luminance = 15;
        return luminance;
    }

    private ColorRGB4 resolveSourceColor(Object source) {
        if (source instanceof Entity entity) {
            ColorRGB4 color = resolveEntityColor(entity);
            return color != null ? color : Config.defaultColor;
        }
        if (source instanceof BlockEntity blockEntity) {
            // the colour the block emits placed: configured, "auto", or an unconfigured lamp's automatic colour
            return Config.getLightColor(blockEntity.getBlockState());
        }
        return Config.defaultColor;
    }

    /** Light color a dynamic light source entity should cast, or null when nothing about it resolves. */
    @Nullable
    private ColorRGB4 resolveEntityColor(Entity entity) {
        ColorRGB4 own = resolveOwnEntityColor(entity);
        if (own != null) return own;

        if (entity instanceof ItemEntity itemEntity) {
            ColorRGB4 color = resolveStackColor(itemEntity.getItem());
            if (color != null) return color;
        }

        if (entity instanceof LivingEntity living) {
            // the brightest equipped light-emitting stack decides the hue
            ColorRGB4 bestColor = null;
            int bestLuminance = 0;
            for (ItemStack stack : living.getAllSlots()) {
                int luminance = getStackLuminance(stack);
                if (luminance <= bestLuminance) continue;
                ColorRGB4 color = resolveStackColor(stack);
                if (color != null) {
                    bestColor = color;
                    bestLuminance = luminance;
                }
            }
            if (bestColor != null) return bestColor;
        }

        if (entity.isOnFire()) return Config.getLightColor(Blocks.FIRE);
        return null;
    }

    /** The entity's own light color (entities.json, then API providers), whatever it holds; null when neither has one. */
    @Nullable
    private ColorRGB4 resolveOwnEntityColor(Entity entity) {
        ResourceLocation entityId = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        if (entityId != null) {
            VariantList<Config.ColorEmitter> config = Config.getEntityEmitterConfig(entityId);
            if (config != null) {
                Config.ColorEmitter emitter = config.resolve(null, config.needsNbt() ? entityNbt(entity) : null);
                if (emitter != null) return emitter.color();
            }
        }

        // API providers: dynamic entity colors JSON cannot express. Run after explicit
        // entities.json config (user/pack intent wins) and before the built-in heuristics.
        return ApiProviderRegistry.getEntityColor(entity);
    }

    // ---- Lively Lighting's client half (2026-10-12) -----------------------------------------------------------------
    // Lively Lighting tells its clients what each of its light blocks stands for (an entity's light and the slot it
    // comes from, a lamp, a burning ship) and answers the block light color API for them, so a light keeps its source's
    // colour however far behind a fast holder it is, after the holder put the light away, merged with other lights or
    // at the dim edge of a lamp's reach, where the nearest-entity and ship-mirror guesses above found nothing (white).
    // These give it the colour Colorful Lighting gives that source, as 0xRRGGBB at full intensity (the light block's
    // emission scales it), -1 when nothing resolves. Called by reflection, on the client thread.

    /** An entity's light colour: with {@code withItems}, as for its dynamic light (its own, then what it holds, then fire); without, its own or fire's. */
    public static int entityLightColor(Entity entity, boolean withItems) {
        DynamicLightsCompat instance = entity.level() instanceof LevelAttachments attachments ? attachments.colorfullighting$getDynamicLights() : null;
        if (instance == null) return -1;
        ColorRGB4 color = withItems ? instance.resolveEntityColor(entity) : instance.resolveOwnEntityColor(entity);
        if (color == null && !withItems && entity.isOnFire()) color = Config.getLightColor(Blocks.FIRE);
        return rgb8(color);
    }

    /** A held, worn or dropped stack's light colour. */
    public static int itemLightColor(ItemStack stack) {
        return rgb8(resolveStackColor(stack));
    }

    /** A burning entity's light colour (minecraft:fire's). */
    public static int fireLightColor() {
        return rgb8(Config.getLightColor(Blocks.FIRE));
    }

    /** Colorful Lighting's colour for light that resolves nothing (plain white unless configured). */
    public static int defaultLightColor() {
        return rgb8(Config.defaultColor);
    }

    /**
     * The colour of the light leaving the lamp at {@code lamp} (in the world or in a ship's shipyard) as stored round it,
     * filters next to it included (see {@link #hueRoundLamp}); -1 while its light isn't there yet.
     */
    public static int lampLightColor(net.minecraft.world.level.Level level, BlockPos lamp) {
        if (!(level instanceof LevelAttachments attachments) || !ColoredLightEngine.isEnabled()) return -1;
        ColoredLightEngine engine = attachments.colorfullighting$getEngine();
        LevelAccessor accessor = attachments.colorfullighting$getAccessor();
        if (engine == null || accessor == null) return -1;
        int[] max = new int[3];
        if (!hueRoundLamp(engine, accessor, (LevelAttachments) accessor, lamp.getX() + 0.5, lamp.getY() + 0.5, lamp.getZ() + 0.5, max)) return -1;
        return rgb8(fullHue(max[0], max[1], max[2]));
    }

    private static int rgb8(@Nullable ColorRGB4 color) {
        if (color == ColorRGB4.UV) return net.camacraft.colorfullighting.api.dynamic.DynamicLightSink.BLACKLIGHT;
        return color == null ? -1 : (color.red4 * 17) << 16 | (color.green4 * 17) << 8 | color.blue4 * 17;
    }

    /** Item NBT is already a live tag on the stack, so there is nothing to serialize or cache. */
    @Nullable
    private static CompoundTag stackNbt(VariantList<Config.ColorEmitter> config, ItemStack stack) {
        return config.needsNbt() ? stack.getTag() : null;
    }

    /**
     * The entity's NBT for this tick. Serialization can throw for entities whose save code assumes a
     * server context, so a failure is cached as an empty tag: NBT rules then simply never match.
     */
    private CompoundTag entityNbt(Entity entity) {
        return entityNbtCache.computeIfAbsent(entity.getId(), id -> {
            try {
                return entity.saveWithoutId(new CompoundTag());
            }
            catch (Throwable e) {
                if (!loggedEntitySaveFailure) {
                    loggedEntitySaveFailure = true;
                    ColorfulLighting.LOGGER.warn("Failed to read NBT of entity {}; its NBT light rules will not apply",
                            entity.getType(), e);
                }
                return NO_NBT;
            }
        });
    }

    /** Light color of an item stack, or null when the stack isn't a known light emitter. */
    @Nullable
    private static ColorRGB4 resolveStackColor(ItemStack stack) {
        if (stack.isEmpty()) return null;
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (itemId == null) return null;

        VariantList<Config.ColorEmitter> itemConfig = Config.getItemEmitterConfig(itemId);
        if (itemConfig != null) {
            Config.ColorEmitter itemEmitter = itemConfig.resolve(null, stackNbt(itemConfig, stack));
            if (itemEmitter != null) return itemEmitter.color();
        }

        // most placeable light sources share the block's id (torch, lantern, glowstone, ...): the colour the placed
        // block emits, an emitters.json "auto" entry's and an automatic emitter's (sampled from the block's texture)
        // included (2026-10-17: a held automatic emitter was white, placed it had its colour)
        VariantList<Config.ColorEmitter> blockConfig = Config.getBlockEmitterConfig(itemId);
        if (blockConfig != null && blockConfig.getDefault() != null) {
            Config.ColorEmitter emitter = blockConfig.getDefault();
            Block block = emitter.autoColor() ? ForgeRegistries.BLOCKS.getValue(itemId) : null;
            return block != null && block != Blocks.AIR ? Config.getLightColor(block.defaultBlockState()) : emitter.color();
        }

        if (stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock().defaultBlockState().getLightEmission() > 0) {
            // a lamp whose item has another id, or one no emitters.json names: whatever the placed block emits (its
            // configured colour; else, with autoEmitterColors, the colour sampled from its texture; else white)
            return Config.getLightColor(blockItem.getBlock().defaultBlockState());
        }
        return null;
    }

    private static int getStackLuminance(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());

        // an items.json brightness overrides everything, so a held item can differ from its block
        // (e.g. glowstone block stays 15 while the held glowstone item is configured to 2)
        VariantList<Config.ColorEmitter> itemConfig = itemId == null ? null : Config.getItemEmitterConfig(itemId);
        Config.ColorEmitter itemEmitter = itemConfig == null ? null : itemConfig.resolve(null, stackNbt(itemConfig, stack));
        if (itemEmitter != null && itemEmitter.overriddenBrightness4() >= 0) {
            return itemEmitter.overriddenBrightness4();
        }

        // otherwise a held block glows like the same block placed in the world: an emitters.json
        // brightness override if present, else the block's vanilla emission (glowstone item == 15)
        if (stack.getItem() instanceof BlockItem blockItem) {
            VariantList<Config.ColorEmitter> config = Config.getBlockEmitterConfig(blockItem.getBlock());
            if (config != null && config.getDefault() != null && config.getDefault().overriddenBrightness4() >= 0) {
                return config.getDefault().overriddenBrightness4();
            }
            int emission = blockItem.getBlock().defaultBlockState().getLightEmission();
            if (emission > 0) return emission;
        }

        // non-block items (lava bucket, ...) have no block emission; a configured color marks them
        // luminous, and with no explicit brightness they fall back to a mid-range level
        if (itemEmitter != null || (itemId != null && Config.getBlockEmitterConfig(itemId) != null)) return 8;
        return 0;
    }
}
