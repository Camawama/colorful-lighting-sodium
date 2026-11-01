package net.camacraft.colorfullighting.compat.distanthorizons;

import net.camacraft.colorfullighting.common.ColoredLightEngine;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.ModList;

import javax.annotation.Nullable;

/**
 * Distant Horizons compatibility: colored lighting on LODs.
 *
 * <p>Three pieces, all behind the {@code dhLodColorMode} config (default {@code AUTO}: on whenever DH
 * is installed; {@code /cl dh on|off|auto}) and touching nothing unless DH is installed:
 * <ul>
 * <li>{@link DhColorCache}: remembers each visited section's net light colour (downsampled),
 *     persisted per world+dimension under {@code <gamedir>/colorful_lighting/dh_color_cache/}.</li>
 * <li>{@link DhColorVolume}: two moving 3D textures around the camera filled from the cache.</li>
 * <li>{@link DhTerrainColorShaderProgram}: a DH API shader override that samples the volumes and
 *     tints LOD block light. Bound/unbound at runtime via {@code /cl dh on|off|auto}.</li>
 * </ul>
 *
 * <p>Capture runs on a single daemon worker thread fed from the client thread's dirty-section drain
 * ({@link ColoredLightEngine#onLightUpdate()}), so the render
 * path never blocks on it.
 *
 * <p>THIS CLASS MUST STAY FREE OF DH API TYPES. It is called from tick handlers and commands that
 * run whether or not Distant Horizons is installed; every method here short-circuits on
 * {@link #isLoaded()} before touching {@link DhCompatImpl}, which is where all DH API references
 * live. Referencing a DH type here would make loading this class throw NoClassDefFoundError on
 * instances without DH (that exact crash shipped in 2.7.0).
 */
public final class DhCompat {
    public static final String MOD_ID = "distanthorizons";
    /** DhApi major version this was built and tested against (DH 3.1.2-b and 3.2.0-b both report 7). */
    public static final int SUPPORTED_API_MAJOR = 7;

    private static Boolean loaded;
    private static volatile int debugMode = 0;

    private DhCompat() {}

    public static boolean isLoaded() {
        if (loaded == null) loaded = ModList.get().isLoaded(MOD_ID);
        return loaded;
    }

    /** Whether the shader override is currently bound into DH. Gates all per-frame and capture cost. */
    public static boolean isOverrideEnabled() {
        return isLoaded() && DhCompatImpl.isOverrideEnabled();
    }

    public static int getDebugMode() { return debugMode; }
    public static void setDebugMode(int mode) { debugMode = mode; }

    /** Called once from mod loading-complete when DH is present. */
    public static void init() {
        if (!isLoaded()) return;
        DhCompatImpl.init();
    }

    /**
     * Binds or unbinds the shader override at runtime. Safe to call from the client thread.
     *
     * @return a user-facing status message
     */
    public static String setOverrideEnabled(boolean enable) {
        if (!isLoaded()) return "Distant Horizons is not installed";
        return DhCompatImpl.setOverrideEnabled(enable);
    }

    /** Client thread, from the engine's dirty-section drain. */
    public static void onSectionsDirty(ColoredLightEngine engine, long[] sectionPositions) {
        if (!isLoaded()) return;
        DhCompatImpl.onSectionsDirty(engine, sectionPositions);
    }

    /** Render thread accessor; the client tick keeps it pointing at the current level's cache. */
    @Nullable
    public static DhColorCache getActiveCache() {
        if (!isLoaded()) return null;
        return DhCompatImpl.getActiveCache();
    }

    /** Set from the render thread when the shader override breaks; unbound on the next client tick. */
    public static void requestEmergencyUnbind() {
        if (!isLoaded()) return;
        DhCompatImpl.requestEmergencyUnbind();
    }

    /** Client tick: keeps the active cache current and autosaves changed caches every 30s. */
    public static void clientTick() {
        if (!isLoaded()) return;
        DhCompatImpl.clientTick();
    }

    /** Level unload: persist what we learned. The cache object itself stays until the Level is GCed. */
    public static void onLevelUnload(Level level) {
        if (!isLoaded()) return;
        DhCompatImpl.onLevelUnload(level);
    }

    /** Render thread only (called from the shader override's fillUniformData, so DH is present). */
    public static DhColorVolume getOrCreateVolume() {
        return DhCompatImpl.getOrCreateVolume();
    }

    public static String describeStatus() {
        if (!isLoaded()) return "Distant Horizons: not installed";
        return DhCompatImpl.describeStatus();
    }
}
