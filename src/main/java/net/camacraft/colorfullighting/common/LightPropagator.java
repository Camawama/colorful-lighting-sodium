package net.camacraft.colorfullighting.common;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.camacraft.colorfullighting.ColorfulLighting;
import net.camacraft.colorfullighting.common.accessors.BlockStateAccessor;
import net.camacraft.colorfullighting.common.accessors.LevelAccessor;
import net.camacraft.colorfullighting.common.accessors.PlayerAccessor;
import net.camacraft.colorfullighting.common.engine.DefaultBlockLightEngine;
import net.camacraft.colorfullighting.common.util.ColorRGB4;
import net.camacraft.colorfullighting.common.util.MathExt;
import net.camacraft.colorfullighting.common.util.ShapeOcclusion;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static net.camacraft.colorfullighting.ColorfulLighting.clientAccessor;
import static net.camacraft.colorfullighting.common.ColoredLightEngine.*;

/**
 * LightPropagator calculates changes to light values. It runs on another thread to avoid lag on the main thread.
 * It propagates increases (increases of light values, e.g. new light source has been placed).
 * It propagates decreases (decreases of light values, e.g. light source has been destroyed, solid block has been placed in the path of light).
 * Changes caused by block updates are applied on the main thread to avoid light flickering
 */
@Deprecated(forRemoval = true)
public class LightPropagator implements Runnable {
	class EngineBox {
		WeakReference<ColoredLightEngine> weakRef;
		
		public EngineBox(ColoredLightEngine engine) {
			this.weakRef = new WeakReference<>(engine);
		}
		
		public ColoredLightEngine getEngine() {
			return weakRef.get();
		}
	}
	
    /**
     * Sections touched by the matching ready batch, computed on this thread when the batch is published
     * rather than on the render thread when it is applied. Each guarded by the lock of its batch, so a
     * section mark is never visible to the renderer before the storage write it belongs to.
     */
    private final LongOpenHashSet lightReadyDirtySections = new LongOpenHashSet();
    private final LongOpenHashSet darknessReadyDirtySections = new LongOpenHashSet();
    private boolean running;
    private volatile boolean shutdown = false;

    private final ChunkOrder lightChunkOrder = new ChunkOrder();
    private final ChunkOrder darknessChunkOrder = new ChunkOrder();

    /** Direct measurement of a drain: terrain makes profiler frame times a poor proxy for throughput. */
    private long drainStartNanos;
    private long lastChunkNanos;
    private int drainChunks;

    /** Backoff for a queue that cannot progress; see the blocked branch in run(). */
    private long blockedSleepMillis = MIN_BLOCKED_SLEEP_MILLIS;
    private int lastChunksRemaining = -1;

    public boolean hasReadyLightChanges(ColoredLightEngine engine) {
        return !engine.lightEngine.changesReady.isEmpty();
    }

    public boolean hasReadyDarknessChanges(ColoredLightEngine engine) {
        return !engine.darkEngine.changesReady.isEmpty();
    }

    public boolean hasReadyChanges(ColoredLightEngine engine) {
        return hasReadyLightChanges(engine) || hasReadyDarknessChanges(engine);
    }
	
	EngineBox box;
	
	public LightPropagator(ColoredLightEngine engine) {
		box = new EngineBox(engine);
	}
	
	@Override
    public void run() {
        running = true;
		
        while (running) {
	        try {
	            long sleepMillis;
			
		        synchronized (this) {
			        sleepMillis = doWork();
		        }
				
				if (sleepMillis != 0) {
					Thread.sleep(sleepMillis);
				}
				
				if (shutdown) {
					return;
				}
	        } catch (InterruptedException e) {
				return;
	        } catch (Throwable t) {
		        // An escaped exception used to KILL this thread silently: colored light then froze
		        // for the whole level until '/cl purge' built a new propagator (the long-standing
		        // "randomly stops working" bug; racing off-thread palette reads are one known
		        // thrower). Every queue this loop drains is safe to retry, so log and keep going.
		        propagatorErrors++;
		        if (propagatorErrors <= 5) {
			        ColorfulLighting.LOGGER.error(
					        "Colored light propagator pass failed (error {}); continuing", propagatorErrors, t);
			        if (propagatorErrors == 5) {
				        ColorfulLighting.LOGGER.error("Further colored light propagator errors will not be logged");
			        }
		        }
		        try {
			        Thread.sleep(100L); // don't spin hot if the error is persistent
		        } catch (InterruptedException e) {
			        return;
		        }
	        }
        }
    }

