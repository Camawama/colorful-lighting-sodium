package me.erykczy.colorfullighting.mixin.compat.flywheel;

import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.glsl.GlslVersion;
import me.erykczy.colorfullighting.ColorfulLighting;
import me.erykczy.colorfullighting.common.ColorfulLightingConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Testing aid ('/cl flywheel texture'): caps GlCompat.MAX_GLSL_VERSION at 410 so a modern GPU runs
 * the exact pipeline older GPUs (GL 4.1 Macs) get. This one value steers everything in lockstep:
 * flywheel stamps it as the #version of every shader (PipelineCompiler), derives the extension set
 * from it, and FlywheelCompat/colored_light.glsl choose SSBO vs buffer-texture from it. It is read
 * once when GlCompat's static initializer runs, hence the mode only changes on a game restart.
 */
@Mixin(value = GlCompat.class, remap = false)
public class GlCompatMixin {
    @Inject(method = "maxGlslVersion", at = @At("RETURN"), cancellable = true)
    private static void colorfullighting$capForTextureModeTesting(CallbackInfoReturnable<GlslVersion> cir) {
        boolean forced = ColorfulLightingConfig.flywheelForceTextureMode();
        // always logged: this decides the colored-light transport for the whole session, and a
        // silently-not-engaging force cost a full debugging round once already
        ColorfulLighting.LOGGER.info("Flywheel GLSL probe: {} (colorful_lighting force-texture: {})",
                cir.getReturnValue(), forced);
        if (!forced) return;
        if (cir.getReturnValue().compareTo(GlslVersion.V410) > 0) {
            ColorfulLighting.LOGGER.warn("flywheelForceTextureMode is enabled: capping flywheel at GLSL 410 (was {}). Colored light uses the buffer-texture path; use the instancing backend.", cir.getReturnValue());
            cir.setReturnValue(GlslVersion.V410);
        }
    }
}
