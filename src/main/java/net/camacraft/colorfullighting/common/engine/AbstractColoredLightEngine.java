package net.camacraft.colorfullighting.common.engine;

import net.camacraft.colorfullighting.common.ColoredLightEngine;
import net.camacraft.colorfullighting.common.ViewArea;
import net.camacraft.colorfullighting.common.accessors.LevelAccessor;
import net.camacraft.colorfullighting.common.engine.cl.ColoredLightSection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;

/*
 * abstract for extensibility
 * if a mod wants to implement its own colored lighting engine and piggy back off of CL's compat code, this allows for that
 */
public abstract class AbstractColoredLightEngine {
	public final ColoredLightEngine lightInterface;
	
	public AbstractColoredLightEngine(ColoredLightEngine lightInterface) {
		this.lightInterface = lightInterface;
	}
	
	public abstract int sampleLightColorPacked(ColoredLightEngine.SectionCursor cursor, int x, int y, int z);
	
	public ColoredLightEngine getInterface() {
		return lightInterface;
	}
	
	public abstract void enableChunk(ChunkPos pos, boolean enabled);
	
	public abstract void setSectionEnabled(SectionPos pos, boolean enabled);
	
	public abstract long[] applyReadyChanges();
	
	public abstract int sectionCount();
	
	public abstract AbstractColoredLightSection getSection(boolean forLight, long pos);
	
	public abstract void blockUpdated(LevelAccessor level, BlockPos blockPos);
	
	public abstract void rebuildChunk(ChunkPos chunkPos, long delay);
	
	public LevelAccessor getLevel() {
		return lightInterface.getLevel();
	}
	
	public abstract void tick();
	
	// CODE REGION: life cycle
	public abstract void start();
	
	public abstract void stop();
	
	public abstract void clear();
	
	// CODE REGION: debug
	public abstract int debugFallbackSamples();
	
	public abstract String describeQueue(ChunkPos center);
}
