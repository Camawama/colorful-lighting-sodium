package net.camacraft.colorfullighting.common.engine.cl;

import net.camacraft.colorfullighting.common.Config;
import net.camacraft.colorfullighting.common.accessors.LevelAccessor;
import net.camacraft.colorfullighting.common.accessors.PlayerAccessor;
import net.camacraft.colorfullighting.common.engine.CLEngineInnerClasses;
import net.camacraft.colorfullighting.common.util.ColorRGB4;
import net.camacraft.colorfullighting.common.util.MathExt;
import net.camacraft.colorfullighting.common.util.ShapeOcclusion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import static net.camacraft.colorfullighting.ColorfulLighting.clientAccessor;

public class LightPropagator extends Propagator {
	public LightPropagator(DefaultBlockLightEngine engine, PropagationManager manager) {
		super(engine, manager);
	}
	
	@Override
	public boolean propagateIncrease(CLEngineInnerClasses.BlockUpdates increaseRequests, CLEngineInnerClasses.LightUpdateRequest request, LevelAccessor level) {
		BlockState sourceState = null;
		
		if (request.checkSource) {
			sourceState = level.getBlockState(request.blockPos);
			if (sourceState == null || Config.getEmissionBrightness(level, request.blockPos, sourceState) == 0) {
				return false;
			}
		}
		
		boolean nullColor = false;
		
		if (request.repropagate) {
			if (request.lightColor == null) {
				// in order for us to do anything with this if request is not forced, the old light must be different from the new light
				// so we can assume there's no work to be done
				if (!request.force)
					return false;
				
				request.lightColor = getLatestLightColor(request.blockPos);
				nullColor = true;
			}
		}
		
		ColorRGB4 oldLightColor = nullColor ? request.lightColor : getLatestLightColor(request.blockPos);
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
		if (sourceState == null) {
			sourceState = level.getBlockState(request.blockPos);
		}
		boolean sourceStateExists = sourceState != null;
		BlockState sourceBlockState = sourceStateExists ? sourceState : null;
		boolean sourceOccludes = sourceStateExists && sourceBlockState.useShapeForLightOcclusion();
		boolean sourceDynamic = sourceStateExists && ShapeOcclusion.isDynamicShapeBlocker(sourceBlockState);
		ColorRGB4 sourceBaseTransmittance = sourceStateExists ? Config.getColoredLightTransmittance(level, request.blockPos, sourceState) : ColorRGB4.WHITE;
		// Multiplicative filters (e.g. water) tint once on entry into each filtering block;
		// the exit face must not clamp or the tint would double-apply at every interior face.
		boolean sourceMultiplies = sourceStateExists && !sourceBaseTransmittance.equals(ColorRGB4.WHITE)
				&& Config.isMultiplyFilter(level, request.blockPos, sourceState);
		
		boolean didWork = false;
		
		for(var direction : Direction.values()) {
			BlockPos neighbourPos = request.blockPos.relative(direction);
			
			ColorRGB4 neighborColor = getLatestLightColor(neighbourPos);
			if (neighborColor == null) continue;
			if (
					neighborColor.red4 >= request.lightColor.red4 &&
							neighborColor.green4 >= request.lightColor.green4 &&
							neighborColor.blue4 >= request.lightColor.blue4
			) continue;

			if(!level.isInBounds(neighbourPos)) continue;
			BlockState neighbourState = level.getBlockState(neighbourPos);
			if(neighbourState == null) continue; // section might have got unloaded and propagation should stop

			// Start with vanilla light blocking
			int lightBlocked = Math.max(1, neighbourState.getLightBlock(level.getLevel(), neighbourPos));

			boolean neighbourDynamic = ShapeOcclusion.isDynamicShapeBlocker(neighbourState);

			// Override with custom absorption if it's defined.
			// Doors/trapdoors are handled by the panel logic below instead: their filter must
			// apply only across the panel face, not omnidirectionally.
			int customAbsorption = neighbourDynamic ? -1 : Config.getLightAbsorption(level, neighbourPos, neighbourState);

			boolean geometryOccludes = false;
			if (sourceStateExists) {
				boolean neighborOccludes = neighbourState.useShapeForLightOcclusion();

				if (sourceOccludes || neighborOccludes) {
					VoxelShape sourceFaceShape = sourceOccludes ? sourceBlockState.getFaceOcclusionShape(level.getLevel(), request.blockPos, direction) : Shapes.empty();
					VoxelShape neighbourFaceShape = neighborOccludes ? neighbourState.getFaceOcclusionShape(level.getLevel(), neighbourPos, direction.getOpposite()) : Shapes.empty();
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
			boolean neighbourPanelBlocks = neighbourDynamic && ShapeOcclusion.panelCoversFace(level.getLevel(), neighbourState, neighbourPos, direction.getOpposite());
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
			
//			ColorRGB4 neighbourLightColor = attenuateLight(request.lightColor, 1);
			
			increaseRequests.add(new CLEngineInnerClasses.LightUpdateRequest(neighbourPos, neighbourLightColor, false));
//			engine.increaseRequests.add(new CLEngineInnerClasses.LightUpdateRequest(neighbourPos, neighbourLightColor, false));
			
			didWork = true;
		}
		return didWork;
	}
	
	@Override
	public boolean propagate(LevelAccessor level, EngineParams clEngine) {
//		engine.blockUpdateIncreaseRequests.clear();
//		engine.blockUpdateDecreaseRequests.clear();
		
		PlayerAccessor player = clientAccessor.getPlayer();
		if(player == null) return false;
		boolean progressed = false;
		
		// decrease requests are always executed
		if(!engine.decreaseRequests.isEmpty()) {
			progressed = true;
			CLEngineInnerClasses.BlockUpdates newIncreaseRequests = new CLEngineInnerClasses.BlockUpdates();
			propagateDecreases(level, engine.decreaseRequests, newIncreaseRequests);
			propagateIncreases(level, newIncreaseRequests);

//			markChangesReady();
		}
		
		var nearestChunkResult = getNearestWaitingChunk(clEngine.frustum, level, player);
		var nearestBlockRequests = PropagationManager.getNearestBlockRequests(player, engine);
//		if (nearestBlockRequests != null)
//			engine.increaseRequests.add(nearestBlockRequests.blockUpdate().increaseRequests.poll());
		
		if(nearestChunkResult != null && (nearestBlockRequests == null)) {
			// propagate chunk
			ChunkPos chunkPos = nearestChunkResult.chunkPos();
			engine.chunksWaitingForPropagation.remove(chunkPos);
			
			CLEngineInnerClasses.BlockUpdates increaseRequests = new CLEngineInnerClasses.BlockUpdates();
			// find light sources and request their propagation
			level.findLightSources(chunkPos, (blockPos -> {
				increaseRequests.add(new CLEngineInnerClasses.LightUpdateRequest(blockPos, Config.getColorEmission(level, blockPos), false, true, false));
//				engine.pendingUpdates.add(new CLEngineInnerClasses.LightUpdateRequest(blockPos, Config.getColorEmission(level, blockPos), false, true, false));
			}));
			propagateIncreases(level, increaseRequests);
			// new chunks' light propagation is not synchronized with main thread
			applyChangesDirectly(clEngine);
			progressed = true;
			manager.drainChunks++;
			manager.lastChunkNanos = System.nanoTime();
		}
		else if(nearestBlockRequests != null) {
			for (BlockPos position : nearestBlockRequests.positions) {
				engine.increaseRequests.remove(position);
			}
			propagateIncreases(level, nearestBlockRequests);
//			propagateIncreases(level, engine.increaseRequests);
//			markChangesReady();
//			applyChangesDirectly(clEngine);
			progressed = true;
		}
		return progressed;
	}
	
	@Override
	public void populateChunk(ChunkPos pos, List<BlockPos> posesLight, List<BlockPos> posesDark, LevelAccessor level, CLEngineInnerClasses.BlockUpdates increaseRequests, boolean isCause) {
		for (BlockPos blockPos : posesLight) {
			increaseRequests.add(new CLEngineInnerClasses.LightUpdateRequest(blockPos, Config.getColorEmission(level, blockPos), false, true, false));
		}
	}
	
	@Override
	protected ColorRGB4 getEmission(LevelAccessor level, BlockPos neighbourPos, BlockState state) {
		return Config.getColorEmission(level, neighbourPos, state);
	}
}
