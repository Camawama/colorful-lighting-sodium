package net.camacraft.colorfullighting.compat.distanthorizons;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.override.rendering.IDhApiShaderProgram;
import com.seibel.distanthorizons.api.methods.events.DhApiEventRegister;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiAfterDhInitEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import net.camacraft.colorfullighting.ColorfulLighting;
import net.camacraft.colorfullighting.common.ColoredLightEngine;
import net.camacraft.colorfullighting.common.ColoredLightSection;
import net.camacraft.colorfullighting.common.ColorfulLightingConfig;
import net.camacraft.colorfullighting.common.accessors.mixin.LevelAttachments;
import net.minecraft.client.Minecraft;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * The DH-touching half of the Distant Horizons compat. This class references the DH API in its
 * imports and bytecode, so it must ONLY ever be class-loaded through {@link DhCompat}, whose
 * every method checks {@link DhCompat#isLoaded()} first. Without that gate the JVM throws
 * NoClassDefFoundError on instances that don't ship DH.
 *
 * <p>See {@link DhCompat} for the feature overview.
 */
final class DhCompatImpl {
    private static final long SAVE_INTERVAL_MS = 30_000L;

    private static volatile boolean apiUsable;
    private static volatile boolean shaderContractOk = true;
    private static volatile boolean overrideBound;
    private static DhTerrainColorShaderProgram overrideProgram;
    private static DhColorVolume volume; // render thread only
    private static long lastSaveMs;

    private static final LinkedBlockingQueue<Runnable> WORKER_QUEUE = new LinkedBlockingQueue<>();
    private static Thread workerThread;

    private DhCompatImpl() {}

    /** Whether the shader override is currently bound into DH. Gates all per-frame and capture cost. */
    static boolean isOverrideEnabled() { return overrideBound; }

    /** Called once from mod loading-complete when DH is present. */
    static void init() {
        DhApiEventRegister.on(DhApiAfterDhInitEvent.class, new DhApiAfterDhInitEvent() {
            @Override
            public void afterDistantHorizonsInit(DhApiEventParam<Void> input) {
                onDhInitialized();
            }
        });
    }

    private static void onDhInitialized() {
        int major = DhApi.getApiMajorVersion();
        apiUsable = major >= DhCompat.SUPPORTED_API_MAJOR;
        ColorfulLighting.LOGGER.info("Distant Horizons detected (DhApi major version {}, DH {})",
                major, DhApi.getModVersion());
        if (!apiUsable) {
            ColorfulLighting.LOGGER.warn(
                    "Distant Horizons API {} is older than the supported version {}; colored LOD lighting stays off",
                    major, DhCompat.SUPPORTED_API_MAJOR);
            return;
        }
        if (major != DhCompat.SUPPORTED_API_MAJOR) {
            ColorfulLighting.LOGGER.warn(
                    "Distant Horizons API {} is newer than the tested version {}; colored LOD lighting may not work",
                    major, DhCompat.SUPPORTED_API_MAJOR);
        }
        checkShaderContract();
        if (ColorfulLightingConfig.dhLodColor()) {
            setOverrideEnabled(true);
        }
    }

    /** Set when DH ships the 3.2.0+ terrain shader (irisData attribute + block texture atlas). */
    private static volatile boolean texturedLodContract;

    static boolean isTexturedLodContract() { return texturedLodContract; }

    /**
     * Our override replicates the exact vertex contract of DH's terrain shader (buffer-local
     * uvec4 positions plus a uModelOffset uniform). Two known layouts carry it:
     * DH 3.1.2 at {@code shared/gl/standard.vert}, and DH 3.2.0 at {@code terrain/gl/vert.vert}
     * (same vertex bytes; the previously unused third attribute became {@code irisData} with a
     * block texture atlas on texture unit 1). A DH build with a different shader would misrender
     * through our program, so read DH's own shader off the classpath and compare the parts we
     * depend on. Mismatch logs loudly and blocks enabling instead of drawing garbage.
     */
    private static void checkShaderContract() {
        try {
            String vert320 = readDhResource("/assets/distanthorizons/shaders/terrain/gl/vert.vert");
            String vert312 = vert320 == null
                    ? readDhResource("/assets/distanthorizons/shaders/shared/gl/standard.vert") : null;
            String vert = vert320 != null ? vert320 : vert312;
            if (vert == null) {
                shaderContractOk = false;
                ColorfulLighting.LOGGER.warn(
                        "[DH] this Distant Horizons build has neither terrain/gl/vert.vert (3.2) nor shared/gl/standard.vert (3.1); its render pipeline differs from the supported DH versions and colored LOD lighting stays off");
                return;
            }
            boolean hasModelOffset = vert.contains("uModelOffset");
            boolean hasUvecPosition = vert.contains("uvec4 vPosition");
            texturedLodContract = vert320 != null && vert320.contains("irisData");
            shaderContractOk = hasModelOffset && hasUvecPosition;
            ColorfulLighting.LOGGER.info(
                    "[DH] terrain shader contract check: {} layout, {} bytes, uModelOffset={}, uvec4 vPosition={}, texturedLods={}",
                    vert320 != null ? "3.2 (terrain/gl)" : "3.1 (shared/gl)",
                    vert.length(), hasModelOffset, hasUvecPosition, texturedLodContract);
            if (!shaderContractOk) {
                ColorfulLighting.LOGGER.warn(
                        "[DH] this Distant Horizons version uses a different terrain shader contract than the supported ones (DH 3.1.2 / 3.2.0); colored LOD lighting stays off to avoid misrendering LODs");
            }
        } catch (Exception e) {
            shaderContractOk = false;
            ColorfulLighting.LOGGER.warn("[DH] failed to check DH's terrain shader contract", e);
        }
    }

    @Nullable
    private static String readDhResource(String path) throws java.io.IOException {
        try (java.io.InputStream in = DhApi.class.getResourceAsStream(path)) {
            if (in == null) return null;
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    // DH 3.2's textured LODs: whether DH itself would sample the block atlas this frame. Mirrors
    // the exact condition DH's own terrain program uses (the enableTexturedLods config entry);
    // when it is off, DH's meta renderer leaves texture unit 1 unbound, so our shader must not
    // sample it either. Internal-class reflection, cached; any failure disables tile texturing
    // for the session (LODs then render 3.1-style flat colors, which is safe).
    private static Object texturedLodsConfigEntry;
    private static java.lang.reflect.Method texturedLodsGet;
    private static boolean texturedLodsReflectFailed;

    static boolean texturedLodsEnabled() {
        if (!texturedLodContract || texturedLodsReflectFailed) return false;
        try {
            if (texturedLodsGet == null) {
                Class<?> holder = Class.forName(
                        "com.seibel.distanthorizons.core.config.Config$Client$Advanced$Graphics$Texture");
                texturedLodsConfigEntry = holder.getField("enableTexturedLods").get(null);
                texturedLodsGet = texturedLodsConfigEntry.getClass().getMethod("get");
            }
            return Boolean.TRUE.equals(texturedLodsGet.invoke(texturedLodsConfigEntry));
        } catch (Throwable t) {
            texturedLodsReflectFailed = true;
            ColorfulLighting.LOGGER.warn(
                    "[DH] could not read DH's enableTexturedLods config; colored LODs will render without block textures", t);
            return false;
        }
    }

    /**
     * Binds or unbinds the shader override at runtime. Safe to call from the client thread; DH reads
     * the override injector at the start of each render pass.
     *
     * @return a user-facing status message
     */
    static String setOverrideEnabled(boolean enable) {
        if (enable && !apiUsable) {
            return "Distant Horizons' API version is unsupported (needs DhApi " + DhCompat.SUPPORTED_API_MAJOR + "+, e.g. DH 3.1.2)";
        }
        if (enable && !shaderContractOk) {
            return "This Distant Horizons version's terrain shader differs from the supported one (DH 3.1.2); colored LOD lighting would misrender, staying off";
        }
        if (enable && overrideProgram != null && overrideProgram.hasFailed()) {
            // Re-binding would hand DH a program whose bind() no-ops, drawing no LODs at all.
            return "Colored LOD lighting hit an error and is off for this session (see the log)";
        }
        if (enable == overrideBound) {
            return enable ? "Colored LOD lighting already on" : "Colored LOD lighting already off";
        }
        try {
            if (enable) {
                if (overrideProgram == null) overrideProgram = new DhTerrainColorShaderProgram();
                DhApi.overrides.bind(IDhApiShaderProgram.class, overrideProgram);
                overrideBound = true;
                ColorfulLighting.LOGGER.info("[DH] bound colored LOD terrain shader override");
                return "Colored LOD lighting enabled";
            } else {
                DhApi.overrides.unbind(IDhApiShaderProgram.class, overrideProgram);
                overrideBound = false;
                ColorfulLighting.LOGGER.info("[DH] unbound colored LOD terrain shader override");
                return "Colored LOD lighting disabled";
            }
        } catch (Throwable t) {
            ColorfulLighting.LOGGER.error("[DH] failed to {} the shader override", enable ? "bind" : "unbind", t);
            return "Failed, see the log";
        }
    }

    // ================ capture ================

    /**
     * Client thread, from the engine's dirty-section drain. Snapshots happen on the worker; a section
     * that got unloaded in between is simply skipped (its previously remembered colour survives).
     */
    static void onSectionsDirty(ColoredLightEngine engine, long[] sectionPositions) {
        if (!overrideBound) return;
        Level level = engine.getLevel().getLevel();
        if (level == null) return;
        DhColorCache cache = getOrCreateCache(level);
        if (cache == null) return;
        // Client thread: filter down to fully-propagated inner-area sections while the view area
        // is still current. Unsafe sections keep whatever was remembered before.
        int kept = 0;
        long[] safe = new long[sectionPositions.length];
        for (long pos : sectionPositions) {
            if (engine.dhIsSectionCaptureSafe(pos)) safe[kept++] = pos;
        }
        if (kept == 0) return;
        final long[] positions = java.util.Arrays.copyOf(safe, kept);
        // Player section for pruning, captured on the client thread rather than kept as
        // shared mutable state.
        var mcPlayer = Minecraft.getInstance().player;
        final long playerSection = mcPlayer == null ? 0L : SectionPos.asLong(
                mcPlayer.getBlockX() >> 4, mcPlayer.getBlockY() >> 4, mcPlayer.getBlockZ() >> 4);
        WeakReference<ColoredLightEngine> engineRef = new WeakReference<>(engine);
        submit(() -> {
            ColoredLightEngine liveEngine = engineRef.get();
            if (liveEngine == null) return;
            for (long pos : positions) {
                ColoredLightSection light = liveEngine.getSection(true, pos);
                ColoredLightSection darkness = liveEngine.getSection(false, pos);
                if (light == null && darkness == null) continue; // left the view area; keep what we remembered
                // darkness matters even with zero net light: absorbers (end portals) must darken LODs
                cache.store(pos, DhColorCache.buildEntry(light, darkness));
            }
            cache.pruneIfNeeded(SectionPos.x(playerSection), SectionPos.z(playerSection));
        });
    }

    // ================ per-level cache ================

    /**
     * Render thread accessor: the cache lives on the level itself ({@link LevelAttachments}),
     * so this simply follows the client's current level. Null until the client tick creates it.
     */
    @Nullable
    static DhColorCache getActiveCache() {
        if (!overrideBound) return null;
        Level level = Minecraft.getInstance().level;
        if (level == null) return null;
        return ((LevelAttachments) level).colorfullighting$getDhColorCache();
    }

    /** Client thread only (clientTick and the engine's dirty-section drain). */
    @Nullable
    private static DhColorCache getOrCreateCache(Level level) {
        LevelAttachments attachments = (LevelAttachments) level;
        DhColorCache cache = attachments.colorfullighting$getDhColorCache();
        if (cache == null) {
            Path file = cacheFileFor(level);
            if (file == null) return null;
            DhColorCache created = new DhColorCache(file);
            attachments.colorfullighting$setDhColorCache(created);
            submit(created::load);
            return created;
        }
        return cache;
    }

    /**
     * One file per world (or server) and dimension. Keyed by save folder / server address, so the
     * remembered colour follows the world across sessions.
     */
    @Nullable
    private static Path cacheFileFor(Level level) {
        Minecraft mc = Minecraft.getInstance();
        String worldKey;
        if (mc.getSingleplayerServer() != null) {
            worldKey = "sp_" + sanitize(mc.getSingleplayerServer().getWorldData().getLevelName());
        } else if (mc.getCurrentServer() != null) {
            worldKey = "mp_" + sanitize(mc.getCurrentServer().ip);
        } else {
            return null;
        }
        String dimension = sanitize(level.dimension().location().toString());
        return mc.gameDirectory.toPath()
                .resolve("colorful_lighting").resolve("dh_color_cache")
                .resolve(worldKey).resolve(dimension + ".bin.gz");
    }

    private static String sanitize(String raw) {
        return raw.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    // ================ lifecycle ================

    /** Set from the render thread when the shader override breaks; unbound on the next client tick. */
    private static volatile boolean emergencyUnbindRequested;

    static void requestEmergencyUnbind() {
        emergencyUnbindRequested = true;
    }

    /** Client tick: keeps the active cache current and autosaves changed caches every 30s. */
    static void clientTick() {
        if (emergencyUnbindRequested) {
            emergencyUnbindRequested = false;
            setOverrideEnabled(false);
            ColorfulLighting.LOGGER.warn("[DH] shader override unbound after a failure; LODs are back to DH's own rendering");
        }

        // Follow the main engine's on/off switch. DH always uses a bound override (it never asks
        // overrideThisFrame on it), so '/cl off' must actually unbind or LODs would stay colored;
        // '/cl on' rebinds automatically when the config wants DH colors.
        boolean want = ColorfulLightingConfig.dhLodColor()
                && ColoredLightEngine.isEnabled() && apiUsable && shaderContractOk
                && (overrideProgram == null || !overrideProgram.hasFailed());
        if (want != overrideBound) {
            setOverrideEnabled(want);
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        DhColorCache cache = overrideBound
                ? getOrCreateCache(mc.level)
                : ((LevelAttachments) mc.level).colorfullighting$getDhColorCache();

        // Autosave the current level's cache; other levels' caches are saved on their unload.
        long now = System.currentTimeMillis();
        if (cache != null && cache.needsSave() && now - lastSaveMs > SAVE_INTERVAL_MS) {
            lastSaveMs = now;
            submit(cache::save);
        }
    }

    /** Level unload: persist what we learned. The cache object lives and dies with the Level. */
    static void onLevelUnload(Level level) {
        DhColorCache cache = ((LevelAttachments) level).colorfullighting$getDhColorCache();
        if (cache != null) submit(cache::save);
    }

    // ================ render-thread volume ================

    /** Render thread only (called from the shader override's fillUniformData). */
    static DhColorVolume getOrCreateVolume() {
        if (volume == null) volume = new DhColorVolume();
        return volume;
    }

    // ================ worker ================

    private static synchronized void submit(Runnable task) {
        if (workerThread == null) {
            workerThread = new Thread(DhCompatImpl::workerLoop, "CL-DhColorCache");
            workerThread.setDaemon(true);
            workerThread.setPriority(Thread.MIN_PRIORITY);
            workerThread.start();
        }
        WORKER_QUEUE.add(task);
    }

    private static void workerLoop() {
        while (true) {
            try {
                WORKER_QUEUE.take().run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                ColorfulLighting.LOGGER.error("[DH color cache] worker task failed", t);
            }
        }
    }

    // ================ command support ================

    static String describeStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("Colored LOD lighting: ").append(overrideBound ? "ON" : "OFF");
        try {
            sb.append(" (DH ").append(DhApi.getModVersion()).append(", DhApi ").append(DhApi.getApiMajorVersion()).append(")");
        } catch (Throwable ignored) {
        }
        if (!apiUsable) sb.append(" (DH API unsupported)");
        if (!shaderContractOk) sb.append(" (DH terrain shader contract mismatch)");
        DhColorCache cache = getActiveCache();
        if (cache != null) {
            sb.append("\nRemembered sections in this dimension: ").append(cache.getSectionCount());
            sb.append("\nCache file: ").append(cache.getFile().getFileName());
        }
        if (DhCompat.getDebugMode() != 0) sb.append("\nDebug view: on (red = beyond color memory range, blue = nothing remembered)");
        return sb.toString();
    }
}