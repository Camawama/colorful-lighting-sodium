package net.camacraft.colorfullighting.common.engine.reference;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.camacraft.colorfullighting.common.ColoredLightEngine;
import net.camacraft.colorfullighting.common.accessors.LevelAccessor;
import net.camacraft.colorfullighting.common.engine.AbstractColoredLightEngine;
import net.camacraft.colorfullighting.common.engine.AbstractColoredLightSection;
import net.camacraft.colorfullighting.common.engine.EmptyLightSection;
import net.camacraft.colorfullighting.common.engine.cl.ColoredLightSection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.lighting.BlockLightEngine;

public class TripleVanillaEngine extends AbstractColoredLightEngine {
	BlockLightEngine red, green, blue;
	
	public TripleVanillaEngine(ColoredLightEngine lightInterface) {
		super(lightInterface);
	}
	
	/**
	 * Vanilla block light at the position, as a packed white 12-bit colour. Reading the client light
	 * engine from Sodium's chunk-build workers matches what vanilla meshing does (RenderChunkRegion
	 * reads it from workers too), so it is as thread-safe as vanilla itself. Positions outside the
	 * build height keep returning 0, same as the missing-section behaviour this falls back from.
	 */
	private int vanillaBlockLightAsWhitePacked(ColoredLightEngine.SectionCursor cursor, int x, int y, int z) {
//		fallbackSamples.incrementAndGet();
		Level level = this.lightInterface.getLevel().getLevel();
		if (level == null || level.isOutsideBuildHeight(y)) return 0;
		int brightness = level.getBrightness(LightLayer.BLOCK, cursor.fallbackPos.set(x, y, z));
		if (brightness <= 0) return 0;
		return brightness << 8 | brightness << 4 | brightness;
	}
	
	@Override
	public int sampleLightColorPacked(ColoredLightEngine.SectionCursor cursor, int x, int y, int z) {
		if (!ColoredLightEngine.isEnabled()) return 0;
		
		long sectionPos = SectionPos.asLong(x >> 4, y >> 4, z >> 4);
		int version = lightInterface.getStructureVersion();
		
//		if (cursor.sectionPos != sectionPos || cursor.version != version) {
			cursor.light = getSection(true, sectionPos);
			cursor.darkness = getSection(false, sectionPos);
			cursor.sectionPos = sectionPos;
			cursor.version = version;
//		}
		
		int colorIndex = ColoredLightSection.getColorIndex(x & 15, y & 15, z & 15);
		int light;
		int darkness;
		if (cursor.light == null && cursor.darkness == null) {
			// No stored section: the position is outside every region the engine tracks. Chunks can
			// legitimately be meshed there — Valkyrien Skies ships live in shipyard chunks millions of
			// blocks from the player, so they can never enter the view area. Colored light never
			// propagates there, but vanilla block light does; render it as white instead of black so
			// such chunks keep their vanilla lighting rather than losing block light entirely.
			light = vanillaBlockLightAsWhitePacked(cursor, x, y, z);
			darkness = 0;
		} else {
			light = cursor.light == null ? 0 : cursor.light.getPacked(colorIndex);
			darkness = cursor.darkness == null ? 0 : cursor.darkness.getPacked(colorIndex);
		}
		
//		// held/dropped-item light from renderer-based dynamic lighting mods (no-op without sources);
//		// applied before the darkness subtraction so darkness absorbers dampen it like any other light
//		if (dynamicLights != null) {
//			light = dynamicLights.maxWithDynamicLightPacked(x, y, z, light);
//		}
		
		if (light == 0 || light == darkness) return 0;
		if (darkness == 0) return light;
		
		int red = Math.max(0, ((light >>> 8) & 0x0F) - ((darkness >>> 8) & 0x0F));
		int green = Math.max(0, ((light >>> 4) & 0x0F) - ((darkness >>> 4) & 0x0F));
		int blue = Math.max(0, (light & 0x0F) - (darkness & 0x0F));
		return red << 8 | green << 4 | blue;
	}
	
	@Override
	public void enableChunk(ChunkPos pos, boolean enabled) {
		make();
		
		red.setLightEnabled(pos, enabled);
		green.setLightEnabled(pos, enabled);
		blue.setLightEnabled(pos, enabled);
		
		red.propagateLightSources(pos);
		blue.propagateLightSources(pos);
		green.propagateLightSources(pos);
	}
	
	@Override
	public void setSectionEnabled(SectionPos pos, boolean enabled) {
		make();
		
		red.updateSectionStatus(pos, !enabled);
		green.updateSectionStatus(pos, !enabled);
		blue.updateSectionStatus(pos, !enabled);
		
		if (!enabled) {
			sections.remove(pos.asLong());
		}
	}
	
	@Override
	public long[] applyReadyChanges() {
		return new long[0];
	}
	
	@Override
	public int sectionCount() {
		return 0;
	}
	
	Long2ObjectMap<AbstractColoredLightSection> sections = new Long2ObjectOpenHashMap<>();
	EmptyLightSection EMPTY_SECTION = new EmptyLightSection();
	
	@Override
	public AbstractColoredLightSection getSection(boolean forLight, long pos) {
		if (red == null) return EMPTY_SECTION;
		
		if (!forLight) {
			return EMPTY_SECTION;
		}
		
		AbstractColoredLightSection section = sections.get(pos);
		
		if (section != null) {
			return section;
		}
		
		synchronized (sections) {
			section = sections.get(pos);
			
			// try again with synchronization
			// reasons:
			// A) a thread may have already created it by now, so we want to return that if possible
			// B) we want to already have predetermined that this thread is going to be the one to create it if this fails
			// tldr; try to eliminate race conditions where the value gets computed twice
			if (section != null) {
				return section;
			}
			
			SectionPos spos = SectionPos.of(pos);
			section = new WrapperLightSection(
					() -> red.getDataLayerData(spos),
					() -> green.getDataLayerData(spos),
					() -> blue.getDataLayerData(spos)
			);
			
			sections.put(pos, section);
		}
		
		return section;
	}
	
	@Override
	public void blockUpdated(LevelAccessor level, BlockPos blockPos) {
		make();
		
		red.checkBlock(blockPos);
		green.checkBlock(blockPos);
		blue.checkBlock(blockPos);
	}
	
	@Override
	public void rebuildChunk(ChunkPos chunkPos, long delay) {
		// TODO: what?
	}
	
	@Override
	public void tick() {
		make();
		
		red.runLightUpdates();
		green.runLightUpdates();
		blue.runLightUpdates();
	}
	
	private void make() {
		if (red == null) {
			if (getLevel().getLevel().getChunkSource() != null) {
				red = new ChanneledBlockEngine(lightInterface.getLevel().getLevel().getChunkSource(), lightInterface.getLevel(), 0);
				green = new ChanneledBlockEngine(lightInterface.getLevel().getLevel().getChunkSource(), lightInterface.getLevel(), 1);
				blue = new ChanneledBlockEngine(lightInterface.getLevel().getLevel().getChunkSource(), lightInterface.getLevel(), 2);
			}
		}
	}
	
	@Override
	public void start() {
		make();
	}
	
	@Override
	public void stop() {
	
	}
	
	@Override
	public void clear() {
	}
	
	@Override
	public int debugFallbackSamples() {
		return 0;
	}
	
	@Override
	public String describeQueue(ChunkPos center) {
		return "";
	}
}
