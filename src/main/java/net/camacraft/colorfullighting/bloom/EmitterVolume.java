package net.camacraft.colorfullighting.bloom;

import com.mojang.blaze3d.systems.RenderSystem;
import net.camacraft.colorfullighting.ColorfulLighting;
import net.camacraft.colorfullighting.accessors.BlockStateWrapper;
import net.camacraft.colorfullighting.common.Config;
import net.camacraft.colorfullighting.common.util.ColorRGB4;
import net.camacraft.colorfullighting.compat.dynamiclights.DynamicLightField;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Which blocks round the camera emit light, for the {@link Bloom} (2.11.0): a 3D texture of a cell a block, {@link #SX} by
 * {@link #SY} by {@link #SZ}, holding each emitting block's light colour (four bits a channel), its level in eight steps
 * and whether it is a lamp all through (a full block at level 14 or 15: glowstone, a sea lantern, a froglight). The
 * bloom finds the block a pixel shows from the picture's depth and reads here whether that block glows, so the terrain's
 * shaders and vertices carry nothing for it.
 *
 * <p>The texture wraps: a block's cell is its place modulo the texture's size, so a camera that moves changes only the
 * sections that came into the window and those that left it. A worker looks at every section of the window (a palette test
 * first, so a section without a light costs a few comparisons), and hands over the sections whose cells differ from what the
 * texture holds; the render thread uploads them and only then moves the window it reports, so the texture and the window
 * always agree.
 *
 * <p>Nothing is built, uploaded or kept bound while the bloom is off.
 */
public final class EmitterVolume {
    public static final int SX = 256, SY = 128, SZ = 256;
    /** Beside the dynamic lights' three slab textures and the blacklight field. */
    public static final int TEXTURE_UNIT = DynamicLightField.FIRST_TEXTURE_UNIT + 4;
    private static final int NX = SX / 16, NY = SY / 16, NZ = SZ / 16;
    /** A rebuild at least every so many ticks (chunks arrive), and at most every so many after a block change. */
    private static final int REBUILD_TICKS = 20, MIN_TICKS = 3;
    private static final long NEVER = Long.MIN_VALUE / 2;
    private static final int NONE = Integer.MIN_VALUE;

    /** One section's cells as the texture takes them (x fastest, then y, then z), or null for a section without a light. */
    private record Update(int slot, short @org.jetbrains.annotations.Nullable [] cells) {}

    /** A finished look at the window: its corner and what changed in the texture. */
    private record Built(int epoch, int x, int y, int z, List<Update> updates, int emitters) {}

    // the client thread
    private static ClientLevel builtFor;
    private static Object builtConfig;
    private static int sectionX = NONE, sectionY, sectionZ;
    private static long lastBuildTick = NEVER;
    private static CompletableFuture<Void> building;
    private static volatile boolean dueNow;
    private static volatile boolean wanted;
    /** Up by one whenever what the texture holds is void: another world, another config, the bloom switched off and on. */
    private static volatile int epoch;
    private static volatile int windowX = NONE, windowY, windowZ;

    // the worker: what the texture holds once every build handed over is uploaded
    private static final short[][] held = new short[NX * NY * NZ][];
    private static int heldEpoch = -1;
    /** Read by the volume's worker and by the ships' look on the client thread. */
    private static final Map<BlockState, Short> values = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * A block that is seen through and emits nothing (2.11.3): glass and panes. A pixel that shows one is looked up behind it,
     * through glass alone: with any graphics mode but Fabulous the glass owns the pixel, and a lamp cased in glass had no glow.
     */
    static final short GLASS = (short) 0xFFF0;

    /** Whether a cell's value is a light source (its level is not 0). */
    static boolean isEmitter(short value) {
        return (value & 0xF) != 0;
    }


    private static final ConcurrentLinkedQueue<Built> finished = new ConcurrentLinkedQueue<>();

    // the render thread
    private static int textureId;
    private static int textureEpoch = -1;
    private static ShortBuffer upload;
    private static boolean shown;
    private static int shownX, shownY, shownZ, shownEmitters;
    private static volatile long builds, uploads;
    private static volatile double lastBuildMs;

    private EmitterVolume() {}

    /** A block changed: when it is in the window, the volume is looked at again within a few ticks. */
    public static void blockChanged(int x, int y, int z) {
        if (!wanted || windowX == NONE) return;
        if (x < windowX || y < windowY || z < windowZ || x >= windowX + SX || y >= windowY + SY || z >= windowZ + SZ) return;
        dueNow = true;
    }

    /** Once a client tick: start a look at the window when one is due. Nothing while the bloom is off. */
    public static void tick(ClientLevel level, boolean bloom) {
        if (level == null || !bloom) {
            if (wanted) {
                wanted = false;
                epoch++;
                finished.clear();
                windowX = NONE;
                sectionX = NONE;
            }
            return;
        }
        wanted = true;
        Object config = Config.uvBlocks();
        if (builtFor != level || builtConfig != config) {
            builtFor = level;
            builtConfig = config;
            epoch++;
            finished.clear();
            sectionX = NONE;
            windowX = NONE;
            lastBuildTick = NEVER;
        }
        if (building != null && !building.isDone()) return;
        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        long tick = level.getGameTime();
        int sx = (int) Math.floor(cam.x) >> 4, sy = (int) Math.floor(cam.y) >> 4, sz = (int) Math.floor(cam.z) >> 4;
        boolean moved = sx != sectionX || sy != sectionY || sz != sectionZ;
        long since = tick - lastBuildTick;
        if (!moved && !(dueNow && since >= MIN_TICKS) && since >= 0 && since < REBUILD_TICKS) return;
        sectionX = sx;
        sectionY = sy;
        sectionZ = sz;
        final int cx0 = sx - NX / 2, cy0 = sy - NY / 2, cz0 = sz - NZ / 2;
        windowX = cx0 << 4;
        windowY = cy0 << 4;
        windowZ = cz0 << 4;
        lastBuildTick = tick;
        dueNow = false;
        final int forEpoch = epoch;
        building = CompletableFuture.runAsync(() -> {
            try {
                build(level, forEpoch, cx0, cy0, cz0);
            } catch (RuntimeException e) {
                ColorfulLighting.LOGGER.warn("Bloom: the look at the light sources failed; the volume stays as it was", e);
            }
        });
    }

    /**
     * What a block state is in the texture: red, green and blue of its light in four bits each, then a bit for a lamp all
     * through and its level in three (1 to 7); 0 for no light.
     */
    static short valueOf(BlockState state) {
        Short known = values.get(state);
        if (known != null) return known;
        short value = 0;
        try {
            int level = Math.max(0, Math.min(15, Config.getEmissionBrightness(new BlockStateWrapper(state))));
            if (level > 0) {
                ColorRGB4 color = Config.getLightColor(state);
                if (color == null) color = Config.defaultColor;
                boolean lamp = level >= 14 && state.isSolidRender(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
                value = (short) ((color.red4 & 15) << 12 | (color.green4 & 15) << 8 | (color.blue4 & 15) << 4 | (lamp ? 8 : 0)
                        | Math.max(1, Math.min(7, (level + 1) >> 1)));
            } else if (state.getBlock() instanceof net.minecraft.world.level.block.AbstractGlassBlock
                    || state.getBlock() instanceof net.minecraft.world.level.block.IronBarsBlock
                    || state.is(net.minecraftforge.common.Tags.Blocks.GLASS) || state.is(net.minecraftforge.common.Tags.Blocks.GLASS_PANES)) {
                value = GLASS;
            }
        } catch (RuntimeException e) {
            value = 0;
        }
        values.put(state, value);
        return value;
    }

    private static short @org.jetbrains.annotations.Nullable [] cellsOf(@org.jetbrains.annotations.Nullable LevelChunkSection section) {
        if (section == null || section.hasOnlyAir() || !section.maybeHas(state -> valueOf(state) != 0)) return null;
        short[] cells = new short[4096];
        boolean any = false;
        for (int ly = 0; ly < 16; ly++) {
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    short value = valueOf(section.getBlockState(lx, ly, lz));
                    if (value == 0) continue;
                    cells[(lz * 16 + ly) * 16 + lx] = value;
                    any = true;
                }
            }
        }
        return any ? cells : null;
    }

    private static void build(ClientLevel level, int forEpoch, int cx0, int cy0, int cz0) {
        long started = System.nanoTime();
        if (heldEpoch != forEpoch) {
            // the texture is cleared for the new epoch before anything of it is uploaded
            Arrays.fill(held, null);
            values.clear();
            heldEpoch = forEpoch;
        }
        int minSection = level.getMinSection();
        List<Update> updates = new ArrayList<>();
        int emitters = 0;
        for (int ix = 0; ix < NX; ix++) {
            for (int iz = 0; iz < NZ; iz++) {
                int cx = cx0 + ix, cz = cz0 + iz;
                ChunkAccess access = level.getChunk(cx, cz, ChunkStatus.FULL, false);
                LevelChunkSection[] column = access instanceof LevelChunk chunk ? chunk.getSections() : null;
                for (int iy = 0; iy < NY; iy++) {
                    int cy = cy0 + iy;
                    short[] cells = null;
                    int si = cy - minSection;
                    if (column != null && si >= 0 && si < column.length) {
                        try {
                            cells = cellsOf(column[si]);
                        } catch (RuntimeException e) {
                            // the section changed under the read: it is as the texture has it until the next look
                            continue;
                        }
                        if (cells != null) for (short cell : cells) if (isEmitter(cell)) emitters++;
                    }
                    int slot = ((cz & (NZ - 1)) * NY + (cy & (NY - 1))) * NX + (cx & (NX - 1));
                    short[] before = held[slot];
                    if (cells == null ? before == null : before != null && Arrays.equals(before, cells)) continue;
                    held[slot] = cells;
                    updates.add(new Update(slot, cells));
                }
            }
        }
        builds++;
        lastBuildMs = (System.nanoTime() - started) / 1.0e6;
        finished.add(new Built(forEpoch, cx0 << 4, cy0 << 4, cz0 << 4, updates, emitters));
    }

    /**
     * Before the bloom reads the volume: uploads what the worker handed over and binds the texture on its unit, leaving the
     * active unit there. True when the volume holds the window {@link #origin} names. Render thread, inside the bloom's saved
     * GL state.
     */
    public static boolean bind() {
        if (!wanted || !RenderSystem.isOnRenderThread()) return false;
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + TEXTURE_UNIT);
        int now = epoch;
        boolean bound = false;
        if (textureEpoch != now) {
            shown = false;
            if (!finished.isEmpty() || textureId <= 0) {
                clear();
                bound = true;
                textureEpoch = now;
            }
        }
        Built b;
        while (textureEpoch == now && (b = finished.poll()) != null) {
            if (b.epoch != now) continue;
            if (!bound) {
                GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, textureId);
                bound = true;
            }
            if (!b.updates.isEmpty()) {
                unpackState();
                for (Update u : b.updates) {
                    int ix = u.slot % NX, iy = (u.slot / NX) % NY, iz = u.slot / (NX * NY);
                    upload.clear();
                    if (u.cells != null) upload.put(u.cells);
                    else for (int i = 0; i < 4096; i++) upload.put((short) 0);
                    upload.flip();
                    GL12C.glTexSubImage3D(GL12C.GL_TEXTURE_3D, 0, ix << 4, iy << 4, iz << 4, 16, 16, 16, GL11C.GL_RGBA, GL12C.GL_UNSIGNED_SHORT_4_4_4_4, upload);
                    uploads++;
                }
            }
            shownX = b.x;
            shownY = b.y;
            shownZ = b.z;
            shownEmitters = b.emitters;
            shown = true;
        }
        if (shown && !bound) GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, textureId);
        return shown;
    }

    /** Makes the texture if there is none and fills it with nothing; it stays bound. */
    private static void clear() {
        if (textureId <= 0) {
            textureId = GL11C.glGenTextures();
            upload = MemoryUtil.memAllocShort(4096);
            GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, textureId);
            GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
            GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
            GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_WRAP_S, GL11C.GL_REPEAT);
            GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_WRAP_T, GL11C.GL_REPEAT);
            GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL12C.GL_TEXTURE_WRAP_R, GL11C.GL_REPEAT);
            GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL12C.GL_TEXTURE_BASE_LEVEL, 0);
            GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL12C.GL_TEXTURE_MAX_LEVEL, 0);
        } else {
            GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, textureId);
        }
        unpackState();
        ByteBuffer zero = MemoryUtil.memCalloc(SX * SY * SZ * 2);
        try {
            GL12C.glTexImage3D(GL12C.GL_TEXTURE_3D, 0, GL11C.GL_RGBA4, SX, SY, SZ, 0, GL11C.GL_RGBA, GL12C.GL_UNSIGNED_SHORT_4_4_4_4, zero);
        } finally {
            MemoryUtil.memFree(zero);
        }
    }

    /** The unpack state is whatever the last upload left (vanilla's lightmap every frame); the volume wants it plain. */
    private static void unpackState() {
        GL11C.glPixelStorei(GL11C.GL_UNPACK_ROW_LENGTH, 0);
        GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_ROWS, 0);
        GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_PIXELS, 0);
        GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, 2);
        GL11C.glPixelStorei(GL12C.GL_UNPACK_IMAGE_HEIGHT, 0);
        GL11C.glPixelStorei(GL12C.GL_UNPACK_SKIP_IMAGES, 0);
        GL11C.glPixelStorei(GL11C.GL_UNPACK_SWAP_BYTES, GL11C.GL_FALSE);
        GL11C.glPixelStorei(GL11C.GL_UNPACK_LSB_FIRST, GL11C.GL_FALSE);
    }

    /** The world corner of the window the texture holds: {x, y, z}. After {@link #bind} said true. */
    public static int[] origin() {
        return new int[]{shownX, shownY, shownZ};
    }

    /** For tests: the light sources in the window, 0 while the volume holds none. */
    public static int emitters() {
        return wanted && shown ? shownEmitters : 0;
    }

    /** For tests and '/cl bloom'. */
    public static String stats() {
        return String.format(java.util.Locale.ROOT, "%d light sources in view, %d looks (%.2f ms the last), %d section uploads",
                emitters(), builds, lastBuildMs, uploads);
    }

    public static void onLevelUnload() {
        builtFor = null;
        epoch++;
        finished.clear();
        sectionX = NONE;
        windowX = NONE;
        lastBuildTick = NEVER;
    }
}