	// outlined: try to prevent reference from existing while thread sleeps
	protected long doWork() {
		ColoredLightEngine engine = box.getEngine();
		// if our engine no longer exists, or is not using this propagator anymore, then thread is ready to die
		if (engine == null || engine.lightPropagator != this) {
			running = false;
			return 0;
		}
		
		// Process delayed chunk updates
		long now = System.currentTimeMillis();
		Iterator<ColoredLightEngine.DelayedChunkUpdate> it = engine.delayedChunkUpdates.iterator();
		while (it.hasNext()) {
			ColoredLightEngine.DelayedChunkUpdate update = it.next();
			if (now >= update.executeTime()) {
				it.remove();
				engine.pendingDelayedUpdates.remove(update.chunkPos());
				performRegionRebuild(engine, update.chunkPos());
			}
		}
		
		boolean hasLightWork = engine.lightEngine.hasWork() || !engine.chunksWaitingForPropagation.isEmpty();
		boolean hasDarknessWork = engine.darkEngine.hasWork() || !engine.chunksWaitingForDarknessPropagation.isEmpty();
		boolean hasWork = hasLightWork || hasDarknessWork;
		
		// Re-read every pass so changing the config takes effect without a restart.
		ColorfulLightingConfig.LightUpdateSpeed speed = ColorfulLightingConfig.lightUpdateSpeed();
		
		// Track the CHUNK queue only. hasWork also covers single-block updates, and in the
		// Nether flowing lava and fire fire checkBlock constantly, so hasWork can essentially
		// never go false - the chunk fill-in would finish and the drain would never close.
		// Report a drain when the chunk queue empties OR when no chunk has propagated for 2s.
		// The latter matters: the queue never empties, because the corners of this square view
		// area fall outside the disc of chunks the server actually sends. Waiting for an empty
		// queue would mean never reporting at all.
		int chunksRemaining = engine.chunksWaitingForPropagation.size();
		if (chunksRemaining != lastChunksRemaining) {
			lastChunksRemaining = chunksRemaining;
			blockedSleepMillis = MIN_BLOCKED_SLEEP_MILLIS; // queue changed: something may be ready now
		}
		if (chunksRemaining > 0 && drainStartNanos == 0L) {
			drainStartNanos = System.nanoTime();
			lastChunkNanos = drainStartNanos;
		}
		if (drainStartNanos != 0L && drainChunks > 0) {
			boolean finished = chunksRemaining == 0;
			boolean stalled = System.nanoTime() - lastChunkNanos > 2_000_000_000L;
			if (finished || stalled) {
				if (drainChunks >= DRAIN_LOG_MIN_CHUNKS) {
					long elapsedMillis = Math.max(1L, (lastChunkNanos - drainStartNanos) / 1_000_000L);
					// The level tag matters: with Immersive Portals several levels run their own
					// propagators, and an untagged log can look healthy while ANOTHER level's
					// propagator is the broken one.
					ColorfulLighting.LOGGER.info(
							"Colored light drain [{}]: {} chunks in {} ms ({} chunks/s), {} still queued [{}], lightUpdateSpeed={}",
							levelName(engine), drainChunks, elapsedMillis,
							String.format("%.1f", drainChunks * 1000.0 / elapsedMillis),
							chunksRemaining, finished ? "finished" : "stalled", speed);
				}
				drainStartNanos = 0L;
				drainChunks = 0;
			}
		}

		// Diagnostic for the long-standing "light stops until /cl purge" bug: a stall where only
		// far view-area-corner chunks are queued is normal (the server never sends those), but a
		// waiting chunk NEAR the player means some readiness check keeps wrongly rejecting it.
		// Log which one so the next natural occurrence names the failing check.
		if (drainStartNanos != 0L && chunksRemaining > 0
				&& System.nanoTime() - lastChunkNanos > 5_000_000_000L) {
			logIfStuckNearPlayer(engine);
		}
		
		long passStartNanos = System.nanoTime();
		boolean progressed = false;
		if (hasWork) {
			// Keep propagating for a budget instead of sleeping 1ms after every single chunk:
			// profiling put ~16% of this thread inside Thread.sleep while work was queued.
			long deadline = System.nanoTime() + speed.budgetNanos();
			boolean progressedThisPass;
			do {
				if (shutdown) {
					return 0;
				}
				
				progressedThisPass = false;
				if (hasLightWork) progressedThisPass |= propagateLight(engine, engine.lightEngine);
				if (hasDarknessWork) progressedThisPass |= propagateDarkness(engine, engine.darkEngine);
				progressed |= progressedThisPass;
				
				hasLightWork = engine.lightEngine.hasWork() || !engine.chunksWaitingForPropagation.isEmpty();
				hasDarknessWork = engine.darkEngine.hasWork() || !engine.chunksWaitingForDarknessPropagation.isEmpty();
				// stop early when nothing moved: the queue is waiting on chunks to load
			} while (running && progressedThisPass && (hasLightWork || hasDarknessWork) && System.nanoTime() < deadline);
		} else {
			// If idle, check if we have sections to rebuild from explosions
			if (!engine.sectionsToRebuildLater.isEmpty()) {
				synchronized (engine.dirtySections) {
					engine.dirtySections.addAll(engine.sectionsToRebuildLater);
				}
				engine.sectionsToRebuildLater.clear();
				Minecraft.getInstance().execute(engine::onLightUpdate);
			}
		}
		
		if (this.hasReadyChanges(engine)) {
			Minecraft.getInstance().execute(engine::onLightUpdate);
		}
		
		if (hasWork && progressed) {
			// Work remains. Pausing here is what lightUpdateSpeed controls: every finished chunk
			// makes the renderer rebuild and re-upload its mesh, so spreading passes out trades
			// fill-in speed for a smoother framerate. The pause scales with the work actually
			// done, because a pass cannot stop mid-chunk and one Nether chunk dwarfs any fixed
			// budget - a constant pause therefore throttles almost nothing.
			blockedSleepMillis = MIN_BLOCKED_SLEEP_MILLIS;
			double pauseFactor = speed.pauseFactor();
			if (pauseFactor <= 0.0) {
				Thread.yield();
			} else {
				long workedMillis = (System.nanoTime() - passStartNanos) / 1_000_000L;
				return Math.min(200L, Math.max(1L, (long) (workedMillis * pauseFactor)));
			}
			
			running = engine.running;
			return 0;
		} else {
			long sleepMillis;
			if (!hasWork) {
				blockedSleepMillis = MIN_BLOCKED_SLEEP_MILLIS;
				sleepMillis = IDLE_SLEEP_MILLIS;
			} else if (engine.lightEngine.hasWork()
					|| engine.darkEngine.hasWork()) {
				// A placed torch must light up immediately: never back off on block updates.
				blockedSleepMillis = MIN_BLOCKED_SLEEP_MILLIS;
				sleepMillis = MIN_BLOCKED_SLEEP_MILLIS;
			} else {
				// Only chunks remain and none can propagate: the ones left are outside the disc
				// of chunks the server sends, or neighbour one that is, so they will never have a
				// full 3x3 of loaded neighbours. Without a backoff the propagator would wake every
				// 5ms for the rest of the session, walk the chunk order, and find nothing. The
				// queue-size check above resets the backoff as soon as anything changes.
				sleepMillis = blockedSleepMillis;
				blockedSleepMillis = Math.min(MAX_BLOCKED_SLEEP_MILLIS, blockedSleepMillis * 2L);
			}
			
			running = engine.running;
			return sleepMillis;
		}
	}
	
    public void stop() {
		running = false;
		shutdown = true;
    }


    public ColorRGB4 getLatestLightColor(ColoredLightEngine engine, BlockPos blockPos) {
        ColorRGB4 inProgress = engine.lightEngine.changesInProgress.get(blockPos);
        if (inProgress != null) return inProgress;
        
        ColorRGB4 ready = engine.lightEngine.changesReady.get(blockPos);
        if (ready != null) return ready;

        return engine.lightEngine.getColor(blockPos);
    }

    public ColorRGB4 getLatestDarknessColor(ColoredLightEngine engine, BlockPos blockPos) {
        ColorRGB4 inProgress = engine.darkEngine.changesInProgress.get(blockPos);
        if (inProgress != null) return inProgress;

        ColorRGB4 ready = engine.darkEngine.changesReady.get(blockPos);
        if (ready != null) return ready;

        return engine.darkEngine.getColor(blockPos);
    }

    private void performRegionRebuild(ColoredLightEngine engine, ChunkPos centerChunk) {
        int radius = 1; // 3x3 area
        int minChunkX = centerChunk.x - radius;
        int maxChunkX = centerChunk.x + radius;
        int minChunkZ = centerChunk.z - radius;
        int maxChunkZ = centerChunk.z + radius;

        // 0. Clear pending changes for the region to avoid contaminating the rebuild with stale data
        engine.lightEngine.changesInProgress.entrySet().removeIf(entry -> {
            ChunkPos pos = new ChunkPos(entry.getKey());
            return pos.x >= minChunkX && pos.x <= maxChunkX && pos.z >= minChunkZ && pos.z <= maxChunkZ;
        });
        engine.darkEngine.changesInProgress.entrySet().removeIf(entry -> {
            ChunkPos pos = new ChunkPos(entry.getKey());
            return pos.x >= minChunkX && pos.x <= maxChunkX && pos.z >= minChunkZ && pos.z <= maxChunkZ;
        });
        
        engine.lightEngine.changesReadyLock.lock();
        try {
            engine.lightEngine.changesReady.entrySet().removeIf(entry -> {
                ChunkPos pos = new ChunkPos(entry.getKey());
                return pos.x >= minChunkX && pos.x <= maxChunkX && pos.z >= minChunkZ && pos.z <= maxChunkZ;
            });
        } finally {
            engine.lightEngine.changesReadyLock.unlock();
        }
        engine.darkEngine.changesReadyLock.lock();
        try {
            engine.darkEngine.changesReady.entrySet().removeIf(entry -> {
                ChunkPos pos = new ChunkPos(entry.getKey());
                return pos.x >= minChunkX && pos.x <= maxChunkX && pos.z >= minChunkZ && pos.z <= maxChunkZ;
            });
        } finally {
            engine.darkEngine.changesReadyLock.unlock();
        }

        // 1. Clear storage for the 3x3 region and mark dirty
        synchronized (engine.storageLock) {
            synchronized (engine.dirtySections) {
                for (int cx = minChunkX; cx <= maxChunkX; cx++) {
                    for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                        for(int y = engine.level.getMinSectionY(); y <= engine.level.getMaxSectionY(); y++) {
                            long pos = SectionPos.asLong(cx, y, cz);
	                        engine.lightEngine.removeSection(pos);
	                        engine.darkEngine.removeSection(pos);
	                        engine.lightEngine.addSection(pos);
	                        engine.darkEngine.addSection(pos);
	                        engine.dirtySections.add(pos); // Mark as dirty so renderer updates even if no new light is found
                        }
                    }
                }
            }
        }
	    engine.structureVersion.incrementAndGet();

