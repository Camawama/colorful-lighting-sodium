package net.camacraft.colorfullighting.common.engine.reference;

import net.camacraft.colorfullighting.common.ColoredLightInterface;
import net.camacraft.colorfullighting.common.engine.AbstractColoredLightSection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.BlockLightEngine;

public class TriChannelEngine {
	BlockLightEngine red, green, blue;
	boolean valid = false;
	boolean forLight;
	
	public TriChannelEngine(boolean forLight) {
		this.forLight = forLight;
	}
	
	public void setLightEnabled(ChunkPos pos, boolean enabled) {
		red.setLightEnabled(pos, enabled);
		green.setLightEnabled(pos, enabled);
		blue.setLightEnabled(pos, enabled);
	}
	
	public void propagateLightSources(ChunkPos pos) {
		red.propagateLightSources(pos);
		blue.propagateLightSources(pos);
		green.propagateLightSources(pos);
	}
	
	public void setSectionEnabled(SectionPos pos, boolean enabled) {
		red.updateSectionStatus(pos, !enabled);
		green.updateSectionStatus(pos, !enabled);
		blue.updateSectionStatus(pos, !enabled);
	}
	
	public AbstractColoredLightSection makeWrapper(SectionPos spos) {
		return new WrapperLightSection(
				() -> red.getDataLayerData(spos),
				() -> green.getDataLayerData(spos),
				() -> blue.getDataLayerData(spos)
		);
	}
	
	public void checkBlock(BlockPos blockPos) {
		red.checkBlock(blockPos);
		green.checkBlock(blockPos);
		blue.checkBlock(blockPos);
	}
	
	public void runLightUpdates() {
		red.runLightUpdates();
		green.runLightUpdates();
		blue.runLightUpdates();
	}
	
	public void make(LightChunkGetter lightChunkGetter, ColoredLightInterface lightInterface) {
		red = new ChanneledBlockEngine(lightChunkGetter, lightInterface.getLevel(), 0, forLight);
		green = new ChanneledBlockEngine(lightChunkGetter, lightInterface.getLevel(), 1, forLight);
		blue = new ChanneledBlockEngine(lightChunkGetter, lightInterface.getLevel(), 2, forLight);
		valid = true;
	}
	
	public void invalidate() {
		valid = false;
//		red = null;
//		green = null;
//		blue = null;
	}
}
