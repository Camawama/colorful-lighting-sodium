package me.erykczy.colorfullighting.common;

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

    public static final ForgeConfigSpec SPEC;

    static {
        ENABLED = BUILDER.comment("Enable colorful lighting").define("enabled", true);
        AUTO_PATCH_SHADERPACKS = BUILDER
                .comment("Automatically create '<Pack> + ColorfulLighting' copies of shaderpacks that decode colored lighting (requires Oculus)")
                .define("autoPatchShaderpacks", true);
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

    /** Safe before the config file is loaded (e.g. during early startup), where {@code get()} would throw. */
    public static LightUpdateSpeed lightUpdateSpeed() {
        if (!SPEC.isLoaded()) return LightUpdateSpeed.FAST;
        return LIGHT_UPDATE_SPEED.get();
    }

    public static void save() {
        SPEC.save();
    }
}