        Queue<LightUpdateRequest> increaseRequests = new ArrayDeque<>();
        Queue<LightUpdateRequest> darknessIncreaseRequests = new ArrayDeque<>();

        // 2. Find internal sources for all chunks in region
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
	            engine.level.findLightSources(new ChunkPos(cx, cz), (blockPos -> {
                    increaseRequests.add(new LightUpdateRequest(blockPos, Config.getColorEmission(engine.level, blockPos), false, true, false));
                }));
	            engine.level.findDarknessSources(new ChunkPos(cx, cz), (blockPos -> {
                    darknessIncreaseRequests.add(new LightUpdateRequest(blockPos, Config.getAbsorptionColor(engine.level, blockPos), false, true, false));
                }));
            }
        }

        // 3. Pull from neighbors OUTSIDE the region
        int minBlockY = engine.level.getMinSectionY() * 16;
        int maxBlockY = (engine.level.getMaxSectionY() + 1) * 16 - 1;

        int regionMinBlockX = minChunkX * 16;
        int regionMaxBlockX = (maxChunkX * 16) + 15;
        int regionMinBlockZ = minChunkZ * 16;
        int regionMaxBlockZ = (maxChunkZ * 16) + 15;

        for (int y = minBlockY; y <= maxBlockY; y++) {
            // North border of the whole region (check z-1)
            checkNeighborAndAdd(engine, increaseRequests, regionMinBlockX, regionMaxBlockX, y, regionMinBlockZ - 1, true);
            checkNeighborDarknessAndAdd(engine, darknessIncreaseRequests, regionMinBlockX, regionMaxBlockX, y, regionMinBlockZ - 1, true);
            // South border (check z+1)
            checkNeighborAndAdd(engine, increaseRequests, regionMinBlockX, regionMaxBlockX, y, regionMaxBlockZ + 1, true);
            checkNeighborDarknessAndAdd(engine, darknessIncreaseRequests, regionMinBlockX, regionMaxBlockX, y, regionMaxBlockZ + 1, true);
            // West border (check x-1)
            checkNeighborAndAdd(engine, increaseRequests, regionMinBlockZ, regionMaxBlockZ, y, regionMinBlockX - 1, false);
            checkNeighborDarknessAndAdd(engine, darknessIncreaseRequests, regionMinBlockZ, regionMaxBlockZ, y, regionMinBlockX - 1, false);
            // East border (check x+1)
            checkNeighborAndAdd(engine, increaseRequests, regionMinBlockZ, regionMaxBlockZ, y, regionMaxBlockX + 1, false);
            checkNeighborDarknessAndAdd(engine, darknessIncreaseRequests, regionMinBlockZ, regionMaxBlockZ, y, regionMaxBlockX + 1, false);
        }

        propagateIncreases(engine, engine.level, increaseRequests);
        propagateDarknessIncreases(engine, engine.level, darknessIncreaseRequests);
        applyChangesDirectly(engine);
    }

    private void checkNeighborAndAdd(ColoredLightEngine engine, Queue<ColoredLightEngine.LightUpdateRequest> requests, int start, int end, int y, int fixed, boolean isZFixed) {
        for (int i = start; i <= end; i++) {
            BlockPos pos = isZFixed ? new BlockPos(i, y, fixed) : new BlockPos(fixed, y, i);
            ColorRGB4 color = getLatestLightColor(engine, pos);
            if (color != null && (color.red4 > 0 || color.green4 > 0 || color.blue4 > 0)) {
                requests.add(new ColoredLightEngine.LightUpdateRequest(pos, color, true));
            }
        }
    }

    private void checkNeighborDarknessAndAdd(ColoredLightEngine engine, Queue<ColoredLightEngine.LightUpdateRequest> requests, int start, int end, int y, int fixed, boolean isZFixed) {
        for (int i = start; i <= end; i++) {
            BlockPos pos = isZFixed ? new BlockPos(i, y, fixed) : new BlockPos(fixed, y, i);
            ColorRGB4 color = getLatestDarknessColor(engine, pos);
            if (color != null && (color.red4 > 0 || color.green4 > 0 || color.blue4 > 0)) {
                requests.add(new ColoredLightEngine.LightUpdateRequest(pos, color, true));
            }
        }
    }

    private record NearestBlockRequestsResult(ColoredLightEngine.BlockRequests blockUpdate, int distanceBlocks) {}
    private NearestBlockRequestsResult getNearestBlockRequests(ColoredLightEngine engine, PlayerAccessor player, DefaultBlockLightEngine blockLightEngine) {
        // find chunk nearest player
        var iterator = blockLightEngine.blockUpdateIncreaseRequests.iterator();
        int minDistance = Integer.MAX_VALUE;
        ColoredLightEngine.BlockRequests nearestUpdate = null;
        while (iterator.hasNext()) {
            ColoredLightEngine.BlockRequests update = iterator.next();
            int distance = update.blockPos.distManhattan(player.getBlockPos());
            if (distance < minDistance) {
                minDistance = distance;
                nearestUpdate = update;
            }
        }
        return nearestUpdate == null ? null : new NearestBlockRequestsResult(nearestUpdate, minDistance);
    }

    private NearestBlockRequestsResult getNearestDarknessRequests(ColoredLightEngine engine, PlayerAccessor player, DefaultBlockLightEngine blockLightEngine) {
        // find chunk nearest player
        var iterator = blockLightEngine.blockUpdateIncreaseRequests.iterator();
        int minDistance = Integer.MAX_VALUE;
        ColoredLightEngine.BlockRequests nearestUpdate = null;
        while (iterator.hasNext()) {
            ColoredLightEngine.BlockRequests update = iterator.next();
            int distance = update.blockPos.distManhattan(player.getBlockPos());
            if (distance < minDistance) {
                minDistance = distance;
                nearestUpdate = update;
            }
        }
        return nearestUpdate == null ? null : new NearestBlockRequestsResult(nearestUpdate, minDistance);
    }

    private record NearestChunkResult(ChunkPos chunkPos, int distanceBlocks) {}
    /**
     * A cached ordering of the chunks still awaiting propagation: frustum-visible first, then
     * nearest to the player.
     *
     * <p>The nearest chunk used to be found by scanning the entire waiting collection — testing
     * nine neighbours for availability and allocating an AABB for a frustum test — once for every
     * chunk propagated. That is O(n) per chunk and O(n^2) to drain the queue; profiling put the
     * two scans at ~20% of this thread during a Nether dimension change.
     *
     * <p>The order is computed once and reused. It is rebuilt when the player crosses a chunk
     * boundary, when it is exhausted, or after a short TTL so camera movement still re-prioritises.
     * Priority is advisory, so a slightly stale ordering costs nothing but ordering.
     */
    private static final class ChunkOrder {
        private final ArrayList<ChunkPos> order = new ArrayList<>();
        private int cursor;
        private long rebuiltAtNanos = Long.MIN_VALUE;
        private ChunkPos rebuiltCenter;

        NearestChunkResult next(ColoredLightEngine engine, LevelAccessor level, PlayerAccessor player, Set<ChunkPos> waiting) {
            if (waiting.isEmpty()) return null;
            ChunkPos center = player.getChunkPos();
            long now = System.nanoTime();
            // Deliberately not rebuilding on cursor exhaustion: when every remaining chunk is
            // still loading, that would rebuild on every poll and reinstate the O(n)-per-poll cost.
            if (!center.equals(rebuiltCenter) || now - rebuiltAtNanos > ColoredLightEngine.CHUNK_ORDER_TTL_NANOS) {
                rebuild(engine, level, center, waiting, now);
            }

            while (cursor < order.size()) {
                ChunkPos chunkPos = order.get(cursor++);
                if (!waiting.contains(chunkPos)) continue; // already propagated, or left the view area
                if (!level.hasChunkAndNeighbours(chunkPos)) continue; // block state data not available yet
                return new NearestChunkResult(chunkPos, chunkPos.getChessboardDistance(center) * 16);
            }
            return null;
        }

        private void rebuild(ColoredLightEngine engine, LevelAccessor level, ChunkPos center, Set<ChunkPos> waiting, long now) {
            order.clear();
            cursor = 0;
            rebuiltAtNanos = now;
            rebuiltCenter = center;

            Frustum currentFrustum = engine.frustum;
            int minY = level.getLevel().getMinBuildHeight();
            int maxY = level.getLevel().getMaxBuildHeight();

            List<ChunkPos> visible = new ArrayList<>();
            List<ChunkPos> hidden = new ArrayList<>();
            ViewArea[] regionAreas = engine.extraRegionAreas; // volatile read; written on the client thread
            for (ChunkPos chunkPos : waiting) {
                // Extra-region chunks count as visible: a ship is usually on screen even though
                // its shipyard chunks never pass the frustum test at their real coordinates.
                boolean inView = ColoredLightEngine.inAnyArea(regionAreas, chunkPos.x, chunkPos.z)
                        || (currentFrustum != null && currentFrustum.isVisible(new AABB(
                                chunkPos.getMinBlockX(), minY, chunkPos.getMinBlockZ(),
                                chunkPos.getMaxBlockX() + 1, maxY, chunkPos.getMaxBlockZ() + 1)));
                (inView ? visible : hidden).add(chunkPos);
            }

            Comparator<ChunkPos> byDistance = Comparator.comparingInt(c -> c.getChessboardDistance(center));
            visible.sort(byDistance);
            hidden.sort(byDistance);
            order.addAll(visible); // anything the player can see outranks anything they cannot
            order.addAll(hidden);
        }
    }

    /** Rate limiter for {@link #logIfStuckNearPlayer}. */
    private long lastStuckLogMillis;
    /** Count of passes that threw; the thread survives them (see run()). */
    private int propagatorErrors;

    private static String levelName(ColoredLightEngine engine) {
        try {
            var level = engine.level.getLevel();
            return level == null ? "unknown" : level.dimension().location().getPath();
        } catch (Throwable t) {
            return "unknown";
        }
    }

    /**
     * Logs the nearest waiting chunks with the exact readiness detail the queue checks
     * ({@code hasChunkAndNeighbours}), so a stuck-near-the-player stall shows WHICH neighbour
     * lookup keeps failing. Rate-limited; silent for the normal far-corner starvation.
     */
    private void logIfStuckNearPlayer(ColoredLightEngine engine) {
        long nowMillis = System.currentTimeMillis();
        if (nowMillis - lastStuckLogMillis < 30_000L) return;
        // "Near the player" only means something in the player's own dimension. A remote level
        // (Immersive Portals) legitimately keeps chunks waiting next to the player's coordinates
        // forever, since those chunks belong to a different world; that used to log every 30s.
        if (Minecraft.getInstance().level != engine.level.getLevel()) return;
        PlayerAccessor player = clientAccessor.getPlayer();
        if (player == null) return;
        ChunkPos center = player.getChunkPos();

        ChunkPos[] nearest = new ChunkPos[3];
        int[] nearestDist = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        for (ChunkPos pos : engine.chunksWaitingForPropagation) {
            int d = pos.getChessboardDistance(center);
            for (int i = 0; i < 3; ++i) {
                if (d < nearestDist[i]) {
                    for (int j = 2; j > i; --j) { nearestDist[j] = nearestDist[j - 1]; nearest[j] = nearest[j - 1]; }
                    nearestDist[i] = d;
                    nearest[i] = pos;
                    break;
                }
            }
        }
        if (nearest[0] == null || nearestDist[0] > 8) return; // far corners starving is expected
        lastStuckLogMillis = nowMillis;

        StringBuilder detail = new StringBuilder();
        for (int i = 0; i < 3 && nearest[i] != null; ++i) {
            ChunkPos pos = nearest[i];
            detail.append(pos).append(" dist=").append(nearestDist[i]).append(" missingNeighbours[");
            boolean first = true;
            for (int ox = -1; ox <= 1; ++ox) {
                for (int oz = -1; oz <= 1; ++oz) {
                    if (!engine.level.hasChunk(new ChunkPos(pos.x + ox, pos.z + oz))) {
                        if (!first) detail.append(' ');
                        detail.append(ox).append(',').append(oz);
                        first = false;
                    }
                }
            }
            detail.append("]  ");
        }
        ColorfulLighting.LOGGER.warn(
                "Colored light queue [{}] is stalled with waiting chunks NEAR the player ({} queued): {}(this is the '/cl purge' bug; please report this line)",
                levelName(engine), engine.chunksWaitingForPropagation.size(), detail);
    }

    private NearestChunkResult getNearestWaitingChunk(ColoredLightEngine engine, LevelAccessor level, PlayerAccessor player) {
        return lightChunkOrder.next(engine, level, player, engine.chunksWaitingForPropagation);
    }

    private NearestChunkResult getNearestWaitingDarknessChunk(ColoredLightEngine engine, LevelAccessor level, PlayerAccessor player) {
        return darknessChunkOrder.next(engine, level, player, engine.chunksWaitingForDarknessPropagation);
    }

    /**
     * apply ready light changes to storage
     */
    protected void applyReadyChanges(ColoredLightEngine engine) {
	    engine.lightEngine.changesReadyLock.lock();
        try {
            if (!engine.lightEngine.changesReady.isEmpty()) {
                synchronized (engine.storageLock) {
                    for (var entry : engine.lightEngine.changesReady.entrySet()) {
	                    engine.lightEngine.storage.setEntryUnsafe(entry.getKey(), entry.getValue());
                    }
                }
                engine.lightEngine.changesReady.clear();
            }
            // After the writes above, so the renderer never rebuilds a section before its colours land.
            publishDirtySections(engine, lightReadyDirtySections);
        } finally {
	        engine.lightEngine.changesReadyLock.unlock();
        }
	    
	    engine.darkEngine.changesReadyLock.lock();
        try {
            if (!engine.darkEngine.changesReady.isEmpty()) {
                synchronized (engine.storageLock) {
                    for (var entry : engine.darkEngine.changesReady.entrySet()) {
	                    engine.darkEngine.storage.setEntryUnsafe(entry.getKey(), entry.getValue());
                    }
                }
                engine.darkEngine.changesReady.clear();
            }
            publishDirtySections(engine, darknessReadyDirtySections);
        } finally {
            engine.darkEngine.changesReadyLock.unlock();
        }
    }

    /**
     * Caller must hold the lock guarding {@code batchDirtySections}. May hold more sections than the
     * batch that was just applied, because performRegionRebuild drops ready entries without dropping
     * their marks; a redundant section rebuild is harmless, a missing one is not.
     */
    private void publishDirtySections(ColoredLightEngine engine, LongOpenHashSet batchDirtySections) {
        if (batchDirtySections.isEmpty()) return;
        synchronized (engine.dirtySections) {
	        engine.dirtySections.addAll(batchDirtySections);
        }
        batchDirtySections.clear();
    }

    /**
     * move light changes in progress to collection of ready light changes
     */
    private void markChangesReady(ColoredLightEngine engine) {
        if (!engine.lightEngine.changesInProgress.isEmpty()) {
	        engine.lightEngine.changesReadyLock.lock();
            try {
                for (var entry : engine.lightEngine.changesInProgress.entrySet()) {
                    markReady(engine.lightEngine.changesReady, lightReadyDirtySections, entry.getKey(), entry.getValue());
                }
            } finally {
	            engine.lightEngine.changesReadyLock.unlock();
            }
            engine.lightEngine.changesInProgress = new ConcurrentHashMap<>();
        }

        if (!engine.darkEngine.changesInProgress.isEmpty()) {
	        engine.darkEngine.changesReadyLock.lock();
            try {
                for (var entry : engine.darkEngine.changesInProgress.entrySet()) {
                    markReady(engine.darkEngine.changesReady, darknessReadyDirtySections, entry.getKey(), entry.getValue());
                }
            } finally {
	            engine.darkEngine.changesReadyLock.unlock();
            }
            engine.darkEngine.changesInProgress = new ConcurrentHashMap<>();
        }
    }

    /**
     * Propagation relaxes the same block many times, so a block reaches the ready batch again and again.
     * Only its first arrival needs the sections around it marked — every later one would recompute marks
     * the batch already holds. Caller must hold the lock guarding both collections.
     */
    private void markReady(Map<BlockPos, ColorRGB4> ready, LongOpenHashSet readyDirty, BlockPos blockPos, ColorRGB4 color) {
        if (ready.put(blockPos, color) == null) {
            SectionPos.aroundAndAtBlockPos(blockPos, readyDirty::add);
        }
    }


    /**
     * apply light changes in progress directly to storage
     */
    private void applyChangesDirectly(ColoredLightEngine engine) {
        if (!engine.lightEngine.changesInProgress.isEmpty()) {
            synchronized (engine.storageLock) {
                for (var entry : engine.lightEngine.changesInProgress.entrySet()) {
	                engine.lightEngine.storage.setEntryUnsafe(entry.getKey(), entry.getValue());
                }
            }
            markDirty(engine, engine.lightEngine.changesInProgress.keySet());
            engine.lightEngine.changesInProgress.clear();
        }

        if (!engine.darkEngine.changesInProgress.isEmpty()) {
            synchronized (engine.storageLock) {
                for (var entry : engine.darkEngine.changesInProgress.entrySet()) {
	                engine.darkEngine.storage.setEntryUnsafe(entry.getKey(), entry.getValue());
                }
            }
            markDirty(engine, engine.darkEngine.changesInProgress.keySet());
            engine.darkEngine.changesInProgress.clear();
        }
    }

    /**
     * Marks straight into the accumulated set rather than into a per-batch one: sections repeat heavily
     * across batches, and an add that hits an existing entry is far cheaper than growing a fresh set.
     * Runs on the propagator thread, after the storage writes the marks refer to.
     */
    private void markDirty(ColoredLightEngine engine, Set<BlockPos> changedBlocks) {
        synchronized (engine.dirtySections) {
            for (BlockPos blockPos : changedBlocks) {
                SectionPos.aroundAndAtBlockPos(blockPos, engine.dirtySections::add);
            }
        }
    }

    /**
     * propagate light in the nearest waiting chunk, handle block light updates
     */
    /** @return true when this pass actually did work; false means the queue is blocked (chunks still loading) */
    private boolean propagateLight(ColoredLightEngine engine, DefaultBlockLightEngine blockEngine) {
        PlayerAccessor player = clientAccessor.getPlayer();
        if(player == null) return false;
        boolean progressed = false;

        // decrease requests are always executed
        if(!blockEngine.blockUpdateDecreaseRequests.isEmpty()) {
            progressed = true;
            Queue<ColoredLightEngine.LightUpdateRequest> newIncreaseRequests = new ArrayDeque<>();
            propagateDecreases(engine, engine.level, blockEngine.blockUpdateDecreaseRequests, newIncreaseRequests);
            propagateIncreases(engine, engine.level, newIncreaseRequests);

            markChangesReady(engine);
        }
        
        var nearestChunkResult = getNearestWaitingChunk(engine, engine.level, player);
        var nearestBlockRequests = getNearestBlockRequests(engine, player, blockEngine);

        if(nearestChunkResult != null && (nearestBlockRequests == null || nearestChunkResult.distanceBlocks() < nearestBlockRequests.distanceBlocks())) {
            // propagate chunk
            ChunkPos chunkPos = nearestChunkResult.chunkPos();
            engine.chunksWaitingForPropagation.remove(chunkPos);

            Queue<ColoredLightEngine.LightUpdateRequest> increaseRequests = new ArrayDeque<>();
            // find light sources and request their propagation
	        engine.level.findLightSources(chunkPos, (blockPos -> {
                increaseRequests.add(new ColoredLightEngine.LightUpdateRequest(blockPos, Config.getColorEmission(engine.level, blockPos), false, true, false));
            }));
            propagateIncreases(engine, engine.level, increaseRequests);
            // new chunks' light propagation is not synchronized with main thread
            applyChangesDirectly(engine);
            progressed = true;
            drainChunks++;
            lastChunkNanos = System.nanoTime();
        }
        else if(nearestBlockRequests != null) {
	        blockEngine.blockUpdateIncreaseRequests.remove(nearestBlockRequests.blockUpdate);
            propagateIncreases(engine, engine.level, nearestBlockRequests.blockUpdate.increaseRequests);
            markChangesReady(engine);
            progressed = true;
        }
        return progressed;
    }

    /** @return true when this pass actually did work; false means the queue is blocked (chunks still loading) */
    private boolean propagateDarkness(ColoredLightEngine engine, DefaultBlockLightEngine blockEngine) {
        PlayerAccessor player = clientAccessor.getPlayer();
        if(player == null) return false;
        boolean progressed = false;

        // decrease requests are always executed
        if(!blockEngine.blockUpdateDecreaseRequests.isEmpty()) {
            progressed = true;
            Queue<ColoredLightEngine.LightUpdateRequest> newIncreaseRequests = new ArrayDeque<>();
            propagateDarknessDecreases(engine, engine.level, blockEngine.blockUpdateDecreaseRequests, newIncreaseRequests);
            propagateDarknessIncreases(engine, engine.level, newIncreaseRequests);

            markChangesReady(engine);
        }

        var nearestChunkResult = getNearestWaitingDarknessChunk(engine, engine.level, player);
        var nearestBlockRequests = getNearestDarknessRequests(engine, player, blockEngine);

        if(nearestChunkResult != null && (nearestBlockRequests == null || nearestChunkResult.distanceBlocks() < nearestBlockRequests.distanceBlocks())) {
            // propagate chunk
            ChunkPos chunkPos = nearestChunkResult.chunkPos();
	        engine.chunksWaitingForDarknessPropagation.remove(chunkPos);

            Queue<ColoredLightEngine.LightUpdateRequest> increaseRequests = new ArrayDeque<>();
            // find darkness sources and request their propagation
	        engine.level.findDarknessSources(chunkPos, (blockPos -> {
                increaseRequests.add(new ColoredLightEngine.LightUpdateRequest(blockPos, Config.getAbsorptionColor(engine.level, blockPos), false, true, false));
            }));
            propagateDarknessIncreases(engine, engine.level, increaseRequests);
            // new chunks' darkness propagation is not synchronized with main thread
            applyChangesDirectly(engine);
            progressed = true;
        }
        else if(nearestBlockRequests != null) {
	        blockEngine.blockUpdateIncreaseRequests.remove(nearestBlockRequests.blockUpdate);
            propagateDarknessIncreases(engine, engine.level, nearestBlockRequests.blockUpdate.increaseRequests);
            markChangesReady(engine);
            progressed = true;
        }
        return progressed;
    }

    /**
     * Handles all increase propagation requests.
     */
    private void propagateIncreases(ColoredLightEngine engine, LevelAccessor level, Queue<ColoredLightEngine.LightUpdateRequest> requests) {
        while(!requests.isEmpty()) {
            propagateIncrease(engine, requests, requests.poll(), level);
        }
    }

    private void propagateDarknessIncreases(ColoredLightEngine engine, LevelAccessor level, Queue<ColoredLightEngine.LightUpdateRequest> requests) {
        while(!requests.isEmpty()) {
            propagateDarknessIncrease(engine, requests, requests.poll(), level);
        }
    }

    private ColorRGB4 attenuateLight(ColorRGB4 source, int lightBlocked) {
        return ColorRGB4.fromRGB4(
                Math.max(0, source.red4 - lightBlocked),
                Math.max(0, source.green4 - lightBlocked),
                Math.max(0, source.blue4 - lightBlocked)
        );
    }

    private boolean propagateIncrease(ColoredLightEngine engine, Queue<ColoredLightEngine.LightUpdateRequest> increaseRequests, LightUpdateRequest request, LevelAccessor level) {
        if (request.checkSource) {
             BlockStateAccessor blockState = level.getBlockState(request.blockPos);
             if (blockState == null || Config.getEmissionBrightness(level, request.blockPos, blockState) == 0) {
                 return false;
             }
        }

        if (request.repropagate) {
             if (request.lightColor == null) {
                request.lightColor = getLatestLightColor(engine, request.blockPos);
             }
        }

        ColorRGB4 oldLightColor = getLatestLightColor(engine, request.blockPos);
        if(oldLightColor == null) return false; // section might have got unloaded and propagation should stop
        ColorRGB4 newLightColor = ColorRGB4.fromRGB4(
                Math.max(oldLightColor.red4, request.lightColor.red4),
                Math.max(oldLightColor.green4, request.lightColor.green4),
                Math.max(oldLightColor.blue4, request.lightColor.blue4)
        );

        // if light color didn't change (check is ignored if request is forced)
        if(!request.force && newLightColor.red4 == oldLightColor.red4 && newLightColor.green4 == oldLightColor.green4 && newLightColor.blue4 == oldLightColor.blue4) return true;
	    engine.lightEngine.changesInProgress.put(request.blockPos, newLightColor);

        // Cache source block state and geometry info once, not per-direction
        BlockStateAccessor sourceState = level.getBlockState(request.blockPos);
        boolean sourceStateExists = sourceState != null;
        BlockState sourceBlockState = sourceStateExists ? sourceState.getBlockState() : null;
        boolean sourceOccludes = sourceStateExists && sourceBlockState.useShapeForLightOcclusion();
        boolean sourceDynamic = sourceStateExists && ShapeOcclusion.isDynamicShapeBlocker(sourceBlockState);
        ColorRGB4 sourceBaseTransmittance = sourceStateExists ? Config.getColoredLightTransmittance(level, request.blockPos, sourceState) : ColorRGB4.WHITE;
        // Multiplicative filters (e.g. water) tint once on entry into each filtering block;
        // the exit face must not clamp or the tint would double-apply at every interior face.
        boolean sourceMultiplies = sourceStateExists && !sourceBaseTransmittance.equals(ColorRGB4.WHITE)
                && Config.isMultiplyFilter(level, request.blockPos, sourceState);

        for(var direction : Direction.values()) {
            BlockPos neighbourPos = request.blockPos.relative(direction);
            if(!level.isInBounds(neighbourPos)) continue;
            BlockStateAccessor neighbourState = level.getBlockState(neighbourPos);
            if(neighbourState == null) return false; // section might have got unloaded and propagation should stop

            // Start with vanilla light blocking
            int lightBlocked = Math.max(1, neighbourState.getLightBlock(level, neighbourPos));

            BlockState neighborBlockState = neighbourState.getBlockState();
            boolean neighbourDynamic = ShapeOcclusion.isDynamicShapeBlocker(neighborBlockState);

            // Override with custom absorption if it's defined.
            // Doors/trapdoors are handled by the panel logic below instead: their filter must
            // apply only across the panel face, not omnidirectionally.
            int customAbsorption = neighbourDynamic ? -1 : Config.getLightAbsorption(level, neighbourPos, neighbourState);

            boolean geometryOccludes = false;
            if (sourceStateExists) {
                boolean neighborOccludes = neighborBlockState.useShapeForLightOcclusion();
                
                if (sourceOccludes || neighborOccludes) {
                    VoxelShape sourceFaceShape = sourceOccludes ? sourceBlockState.getFaceOcclusionShape(level.getLevel(), request.blockPos, direction) : Shapes.empty();
                    VoxelShape neighbourFaceShape = neighborOccludes ? neighborBlockState.getFaceOcclusionShape(level.getLevel(), neighbourPos, direction.getOpposite()) : Shapes.empty();
                    geometryOccludes = Shapes.faceShapeOccludes(sourceFaceShape, neighbourFaceShape);
                }
            }

            if (customAbsorption >= 0) {
                if (customAbsorption < 15) {
                    lightBlocked = Math.max(1, customAbsorption);
                } else {
                    lightBlocked = geometryOccludes ? 15 : 1;
                }
            } else if (geometryOccludes) {
                lightBlocked = 15;
            }

            // Door/trapdoor panels block only the one cell face they are flush against; the
            // other faces of the cell stay fully open. Crossing a panel face costs the block's
            // filter absorption (partial for doors with windows), or is opaque without a filter.
            boolean sourcePanelBlocks = sourceDynamic && ShapeOcclusion.panelCoversFace(level.getLevel(), sourceBlockState, request.blockPos, direction);
            boolean neighbourPanelBlocks = neighbourDynamic && ShapeOcclusion.panelCoversFace(level.getLevel(), neighborBlockState, neighbourPos, direction.getOpposite());
            if (sourcePanelBlocks) {
                int panelAbsorption = Config.getLightAbsorption(level, request.blockPos, sourceState);
                lightBlocked = Math.max(lightBlocked, panelAbsorption >= 0 ? Math.max(1, panelAbsorption) : 15);
            }
            if (neighbourPanelBlocks) {
                int panelAbsorption = Config.getLightAbsorption(level, neighbourPos, neighbourState);
                lightBlocked = Math.max(lightBlocked, panelAbsorption >= 0 ? Math.max(1, panelAbsorption) : 15);
            }

            // Calculate transmittance based on both source exit and destination entry.
            // A door/trapdoor tint likewise applies only to light crossing its panel face.
            ColorRGB4 exitTransmittance;
            if (sourceDynamic) {
                exitTransmittance = sourcePanelBlocks ? sourceBaseTransmittance : ColorRGB4.WHITE;
            } else if (sourceMultiplies) {
                exitTransmittance = ColorRGB4.WHITE; // tint was already applied entering this block
            } else if (!sourceBaseTransmittance.equals(ColorRGB4.WHITE)) {
                exitTransmittance = Config.getColoredLightTransmittance(level, request.blockPos, sourceState, direction);
            } else {
                exitTransmittance = sourceBaseTransmittance;
            }
            ColorRGB4 entryTransmittance;
            if (neighbourDynamic) {
                entryTransmittance = neighbourPanelBlocks ? Config.getColoredLightTransmittance(level, neighbourPos, neighbourState) : ColorRGB4.WHITE;
            } else {
                entryTransmittance = Config.getColoredLightTransmittance(level, neighbourPos, neighbourState, direction.getOpposite());
            }
            boolean entryMultiplies = !neighbourDynamic && !entryTransmittance.equals(ColorRGB4.WHITE)
                    && Config.isMultiplyFilter(level, neighbourPos, neighbourState);

            ColorRGB4 coloredLightTransmittance = ColorRGB4.min(exitTransmittance, entryMultiplies ? ColorRGB4.WHITE : entryTransmittance);

            ColorRGB4 attenuated = attenuateLight(request.lightColor, lightBlocked);
            ColorRGB4 neighbourLightColor = ColorRGB4.fromRGB4(
                    MathExt.clamp(attenuated.red4, 0, coloredLightTransmittance.red4),
                    MathExt.clamp(attenuated.green4, 0, coloredLightTransmittance.green4),
                    MathExt.clamp(attenuated.blue4, 0, coloredLightTransmittance.blue4)
            );
            if (entryMultiplies) {
                neighbourLightColor = ColorRGB4.mul(neighbourLightColor, entryTransmittance);
            }
            // if no more color to propagate
            if(neighbourLightColor.red4 == 0 && neighbourLightColor.green4 == 0 && neighbourLightColor.blue4 == 0) continue;

            increaseRequests.add(new LightUpdateRequest(neighbourPos, neighbourLightColor, false));
        }
        return true;
    }

    private boolean propagateDarknessIncrease(ColoredLightEngine engine, Queue<LightUpdateRequest> increaseRequests, LightUpdateRequest request, LevelAccessor level) {
        if (request.checkSource) {
             BlockStateAccessor blockState = level.getBlockState(request.blockPos);
             if (blockState == null || Config.getAbsorption(level, request.blockPos, blockState) == 0) {
                 return false;
             }
        }

        if (request.repropagate) {
             if (request.lightColor == null) {
                request.lightColor = getLatestDarknessColor(engine, request.blockPos);
             }
        }

        ColorRGB4 oldDarknessColor = getLatestDarknessColor(engine, request.blockPos);
        if(oldDarknessColor == null) return false; // section might have got unloaded and propagation should stop
        ColorRGB4 newDarknessColor = ColorRGB4.fromRGB4(
                Math.max(oldDarknessColor.red4, request.lightColor.red4),
                Math.max(oldDarknessColor.green4, request.lightColor.green4),
                Math.max(oldDarknessColor.blue4, request.lightColor.blue4)
        );

        // if light color didn't change (check is ignored if request is forced)
        if(!request.force && newDarknessColor.red4 == oldDarknessColor.red4 && newDarknessColor.green4 == oldDarknessColor.green4 && newDarknessColor.blue4 == oldDarknessColor.blue4) return true;
	    engine.darkEngine.changesInProgress.put(request.blockPos, newDarknessColor);

        // Cache source block state and geometry info once, not per-direction
        BlockStateAccessor sourceState = level.getBlockState(request.blockPos);
        boolean sourceStateExists = sourceState != null;
        BlockState sourceBlockState = sourceStateExists ? sourceState.getBlockState() : null;
        boolean sourceOccludes = sourceStateExists && sourceBlockState.useShapeForLightOcclusion();
        boolean sourceDynamic = sourceStateExists && ShapeOcclusion.isDynamicShapeBlocker(sourceBlockState);

        for(var direction : Direction.values()) {
            BlockPos neighbourPos = request.blockPos.relative(direction);
            if(!level.isInBounds(neighbourPos)) continue;
            BlockStateAccessor neighbourState = level.getBlockState(neighbourPos);
            if(neighbourState == null) return false; // section might have got unloaded and propagation should stop

            int lightBlocked = Math.max(1, neighbourState.getLightBlock(level, neighbourPos));

            BlockState neighborBlockState = neighbourState.getBlockState();
            boolean neighbourDynamic = ShapeOcclusion.isDynamicShapeBlocker(neighborBlockState);

            if (sourceStateExists) {
                boolean neighborOccludes = neighborBlockState.useShapeForLightOcclusion();

                if (sourceOccludes || neighborOccludes) {
                    VoxelShape sourceFaceShape = sourceOccludes ? sourceBlockState.getFaceOcclusionShape(level.getLevel(), request.blockPos, direction) : Shapes.empty();
                    VoxelShape neighbourFaceShape = neighborOccludes ? neighborBlockState.getFaceOcclusionShape(level.getLevel(), neighbourPos, direction.getOpposite()) : Shapes.empty();

                    if (Shapes.faceShapeOccludes(sourceFaceShape, neighbourFaceShape)) {
                        lightBlocked = 15;
                    }
                }
            }

            // Door/trapdoor panels block darkness across their covered face the same way they
            // block light, using the same filter absorption so both stay consistent.
            if (sourceDynamic && ShapeOcclusion.panelCoversFace(level.getLevel(), sourceBlockState, request.blockPos, direction)) {
                int panelAbsorption = Config.getLightAbsorption(level, request.blockPos, sourceState);
                lightBlocked = Math.max(lightBlocked, panelAbsorption >= 0 ? Math.max(1, panelAbsorption) : 15);
            }
            if (neighbourDynamic && ShapeOcclusion.panelCoversFace(level.getLevel(), neighborBlockState, neighbourPos, direction.getOpposite())) {
                int panelAbsorption = Config.getLightAbsorption(level, neighbourPos, neighbourState);
                lightBlocked = Math.max(lightBlocked, panelAbsorption >= 0 ? Math.max(1, panelAbsorption) : 15);
            }

            ColorRGB4 attenuated = attenuateLight(request.lightColor, lightBlocked);
            // if no more color to propagate
            if(attenuated.red4 == 0 && attenuated.green4 == 0 && attenuated.blue4 == 0) continue;

            increaseRequests.add(new ColoredLightEngine.LightUpdateRequest(neighbourPos, attenuated, false));
        }
        return true;
    }

    /**
     * Handles all decrease propagation requests.
     */
    private void propagateDecreases(ColoredLightEngine engine, LevelAccessor level, Queue<ColoredLightEngine.LightUpdateRequest> decreaseRequests, Queue<ColoredLightEngine.LightUpdateRequest> increaseRequests) {
        Map<BlockPos, ColorRGB4> visited = new HashMap<>();
        while(!decreaseRequests.isEmpty()) {
            ColoredLightEngine.LightUpdateRequest req = decreaseRequests.poll();
            ColorRGB4 prev = visited.get(req.blockPos);
            if (prev != null && prev.red4 >= req.lightColor.red4 && prev.green4 >= req.lightColor.green4 && prev.blue4 >= req.lightColor.blue4) {
                continue;
            }
            if (prev == null) {
                visited.put(req.blockPos, req.lightColor);
            } else {
                visited.put(req.blockPos, ColorRGB4.max(prev, req.lightColor));
            }
            propagateDecrease(engine, increaseRequests, decreaseRequests, req, level);
        }
    }

    private void propagateDarknessDecreases(ColoredLightEngine engine, LevelAccessor level, Queue<ColoredLightEngine.LightUpdateRequest> decreaseRequests, Queue<ColoredLightEngine.LightUpdateRequest> increaseRequests) {
        Map<BlockPos, ColorRGB4> visited = new HashMap<>();
        while(!decreaseRequests.isEmpty()) {
            ColoredLightEngine.LightUpdateRequest req = decreaseRequests.poll();
            ColorRGB4 prev = visited.get(req.blockPos);
            if (prev != null && prev.red4 >= req.lightColor.red4 && prev.green4 >= req.lightColor.green4 && prev.blue4 >= req.lightColor.blue4) {
                continue;
            }
            if (prev == null) {
                visited.put(req.blockPos, req.lightColor);
            } else {
                visited.put(req.blockPos, ColorRGB4.max(prev, req.lightColor));
            }
            propagateDarknessDecrease(engine, increaseRequests, decreaseRequests, req, level);
        }
    }

    private boolean propagateDecrease(ColoredLightEngine engine, Queue<ColoredLightEngine.LightUpdateRequest> increaseRequests, Queue<ColoredLightEngine.LightUpdateRequest> decreaseRequests, ColoredLightEngine.LightUpdateRequest request, LevelAccessor level) {
        ColorRGB4 oldLightColor = getLatestLightColor(engine, request.blockPos);
        if(oldLightColor == null) return false; // section might have got unloaded and propagation should stop
	    
	    engine.lightEngine.changesInProgress.put(request.blockPos, ColorRGB4.fromRGB4(0, 0, 0));

        BlockStateAccessor blockState = level.getBlockState(request.blockPos);
        if(blockState == null) return false; // section might have got unloaded and propagation should stop
        // repropagate removed light (single lookup for both brightness and color)
        if(Config.getEmissionBrightness(level, request.blockPos, blockState) > 0) {
            increaseRequests.add(new ColoredLightEngine.LightUpdateRequest(request.blockPos, Config.getColorEmission(level, request.blockPos, blockState), false, true, false));
        }

        // attenuation
        ColorRGB4 neighbourLightDecrease = attenuateLight(request.lightColor, 1);

        // whether neighbours' light should be decreased or increased (to repropagate), true on "light edges"
        boolean repropagateNeighbours = neighbourLightDecrease.red4 == 0 && neighbourLightDecrease.green4 == 0 && neighbourLightDecrease.blue4 == 0;

        for(var direction : Direction.values()) {
            BlockPos neighbourPos = request.blockPos.relative(direction);
            if(!level.isInBounds(neighbourPos)) continue;

            if(!repropagateNeighbours) {
                // propagate decrease
                decreaseRequests.add(new ColoredLightEngine.LightUpdateRequest(neighbourPos, neighbourLightDecrease, false));
            }
            else {
                ColorRGB4 neighbourLightColor = getLatestLightColor(engine, neighbourPos);
                if(neighbourLightColor == null) return false; // section might have got unloaded and propagation should stop
                // if neighbour doesn't have any light
                if(neighbourLightColor.red4 == 0 && neighbourLightColor.green4 == 0 && neighbourLightColor.blue4 == 0)
                    continue;

                // force neighbour to propagate light to the region that has been just cleared (decreased)
                increaseRequests.add(new ColoredLightEngine.LightUpdateRequest(neighbourPos, null, true, false, true));
            }
        }
        return true;
    }

    private boolean propagateDarknessDecrease(ColoredLightEngine engine, Queue<ColoredLightEngine.LightUpdateRequest> increaseRequests, Queue<ColoredLightEngine.LightUpdateRequest> decreaseRequests, ColoredLightEngine.LightUpdateRequest request, LevelAccessor level) {
        ColorRGB4 oldDarknessColor = getLatestDarknessColor(engine, request.blockPos);
        if(oldDarknessColor == null) return false; // section might have got unloaded and propagation should stop
	    
	    engine.darkEngine.changesInProgress.put(request.blockPos, ColorRGB4.fromRGB4(0, 0, 0));

        BlockStateAccessor blockState = level.getBlockState(request.blockPos);
        if(blockState == null) return false; // section might have got unloaded and propagation should stop
        // repropagate removed darkness (single lookup for both absorption and color)
        if(Config.getAbsorption(level, request.blockPos, blockState) > 0) {
            increaseRequests.add(new ColoredLightEngine.LightUpdateRequest(request.blockPos, Config.getAbsorptionColor(level, request.blockPos, blockState), false, true, false));
        }

        // attenuation
        ColorRGB4 neighbourDarknessDecrease = attenuateLight(request.lightColor, 1);

        // whether neighbours' light should be decreased or increased (to repropagate), true on "light edges"
        boolean repropagateNeighbours = neighbourDarknessDecrease.red4 == 0 && neighbourDarknessDecrease.green4 == 0 && neighbourDarknessDecrease.blue4 == 0;

        for(var direction : Direction.values()) {
            BlockPos neighbourPos = request.blockPos.relative(direction);
            if(!level.isInBounds(neighbourPos)) continue;

            if(!repropagateNeighbours) {
                // propagate decrease
                decreaseRequests.add(new ColoredLightEngine.LightUpdateRequest(neighbourPos, neighbourDarknessDecrease, false));
            }
            else {
                ColorRGB4 neighbourDarknessColor = getLatestDarknessColor(engine, neighbourPos);
                if(neighbourDarknessColor == null) return false; // section might have got unloaded and propagation should stop
                // if neighbour doesn't have any light
                if(neighbourDarknessColor.red4 == 0 && neighbourDarknessColor.green4 == 0 && neighbourDarknessColor.blue4 == 0)
                    continue;

                // force neighbour to propagate light to the region that has been just cleared (decreased)
                increaseRequests.add(new ColoredLightEngine.LightUpdateRequest(neighbourPos, null, true, false, true));
            }
        }
        return true;
    }
}