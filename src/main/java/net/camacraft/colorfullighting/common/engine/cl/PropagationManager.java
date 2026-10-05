package net.camacraft.colorfullighting.common.engine.cl;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.camacraft.colorfullighting.ColorfulLighting;
import net.camacraft.colorfullighting.common.ColorfulLightingConfig;
import net.camacraft.colorfullighting.common.accessors.LevelAccessor;
import net.camacraft.colorfullighting.common.accessors.PlayerAccessor;
import net.camacraft.colorfullighting.common.engine.CLEngineInnerClasses;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.lang.ref.WeakReference;
import java.util.*;

import static net.camacraft.colorfullighting.ColorfulLighting.clientAccessor;
import static net.camacraft.colorfullighting.common.ColoredLightEngine.*;
import static net.camacraft.colorfullighting.common.engine.CLEngineInnerClasses.*;

/**
 * LightPropagator calculates changes to light values. It runs on another thread to avoid lag on the main thread.
 * It propagates increases (increases of light values, e.g. new light source has been placed).
 * It propagates decreases (decreases of light values, e.g. light source has been destroyed, solid block has been placed in the path of light).
 * Changes caused by block updates are applied on the main thread to avoid light flickering
 */
public class PropagationManager implements Runnable {
	static class EngineBox {
		WeakReference<CLEngine> weakRef;
		
		public EngineBox(CLEngine engine) {
			this.weakRef = new WeakReference<>(engine);
		}
		
		public CLEngine getEngine() {
			return weakRef.get();
		}
	}
	
	List<Propagator> propagators = new ArrayList<>();
	
    private boolean running;
    private volatile boolean shutdown = false;

    /** Direct measurement of a drain: terrain makes profiler frame times a poor proxy for throughput. */
    private long drainStartNanos;
	public long lastChunkNanos;
	public int drainChunks;

    /** Backoff for a queue that cannot progress; see the blocked branch in run(). */
    private long blockedSleepMillis = MIN_BLOCKED_SLEEP_MILLIS;
    private int lastChunksRemaining = -1;

    public boolean hasReadyLightChanges(CLEngine engine) {
        return !engine.lightEngine.changesReady.isEmpty();
    }

    public boolean hasReadyDarknessChanges(CLEngine engine) {
        return !engine.darkEngine.changesReady.isEmpty();
    }

    public boolean hasReadyChanges(CLEngine engine) {
        return hasReadyLightChanges(engine) || hasReadyDarknessChanges(engine);
    }
	
	EngineBox box;
	
