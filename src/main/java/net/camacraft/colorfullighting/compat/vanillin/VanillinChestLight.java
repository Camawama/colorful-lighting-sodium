package net.camacraft.colorfullighting.compat.vanillin;

import net.camacraft.colorfullighting.common.util.PackedLightData;

/**
 * Vanillin (Flywheel's visuals for vanilla's block entities) lights a double chest the way vanilla's
 * {@code BrightnessCombiner} does: the two halves' {@code LevelRenderer.getLightColor} answers taken apart with
 * {@code LightTexture.block} and {@code sky} and put back with {@code pack}. That round trip (rule 2 of
 * {@code CLPackedLight}) drops the sky nibble of a colored value, so a double chest by a torch drew lit by the torch
 * alone, about a third darker than vanilla's renderer by day (2026-11-07; vanilla's own combiner is covered by
 * {@code BrightnessCombinerMixin}). A single chest takes its one answer whole, so it was never affected.
 *
 * <p>While {@code ChestVisual.updateLight} runs on a thread ({@code VanillinChestVisualMixin}), the colored answers
 * {@code getLightColor} gives on that thread are kept ({@link #answered}), and the light the visual then sets on each
 * of its instances ({@code ColoredLitInstanceMixin} on Flywheel's {@code ColoredLitInstance.light(int)}) is replaced by
 * their colored max ({@link #instanceLight}). Outside that window, or when no colored answer was given, nothing changes.
 * Flywheel relights visuals on its worker threads, hence the per-thread state. Since 2.7.6.
 */
public final class VanillinChestLight {
    /** depth of updateLight on this thread, colored answers given, their max. */
    private static final ThreadLocal<int[]> STATE = ThreadLocal.withInitial(() -> new int[3]);

    private VanillinChestLight() {
    }

    public static void enter() {
        int[] s = STATE.get();
        if (s[0]++ == 0) {
            s[1] = 0;
            s[2] = 0;
        }
    }

    public static void exit() {
        int[] s = STATE.get();
        if (s[0] > 0) s[0]--;
    }

    /** A colored answer {@code LevelRenderer.getLightColor} gave on this thread. */
    public static void answered(int colored) {
        int[] s = STATE.get();
        if (s[0] == 0) return;
        s[2] = s[1] == 0 ? colored : PackedLightData.max(s[2], colored);
        s[1]++;
    }

    /** The light a Flywheel instance is given: inside a chest visual's relight, the colored max of the answers instead. */
    public static int instanceLight(int packed) {
        int[] s = STATE.get();
        return s[0] == 0 || s[1] == 0 ? packed : s[2];
    }
}
