package me.erykczy.colorfullighting.mixin.compat.flywheel;

import dev.engine_room.flywheel.backend.engine.indirect.LightBuffers;
import me.erykczy.colorfullighting.compat.flywheel.FlywheelCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = LightBuffers.class, remap = false)
public class LightBuffersMixin {
    @Inject(method = "bind", at = @At("TAIL"))
    private void colorfullighting$uploadChangedSections(CallbackInfo ci) {
        if (FlywheelCompat.isAvailable()) {
            FlywheelCompat.getInstance().flywheelColoredLightStorage.bindBuffers();
        }
    }
}
