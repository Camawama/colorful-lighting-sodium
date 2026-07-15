package me.erykczy.colorfullighting.compat.oculus;

import me.erykczy.colorfullighting.ColorfulLighting;
import me.erykczy.colorfullighting.common.ColorfulLightingConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Euphoria-Patches-style auto patcher: scans the shaderpacks folder and writes a
 * "&lt;Pack&gt; + ColorfulLighting" copy of every recognizable pack, patched to decode the mod's
 * packed colored lightmap format. Users then simply select the patched pack in the Oculus GUI.
 */
public final class ShaderpackAutoPatcher {
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private ShaderpackAutoPatcher() {}

    /** Fire-and-forget scan+patch. Reports a one line summary through {@code feedback} when given. */
    public static void runAsync(Consumer<String> feedback) {
        if (!RUNNING.compareAndSet(false, true)) {
            if (feedback != null) feedback.accept("Shaderpack patcher is already running");
            return;
        }
        Thread thread = new Thread(() -> {
            try {
                Path dir = OculusCompat.getShaderpacksDirectory();
                if (!Files.isDirectory(dir)) return;
                List<ShaderpackPatchEngine.Result> results =
                        ShaderpackPatchEngine.patchAll(dir, ColorfulLighting.LOGGER::info);
                long patched = results.stream().filter(r -> !r.skipped()).count();
                long upToDate = results.stream().filter(r -> "up to date".equals(r.message())).count();
                if (patched > 0) {
                    OculusCompat.clearPatchedPackCache();
                }
                String summary = "Shaderpack auto-patch finished: " + patched + " patched, "
                        + upToDate + " already up to date, "
                        + (results.size() - patched - upToDate) + " skipped";
                ColorfulLighting.LOGGER.info(summary);
                if (feedback != null) feedback.accept(summary);
            } catch (Throwable t) {
                ColorfulLighting.LOGGER.error("Shaderpack auto-patch failed", t);
                if (feedback != null) feedback.accept("Shaderpack auto-patch failed: " + t);
            } finally {
                RUNNING.set(false);
            }
        }, "ColorfulLighting-ShaderpackPatcher");
        thread.setDaemon(true);
        thread.start();
    }

    /** Called once at load-complete when Oculus is present. */
    public static void runOnStartup() {
        if (!ColorfulLightingConfig.AUTO_PATCH_SHADERPACKS.get()) return;
        runAsync(null);
    }
}
