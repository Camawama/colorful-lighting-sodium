package net.camacraft.colorfullighting.compat.flywheel;

import dev.engine_room.flywheel.backend.engine.CpuArena;
import dev.engine_room.flywheel.backend.engine.indirect.StagingBuffer;
import dev.engine_room.flywheel.backend.gl.GlTextureUnit;
import dev.engine_room.flywheel.backend.gl.TextureBuffer;
import dev.engine_room.flywheel.backend.gl.buffer.GlBuffer;
import dev.engine_room.flywheel.backend.gl.buffer.GlBufferType;
import dev.engine_room.flywheel.backend.gl.buffer.GlBufferUsage;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.camacraft.colorfullighting.ColorfulLighting;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.system.MemoryUtil;

import java.util.BitSet;

public class ColoredLightFlywheelStorage {
    public static final int BLOCKS_PER_SECTION = 18 * 18 * 18;
    public static final int LIGHT_SIZE_BYTES = BLOCKS_PER_SECTION * 4;
    public static final int SECTION_SIZE_BYTES = LIGHT_SIZE_BYTES;
    private static final int DEFAULT_ARENA_CAPACITY_SECTIONS = 64;
    private static final int INVALID_SECTION = -1;

    public static final int COLORED_LIGHT_BUFFER_BINDING = 8;
    /**
     * Texture unit for the buffer-texture fallback ({@link GlTextureUnit#T10} — keep in sync).
     * Flywheel's Samplers claim units 0-9 (DIFFUSE..NOISE), so 10 is the first free one; the
     * fragment-stage minimum of 16 combined units makes it safe everywhere flywheel runs.
     */
    public static final int COLORED_LIGHT_TEXTURE_UNIT = 10;
    /** Sampler uniform declared by colored_light.glsl below GLSL 430; bound by GlProgramMixin. */
    public static final String COLORED_LIGHT_SAMPLER_NAME = "_cl_coloredLightSections";

    /**
     * The level this storage collects light for — the level of the flywheel LightStorage that
     * owns it (one ColoredLightFlywheelStorage per LightStorage, see LightStorageMixin). With
     * mods like Immersive Portals or Ponder, several levels have live flywheel engines at once;
     * the level is what keeps each storage sampling its own dimension's ColoredLightEngine
     * instead of another level's colors. Null only for FlywheelCompat's placeholder storage,
     * which never tracks a section.
     */
    @Nullable
    private final LevelAccessor level;

    public final CpuArena arena;
    private final Long2IntMap section2ArenaIndex;
    private final BitSet changed = new BitSet();
    private final SlowLightCollector collector;

    /**
     * Sections whose colored light is to be collected at the next upload, and the subset never collected since their
     * slot was allocated (collected at that upload whatever happened this tick: the slot may still hold a freed
     * section's data). Flywheel asks for a section on its frame-plan worker (LightStorage#collectSection, for every
     * light update within a section of it) and this engine asks again from the render thread once its own
     * propagation lands a frame or two later; both used to sample the whole 18x18x18 box straight away, so walking
     * with a moving light by a contraption sampled each section near it twice a tick, part of it on the render
     * thread while the frame plan wrote this storage from the worker. Now a request only marks the section, the
     * upload (render thread) collects, and a section collected this tick waits for the next: at most once a tick, at
     * most a tick late. Everything that touches the arena, the map or these sets holds this storage's lock.
     */
    private final LongOpenHashSet pending = new LongOpenHashSet();
    private final LongOpenHashSet fresh = new LongOpenHashSet();
    /** When each section was last collected, in 50 ms steps of {@link System#nanoTime()} (a client tick). */
    private final Long2LongOpenHashMap collectedAt = new Long2LongOpenHashMap();
    private static final long TICK_NANOS = 50_000_000L;

    /**
     * GLSL 430+ path: a plain mutable buffer bound as an SSBO. Created lazily with GL ≤4.3 calls
     * (never flywheel's ResizableStorageArray, whose constructor needs GL 4.5 DSA). 0 in fallback
     * mode. GL work runs on the render thread; the section bookkeeping is also reached from Flywheel's frame-plan
     * worker (collectSection, removeSection), hence the lock.
     */
    private int ssboHandle;
    private long ssboByteCapacity;

