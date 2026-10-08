package net.camacraft.colorfullighting.compat.flywheel;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import net.camacraft.colorfullighting.ColorfulLighting;
import net.camacraft.colorfullighting.common.ColoredLightEngine;
import net.camacraft.colorfullighting.common.accessors.LevelAccessor;
import net.camacraft.colorfullighting.common.accessors.mixin.LevelAttachments;

public class FlywheelCompat {
	private static boolean isAvailable = false;
	
    /**
     * True when flywheel shaders compile below GLSL 430 and the colored light section data must
     * therefore travel as a buffer texture instead of an SSBO. Decided once on the render thread
     * before any flywheel program can exist; read by ColoredLightFlywheelStorage and GlProgramMixin.
     */
    private static boolean textureFallback;

    /**
     * Every live level-backed storage, one per flywheel LightStorage (see LightStorageMixin);
     * self-registered in the storage's constructor, removed on delete(). Normally one entry, but
     * Immersive Portals' other-dimension levels and Ponder scenes each add their own. Used by the
     * engine-wide refresh paths (toggle, dirty sections) and '/cl flywheel report'. Render thread
     * only, like everything else in this compat.
     */
    private static ColoredLightFlywheelStorage placeholder;
    private ColoredLightFlywheelStorage storage;

    public static void init() {
        // Probe the Flywheel 1.0 API before ColoredLightFlywheelStorage (which references it in
        // field and method signatures) is ever loaded, so an unsupported Flywheel degrades to
        // plain vanilla-lit flywheel rendering instead of a NoClassDefFoundError. Mirrors the
        // gate ColorfulLightingMixinPlugin applies to the flywheel mixins.
        if (!hasClass("dev.engine_room.flywheel.backend.engine.LightStorage")
                || !hasClass("dev.engine_room.flywheel.backend.engine.CpuArena")
                || !hasClass("dev.engine_room.flywheel.backend.engine.indirect.StagingBuffer")
                || !hasClass("dev.engine_room.flywheel.backend.gl.GlCompat")
                // the per-level storages take their level from LightStorage#level()
                || !hasMethod("dev.engine_room.flywheel.backend.engine.LightStorage", "level")) {
            ColorfulLighting.LOGGER.warn("Flywheel is installed but not a supported version; colored light on flywheel-rendered objects is disabled");
            return;
        }
        // no shader copies for this Flywheel's internal shaders (InternalPackRegistration logged why): nothing would
        // read the colored sections, so none are kept
        if (!net.camacraft.colorfullighting.resourcemanager.InternalPackRegistration.flywheelShadersActive()) return;
        RenderSystem.recordRenderCall(() -> {
	        isAvailable = net.minecraftforge.fml.ModList.get().isLoaded("flywheel");
			
			if (isAvailable) {
				// Flywheel stamps "#version MAX_GLSL_VERSION" into every shader it compiles, and
				// colored_light.glsl switches on __VERSION__ >= 430 between the SSBO and the
				// buffer-texture fallback — so deciding from the same value keeps the Java side and
				// the shaders in lockstep. Below GLSL 430 only the instancing backend can run
				// (indirect needs GL 4.6), and buffer textures are core since GL 3.1, below
				// flywheel's own minimum.
				textureFallback = FlwIndirect.checkVersion();
				// logged unconditionally: any log file must answer "which transport actually ran"
				ColorfulLighting.LOGGER.info("Flywheel colored light mode: {} (flywheel GLSL {})",
						textureFallback ? "buffer texture" : "SSBO", GlCompat.MAX_GLSL_VERSION);
				
				placeholder = new ColoredLightFlywheelStorage(null);
			} else {
				textureFallback = true;
			}
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

    private static boolean hasMethod(String className, String methodName) {
        try {
            Class.forName(className, false, FlywheelCompat.class.getClassLoader()).getMethod(methodName);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isAvailable() {
        return isAvailable;
    }

    public static boolean isTextureFallback() {
        return textureFallback;
    }

    /** '/cl flywheel report': one line per live storage so per-level state is visible. */
    public static String debugReportAll() {
	    StringBuilder report = new StringBuilder();
		ColoredLightEngine.forEach((engine)->{
			LevelAccessor accessor = engine.getLevel();
			FlywheelCompat compat = ((LevelAttachments) accessor).colorfullighting$getFlywheelCompat();
			if (compat != null) {
				ColoredLightFlywheelStorage storage = compat.storage;
				
				if (!report.isEmpty()) report.append('\n');
				report.append(storage.debugReport());
			}
		});
        return report.toString();
    }

    /** Human-readable state for the '/cl flywheel' command. Safe to call with flywheel absent. */
    public static String describeMode() {
        if (!isAvailable()) {
            return "Flywheel is not installed";
        }
        // safe: instance != null implies the flywheel classes exist
        String glsl = String.valueOf(GlCompat.MAX_GLSL_VERSION);
        return textureFallback
                ? "Flywheel colored light mode: buffer texture (flywheel GLSL " + glsl + ")"
                : "Flywheel colored light mode: SSBO (flywheel GLSL " + glsl + ")";
    }

    public FlywheelCompat() {
        storage = null;
    }
	
	public ColoredLightFlywheelStorage getStorage() {
		return storage == null ? placeholder : storage;
	}
	
	public void setStorage(ColoredLightFlywheelStorage strg) {
		if (storage != null && !storage.isDeleted())
			throw new RuntimeException("Replacing non-deleted light storage.");
		storage = strg;
	}

    public void onLightUpdate(int sectionX, int sectionY, int sectionZ, long dirtySection, net.minecraft.world.level.Level level) {
        getStorage().recollectSectionIfTracked(dirtySection);
        if (isAvailable && level != null) {
            var manager = dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl.get(level);
            if (manager != null) {
                manager.onLightUpdate(net.minecraft.core.SectionPos.of(sectionX, sectionY, sectionZ), net.minecraft.world.level.LightLayer.BLOCK);
            }
        }
    }
}