	public PropagationManager(CLEngine engine) {
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
	public long doWork() {
		CLEngine engine = box.getEngine();
		// if our engine no longer exists, or is not using this propagator anymore, then thread is ready to die
		if (engine == null) {
			running = false;
			return 0;
		}
		
		if (engine.lightPropagator != this) {
			running = false;
			return 0;
		}
		
		EngineParams params = new EngineParams(
				engine.dirtySections, engine.storageLock,
				engine.lightInterface.getFrustum(), engine.lightInterface.getStructureVersionAtomic()
		);
		
		// Process delayed chunk updates
		long now = System.currentTimeMillis();
		Iterator<DelayedChunkUpdate> it = engine.delayedChunkUpdates.iterator();
		while (it.hasNext()) {
			DelayedChunkUpdate update = it.next();
			if (now >= update.executeTime()) {
				it.remove();
				engine.pendingDelayedUpdates.remove(update.chunkPos());
				performRegionRebuild(engine.getLevel(), params, update.chunkPos());
			}
		}
		
		boolean hasWork = false;
		for (Propagator propagator : propagators) {
			hasWork = hasWork | propagator.engine.hasWork();
		}
		
		// Re-read every pass so changing the config takes effect without a restart.
		ColorfulLightingConfig.LightUpdateSpeed speed = ColorfulLightingConfig.lightUpdateSpeed();
		
		long passStartNanos = System.nanoTime();
		boolean stillHasWork = hasWork;
		boolean progressed = false;
		if (hasWork) {
			int numPropagators = propagators.size();
			boolean[] workCache = new boolean[numPropagators];
			for (int i = 0; i < numPropagators; i++) {
				Propagator propagator = propagators.get(i);
				workCache[i] = propagator.engine.hasWork();
			}
			
			// Keep propagating for a budget instead of sleeping 1ms after every single chunk:
			// profiling put ~16% of this thread inside Thread.sleep while work was queued.
//			long deadline = System.nanoTime() + speed.budgetNanos();
			long deadline = Long.MAX_VALUE;
			boolean progressedThisPass;
			do {
				if (shutdown) {
					return 0;
				}
				
				progressedThisPass = false;
				stillHasWork = false;
				
				for (int i = 0; i < numPropagators; i++) {
					if (workCache[i]) {
						Propagator propagator = propagators.get(i);
						
						// do work
						progressedThisPass = propagator.propagate(engine.getLevel(), params) | progressedThisPass;
						
						// do we still have more work to do?
						workCache[i] = propagator.engine.hasWork();
						stillHasWork = stillHasWork | workCache[i];
					}
				}
				// is there any case where this would ever not progress?
				progressed |= progressedThisPass;
				
				// stop early when nothing moved: the queue is waiting on chunks to load
			} while (running && progressedThisPass && (hasWork) && System.nanoTime() < deadline);
		} else {
			// If idle, check if we have sections to rebuild from explosions
			if (!engine.sectionsToRebuildLater.isEmpty()) {
				synchronized (engine.dirtySections) {
					engine.dirtySections.addAll(engine.sectionsToRebuildLater);
				}
				engine.sectionsToRebuildLater.clear();
				Minecraft.getInstance().execute(engine.lightInterface::onLightUpdate);
			}
		}
		
		if (this.hasReadyChanges(engine)) {
			Minecraft.getInstance().execute(engine.lightInterface::onLightUpdate);
		}
		
		if (!hasWork) {
			drainStartNanos = 0;
			drainChunks = 0;
			lastChunkNanos = System.nanoTime();
		}
		
		// check if work was done after the work is supposed to have been done
		{
			// Track the CHUNK queue only. hasWork also covers single-block updates, and in the
			// Nether flowing lava and fire fire checkBlock constantly, so hasWork can essentially
			// never go false - the chunk fill-in would finish and the drain would never close.
			// Report a drain when the chunk queue empties OR when no chunk has propagated for 2s.
			// The latter matters: the queue never empties, because the corners of this square view
			// area fall outside the disc of chunks the server actually sends. Waiting for an empty
			// queue would mean never reporting at all.
			int chunksRemaining = engine.lightEngine.chunksWaitingForPropagation.size();
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
			
			// if there is no work to be done, then we shouldn't expect work to be done
			if (hasWork) {
				// Diagnostic for the long-standing "light stops until /cl purge" bug: a stall where only
				// far view-area-corner chunks are queued is normal (the server never sends those), but a
				// waiting chunk NEAR the player means some readiness check keeps wrongly rejecting it.
				// Log which one so the next natural occurrence names the failing check.
				if (drainStartNanos != 0L && chunksRemaining > 0
						&& System.nanoTime() - lastChunkNanos > 5_000_000_000L) {
//					logIfStuckNearPlayer(engine);
				}
			}
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
			} else if (stillHasWork) {
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

	/* stays here */
    private void performRegionRebuild(LevelAccessor level, EngineParams engine, ChunkPos centerChunk) {
        int radius = 1; // 3x3 area
        int minChunkX = centerChunk.x - radius;
        int maxChunkX = centerChunk.x + radius;
        int minChunkZ = centerChunk.z - radius;
        int maxChunkZ = centerChunk.z + radius;

        // 0. Clear pending changes for the region to avoid contaminating the rebuild with stale data
	    for (Propagator propagator : propagators) {
		    propagator.clearChanges(minChunkX, minChunkZ, maxChunkX, maxChunkZ);
	    }

        // 1. Clear storage for the 3x3 region and mark dirty
        synchronized (engine.storageLock) {
            synchronized (engine.dirtySections) {
                for (int cx = minChunkX; cx <= maxChunkX; cx++) {
                    for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                        for(int y = level.getMinSectionY(); y <= level.getMaxSectionY(); y++) {
                            long pos = SectionPos.asLong(cx, y, cz);
	                        for (Propagator propagator : propagators) {
		                        propagator.refreshSection(pos);
	                        }
	                        engine.dirtySections.add(pos); // Mark as dirty so renderer updates even if no new light is found
                        }
                    }
                }
            }
        }
	    engine.structureVersion.incrementAndGet();

        Queue<LightUpdateRequest>[] increaseRequests = new Queue[propagators.size()];
	    for (int i = 0; i < increaseRequests.length; i++) {
		    increaseRequests[i] = new ArrayDeque<>();
	    }

        // 2. Find internal sources for all chunks in region
	    List<BlockPos> posesLight = new ArrayList<>();
	    List<BlockPos> posesDark = new ArrayList<>();
	    for (int cx = minChunkX; cx <= maxChunkX; cx++) {
	        for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
				ChunkPos pos = new ChunkPos(cx, cz);
	            level.findLightSources(pos, (posesLight::add));
	            level.findDarknessSources(pos, (posesDark::add));
		        for (int i = 0; i < propagators.size(); i++) {
			        Propagator propagator = propagators.get(i);
			        propagator.populateChunk(pos, posesLight, posesDark, level, increaseRequests[i], cx == centerChunk.x && cz == centerChunk.z);
		        }
				posesLight.clear();
				posesDark.clear();
	        }
	    }

        // 3. Pull from neighbors OUTSIDE the region
        int minBlockY = level.getMinSectionY() * 16;
        int maxBlockY = (level.getMaxSectionY() + 1) * 16 - 1;

        int regionMinBlockX = minChunkX * 16;
        int regionMaxBlockX = (maxChunkX * 16) + 15;
        int regionMinBlockZ = minChunkZ * 16;
        int regionMaxBlockZ = (maxChunkZ * 16) + 15;
	    
	    for (int i = 0; i < propagators.size(); i++) {
		    Propagator propagator = propagators.get(i);
		    for (int y = minBlockY; y <= maxBlockY; y++) {
			    // North border of the whole region (check z-1)
			    propagator.checkNeighborAndAdd(increaseRequests[i], regionMinBlockX, regionMaxBlockX, y, regionMinBlockZ - 1, true);
			    // South border (check z+1)
			    propagator.checkNeighborAndAdd(increaseRequests[i], regionMinBlockX, regionMaxBlockX, y, regionMaxBlockZ + 1, true);
			    // West border (check x-1)
			    propagator.checkNeighborAndAdd(increaseRequests[i], regionMinBlockZ, regionMaxBlockZ, y, regionMinBlockX - 1, false);
			    // East border (check x+1)
			    propagator.checkNeighborAndAdd(increaseRequests[i], regionMinBlockZ, regionMaxBlockZ, y, regionMaxBlockX + 1, false);
		    }
		    propagator.propagateIncreases(level, increaseRequests[i]);
			propagator.applyChangesDirectly(engine);
	    }
    }
	
	public record NearestBlockRequestsResult(CLEngineInnerClasses.BlockRequests blockUpdate, int distanceBlocks) {}
	public static NearestBlockRequestsResult getNearestBlockRequests(PlayerAccessor player, DefaultBlockLightEngine blockLightEngine) {
		// find chunk nearest player
		var iterator = blockLightEngine.blockUpdateIncreaseRequests.iterator();
		int minDistance = Integer.MAX_VALUE;
		CLEngineInnerClasses.BlockRequests nearestUpdate = null;
		while (iterator.hasNext()) {
			CLEngineInnerClasses.BlockRequests update = iterator.next();
			int distance = update.blockPos.distManhattan(player.getBlockPos());
			if (distance < minDistance) {
				minDistance = distance;
				nearestUpdate = update;
			}
		}
		return nearestUpdate == null ? null : new NearestBlockRequestsResult(nearestUpdate, minDistance);
	}
	
	public record NearestChunkResult(ChunkPos chunkPos, int distanceBlocks) {}
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
	public static final class ChunkOrder {
		private final ArrayList<ChunkPos> order = new ArrayList<>();
		private int cursor;
		private long rebuiltAtNanos = Long.MIN_VALUE;
		private ChunkPos rebuiltCenter;
		
		public NearestChunkResult next(Frustum frustum, LevelAccessor level, PlayerAccessor player, Set<ChunkPos> waiting) {
			if (waiting.isEmpty()) return null;
			ChunkPos center = player.getChunkPos();
			long now = System.nanoTime();
			// Deliberately not rebuilding on cursor exhaustion: when every remaining chunk is
			// still loading, that would rebuild on every poll and reinstate the O(n)-per-poll cost.
			if (!center.equals(rebuiltCenter) || now - rebuiltAtNanos > CHUNK_ORDER_TTL_NANOS) {
				rebuild(frustum, level, center, waiting, now);
			}
			
			while (cursor < order.size()) {
				ChunkPos chunkPos = order.get(cursor++);
				if (!waiting.contains(chunkPos)) continue; // already propagated, or left the view area
				if (!level.hasChunkAndNeighbours(chunkPos)) continue; // block state data not available yet
				return new NearestChunkResult(chunkPos, chunkPos.getChessboardDistance(center) * 16);
			}
			return null;
		}
		
		public void rebuild(Frustum frustum, LevelAccessor level, ChunkPos center, Set<ChunkPos> waiting, long now) {
			order.clear();
			cursor = 0;
			rebuiltAtNanos = now;
			rebuiltCenter = center;
			
			int minY = level.getLevel().getMinBuildHeight();
			int maxY = level.getLevel().getMaxBuildHeight();
			
			List<ChunkPos> visible = new ArrayList<>();
			List<ChunkPos> hidden = new ArrayList<>();
			for (ChunkPos chunkPos : waiting) {
				boolean inView = (frustum != null && frustum.isVisible(new AABB(
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
	
	private static String levelName(CLEngine engine) {
		try {
			var level = engine.getLevel().getLevel();
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
	// this will always claim that it's stuck in the nether
	private void logIfStuckNearPlayer(CLEngine engine) {
		long nowMillis = System.currentTimeMillis();
		if (nowMillis - lastStuckLogMillis < 30_000L) return;
		// "Near the player" only means something in the player's own dimension. A remote level
		// (Immersive Portals) legitimately keeps chunks waiting next to the player's coordinates
		// forever, since those chunks belong to a different world; that used to log every 30s.
		if (Minecraft.getInstance().level != engine.getLevel().getLevel()) return;
		PlayerAccessor player = clientAccessor.getPlayer();
		if (player == null) return;
		ChunkPos center = player.getChunkPos();
		
		ChunkPos[] nearest = new ChunkPos[3];
		int[] nearestDist = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
		for (ChunkPos pos : engine.lightEngine.chunksWaitingForPropagation) {
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
					if (!engine.getLevel().hasChunk(new ChunkPos(pos.x + ox, pos.z + oz))) {
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
				levelName(engine), engine.lightEngine.chunksWaitingForPropagation.size(), detail);
	}
	
	public void applyReadyChanges(LongOpenHashSet dirtySections, Object storageLock) {
		for (Propagator propagator : propagators) {
			propagator.applyReadyChanges(dirtySections, storageLock);
		}
	}
	
	public void applyReadyChanges(CLEngine engine) {
		for (Propagator propagator : propagators) {
			propagator.applyReadyChanges(engine.dirtySections, engine.storageLock);
		}
	}
}