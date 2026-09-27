package net.camacraft.colorfullighting.common.engine.cl;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.camacraft.colorfullighting.common.Config;
import net.camacraft.colorfullighting.common.accessors.LevelAccessor;
import net.camacraft.colorfullighting.common.accessors.PlayerAccessor;
import net.camacraft.colorfullighting.common.engine.CLEngineInnerClasses;
import net.camacraft.colorfullighting.common.util.ColorRGB4;
import net.camacraft.colorfullighting.common.util.MathExt;
import net.camacraft.colorfullighting.common.util.ShapeOcclusion;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static net.camacraft.colorfullighting.ColorfulLighting.clientAccessor;

public abstract class Propagator {
	public final DefaultBlockLightEngine engine;
	public final PropagationManager manager;
	
	public Propagator(DefaultBlockLightEngine engine, PropagationManager manager) {
		this.engine = engine;
		this.manager = manager;
	}
	
	public ColorRGB4 getLatestLightColor(BlockPos blockPos) {
		ColorRGB4 inProgress = engine.changesInProgress.get(blockPos);
		if (inProgress != null) return inProgress;
		
		engine.changesReadyLock.lock();
		ColorRGB4 ready = engine.changesReady.get(blockPos);
		engine.changesReadyLock.unlock();
		if (ready != null) return ready;
		
		return engine.getColor(blockPos);
	}
	
	public void refreshSection(long pos) {
		engine.removeSection(pos);
		engine.addSection(pos);
	}
	
	public void clearChanges(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
		engine.changesInProgress.entrySet().removeIf(entry -> {
			ChunkPos pos = new ChunkPos(entry.getKey());
			return pos.x >= minChunkX && pos.x <= maxChunkX && pos.z >= minChunkZ && pos.z <= maxChunkZ;
		});
		
		engine.changesReadyLock.lock();
		try {
			engine.changesReady.entrySet().removeIf(entry -> {
				ChunkPos pos = new ChunkPos(entry.getKey());
				return pos.x >= minChunkX && pos.x <= maxChunkX && pos.z >= minChunkZ && pos.z <= maxChunkZ;
			});
		} finally {
			engine.changesReadyLock.unlock();
		}
	}
	
	public void checkNeighborAndAdd(Queue<CLEngineInnerClasses.LightUpdateRequest> requests, int start, int end, int y, int fixed, boolean isZFixed) {
		for (int i = start; i <= end; i++) {
			BlockPos pos = isZFixed ? new BlockPos(i, y, fixed) : new BlockPos(fixed, y, i);
			ColorRGB4 color = getLatestLightColor(pos);
			if (color != null && (color.red4 > 0 || color.green4 > 0 || color.blue4 > 0)) {
				requests.add(new CLEngineInnerClasses.LightUpdateRequest(pos, color, true));
			}
		}
	}
	
	protected PropagationManager.NearestChunkResult getNearestWaitingChunk(Frustum frustum, LevelAccessor level, PlayerAccessor player) {
		return engine.chunkOrder.next(frustum, level, player, engine.chunksWaitingForPropagation);
	}
	
	/**
	 * apply ready light changes to storage
	 */
	public void applyReadyChanges(LongOpenHashSet dirtySections, Object storageLock) {
		engine.changesReadyLock.lock();
		try {
			if (!engine.changesReady.isEmpty()) {
				synchronized (storageLock) {
					for (var entry : engine.changesReady.entrySet()) {
						engine.storage.setEntryUnsafe(entry.getKey(), entry.getValue());
					}
				}
				engine.changesReady.clear();
			}
			// After the writes above, so the renderer never rebuilds a section before its colours land.
			publishDirtySections(dirtySections, engine.readyDirtySections);
		} finally {
			engine.changesReadyLock.unlock();
		}
	}
	
	/**
	 * Caller must hold the lock guarding {@code batchDirtySections}. May hold more sections than the
	 * batch that was just applied, because performRegionRebuild drops ready entries without dropping
	 * their marks; a redundant section rebuild is harmless, a missing one is not.
	 */
	protected void publishDirtySections(LongOpenHashSet dirtySections, LongOpenHashSet batchDirtySections) {
		if (batchDirtySections.isEmpty()) return;
		synchronized (dirtySections) {
			dirtySections.addAll(batchDirtySections);
		}
		batchDirtySections.clear();
	}
	
	/**
	 * move light changes in progress to collection of ready light changes
	 */
	protected void markChangesReady() {
		if (!engine.changesInProgress.isEmpty()) {
			engine.changesReadyLock.lock();
			try {
				for (var entry : engine.changesInProgress.entrySet()) {
					markReady(engine.changesReady, engine.readyDirtySections, entry.getKey(), entry.getValue());
				}
			} finally {
				engine.changesReadyLock.unlock();
			}
			engine.changesInProgress = new ConcurrentHashMap<>();
		}
	}
	
	/**
	 * Propagation relaxes the same block many times, so a block reaches the ready batch again and again.
	 * Only its first arrival needs the sections around it marked — every later one would recompute marks
	 * the batch already holds. Caller must hold the lock guarding both collections.
	 */
	protected void markReady(Map<BlockPos, ColorRGB4> ready, LongOpenHashSet readyDirty, BlockPos blockPos, ColorRGB4 color) {
		if (ready.put(blockPos, color) == null) {
			SectionPos.aroundAndAtBlockPos(blockPos, readyDirty::add);
		}
	}
	
	
	/**
	 * apply light changes in progress directly to storage
	 */
	public void applyChangesDirectly(EngineParams clEngine) {
		if (!engine.changesInProgress.isEmpty()) {
			synchronized (clEngine.storageLock) {
				for (var entry : engine.changesInProgress.entrySet()) {
					engine.storage.setEntryUnsafe(entry.getKey(), entry.getValue());
				}
			}
			markDirty(clEngine, engine.changesInProgress.keySet());
			engine.changesInProgress.clear();
		}
	}
	
