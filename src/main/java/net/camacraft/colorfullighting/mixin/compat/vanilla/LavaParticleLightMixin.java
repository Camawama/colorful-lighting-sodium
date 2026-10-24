package net.camacraft.colorfullighting.mixin.compat.vanilla;

import net.camacraft.colorfullighting.compat.vanilla.ParticleLightRamps;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.LavaParticle;
import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Lava's ember sets its block byte full and keeps the sky; on a colored value the sky came out as the blue channel's low bits. Full white block light with the real sky instead ({@link ParticleLightRamps}). */
@Mixin(LavaParticle.class)
public abstract class LavaParticleLightMixin extends Particle {
    protected LavaParticleLightMixin(ClientLevel level, double x, double y, double z) {
        super(level, x, y, z);
    }

    @Inject(method = "getLightColor", at = @At("HEAD"), cancellable = true)
    private void colorfullighting$fullBlockInColor(float partialTick, CallbackInfoReturnable<Integer> cir) {
        int light = super.getLightColor(partialTick);
        if (!ParticleLightRamps.applies(light)) return;
        cir.setReturnValue(ParticleLightRamps.fullBlock(light));
    }
}
