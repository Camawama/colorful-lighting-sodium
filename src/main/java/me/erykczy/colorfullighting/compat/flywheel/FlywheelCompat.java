package me.erykczy.colorfullighting.compat.flywheel;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.glsl.GlslVersion;
import me.erykczy.colorfullighting.ColorfulLighting;

public class FlywheelCompat {
    private static FlywheelCompat instance;
    /**
     * True when flywheel shaders compile below GLSL 430 and the colored light section data must
     * therefore travel as a buffer texture instead of an SSBO. Decided once on the render thread
     * before any flywheel program can exist; read by ColoredLightFlywheelStorage and GlProgramMixin.
     */
    private static boolean textureFallback;

    public ColoredLightFlywheelStorage flywheelColoredLightStorage;

    public static void init() {
        // Probe the Flywheel 1.0 API before ColoredLightFlywheelStorage (which references it in
        // field and method signatures) is ever loaded, so an unsupported Flywheel degrades to
        // plain vanilla-lit flywheel rendering instead of a NoClassDefFoundError. Mirrors the
        // gate ColorfulLightingMixinPlugin applies to the flywheel mixins.
        if (!hasClass("dev.engine_room.flywheel.backend.engine.LightStorage")
                || !hasClass("dev.engine_room.flywheel.backend.engine.CpuArena")
                || !hasClass("dev.engine_room.flywheel.backend.engine.indirect.StagingBuffer")
                || !hasClass("dev.engine_room.flywheel.backend.gl.GlCompat")) {
            ColorfulLighting.LOGGER.warn("Flywheel is installed but not a supported version; colored light on flywheel-rendered objects is disabled");
            return;
        }
        RenderSystem.recordRenderCall(() -> {
            // Flywheel stamps "#version MAX_GLSL_VERSION" into every shader it compiles, and
            // colored_light.glsl switches on __VERSION__ >= 430 between the SSBO and the
            // buffer-texture fallback — so deciding from the same value keeps the Java side and
            // the shaders in lockstep. Below GLSL 430 only the instancing backend can run
            // (indirect needs GL 4.6), and buffer textures are core since GL 3.1, below
            // flywheel's own minimum.
            textureFallback = GlCompat.MAX_GLSL_VERSION.compareTo(GlslVersion.V430) < 0;
            // logged unconditionally: any log file must answer "which transport actually ran"
            ColorfulLighting.LOGGER.info("Flywheel colored light mode: {} (flywheel GLSL {})",
                    textureFallback ? "buffer texture" : "SSBO", GlCompat.MAX_GLSL_VERSION);
            instance = new FlywheelCompat();
        });
    }

    private static boolean hasClass(String className) {
        try {
            Class.forName(className, false, FlywheelCompat.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static FlywheelCompat getInstance() {
        return instance;
    }

    public static boolean isAvailable() {
        return instance != null;
    }

    public static boolean isTextureFallback() {
        return textureFallback;
    }

    /** Human-readable state for the '/cl flywheel' command. Safe to call with flywheel absent. */
    public static String describeMode() {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("flywheel")) {
            return "Flywheel is not installed";
        }
        if (instance == null) {
            return "Flywheel colored light is inactive (unsupported flywheel version, or still initializing)";
        }
        // safe: instance != null implies the flywheel classes exist
        String glsl = String.valueOf(GlCompat.MAX_GLSL_VERSION);
        return textureFallback
                ? "Flywheel colored light mode: buffer texture (flywheel GLSL " + glsl + ")"
                : "Flywheel colored light mode: SSBO (flywheel GLSL " + glsl + ")";
    }

    public FlywheelCompat() {
        flywheelColoredLightStorage = new ColoredLightFlywheelStorage();
    }
}
