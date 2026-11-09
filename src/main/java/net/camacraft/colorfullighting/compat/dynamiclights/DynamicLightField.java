package net.camacraft.colorfullighting.compat.dynamiclights;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.camacraft.colorfullighting.ColorfulLighting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The dynamic lights propagated through the blocks (2.9.0): a {@link #SIZE}-block cube round the camera (the same cube as
 * {@link VoxelOcclusion}) holding, per block, the coloured light the dynamic lights give it, spread from each light cell by
 * cell the way the block engine spreads a lamp's light: stopped by opaque blocks, tinted per channel by Colorful Lighting's
 * filter blocks, going round corners because the path does. That is the look of Minecraft's own light, where a straight line
 * from the light to each point gave hard shadows with jagged edges and black behind every wall (2026-11-09). Since 2.9.1 the
 * level a cell gets is the light's own falloff by the straight distance (Manhattan for a diamond light, Euclidean for a round
 * one or a spot) wherever the path through the blocks is no longer than that, and the path's length only where it is (round a
 * corner), so a round light is round again and the gradient is the analytic one, while a wall still stops it. A spot light is
 * cut by its cone where it is written, except within two blocks of itself, where it lights its own lamp. The field is rebuilt on
 * a worker thread whenever a light moved, changed or the blocks were rebuilt under it (a light that stands still costs nothing),
 * and uploaded as an RGBA8 3D texture (the part that changed) into one of two textures in turn; the shaders blend the tick
 * before's with the latest by the frame's partial tick, so a moving light slides instead of stepping twenty times a second.
 * Sampled trilinearly half a block out along the face's normal, as vanilla's smooth lighting samples the blocks in front of a
 * face. The CPU samples (entities, block entities, particles) read the latest array. Lights whose cell is outside the cube are
 * left to the shaders' straight-line falloff, as are points outside the cube.
 */
public final class DynamicLightField {
    public static final int SIZE = VoxelOcclusion.SIZE;
    /** The latest field's texture unit, and the tick before's. */
    public static final int TEXTURE_UNIT = 12, TEXTURE_UNIT_PREVIOUS = 13;
    private static final int BYTES = SIZE * SIZE * SIZE * 4;
    /** At most this many lights (nearest the camera) go into the field. */
    private static final int MAX_LIGHTS = 64;
    /** A spot's propagation is pruned beyond its outer half angle plus this (degrees): it wraps a corner a little, never behind itself. */
    private static final double WRAP_MARGIN = Math.toRadians(20);
    /** Within this of a spot light every direction is lit in full, fading to the cone a block farther: the lamp itself and the kart it sits on. */
    private static final double SPOT_NEAR = 2.0;
    private static final float SQRT2 = (float) Math.sqrt(2.0), SQRT3 = (float) Math.sqrt(3.0);
    /** How much longer than the straight distance a path may be before it counts (the grid's paths overshoot a little). */
    private static final float ROUND_SLACK = 0.6f, DIAMOND_SLACK = 0.1f;

    /** One light as a build takes it: world position, level 0..15, colour 0..1, the falloff and reach, and the cone for a spot. */
    record Light(long key, double x, double y, double z, float level, float r, float g, float b, int falloff, float range,
                 boolean spot, double dx, double dy, double dz, float outerCos, float innerCos, float pruneCos) {}

    /** A finished build: the field, its origin, the box with light in it (half-open), whether the origin moved, and the keys of the lights it holds. */
    private record Build(byte[] field, int ox, int oy, int oz, int x0, int y0, int z0, int x1, int y1, int z1, boolean moved, Set<Long> keys, long tick) {}

