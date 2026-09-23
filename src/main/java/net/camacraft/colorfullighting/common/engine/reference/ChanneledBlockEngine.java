package net.camacraft.colorfullighting.common.engine.reference;

import net.camacraft.colorfullighting.common.Config;
import net.camacraft.colorfullighting.common.accessors.LevelAccessor;
import net.camacraft.colorfullighting.common.engine.CLEngineInnerClasses;
import net.camacraft.colorfullighting.common.util.ColorRGB4;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.BlockLightEngine;
import net.minecraft.world.level.lighting.BlockLightSectionStorage;
import net.minecraft.world.level.lighting.LightEngine;

public class ChanneledBlockEngine extends BlockLightEngine {
	LevelAccessor accessor;
	int channel;
	
	public ChanneledBlockEngine(LightChunkGetter p_75492_, LevelAccessor accessor, int channel) {
		super(p_75492_);
		this.accessor = accessor;
		this.channel = channel;
	}
	
	public ChanneledBlockEngine(LightChunkGetter p_278252_, BlockLightSectionStorage p_278255_, LevelAccessor accessor, int channel) {
		super(p_278252_, p_278255_);
		this.accessor = accessor;
		this.channel = channel;
	}
	
	@Override
	public int getEmission(long p_285243_, BlockState p_284973_) {
//		int i = p_284973_.getLightEmission(chunkSource.getLevel(), mutablePos);
		ColorRGB4 rgb4 = Config.getColorEmission(accessor, mutablePos, p_284973_);
		int i = switch (channel) {
			case 0 -> rgb4.red4;
			case 1 -> rgb4.green4;
			case 2 -> rgb4.blue4;
			default -> throw new RuntimeException("Illegal channel.");
		};
		return i > 0 && this.storage.lightOnInSection(SectionPos.blockToSection(p_285243_)) ? i : 0;
	}
	
	@Override
	protected int getOpacity(BlockState p_285084_, BlockPos p_285057_) {
		return super.getOpacity(p_285084_, p_285057_);
	}
	
	@Override
	public void propagateLightSources(ChunkPos p_285274_) {
		this.setLightEnabled(p_285274_, true);
		LightChunk lightchunk = this.chunkSource.getChunkForLighting(p_285274_.x, p_285274_.z);
		if (lightchunk != null) {
//			lightchunk.findBlockLightSources((p_285266_, p_285452_) -> {
//				int i = p_285452_.getLightEmission(chunkSource.getLevel(), p_285266_);
//				this.enqueueIncrease(p_285266_.asLong(), LightEngine.QueueEntry.increaseLightFromEmission(i, isEmptyShape(p_285452_)));
//			});
			accessor.findLightSources(p_285274_, (blockPos -> {
				BlockState state = chunkSource.getLevel().getBlockState(blockPos);
				
				ColorRGB4 rgb4 = Config.getColorEmission(accessor, blockPos, state);
				int i = switch (channel) {
					case 0 -> rgb4.red4;
					case 1 -> rgb4.green4;
					case 2 -> rgb4.blue4;
					default -> throw new RuntimeException("Illegal channel.");
				};
				
//				int i = state.getLightEmission(chunkSource.getLevel(), p_285266_);
				this.enqueueIncrease(blockPos.asLong(), LightEngine.QueueEntry.increaseLightFromEmission(i, isEmptyShape(state)));
			}));
		}
		
	}
}
