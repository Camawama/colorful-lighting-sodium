package net.camacraft.colorfullighting.common;

import net.minecraftforge.common.ForgeConfigSpec;

public class ColorfulLightingConfig {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    /**
     * How hard the colored light propagator is allowed to work.
     *
     * <p>Colored light is computed on a background thread after a chunk has already loaded, and every
     * chunk it finishes makes the renderer rebuild and re-upload that chunk's mesh. Working faster
     * fills the light in sooner but concentrates those mesh uploads, which is what can stutter on a
     * slower machine. Pausing between passes spreads them out.
     *
     * <p><b>The pause is proportional to the work just done, not a fixed number of milliseconds.</b>
     * A propagation pass cannot be interrupted mid-chunk, so the smallest unit of work is one whole
     * chunk — which in the Nether costs far more than any sane fixed budget. A fixed 8ms pause after
     * a 15ms chunk barely throttles anything; measurements showed a "gentle" fixed pause moving the
     * duty cycle only from 67% to 60%. Pausing for {@code work x pauseFactor} instead gives a duty
     * cycle of {@code 1 / (1 + pauseFactor)} regardless of how expensive a chunk turns out to be.
     *
     * @param budgetMillis how long a pass keeps propagating before pausing (a pass always finishes
     *                     the chunk it started, so this is a floor, not a ceiling)
     * @param pauseFactor  multiple of the elapsed work time to pause for; 0 yields instead of sleeping
     */
    public enum LightUpdateSpeed {
        FASTEST(8, 0.0),   // 100% duty
        FAST(8, 0.25),     //  80% duty
        BALANCED(8, 1.0),  //  50% duty
        GENTLE(4, 3.0);    //  25% duty

        private final long budgetMillis;
        private final double pauseFactor;

        LightUpdateSpeed(long budgetMillis, double pauseFactor) {
            this.budgetMillis = budgetMillis;
            this.pauseFactor = pauseFactor;
        }

        public long budgetNanos() { return budgetMillis * 1_000_000L; }
        public double pauseFactor() { return pauseFactor; }
    }

    public static final ForgeConfigSpec.BooleanValue ENABLED;
    public static final ForgeConfigSpec.BooleanValue AUTO_PATCH_SHADERPACKS;
    public static final ForgeConfigSpec.EnumValue<LightUpdateSpeed> LIGHT_UPDATE_SPEED;
    public static final ForgeConfigSpec.BooleanValue FLYWHEEL_FORCE_TEXTURE_MODE;
    public static final ForgeConfigSpec.BooleanValue AUTO_EMITTER_COLORS;
    public static final ForgeConfigSpec.BooleanValue DH_LOD_COLOR;
    public static final ForgeConfigSpec.BooleanValue SMOOTH_LIGHT_STAYS_BEHIND_BLOCKERS;
    public static final ForgeConfigSpec.BooleanValue XAERO_MINIMAP_COLORS;
    public static final ForgeConfigSpec.BooleanValue XAERO_WORLD_MAP_COLORS;

    public static final ForgeConfigSpec SPEC;

