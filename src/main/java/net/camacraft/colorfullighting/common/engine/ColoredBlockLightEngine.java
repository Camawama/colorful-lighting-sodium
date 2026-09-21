package net.camacraft.colorfullighting.common.engine;

import net.camacraft.colorfullighting.common.ColoredLightEngine;
import net.camacraft.colorfullighting.common.ColoredLightSection;
import net.camacraft.colorfullighting.common.ViewArea;
import net.camacraft.colorfullighting.common.accessors.BlockStateAccessor;
import net.camacraft.colorfullighting.common.accessors.LevelAccessor;
import net.camacraft.colorfullighting.common.util.ColorRGB4;
import net.minecraft.core.BlockPos;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.LongConsumer;

public abstract class ColoredBlockLightEngine {
	public abstract String describeQueue();
	
	public abstract void remove(ViewArea newArea);
	
	public abstract void removeAlt(ViewArea newArea);
	
	public abstract void handleBlockUpdate(LevelAccessor level, ColoredLightEngine.BlockRequests increaseRequests, BlockPos blockPos);
	
	public abstract ColoredLightSection getSection(long sectionPos);
	
	public abstract void removeSection(long sectionPos);
	
	public abstract void addSection(long pos);
	
	public abstract int sectionCount();
	
	public abstract void forEachPopulatedSection(LongConsumer action);
	
	public abstract void clear();
	
	public abstract boolean hasWork();
	
	public abstract ColorRGB4 getColor(BlockPos blockPos);
	
	public abstract int getValue(LevelAccessor level, BlockPos blockPos, BlockStateAccessor blockState);
	
	public abstract ColorRGB4 getColor(LevelAccessor level, BlockPos blockPos, BlockStateAccessor blockState);
}
