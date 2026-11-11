package net.camacraft.colorfullighting.mixin.render;

import net.camacraft.colorfullighting.compat.dynamiclights.ShaderDynamicLights;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A frame begins: the shader path's dynamic light arrays are rebuilt once for it (2.8.0). */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererFrameMixin {
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void colorfullighting$newFrame(CallbackInfo ci) {
        ShaderDynamicLights.newFrame();
        net.camacraft.colorfullighting.compat.vanilla.EntityBlacklight.levelStart();
        net.camacraft.colorfullighting.bloom.ItemGlows.levelStart();
    }

    /** The level is drawn: what is drawn from here on (the hand, the screens) is at no place in the world (2.11.1). */
    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void colorfullighting$levelDrawn(CallbackInfo ci) {
        net.camacraft.colorfullighting.compat.vanilla.EntityBlacklight.levelEnd();
        net.camacraft.colorfullighting.bloom.ItemGlows.levelEnd();
    }
}
