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

    private record DynamicSource(double x, double y, double z, int luminance, ColorRGB4 color) {}
    private static final DynamicSource[] NO_SOURCES = new DynamicSource[0];
    // written on this level's tick, read from chunk-build worker threads
    private volatile DynamicSource[] entitySources = NO_SOURCES;

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
	    ColoredLightEngine engine = ((LevelAttachments) level).colorfullighting$getEngine();
	    if (engine == null || !ColoredLightEngine.isEnabled()) {
            entitySources = NO_SOURCES;
            blockColorSources = NO_COLOR_SOURCES;
            trackedAnchors = new HashMap<>();
            shipMirrorHuesUsed.clear();
            return;
        }

        recheckShipMirrorHues(engine, level);

        if (sdlHook == null && !trackEntities && !trackBlockColors) return;

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
        entitySources = sources.isEmpty() ? NO_SOURCES : sources.toArray(NO_SOURCES);
        blockColorSources = (colors == null || colors.isEmpty()) ? NO_COLOR_SOURCES : colors.toArray(NO_COLOR_SOURCES);
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

    private static boolean movedFar(DynamicSource anchor, DynamicSource now) {
        double dx = now.x - anchor.x, dy = now.y - anchor.y, dz = now.z - anchor.z;
        return dx * dx + dy * dy + dz * dz > REBUILD_MOVE_THRESHOLD_SQUARED;
    }

    private static Set<Long> addSectionsAround(Set<Long> sections, DynamicSource source) {
        if (sections == null) sections = new HashSet<>();
        int minX = SectionPos.blockToSectionCoord((int) Math.floor(source.x - REBUILD_RANGE));
        int maxX = SectionPos.blockToSectionCoord((int) Math.floor(source.x + REBUILD_RANGE));
        int minY = SectionPos.blockToSectionCoord((int) Math.floor(source.y - REBUILD_RANGE));
        int maxY = SectionPos.blockToSectionCoord((int) Math.floor(source.y + REBUILD_RANGE));
        int minZ = SectionPos.blockToSectionCoord((int) Math.floor(source.z - REBUILD_RANGE));
        int maxZ = SectionPos.blockToSectionCoord((int) Math.floor(source.z + REBUILD_RANGE));
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

        for (long key : sections) {
            SectionPos pos = SectionPos.of(key);
            if (pos.y() < minSectionY || pos.y() > maxSectionY) continue;
            level.setSectionDirty(pos.x(), pos.y(), pos.z());
            if (sodiumFastPath && renderer instanceof SodiumWorldRendererAccessor sodiumRenderer) {
                sodiumRenderer.scheduleRebuild(pos.x(), pos.y(), pos.z(), false);
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
    public int maxWithDynamicLightPacked(int blockX, int blockY, int blockZ, int base) {
        DynamicSource[] sources = entitySources;
        if (sources.length == 0) return base;

        double x = blockX + 0.5, y = blockY + 0.5, z = blockZ + 0.5;
        int r = (base >>> 8) & 0x0F, g = (base >>> 4) & 0x0F, b = base & 0x0F;
        for (DynamicSource source : sources) {
            double dx = x - source.x, dy = y - source.y, dz = z - source.z;
            double distanceSquared = dx * dx + dy * dy + dz * dz;
            if (distanceSquared > MAX_RADIUS_SQUARED) continue;

            double level = (1.0 - Math.sqrt(distanceSquared) / MAX_RADIUS) * source.luminance;
            if (level <= 0.0) continue;

            float scale = (float) (level / 15.0);
            r = Math.max(r, Math.round(source.color.red4 * scale));
            g = Math.max(g, Math.round(source.color.green4 * scale));
            b = Math.max(b, Math.round(source.color.blue4 * scale));
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
            VariantList<Config.ColorEmitter> config = Config.getBlockEmitterConfig(blockEntity.getBlockState().getBlock());
            if (config != null && config.getDefault() != null) return config.getDefault().color();
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

        // most placeable light sources share the block's id (torch, lantern, glowstone, ...)
        VariantList<Config.ColorEmitter> blockConfig = Config.getBlockEmitterConfig(itemId);
        if (blockConfig != null && blockConfig.getDefault() != null) return blockConfig.getDefault().color();

        if (stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock().defaultBlockState().getLightEmission() > 0) {
            ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(blockItem.getBlock());
            if (blockId != null && !blockId.equals(itemId)) {
                VariantList<Config.ColorEmitter> config = Config.getBlockEmitterConfig(blockItem.getBlock());
                if (config != null && config.getDefault() != null) return config.getDefault().color();
            }
            return Config.defaultColor; // emits light but has no configured color
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
