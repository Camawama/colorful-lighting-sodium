package net.camacraft.colorfullighting.bloom;

import com.mojang.blaze3d.systems.RenderSystem;
import net.camacraft.colorfullighting.ColorfulLighting;
import net.camacraft.colorfullighting.common.accessors.mixin.LevelAttachments;
import net.camacraft.colorfullighting.compat.valkyrienskies.VsCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The bloom's light sources on Valkyrien Skies ships (2.11.2). A ship's blocks are kept far from where the ship is drawn, so
 * the volume of the light sources round the camera ({@link EmitterVolume}) never holds a ship's lamp, and a beacon on a ship
 * had no glow. Here the nearest ships that carry a light source each get a cube of {@link #SIZE} cells in a texture of their
 * own, in the ship's own block grid, with what {@link EmitterVolume} would hold for those blocks. The first pass takes a
 * pixel that matched nothing in the world, turns its place into each of those ships' grids (the ship's transform as it is
 * drawn this frame) and looks there. A ship that moves or turns costs nothing but its matrix; its cube is filled again only
 * every few ticks, or at once when one of its blocks changes.
 */
public final class ShipEmitters {
    public static final int SLOTS = 4, SIZE = 64;
    public static final int TEXTURE_UNIT = EmitterVolume.TEXTURE_UNIT + 1;
    /** Ships further than this from the camera (their nearest side) are not looked at. */
    private static final double REACH = 160.0;
    private static final int RESCAN_TICKS = 10, CANDIDATES = 12;
    private static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("colorfullighting.bloomOnShips"));

    /** One ship's cube as the texture takes it: its ship, the corner of its window in the ship's block grid, its cells. */
    private record Cube(long ship, int x, int y, int z, short[] cells, int emitters) {}

    // the client thread
    private static long lastScanTick = Long.MIN_VALUE / 2;
    private static volatile boolean dueNow;
    /** What the first pass may read: at most {@link #SLOTS} cubes, never changed once here. */
    private static volatile Cube[] cubes = new Cube[0];
    private static volatile long scans;
    private static volatile double lastScanMs;

    // the render thread
    private static int textureId;
    private static ShortBuffer upload;
    private static final Cube[] uploaded = new Cube[SLOTS];
    private static long uploads;

    private ShipEmitters() {}

    /** A block changed: a ship's cube it lies in is filled again on the next tick. */
    public static void blockChanged(int x, int y, int z) {
        for (Cube c : cubes) {
            if (x >= c.x && y >= c.y && z >= c.z && x < c.x + SIZE && y < c.y + SIZE && z < c.z + SIZE) {
                dueNow = true;
                return;
            }
        }
    }

    /** Once a client tick: looks at the ships near the camera when it is time. Nothing while the bloom is off or without ships. */
    public static void tick(ClientLevel level, boolean bloom) {
        if (!ENABLED || !bloom || level == null || !VsCompat.isAvailable()) {
            if (cubes.length != 0) cubes = new Cube[0];
            return;
        }
        long tick = level.getGameTime();
        if (!dueNow && tick - lastScanTick < RESCAN_TICKS && tick >= lastScanTick) return;
        lastScanTick = tick;
        dueNow = false;
        long started = System.nanoTime();
        try {
            VsCompat.ShipFrame[] ships = VsCompat.ships((LevelAttachments) level);
            if (ships.length == 0) {
                if (cubes.length != 0) cubes = new Cube[0];
                return;
            }
            Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            List<VsCompat.ShipFrame> near = new ArrayList<>();
            for (VsCompat.ShipFrame ship : ships) {
                if (ship.distanceTo(cam.x, cam.y, cam.z) <= REACH) near.add(ship);
            }
            near.sort((a, b) -> Double.compare(a.distanceTo(cam.x, cam.y, cam.z), b.distanceTo(cam.x, cam.y, cam.z)));
            Cube[] before = cubes;
            List<Cube> found = new ArrayList<>(SLOTS);
            for (int i = 0; i < near.size() && i < CANDIDATES && found.size() < SLOTS; i++) {
                Cube cube = scan(level, near.get(i), cam);
                if (cube == null) continue;
                // the same cells as before: the same object, so nothing is uploaded again
                for (Cube old : before) {
                    if (old.ship == cube.ship && old.x == cube.x && old.y == cube.y && old.z == cube.z && Arrays.equals(old.cells, cube.cells)) {
                        cube = old;
                        break;
                    }
                }
                found.add(cube);
            }
            cubes = found.toArray(new Cube[0]);
        } catch (RuntimeException | LinkageError e) {
            ColorfulLighting.LOGGER.warn("Bloom: the ships' light sources could not be read; ships have no glow", e);
            cubes = new Cube[0];
            lastScanTick = tick + 1200;
        }
        scans++;
        lastScanMs = (System.nanoTime() - started) / 1.0e6;
    }

    /** A ship's cube, or null when the part of it looked at holds no light source. */
    private static Cube scan(ClientLevel level, VsCompat.ShipFrame ship, Vec3 cam) {
        // the window: the whole ship when it fits, else the part round the camera's place in the ship's grid
        double[] at = ship.toShip(cam.x, cam.y, cam.z);
        int ox = origin(ship.minX(), ship.maxX(), at[0]), oy = origin(ship.minY(), ship.maxY(), at[1]), oz = origin(ship.minZ(), ship.maxZ(), at[2]);
        short[] cells = null;
        int emitters = 0;
        int minSection = level.getMinSection();
        for (int cx = ox >> 4; cx <= (ox + SIZE - 1) >> 4; cx++) {
            for (int cz = oz >> 4; cz <= (oz + SIZE - 1) >> 4; cz++) {
                if (!(level.getChunk(cx, cz, ChunkStatus.FULL, false) instanceof LevelChunk chunk)) continue;
                LevelChunkSection[] column = chunk.getSections();
                for (int cy = oy >> 4; cy <= (oy + SIZE - 1) >> 4; cy++) {
                    int si = cy - minSection;
                    if (si < 0 || si >= column.length) continue;
                    LevelChunkSection section = column[si];
                    if (section == null || section.hasOnlyAir() || !section.maybeHas(state -> EmitterVolume.valueOf(state) != 0)) continue;
                    int x0 = Math.max(ox, cx << 4), x1 = Math.min(ox + SIZE, (cx << 4) + 16);
                    int y0 = Math.max(oy, cy << 4), y1 = Math.min(oy + SIZE, (cy << 4) + 16);
                    int z0 = Math.max(oz, cz << 4), z1 = Math.min(oz + SIZE, (cz << 4) + 16);
                    for (int y = y0; y < y1; y++) {
                        for (int z = z0; z < z1; z++) {
                            for (int x = x0; x < x1; x++) {
                                short value = EmitterVolume.valueOf(section.getBlockState(x & 15, y & 15, z & 15));
                                if (value == 0) continue;
                                // glass too (2.12.0): a lamp behind a ship's glass is looked up through it
                                if (cells == null) cells = new short[SIZE * SIZE * SIZE];
                                cells[((z - oz) * SIZE + (y - oy)) * SIZE + (x - ox)] = value;
                                if (EmitterVolume.isEmitter(value)) emitters++;
                            }
                        }
                    }
                }
            }
        }
        return cells == null || emitters == 0 ? null : new Cube(ship.id(), ox, oy, oz, cells, emitters);
    }

    private static int origin(int min, int max, double at) {
        if (max - min <= SIZE) return min;
        return Math.max(min, Math.min(max - SIZE, (int) Math.floor(at) - SIZE / 2));
    }

    /**
     * For the first pass, inside the bloom's saved GL state: uploads what changed, binds the texture on its unit and writes a
     * matrix a cube into {@code matrices} (column-major, 16 floats each) that takes a place relative to the camera into the
     * cube's cells. Returns how many cubes there are; the active texture unit is left on this texture's.
     */
    public static int bind(ClientLevel level, Vec3 cam, float[] matrices) {
        Cube[] now = cubes;
        if (now.length == 0 || !RenderSystem.isOnRenderThread()) return 0;
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + TEXTURE_UNIT);
        if (textureId <= 0) create();
        else GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, textureId);
        int count = 0;
        for (int i = 0; i < now.length && count < SLOTS; i++) {
            Cube cube = now[i];
            double[] m = VsCompat.renderWorldToShip(level, cube.ship);
            if (m == null) continue;
            if (uploaded[count] != cube) {
                unpackState();
                upload.clear();
                upload.put(cube.cells).flip();
                GL12C.glTexSubImage3D(GL12C.GL_TEXTURE_3D, 0, 0, 0, count * SIZE, SIZE, SIZE, SIZE, GL11C.GL_RGBA, GL12C.GL_UNSIGNED_SHORT_4_4_4_4, upload);
                uploaded[count] = cube;
                uploads++;
            }
            // rows of the ship's world-to-ship transform; a place relative to the camera goes through the camera's own place
            int o = count * 16;
            int[] corner = {cube.x, cube.y, cube.z};
            for (int row = 0; row < 3; row++) {
                double a = m[row * 4], b = m[row * 4 + 1], c = m[row * 4 + 2], d = m[row * 4 + 3];
                matrices[o + row] = (float) a;
                matrices[o + 4 + row] = (float) b;
                matrices[o + 8 + row] = (float) c;
                matrices[o + 12 + row] = (float) (a * cam.x + b * cam.y + c * cam.z + d - corner[row]);
            }
            matrices[o + 3] = 0f;
            matrices[o + 7] = 0f;
            matrices[o + 11] = 0f;
            matrices[o + 15] = 1f;
            count++;
        }
        return count;
    }

    private static void create() {
        textureId = GL11C.glGenTextures();
        upload = MemoryUtil.memAllocShort(SIZE * SIZE * SIZE);
        GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, textureId);
        GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
        GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
        GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL12C.GL_TEXTURE_WRAP_R, GL12C.GL_CLAMP_TO_EDGE);
        GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL12C.GL_TEXTURE_BASE_LEVEL, 0);
        GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL12C.GL_TEXTURE_MAX_LEVEL, 0);
        unpackState();
        GL12C.glTexImage3D(GL12C.GL_TEXTURE_3D, 0, GL11C.GL_RGBA4, SIZE, SIZE, SIZE * SLOTS, 0, GL11C.GL_RGBA, GL12C.GL_UNSIGNED_SHORT_4_4_4_4, (java.nio.ByteBuffer) null);
        Arrays.fill(uploaded, null);
    }

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

    public static void onLevelUnload() {
        cubes = new Cube[0];
        lastScanTick = Long.MIN_VALUE / 2;
    }

    /** For tests: the ships with a cube, and the light sources in them. */
    public static int ships() {
        return cubes.length;
    }

    public static int emitters() {
        int n = 0;
        for (Cube c : cubes) n += c.emitters;
        return n;
    }

    public static String stats() {
        return String.format(java.util.Locale.ROOT, "%d ships with %d light sources, %d scans (%.3f ms the last), %d uploads", ships(), emitters(), scans, lastScanMs, uploads);
    }
}
