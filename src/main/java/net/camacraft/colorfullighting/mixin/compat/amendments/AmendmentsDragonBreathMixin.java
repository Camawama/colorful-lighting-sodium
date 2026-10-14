package net.camacraft.colorfullighting.mixin.compat.amendments;

import net.camacraft.colorfullighting.compat.CompatPackedLight;
import net.camacraft.colorfullighting.compat.amendments.AmendmentsCompat;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.DragonBreathParticle;
import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Amendments makes dragon's breath particles emissive ({@code dragon_breath_emissive}, on by default) with a
 * {@code getLightColor} override it adds to {@link DragonBreathParticle}:
 * {@code LightTexture.pack(max(LightTexture.block(light), 10), LightTexture.sky(light))}. That round trip (rule 2 of
 * {@code CLPackedLight}) turns a colored value into red light at level 10 with no sky light, so the purple breath was
 * drawn red everywhere, in the dark and under white light alike (the owner: "Dragon breath particles are appearing
 * as red"; pack harness light_dragon: every particle's light was R160 G0 B0).
 *
 * <p>Runs after Amendments' override (priority above its 1000) and answers first: a colored value keeps its colour,
 * raised to at least white level 10 when the option is on; a vanilla value is left to Amendments' own math.
 */
@Mixin(value = DragonBreathParticle.class, priority = 1100)
public abstract class AmendmentsDragonBreathMixin extends Particle {
    protected AmendmentsDragonBreathMixin(ClientLevel level, double x, double y, double z) {
        super(level, x, y, z);
    }

    @Inject(method = {"getLightColor", "m_6355_"}, at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void colorfullighting$keepColoredBreath(float partialTick, CallbackInfoReturnable<Integer> cir) {
        int light = super.getLightColor(partialTick);
        if (!CompatPackedLight.isColored(light)) return;
        cir.setReturnValue(AmendmentsCompat.dragonBreathEmissive() ? CompatPackedLight.maxWithLightLevel(light, 10) : light);
    }
}
