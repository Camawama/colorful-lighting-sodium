package me.erykczy.colorfullighting.common;

import me.erykczy.colorfullighting.ColorfulLighting;
import me.erykczy.colorfullighting.common.accessors.BlockStateAccessor;
import me.erykczy.colorfullighting.common.accessors.LevelAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Publishes block entity NBT to the light propagator thread.
 *
 * <p>The propagator must never touch a live {@link BlockEntity}: serializing one walks its inventory,
 * which the client thread mutates concurrently. So block entities are serialized on the client thread
 * only, and the resulting immutable {@link CompoundTag} snapshots are published here for
 * {@link Config} to read from any thread.
 *
 * <p>Only block entities whose block has an NBT-conditioned rule are tracked, so a pack that uses no
 * NBT rules pays nothing. Tracked block entities are re-serialized every client tick; a relight is
 * queued only when the <em>resolved</em> emitter/filter/absorber changes, not when the NBT changes.
 * That distinction matters: a lit furnace rewrites {@code CookTime} every tick, and relighting on
 * every NBT delta would re-propagate light continuously.
 */
public final class BlockEntityNbtCache {
    /** Written on the client thread, read from the light propagator thread. */
    private static final ConcurrentHashMap<Long, CompoundTag> SNAPSHOTS = new ConcurrentHashMap<>();
    /** Client thread only. */
    private static final Map<Long, Tracked> TRACKED = new HashMap<>();

    private static boolean loggedSaveFailure = false;

    private BlockEntityNbtCache() {}

    private static final class Tracked {
        final BlockEntity blockEntity;
        boolean seeded;
        Config.ColorEmitter emitter;
        Config.ColorFilter filter;
        Config.ColorEmitter absorber;

        Tracked(BlockEntity blockEntity) {
            this.blockEntity = blockEntity;
        }
    }

    /**
     * The last NBT snapshot of the block entity at {@code pos}, or null when there is none. Safe to
     * call from any thread.
     */
    @Nullable
    public static CompoundTag get(BlockPos pos) {
        if (SNAPSHOTS.isEmpty()) return null;
        return SNAPSHOTS.get(pos.asLong());
    }

    public static void onBlockEntityAdded(BlockEntity blockEntity) {
        // Runs for every block entity in every loaded chunk, so both checks must be near-free:
        // an empty-set test, then a reference compare against the blocks that carry NBT rules.
        if (!Config.anyBlockNeedsNbt()) return;
        if (!Config.blockNeedsNbt(blockEntity.getBlockState().getBlock())) return;

        long packedPos = blockEntity.getBlockPos().asLong();
        Tracked tracked = new Tracked(blockEntity);
        TRACKED.put(packedPos, tracked);
        // A block entity from a chunk packet is registered before its tag is loaded, so this seeds an
        // empty snapshot; onChunkLoaded and the tick scan correct it.
        refresh(packedPos, tracked, false);
    }

    public static void onBlockEntityRemoved(BlockPos pos) {
        long packedPos = pos.asLong();
        if (TRACKED.remove(packedPos) != null) {
            SNAPSHOTS.remove(packedPos);
        }
    }

    /** Snapshots a freshly received chunk's block entities before its light is propagated. */
    public static void onChunkLoaded(LevelChunk chunk) {
        if (!Config.anyBlockNeedsNbt()) return;
        for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
            onBlockEntityAdded(blockEntity);
        }
    }

    /** A block entity just received new data from the server; relight now rather than on the next tick. */
    public static void onBlockEntityDataChanged(BlockPos pos) {
        Tracked tracked = TRACKED.get(pos.asLong());
        if (tracked != null) refresh(pos.asLong(), tracked, true);
    }

    public static void clear() {
        TRACKED.clear();
        SNAPSHOTS.clear();
    }

    /**
     * Re-derives which block entities need tracking after the light configs are reloaded. Must run
     * before the engine resets: it re-propagates light asynchronously, and any chunk it reaches before
     * the snapshots are republished would be lit from stale NBT with no relight to correct it.
     */
    public static void reload(@Nullable Level level, int centerChunkX, int centerChunkZ, int chunkRadius) {
        clear();
        if (level != null && Config.anyBlockNeedsNbt()) {
            rescan(level, centerChunkX, centerChunkZ, chunkRadius);
        }
    }

    /**
     * Re-serializes every tracked block entity and queues a relight where the resolved light changed.
     * Called once per client tick, on the client thread.
     */
    public static void clientTick() {
        if (TRACKED.isEmpty()) return;

        Iterator<Map.Entry<Long, Tracked>> iterator = TRACKED.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, Tracked> entry = iterator.next();
            Tracked tracked = entry.getValue();
            if (tracked.blockEntity.isRemoved()) {
                iterator.remove();
                SNAPSHOTS.remove(entry.getKey());
                continue;
            }
            refresh(entry.getKey(), tracked, true);
        }
    }

    private static void rescan(Level level, int centerChunkX, int centerChunkZ, int chunkRadius) {
        for (int x = centerChunkX - chunkRadius; x <= centerChunkX + chunkRadius; x++) {
            for (int z = centerChunkZ - chunkRadius; z <= centerChunkZ + chunkRadius; z++) {
                LevelChunk chunk = level.getChunkSource().getChunk(x, z, false);
                if (chunk != null) onChunkLoaded(chunk);
            }
        }
    }

    /**
     * @param allowRelight false while seeding, when the chunk's light has not been propagated yet and
     *                     a relight request would be wasted work
     */
    private static void refresh(long packedPos, Tracked tracked, boolean allowRelight) {
        CompoundTag tag = snapshot(tracked.blockEntity);
        if (tag == null) return; // serialization failed; keep the previous snapshot

        // Publish before resolving: a block entity registered while its chunk is still being
        // deserialized has no resolvable block state yet, but the propagator must already see its NBT.
        SNAPSHOTS.put(packedPos, tag);

        LevelAccessor level = ColorfulLighting.clientAccessor == null ? null : ColorfulLighting.clientAccessor.getLevel();
        if (level == null) return;
        BlockPos pos = BlockPos.of(packedPos);
        BlockStateAccessor blockState = level.getBlockState(pos);
        if (blockState == null) {
            tracked.seeded = false; // resolve once the chunk is reachable, on a later tick
            return;
        }

        Config.ColorEmitter emitter = Config.resolveEmitter(blockState, tag);
        Config.ColorFilter filter = Config.resolveFilter(blockState, tag);
        Config.ColorEmitter absorber = Config.resolveAbsorber(blockState, tag);

        boolean changed = tracked.seeded
                && (!Objects.equals(emitter, tracked.emitter)
                || !Objects.equals(filter, tracked.filter)
                || !Objects.equals(absorber, tracked.absorber));

        tracked.emitter = emitter;
        tracked.filter = filter;
        tracked.absorber = absorber;
        tracked.seeded = true;

        if (changed && allowRelight) {
            ColoredLightEngine.getInstance().onBlockLightPropertiesChanged(pos);
        }
    }

    @Nullable
    private static CompoundTag snapshot(BlockEntity blockEntity) {
        try {
            return blockEntity.saveWithoutMetadata();
        }
        catch (Throwable e) {
            if (!loggedSaveFailure) {
                loggedSaveFailure = true;
                ColorfulLighting.LOGGER.warn("Failed to read NBT of block entity {}; its NBT light rules will not apply",
                        blockEntity.getType(), e);
            }
            return null;
        }
    }
}
