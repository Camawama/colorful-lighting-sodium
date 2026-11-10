package net.camacraft.colorfullighting.compat.dynamiclights;

import net.camacraft.colorfullighting.accessors.BlockStateWrapper;
import net.camacraft.colorfullighting.accessors.LevelWrapper;
import net.camacraft.colorfullighting.common.Config;
import net.camacraft.colorfullighting.common.util.ColorRGB4;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

/**
 * What each block round the camera lets through (2.8.1, reworked 2.9.0): a {@link #SIZE}-block cube in world space, its
 * origin on a 16-block grid 48 blocks before the camera, holding per block and per channel the share of light that passes
 * (air 255, an opaque full cube 0, a Colorful Lighting filter block its filter colour; three bytes a block and a spare). It is
 * the ground the {@link DynamicLightField} propagates the dynamic lights over, as the block engine propagates lamps over the
 * world's blocks, so a wall stops a held torch and stained glass tints it. The blocks of moving structures that are not in
 * the world's cells (Valkyrien Skies ships live in the shipyard) are added by an external {@link BlockFiller}, registered by
 * the mod that knows them. Rebuilt on a worker thread from the client level's chunk sections every {@link #REBUILD_TICKS}
 * ticks, at once after a block change in view ({@link #blockChanged}) and when the camera leaves the grid cell it was
 * built for. Nothing of it goes to the GPU since 2.9.0: the shaders sample the light field, not the blocks.
 */
public final class VoxelOcclusion {
    public static final int SIZE = 96;
    private static final int REBUILD_TICKS = 10;
    /** Four bytes a block: what it lets through, per channel (red, green, blue), and a spare. */
    private static final int BYTES = SIZE * SIZE * SIZE * 4;
    private static final int GRID = 16, BEFORE = 3;

    /**
     * Adds the blocks of structures the world's cells don't hold (a Valkyrien Skies ship's, in the shipyard) to a build of the
     * volume. Called on a worker thread with the volume's world origin and size; every block whose world position falls in the
     * cube goes to the writer, in the world position it is drawn at. Register with
     * {@link DynamicLightsCompat#setExternalBlockFiller}.
     *
     * @since 2.9.0
     */
    @FunctionalInterface
    public interface BlockFiller {
        void fill(ClientLevel level, int originX, int originY, int originZ, int size, BlockWriter out);
    }

    /** Where a {@link BlockFiller} puts a block: its world cell, its state and the position its state is read at (for filters' NBT). */
    public interface BlockWriter {
        void set(int worldX, int worldY, int worldZ, BlockState state, BlockPos statePos);
    }

    private static volatile boolean enabled;
    private static volatile @Nullable BlockFiller externalFiller;
    private static int originX = Integer.MIN_VALUE, originY, originZ;
    /** Far back but finite: the game time minus {@code Long.MIN_VALUE} overflows. */
    private static final long NEVER = -1_000_000L;
    private static long lastBuildTick = NEVER;
    private static volatile boolean dueNow;
    private static CompletableFuture<Void> building;
    private static ClientLevel builtFor;
    /** The latest finished build, for the field's propagation and the CPU samples. Written by the worker, read anywhere. */
    private static volatile byte @Nullable [] current;
    private static volatile int currentX = Integer.MIN_VALUE, currentY, currentZ;
    /** Counts finished builds, so the field knows the blocks changed under it. */
    private static volatile int buildId;

    /** A finished build with its origin and number, taken whole by a reader on another thread (2.10.3). */
    record Snapshot(byte[] data, int x, int y, int z, int id) {}

    private static volatile @Nullable Snapshot snapshot;
    /** The array a slab build is reading now: never the one built into next. */
    private static volatile byte @Nullable [] pinned;
    private static final byte[][] arrays = new byte[3][];

    private VoxelOcclusion() {}

    public static void setEnabled(boolean value) {
        enabled = value;
        if (!value) {
            current = null;
            snapshot = null;
            currentX = Integer.MIN_VALUE;
            originX = Integer.MIN_VALUE;
        }
    }

    /**
     * The latest build for a reader that takes a while (a slab's propagation), held against being built into until
     * {@link #unpin}; null when there is none. One reader at a time. Until 2.10.3 two arrays took turns, and a reader still on the
     * older one while blocks changed tick after tick read it half rewritten.
     */
    static @Nullable Snapshot pinLatest() {
        while (true) {
            Snapshot s = snapshot;
            if (s == null) {
                pinned = null;
                return null;
            }
            pinned = s.data;
            if (snapshot == s) return s;
        }
    }

