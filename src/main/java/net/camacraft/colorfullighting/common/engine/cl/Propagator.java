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
		
		ColorRGB4 ready = engine.changesReady.get(blockPos);
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
	
	private PropagationManager.NearestChunkResult getNearestWaitingChunk(Frustum frustum, LevelAccessor level, PlayerAccessor player) {
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
	private void publishDirtySections(LongOpenHashSet dirtySections, LongOpenHashSet batchDirtySections) {
		if (batchDirtySections.isEmpty()) return;
		synchronized (dirtySections) {
			dirtySections.addAll(batchDirtySections);
		}
		batchDirtySections.clear();
	}
	
	/**
	 * move light changes in progress to collection of ready light changes
	 */
	private void markChangesReady() {
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
	private void markReady(Map<BlockPos, ColorRGB4> ready, LongOpenHashSet readyDirty, BlockPos blockPos, ColorRGB4 color) {
		if (ready.put(blockPos, color) == null) {
			SectionPos.aroundAndAtBlockPos(blockPos, readyDirty::add);
		}
	}
	
	
	/**
	 * apply light changes in progress directly to storage
	 */
	public void applyChangesDirectly(CLEngine clEngine) {
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
	private void markDirty(CLEngine engine, Set<BlockPos> changedBlocks) {
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
	private boolean propagateLight(CLEngine clEngine, DefaultBlockLightEngine blockEngine) {
		PlayerAccessor player = clientAccessor.getPlayer();
		if(player == null) return false;
		boolean progressed = false;
		
		// decrease requests are always executed
		if(!blockEngine.blockUpdateDecreaseRequests.isEmpty()) {
			progressed = true;
			Queue<CLEngineInnerClasses.LightUpdateRequest> newIncreaseRequests = new ArrayDeque<>();
			propagateDecreases(clEngine.getLevel(), blockEngine.blockUpdateDecreaseRequests, newIncreaseRequests);
			propagateLightIncreases(clEngine.getLevel(), newIncreaseRequests);
			
			markChangesReady();
		}
		
		var nearestChunkResult = getNearestWaitingChunk(clEngine.lightInterface.getFrustum(), clEngine.getLevel(), player);
		var nearestBlockRequests = PropagationManager.getNearestBlockRequests(player, blockEngine);
		
		if(nearestChunkResult != null && (nearestBlockRequests == null || nearestChunkResult.distanceBlocks() < nearestBlockRequests.distanceBlocks())) {
			// propagate chunk
			ChunkPos chunkPos = nearestChunkResult.chunkPos();
			blockEngine.chunksWaitingForPropagation.remove(chunkPos);
			
			Queue<CLEngineInnerClasses.LightUpdateRequest> increaseRequests = new ArrayDeque<>();
			// find light sources and request their propagation
			clEngine.getLevel().findLightSources(chunkPos, (blockPos -> {
				increaseRequests.add(new CLEngineInnerClasses.LightUpdateRequest(blockPos, Config.getColorEmission(clEngine.getLevel(), blockPos), false, true, false));
			}));
			propagateLightIncreases(clEngine.getLevel(), increaseRequests);
			// new chunks' light propagation is not synchronized with main thread
			applyChangesDirectly(clEngine);
			progressed = true;
			manager.drainChunks++;
			manager.lastChunkNanos = System.nanoTime();
		}
		else if(nearestBlockRequests != null) {
			blockEngine.blockUpdateIncreaseRequests.remove(nearestBlockRequests.blockUpdate());
			propagateLightIncreases(clEngine.getLevel(), nearestBlockRequests.blockUpdate().increaseRequests);
			markChangesReady();
			progressed = true;
		}
		return progressed;
	}
	
	/** @return true when this pass actually did work; false means the queue is blocked (chunks still loading) */
	private boolean propagateDarkness(CLEngine engine, DefaultBlockLightEngine blockEngine) {
		PlayerAccessor player = clientAccessor.getPlayer();
		if(player == null) return false;
		boolean progressed = false;
		
		// decrease requests are always executed
		if(!blockEngine.blockUpdateDecreaseRequests.isEmpty()) {
			progressed = true;
			Queue<CLEngineInnerClasses.LightUpdateRequest> newIncreaseRequests = new ArrayDeque<>();
			propagateDecreases(engine.getLevel(), blockEngine.blockUpdateDecreaseRequests, newIncreaseRequests);
			propagateDarknessIncreases(engine.getLevel(), newIncreaseRequests);
			
			markChangesReady();
		}
		
		var nearestChunkResult = getNearestWaitingChunk(engine.getInterface().getFrustum(), engine.getLevel(), player);
		var nearestBlockRequests = PropagationManager.getNearestBlockRequests(player, blockEngine);
		
		if(nearestChunkResult != null && (nearestBlockRequests == null || nearestChunkResult.distanceBlocks() < nearestBlockRequests.distanceBlocks())) {
			// propagate chunk
			ChunkPos chunkPos = nearestChunkResult.chunkPos();
			blockEngine.chunksWaitingForPropagation.remove(chunkPos);
			
			Queue<CLEngineInnerClasses.LightUpdateRequest> increaseRequests = new ArrayDeque<>();
			// find darkness sources and request their propagation
			engine.getLevel().findDarknessSources(chunkPos, (blockPos -> {
				increaseRequests.add(new CLEngineInnerClasses.LightUpdateRequest(blockPos, Config.getAbsorptionColor(engine.getLevel(), blockPos), false, true, false));
			}));
			propagateDarknessIncreases(engine.getLevel(), increaseRequests);
			// new chunks' darkness propagation is not synchronized with main thread
			applyChangesDirectly(engine);
			progressed = true;
		}
		else if(nearestBlockRequests != null) {
			blockEngine.blockUpdateIncreaseRequests.remove(nearestBlockRequests.blockUpdate());
			propagateDarknessIncreases(engine.getLevel(), nearestBlockRequests.blockUpdate().increaseRequests);
			markChangesReady();
			progressed = true;
		}
		return progressed;
	}
	
	/**
	 * Handles all increase propagation requests.
	 */
	private void propagateLightIncreases(LevelAccessor level, Queue<CLEngineInnerClasses.LightUpdateRequest> requests) {
		while(!requests.isEmpty()) {
			propagateIncrease(requests, requests.poll(), level);
		}
	}
	
	private void propagateDarknessIncreases(LevelAccessor level, Queue<CLEngineInnerClasses.LightUpdateRequest> requests) {
		while(!requests.isEmpty()) {
			propagateDarknessIncrease(requests, requests.poll(), level);
		}
	}
	
	private ColorRGB4 attenuateLight(ColorRGB4 source, int lightBlocked) {
		return ColorRGB4.fromRGB4(
				Math.max(0, source.red4 - lightBlocked),
				Math.max(0, source.green4 - lightBlocked),
				Math.max(0, source.blue4 - lightBlocked)
		);
	}
	
	private boolean propagateIncrease(Queue<CLEngineInnerClasses.LightUpdateRequest> increaseRequests, CLEngineInnerClasses.LightUpdateRequest request, LevelAccessor level) {
		if (request.checkSource) {
			BlockState blockState = level.getBlockState(request.blockPos);
			if (blockState == null || Config.getEmissionBrightness(level, request.blockPos, blockState) == 0) {
				return false;
			}
		}
		
		if (request.repropagate) {
			if (request.lightColor == null) {
				request.lightColor = getLatestLightColor(request.blockPos);
			}
		}
		
		ColorRGB4 oldLightColor = getLatestLightColor(request.blockPos);
		if(oldLightColor == null) return false; // section might have got unloaded and propagation should stop
		ColorRGB4 newLightColor = ColorRGB4.fromRGB4(
				Math.max(oldLightColor.red4, request.lightColor.red4),
				Math.max(oldLightColor.green4, request.lightColor.green4),
				Math.max(oldLightColor.blue4, request.lightColor.blue4)
		);
		
		// if light color didn't change (check is ignored if request is forced)
		if(!request.force && newLightColor.red4 == oldLightColor.red4 && newLightColor.green4 == oldLightColor.green4 && newLightColor.blue4 == oldLightColor.blue4) return true;
		engine.changesInProgress.put(request.blockPos, newLightColor);
		
		// Cache source block state and geometry info once, not per-direction
		BlockState sourceState = level.getBlockState(request.blockPos);
		boolean sourceStateExists = sourceState != null;
		BlockState sourceBlockState = sourceStateExists ? sourceState : null;
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
			BlockState neighbourState = level.getBlockState(neighbourPos);
			if(neighbourState == null) return false; // section might have got unloaded and propagation should stop
			
			// Start with vanilla light blocking
			int lightBlocked = Math.max(1, neighbourState.getLightBlock(level.getLevel(), neighbourPos));
			
			BlockState neighborBlockState = neighbourState;
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
			
			increaseRequests.add(new CLEngineInnerClasses.LightUpdateRequest(neighbourPos, neighbourLightColor, false));
		}
		return true;
	}
	
	private boolean propagateDarknessIncrease(Queue<CLEngineInnerClasses.LightUpdateRequest> increaseRequests, CLEngineInnerClasses.LightUpdateRequest request, LevelAccessor level) {
		if (request.checkSource) {
			BlockState blockState = level.getBlockState(request.blockPos);
			if (blockState == null || Config.getAbsorption(level, request.blockPos, blockState) == 0) {
				return false;
			}
		}
		
		if (request.repropagate) {
			if (request.lightColor == null) {
				request.lightColor = getLatestLightColor(request.blockPos);
			}
		}
		
		ColorRGB4 oldDarknessColor = getLatestLightColor(request.blockPos);
		if(oldDarknessColor == null) return false; // section might have got unloaded and propagation should stop
		ColorRGB4 newDarknessColor = ColorRGB4.fromRGB4(
				Math.max(oldDarknessColor.red4, request.lightColor.red4),
				Math.max(oldDarknessColor.green4, request.lightColor.green4),
				Math.max(oldDarknessColor.blue4, request.lightColor.blue4)
		);
		
		// if light color didn't change (check is ignored if request is forced)
		if(!request.force && newDarknessColor.red4 == oldDarknessColor.red4 && newDarknessColor.green4 == oldDarknessColor.green4 && newDarknessColor.blue4 == oldDarknessColor.blue4) return true;
		engine.changesInProgress.put(request.blockPos, newDarknessColor);
		
		// Cache source block state and geometry info once, not per-direction
		BlockState sourceState = level.getBlockState(request.blockPos);
		boolean sourceStateExists = sourceState != null;
		BlockState sourceBlockState = sourceStateExists ? sourceState : null;
		boolean sourceOccludes = sourceStateExists && sourceBlockState.useShapeForLightOcclusion();
		boolean sourceDynamic = sourceStateExists && ShapeOcclusion.isDynamicShapeBlocker(sourceBlockState);
		
		for(var direction : Direction.values()) {
			BlockPos neighbourPos = request.blockPos.relative(direction);
			if(!level.isInBounds(neighbourPos)) continue;
			BlockState neighbourState = level.getBlockState(neighbourPos);
			if(neighbourState == null) return false; // section might have got unloaded and propagation should stop
			
			int lightBlocked = Math.max(1, neighbourState.getLightBlock(level.getLevel(), neighbourPos));
			
			BlockState neighborBlockState = neighbourState;
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
			
			increaseRequests.add(new CLEngineInnerClasses.LightUpdateRequest(neighbourPos, attenuated, false));
		}
		return true;
	}
	
	/**
	 * Handles all decrease propagation requests.
	 */
	private void propagateDecreases(LevelAccessor level, Queue<CLEngineInnerClasses.LightUpdateRequest> decreaseRequests, Queue<CLEngineInnerClasses.LightUpdateRequest> increaseRequests) {
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
	
	private boolean propagateDecrease(Queue<CLEngineInnerClasses.LightUpdateRequest> increaseRequests, Queue<CLEngineInnerClasses.LightUpdateRequest> decreaseRequests, CLEngineInnerClasses.LightUpdateRequest request, LevelAccessor level) {
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
				if(neighbourLightColor.red4 == 0 && neighbourLightColor.green4 == 0 && neighbourLightColor.blue4 == 0)
					continue;
				
				// force neighbour to propagate light to the region that has been just cleared (decreased)
				increaseRequests.add(new CLEngineInnerClasses.LightUpdateRequest(neighbourPos, null, true, false, true));
			}
		}
		return true;
	}
	
	public boolean propagate(CLEngine clEngine) {
		if (this.engine.forLight) {
			return propagateLight(clEngine, this.engine);
		} else {
			return propagateDarkness(clEngine, this.engine);
		}
	}
	
	public void propagateIncreases(LevelAccessor level, Queue<CLEngineInnerClasses.LightUpdateRequest> requests) {
		if (this.engine.forLight) {
			propagateLightIncreases(level, requests);
		} else {
			propagateDarknessIncreases(level, requests);
		}
	}
	
	public void populateChunk(ChunkPos pos, List<BlockPos> posesLight, List<BlockPos> posesDark, LevelAccessor level, Queue<CLEngineInnerClasses.LightUpdateRequest> increaseRequests, boolean isCause) {
		if (this.engine.forLight) {
			for (BlockPos blockPos : posesLight) {
				increaseRequests.add(new CLEngineInnerClasses.LightUpdateRequest(blockPos, Config.getColorEmission(level, blockPos), false, true, false));
			}
		} else {
			for (BlockPos blockPos : posesDark) {
				increaseRequests.add(new CLEngineInnerClasses.LightUpdateRequest(blockPos, Config.getAbsorptionColor(level, blockPos), false, true, false));
			}
		}
	}
}
