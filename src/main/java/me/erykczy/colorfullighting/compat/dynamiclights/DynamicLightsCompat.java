package me.erykczy.colorfullighting.compat.dynamiclights;

import me.erykczy.colorfullighting.ColorfulLighting;
import me.erykczy.colorfullighting.common.ColoredLightEngine;
import me.erykczy.colorfullighting.common.Config;
import me.erykczy.colorfullighting.common.accessors.LevelAccessor;
import me.erykczy.colorfullighting.common.util.ColorRGB4;
import me.erykczy.colorfullighting.compat.sodium.SodiumCompat;
import me.erykczy.colorfullighting.mixin.compat.sodium.SodiumWorldRendererAccessor;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compatibility with dynamic lighting mods (Torcy, AtomicStryker's Dynamic Lights,
 * SodiumDynamicLights / "DynamicLights Reforged", Lively Lighting). Their light reaches the colored
 * pipeline two ways, and both are covered so the mechanism of the installed version never matters:
 * <ul>
 *   <li><b>Light blocks</b>: Lively Lighting (server-only) places minecraft:light blocks, which the
 *       colored engine already propagates; this class only supplies their color, inferred from the
 *       nearby entity that caused them. minecraft:light placed by any other mod and the
 *       dynamiclights:lit_* blocks shipped in AtomicStryker's 1.20.1 jar are colored the same way.</li>
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
 */
public final class DynamicLightsCompat {
    /** LambDynamicLights falloff radius; matches SodiumDynamicLights so color aligns with its brightness. */
    private static final double MAX_RADIUS = 7.75;
    private static final double MAX_RADIUS_SQUARED = MAX_RADIUS * MAX_RADIUS;
    /** Sections within this many blocks of a changed source get remeshed (light radius rounded up). */
    private static final int REBUILD_RANGE = 8;
    /** A tracked source must move this far (squared) before its surroundings are remeshed. */
    private static final double REBUILD_MOVE_THRESHOLD_SQUARED = 0.5 * 0.5;

    /** Light-emitting blocks placed by dynamic lighting mods, colored by the entity that caused them. */
    private static final Set<ResourceLocation> DYNAMIC_LIGHT_BLOCKS = Set.of(
            new ResourceLocation("minecraft", "light"),
            new ResourceLocation("dynamiclights", "lit_air"),
            new ResourceLocation("dynamiclights", "lit_cave_air"),
            new ResourceLocation("dynamiclights", "lit_water")
    );

    private record DynamicSource(double x, double y, double z, int luminance, ColorRGB4 color) {}
    private static final DynamicSource[] NO_SOURCES = new DynamicSource[0];
    // written on the client thread each tick, read from chunk-build worker threads
    private static volatile DynamicSource[] entitySources = NO_SOURCES;

    private static SodiumDynamicLightsHook sdlHook;
    private static boolean trackEntities;
    /** Last remesh anchor per tracked entity id, so terrain updates as sources move. Client thread only. */
    private static Map<Integer, DynamicSource> trackedAnchors = new HashMap<>();

    private DynamicLightsCompat() {}

