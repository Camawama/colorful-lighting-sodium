package net.camacraft.colorfullighting.mixin.compat.vanilla;

import net.camacraft.colorfullighting.compat.vanilla.ParticleLightRamps;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.GlowParticle;
import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The glow particles (glow squid ink, electric sparks, scrape, wax on and off) raise their block byte as they age, as a flame does: white light of the same level added to the colour ({@link ParticleLightRamps}). */
@Mixin(GlowParticle.class)
public abstract class GlowParticleLightMixin extends Particle {
    protected GlowParticleLightMixin(ClientLevel level, double x, double y, double z) {
        super(level, x, y, z);
    }

    @Inject(method = "getLightColor", at = @At("HEAD"), cancellable = true)
    private void colorfullighting$rampBlockInColor(float partialTick, CallbackInfoReturnable<Integer> cir) {
        int light = super.getLightColor(partialTick);
        if (!ParticleLightRamps.applies(light)) return;
        cir.setReturnValue(ParticleLightRamps.raiseBlock(light, ((float) this.age + partialTick) / (float) this.lifetime));
    }
}
