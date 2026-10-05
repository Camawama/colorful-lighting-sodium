package net.camacraft.colorfullighting.common.engine.cl;

import net.camacraft.colorfullighting.common.Config;
import net.camacraft.colorfullighting.common.accessors.LevelAccessor;
import net.camacraft.colorfullighting.common.accessors.PlayerAccessor;
import net.camacraft.colorfullighting.common.engine.CLEngineInnerClasses;
import net.camacraft.colorfullighting.common.util.ColorRGB4;
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

public class DarkPropagator extends Propagator {
	public DarkPropagator(DefaultBlockLightEngine engine, PropagationManager manager) {
		super(engine, manager);
	}
	
	@Override
	public boolean propagateIncrease(CLEngineInnerClasses.BlockUpdates increaseRequests, CLEngineInnerClasses.LightUpdateRequest request, LevelAccessor level) {
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
	
	@Override
	public boolean propagate(LevelAccessor level, EngineParams clEngine) {
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
		
		if(nearestChunkResult != null && (nearestBlockRequests == null || nearestChunkResult.distanceBlocks() < nearestBlockRequests.distanceBlocks())) {
			// propagate chunk
			ChunkPos chunkPos = nearestChunkResult.chunkPos();
			engine.chunksWaitingForPropagation.remove(chunkPos);
			
			CLEngineInnerClasses.BlockUpdates increaseRequests = new CLEngineInnerClasses.BlockUpdates();
			// find darkness sources and request their propagation
			level.findDarknessSources(chunkPos, (blockPos -> {
				increaseRequests.add(new CLEngineInnerClasses.LightUpdateRequest(blockPos, Config.getAbsorptionColor(level, blockPos), false, true, false));
			}));
			propagateIncreases(level, increaseRequests);
			// new chunks' darkness propagation is not synchronized with main thread
			applyChangesDirectly(clEngine);
			progressed = true;
		}
		else if(nearestBlockRequests != null) {
			engine.increaseRequests.remove(nearestBlockRequests.blockUpdate().blockPos);
			propagateIncreases(level, nearestBlockRequests.blockUpdate().increaseRequests);
//			markChangesReady();
			progressed = true;
		}
		return progressed;
	}
	
	@Override
	public void populateChunk(ChunkPos pos, List<BlockPos> posesLight, List<BlockPos> posesDark, LevelAccessor level, CLEngineInnerClasses.BlockUpdates increaseRequests, boolean isCause) {
		for (BlockPos blockPos : posesDark) {
			increaseRequests.add(new CLEngineInnerClasses.LightUpdateRequest(blockPos, Config.getAbsorptionColor(level, blockPos), false, true, false));
		}
	}
	
	@Override
	protected ColorRGB4 getEmission(LevelAccessor level, BlockPos neighbourPos, BlockState state) {
		return Config.getAbsorptionColor(level, neighbourPos, state);
	}
}