    public static void init() {
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
     * Refreshes the dynamic light sources for this tick: snapshots SodiumDynamicLights' tracked
     * sources and/or scans luminous entities, into an immutable array so light sampling on
     * chunk-build threads never touches live collections. Called once per client tick.
     */
    public static void clientTick() {
        if (sdlHook == null && !trackEntities) return;

        ColoredLightEngine engine = ColoredLightEngine.getInstance();
        ClientLevel level = Minecraft.getInstance().level;
        if (engine == null || !engine.isEnabled() || level == null) {
            entitySources = NO_SOURCES;
            trackedAnchors = new HashMap<>();
            return;
        }

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
        if (trackEntities) {
            collectTrackedEntities(level, sources);
        }
        entitySources = sources.isEmpty() ? NO_SOURCES : sources.toArray(NO_SOURCES);
    }

    /**
     * Scans the client world for luminous entities and schedules remeshes around the ones that
     * appeared, changed, moved or vanished — client-lighting mods leave no light data or chunk
     * updates behind for the engine to react to, so this drives both the light and its updates.
     */
    private static void collectTrackedEntities(ClientLevel level, List<DynamicSource> sources) {
        Map<Integer, DynamicSource> anchors = new HashMap<>();
        Set<Long> sectionsToRebuild = null;

        for (Entity entity : level.entitiesForRendering()) {
            if (entity.isSpectator()) continue;
            int luminance = getEntityLuminance(entity);
            if (luminance <= 0) continue;

            ColorRGB4 color = resolveEntityColor(entity);
            if (color == null) color = Config.defaultColor;
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
        scheduleRebuilds(sectionsToRebuild);
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

    private static void scheduleRebuilds(@Nullable Set<Long> sections) {
        if (sections == null) return;
        LevelAccessor level = ColorfulLighting.clientAccessor == null ? null : ColorfulLighting.clientAccessor.getLevel();
        if (level == null) return;
        var renderer = Minecraft.getInstance().levelRenderer;
        int minSectionY = level.getMinSectionY();
        int maxSectionY = level.getMaxSectionY();

        for (long key : sections) {
            SectionPos pos = SectionPos.of(key);
            if (pos.y() < minSectionY || pos.y() > maxSectionY) continue;
            level.setSectionDirty(pos.x(), pos.y(), pos.z());
            if (SodiumCompat.isSodiumLoaded() && renderer instanceof SodiumWorldRendererAccessor sodiumRenderer) {
                sodiumRenderer.scheduleRebuild(pos.x(), pos.y(), pos.z(), false);
            }
        }
    }

    /**
     * Per-channel max of the stored light color and the contribution of tracked dynamic light
     * sources at the given block. No-op while nothing is tracked. Thread-safe.
     */
    public static ColorRGB4 maxWithDynamicLight(int blockX, int blockY, int blockZ, ColorRGB4 base) {
        DynamicSource[] sources = entitySources;
        if (sources.length == 0) return base;

        double x = blockX + 0.5, y = blockY + 0.5, z = blockZ + 0.5;
        int r = base.red4, g = base.green4, b = base.blue4;
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
        if (r == base.red4 && g == base.green4 && b == base.blue4) return base;
        return ColorRGB4.fromRGB4(r, g, b);
    }

    public static boolean isDynamicLightBlock(ResourceLocation blockId) {
        return DYNAMIC_LIGHT_BLOCKS.contains(blockId);
    }

    /**
     * Color for a light block placed by a dynamic lighting mod, inferred from the nearest entity that
     * resolves to one. Null when nothing nearby resolves (callers fall back to plain white light).
     */
    @Nullable
    public static ColorRGB4 getDynamicBlockLightColor(BlockPos lightBlockPos) {
        Level level = Minecraft.getInstance().level;
        if (level == null) return null;

        List<Entity> nearbyEntities = level.getEntitiesOfClass(Entity.class, new AABB(lightBlockPos).inflate(3.0));
        ColorRGB4 bestColor = null;
        double bestDistanceSquared = Double.MAX_VALUE;
        for (Entity entity : nearbyEntities) {
            double distanceSquared = entity.distanceToSqr(lightBlockPos.getX() + 0.5, lightBlockPos.getY() + 0.5, lightBlockPos.getZ() + 0.5);
            if (distanceSquared >= bestDistanceSquared) continue;
            ColorRGB4 color = resolveEntityColor(entity);
            if (color == null) continue;
            bestColor = color;
            bestDistanceSquared = distanceSquared;
        }
        return bestColor;
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

    private static ColorRGB4 resolveSourceColor(Object source) {
        if (source instanceof Entity entity) {
            ColorRGB4 color = resolveEntityColor(entity);
            return color != null ? color : Config.defaultColor;
        }
        if (source instanceof BlockEntity blockEntity) {
            ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(blockEntity.getBlockState().getBlock());
            if (blockId != null) {
                Config.BlockEmitterConfig config = Config.getBlockEmitterConfig(blockId);
                if (config != null && config.defaultEmitter != null) return config.defaultEmitter.color();
            }
        }
        return Config.defaultColor;
    }

    /** Light color a dynamic light source entity should cast, or null when nothing about it resolves. */
    @Nullable
    private static ColorRGB4 resolveEntityColor(Entity entity) {
        ResourceLocation entityId = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        if (entityId != null) {
            Config.ColorEmitter emitter = Config.getEntityEmitter(entityId);
            if (emitter != null) return emitter.color();
        }

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

        if (entity.isOnFire()) return Config.getLightColor(Blocks.FIRE.builtInRegistryHolder().key());
        return null;
    }

    /** Light color of an item stack, or null when the stack isn't a known light emitter. */
    @Nullable
    private static ColorRGB4 resolveStackColor(ItemStack stack) {
        if (stack.isEmpty()) return null;
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (itemId == null) return null;

        Config.ColorEmitter itemEmitter = Config.getItemEmitter(itemId);
        if (itemEmitter != null) return itemEmitter.color();

        // most placeable light sources share the block's id (torch, lantern, glowstone, ...)
        Config.BlockEmitterConfig blockConfig = Config.getBlockEmitterConfig(itemId);
        if (blockConfig != null && blockConfig.defaultEmitter != null) return blockConfig.defaultEmitter.color();

        if (stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock().defaultBlockState().getLightEmission() > 0) {
            ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(blockItem.getBlock());
            if (blockId != null && !blockId.equals(itemId)) {
                Config.BlockEmitterConfig config = Config.getBlockEmitterConfig(blockId);
                if (config != null && config.defaultEmitter != null) return config.defaultEmitter.color();
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
        Config.ColorEmitter itemEmitter = itemId == null ? null : Config.getItemEmitter(itemId);
        if (itemEmitter != null && itemEmitter.overriddenBrightness4() >= 0) {
            return itemEmitter.overriddenBrightness4();
        }

        // otherwise a held block glows like the same block placed in the world: an emitters.json
        // brightness override if present, else the block's vanilla emission (glowstone item == 15)
        if (stack.getItem() instanceof BlockItem blockItem) {
            ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(blockItem.getBlock());
            if (blockId != null) {
                Config.BlockEmitterConfig config = Config.getBlockEmitterConfig(blockId);
                if (config != null && config.defaultEmitter != null && config.defaultEmitter.overriddenBrightness4() >= 0) {
                    return config.defaultEmitter.overriddenBrightness4();
                }
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
