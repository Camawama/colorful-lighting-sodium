package me.erykczy.colorfullighting.mixin.compat.dynamiclights;

import me.erykczy.colorfullighting.common.ColoredLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * SodiumDynamicLights brightens vanilla-format lightmap coords (block-light bits 4-19). All of its
 * brightness injection — the LevelRenderer.getLightColor TAIL hook, the Sodium
 * LightDataAccess.getLightmap / FlatLightPipeline.getOffsetLightmap hooks and the
 * EntityRenderer.getBlockLightLevel hook — funnels through getLightmapWithDynamicLight(double, int).
 * While the colored engine is active those coords carry the packed colored format (red/green live in
 * the block-light bits), so the boost would corrupt them; DynamicLightsCompat re-applies the dynamic
 * light with proper color inside the colored pipeline instead. With the engine off (e.g. an
 * unpatched shaderpack is active), SodiumDynamicLights is left untouched.
 */
@Pseudo
@Mixin(targets = "toni.sodiumdynamiclights.SodiumDynamicLights", remap = false)
public class SodiumDynamicLightsMixin {
    @Inject(method = "getLightmapWithDynamicLight(DI)I", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void colorfullighting$skipVanillaFormatBoost(double dynamicLightLevel, int lightmap, CallbackInfoReturnable<Integer> cir) {
        ColoredLightEngine engine = ColoredLightEngine.getInstance();
        if (engine != null && engine.isEnabled()) {
            cir.setReturnValue(lightmap);
        }
    }
}
