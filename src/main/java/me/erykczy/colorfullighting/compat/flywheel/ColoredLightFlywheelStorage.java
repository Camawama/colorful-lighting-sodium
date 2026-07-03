package me.erykczy.colorfullighting.compat.flywheel;

import dev.engine_room.flywheel.backend.engine.CpuArena;
import dev.engine_room.flywheel.backend.engine.indirect.ResizableStorageArray;
import dev.engine_room.flywheel.backend.engine.indirect.StagingBuffer;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import org.lwjgl.opengl.GL46;
import org.lwjgl.system.MemoryUtil;

import java.util.BitSet;

public class ColoredLightFlywheelStorage {
    public static final int BLOCKS_PER_SECTION = 18 * 18 * 18;
    public static final int LIGHT_SIZE_BYTES = BLOCKS_PER_SECTION * 4;
    public static final int SECTION_SIZE_BYTES = LIGHT_SIZE_BYTES;
    private static final int DEFAULT_ARENA_CAPACITY_SECTIONS = 64;
    private static final int INVALID_SECTION = -1;

    public static final int COLORED_LIGHT_BUFFER_BINDING = 8;

    public final CpuArena arena;
    private final Long2IntMap section2ArenaIndex;
    private final BitSet changed = new BitSet();
    private final SlowLightCollector collector;

    private final ResizableStorageArray sectionsGPUBuffer = new ResizableStorageArray(SECTION_SIZE_BYTES);
    
    private boolean deleted = false;

    public ColoredLightFlywheelStorage() {
        this.arena = new CpuArena(SECTION_SIZE_BYTES, DEFAULT_ARENA_CAPACITY_SECTIONS);
        this.section2ArenaIndex = new Long2IntOpenHashMap();
        this.section2ArenaIndex.defaultReturnValue(INVALID_SECTION);
        this.collector = new SlowLightCollector();
    }

    public int capacity() {
        return arena.capacity();
    }

    public void delete() {
        if (deleted) return;
        arena.delete();
        sectionsGPUBuffer.delete();
        section2ArenaIndex.clear();
        changed.clear();
        deleted = true;
    }

    private int indexForSection(long section) {
        int out = section2ArenaIndex.get(section);

        // Need to allocate.
        if (out == INVALID_SECTION) {
            out = arena.alloc();
            section2ArenaIndex.put(section, out);
        }
        return out;
    }

    public void removeSection(long section) {
        if (deleted) return;
        int index = section2ArenaIndex.remove(section);
        if (index != INVALID_SECTION) {
            arena.free(index);
        }
    }

    public void collectSection(long section) {
        if (deleted) return;
        int index = indexForSection(section);

        changed.set(index);

        long ptr = arena.indexToPointer(index);

        // Zero it out first. This is basically free and makes it easier to handle missing sections later.
        MemoryUtil.memSet(ptr, 0, SECTION_SIZE_BYTES);

        collector.collectLightData(ptr, section);
    }

    public void recollectSectionIfTracked(long section) {
        if (deleted) return;
        if (!section2ArenaIndex.containsKey(section)) return;
        collectSection(section);
    }

    /**
     * Recollects every tracked section. Used when the engine is toggled: onLightUpdate (the
     * normal refresh path) is gated on the engine being enabled, so without this the GPU
     * buffers would keep the last collected colored light forever after a disable.
     */
    public void recollectAllTracked() {
        if (deleted) return;
        for (long section : section2ArenaIndex.keySet().toLongArray()) {
            collectSection(section);
        }
    }

    public void uploadChangedSections(StagingBuffer staging) {
        if (deleted) return;
        sectionsGPUBuffer.ensureCapacity(capacity());
        for (int i = changed.nextSetBit(0); i >= 0; i = changed.nextSetBit(i + 1)) {
            staging.enqueueCopy(arena.indexToPointer(i), SECTION_SIZE_BYTES, sectionsGPUBuffer.handle(), i * SECTION_SIZE_BYTES);
        }
        changed.clear();
    }

    /**
     * Upload path for the instancing backend, which has no StagingBuffer (that is an
     * indirect-backend construct). The target buffer is immutable storage allocated with
     * flags=0 (no GL_DYNAMIC_STORAGE_BIT), so glNamedBufferSubData into it is illegal and
     * silently no-ops, leaving uninitialized VRAM. Buffer-to-buffer copies are always
     * permitted though, so stage each section through a scratch buffer and copy.
     */
    public void uploadChangedSectionsDirect() {
        if (deleted) return;
        if (changed.isEmpty()) return;
        sectionsGPUBuffer.ensureCapacity(capacity());

        int scratch = GL46.glCreateBuffers();
        try {
            for (int i = changed.nextSetBit(0); i >= 0; i = changed.nextSetBit(i + 1)) {
                GL46.glNamedBufferData(scratch, MemoryUtil.memByteBuffer(arena.indexToPointer(i), SECTION_SIZE_BYTES), GL46.GL_STREAM_COPY);
                GL46.glCopyNamedBufferSubData(scratch, sectionsGPUBuffer.handle(), 0, (long) i * SECTION_SIZE_BYTES, SECTION_SIZE_BYTES);
            }
        } finally {
            GL46.glDeleteBuffers(scratch);
        }
        changed.clear();
    }

    public void bindBuffers() {
        if (deleted) return;
        GL46.glBindBufferRange(GL46.GL_SHADER_STORAGE_BUFFER, COLORED_LIGHT_BUFFER_BINDING, sectionsGPUBuffer.handle(), 0, sectionsGPUBuffer.byteCapacity());
    }
}
