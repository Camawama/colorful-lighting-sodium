package net.camacraft.colorfullighting.common.engine;

import net.camacraft.colorfullighting.common.ViewArea;
import net.camacraft.colorfullighting.common.accessors.LevelAccessor;
import net.camacraft.colorfullighting.common.engine.cl.ColoredLightSection;
import net.camacraft.colorfullighting.common.util.ColorRGB4;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public abstract class ColoredBlockLightEngine {
	public abstract String describeQueue();
	
	public abstract void remove(ViewArea newArea);
	
	public abstract void removeAlt(ViewArea newArea);
	
	public abstract void handleBlockUpdate(LevelAccessor level, CLEngineInnerClasses.BlockRequests increaseRequests, BlockPos blockPos);
	
	public abstract AbstractColoredLightSection getSection(long sectionPos);
	
	public abstract void removeSection(long sectionPos);
	
	public abstract void addSection(long pos);
	
	public abstract int sectionCount();
	
	public abstract void clear();
	
	public abstract boolean hasWork();
	
	public abstract ColorRGB4 getColor(BlockPos blockPos);
	
	public abstract int getValue(LevelAccessor level, BlockPos blockPos, BlockState blockState);
	
	public abstract ColorRGB4 getColor(LevelAccessor level, BlockPos blockPos, BlockState blockState);
}