    private static volatile boolean enabled;
    private static final int[] textureIds = {-1, -1};
    /** The box each texture holds light in now (half-open), so stale light is cleared with its next upload. */
    private static final int[][] litBox = new int[2][6];
    /** Which texture holds the latest field, and whether the other holds the tick before's of the same origin. */
    private static int latest = 0;
    private static boolean previousValid;
    private static long uploadedTick = Long.MIN_VALUE;
    private static ByteBuffer upload;
    private static int uploadedX = Integer.MIN_VALUE, uploadedY, uploadedZ;
    private static boolean uploadedAny;
    private static final AtomicReference<Build> ready = new AtomicReference<>();
    private static CompletableFuture<Void> building;
    private static long lastHash = Long.MIN_VALUE;
    private static int lastBlocksId = -1;
    private static int lastOx = Integer.MIN_VALUE, lastOy, lastOz;
    private static ClientLevel builtFor;
    private static byte[] bufA, bufB;
    /** What the shaders have: the latest array, its origin and the lights' keys; the CPU samples read the same. */
    private static volatile byte[] current;
    private static volatile int currentX = Integer.MIN_VALUE, currentY, currentZ;
    private static volatile Set<Long> held = Collections.emptySet();

    // the worker's scratch: a local grid per light (the shortest path length, the transmittance along it, a visit stamp) and a binary heap
    private static float[] bestPath = new float[0], transR = new float[0], transG = new float[0], transB = new float[0];
    private static int[] stamp = new int[0];
    private static int generation;
    private static int[] heapIdx = new int[1024];
    private static float[] heapKey = new float[1024];
    private static int heapSize;

    private DynamicLightField() {}

    public static void setEnabled(boolean value) {
        enabled = value;
        if (!value) {
            ready.set(null);
            current = null;
            currentX = Integer.MIN_VALUE;
            held = Collections.emptySet();
            lastHash = Long.MIN_VALUE;
            previousValid = false;
        }
    }

    public static boolean enabled() {
        return enabled;
    }

    /** Whether the shaders and the CPU samples have a field now. */
    public static boolean active() {
        return enabled && current != null;
    }

    /** Whether the field holds this light (its key from the provider): the straight-line paths skip it where the field answers. */
    public static boolean holds(long key) {
        return held.contains(key);
    }

    /** The field's origin relative to the camera, for the shaders. */
    public static float[] originRelative(Vec3 camera) {
        return new float[]{(float) (uploadedX - camera.x), (float) (uploadedY - camera.y), (float) (uploadedZ - camera.z)};
    }

    /** The texture unit of the latest field. */
    public static int latestUnit() {
        return latest == 0 ? TEXTURE_UNIT : TEXTURE_UNIT_PREVIOUS;
    }

    /** The texture unit of the tick before's field (the latest's when there is none to blend from). */
    public static int previousUnit() {
        if (!previousValid) return latestUnit();
        return latest == 0 ? TEXTURE_UNIT_PREVIOUS : TEXTURE_UNIT;
    }

    /**
     * How far the frame is from the tick before's field toward the latest (0 to 1): the frame's partial tick while the latest
     * arrived this tick, else 1 (the shaders then read the latest alone).
     */
    public static float blend(ClientLevel level, float partialTick) {
        if (!previousValid || level == null || level.getGameTime() != uploadedTick) return 1f;
        return Math.max(0f, Math.min(1f, partialTick));
    }

    /**
     * The CPU twin of the shaders' sample: the latest field's light at a block (r, g, b as 0..255 into {@code out}); false when
     * the block is outside the field or there is none. Thread-safe.
     */
    public static boolean sample(int blockX, int blockY, int blockZ, int[] out) {
        byte[] f = current;
        if (f == null) return false;
        int x = blockX - currentX, y = blockY - currentY, z = blockZ - currentZ;
        if (x < 0 || y < 0 || z < 0 || x >= SIZE || y >= SIZE || z >= SIZE) return false;
        int i = ((z * SIZE + y) * SIZE + x) * 4;
        out[0] = f[i] & 0xFF;
        out[1] = f[i + 1] & 0xFF;
        out[2] = f[i + 2] & 0xFF;
        return true;
    }

