package me.erykczy.colorfullighting.common;

import net.minecraftforge.common.ForgeConfigSpec;

public class ColorfulLightingConfig {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.BooleanValue ENABLED;
    public static final ForgeConfigSpec.BooleanValue AUTO_PATCH_SHADERPACKS;

    public static final ForgeConfigSpec SPEC;

    static {
        ENABLED = BUILDER.comment("Enable colorful lighting").define("enabled", true);
        AUTO_PATCH_SHADERPACKS = BUILDER
                .comment("Automatically create '<Pack> + ColorfulLighting' copies of shaderpacks that decode colored lighting (requires Oculus)")
                .define("autoPatchShaderpacks", true);
        SPEC = BUILDER.build();
    }

    public static void save() {
        SPEC.save();
    }
}
