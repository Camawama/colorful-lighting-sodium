package net.camacraft.colorfullighting.mixin.compat.vanilla;

import net.camacraft.colorfullighting.compat.vanilla.ParticleLightRamps;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.FlameParticle;
import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A flame (and the small flame) raises its block byte as it ages; on a colored value that read the red channel as the level and lost the sky, so a flame in the open by day started black. White light of the same level is added to the colour instead ({@link ParticleLightRamps}). */
@Mixin(FlameParticle.class)
public abstract class FlameParticleLightMixin extends Particle {
    protected FlameParticleLightMixin(ClientLevel level, double x, double y, double z) {
        super(level, x, y, z);
    }

    @Inject(method = "getLightColor", at = @At("HEAD"), cancellable = true)
    private void colorfullighting$rampBlockInColor(float partialTick, CallbackInfoReturnable<Integer> cir) {
        int light = super.getLightColor(partialTick);
        if (!ParticleLightRamps.applies(light)) return;
        cir.setReturnValue(ParticleLightRamps.raiseBlock(light, ((float) this.age + partialTick) / (float) this.lifetime));
    }
}