    /** Once a client tick, after the level's sources were gathered: starts a rebuild when the lights or the blocks changed. */
    static void tick(ClientLevel level, List<Light> lights) {
        if (!enabled) return;
        if (builtFor != level) {
            builtFor = level;
            lastHash = Long.MIN_VALUE;
            current = null;
            currentX = Integer.MIN_VALUE;
            held = Collections.emptySet();
            previousValid = false;
        }
        byte[] blocks = VoxelOcclusion.current();
        if (blocks == null) return;
        int[] origin = VoxelOcclusion.currentOrigin();
        int blocksId = VoxelOcclusion.buildId();
        int ox = origin[0], oy = origin[1], oz = origin[2];
        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        List<Light> inside = new ArrayList<>();
        for (Light l : lights) {
            int cx = (int) Math.floor(l.x) - ox, cy = (int) Math.floor(l.y) - oy, cz = (int) Math.floor(l.z) - oz;
            if (cx < 0 || cy < 0 || cz < 0 || cx >= SIZE || cy >= SIZE || cz >= SIZE) continue;
            inside.add(l);
        }
        if (inside.size() > MAX_LIGHTS) {
            inside.sort((a, b) -> Double.compare(dist2(a, cam), dist2(b, cam)));
            inside = new ArrayList<>(inside.subList(0, MAX_LIGHTS));
        }
        long hash = 17;
        for (Light l : inside) {
            hash = hash * 31 + l.key;
            hash = hash * 31 + Math.round(l.x * 16) + Math.round(l.y * 16) * 7919L + Math.round(l.z * 16) * 104729L;
            hash = hash * 31 + Math.round(l.level * 16);
            hash = hash * 31 + Float.floatToIntBits(l.r) + Float.floatToIntBits(l.g) * 31L + Float.floatToIntBits(l.b) * 961L;
            hash = hash * 31 + l.falloff + Float.floatToIntBits(l.range) * 3L;
            if (l.spot) hash = hash * 31 + Math.round(l.dx * 64) + Math.round(l.dy * 64) * 131L + Math.round(l.dz * 64) * 17161L + Float.floatToIntBits(l.outerCos);
        }
        boolean moved = ox != lastOx || oy != lastOy || oz != lastOz;
        if (!moved && hash == lastHash && blocksId == lastBlocksId) return;
        if (building != null && !building.isDone()) return;
        lastHash = hash;
        lastBlocksId = blocksId;
        lastOx = ox;
        lastOy = oy;
        lastOz = oz;
        final List<Light> job = inside;
        final long tick = level.getGameTime();
        building = CompletableFuture.runAsync(() -> {
            try {
                build(job, blocks, ox, oy, oz, moved, tick);
            } catch (RuntimeException e) {
                ColorfulLighting.LOGGER.warn("Building the dynamic light field failed; the lights fall back to straight lines this tick", e);
            }
        });
    }

    private static double dist2(Light l, Vec3 cam) {
        double dx = l.x - cam.x, dy = l.y - cam.y, dz = l.z - cam.z;
        return dx * dx + dy * dy + dz * dz;
    }

    // ---- the worker ----

    private static void build(List<Light> lights, byte[] blocks, int ox, int oy, int oz, boolean moved, long tick) {
        byte[] field;
        if (bufA == null) bufA = new byte[BYTES];
        if (bufB == null) bufB = new byte[BYTES];
        field = current == bufA ? bufB : bufA;
        Arrays.fill(field, (byte) 0);
        int x0 = SIZE, y0 = SIZE, z0 = SIZE, x1 = 0, y1 = 0, z1 = 0;
        Set<Long> keys = new HashSet<>();
        for (Light l : lights) {
            int reach = (int) Math.ceil(l.range) + 1;
            int cx = (int) Math.floor(l.x) - ox, cy = (int) Math.floor(l.y) - oy, cz = (int) Math.floor(l.z) - oz;
            x0 = Math.min(x0, Math.max(0, cx - reach));
            y0 = Math.min(y0, Math.max(0, cy - reach));
            z0 = Math.min(z0, Math.max(0, cz - reach));
            x1 = Math.max(x1, Math.min(SIZE, cx + reach + 1));
            y1 = Math.max(y1, Math.min(SIZE, cy + reach + 1));
            z1 = Math.max(z1, Math.min(SIZE, cz + reach + 1));
            propagate(l, blocks, field, ox, oy, oz);
            keys.add(l.key);
        }
        if (x1 > x0 && y1 > y0 && z1 > z0) dilate(blocks, field, x0, y0, z0, x1, y1, z1);
        else x0 = y0 = z0 = x1 = y1 = z1 = 0;
        ready.set(new Build(field, ox, oy, oz, x0, y0, z0, x1, y1, z1, moved, keys, tick));
    }

