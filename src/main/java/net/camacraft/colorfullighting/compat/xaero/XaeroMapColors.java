package net.camacraft.colorfullighting.compat.xaero;

import net.camacraft.colorfullighting.common.ColoredLightEngine;
import net.camacraft.colorfullighting.common.ColorfulLightingConfig;
import net.camacraft.colorfullighting.common.accessors.mixin.LevelAttachments;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;

/**
 * What Xaero's maps are told about colored light, shared by the minimap and World Map compat. A map pixel's
 * colored light is one int, its <em>key</em>: {@code (r4 << 8 | g4 << 4 | b4) << 4 | blockLight}, the color of the
 * light at the cell Xaero read the pixel's block light from and that block light. 0 means there is nothing to
 * tint: no block light, white light, or the engine has no settled color there.
 *
 * <p>Uses no Xaero class, so it loads whether or not Xaero is installed.
 */
public final class XaeroMapColors {
    /** Set by the pack harness (reflection) to count the time spent reading colors for Xaero's maps. */
    public static volatile boolean timing;
    public static volatile long keyCalls, keyNanos;

    private XaeroMapColors() {
    }

    /** The minimap's own chunks take colors (its writer, used without the World Map or with its World Map chunks off). */
    public static boolean minimapOn() {
        return ColoredLightEngine.isEnabled() && ColorfulLightingConfig.xaeroMinimapColors();
    }

    /** The World Map's chunks take colors (the World Map screen, and the minimap when it shows World Map chunks). */
    public static boolean worldMapOn() {
        return ColoredLightEngine.isEnabled() && ColorfulLightingConfig.xaeroWorldMapColors();
    }

    /**
     * The key of a map pixel whose block light {@code blockLight} Xaero read at {@code x, y, z}; 0 when that light
     * is white or there is none.
     */
    public static int key(Level level, int x, int y, int z, int blockLight) {
        if (blockLight <= 0 || level == null) return 0;
        if (timing) {
            long start = System.nanoTime();
            int key = sampleKey(level, x, y, z, blockLight);
            keyNanos += System.nanoTime() - start;
            keyCalls++;
            return key;
        }
        return sampleKey(level, x, y, z, blockLight);
    }

    private static int sampleKey(Level level, int x, int y, int z, int blockLight) {
        ColoredLightEngine engine = ((LevelAttachments) level).colorfullighting$getEngine();
        if (engine == null) return 0;
        int rgb4 = engine.sampleLightColorPacked(x, y, z) & 0xFFF;
        int r = rgb4 >>> 8 & 0xF, g = rgb4 >>> 4 & 0xF, b = rgb4 & 0xF;
        if (rgb4 == 0 || (r == g && g == b)) return 0;
        return rgb4 << 4 | Math.min(blockLight, 15);
    }

    /**
     * Whether the engine's color at a cell is final: the chunk lies inside the engine's view area, where light is
     * fully propagated (border chunks hold partial spill-in), so a remembered color won't be overwritten with a
     * clipped one. Client thread.
     */
    public static boolean settled(Level level, int x, int y, int z) {
        if (level == null) return false;
        ColoredLightEngine engine = ((LevelAttachments) level).colorfullighting$getEngine();
        return engine != null && engine.dhIsSectionCaptureSafe(SectionPos.asLong(x >> 4, y >> 4, z >> 4));
    }

    /** The light's share of a channel, 0..1: the channel over the key's brightest channel. */
    public static float hue(int key, int shift) {
        int rgb4 = key >>> 4;
        int r = rgb4 >>> 8 & 0xF, g = rgb4 >>> 4 & 0xF, b = rgb4 & 0xF;
        int brightest = Math.max(r, Math.max(g, b));
        return brightest == 0 ? 1F : (float) (rgb4 >>> shift & 0xF) / brightest;
    }

    /** The block light stored in a key. */
    public static int light(int key) {
        return key & 0xF;
    }
}
