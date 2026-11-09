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
    }
}
