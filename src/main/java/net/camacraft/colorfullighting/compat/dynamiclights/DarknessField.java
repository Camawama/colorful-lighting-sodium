package net.camacraft.colorfullighting.compat.dynamiclights;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.camacraft.colorfullighting.ColorfulLighting;
import net.camacraft.colorfullighting.common.ColoredLightEngine;
import net.camacraft.colorfullighting.common.ColoredLightSection;
import net.camacraft.colorfullighting.common.accessors.mixin.LevelAttachments;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ShortBuffer;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The darkness round absorbing blocks, for the dynamic lights (2.13.0). The blocks of {@code light/absorbers.json} (an end
 * gateway, an end portal) take light away: the engine spreads their darkness as it spreads light and takes it from the stored
 * light when a mesh is built. A dynamic light is drawn by the shaders over the finished mesh, so nothing took the darkness
 * from it: a flashlight lit the ground beside an end gateway as if the gateway were not there.
 *
 * <p>This is a window of {@link #SIZE} cells a side round the camera with the engine's darkness in it, as one 3D texture of two
 * bytes a cell: per channel the levels taken less one (0 at the last block of the darkness' reach, so it ends without a step)
 * and whether the cell holds any. The shaders divide one by the other, so a face takes the darkness of the open cells before
 * it alone, and take the result from the dynamic lights at a vertex (the fragments' spot lights with them).
 *
 * <p>Nothing is copied, bound or uploaded while the window holds no darkness: the sections of the window are asked for theirs
 * every {@link #POLL_TICKS} ticks (512 lookups), and only the ones that hold any are read, on a worker. A build that changed
 * nothing uploads nothing. The darkness on a Valkyrien Skies ship is not in the field (it is kept where the ship's blocks are
 * kept). {@code -Dcolorfullighting.darknessField=false} for the way before.
 */
public final class DarknessField {
    public static final int SIZE = 128;
    /** Past the slabs' three units, the blacklight field's and the bloom's two. */
    public static final int TEXTURE_UNIT = DynamicLightField.FIRST_TEXTURE_UNIT + 6;
    private static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("colorfullighting.darknessField"));
    private static final int GRID = 16, BEFORE = 4, SECTIONS = SIZE / 16;
    private static final int POLL_TICKS = 10;
    private static final long NEVER = Long.MIN_VALUE / 2;
    private static final int NONE = Integer.MIN_VALUE;
    private static final int GL_UNSIGNED_SHORT_4_4_4_4 = 0x8033, GL_RGBA4 = 0x8056;

    /** A finished build: a short a cell (x fastest, then y, then z; red in the top four bits), or null when the window holds no darkness. */
    private record Built(short @org.jetbrains.annotations.Nullable [] data, int x, int y, int z, int cells) {}

    // the client thread
    private static ClientLevel builtFor;
    private static long lastPollTick = NEVER;
    private static int originX = NONE, originY, originZ;
    private static CompletableFuture<Void> building;
    private static boolean wanted;
    /** Whether the last look at the window found darkness in it (so an emptied window is handed over once). */
    private static boolean hadDarkness;

    // the worker: its own scratch, and what it last handed over, to tell a build that changed nothing
    private static short[] scratch;
    private static short[] lastData;
    private static int lastX = NONE, lastY, lastZ;

    private static final AtomicReference<Built> finished = new AtomicReference<>();

    // the render thread
    private static int textureId;
    private static ShortBuffer upload;
    private static volatile boolean shown;
    private static volatile int shownX, shownY, shownZ, shownCells;
    private static volatile long builds, uploads;
    private static volatile double lastBuildMs;

    private DarknessField() {}

    /** Once a client tick: looks at the window's sections when due and starts a build when any holds darkness. */
    public static void tick(ClientLevel level) {
        boolean want = ENABLED && level != null && ColoredLightEngine.isEnabled() && DynamicLightsCompat.hasExternalProviders();
        if (!want) {
            if (wanted) onLevelUnload();
            wanted = false;
            return;
        }
        wanted = true;
        if (builtFor != level) {
            onLevelUnload();
            builtFor = level;
        }
        if (building != null && !building.isDone()) return;
        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        long tick = level.getGameTime();
        int wantX = (Math.floorDiv((int) Math.floor(cam.x), GRID) - BEFORE) * GRID;
        int wantY = (Math.floorDiv((int) Math.floor(cam.y), GRID) - BEFORE) * GRID;
        int wantZ = (Math.floorDiv((int) Math.floor(cam.z), GRID) - BEFORE) * GRID;
        boolean moved = wantX != originX || wantY != originY || wantZ != originZ;
        long since = tick - lastPollTick;
        if (!moved && since >= 0 && since < POLL_TICKS) return;
        lastPollTick = tick;
        originX = wantX;
        originY = wantY;
        originZ = wantZ;
        ColoredLightEngine engine = ((LevelAttachments) level).colorfullighting$getEngine();
        if (engine == null) return;
        // the sections of the window that hold darkness: none in nearly every world, and then nothing more is done
        ColoredLightSection[] sections = null;
        int sx0 = wantX >> 4, sy0 = wantY >> 4, sz0 = wantZ >> 4;
        for (int sz = 0; sz < SECTIONS; sz++) {
            for (int sy = 0; sy < SECTIONS; sy++) {
                for (int sx = 0; sx < SECTIONS; sx++) {
                    ColoredLightSection section = engine.dhGetDarknessSection(SectionPos.asLong(sx0 + sx, sy0 + sy, sz0 + sz));
                    if (section == null || !section.hasData()) continue;
                    if (sections == null) sections = new ColoredLightSection[SECTIONS * SECTIONS * SECTIONS];
                    sections[(sz * SECTIONS + sy) * SECTIONS + sx] = section;
                }
            }
        }
        if (sections == null) {
            if (hadDarkness) {
                hadDarkness = false;
                // no build runs now: the worker's memory of what it handed over goes with the field
                lastData = null;
                lastX = NONE;
                finished.set(new Built(null, wantX, wantY, wantZ, 0));
            }
            return;
        }
        hadDarkness = true;
        final ColoredLightSection[] found = sections;
        building = CompletableFuture.runAsync(() -> {
            try {
                build(found, wantX, wantY, wantZ);
            } catch (RuntimeException e) {
                ColorfulLighting.LOGGER.warn("Darkness field: the build failed; it stays as it was", e);
            }
        });
    }

    private static void build(ColoredLightSection[] sections, int ox, int oy, int oz) {
        long started = System.nanoTime();
        if (scratch == null) scratch = new short[SIZE * SIZE * SIZE];
        short[] data = scratch;
        Arrays.fill(data, (short) 0);
        int cells = 0;
        for (int sz = 0; sz < SECTIONS; sz++) {
            for (int sy = 0; sy < SECTIONS; sy++) {
                for (int sx = 0; sx < SECTIONS; sx++) {
                    ColoredLightSection section = sections[(sz * SECTIONS + sy) * SECTIONS + sx];
                    if (section == null) continue;
                    for (int ly = 0; ly < 16; ly++) {
                        for (int lz = 0; lz < 16; lz++) {
                            int row = (((sz << 4) + lz) * SIZE + (sy << 4) + ly) * SIZE + (sx << 4);
                            for (int lx = 0; lx < 16; lx++) {
                                int packed = section.getPacked(lx, ly, lz);
                                if (packed == 0) continue;
                                int r = (packed >>> 8) & 15, g = (packed >>> 4) & 15, b = packed & 15;
                                // less one: 0 at the last block of the reach, so the darkness ends without a step
                                data[row + lx] = (short) (Math.max(0, r - 1) << 12 | Math.max(0, g - 1) << 8 | Math.max(0, b - 1) << 4 | 15);
                                cells++;
                            }
                        }
                    }
                }
            }
        }
        builds++;
        lastBuildMs = (System.nanoTime() - started) / 1.0e6;
        if (cells == 0) {
            if (lastData != null) {
                lastData = null;
                lastX = NONE;
                finished.set(new Built(null, ox, oy, oz, 0));
            }
            return;
        }
        if (lastData != null && lastX == ox && lastY == oy && lastZ == oz && Arrays.equals(lastData, data)) return;
        // a copy only when the darkness changed: the scratch is this worker's alone
        lastData = data.clone();
        lastX = ox;
        lastY = oy;
        lastZ = oz;
        finished.set(new Built(lastData, ox, oy, oz, cells));
    }

    /**
     * Before a pass that samples the field: takes a finished build (uploading it) and binds the texture on its unit. True when
     * the field holds darkness; its corner is {@link #origin}. Render thread.
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
                GL12C.glTexSubImage3D(GL12C.GL_TEXTURE_3D, 0, 0, 0, 0, SIZE, SIZE, SIZE, GL11C.GL_RGBA, GL_UNSIGNED_SHORT_4_4_4_4, upload);
                shownX = b.x;
                shownY = b.y;
                shownZ = b.z;
                shownCells = b.cells;
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
        upload = MemoryUtil.memAllocShort(SIZE * SIZE * SIZE);
        GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, textureId);
        GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
        GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
        GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
        GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
        GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL12C.GL_TEXTURE_WRAP_R, GL12C.GL_CLAMP_TO_EDGE);
        unpackState();
        GL12C.glTexImage3D(GL12C.GL_TEXTURE_3D, 0, GL_RGBA4, SIZE, SIZE, SIZE, 0, GL11C.GL_RGBA, GL_UNSIGNED_SHORT_4_4_4_4, (ShortBuffer) null);
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

    /** Whether the field shown holds darkness (after {@link #bind} this frame). */
    public static boolean shown() {
        return wanted && shown;
    }

    /** The world corner of the field shown: {x, y, z}. */
    public static int[] origin() {
        return new int[]{shownX, shownY, shownZ};
    }

    /** For tests: the cells of darkness in the field shown and how many builds and uploads there were. */
    public static String stats() {
        return String.format(java.util.Locale.ROOT, "%s, %d cells, %d builds (%.2f ms the last), %d uploads", shown() ? "shown" : "not shown",
                shown() ? shownCells : 0, builds, lastBuildMs, uploads);
    }

    /** For tests: the cells of darkness in the field shown, 0 when none is. */
    public static int cells() {
        return shown() ? shownCells : 0;
    }

    /**
     * The darkness the engine stores at a block, each channel 0 to 15, into {@code out}; false when there is none. For the
     * paths that work a dynamic light out on the processor (an entity's exact light).
     */
    public static boolean at(ClientLevel level, int x, int y, int z, float[] out) {
        if (!ENABLED || level == null) return false;
        ColoredLightEngine engine = ((LevelAttachments) level).colorfullighting$getEngine();
        if (engine == null) return false;
        ColoredLightSection section = engine.dhGetDarknessSection(SectionPos.asLong(x >> 4, y >> 4, z >> 4));
        if (section == null || !section.hasData()) return false;
        int packed = section.getPacked(x & 15, y & 15, z & 15);
        if (packed == 0) return false;
        out[0] = (packed >>> 8) & 15;
        out[1] = (packed >>> 4) & 15;
        out[2] = packed & 15;
        return true;
    }

    public static void onLevelUnload() {
        builtFor = null;
        shown = false;
        hadDarkness = false;
        finished.set(null);
        originX = NONE;
        lastPollTick = NEVER;
    }
}
