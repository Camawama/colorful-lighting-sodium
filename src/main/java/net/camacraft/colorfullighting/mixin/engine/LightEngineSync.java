package net.camacraft.colorfullighting.mixin.engine;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.camacraft.colorfullighting.common.ColoredLightEngine;
import net.camacraft.colorfullighting.common.accessors.mixin.LevelAttachments;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashSet;

@Mixin(LevelLightEngine.class)
public class LightEngineSync {
	@Shadow
	@Final
	protected LevelHeightAccessor levelHeightAccessor;
	
	@Unique
	final LongOpenHashSet enabledLights = new LongOpenHashSet();
	@Unique
	final HashSet<SectionPos> enabledSections = new HashSet<>();
	
	private boolean enable;
	ColoredLightEngine engine;
	
	@Inject(at = @At("TAIL"), method = "<init>")
	public void postInit(LightChunkGetter p_75805_, boolean p_75806_, boolean p_75807_, CallbackInfo ci) {
		BlockGetter lvl = p_75805_.getLevel();
		if (lvl instanceof ClientLevel clvl) {
			enable = true;
			this.engine = ((LevelAttachments) clvl).colorfullighting$getEngine();
			engine.setChunkList(enabledLights);
			engine.setSectionList(enabledSections);
			if (!engine.isEngineInitialized()) {
				engine.initEngine(p_75805_);
			}
		} else if (lvl instanceof LevelAttachments attachments) {
			this.engine = attachments.colorfullighting$getEngine();
			if (engine != null) {
				enable = true;
			} else {
				enable = false;
			}
		} else {
			enable = false;
		}
	}
	
	@Inject(at = @At("HEAD"), method = "setLightEnabled")
	public void postEnableLights(ChunkPos pPos, boolean enabled, CallbackInfo ci) {
		if (enable) {
			if (enabled) enabledLights.add(pPos.toLong());
			else enabledLights.remove(pPos.toLong());
			
			engine.setChunkEnabled(pPos, enabled);
		}
	}
	
	@Inject(at = @At("HEAD"), method = "updateSectionStatus")
	public void preUpdateSection(SectionPos pPos, boolean pIsEmpty, CallbackInfo ci) {
		if (enable) {
			if (pIsEmpty) enabledSections.remove(pPos);
			else enabledSections.add(pPos);

			engine.setSectionEnabled(pPos, !pIsEmpty);
		}
	}
}