    static void unpin() {
        pinned = null;
    }

    /** An array to build into: not the latest finished build, not the one a slab build reads. */
    private static byte[] takeArray() {
        byte[] latest = current, held = pinned;
        for (int i = 0; i < arrays.length; i++) {
            if (arrays[i] == null) return arrays[i] = new byte[BYTES];
            if (arrays[i] != latest && arrays[i] != held) return arrays[i];
        }
        return new byte[BYTES];
    }

    public static boolean enabled() {
        return enabled && current != null;
    }

    static void setExternalFiller(@Nullable BlockFiller filler) {
        externalFiller = filler;
    }

    /** A block in view changed (the client level told its renderer): the volume is rebuilt on the next tick. */
    public static void blockChanged(int x, int y, int z) {
        if (!enabled || currentX == Integer.MIN_VALUE) return;
        if (x < currentX || y < currentY || z < currentZ || x >= currentX + SIZE || y >= currentY + SIZE || z >= currentZ + SIZE) return;
        dueNow = true;
    }

    /**
     * The blocks of a moving structure in view are somewhere else (a ship with lamps turned or moved; 2.10.3): the volume is
     * rebuilt on the next tick instead of within ten, one build at a time.
     */
    public static void requestRebuild() {
        if (enabled && currentX != Integer.MIN_VALUE) dueNow = true;
    }

    private static final java.util.concurrent.atomic.AtomicLong builds = new java.util.concurrent.atomic.AtomicLong(),
            buildNanos = new java.util.concurrent.atomic.AtomicLong(), longestNanos = new java.util.concurrent.atomic.AtomicLong();

    /** For tests: how many builds of the volume there were and what they took. */
    public static String stats() {
        long n = builds.get();
        return String.format(java.util.Locale.ROOT, "%d builds, %.2f ms mean, %.2f ms longest", n, n == 0 ? 0.0 : buildNanos.get() / 1.0e6 / n, longestNanos.get() / 1.0e6);
    }

    public static void resetStats() {
        builds.set(0);
        buildNanos.set(0);
        longestNanos.set(0);
    }

    /** The latest build (per-channel transmittance, four bytes a cell, x fastest then y then z), or null; its origin by {@link #currentOrigin}. */
    static byte @Nullable [] current() {
        return current;
    }

    /** {x, y, z} world origin of {@link #current}. Read the array first: the origin is written after it. */
    static int[] currentOrigin() {
        return new int[]{currentX, currentY, currentZ};
    }

    static int buildId() {
        return buildId;
    }

    /** Once a client tick: start a rebuild when due. */
    public static void tick(ClientLevel level) {
        if (!enabled) return;
        if (builtFor != level) {
            builtFor = level;
            current = null;
            snapshot = null;
            currentX = Integer.MIN_VALUE;
            originX = Integer.MIN_VALUE;
            lastBuildTick = NEVER;
        }
        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        long tick = level.getGameTime();
        int wantX = (Math.floorDiv((int) Math.floor(cam.x), GRID) - BEFORE) * GRID;
        int wantY = (Math.floorDiv((int) Math.floor(cam.y), GRID) - BEFORE) * GRID;
        int wantZ = (Math.floorDiv((int) Math.floor(cam.z), GRID) - BEFORE) * GRID;
        boolean moved = wantX != originX || wantY != originY || wantZ != originZ;
        if (building != null && !building.isDone()) return;
        if (!moved && !dueNow && tick - lastBuildTick < REBUILD_TICKS) return;
        originX = wantX;
        originY = wantY;
        originZ = wantZ;
        lastBuildTick = tick;
        dueNow = false;
        final int ox = wantX, oy = wantY, oz = wantZ;
        final BlockFiller filler = externalFiller;
        building = CompletableFuture.runAsync(() -> build(level, ox, oy, oz, filler));
    }

