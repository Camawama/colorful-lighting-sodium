package me.erykczy.colorfullighting.mixin.compat.flywheel;

import dev.engine_room.flywheel.backend.engine.LightStorage;
import dev.engine_room.flywheel.backend.engine.instancing.InstancedLight;
import me.erykczy.colorfullighting.compat.flywheel.FlywheelCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The instancing backend does not use LightBuffers/StagingBuffer (those are indirect-backend
 * classes hooked by LightBuffersMixin/LightStorageMixin); it flushes and binds light through
 * its own InstancedLight. Without these hooks the colored light SSBO (binding 8) is never
 * uploaded or bound under instancing, so flywheel-rendered objects get no colored light.
 */
@Mixin(value = InstancedLight.class, remap = false)
public class InstancedLightMixin {
    @Inject(method = "flush", at = @At("TAIL"))
    private void colorfullighting$flush(LightStorage lightStorage, CallbackInfo ci) {
        if (FlywheelCompat.isAvailable()) {
            FlywheelCompat.getInstance().flywheelColoredLightStorage.uploadChangedSectionsDirect();
        }
    }

    @Inject(method = "bind", at = @At("TAIL"))
    private void colorfullighting$bind(CallbackInfo ci) {
        if (FlywheelCompat.isAvailable()) {
            FlywheelCompat.getInstance().flywheelColoredLightStorage.bindBuffers();
        }
    }
}