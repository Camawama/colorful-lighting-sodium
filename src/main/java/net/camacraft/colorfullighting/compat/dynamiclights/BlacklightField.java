package net.camacraft.colorfullighting.compat.dynamiclights;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.camacraft.colorfullighting.ColorfulLighting;
import net.camacraft.colorfullighting.accessors.BlockStateWrapper;
import net.camacraft.colorfullighting.common.ColorfulLightingConfig;
import net.camacraft.colorfullighting.common.Config;
import net.camacraft.colorfullighting.common.config.VariantList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Where the blacklight of blocks reaches (2.11.0): a window of {@link #SIZE} cells a side round the camera holding, per cell,
 * how much ultraviolet the blacklight blocks of {@code light/emitters.json} ({@code "uv;15"}) send there. The coloured light
 * engine carries what is seen of such a block's light (a dim violet); what it makes glow needs to know that the light is
 * ultraviolet, which a colour in the light data cannot say, so the terrain's fragments read this field instead.
 *
 * <p>Built on a worker like the {@link VoxelOcclusion} volume: the sources are found per chunk section (a palette test first,
 * so a section without one costs a few comparisons), their light spreads a level a block through everything that is not a
 * full solid block, as block light does, and the result goes into one 3D texture of two bytes a cell: the level (0 at the
 * last block of the reach, 255 at a source of 15), and whether the cell is lit at all. The shader divides one by the other, so a face is lit by the open cells in front of it alone:
 * the walls beside it neither darken its edges nor leak light from their far side.
 *
 * <p>Nothing is built, bound or uploaded while no block of the pack is a blacklight or blacklight is off. A build with no
 * source in the window uploads nothing. Blacklight blocks on a Valkyrien Skies ship are not in the field.
 */
public final class BlacklightField {
    public static final int SIZE = 128;
    public static final int TEXTURE_UNIT = DynamicLightField.FIRST_TEXTURE_UNIT + 3;
    private static final int GRID = 16, BEFORE = 4, SECTIONS = SIZE / 16;
    /** A rebuild at least every so many ticks (chunks arrive), and at most every so many after a block change. */
    private static final int REBUILD_TICKS = 40, MIN_TICKS = 4;
    private static final long NEVER = Long.MIN_VALUE / 2;
    private static final int NONE = Integer.MIN_VALUE;

    /** A finished build: two bytes a cell (x fastest, then y, then z), or null data when the window holds no source. */
    private record Built(byte @org.jetbrains.annotations.Nullable [] data, int x, int y, int z, int sources) {}

    // the client thread
    private static ClientLevel builtFor;
    private static int originX = NONE, originY, originZ;
    private static long lastBuildTick = NEVER;
    private static CompletableFuture<Void> building;
    private static volatile boolean dueNow;
    private static volatile boolean wanted;
    // the window the last build was asked for, for blockChanged
    private static volatile int windowX = NONE, windowY, windowZ;

    // the worker: what it last handed over, to tell a build that changed nothing
    private static byte[] lastData;
    private static int lastX = NONE, lastY, lastZ;

    private static final AtomicReference<Built> finished = new AtomicReference<>();

    // the render thread
    private static int textureId;
    private static ByteBuffer upload;
    private static volatile boolean shown;
    private static volatile int shownX, shownY, shownZ, shownSources;
    private static volatile long builds, uploads;
    private static volatile double lastBuildMs;

    private BlacklightField() {}

    /** A block in the window changed: the field is rebuilt within a few ticks. */
    public static void blockChanged(int x, int y, int z) {
        if (!wanted || windowX == NONE) return;
        if (x < windowX || y < windowY || z < windowZ || x >= windowX + SIZE || y >= windowY + SIZE || z >= windowZ + SIZE) return;
        dueNow = true;
    }

    /** Once a client tick: start a rebuild when due. */
    public static void tick(ClientLevel level) {
        boolean want = level != null && ColorfulLightingConfig.blacklight() && !Config.uvBlocks().isEmpty();
        if (!want) {
            if (wanted) {
                wanted = false;
                shown = false;
                finished.set(null);
                windowX = NONE;
                originX = NONE;
            }
            return;
        }
        wanted = true;
        if (builtFor != level) {
            builtFor = level;
            shown = false;
            finished.set(null);
            originX = NONE;
            windowX = NONE;
            lastBuildTick = NEVER;
        }
        if (building != null && !building.isDone()) return;
        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        long tick = level.getGameTime();
        int wantX = (Math.floorDiv((int) Math.floor(cam.x), GRID) - BEFORE) * GRID;
        int wantY = (Math.floorDiv((int) Math.floor(cam.y), GRID) - BEFORE) * GRID;
        int wantZ = (Math.floorDiv((int) Math.floor(cam.z), GRID) - BEFORE) * GRID;
        boolean moved = wantX != originX || wantY != originY || wantZ != originZ;
        long since = tick - lastBuildTick;
        if (!moved && !(dueNow && since >= MIN_TICKS) && since >= 0 && since < REBUILD_TICKS) return;
        originX = wantX;
        originY = wantY;
        originZ = wantZ;
        windowX = wantX;
        windowY = wantY;
        windowZ = wantZ;
        lastBuildTick = tick;
        dueNow = false;
        final Set<Block> uv = Config.uvBlocks();
        building = CompletableFuture.runAsync(() -> {
            try {
                build(level, wantX, wantY, wantZ, uv);
            } catch (RuntimeException e) {
                ColorfulLighting.LOGGER.warn("Blacklight: the field's build failed; it stays as it was", e);
            }
        });
    }

    private static int levelOf(BlockState state, Map<BlockState, Integer> cache) {
        Integer known = cache.get(state);
        if (known != null) return known;
        int level = 0;
        VariantList<Config.ColorEmitter> config = Config.getBlockEmitterConfig(state.getBlock());
        if (config != null) {
            Config.ColorEmitter emitter = config.resolve(new BlockStateWrapper(state), null);
            if (emitter != null && emitter.isUv()) {
                level = emitter.overriddenBrightness4() >= 0 ? emitter.overriddenBrightness4() : state.getLightEmission();
                level = Math.max(0, Math.min(15, level));
            }
        }
        cache.put(state, level);
        return level;
    }

    private static void build(ClientLevel level, int ox, int oy, int oz, Set<Block> uv) {
        long started = System.nanoTime();
        // the window's sections: null where the chunk is not loaded (light stops there), EMPTY where there is nothing but air
        LevelChunkSection[] sections = new LevelChunkSection[SECTIONS * SECTIONS * SECTIONS];
        boolean[] loaded = new boolean[SECTIONS * SECTIONS];
        int minSection = level.getMinSection();
        int cx0 = ox >> 4, cy0 = oy >> 4, cz0 = oz >> 4;
        Map<BlockState, Integer> levels = new IdentityHashMap<>();
        IntArrayList sourceCells = new IntArrayList();
        IntArrayList sourceLevels = new IntArrayList();
        for (int sx = 0; sx < SECTIONS; sx++) {
            for (int sz = 0; sz < SECTIONS; sz++) {
                net.minecraft.world.level.chunk.ChunkAccess access = level.getChunk(cx0 + sx, cz0 + sz, ChunkStatus.FULL, false);
                if (!(access instanceof LevelChunk chunk)) continue;
                loaded[sz * SECTIONS + sx] = true;
                LevelChunkSection[] column = chunk.getSections();
                for (int sy = 0; sy < SECTIONS; sy++) {
                    int si = cy0 + sy - minSection;
                    if (si < 0 || si >= column.length) continue;
                    LevelChunkSection section = column[si];
                    if (section == null || section.hasOnlyAir()) continue;
                    sections[(sz * SECTIONS + sy) * SECTIONS + sx] = section;
                    if (!section.maybeHas(state -> uv.contains(state.getBlock()))) continue;
                    for (int ly = 0; ly < 16; ly++) {
                        for (int lz = 0; lz < 16; lz++) {
                            for (int lx = 0; lx < 16; lx++) {
                                BlockState state = section.getBlockState(lx, ly, lz);
                                if (!uv.contains(state.getBlock())) continue;
                                int l = levelOf(state, levels);
                                if (l <= 0) continue;
                                sourceCells.add((((sz << 4) + lz) * SIZE + (sy << 4) + ly) * SIZE + (sx << 4) + lx);
                                sourceLevels.add(l);
                            }
                        }
                    }
                }
            }
        }
        builds++;
        if (sourceCells.isEmpty()) {
            lastData = null;
            lastX = NONE;
            finished.set(new Built(null, ox, oy, oz, 0));
            lastBuildMs = (System.nanoTime() - started) / 1.0e6;
            return;
        }
        byte[] data = new byte[SIZE * SIZE * SIZE * 2];
        byte[] lit = new byte[SIZE * SIZE * SIZE];
        IntArrayList[] buckets = new IntArrayList[16];
        for (int l = 1; l <= 15; l++) buckets[l] = new IntArrayList();
        for (int i = 0; i < sourceCells.size(); i++) {
            int cell = sourceCells.getInt(i), l = sourceLevels.getInt(i);
            if (lit[cell] >= l) continue;
            lit[cell] = (byte) l;
            buckets[l].add(cell);
        }
        final int stepY = SIZE, stepZ = SIZE * SIZE;
        for (int l = 15; l >= 2; l--) {
            IntArrayList bucket = buckets[l];
            byte next = (byte) (l - 1);
            for (int i = 0; i < bucket.size(); i++) {
                int cell = bucket.getInt(i);
                if (lit[cell] != l) continue;
                int x = cell % SIZE, y = (cell / SIZE) % SIZE, z = cell / stepZ;
                if (x > 0) spread(cell - 1, x - 1, y, z, next, lit, sections, loaded, buckets);
                if (x < SIZE - 1) spread(cell + 1, x + 1, y, z, next, lit, sections, loaded, buckets);
                if (y > 0) spread(cell - stepY, x, y - 1, z, next, lit, sections, loaded, buckets);
                if (y < SIZE - 1) spread(cell + stepY, x, y + 1, z, next, lit, sections, loaded, buckets);
                if (z > 0) spread(cell - stepZ, x, y, z - 1, next, lit, sections, loaded, buckets);
                if (z < SIZE - 1) spread(cell + stepZ, x, y, z + 1, next, lit, sections, loaded, buckets);
            }
        }
        for (int cell = 0; cell < lit.length; cell++) {
            int l = lit[cell];
            if (l == 0) continue;
            // 0 at the last block of the reach, so the light ends without a step
            data[cell * 2] = (byte) Math.round((l - 1) * (255f / 14f));
            data[cell * 2 + 1] = (byte) 255;
        }
        lastBuildMs = (System.nanoTime() - started) / 1.0e6;
        if (lastData != null && lastX == ox && lastY == oy && lastZ == oz && Arrays.equals(lastData, data)) return;
        lastData = data;
        lastX = ox;
        lastY = oy;
        lastZ = oz;
        finished.set(new Built(data, ox, oy, oz, sourceCells.size()));
    }

    private static void spread(int cell, int x, int y, int z, byte level, byte[] lit, LevelChunkSection[] sections, boolean[] loaded, IntArrayList[] buckets) {
        if (lit[cell] >= level) return;
        int sx = x >> 4, sy = y >> 4, sz = z >> 4;
        if (!loaded[sz * SECTIONS + sx]) return;
        LevelChunkSection section = sections[(sz * SECTIONS + sy) * SECTIONS + sx];
        if (section != null) {
            BlockState state = section.getBlockState(x & 15, y & 15, z & 15);
            if (!state.isAir()) {
                if (state.canOcclude() && state.isSolidRender(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) return;
                // what lets no light through lets no ultraviolet through either (2.11.1: tinted glass is no solid block to the
                // renderer, and a blacklight boxed in with it lit the room)
                if (state.getLightBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) >= 15) return;
            }
        }
        lit[cell] = level;
        buckets[level].add(cell);
    }

    /**
     * Before a pass that samples the field: takes a finished build (uploading it) and binds the texture on its unit. True when
     * the field holds blacklight; its corner is {@link #origin}. Render thread.
     */
    public static boolean bind() {
        if (!wanted || !RenderSystem.isOnRenderThread()) return false;
        Built b = finished.getAndSet(null);
        if (b == null && !shown) return false;
        int previousUnit = GlStateManager._getActiveTexture();
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + TEXTURE_UNIT);
        if (b != null) {
            if (b.data == null) {
                shown = false;
            } else {
                if (textureId <= 0) create();
                else GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, textureId);
                unpackState();
                upload.clear();
                upload.put(b.data).flip();
                GL12C.glTexSubImage3D(GL12C.GL_TEXTURE_3D, 0, 0, 0, 0, SIZE, SIZE, SIZE, GL30C.GL_RG, GL11C.GL_UNSIGNED_BYTE, upload);
                shownX = b.x;
                shownY = b.y;
                shownZ = b.z;
                shownSources = b.sources;
                shown = true;
                uploads++;
            }
        }
        if (shown) GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, textureId);
        GlStateManager._activeTexture(previousUnit);
        return shown;
    }

    private static void create() {
        textureId = GL11C.glGenTextures();
        upload = MemoryUtil.memAlloc(SIZE * SIZE * SIZE * 2);
        GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, textureId);
        GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
        GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
        GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
        GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
        GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL12C.GL_TEXTURE_WRAP_R, GL12C.GL_CLAMP_TO_EDGE);
        unpackState();
        GL12C.glTexImage3D(GL12C.GL_TEXTURE_3D, 0, GL30C.GL_RG8, SIZE, SIZE, SIZE, 0, GL30C.GL_RG, GL11C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
    }

    /** The unpack state is whatever the last upload left (vanilla's 16-wide lightmap every frame); the field wants it plain. */
    private static void unpackState() {
        GlStateManager._pixelStore(GL11C.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11C.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11C.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11C.GL_UNPACK_ALIGNMENT, 1);
        GlStateManager._pixelStore(GL12C.GL_UNPACK_IMAGE_HEIGHT, 0);
        GlStateManager._pixelStore(GL12C.GL_UNPACK_SKIP_IMAGES, 0);
    }

    /** Whether the field shown holds blacklight (after {@link #bind} this frame). */
    public static boolean shown() {
        return wanted && shown;
    }

    /** The texture of the field shown, or 0. */
    public static int texture() {
        return shown() ? textureId : 0;
    }

    /** The world corner of the field shown: {x, y, z}. */
    public static int[] origin() {
        return new int[]{shownX, shownY, shownZ};
    }

    /** For tests: the sources in the field shown and how many builds and uploads there were. */
    public static String stats() {
        return String.format(java.util.Locale.ROOT, "%s, %d sources, %d builds (%.2f ms the last), %d uploads", shown() ? "shown" : "not shown",
                shown() ? shownSources : 0, builds, lastBuildMs, uploads);
    }

    /** For tests: the sources in the field shown, 0 when none is. */
    public static int sources() {
        return shown() ? shownSources : 0;
    }

    public static void onLevelUnload() {
        builtFor = null;
        shown = false;
        finished.set(null);
        originX = NONE;
        windowX = NONE;
        lastBuildTick = NEVER;
    }
}
