package net.camacraft.colorfullighting.mixin.render;

import net.camacraft.colorfullighting.api.CLPackedLight;
import net.camacraft.colorfullighting.common.ColoredLightEngine;
import net.camacraft.colorfullighting.common.util.PackedLightData;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ModelBlockRenderer.AmbientOcclusionFace.class)
public class AmbientOcclusionFaceMixin {

    @Inject(method = "blend(IIII)I", at = @At("HEAD"), cancellable = true)
    private void colorfullighting$blend(int lightColor0, int lightColor1, int lightColor2, int lightColor3, CallbackInfoReturnable<Integer> cir) {
        if (!ColoredLightEngine.isEnabled()) {
            return;
        }
        if (!CLPackedLight.isColored(lightColor0) && !CLPackedLight.isColored(lightColor1) &&
            !CLPackedLight.isColored(lightColor2) && !CLPackedLight.isColored(lightColor3)) {
            return;
        }
        cir.setReturnValue(PackedLightData.blend(lightColor0, lightColor1, lightColor2, lightColor3));
    }

    @Inject(method = "blend(IIIIFFFF)I", at = @At("HEAD"), cancellable = true)
    private void colorfullighting$blend(int lightColor0, int lightColor1, int lightColor2, int lightColor3, float weight0, float weight1, float weight2, float weight3, CallbackInfoReturnable<Integer> cir) {
        if (!ColoredLightEngine.isEnabled()) {
            return;
        }
        if (!CLPackedLight.isColored(lightColor0) && !CLPackedLight.isColored(lightColor1) &&
            !CLPackedLight.isColored(lightColor2) && !CLPackedLight.isColored(lightColor3)) {
            return;
        }
        cir.setReturnValue(PackedLightData.blend(lightColor0, lightColor1, lightColor2, lightColor3, weight0, weight1, weight2, weight3));
    }
}
