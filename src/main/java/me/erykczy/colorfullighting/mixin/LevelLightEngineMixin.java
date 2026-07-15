package me.erykczy.colorfullighting.mixin;

import me.erykczy.colorfullighting.common.ColoredLightEngine;
import me.erykczy.colorfullighting.common.accessors.mixin.LevelAttachments;
import me.erykczy.colorfullighting.common.accessors.mixin.LightEngineAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelLightEngine.class)
public class LevelLightEngineMixin implements LightEngineAccessor {
	@Unique
	private LightChunkGetter colorfullighting$lightChunkGetter;
	
	@Inject(at = @At("TAIL"), method = "<init>")
	public void postInit(LightChunkGetter p_75805_, boolean p_75806_, boolean p_75807_, CallbackInfo ci) {
		colorfullighting$lightChunkGetter = p_75805_;
	}
	
    @Inject(method = "runLightUpdates", at = @At("HEAD"))
    private void colorfullighting$runLightUpdates(CallbackInfoReturnable<Integer> cir) {
        if (!ColoredLightEngine.isEnabled()) {
            return;
        }
        if(!Minecraft.getInstance().isSameThread()) return; // only client side
        ((LevelAttachments) colorfullighting$lightChunkGetter.getLevel()).colorfullighting$getEngine().onLightUpdate();
    }
	
	@Override
	public LightChunkGetter colorfullighting$getChunkGetter() {
		return colorfullighting$lightChunkGetter;
	}
}
