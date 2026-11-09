package net.camacraft.colorfullighting.mixin.compat.flywheel;

import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import net.camacraft.colorfullighting.ColorfulLighting;
import net.camacraft.colorfullighting.compat.flywheel.ColoredLightFlywheelStorage;
import net.camacraft.colorfullighting.compat.flywheel.FlywheelCompat;
import org.lwjgl.opengl.GL20C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * In the buffer-texture fallback (GLSL <430, see FlywheelCompat.isTextureFallback), the colored
 * light section data is read through the _cl_coloredLightSections sampler declared by
 * colored_light.glsl, and unlike an SSBO binding a sampler uniform must be pointed at its texture
 * unit per program. Flywheel only wires up its own samplers, so this points ours at unit 10 on the
 * program's first bind. First bind, NOT construction: flywheel constructs GlProgram around a
 * freshly created, still-unlinked program id (ProgramLinker.linkInternal attaches and links
 * afterwards), so a construction-time glGetUniformLocation always answered -1 with a
 * GL_INVALID_OPERATION — which left the sampler on unit 0, reading garbage on desktop drivers
 * (white flywheel light) and killing every draw on macOS, whose draw-time validation rejects a
 * sampler-type conflict on a unit. At bind() TAIL the program is linked and current, so the
 * uniform is set with a plain glUniform1i.
 */
@Mixin(value = GlProgram.class, remap = false)
public class GlProgramMixin {
    @Unique
    private boolean colorfullighting$samplerBound;
    /** {@code _cl_nightVibrancy}'s location in this program: -2 not looked up yet, -1 none. */
    @Unique
    private int colorfullighting$vibrancyLocation = -2;

    /**
     * The night vibrancy for the colored light composite ({@code mixColoredLightWithLightMap} in colored_light.glsl), set on
     * every bind as the chunk and core shaders get theirs each frame. Since 2.7.6.
     */
    @Inject(method = "bind", at = @At("TAIL"))
    private void colorfullighting$setNightVibrancy(CallbackInfo ci) {
        if (!FlywheelCompat.isAvailable()) return;
        if (colorfullighting$vibrancyLocation == -2) {
            colorfullighting$vibrancyLocation = ((GlProgram) (Object) this).getUniformLocation("_cl_nightVibrancy");
        }
        if (colorfullighting$vibrancyLocation >= 0) {
            GL20C.glUniform1f(colorfullighting$vibrancyLocation, FlywheelCompat.nightVibrancy());
        }
        // the dynamic lights of this frame (2.8.0), to programs that carry colored_light.glsl
        net.camacraft.colorfullighting.compat.dynamiclights.ShaderDynamicLights.uploadFlywheel(((GlProgram) (Object) this).handle());
    }

    @Inject(method = "bind", at = @At("TAIL"))
    private void colorfullighting$bindColoredLightSampler(CallbackInfo ci) {
        if (colorfullighting$samplerBound || !FlywheelCompat.isTextureFallback()) return;
        colorfullighting$samplerBound = true;

        GlProgram self = (GlProgram) (Object) this;
        int location = self.getUniformLocation(ColoredLightFlywheelStorage.COLORED_LIGHT_SAMPLER_NAME);
        // one line per program at first bind; -1 is normal for programs without colored light
        // (utility/composite shaders) but on a pipeline program it would mean the sampler was
        // eliminated — keep while the fallback is being stabilized
        ColorfulLighting.LOGGER.info("[CL flywheel] program {}: sampler '{}' location {}",
                self.handle(), ColoredLightFlywheelStorage.COLORED_LIGHT_SAMPLER_NAME, location);
        if (location >= 0) {
            GL20C.glUniform1i(location, ColoredLightFlywheelStorage.COLORED_LIGHT_TEXTURE_UNIT);
        }
    }
}