	/**
	 * Marks straight into the accumulated set rather than into a per-batch one: sections repeat heavily
	 * across batches, and an add that hits an existing entry is far cheaper than growing a fresh set.
	 * Runs on the propagator thread, after the storage writes the marks refer to.
	 */
	protected void markDirty(EngineParams clEngine, Set<BlockPos> changedBlocks) {
		synchronized (clEngine.dirtySections) {
			for (BlockPos blockPos : changedBlocks) {
				SectionPos.aroundAndAtBlockPos(blockPos, clEngine.dirtySections::add);
			}
		}
	}
	
	protected ColorRGB4 attenuateLight(ColorRGB4 source, int lightBlocked) {
		return ColorRGB4.fromRGB4(
				Math.max(0, source.red4 - lightBlocked),
				Math.max(0, source.green4 - lightBlocked),
				Math.max(0, source.blue4 - lightBlocked)
		);
	}
	
	/**
	 * Handles all decrease propagation requests.
	 */
	protected void propagateDecreases(LevelAccessor level, Queue<CLEngineInnerClasses.LightUpdateRequest> decreaseRequests, Queue<CLEngineInnerClasses.LightUpdateRequest> increaseRequests) {
		Map<BlockPos, ColorRGB4> visited = new HashMap<>();
		while(!decreaseRequests.isEmpty()) {
			CLEngineInnerClasses.LightUpdateRequest req = decreaseRequests.poll();
			ColorRGB4 prev = visited.get(req.blockPos);
			if (prev != null && prev.red4 >= req.lightColor.red4 && prev.green4 >= req.lightColor.green4 && prev.blue4 >= req.lightColor.blue4) {
				continue;
			}
			if (prev == null) {
				visited.put(req.blockPos, req.lightColor);
			} else {
				visited.put(req.blockPos, ColorRGB4.max(prev, req.lightColor));
			}
			propagateDecrease(increaseRequests, decreaseRequests, req, level);
		}
	}
	
	protected boolean compareColors(ColorRGB4 lightColor, ColorRGB4 neighbourLightDecrease) {
		return lightColor.red4 <= neighbourLightDecrease.red4 &&
				lightColor.blue4 <= neighbourLightDecrease.blue4 &&
				lightColor.green4 <= neighbourLightDecrease.green4;
	}
	
	protected boolean propagateDecrease(Queue<CLEngineInnerClasses.LightUpdateRequest> increaseRequests, Queue<CLEngineInnerClasses.LightUpdateRequest> decreaseRequests, CLEngineInnerClasses.LightUpdateRequest request, LevelAccessor level) {
		ColorRGB4 oldLightColor = getLatestLightColor(request.blockPos);
		if(oldLightColor == null) return false; // section might have got unloaded and propagation should stop
		
		this.engine.changesInProgress.put(request.blockPos, ColorRGB4.fromRGB4(0, 0, 0));
		
		BlockState blockState = level.getBlockState(request.blockPos);
		if(blockState == null) return false; // section might have got unloaded and propagation should stop
		// repropagate removed light (single lookup for both value and color)
		if(engine.getValue(level, request.blockPos, blockState) > 0) {
			increaseRequests.add(new CLEngineInnerClasses.LightUpdateRequest(request.blockPos, engine.getColor(level, request.blockPos, blockState), false, true, false));
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
				decreaseRequests.add(new CLEngineInnerClasses.LightUpdateRequest(neighbourPos, neighbourLightDecrease, false));
			}
			else {
				ColorRGB4 neighbourLightColor = getLatestLightColor(neighbourPos);
				if(neighbourLightColor == null) return false; // section might have got unloaded and propagation should stop
				
				// if neighbour doesn't have any light
//				if(neighbourLightColor.red4 == 0 && neighbourLightColor.green4 == 0 && neighbourLightColor.blue4 == 0)
//					continue;
				
				// if neighbor has either less or the same light as current, then there's no point in propagating
				if (compareColors(neighbourLightColor, request.lightColor))
					continue;
				
				// force neighbour to propagate light to the region that has been just cleared (decreased)
				increaseRequests.add(new CLEngineInnerClasses.LightUpdateRequest(neighbourPos, null, true, false, true));
			}
		}
		return true;
	}
	
	public void propagateIncreases(LevelAccessor level, Queue<CLEngineInnerClasses.LightUpdateRequest> requests) {
		while(!requests.isEmpty()) {
			propagateIncrease(requests, requests.poll(), level);
		}
	}
	
	public abstract boolean propagate(LevelAccessor level, EngineParams clEngine);
	
	public abstract boolean propagateIncrease(Queue<CLEngineInnerClasses.LightUpdateRequest> requests, CLEngineInnerClasses.LightUpdateRequest poll, LevelAccessor level);
	
	public abstract void populateChunk(ChunkPos pos, List<BlockPos> posesLight, List<BlockPos> posesDark, LevelAccessor level, Queue<CLEngineInnerClasses.LightUpdateRequest> increaseRequests, boolean isCause);
}