    /**
     * Spreads one light through the blocks into the field (per-channel max). A shortest-path search over the cells from the
     * light's cell (six neighbours for a diamond light, twenty-six for a round one or a spot), impassable through opaque cells,
     * carrying the filter transmittance met along the way; a cell's level is the light's level less its falloff over the
     * larger of the straight distance from the light and the path's length (less a little slack for the grid's overshoot), so
     * the open is the analytic falloff and only a corner lengthens the way.
     */
    private static void propagate(Light l, byte[] blocks, byte[] field, int ox, int oy, int oz) {
        int reach = (int) Math.ceil(l.range) + 1;
        int d = 2 * reach + 1;
        int cells = d * d * d;
        if (bestPath.length < cells) {
            bestPath = new float[cells];
            transR = new float[cells];
            transG = new float[cells];
            transB = new float[cells];
            stamp = new int[cells];
        }
        generation++;
        if (generation == Integer.MAX_VALUE) {
            Arrays.fill(stamp, 0);
            generation = 1;
        }
        heapSize = 0;
        int sx = (int) Math.floor(l.x), sy = (int) Math.floor(l.y), sz = (int) Math.floor(l.z);
        int bx = sx - reach, by = sy - reach, bz = sz - reach; // world origin of the local grid
        boolean round = l.falloff == 1 || l.spot;
        // a level lost per block of distance: a point light's reach is its level; a spot dims over its range
        float cost = l.spot ? l.level / Math.max(1f, l.range) : 1f;
        float slack = round ? ROUND_SLACK : DIAMOND_SLACK;
        int neighbours = round ? 26 : 6;
        // the source cell: the path starts at the light itself
        int si = (reach * d + reach) * d + reach;
        relax(si, 0f, 1f, 1f, 1f);
        float colR = l.r / 15f * 255f, colG = l.g / 15f * 255f, colB = l.b / 15f * 255f;
        while (heapSize > 0) {
            int li = pop();
            float path = bestPath[li];
            if (heapKey[heapSize] != -path) { /* a stale entry: the cell was reached shorter since */ }
            int lx = li % d, ly = (li / d) % d, lz = li / (d * d);
            int wx = bx + lx, wy = by + ly, wz = bz + lz;
            int vx = wx - ox, vy = wy - oy, vz = wz - oz;
            // the straight distance from the light to the cell's centre, by the light's own measure
            double ddx = wx + 0.5 - l.x, ddy = wy + 0.5 - l.y, ddz = wz + 0.5 - l.z;
            double straight = round ? Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz) : Math.abs(ddx) + Math.abs(ddy) + Math.abs(ddz);
            float value = l.level - cost * (float) Math.max(straight, path - slack);
            if (value > 0f) {
                float cone = 1f;
                if (l.spot) {
                    double len = Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz);
                    if (len > 1.0e-6) {
                        double cosA = (ddx * l.dx + ddy * l.dy + ddz * l.dz) / len;
                        cone = cosA <= l.outerCos ? 0f : l.innerCos <= l.outerCos ? 1f : (float) Math.min(1.0, (cosA - l.outerCos) / (l.innerCos - l.outerCos));
                        // the lamp itself and what carries it are lit all round
                        cone = Math.max(cone, (float) Math.max(0.0, Math.min(1.0, SPOT_NEAR + 1.0 - len)));
                    }
                }
                if (cone > 0f) {
                    float v = Math.min(15f, value) / 15f * cone;
                    int fi = ((vz * SIZE + vy) * SIZE + vx) * 4;
                    int r = Math.round(colR * v * transR[li]), g = Math.round(colG * v * transG[li]), b = Math.round(colB * v * transB[li]);
                    if (r > (field[fi] & 0xFF)) field[fi] = (byte) r;
                    if (g > (field[fi + 1] & 0xFF)) field[fi + 1] = (byte) g;
                    if (b > (field[fi + 2] & 0xFF)) field[fi + 2] = (byte) b;
                }
            }
            // no light left beyond here: the path can only grow
            if (l.level - cost * (path - slack) <= 0f) continue;
            for (int n = 0; n < neighbours; n++) {
                int nx = lx + NX[n], ny = ly + NY[n], nz = lz + NZ[n];
                if (nx < 0 || ny < 0 || nz < 0 || nx >= d || ny >= d || nz >= d) continue;
                int nvx = vx + NX[n], nvy = vy + NY[n], nvz = vz + NZ[n];
                if (nvx < 0 || nvy < 0 || nvz < 0 || nvx >= SIZE || nvy >= SIZE || nvz >= SIZE) continue;
                int bi = ((nvz * SIZE + nvy) * SIZE + nvx) * 4;
                int tr8 = blocks[bi] & 0xFF, tg8 = blocks[bi + 1] & 0xFF, tb8 = blocks[bi + 2] & 0xFF;
                if (tr8 == 0 && tg8 == 0 && tb8 == 0) continue; // opaque
                // a diagonal step passes between its two or three face neighbours: both must be open, or light would cut a corner through a wall
                if (n >= 6 && !diagonalOpen(blocks, vx, vy, vz, NX[n], NY[n], NZ[n])) continue;
                float step = n < 6 ? 1f : n < 18 ? SQRT2 : SQRT3;
                float npath = path + step;
                if (l.spot) {
                    double ndx = bx + nx + 0.5 - l.x, ndy = by + ny + 0.5 - l.y, ndz = bz + nz + 0.5 - l.z;
                    double len = Math.sqrt(ndx * ndx + ndy * ndy + ndz * ndz);
                    if (len > SPOT_NEAR + 1.0 && (ndx * l.dx + ndy * l.dy + ndz * l.dz) / len < l.pruneCos) continue;
                }
                relax((nz * d + ny) * d + nx, npath, transR[li] * tr8 / 255f, transG[li] * tg8 / 255f, transB[li] * tb8 / 255f);
            }
        }
    }

    /** Whether a diagonal step from a cell is open: every face neighbour it slips between lets light through. */
    private static boolean diagonalOpen(byte[] blocks, int vx, int vy, int vz, int sx, int sy, int sz) {
        if (sx != 0 && !open(blocks, vx + sx, vy, vz)) return false;
        if (sy != 0 && !open(blocks, vx, vy + sy, vz)) return false;
        return sz == 0 || open(blocks, vx, vy, vz + sz);
    }

    private static boolean open(byte[] blocks, int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= SIZE || y >= SIZE || z >= SIZE) return false;
        int i = ((z * SIZE + y) * SIZE + x) * 4;
        return blocks[i] != 0 || blocks[i + 1] != 0 || blocks[i + 2] != 0;
    }

    private static final int[] NX = {1, -1, 0, 0, 0, 0, 1, 1, -1, -1, 1, 1, -1, -1, 0, 0, 0, 0, 1, 1, 1, 1, -1, -1, -1, -1};
    private static final int[] NY = {0, 0, 1, -1, 0, 0, 1, -1, 1, -1, 0, 0, 0, 0, 1, 1, -1, -1, 1, 1, -1, -1, 1, 1, -1, -1};
    private static final int[] NZ = {0, 0, 0, 0, 1, -1, 0, 0, 0, 0, 1, -1, 1, -1, 1, -1, 1, -1, 1, -1, 1, -1, 1, -1, 1, -1};

    /** Records a shorter path to a cell (with the transmittance along it) and queues the cell. */
    private static void relax(int li, float path, float tr, float tg, float tb) {
        boolean fresh = stamp[li] != generation;
        if (fresh) stamp[li] = generation;
        else if (path >= bestPath[li] - 1.0e-4f) return;
        bestPath[li] = path;
        transR[li] = tr;
        transG[li] = tg;
        transB[li] = tb;
        push(li, -path);
    }

    private static void push(int idx, float key) {
        if (heapSize == heapIdx.length) {
            heapIdx = Arrays.copyOf(heapIdx, heapSize * 2);
            heapKey = Arrays.copyOf(heapKey, heapSize * 2);
        }
        int i = heapSize++;
        while (i > 0) {
            int parent = (i - 1) >> 1;
            if (heapKey[parent] >= key) break;
            heapIdx[i] = heapIdx[parent];
            heapKey[i] = heapKey[parent];
            i = parent;
        }
        heapIdx[i] = idx;
        heapKey[i] = key;
    }

    /** Pops the cell with the shortest path (the heap's keys are the negated path lengths, so the largest key is the shortest path). */
    private static int pop() {
        int top = heapIdx[0];
        float topKey = heapKey[0];
        heapSize--;
        if (heapSize > 0) {
            int idx = heapIdx[heapSize];
            float key = heapKey[heapSize];
            int i = 0;
            while (true) {
                int child = 2 * i + 1;
                if (child >= heapSize) break;
                if (child + 1 < heapSize && heapKey[child + 1] > heapKey[child]) child++;
                if (heapKey[child] <= key) break;
                heapIdx[i] = heapIdx[child];
                heapKey[i] = heapKey[child];
                i = child;
            }
            heapIdx[i] = idx;
            heapKey[i] = key;
        }
        heapKey[heapSize] = topKey;
        return top;
    }

    /**
     * Opaque cells take the brightest of their six neighbours, so a face sampled against a wall's foot or a floor's edge
     * (the shaders interpolate between the cells round the sample point) is not dragged dark by the solid cell beside it, as
     * vanilla's smooth lighting substitutes an opaque neighbour's light. Light never passes through: the written value
     * comes from the lit side's air and is read only where that air is sampled.
     */
    private static void dilate(byte[] blocks, byte[] field, int x0, int y0, int z0, int x1, int y1, int z1) {
        for (int z = z0; z < z1; z++) {
            for (int y = y0; y < y1; y++) {
                for (int x = x0; x < x1; x++) {
                    int i = ((z * SIZE + y) * SIZE + x) * 4;
                    if (blocks[i] != 0 || blocks[i + 1] != 0 || blocks[i + 2] != 0) continue;
                    int r = 0, g = 0, b = 0;
                    for (int n = 0; n < 6; n++) {
                        int nx = x + NX[n], ny = y + NY[n], nz = z + NZ[n];
                        if (nx < 0 || ny < 0 || nz < 0 || nx >= SIZE || ny >= SIZE || nz >= SIZE) continue;
                        int j = ((nz * SIZE + ny) * SIZE + nx) * 4;
                        if (blocks[j] == 0 && blocks[j + 1] == 0 && blocks[j + 2] == 0) continue;
                        r = Math.max(r, field[j] & 0xFF);
                        g = Math.max(g, field[j + 1] & 0xFF);
                        b = Math.max(b, field[j + 2] & 0xFF);
                    }
                    field[i] = (byte) r;
                    field[i + 1] = (byte) g;
                    field[i + 2] = (byte) b;
                }
            }
        }
    }

    // ---- the render thread ----

    /**
     * Before a pass that samples the field: uploads a finished build (the box that changed) into the texture not holding the
     * latest field, which then becomes the latest while the other is the tick before's, and binds both on their units. True
     * when a texture with light is bound. Render thread.
     */
    public static boolean bind() {
        if (!enabled) return false;
        if (!RenderSystem.isOnRenderThread()) return uploadedAny;
        int previousUnit = GlStateManager._getActiveTexture();
        if (textureIds[0] <= 0) {
            upload = MemoryUtil.memAlloc(BYTES);
            ByteBuffer zero = MemoryUtil.memCalloc(BYTES);
            unpackState();
            for (int t = 0; t < 2; t++) {
                textureIds[t] = GL11C.glGenTextures();
                GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + (t == 0 ? TEXTURE_UNIT : TEXTURE_UNIT_PREVIOUS));
                GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, textureIds[t]);
                GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
                GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
                GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
                GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
                GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL12C.GL_TEXTURE_WRAP_R, GL12C.GL_CLAMP_TO_EDGE);
                GL12C.glTexImage3D(GL12C.GL_TEXTURE_3D, 0, GL11C.GL_RGBA8, SIZE, SIZE, SIZE, 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, zero);
            }
            MemoryUtil.memFree(zero);
        }
        Build b = ready.getAndSet(null);
        if (b != null) {
            unpackState();
            boolean whole = b.moved || !uploadedAny;
            int target = whole ? 0 : 1 - latest;
            if (whole) {
                // a new origin: both textures take the whole field (nothing of the old place is worth blending from)
                for (int t = 0; t < 2; t++) {
                    GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + (t == 0 ? TEXTURE_UNIT : TEXTURE_UNIT_PREVIOUS));
                    GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, textureIds[t]);
                    upload.clear();
                    upload.put(b.field).flip();
                    GL12C.glTexSubImage3D(GL12C.GL_TEXTURE_3D, 0, 0, 0, 0, SIZE, SIZE, SIZE, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, upload);
                    litBox[t] = new int[]{b.x0, b.y0, b.z0, b.x1, b.y1, b.z1};
                }
                latest = 0;
                previousValid = false;
            } else {
                GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + (target == 0 ? TEXTURE_UNIT : TEXTURE_UNIT_PREVIOUS));
                GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, textureIds[target]);
                // the box the target texture still holds light in (from two ticks ago) is cleared by uploading it again
                int[] lit = litBox[target];
                int x0 = b.x0, y0 = b.y0, z0 = b.z0, x1 = b.x1, y1 = b.y1, z1 = b.z1;
                if (lit[3] > lit[0]) {
                    if (x1 > x0) {
                        x0 = Math.min(x0, lit[0]); y0 = Math.min(y0, lit[1]); z0 = Math.min(z0, lit[2]);
                        x1 = Math.max(x1, lit[3]); y1 = Math.max(y1, lit[4]); z1 = Math.max(z1, lit[5]);
                    } else {
                        x0 = lit[0]; y0 = lit[1]; z0 = lit[2]; x1 = lit[3]; y1 = lit[4]; z1 = lit[5];
                    }
                }
                if (x1 > x0 && y1 > y0 && z1 > z0) {
                    upload.clear();
                    int w = x1 - x0;
                    for (int z = z0; z < z1; z++) {
                        for (int y = y0; y < y1; y++) {
                            upload.put(b.field, ((z * SIZE + y) * SIZE + x0) * 4, w * 4);
                        }
                    }
                    upload.flip();
                    GL12C.glTexSubImage3D(GL12C.GL_TEXTURE_3D, 0, x0, y0, z0, w, y1 - y0, z1 - z0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, upload);
                }
                litBox[target] = new int[]{b.x0, b.y0, b.z0, b.x1, b.y1, b.z1};
                latest = target;
                previousValid = true;
            }
            uploadedTick = b.tick + 1;
            uploadedX = b.ox;
            uploadedY = b.oy;
            uploadedZ = b.oz;
            uploadedAny = true;
            current = b.field;
            currentX = b.ox;
            currentY = b.oy;
            currentZ = b.oz;
            held = b.keys;
        }
        // both bound on their units for the pass
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + TEXTURE_UNIT);
        GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, textureIds[0]);
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + TEXTURE_UNIT_PREVIOUS);
        GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, textureIds[1]);
        GlStateManager._activeTexture(previousUnit);
        return uploadedAny;
    }

    /** The unpack state is whatever the last upload left (vanilla's 16-wide lightmap every frame); the volume wants it plain. */
    private static void unpackState() {
        GlStateManager._pixelStore(GL11C.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11C.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11C.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11C.GL_UNPACK_ALIGNMENT, 4);
        GlStateManager._pixelStore(GL12C.GL_UNPACK_IMAGE_HEIGHT, 0);
        GlStateManager._pixelStore(GL12C.GL_UNPACK_SKIP_IMAGES, 0);
    }

    /** For tests: the lights the field holds now. */
    public static int lightsHeld() {
        return held.size();
    }

    public static void onLevelUnload() {
        ready.set(null);
        lastHash = Long.MIN_VALUE;
        builtFor = null;
        previousValid = false;
    }
}