    /**
     * Buffer-texture fallback (GLSL <430, FlywheelCompat.isTextureFallback): the same section
     * data as an R32I buffer texture on unit 10, read with texelFetch. Built exclusively from
     * flywheel's own GL primitives — GlBuffer binds through flywheel's GlStateTracker, whose
     * cached buffer bindings our earlier raw glBindBuffer calls silently desynced; on GL 4.1 the
     * instancing engine feeds instance data through buffer textures too, so a stale tracker
     * entry made flywheel upload into the wrong buffer and every instanced object vanished.
     * Created eagerly (zero-filled) in the constructor so unit 10 always holds a complete buffer
     * texture: macOS validates sampler bindings at draw time and drops draws over a missing or
     * unattached one. Null in SSBO mode.
     */
    private GlBuffer fallbackBuffer;
    private TextureBuffer fallbackTexture;
    /** How many sections fit under GL_MAX_TEXTURE_BUFFER_SIZE; sections past it stay vanilla-lit. */
    private int maxFallbackSections = Integer.MAX_VALUE;
    private boolean warnedTextureLimit;

    private boolean deleted = false;

    public ColoredLightFlywheelStorage(@Nullable LevelAccessor level) {
        this.level = level;
        this.arena = new CpuArena(SECTION_SIZE_BYTES, DEFAULT_ARENA_CAPACITY_SECTIONS);
        this.section2ArenaIndex = new Long2IntOpenHashMap();
        this.section2ArenaIndex.defaultReturnValue(INVALID_SECTION);
        this.collector = new SlowLightCollector(level);

        if (FlywheelCompat.isTextureFallback()) {
            maxFallbackSections = Math.max(1, TextureBuffer.MAX_TEXELS / BLOCKS_PER_SECTION);
            MemoryUtil.memSet(arena.indexToPointer(0), 0, (long) capacity() * SECTION_SIZE_BYTES);
            fallbackBuffer = new GlBuffer(GlBufferUsage.DYNAMIC_DRAW);
            fallbackBuffer.upload(arena.indexToPointer(0), (long) capacity() * SECTION_SIZE_BYTES);
            fallbackTexture = new TextureBuffer(GL30C.GL_R32I);
            // Bind once so unit 10 is never without a complete buffer texture at draw time — but
            // unlike the per-frame bind, restore the active unit: this constructor can run during
            // the loading screen (FlywheelCompat.init's recordRenderCall), where leaving unit 10
            // active made the overlay bind the Mojang logo to the wrong unit (black rectangle).
            // Restored through GlStateManager so its active-texture cache stays truthful.
            int previousActiveTexture = com.mojang.blaze3d.platform.GlStateManager._getActiveTexture();
            bindBuffers();
            com.mojang.blaze3d.platform.GlStateManager._activeTexture(previousActiveTexture);
        }
    }

    public int capacity() {
        return arena.capacity();
    }

    public synchronized void delete() {
        if (deleted) return;
        pending.clear();
        fresh.clear();
        collectedAt.clear();
        arena.delete();
        if (fallbackTexture != null) {
            fallbackTexture.delete();
            fallbackTexture = null;
        }
        if (fallbackBuffer != null) {
            fallbackBuffer.delete();
            fallbackBuffer = null;
        }
        if (ssboHandle != 0) {
            GL15C.glDeleteBuffers(ssboHandle);
            ssboHandle = 0;
            ssboByteCapacity = 0;
        }
        section2ArenaIndex.clear();
        changed.clear();
        deleted = true;
    }

    private int indexForSection(long section) {
	    if (level == null) return 0;
        int out = section2ArenaIndex.get(section);

        // Need to allocate.
        if (out == INVALID_SECTION) {
            out = arena.alloc();
            section2ArenaIndex.put(section, out);
            fresh.add(section);
        }
        return out;
    }

    public synchronized void removeSection(long section) {
	    if (level == null) return;
        if (deleted) return;
        pending.remove(section);
        fresh.remove(section);
        collectedAt.remove(section);
        int index = section2ArenaIndex.remove(section);
        if (index != INVALID_SECTION) {
            arena.free(index);
        }
    }

