package net.camacraft.colorfullighting.common.engine.cl;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.camacraft.colorfullighting.common.accessors.LevelAccessor;
import net.camacraft.colorfullighting.common.accessors.PlayerAccessor;
import net.camacraft.colorfullighting.common.engine.CLEngineInnerClasses;
import net.camacraft.colorfullighting.common.util.ColorRGB4;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;
import java.util.List;

public abstract class Propagator {
	public final DefaultBlockLightEngine engine;
	public final PropagationManager manager;
	
	static int[] kernelXs;
	static int[] kernelYs;
	static int[] kernelZs;
	static int[] dists;
	
	static {
		Vec3i origin = new BlockPos(0, 0, 0);
		
		List<BlockPos> positions = new ArrayList<>();
		for (int x = -15; x <= 15; x++) {
			for (int y = -15; y <= 15; y++) {
				for (int z = -15; z <= 15; z++) {
					BlockPos bp = new BlockPos(x, y, z);
					if (bp.distManhattan(origin) < 16) {
						positions.add(bp);
					}
				}
			}
		}
		positions.sort(Comparator.comparingDouble(posA -> posA.distManhattan(origin)));
		
		kernelXs = new int[positions.size()];
		kernelYs = new int[positions.size()];
		kernelZs = new int[positions.size()];
		dists = new int[positions.size()];
		
		for (int i = 0; i < positions.size(); i++) {
			BlockPos pos = positions.get(i);
			
			kernelXs[i] = pos.getX();
			kernelYs[i] = pos.getY();
			kernelZs[i] = pos.getZ();
			dists[i] = pos.distManhattan(origin);
			System.out.println(dists[i]);
		}
	}
	
	public Propagator(DefaultBlockLightEngine engine, PropagationManager manager) {
		this.engine = engine;
		this.manager = manager;
	}
	