    static {
        ENABLED = BUILDER.comment("Enable colorful lighting").define("enabled", true);
        FLYWHEEL_FORCE_TEXTURE_MODE = BUILDER
                .comment(
                        "TESTING ONLY. Caps flywheel's GLSL version at 410, forcing the whole flywheel pipeline down",
                        "the same path older GPUs (e.g. GL 4.1 Macs) take: colored light travels as a buffer texture",
                        "instead of an SSBO. Takes effect on the next game launch (the mode is baked into every",
                        "compiled flywheel shader). Use '/flywheel backend instancing' with this - the indirect",
                        "backend needs GLSL 460 and cannot work while capped. Toggle with '/cl debug flywheel texture|auto'.")
                .define("flywheelForceTextureMode", false);
        AUTO_PATCH_SHADERPACKS = BUILDER
                .comment("Automatically create '<Pack> + ColorfulLighting' copies of shaderpacks that decode colored lighting (requires Oculus)")
                .define("autoPatchShaderpacks", false);
        AUTO_EMITTER_COLORS = BUILDER
                .comment(
                        "Automatically derive a light color from a block's texture for light sources that have",
                        "no configured color (typically modded blocks). Bright texture pixels dominate the sample,",
                        "so a lamp glows in the color of its glowing part rather than its casing. Blocks whose",
                        "color depends on position (via a block color provider) are tinted per position.",
                        "Disable to make unconfigured light sources emit plain white light as before.")
                .define("autoEmitterColors", true);
        DH_LOD_COLOR = BUILDER
                .comment(
                        "Colored lighting on Distant Horizons LODs. Remembers the light colours of chunks you",
                        "visit and replays them on the LODs after those chunks unload, out to about 1500 blocks.",
                        "Needs Distant Horizons 3.x (DhApi 7). Costs ~56MB of VRAM while enabled.",
                        "Toggle in-game with '/cl dh on' and '/cl dh off'.")
                .define("dhLodColor", false);
        SMOOTH_LIGHT_STAYS_BEHIND_BLOCKERS = BUILDER
                .comment(
                        "Smooth lighting takes a block face's light from the cells round its corners. With this on, a",
                        "neighbour's colored light counts only as far as it could reach the face: light loses a level per",
                        "block, so a cell more than a level brighter than the way it would come by got its light round",
                        "something that stops it. Open scenes look exactly as with this off; light just doesn't glow round",
                        "blocks that stop it: no bright patch under and beside a shut door on its dark side, no light round",
                        "a beacon or lamp walled in tinted glass. Off: the old blend, which spread such light a block wide.")
                .define("smoothLightStaysBehindBlockers", true);
        XAERO_MINIMAP_COLORS = BUILDER
                .comment(
                        "Xaero's Minimap with its Lighting option on, on the chunks the minimap draws itself (without Xaero's",
                        "World Map, or with its 'World Map chunks' option off): the block-lit part of a map pixel takes the",
                        "color of the light there (a red lamp's surroundings show red at night), in cave mode too. A pixel is",
                        "redrawn when its light's color changes. While on, the minimap draws its own chunks in cave mode even",
                        "with the World Map installed (its cave layers carry no colors in singleplayer). Off: Xaero's grey",
                        "lighting and its own choice of chunks.")
                .define("xaeroMinimapColors", true);
        XAERO_WORLD_MAP_COLORS = BUILDER
                .comment(
                        "Xaero's World Map with its Lighting option on (the World Map screen, and the minimap when it shows",
                        "World Map chunks): the same colors, recorded when the World Map itself updates a chunk you are near",
                        "and kept with its map (a .clcolors file beside each region), so a place keeps the colors it had when",
                        "you were last there. Nothing is read for places you haven't been. The World Map's cave layers stay",
                        "grey in singleplayer (it builds them from the world save); the minimap shows caves from its own",
                        "chunks (xaeroMinimapColors). Off: Xaero's grey lighting (what was recorded stays on disk).")
                .define("xaeroWorldMapColors", true);
        LIGHT_UPDATE_SPEED = BUILDER
                .comment(
                        "How quickly colored light fills in after chunks load.",
                        "Faster looks better; slower spreads out chunk mesh rebuilds and can help framerate on lower-end machines.",
                        "Change this with the game closed - the log line 'Colored light engine reset (lightUpdateSpeed=...)'",
                        "reports the value actually in force.",
                        "  FASTEST  - no pause; light fills in as fast as the CPU allows (default)",
                        "  FAST     - pauses for 1/4 of the time it spent working (~80% speed)",
                        "  BALANCED - pauses for as long as it worked (~50% speed)",
                        "  GENTLE   - pauses for 3x as long as it worked (~25% speed)")
                .defineEnum("lightUpdateSpeed", LightUpdateSpeed.FASTEST);
        SPEC = BUILDER.build();
    }

    /** Safe before the config file is loaded; auto colors stay off until the config is available. */
    public static boolean autoEmitterColors() {
        return SPEC.isLoaded() && AUTO_EMITTER_COLORS.get();
    }

    /** Safe before the config file is loaded; DH LOD colour stays off until the config is available. */
    public static boolean dhLodColor() {
        return SPEC.isLoaded() && DH_LOD_COLOR.get();
    }

    /** Safe before the config file is loaded; on (the default) until the config is available. */
    public static boolean xaeroMinimapColors() {
        return !SPEC.isLoaded() || XAERO_MINIMAP_COLORS.get();
    }

    /** Safe before the config file is loaded; on (the default) until the config is available. */
    public static boolean xaeroWorldMapColors() {
        return !SPEC.isLoaded() || XAERO_WORLD_MAP_COLORS.get();
    }

    /** Safe before the config file is loaded; on (the default) until the config is available. */
    public static boolean smoothLightStaysBehindBlockers() {
        return !SPEC.isLoaded() || SMOOTH_LIGHT_STAYS_BEHIND_BLOCKERS.get();
    }

    /** Safe before the config file is loaded (e.g. during early startup), where {@code get()} would throw. */
    public static LightUpdateSpeed lightUpdateSpeed() {
        if (!SPEC.isLoaded()) return LightUpdateSpeed.FAST;
        return LIGHT_UPDATE_SPEED.get();
    }

    /** Cached result of the raw-file read below; the value can't change while the game runs. */
    private static Boolean forceTextureModeFromFile;

    /**
     * Safe before the config file is loaded: GlCompatMixin reads this during flywheel's GL probing,
     * which runs before Forge loads client configs — in that window the setting is read straight
     * from the config file on disk (written by the '/cl flywheel' command last session). The JVM
     * property is an additional override that can never lose a timing race.
     */
    public static boolean flywheelForceTextureMode() {
        if (Boolean.getBoolean("colorfullighting.flywheelForceTextureMode")) return true;
        if (SPEC.isLoaded()) return FLYWHEEL_FORCE_TEXTURE_MODE.get();
        return forceTextureModeFromFile();
    }

    private static boolean forceTextureModeFromFile() {
        if (forceTextureModeFromFile != null) return forceTextureModeFromFile;
        boolean result = false;
        try {
            java.nio.file.Path path = net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get()
                    .resolve("colorful_lighting-client.toml");
            if (java.nio.file.Files.exists(path)) {
                for (String line : java.nio.file.Files.readAllLines(path)) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("flywheelForceTextureMode")) {
                        result = trimmed.replace(" ", "").endsWith("=true");
                        break;
                    }
                }
            }
        } catch (Exception e) {
            // unreadable config: fall through to the safe default
        }
        forceTextureModeFromFile = result;
        return result;
    }

    public static void save() {
        SPEC.save();
    }
}