    /**
     * Flywheel collected {@code section} (LightStorage#collectSection, on its frame-plan worker): its slot is
     * allocated now, in the same order as Flywheel's own, which keeps the indices in step with Flywheel's LightLut;
     * its colored light is collected at the next upload.
     */
    public synchronized void collectSection(long section) {
	    if (level == null) return;
        if (deleted) return;
        indexForSection(section);
        pending.add(section);
    }

    /** This engine's colored light changed in {@code section} (render thread): collected at an upload if Flywheel tracks it. */
    public synchronized void recollectSectionIfTracked(long section) {
	    if (level == null) return;
        if (deleted) return;
        if (!section2ArenaIndex.containsKey(section)) return;
        pending.add(section);
    }

    /**
     * Collects the pending sections' colored light (render thread, at upload, holding the lock): every fresh one,
     * and every other one not collected yet this tick; the rest stay pending for the next tick's first upload.
     */
    private void collectPending() {
        if (pending.isEmpty()) return;
        long tick = System.nanoTime() / TICK_NANOS;
        for (LongIterator it = pending.iterator(); it.hasNext(); ) {
            long section = it.nextLong();
            int index = section2ArenaIndex.get(section);
            if (index == INVALID_SECTION) {
                it.remove();
                continue;
            }
            boolean isFresh = fresh.remove(section);
            if (!isFresh && collectedAt.containsKey(section) && collectedAt.get(section) == tick) continue;
            it.remove();
            collectedAt.put(section, tick);
            changed.set(index);
            long ptr = arena.indexToPointer(index);
            // Zero it out first. This is basically free and makes it easier to handle missing sections later.
            MemoryUtil.memSet(ptr, 0, SECTION_SIZE_BYTES);
            collector.collectLightData(ptr, section);
        }
    }

    /**
     * Recollects every tracked section. Used when the engine is toggled: onLightUpdate (the
     * normal refresh path) is gated on the engine being enabled, so without this the GPU
     * buffers would keep the last collected colored light forever after a disable.
     */
    public synchronized void recollectAllTracked() {
	    if (level == null) return;
        if (deleted) return;
        for (long section : section2ArenaIndex.keySet().toLongArray()) {
            // collected at the next upload whatever this tick held: the engine was just switched
            pending.add(section);
            fresh.add(section);
        }
    }

