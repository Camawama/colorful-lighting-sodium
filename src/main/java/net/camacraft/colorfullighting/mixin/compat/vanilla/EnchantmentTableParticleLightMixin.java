package net.camacraft.colorfullighting.mixin.compat.vanilla;

import net.camacraft.colorfullighting.compat.vanilla.ParticleLightRamps;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.EnchantmentTableParticle;
import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The enchantment-table glyphs (and the nautilus particle) raise their sky byte as they age, as the portal particle does: the same ramp on a colored value ({@link ParticleLightRamps}). */
@Mixin(EnchantmentTableParticle.class)
public abstract class EnchantmentTableParticleLightMixin extends Particle {
    protected EnchantmentTableParticleLightMixin(ClientLevel level, double x, double y, double z) {
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
