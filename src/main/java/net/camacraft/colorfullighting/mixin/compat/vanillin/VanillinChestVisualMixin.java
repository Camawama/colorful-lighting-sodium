package net.camacraft.colorfullighting.mixin.compat.vanillin;

import net.camacraft.colorfullighting.compat.vanillin.VanillinChestLight;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks the thread while Vanillin's chest visual relights its instances ({@link VanillinChestLight}): the double chest's
 * light it rebuilds from vanilla's nibbles is then replaced by the colored max of what {@code getLightColor} answered.
 * Applied only where Vanillin's {@code ChestVisual} exists ({@code ColorfulLightingMixinPlugin}).
 */
@Pseudo
@Mixin(targets = "dev.engine_room.vanillin.visuals.ChestVisual", remap = false)
public abstract class VanillinChestVisualMixin {
    @Inject(method = "updateLight", at = @At("HEAD"), require = 0, remap = false)
    private void colorfullighting$relightStart(CallbackInfo ci) {
        VanillinChestLight.enter();
    }

    @Inject(method = "updateLight", at = @At("RETURN"), require = 0, remap = false)
    private void colorfullighting$relightEnd(CallbackInfo ci) {
        VanillinChestLight.exit();
    }
}