    /**
     * Sizes the SSBO to the arena's capacity, creating it on first use. Growing swaps in a fresh
     * data store, so every section is marked changed to be re-uploaded from the CPU arena.
     */
    private void ensureSsboCapacity() {
	    if (level == null) return;
        long needed = (long) capacity() * SECTION_SIZE_BYTES;
        if (ssboHandle != 0 && needed <= ssboByteCapacity) return;

        if (ssboHandle == 0) {
            ssboHandle = GL15C.glGenBuffers();
        }
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, ssboHandle);
        GL15C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, needed, GL15C.GL_DYNAMIC_DRAW);
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
        ssboByteCapacity = needed;
        changed.set(0, capacity());
    }

    public synchronized void uploadChangedSections(StagingBuffer staging) {
	    if (level == null) return;
        if (deleted) return;
        collectPending();
        ensureSsboCapacity();
        for (int i = changed.nextSetBit(0); i >= 0; i = changed.nextSetBit(i + 1)) {
            staging.enqueueCopy(arena.indexToPointer(i), SECTION_SIZE_BYTES, ssboHandle, (long) i * SECTION_SIZE_BYTES);
        }
        changed.clear();
    }

    /**
     * Upload path for the instancing backend, which has no StagingBuffer (that is an
     * indirect-backend construct). In fallback mode this goes through flywheel's GlBuffer so its
     * GlStateTracker always sees our binds; growth is one full re-upload from the CPU arena,
     * which always holds all the data.
     */
    public synchronized void uploadChangedSectionsDirect() {
	    if (level == null) return;
        if (deleted) return;
        collectPending();
        if (changed.isEmpty()) return;

        if (fallbackBuffer != null) {
            int sections = capacity();
            if (sections > maxFallbackSections) {
                if (!warnedTextureLimit) {
                    warnedTextureLimit = true;
                    ColorfulLighting.LOGGER.warn("Flywheel colored light needs {} sections but GL_MAX_TEXTURE_BUFFER_SIZE only fits {}; some flywheel-rendered objects will fall back to vanilla light", sections, maxFallbackSections);
                }
                sections = maxFallbackSections;
            }
            long needed = (long) sections * SECTION_SIZE_BYTES;
            if (fallbackBuffer.size() < needed) {
                fallbackBuffer.upload(arena.indexToPointer(0), needed);
            } else {
                for (int i = changed.nextSetBit(0); i >= 0 && i < sections; i = changed.nextSetBit(i + 1)) {
                    fallbackBuffer.uploadSpan((long) i * SECTION_SIZE_BYTES, arena.indexToPointer(i), SECTION_SIZE_BYTES);
                }
            }
            changed.clear();
            return;
        }

        // SSBO mode: our own mutable buffer, plain glBufferSubData
        ensureSsboCapacity();
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, ssboHandle);
        for (int i = changed.nextSetBit(0); i >= 0; i = changed.nextSetBit(i + 1)) {
            GL15C.glBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, (long) i * SECTION_SIZE_BYTES,
                    MemoryUtil.memByteBuffer(arena.indexToPointer(i), SECTION_SIZE_BYTES));
        }
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
        changed.clear();
    }

    public synchronized void bindBuffers() {
	    if (deleted) return;
        if (fallbackTexture != null) {
            // mirrors InstancedLight.bind: tracked active-texture switch, re-attach every bind
            GlTextureUnit.T10.makeActive();
            fallbackTexture.bind(fallbackBuffer.handle());
            return;
        }
        if (ssboHandle == 0) return;
        GL30C.glBindBufferRange(GL43C.GL_SHADER_STORAGE_BUFFER, COLORED_LIGHT_BUFFER_BINDING, ssboHandle, 0, ssboByteCapacity);
    }

    /**
     * '/cl flywheel debug': checks every stage of the colored-light data chain and reports where
     * the data stops. Render thread only (client command). CPU-nonzero counts the packed ints the
     * collector produced; GPU-nonzero reads the same range back from the buffer — a mismatch means
     * the upload is broken, matching zeros mean the loss is at bind/uniform/shader level.
     */
    public synchronized String debugReport() {
        if (deleted) return "storage is deleted";
        int tracked = section2ArenaIndex.size();
        if (fallbackBuffer == null) {
            return "[" + describeLevel() + "] SSBO mode; tracked sections: " + tracked + ", buffer " + ssboByteCapacity + " bytes (handle " + ssboHandle + ")";
        }

        long bytes = Math.min(fallbackBuffer.size(), (long) Math.min(capacity(), maxFallbackSections) * SECTION_SIZE_BYTES);
        int ints = (int) (bytes / 4);

        long cpuNonZero = 0;
        long base = arena.indexToPointer(0);
        for (int i = 0; i < ints; i++) {
            if (MemoryUtil.memGetInt(base + (long) i * 4) != 0) cpuNonZero++;
        }

        int[] gpuData = new int[ints];
        GlBufferType.COPY_READ_BUFFER.bind(fallbackBuffer.handle());
        GL15C.glGetBufferSubData(GlBufferType.COPY_READ_BUFFER.glEnum, 0, gpuData);
        long gpuNonZero = 0;
        for (int value : gpuData) {
            if (value != 0) gpuNonZero++;
        }

        return "[" + describeLevel() + "] texture mode; tracked sections: " + tracked
                + ", buffer " + fallbackBuffer.size() + " bytes (buffer handle " + fallbackBuffer.handle()
                + ", texture handle " + fallbackTexture.handle() + ", unit " + COLORED_LIGHT_TEXTURE_UNIT + ")"
                + ", CPU nonzero ints: " + cpuNonZero
                + ", GPU nonzero ints: " + gpuNonZero;
    }

    /**
     * Identifies which level's storage a log or report line is about. A dimension id when the
     * level is a real Level; fake levels fall back to their class name — that they show up as a
     * distinct storage at all is the point.
     */
    public String describeLevel() {
        if (level == null) return "placeholder";
        if (level instanceof Level realLevel) return realLevel.dimension().location().toString();
        return level.getClass().getSimpleName();
    }
	
	public boolean isDeleted() {
		return deleted;
	}
}
