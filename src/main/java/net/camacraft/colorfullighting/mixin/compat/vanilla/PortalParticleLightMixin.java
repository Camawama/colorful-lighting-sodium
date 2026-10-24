package net.camacraft.colorfullighting.mixin.compat.vanilla;

import net.camacraft.colorfullighting.compat.vanilla.ParticleLightRamps;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.PortalParticle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The portal particle (and the reverse portal particle, which inherits this) raises the sky byte of its light as it
 * ages; on a colored value that math read the sky level 15 as 0 and dropped the colour, so the particle was black
 * when spawned and faded in (see {@link ParticleLightRamps}). The same ramp on the colored value instead.
 */
@Mixin(PortalParticle.class)
public abstract class PortalParticleLightMixin extends Particle {
    protected PortalParticleLightMixin(ClientLevel level, double x, double y, double z) {
        super(level, x, y, z);
    }

    @Inject(method = "getLightColor", at = @At("HEAD"), cancellable = true)
    private void colorfullighting$rampSkyInColor(float partialTick, CallbackInfoReturnable<Integer> cir) {
        int light = super.getLightColor(partialTick);
        if (!ParticleLightRamps.applies(light)) return;
        float f = (float) this.age / (float) this.lifetime;
        f *= f;
        f *= f;
        cir.setReturnValue(ParticleLightRamps.raiseSky(light, f));
    }
}