    private static void build(ClientLevel level, int ox, int oy, int oz, @Nullable BlockFiller filler) {
        // three arrays in turn: the one being built is never the one last finished, nor the one a slab build is reading
        long started = System.nanoTime();
        byte[] data = takeArray();
        java.util.Arrays.fill(data, (byte) 0);
        LevelWrapper accessor = new LevelWrapper(level);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minY = level.getMinBuildHeight(), maxY = level.getMaxBuildHeight();
        int minSection = level.getMinSection();
        for (int cx = ox >> 4; cx <= (ox + SIZE - 1) >> 4; cx++) {
            for (int cz = oz >> 4; cz <= (oz + SIZE - 1) >> 4; cz++) {
                net.minecraft.world.level.chunk.ChunkAccess access = level.getChunk(cx, cz, ChunkStatus.FULL, false);
                if (!(access instanceof LevelChunk chunk)) continue; // an unloaded chunk: nothing known, light stops there
                LevelChunkSection[] sections = chunk.getSections();
                for (int sy = Math.max(oy, minY) >> 4; sy <= (Math.min(oy + SIZE, maxY) - 1) >> 4; sy++) {
                    int si = sy - minSection;
                    if (si < 0 || si >= sections.length) continue;
                    LevelChunkSection section = sections[si];
                    boolean air = section.hasOnlyAir();
                    for (int ly = 0; ly < 16; ly++) {
                        int wy = (sy << 4) + ly;
                        int y = wy - oy;
                        if (y < 0 || y >= SIZE) continue;
                        for (int lz = 0; lz < 16; lz++) {
                            int wz = (cz << 4) + lz;
                            int z = wz - oz;
                            if (z < 0 || z >= SIZE) continue;
                            for (int lx = 0; lx < 16; lx++) {
                                int wx = (cx << 4) + lx;
                                int x = wx - ox;
                                if (x < 0 || x >= SIZE) continue;
                                int i = ((z * SIZE + y) * SIZE + x) * 4;
                                if (air) {
                                    data[i] = data[i + 1] = data[i + 2] = (byte) 255;
                                    continue;
                                }
                                BlockState state = section.getBlockState(lx, ly, lz);
                                if (state.isAir()) {
                                    data[i] = data[i + 1] = data[i + 2] = (byte) 255;
                                    continue;
                                }
                                pos.set(wx, wy, wz);
                                write(data, i, level, accessor, pos, state);
                            }
                        }
                    }
                }
            }
        }
        if (filler != null) {
            try {
                filler.fill(level, ox, oy, oz, SIZE, (wx, wy, wz, state, statePos) -> {
                    int x = wx - ox, y = wy - oy, z = wz - oz;
                    if (x < 0 || y < 0 || z < 0 || x >= SIZE || y >= SIZE || z >= SIZE || state.isAir()) return;
                    int i = ((z * SIZE + y) * SIZE + x) * 4;
                    int r = data[i] & 0xFF, g = data[i + 1] & 0xFF, b = data[i + 2] & 0xFF;
                    write(data, i, level, accessor, statePos, state);
                    // a structure's block only takes away: the world's own cell keeps what is darker
                    data[i] = (byte) Math.min(r, data[i] & 0xFF);
                    data[i + 1] = (byte) Math.min(g, data[i + 1] & 0xFF);
                    data[i + 2] = (byte) Math.min(b, data[i + 2] & 0xFF);
                });
            } catch (RuntimeException e) {
                net.camacraft.colorfullighting.ColorfulLighting.LOGGER.warn("The external block filler failed; the volume holds the world's blocks only this time", e);
            }
        }
        current = data;
        currentX = ox;
        currentY = oy;
        currentZ = oz;
        buildId++;
        snapshot = new Snapshot(data, ox, oy, oz, buildId);
        long took = System.nanoTime() - started;
        builds.incrementAndGet();
        buildNanos.addAndGet(took);
        longestNanos.accumulateAndGet(took, Math::max);
    }

    /** What stops light: an opaque full cube; what tints it: Colorful Lighting's filters; everything else lets it through, as vanilla's light does mostly. */
    private static void write(byte[] data, int i, ClientLevel level, LevelWrapper accessor, BlockPos pos, BlockState state) {
        if (state.isSolidRender(level, pos)) {
            data[i] = data[i + 1] = data[i + 2] = 0;
            return;
        }
        ColorRGB4 through = Config.getColoredLightTransmittance(accessor, pos, new BlockStateWrapper(state));
        data[i] = (byte) (through.red4 * 17);
        data[i + 1] = (byte) (through.green4 * 17);
        data[i + 2] = (byte) (through.blue4 * 17);
    }

    public static void onLevelUnload() {
        current = null;
        snapshot = null;
        currentX = Integer.MIN_VALUE;
        originX = Integer.MIN_VALUE;
        lastBuildTick = NEVER;
        builtFor = null;
    }
}
