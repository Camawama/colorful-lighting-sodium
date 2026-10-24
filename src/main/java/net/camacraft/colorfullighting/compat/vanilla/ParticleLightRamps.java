package net.camacraft.colorfullighting.compat.vanilla;

import net.camacraft.colorfullighting.api.CLPackedLight;
import net.camacraft.colorfullighting.common.ColorfulLightingConfig;

/**
 * Vanilla's own particle light ramps, redone in the colored packed-light format.
 *
 * <p>Five vanilla particles override {@code getLightColor} with bit arithmetic on vanilla's packed light
 * ({@code j = light & 255} for the block byte, {@code k = light >> 16 & 255} for the sky byte, one of them raised,
 * then {@code j | k << 16}): the portal and enchantment-table particles raise the SKY byte as they age (purple at
 * once under the open sky by day, a dark start only where there is no sky), flames and the glow particle raise the
 * BLOCK byte, lava sets it full. On a colored value (R8 G8 B8 + sky4 + the 0xF marker, {@link CLPackedLight}) that math
 * reads the red channel as the block light and {@code sky4 | blue & 0xF << 4} as the sky, drops the colour and the
 * marker, and hands the renderer a vanilla-format value: a portal particle by day read sky {@code 15 >> 4 = 0},
 * so it was black and brightened only as the ramp reached 240 (the owner, 2026-10-18: "when the portal particle is
 * spawned in via command, it is completely black, and then it fades to purple before its time is up"); a flame in the
 * open by day started black for the same reason. The mixins in {@code mixin.compat.vanilla} run these instead when
 * the inherited value is colored: the sky level raised by the same share, or white light of the same level added to
 * the colour, the rest of the value kept. Vanilla-format values are left to vanilla's own math. Switch
 * {@code particleLightRamps} in the client config.
 */
public final class ParticleLightRamps {
    private ParticleLightRamps() {}

    /** Whether a ramp here applies: the switch on and the inherited value in the colored format. */
    public static boolean applies(int light) {
        return CLPackedLight.isColored(light) && ColorfulLightingConfig.particleLightRamps();
    }

    /** Vanilla's {@code k += f * 15 * 16} on the sky byte: the sky level raised by {@code f * 15}, capped at 15, colour kept. */
    public static int raiseSky(int light, float f) {
        int sky = Math.min(15, CLPackedLight.sky4(light) + Math.round(clamp(f) * 15.0F));
        return CLPackedLight.pack(sky, CLPackedLight.red8(light), CLPackedLight.green8(light), CLPackedLight.blue8(light));
    }

    /** Vanilla's {@code j += f * 15 * 16} on the block byte: white light of level {@code f * 15} added to the colour, each channel capped. */
    public static int raiseBlock(int light, float f) {
        int add = Math.round(clamp(f) * 255.0F);
        return CLPackedLight.pack(CLPackedLight.sky4(light),
                Math.min(255, CLPackedLight.red8(light) + add),
                Math.min(255, CLPackedLight.green8(light) + add),
                Math.min(255, CLPackedLight.blue8(light) + add));
    }

    /** Vanilla's {@code 240 | k << 16}: full white block light, the sky kept. */
    public static int fullBlock(int light) {
        return CLPackedLight.pack(CLPackedLight.sky4(light), 255, 255, 255);
    }

    private static float clamp(float f) {
        return f < 0.0F ? 0.0F : f > 1.0F ? 1.0F : f;
    }
}
