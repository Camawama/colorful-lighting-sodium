package net.camacraft.colorfullighting.compat.asyncparticles;

import net.camacraft.colorfullighting.common.ColoredLightEngine;
import net.camacraft.colorfullighting.common.accessors.mixin.LevelAttachments;
import net.camacraft.colorfullighting.common.util.PackedLightData;
import net.camacraft.colorfullighting.compat.sodium.SodiumPackedLightData;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

public final class AsyncParticlesCompat {
    private AsyncParticlesCompat() {}

    /**
     * Colorizes the vanilla-format seed AsyncParticles computes for block-destruction particles.
     *
     * <p>AsyncParticles seeds every destruction particle's light cache from
     * {@code GameUtil.getLightColorFromNeighbor} — a raw {@code DataLayer} neighbor scan that
     * bypasses {@code LevelRenderer.getLightColor} (and with it our colored sampler), because the
     * vanilla light at the just-destroyed pos is still the opaque block's stale zero. The seed is
     * therefore vanilla white; the particle's first async tick then refreshes through our hook,
     * so it flashes uncolored for exactly one frame.
     *
     * <p>The same staleness applies to our engine's cell at the destroyed pos, so this mirrors
     * their scan in colored space: a per-channel max over the pos and its six neighbors, packed
     * with the sky nibble their scan already resolved.
     */
    public static int colorizeDestructionLight(ClientLevel level, BlockPos pos, int vanillaPacked) {
        if (!ColoredLightEngine.isEnabled() || !(level instanceof LevelAttachments attachments))
            return vanillaPacked;
        ColoredLightEngine engine = attachments.colorfullighting$getEngine();
        if (engine == null)
            return vanillaPacked;

        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        // the seed takes the drawn dynamic lights on whichever thread it is made (2.10.3): off the render thread it left them
        // out, and a block broken in a red dynamic light began with white particles at the vanilla level
        boolean live = net.camacraft.colorfullighting.compat.dynamiclights.DynamicLightsCompat.beginLiveSample();
        int color;
        try {
            color = sampleRound(engine, x, y, z);
        } finally {
            if (live) net.camacraft.colorfullighting.compat.dynamiclights.DynamicLightsCompat.endLiveSample();
        }
        net.camacraft.colorfullighting.compat.dynamiclights.DynamicLightsCompat.debugNote("seed");

        int sky4 = (vanillaPacked >>> 20) & 0xF;
        if (color == 0) {
            // no colored light stored nearby; keep the vanilla scan's level as white light
            int block8 = ((vanillaPacked >>> 4) & 0xF) * 17;
            return PackedLightData.packData(sky4, block8, block8, block8);
        }
        return SodiumPackedLightData.packDataFromRGB4(sky4, color);
    }

    /** The per-channel max of the colored light at a block and its six neighbours. */
    private static int sampleRound(ColoredLightEngine engine, int x, int y, int z) {
        var cursor = engine.acquireCursor();
        int color = engine.sampleLightColorPacked(cursor, x, y, z);
        color = maxRGB4(color, engine.sampleLightColorPacked(cursor, x, y + 1, z));
        color = maxRGB4(color, engine.sampleLightColorPacked(cursor, x, y - 1, z));
        color = maxRGB4(color, engine.sampleLightColorPacked(cursor, x - 1, y, z));
        color = maxRGB4(color, engine.sampleLightColorPacked(cursor, x + 1, y, z));
        color = maxRGB4(color, engine.sampleLightColorPacked(cursor, x, y, z - 1));
        color = maxRGB4(color, engine.sampleLightColorPacked(cursor, x, y, z + 1));
        return color;
    }

    /** Per-channel max of two packed 12-bit {@code r << 8 | g << 4 | b} colors. */
    private static int maxRGB4(int a, int b) {
        return Math.max(a & 0xF00, b & 0xF00)
                | Math.max(a & 0x0F0, b & 0x0F0)
                | Math.max(a & 0x00F, b & 0x00F);
    }
}
