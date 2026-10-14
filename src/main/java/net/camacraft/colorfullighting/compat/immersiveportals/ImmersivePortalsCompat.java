package net.camacraft.colorfullighting.compat.immersiveportals;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.camacraft.colorfullighting.ColorfulLighting;
import net.camacraft.colorfullighting.common.ColoredLightEngine;
import net.camacraft.colorfullighting.common.ViewArea;
import net.camacraft.colorfullighting.common.accessors.mixin.LevelAttachments;
import net.camacraft.colorfullighting.compat.CompatRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Keeps colored light alive in client levels the player is not currently in.
 *
 * <p>Immersive Portals (and mods like it) keep several ClientLevels loaded at once and render a
 * remote dimension through portals. Only the current level is ticked through Forge's
 * LevelTickEvent (Immersive Portals ticks remote levels itself, without the Forge hooks), so the
 * engine's view area is never established there and every sample takes the vanilla-white
 * out-of-area fallback until the player has visited that dimension once. Rather than binding to
 * any Immersive Portals API, this tracks which chunks the client actually has loaded in each
 * level (chunks only exist in a non-current level when such a mod put them there) and mirrors
 * them into that level's engine as extra light regions, grouped into cells of
 * {@code CELL_SIZE * CELL_SIZE} chunks.
 *
 * <p>While a level IS the current one its coverage comes from the engine's view area, which also
 * filters out the chunks that can never propagate (see ColoredLightEngine#updateViewArea), so its
 * cells are withdrawn (chunk tracking continues, ready for the level to go remote again). The
 * withdrawal is a bookkeeping handover, not a purge: the engine keeps every section the view area
 * holds and only drops columns that neither the view area nor another cell covers.
 *
 * <p>Ordering matters for that handover. Immersive Portals can change the player's dimension from
 * its own hook at the tail of Minecraft#tick, i.e. after the level tick (which already ran for the
 * OLD level) but before ClientTickEvent END. Withdrawing the new level's cells from the client tick
 * at that point would run before the new level's view area exists, so nothing would be tracked and
 * every section of colored light would be wiped, to be re-propagated and re-meshed from scratch on
 * the following ticks. The withdrawal therefore runs from the level tick, right after the view area
 * update ({@link #onCurrentLevelTick}), where the ordering holds by construction; the client tick
 * only ever adds coverage to non-current levels.
 *
 * <p>Client thread only: chunk load/unload events, level unload, and both tick handlers run there.
 */
public final class ImmersivePortalsCompat {
    /** Cells are 2^CELL_SHIFT chunks on a side. */
    private static final int CELL_SHIFT = 3;
    private static final int CELL_SIZE = 1 << CELL_SHIFT;
    /**
     * How often (in client ticks) tracked chunks are checked against the chunk source, dropping any
     * whose unload event never arrived. Safety net only; unloads are normally seen as events.
     */
    private static final int VALIDATE_INTERVAL_TICKS = 100;

	public static final CompatRegistry.CompatKey<Level, LevelState> COMPAT_KEY = new CompatRegistry.CompatKey<>(ResourceLocation.parse("colorful_lighting:immersive_portals"), (lvl) -> new LevelState());
    /**
     * Passed to {@link ColoredLightEngine#syncExtraRegions(long, Map)} to withdraw every cell of a
     * level: the engine reconciles the remote-level namespace against this empty map and removes
     * the regions it still holds. Only columns nothing else tracks lose their sections.
     */
    private static final Map<Long, ColoredLightEngine.LightRegion> NO_REGIONS = Map.of();
    /**
     * Every client level that has had a chunk, for {@link #clientTick}: the per-level state lives in
     * the level's compat registry, which cannot be enumerated. Weak, so a level that unloads without
     * its LevelEvent.Unload is not pinned; removed explicitly in {@link #onLevelUnload}.
     */
    private static final Set<Level> LEVELS = Collections.newSetFromMap(new WeakHashMap<>());

    private ImmersivePortalsCompat() {}

    private static final class LevelState {
        /** Cell key -> chunks the client currently has loaded in that cell. */
        final Long2ObjectOpenHashMap<Set<ChunkPos>> cellChunks = new Long2ObjectOpenHashMap<>();
        /** Regions mirroring cellChunks; values are immutable snapshots handed to the engine. */
        final Map<Long, ColoredLightEngine.LightRegion> regions = new HashMap<>();
        final LongOpenHashSet dirtyCells = new LongOpenHashSet();
	    public int tickCounter;
	    /** Whether the engine currently holds this level's cells (i.e. the level was remote). */
        boolean cellsHeldByEngine;
        // Diagnostics for the dimension-change transient: activity in the seconds after a handover.
        int chunkLoads, chunkUnloads;
        int fallbackSamplesAtHandover;
        int ticksUntilSummary;
    }

    public static void onChunkLoad(ClientLevel level, ChunkPos pos) {
	    LevelState state = ((CompatRegistry<Level>) level).colorfullighting$getCompatInstance(COMPAT_KEY);
        LEVELS.add(level);
        state.chunkLoads++;
        long cell = cellKey(pos.x >> CELL_SHIFT, pos.z >> CELL_SHIFT);
        if (state.cellChunks.computeIfAbsent(cell, c -> new HashSet<>()).add(pos)) {
            state.dirtyCells.add(cell);
        }
    }

    public static void onChunkUnload(ClientLevel level, ChunkPos pos) {
	    LevelState state = ((CompatRegistry<Level>) level).colorfullighting$getCompatInstance(COMPAT_KEY);
        if (state == null) return;
        state.chunkUnloads++;
        long cell = cellKey(pos.x >> CELL_SHIFT, pos.z >> CELL_SHIFT);
        Set<ChunkPos> chunks = state.cellChunks.get(cell);
        if (chunks != null && chunks.remove(pos)) {
            if (chunks.isEmpty()) state.cellChunks.remove(cell);
            state.dirtyCells.add(cell);
        }
    }

    /**
     * Called from the LevelTickEvent handler for the current level, after its engine's view area
     * has been updated for this tick. Hands coverage over from the cells (held while the level was
     * remote) to the view area. See the class comment for why this must not run from the client
     * tick.
     */
    public static void onCurrentLevelTick(Level level) {
	    LevelState state = ((CompatRegistry<Level>) level).colorfullighting$getCompatInstance(COMPAT_KEY);
        if (state == null) return;
        ColoredLightEngine engine = ((LevelAttachments) level).colorfullighting$getEngine();
        if (engine == null) return;
        if (state.ticksUntilSummary > 0 && --state.ticksUntilSummary == 0) {
            // Two seconds after the handover: did the client reload chunks, and did any mesh get
            // built against a missing section (the white fallback) in that window?
            ColorfulLighting.LOGGER.info(
                    "[CL portal] 2s after {} became current: {} chunk loads, {} chunk unloads, {} white-fallback samples, sections {}",
                    level.dimension().location(), state.chunkLoads, state.chunkUnloads,
                    engine.debugFallbackSamples() - state.fallbackSamplesAtHandover, engine.debugStoredSectionCount());
        }
        if (!state.cellsHeldByEngine) return;
        state.chunkLoads = 0;
        state.chunkUnloads = 0;
        state.fallbackSamplesAtHandover = engine.debugFallbackSamples();
        state.ticksUntilSummary = 40;
        int sectionsBefore = engine.debugStoredSectionCount();
        int queuedBefore = engine.debugQueuedChunkCount();
        engine.syncExtraRegions(ColoredLightEngine.REGION_NAMESPACE_REMOTE_LEVEL, NO_REGIONS);
        state.cellsHeldByEngine = false;
        // One line per dimension change; a healthy handover drops only what lies beyond the view
        // area (a few hundred sections at most) and never re-queues chunks.
        ColorfulLighting.LOGGER.info(
                "[CL portal] {} is now the current level: withdrew {} cells, view area {}, sections {} -> {}, queued chunks {} -> {}",
                level.dimension().location(), state.regions.size(), engine.debugViewArea(),
                sectionsBefore, engine.debugStoredSectionCount(), queuedBefore, engine.debugQueuedChunkCount());
    }

    public static void onLevelUnload(Level level) {
        LEVELS.remove(level);
    }

    /**
     * Called once per client tick (ClientTickEvent END) from ClientEventListener. Keeps the cells
     * of every level that is NOT the current one synced into that level's engine. This is the one
     * place that must visit all levels, hence a client tick rather than a level tick: only the
     * current level receives LevelTickEvent. (The 2026-09-05 refactor folded this into
     * onCurrentLevelTick, which only ever runs for the current level and returned before it, so
     * remote levels never got their cells and portals showed them white again.)
     */
    public static void clientTick(Minecraft minecraft) {
        if (LEVELS.isEmpty()) return;
        for (Level level : LEVELS) {
            // The current level is covered by its view area; its handover runs from the level tick.
            if (level == minecraft.level) continue;
            LevelState state = ((CompatRegistry<Level>) level).colorfullighting$getCompatInstance(COMPAT_KEY);
            if (state == null) continue;
            ColoredLightEngine engine = ((LevelAttachments) level).colorfullighting$getEngine();
            if (engine == null) continue;

            boolean validate = ++state.tickCounter % VALIDATE_INTERVAL_TICKS == 0;
            if (validate) validateAgainstChunkSource(level, state);
            rebuildDirtyCells(state);
            // Synced every tick even when unchanged: an engine reset (/cl on, /cl purge) drops all
            // extra regions and relies on their owners re-syncing them.
            engine.syncExtraRegions(ColoredLightEngine.REGION_NAMESPACE_REMOTE_LEVEL, state.regions);
            state.cellsHeldByEngine = true;
        }
    }

    private static void validateAgainstChunkSource(Level level, LevelState state) {
        var chunkSource = level.getChunkSource();
        for (var it = state.cellChunks.long2ObjectEntrySet().fastIterator(); it.hasNext(); ) {
            Long2ObjectMap.Entry<Set<ChunkPos>> entry = it.next();
            Set<ChunkPos> chunks = entry.getValue();
            if (chunks.removeIf(pos -> !chunkSource.hasChunk(pos.x, pos.z))) {
                state.dirtyCells.add(entry.getLongKey());
                if (chunks.isEmpty()) it.remove();
            }
        }
    }

    private static void rebuildDirtyCells(LevelState state) {
        if (state.dirtyCells.isEmpty()) return;
        for (LongIterator it = state.dirtyCells.iterator(); it.hasNext(); ) {
            long cell = it.nextLong();
            long regionKey = regionKey(cell);
            Set<ChunkPos> chunks = state.cellChunks.get(cell);
            if (chunks == null || chunks.isEmpty()) {
                state.regions.remove(regionKey);
            } else {
                int minChunkX = cellX(cell) << CELL_SHIFT;
                int minChunkZ = cellZ(cell) << CELL_SHIFT;
                // The cell's chunks plus a 1-chunk border, mirroring ViewArea's border: propagation
                // out of an inner chunk writes into its neighbours' sections.
                ViewArea area = new ViewArea(minChunkX - 1, minChunkZ - 1,
                        minChunkX + CELL_SIZE, minChunkZ + CELL_SIZE);
                state.regions.put(regionKey, new ColoredLightEngine.LightRegion(area, Set.copyOf(chunks)));
            }
        }
        state.dirtyCells.clear();
    }

    private static long cellKey(int cellX, int cellZ) {
        return ((long) cellX << 32) | (cellZ & 0xFFFFFFFFL);
    }

    private static int cellX(long cellKey) {
        return (int) (cellKey >> 32);
    }

    private static int cellZ(long cellKey) {
        return (int) cellKey;
    }

    /**
     * The engine-facing region key: the cell coordinates packed into 24 bits each (far beyond the
     * world border at 128-block cells) under the remote-level namespace.
     */
    private static long regionKey(long cellKey) {
        return ColoredLightEngine.REGION_NAMESPACE_REMOTE_LEVEL
                | ((cellX(cellKey) & 0xFFFFFFL) << 24)
                | (cellZ(cellKey) & 0xFFFFFFL);
    }
}