	public ColorRGB4 getLatestLightColor(BlockPos blockPos) {
		ColorRGB4 inProgress = engine.changesInProgress.get(blockPos);
		if (inProgress != null) return inProgress;
		
		if (!engine.changesReady.isEmpty()) {
			ColorRGB4 ready;
			try {
				// attempt to get it quickly without locking
				ready = engine.changesReady.get(blockPos);
			} catch (Exception e) {
				// if that fails, try again with the lock
				engine.changesReadyLock.lock();
				ready = engine.changesReady.get(blockPos);
				engine.changesReadyLock.unlock();
			}
			if (ready != null) return ready;
		}
		
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
	
	public void checkNeighborAndAdd(CLEngineInnerClasses.BlockUpdates requests, int start, int end, int y, int fixed, boolean isZFixed) {
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
//			engine.changesInProgress = new ConcurrentHashMap<>();
			engine.changesInProgress.clear();
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
	
//	int ceilMul(int a, int b) {
//		int val = a * b;
//		int frac = val - (val / 1000) * 1000;
//		if (frac > 150) return val / 1000 + 1;
//		return val / 1000;
//	}
	
	protected ColorRGB4 attenuateLight(ColorRGB4 source, int lightBlocked) {
		return ColorRGB4.fromRGB4(
				Math.max(0, source.red4 - lightBlocked),
				Math.max(0, source.green4 - lightBlocked),
				Math.max(0, source.blue4 - lightBlocked)
		);
		
//		int brightness = Math.max(source.red4, Math.max(source.green4, source.blue4));
//
//		int targetBrightness = brightness - lightBlocked;
//		if (brightness <= 0 || targetBrightness <= 0) return ColorRGB4.fromRGB4(0, 0, 0);
//
//		int divis = (targetBrightness * 1000) / brightness;
//		int frac = divis - ((divis / 10) * 10);
//		if (frac > 0) divis += 1;
//
//		return ColorRGB4.fromRGB4(
//				Math.max(0, ceilMul(source.red4, divis)),
//				Math.max(0, ceilMul(source.green4, divis)),
//				Math.max(0, ceilMul(source.blue4, divis))
//		);
	}
	
	/**
	 * Handles all decrease propagation requests.
	 */
	protected void propagateDecreases(LevelAccessor level, CLEngineInnerClasses.BlockUpdates decreaseRequests, CLEngineInnerClasses.BlockUpdates increaseRequests) {
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
	
	protected boolean propagateDecrease(CLEngineInnerClasses.BlockUpdates increaseRequests, CLEngineInnerClasses.BlockUpdates decreaseRequests, CLEngineInnerClasses.LightUpdateRequest request, LevelAccessor level) {
//		ColorRGB4 oldLightColor = getLatestLightColor(request.blockPos);
//		ColorRGB4 oldLightColor = request.lightColor;
//		if(oldLightColor == null) return false; // section might have got unloaded and propagation should stop
//
//		int brightness = Math.max(oldLightColor.red4, Math.max(oldLightColor.green4, oldLightColor.blue4));
//
//		int minB = brightness;
//
//
//		ColorRGB4 ref = request.lightColor;
//		ColorRGB4[] colors = new ColorRGB4[minB + 2];
//		for (int i = 0; i < colors.length; i++) {
//			colors[i] = ref;
//			ref = attenuateLight(ref, 1);
//		}
//
//		int i = 0;
//
//		for (; i < dists.length; i++) {
//			int dist = dists[i];
//
//			if (dist > (minB + 1)) {
//				break;
//			}
//
//			int offX = kernelXs[i];
//			int offY = kernelYs[i];
//			int offZ = kernelZs[i];
//
//			BlockPos neighbourPos = request.blockPos.offset(offX, offY, offZ);
//			if (!level.isInBounds(neighbourPos)) continue;
//
//			ColorRGB4 neighbourLightColor = getLatestLightColor(neighbourPos);
//			if(neighbourLightColor == null) continue;
//
//			ColorRGB4 refCurr = colors[dist];
//
//			if (refCurr.isBlack()) {
//				increaseRequests.replace(new CLEngineInnerClasses.LightUpdateRequest(neighbourPos, null, true, false, true));
//			} else {
//				this.engine.changesInProgress.put(neighbourPos, ColorRGB4.BLACK);
//				increaseRequests.replace(new CLEngineInnerClasses.LightUpdateRequest(neighbourPos, null, true, false, true));
//			}
//
//			BlockState state = level.getBlockState(neighbourPos);
//			if (state != null) {
//				ColorRGB4 color = getEmission(level, neighbourPos, state);
//				if (color != null && (color.red4 != 0 || color.blue4 != 0 || color.green4 != 0))
//					increaseRequests.replace(new CLEngineInnerClasses.LightUpdateRequest(neighbourPos, color, true, false, true));
//			}
//		}
		
		ColorRGB4 trgColor = ColorRGB4.BLACK;
		this.engine.changesInProgress.put(request.blockPos, trgColor);

		BlockState blockState = level.getBlockState(request.blockPos);
		if(blockState == null) return false; // section might have got unloaded and propagation should stop
		// repropagate removed light (single lookup for both value and color)
		if(engine.getValue(level, request.blockPos, blockState) > 0) {
			trgColor = engine.getColor(level, request.blockPos, blockState);
			increaseRequests.add(new CLEngineInnerClasses.LightUpdateRequest(request.blockPos, trgColor, false, true, false));
		}

		// attenuation
		ColorRGB4 neighbourLightDecrease = attenuateLight(request.lightColor, 1);

		// whether neighbours' light should be decreased or increased (to repropagate), true on "light edges"
		boolean repropagateNeighbours = neighbourLightDecrease.red4 <= trgColor.red4 && neighbourLightDecrease.green4 <= trgColor.green4 && neighbourLightDecrease.blue4 <= trgColor.blue4;

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
	
	public void propagateIncreases(LevelAccessor level, CLEngineInnerClasses.BlockUpdates requests) {
		while(!requests.isEmpty()) {
			propagateIncrease(requests, requests.poll(), level);
		}
	}
	
	public abstract boolean propagate(LevelAccessor level, EngineParams clEngine);
	
	public abstract boolean propagateIncrease(CLEngineInnerClasses.BlockUpdates requests, CLEngineInnerClasses.LightUpdateRequest poll, LevelAccessor level);
	
	public abstract void populateChunk(ChunkPos pos, List<BlockPos> posesLight, List<BlockPos> posesDark, LevelAccessor level, CLEngineInnerClasses.BlockUpdates increaseRequests, boolean isCause);
	
	protected abstract ColorRGB4 getEmission(LevelAccessor level, BlockPos neighbourPos, BlockState state);
	
	public void schedule() {
		synchronized (engine.pendingUpdates) {
			engine.pendingUpdates.updates.values().forEach(engine.increaseRequests::replace);
			engine.pendingUpdates.updates.clear();
		}
		
		// defer addition of decreases to prevent race conditions
		synchronized (engine.pendingDecreases) {
			engine.pendingDecreases.updates.values().forEach(engine.decreaseRequests::replace);
			engine.pendingDecreases.clear();
		}
	}
}
