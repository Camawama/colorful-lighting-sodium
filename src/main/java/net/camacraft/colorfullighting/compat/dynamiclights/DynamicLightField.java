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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Where each dynamic light's light may go (2.10.0). Per light a cube of cells round its cell (a slab) holds, per cell, how much
 * longer the way through the blocks from the light's cell is than the straight grid way (the detour: 0 in the open, a few blocks
 * round a corner, unreachable behind a wall) and the tint picked up through filter blocks on the way; nothing of how bright. The
 * shaders and the CPU samples compute each light's own falloff at the exact point (the analytic light: round or diamond, a spot's
 * cone, at the light's position for the frame) and multiply it by the slab's answer: 1 in the open, less by the detour's share of
 * the reach left at the point, 0 where the way is too long. So in the open the light is exactly the smooth, round light of 2.8.0,
 * moving with its source every frame, and a wall still stops it, a corner still wraps and fades, glass still tints. 2.9.x stored the
 * summed light levels per cell and interpolated them, which drew a faceted light that stepped and flickered as it moved
 * (2026-11-09). A slab is rebuilt on a worker only when its light enters another cell, a spot turns more than
 * {@link #TURN_COS} allows, or the blocks under it change (the volume's cells in its box, hashed), never for a level, colour or
 * sub-block move; the propagation is a shortest-path search over the cells (six neighbours for a diamond light, twenty-six for a
 * round one or a spot, a diagonal step needing its face neighbours open), impassable through opaque cells, pruned to a spot's
 * cone plus a margin, to the slab's edge. Slabs are kept in three 3D textures by reach (up to 15, 24 and 48 blocks; units
 * {@link #TEXTURE_UNITS}), each a stack of slabs along z, uploaded whole when rebuilt. Lights whose cell is outside the block
 * volume, or beyond the slots, are left to the shaders' analytic light with nothing in the way. Sampled trilinearly half a block
 * out along the face's normal, as vanilla's smooth lighting reads the blocks in front of a face.
 */
public final class DynamicLightField {
    public static final int SIZE = VoxelOcclusion.SIZE;
    /** The slab classes: the light's cell plus this many cells each way; reaches up to 15, 24 and 48 blocks. */
    static final int[] HALF = {16, 25, 49};
    /** Slabs wanted per class (fewer when the GPU's 3D textures are too short for the stack). */
    private static final int[] WANT_SLOTS = {32, 16, 4};
    /**
     * The first of the three texture units the slabs are bound at: 24 since 2.10.3, {@code -Dcolorfullighting.slabTextureUnit}
     * for another. They were 12 to 14, and Chunks Fade In keeps its sky texture at 13: two samplers of different kinds on one
     * unit in one program, which a driver may refuse to draw with. It drew while the slabs were read by the vertices alone and
     * drew no terrain at all once the fragments read them too ({@link ShaderDynamicLights} names such a clash in the log).
     */
    public static final int FIRST_TEXTURE_UNIT = Math.max(8, Math.min(45, Integer.getInteger("colorfullighting.slabTextureUnit", 24)));
    public static final int[] TEXTURE_UNITS = {FIRST_TEXTURE_UNIT, FIRST_TEXTURE_UNIT + 1, FIRST_TEXTURE_UNIT + 2};
    /** A spot's propagation is pruned beyond its outer half angle plus this (degrees): it wraps a corner a little, never behind itself. */
    private static final double WRAP_MARGIN = Math.toRadians(30);
    /** The longest {@link #setUpdateInterval}: beyond half a second a walking light's shadows trail it by more than two blocks. */
    public static final int MAX_UPDATE_INTERVAL = 10;
    /** A spot's slab is rebuilt when its direction has turned more than this (the cosine of 12 degrees) from the one it was built with. */
    private static final double TURN_COS = Math.cos(Math.toRadians(12));
    private static final float SQRT2 = (float) Math.sqrt(2.0), SQRT3 = (float) Math.sqrt(3.0);
    /** Switch: {@code -Dcolorfullighting.slabWorker=common} builds slabs in one batch at a time on the common pool, as 2.10.2. */
    private static final boolean OWN_WORKER = !"common".equalsIgnoreCase(System.getProperty("colorfullighting.slabWorker", "own"));
    /** Switch: {@code -Dcolorfullighting.slabSubCell=false} tints a slab from its light's cell alone, rebuilt only on a new cell, as 2.10.2. */
    private static final boolean SUB_CELL = !"false".equalsIgnoreCase(System.getProperty("colorfullighting.slabSubCell", "true"));
    /** Switch: {@code -Dcolorfullighting.slabExactPrune=false} prunes a spot's slab from its cell's centre, as 2.10.2. */
    private static final boolean EXACT_PRUNE = !"false".equalsIgnoreCase(System.getProperty("colorfullighting.slabExactPrune", "true"));
    /** A light that moved this far within its cell has its slab's tint worked out again (blocks). */
    private static final double NUDGE = 0.2;
    /**
     * The fewest ticks between two rebuilds of one moving light's slab (2.10.4, {@link #setUpdateInterval}; 1: as often as the
     * light moves). {@code -Dcolorfullighting.slabUpdateTicks} is where it starts.
     */
    private static volatile int updateInterval = Math.max(1, Math.min(MAX_UPDATE_INTERVAL, Integer.getInteger("colorfullighting.slabUpdateTicks", 1)));
    /** A spot's cells this near its position are always spread from, and so are cells this near its widened cone (cells). */
    private static final double NEAR_KEEP = 2.5, SKIRT = 1.75;

    /** One light as the field takes it: world position, level (0..15, a point light's to 31 since 2.10.1), the falloff and reach, and the cone for a spot. */
    record Light(long key, double x, double y, double z, float level, int falloff, float range, boolean spot, double dx, double dy, double dz, float outerCos) {
        int cellX() { return (int) Math.floor(x); }
        int cellY() { return (int) Math.floor(y); }
        int cellZ() { return (int) Math.floor(z); }
        boolean round() { return falloff == 1 || spot; }
    }

    /** One slot of one texture, holding one light's slab. Written on the render thread; the worker reads a job's copy. */
    private static final class Slab {
        final long key;
        final int cls, slot;
        /** What the uploaded data was built for: the light's cell, its kind, a spot's direction, the blocks' build and their hash in the box. */
        int cellX = Integer.MIN_VALUE, cellY, cellZ;
        boolean round, spot;
        double dx, dy, dz;
        int blocksId = -1;
        long regionHash;
        boolean uploaded;
        boolean building;
        long lastSeen;
        /** The light as it was last gathered; a build takes it as it is when the build starts. */
        volatile Light latest;
        /** Its light is gone while a build of it waits: the build is skipped. */
        volatile boolean dropped;
        /** Where exactly the light was for the uploaded data, whether a filter block lies in its box, when it was built and how long that took. */
        double builtX, builtY, builtZ;
        boolean tinted;
        long builtTick;
        float buildMs;

        Slab(long key, int cls, int slot) {
            this.key = key;
            this.cls = cls;
            this.slot = slot;
        }

        int originX() { return cellX - HALF[cls]; }
        int originY() { return cellY - HALF[cls]; }
        int originZ() { return cellZ - HALF[cls]; }
    }

    /**
     * A rebuild to do: the slab, the light as it was when asked for, how urgent (0 a light in another cell or turned, 1 a light that
     * moved within its cell, 2 a check of the blocks alone), its place in the queue and when it was asked for.
     */
    private record Job(Slab slab, Light light, boolean checkOnly, int priority, long sequence, long asked) {}

    /**
     * A finished rebuild: the light it was built for, the slab's new data (null when the check found the blocks unchanged), the hash
     * of the blocks in its box and their build's number, whether a filter block lies in the box, the milliseconds it took.
     */
    private record Result(Job job, Light light, byte[] data, long regionHash, int blocksId, boolean tinted, float ms) {}

    private static volatile boolean enabled;
    private static final int[] textureIds = {-1, -1, -1};
    private static final int[] slots = {0, 0, 0};
    private static ByteBuffer upload;
    private static final Slab[][] bySlot = new Slab[3][];
    /** The slabs by light key: what the shaders and the CPU samples read. */
    private static final Map<Long, Slab> byKey = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<Result> results = new ConcurrentLinkedQueue<>();
    private static CompletableFuture<Void> building;
    private static ClientLevel builtFor;
    /** The mod's own slab worker (2.10.3): the most urgent and the smallest slab first, never behind another mod's work on the common pool. */
    private static final java.util.concurrent.PriorityBlockingQueue<Job> queue = new java.util.concurrent.PriorityBlockingQueue<>(64,
            java.util.Comparator.<Job>comparingInt(j -> j.priority).thenComparingInt(j -> j.slab.cls).thenComparingLong(j -> j.sequence));
    private static Thread worker;
    private static long sequence;
    /** Client ticks counted here (the game time stands still while the world is frozen). */
    private static long ticks;
    // what the builds cost, per class: full builds, their nanoseconds and the longest, checks that found nothing, the wait from asked to begun and the longest
    private static final long[][] stats = new long[3][8];
    private static boolean hashTinted;
    /** The latest slab data by key, for the CPU samples; the worker's output, swapped in on the render thread. */
    private static final Map<Long, byte[]> dataByKey = new ConcurrentHashMap<>();
    private static final ArrayDeque<byte[]>[] pool = new ArrayDeque[]{new ArrayDeque<>(), new ArrayDeque<>(), new ArrayDeque<>()};
    private static volatile Set<Long> held = java.util.Collections.emptySet();
    private static volatile int lightsHeld;

    // the worker's scratch: a local grid per slab (the shortest path length, the transmittance along it, a visit stamp) and a binary heap
    private static float[] bestPath = new float[0], transR = new float[0], transG = new float[0], transB = new float[0];
    private static int[] stamp = new int[0], done = new int[0];
    private static int generation;
    private static int[] heapIdx = new int[1024];
    private static float[] heapKey = new float[1024];
    private static int heapSize;

    private DynamicLightField() {}

    public static void setEnabled(boolean value) {
        enabled = value;
        if (!value) forgetAll();
    }

    /**
     * The fewest ticks between two rebuilds of one moving light's slab, 1 to {@link #MAX_UPDATE_INTERVAL} (2.10.4). At 1 a slab
     * follows its light every tick it enters another cell, turns or, behind a filter block, moves a fifth of a block. At more,
     * where the light may go (its shadows and the tint behind glass) follows it that many ticks apart, and the light itself, its
     * place and falloff, still moves every frame. A light's first slab, a change of its kind and a change of the blocks are
     * never held back.
     */
    public static void setUpdateInterval(int ticks) {
        updateInterval = Math.max(1, Math.min(MAX_UPDATE_INTERVAL, ticks));
    }

    public static int updateInterval() {
        return updateInterval;
    }

    public static boolean enabled() {
        return enabled;
    }

    /** Whether the shaders and the CPU samples have slabs now. */
    public static boolean active() {
        return enabled && !byKey.isEmpty();
    }

    /** Whether a light (its key from the provider) has a slab the shaders and the CPU samples read. */
    public static boolean holds(long key) {
        return held.contains(key);
    }

    /** For tests: the lights with a slab now. */
    public static int lightsHeld() {
        return lightsHeld;
    }

    /** The cells a side of each class's slabs, for the shaders. */
    public static float[] slabSizes() {
        return new float[]{2 * HALF[0] + 1, 2 * HALF[1] + 1, 2 * HALF[2] + 1};
    }

    /** The slabs stacked in each class's texture, for the shaders. */
    public static float[] slabCounts() {
        return new float[]{Math.max(1, slots[0]), Math.max(1, slots[1]), Math.max(1, slots[2])};
    }

    /**
     * A light's slab for the shaders: {@code out} takes the slab's corner relative to the camera and its class times 1000 plus its
     * slot; false (and -1 in {@code out[3]}) when the light has no uploaded slab.
     */
    public static boolean slabUniform(long key, Vec3 camera, float[] out) {
        Slab s = byKey.get(key);
        if (s == null || !s.uploaded) {
            out[3] = -1f;
            return false;
        }
        out[0] = (float) (s.originX() - camera.x);
        out[1] = (float) (s.originY() - camera.y);
        out[2] = (float) (s.originZ() - camera.z);
        out[3] = s.cls * 1000 + s.slot;
        return true;
    }

    /**
     * The CPU twin of the shaders' sample for one light at a block: {@code out} takes the share of the light left per channel (0..1)
     * after the detour the slab records for the block's cell and the tint on the way, given the reach the light has left there
     * ({@code remaining}, blocks). False when the light has no slab (nothing in the way, then). Thread-safe.
     */
    public static boolean visibility(long key, int blockX, int blockY, int blockZ, double remaining, float[] out) {
        Slab s = byKey.get(key);
        byte[] data = s == null ? null : dataByKey.get(key);
        if (s == null || data == null) return false;
        int half = HALF[s.cls], side = 2 * half + 1;
        int x = blockX - s.originX(), y = blockY - s.originY(), z = blockZ - s.originZ();
        if (x < 0 || y < 0 || z < 0 || x >= side || y >= side || z >= side) {
            out[0] = out[1] = out[2] = 1f;
            return true;
        }
        int i = ((z * side + y) * side + x) * 4;
        float detour = (data[i + 3] & 0xFF) / 255f * half;
        float share = remaining <= 1.0e-3 ? 0f : (float) Math.max(0.0, Math.min(1.0, 1.0 - detour / remaining));
        out[0] = (data[i] & 0xFF) / 255f * share;
        out[1] = (data[i + 1] & 0xFF) / 255f * share;
        out[2] = (data[i + 2] & 0xFF) / 255f * share;
        return true;
    }

    /** Once a client tick, after the level's sources were gathered: gives the nearest lights slabs and rebuilds the ones that need it. */
    static void tick(ClientLevel level, List<Light> lights) {
        if (!enabled) return;
        if (builtFor != level) {
            builtFor = level;
            forgetSlabs();
        }
        byte[] blocks = VoxelOcclusion.current();
        if (blocks == null) return;
        int[] origin = VoxelOcclusion.currentOrigin();
        int blocksId = VoxelOcclusion.buildId();
        int ox = origin[0], oy = origin[1], oz = origin[2];
        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        List<Light> inside = new ArrayList<>();
        for (Light l : lights) {
            int cx = l.cellX() - ox, cy = l.cellY() - oy, cz = l.cellZ() - oz;
            if (cx < 0 || cy < 0 || cz < 0 || cx >= SIZE || cy >= SIZE || cz >= SIZE) continue;
            inside.add(l);
        }
        inside.sort((a, b) -> Double.compare(dist2(a, cam), dist2(b, cam)));
        // the lights that get a slab this tick: the nearest of each class, up to its slots
        int[] taken = new int[3];
        Map<Long, Light> wanted = new HashMap<>();
        int[] clsOf = new int[inside.size()];
        List<Light> chosen = new ArrayList<>();
        for (Light l : inside) {
            int cls = classOf(l);
            // the textures (and so the slot counts) exist once a pass has bound them; until then no light has a slab
            if (bySlot[cls] == null || taken[cls] >= slots[cls]) continue;
            taken[cls]++;
            clsOf[chosen.size()] = cls;
            chosen.add(l);
            wanted.put(l.key, l);
        }
        long now = level.getGameTime();
        // slabs whose light is gone, or changed class, are freed
        for (Map.Entry<Long, Slab> e : new ArrayList<>(byKey.entrySet())) {
            Slab s = e.getValue();
            Light l = wanted.get(e.getKey());
            if (l != null && classOf(l) == s.cls) {
                s.lastSeen = now;
                s.dropped = false;
                continue;
            }
            if (s.building) {
                s.dropped = true; // freed once its build is back
                continue;
            }
            free(s);
        }
        ticks++;
        boolean ownWorker = OWN_WORKER;
        boolean workerIdle = ownWorker || building == null || building.isDone();
        List<Job> jobs = new ArrayList<>();
        for (int i = 0; i < chosen.size(); i++) {
            Light l = chosen.get(i);
            int cls = clsOf[i];
            Slab s = byKey.get(l.key);
            if (s == null) {
                if (bySlot[cls] == null) continue; // the textures aren't up yet (no render pass has bound them)
                int slot = freeSlot(cls);
                if (slot < 0) continue;
                s = new Slab(l.key, cls, slot);
                s.lastSeen = now;
                bySlot[cls][slot] = s;
                byKey.put(l.key, s);
            }
            s.latest = l;
            if (!workerIdle || s.building) continue;
            // a slab that has data follows its light at most every updateInterval ticks (2.10.4); its first build never waits
            boolean due = s.cellX == Integer.MIN_VALUE || ticks - s.builtTick >= updateInterval;
            boolean moved = due && (s.cellX != l.cellX() || s.cellY != l.cellY() || s.cellZ != l.cellZ());
            boolean kind = s.round != l.round() || s.spot != l.spot;
            boolean turned = due && l.spot && s.spot && (s.dx * l.dx + s.dy * l.dy + s.dz * l.dz) < TURN_COS;
            boolean blocksChanged = s.blocksId != blocksId;
            // a light that moved within its cell with a filter block in its box: the tint behind the block follows it (2.10.3), as
            // often as its slab's builds allow (a tick for a small slab, four for the largest)
            boolean nudged = false;
            if (SUB_CELL && due && s.tinted && !moved && !kind && !turned) {
                double mx = l.x - s.builtX, my = l.y - s.builtY, mz = l.z - s.builtZ;
                nudged = mx * mx + my * my + mz * mz > NUDGE * NUDGE && ticks - s.builtTick >= Math.max(1, Math.min(4, (int) Math.ceil(s.buildMs / 15f)));
            }
            if (!moved && !kind && !turned && !blocksChanged && !nudged) continue;
            // the blocks were rebuilt under a slab that otherwise stands: the worker hashes its box and rebuilds only on a change
            boolean checkOnly = !moved && !kind && !turned && !nudged && s.cellX != Integer.MIN_VALUE;
            s.building = true;
            jobs.add(new Job(s, l, checkOnly, checkOnly ? 2 : nudged ? 1 : 0, sequence++, System.nanoTime()));
        }
        Set<Long> keys = new HashSet<>();
        for (Slab s : byKey.values()) if (s.uploaded) keys.add(s.key);
        held = keys;
        lightsHeld = keys.size();
        if (jobs.isEmpty()) return;
        if (ownWorker) {
            queue.addAll(jobs);
            if (worker == null || !worker.isAlive()) {
                worker = new Thread(DynamicLightField::work, "Colorful Lighting slabs");
                worker.setDaemon(true);
                worker.setPriority(Thread.NORM_PRIORITY - 1);
                worker.start();
            }
            return;
        }
        building = CompletableFuture.runAsync(() -> {
            for (Job job : jobs) results.add(run(job));
        });
    }

    private static void work() {
        while (true) {
            Job job;
            try {
                job = queue.take();
            } catch (InterruptedException e) {
                return;
            }
            results.add(run(job));
        }
    }

    private static Result run(Job job) {
        try {
            return build(job);
        } catch (RuntimeException | Error e) {
            ColorfulLighting.LOGGER.warn("Building a dynamic light's slab failed; the light shines through everything this tick", e);
            // with the blocks' number of now, so the slab waits for a change instead of failing every tick
            return new Result(job, job.light, null, job.slab.regionHash, VoxelOcclusion.buildId(), job.slab.tinted, 0f);
        }
    }

    /**
     * For tests: per slab class, full builds, their mean and longest milliseconds, checks that found nothing, the mean and longest
     * wait before a build began, and the uploads to the texture with their mean milliseconds on the render thread.
     */
    public static String stats() {
        StringBuilder sb = new StringBuilder();
        synchronized (stats) {
            for (int c = 0; c < 3; c++) {
                long[] s = stats[c];
                long jobs = s[0] + s[3];
                sb.append(String.format(java.util.Locale.ROOT, "%sclass %d: %d builds, %.2f ms mean, %.2f ms longest, %d checks, wait %.2f ms mean, %.2f ms longest, %d uploads, %.3f ms mean", c == 0 ? "" : "; ",
                        c, s[0], s[0] == 0 ? 0.0 : s[1] / 1.0e6 / s[0], s[2] / 1.0e6, s[3], jobs == 0 ? 0.0 : s[4] / 1.0e6 / jobs, s[5] / 1.0e6,
                        s[6], s[6] == 0 ? 0.0 : s[7] / 1.0e6 / s[6]));
            }
        }
        return sb.toString();
    }

    /**
     * For tests: per slab class {builds, their nanoseconds, the longest, checks that found nothing, the nanoseconds waited before
     * builds and checks began, the longest wait, uploads, their nanoseconds on the render thread}.
     */
    public static long[][] statsRaw() {
        long[][] out = new long[3][];
        synchronized (stats) {
            for (int c = 0; c < 3; c++) out[c] = stats[c].clone();
        }
        return out;
    }

    public static void resetStats() {
        synchronized (stats) {
            for (long[] s : stats) Arrays.fill(s, 0L);
        }
    }

    /** The slab class a light's reach wants (0 to 2); a reach beyond the largest is cut to it. */
    private static int classOf(Light l) {
        int reach = (int) Math.ceil(Math.max(1f, l.range));
        if (reach <= HALF[0] - 1) return 0;
        if (reach <= HALF[1] - 1) return 1;
        return 2;
    }

    private static int freeSlot(int cls) {
        Slab[] arr = bySlot[cls];
        if (arr == null) return -1;
        for (int i = 0; i < arr.length; i++) if (arr[i] == null) return i;
        return -1;
    }

    private static void free(Slab s) {
        byKey.remove(s.key);
        byte[] data = dataByKey.remove(s.key);
        if (data != null) recycle(s.cls, data);
        if (bySlot[s.cls] != null && s.slot < bySlot[s.cls].length && bySlot[s.cls][s.slot] == s) bySlot[s.cls][s.slot] = null;
    }

    private static void recycle(int cls, byte[] data) {
        synchronized (pool) {
            if (pool[cls].size() < 4) pool[cls].push(data);
        }
    }

    private static byte[] take(int cls) {
        synchronized (pool) {
            byte[] b = pool[cls].poll();
            if (b != null) return b;
        }
        int side = 2 * HALF[cls] + 1;
        return new byte[side * side * side * 4];
    }

    private static double dist2(Light l, Vec3 cam) {
        double dx = l.x - cam.x, dy = l.y - cam.y, dz = l.z - cam.z;
        return dx * dx + dy * dy + dz * dz;
    }

    // ---- the worker ----

    private static Result build(Job job) {
        long start = System.nanoTime();
        Slab slab = job.slab;
        // the light as it is now, not as it was when the build was asked for
        Light l = slab.latest;
        if (l == null || classOf(l) != slab.cls) l = job.light;
        // a check alone stands only while the light is where the check was asked for
        boolean checkOnly = job.checkOnly && l.cellX() == job.light.cellX() && l.cellY() == job.light.cellY() && l.cellZ() == job.light.cellZ()
                && l.round() == job.light.round() && l.spot == job.light.spot;
        int cls = slab.cls, half = HALF[cls];
        // nothing changes on the slab: whatever asked for the build is asked again should the light come back
        if (slab.dropped) return new Result(job, l, null, slab.regionHash, slab.blocksId, slab.tinted, 0f);
        VoxelOcclusion.Snapshot snap = VoxelOcclusion.pinLatest();
        try {
            if (snap == null) return new Result(job, l, null, slab.regionHash, slab.blocksId, slab.tinted, 0f);
            byte[] blocks = snap.data();
            int ox = snap.x(), oy = snap.y(), oz = snap.z();
            long hash = regionHash(blocks, ox, oy, oz, l.cellX() - half, l.cellY() - half, l.cellZ() - half, 2 * half + 1);
            boolean tinted = hashTinted;
            if (checkOnly && hash == slab.regionHash) {
                synchronized (stats) {
                    stats[cls][3]++;
                    stats[cls][4] += start - job.asked;
                    stats[cls][5] = Math.max(stats[cls][5], start - job.asked);
                }
                return new Result(job, l, null, hash, snap.id(), tinted, 0f);
            }
            byte[] out = take(cls);
            propagate(l, half, blocks, ox, oy, oz, out, tinted);
            dilate(blocks, ox, oy, oz, l.cellX() - half, l.cellY() - half, l.cellZ() - half, 2 * half + 1, out);
            long took = System.nanoTime() - start;
            synchronized (stats) {
                stats[cls][0]++;
                stats[cls][1] += took;
                stats[cls][2] = Math.max(stats[cls][2], took);
                stats[cls][4] += start - job.asked;
                stats[cls][5] = Math.max(stats[cls][5], start - job.asked);
            }
            return new Result(job, l, out, hash, snap.id(), tinted, took / 1.0e6f);
        } finally {
            VoxelOcclusion.unpin();
        }
    }

    /**
     * A hash of what the volume holds in a slab's box (cells outside the volume count as air), so unchanged blocks rebuild nothing.
     * Leaves in {@link #hashTinted} whether a filter block (neither clear nor opaque) lies in the box.
     */
    private static long regionHash(byte[] blocks, int ox, int oy, int oz, int bx, int by, int bz, int side) {
        long h = 17;
        boolean filter = false;
        for (int z = 0; z < side; z++) {
            int vz = bz + z - oz;
            for (int y = 0; y < side; y++) {
                int vy = by + y - oy;
                for (int x = 0; x < side; x++) {
                    int vx = bx + x - ox;
                    int v;
                    if (vx < 0 || vy < 0 || vz < 0 || vx >= SIZE || vy >= SIZE || vz >= SIZE) v = 0xFFFFFF;
                    else {
                        int i = ((vz * SIZE + vy) * SIZE + vx) * 4;
                        v = (blocks[i] & 0xFF) << 16 | (blocks[i + 1] & 0xFF) << 8 | (blocks[i + 2] & 0xFF);
                        if (v != 0 && v != 0xFFFFFF) filter = true;
                    }
                    h = h * 31 + v;
                }
            }
        }
        hashTinted = filter;
        return h;
    }

    /**
     * Fills a light's slab: a shortest-path search over the cells from the light's cell (six neighbours for a diamond light,
     * twenty-six for a round one or a spot), impassable through opaque cells, carrying the filter transmittance met along the way,
     * out to the slab's edge. Each cell reached records the way's length over the straight grid way to it (the detour, as a share of
     * the slab's half size in the alpha byte) and the transmittance (the colour bytes); a cell never reached keeps the full detour.
     *
     * <p>Since 2.10.3 two things are measured from the light's exact place instead of its cell's centre. A spot's cells outside its
     * widened cone are written but not spread from, with a skirt of cells round the cone: pruned from the centre, up to 0.87 blocks
     * from where the drawn cone starts, the unwritten cells reached into the lit patch of a flashlight held near the ground and
     * showed as a jagged edge. And with a filter block in the box ({@code tinted}) a cell's tint is its own transmittance times the
     * tints of the cells the straight line to the light came through (the up to four cells one step back along the line's longest
     * axis, weighted by where the line crosses them), so the colour behind stained glass moves with the light inside its cell; the
     * path's own tint, which changes only when the light enters another cell, remains for cells no straight line reaches.
     */
    private static void propagate(Light l, int half, byte[] blocks, int ox, int oy, int oz, byte[] out, boolean tinted) {
        int d = 2 * half + 1;
        int cells = d * d * d;
        Arrays.fill(out, (byte) 0xFF);
        if (bestPath.length < cells) {
            bestPath = new float[cells];
            transR = new float[cells];
            transG = new float[cells];
            transB = new float[cells];
            stamp = new int[cells];
            done = new int[cells];
        }
        generation++;
        if (generation == Integer.MAX_VALUE) {
            Arrays.fill(stamp, 0);
            Arrays.fill(done, 0);
            generation = 1;
        }
        heapSize = 0;
        int sx = l.cellX(), sy = l.cellY(), sz = l.cellZ();
        int bx = sx - half, by = sy - half, bz = sz - half; // world origin of the slab
        boolean round = l.round();
        int neighbours = round ? 26 : 6;
        double pruneAngle = l.spot ? Math.min(Math.PI, Math.acos(Math.max(-1.0, Math.min(1.0, l.outerCos))) + WRAP_MARGIN) : Math.PI;
        double pruneCos = l.spot ? Math.cos(pruneAngle) : -2.0;
        boolean exactPrune = l.spot && EXACT_PRUNE;
        boolean subCell = tinted && SUB_CELL;
        // the light's exact place among the slab's cells (a cell's centre is its index and a half)
        double px = l.x - bx, py = l.y - by, pz = l.z - bz;
        int si = (half * d + half) * d + half;
        relax(si, 0f, 1f, 1f, 1f);
        while (heapSize > 0) {
            int li = pop();
            float path = bestPath[li];
            if (-heapKey[heapSize] > path + 1.0e-4f) continue; // a stale entry: the cell was reached shorter since
            int lx = li % d, ly = (li / d) % d, lz = li / (d * d);
            float free = freePath(Math.abs(lx - half), Math.abs(ly - half), Math.abs(lz - half), round);
            float detour = Math.max(0f, path - free);
            int vx = bx + lx - ox, vy = by + ly - oy, vz = bz + lz - oz;
            double wx = lx + 0.5 - px, wy = ly + 0.5 - py, wz = lz + 0.5 - pz;
            if (subCell && li != si && blendBack(lx, ly, lz, d, wx, wy, wz, blocks, vx, vy, vz)) {
                float or = 1f, og = 1f, ob = 1f;
                if (vx >= 0 && vy >= 0 && vz >= 0 && vx < SIZE && vy < SIZE && vz < SIZE) {
                    int bi = ((vz * SIZE + vy) * SIZE + vx) * 4;
                    or = (blocks[bi] & 0xFF) / 255f;
                    og = (blocks[bi + 1] & 0xFF) / 255f;
                    ob = (blocks[bi + 2] & 0xFF) / 255f;
                }
                // what spreads on from here carries it too
                transR[li] = or * blend[0];
                transG[li] = og * blend[1];
                transB[li] = ob * blend[2];
            }
            done[li] = generation;
            int oi = li * 4;
            out[oi] = (byte) Math.round(transR[li] * 255f);
            out[oi + 1] = (byte) Math.round(transG[li] * 255f);
            out[oi + 2] = (byte) Math.round(transB[li] * 255f);
            out[oi + 3] = (byte) Math.min(255, Math.round(detour / half * 255f));
            if (path >= half) continue; // the slab's edge: nothing beyond is kept
            if (exactPrune && li != si) {
                double len = Math.sqrt(wx * wx + wy * wy + wz * wz);
                if (len > NEAR_KEEP) {
                    double cos = (wx * l.dx + wy * l.dy + wz * l.dz) / len;
                    if (cos < pruneCos) {
                        // outside the widened cone: spread from only within the skirt round it
                        double over = Math.acos(Math.max(-1.0, Math.min(1.0, cos))) - pruneAngle;
                        if (over >= Math.PI / 2 || len * Math.sin(over) > SKIRT) continue;
                    }
                }
            }
            for (int n = 0; n < neighbours; n++) {
                int nx = lx + NX[n], ny = ly + NY[n], nz = lz + NZ[n];
                if (nx < 0 || ny < 0 || nz < 0 || nx >= d || ny >= d || nz >= d) continue;
                int nvx = vx + NX[n], nvy = vy + NY[n], nvz = vz + NZ[n];
                int tr8 = 255, tg8 = 255, tb8 = 255;
                if (nvx >= 0 && nvy >= 0 && nvz >= 0 && nvx < SIZE && nvy < SIZE && nvz < SIZE) {
                    int bi = ((nvz * SIZE + nvy) * SIZE + nvx) * 4;
                    tr8 = blocks[bi] & 0xFF;
                    tg8 = blocks[bi + 1] & 0xFF;
                    tb8 = blocks[bi + 2] & 0xFF;
                    if (tr8 == 0 && tg8 == 0 && tb8 == 0) continue; // opaque
                }
                // a diagonal step passes between its two or three face neighbours: both must be open, or light would cut a corner through a wall
                if (n >= 6 && !diagonalOpen(blocks, vx, vy, vz, NX[n], NY[n], NZ[n])) continue;
                if (l.spot && !exactPrune) {
                    double ndx = nx - half, ndy = ny - half, ndz = nz - half;
                    double len = Math.sqrt(ndx * ndx + ndy * ndy + ndz * ndz);
                    if (len > 1.5 && (ndx * l.dx + ndy * l.dy + ndz * l.dz) / len < pruneCos) continue;
                }
                float step = n < 6 ? 1f : n < 18 ? SQRT2 : SQRT3;
                relax((nz * d + ny) * d + nx, path + step, transR[li] * tr8 / 255f, transG[li] * tg8 / 255f, transB[li] * tb8 / 255f);
            }
        }
    }

    private static final float[] blend = new float[3];

    /**
     * The tint arriving at a cell along the straight line from the light: the cells one step back along the line's longest axis,
     * weighted by where the line crosses their plane, of those already settled and not behind a corner. False when too little of
     * the line comes through settled cells (the path's tint stands then). {@code wx, wy, wz} is the cell's centre from the light.
     */
    private static boolean blendBack(int lx, int ly, int lz, int d, double wx, double wy, double wz, byte[] blocks, int vx, int vy, int vz) {
        double ax = Math.abs(wx), ay = Math.abs(wy), az = Math.abs(wz);
        double m = Math.max(ax, Math.max(ay, az));
        if (m < 1.0e-6) return false;
        // the longest axis has the share 1, so only the cells a step back along it count
        double fx = ax / m, fy = ay / m, fz = az / m;
        int sx = wx > 0 ? -1 : 1, sy = wy > 0 ? -1 : 1, sz = wz > 0 ? -1 : 1;
        float r = 0f, g = 0f, b = 0f, sum = 0f;
        for (int k = 1; k < 8; k++) {
            double w = ((k & 1) != 0 ? fx : 1.0 - fx) * ((k & 2) != 0 ? fy : 1.0 - fy) * ((k & 4) != 0 ? fz : 1.0 - fz);
            if (w < 1.0e-3) continue;
            int ux = (k & 1) != 0 ? sx : 0, uy = (k & 2) != 0 ? sy : 0, uz = (k & 4) != 0 ? sz : 0;
            int nx = lx + ux, ny = ly + uy, nz = lz + uz;
            if (nx < 0 || ny < 0 || nz < 0 || nx >= d || ny >= d || nz >= d) continue;
            int ni = (nz * d + ny) * d + nx;
            if (done[ni] != generation) continue;
            if (Integer.bitCount(k) > 1 && !diagonalOpen(blocks, vx, vy, vz, ux, uy, uz)) continue;
            float wf = (float) w;
            r += transR[ni] * wf;
            g += transG[ni] * wf;
            b += transB[ni] * wf;
            sum += wf;
        }
        if (sum < 0.2f) return false;
        blend[0] = r / sum;
        blend[1] = g / sum;
        blend[2] = b / sum;
        return true;
    }

    /** The shortest grid way to an offset with nothing in the way: Manhattan for six neighbours; the diagonal steps for twenty-six. */
    private static float freePath(int a, int b, int c, boolean round) {
        if (!round) return a + b + c;
        // sorted a >= b >= c: c steps along three axes, b - c along two, a - b along one
        if (a < b) { int t = a; a = b; b = t; }
        if (b < c) { int t = b; b = c; c = t; }
        if (a < b) { int t = a; a = b; b = t; }
        return c * SQRT3 + (b - c) * SQRT2 + (a - b);
    }

    /** Whether a diagonal step from a cell is open: every face neighbour it slips between lets light through. */
    private static boolean diagonalOpen(byte[] blocks, int vx, int vy, int vz, int sx, int sy, int sz) {
        if (sx != 0 && !open(blocks, vx + sx, vy, vz)) return false;
        if (sy != 0 && !open(blocks, vx, vy + sy, vz)) return false;
        return sz == 0 || open(blocks, vx, vy, vz + sz);
    }

    /** Whether a volume cell lets light through; a cell outside the volume is taken as air. */
    private static boolean open(byte[] blocks, int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= SIZE || y >= SIZE || z >= SIZE) return true;
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

    /** Pops the cell with the shortest path (the heap's keys are the negated path lengths); the popped key is left at {@code heapKey[heapSize]}. */
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
     * Opaque cells take the best of their six neighbours (the shortest detour, with its tint), so a face sampled against a wall's
     * foot or a floor's edge (the shaders interpolate between the cells round the sample point) is not dragged dark by the solid
     * cell beside it, as vanilla's smooth lighting substitutes an opaque neighbour's light. Light never passes through: the written
     * value comes from the lit side's air and is read only where that air is sampled.
     */
    private static void dilate(byte[] blocks, int ox, int oy, int oz, int bx, int by, int bz, int side, byte[] out) {
        for (int z = 0; z < side; z++) {
            int vz = bz + z - oz;
            if (vz < 0 || vz >= SIZE) continue;
            for (int y = 0; y < side; y++) {
                int vy = by + y - oy;
                if (vy < 0 || vy >= SIZE) continue;
                for (int x = 0; x < side; x++) {
                    int vx = bx + x - ox;
                    if (vx < 0 || vx >= SIZE) continue;
                    int bi = ((vz * SIZE + vy) * SIZE + vx) * 4;
                    if (blocks[bi] != 0 || blocks[bi + 1] != 0 || blocks[bi + 2] != 0) continue;
                    int best = -1, bestDetour = 256;
                    for (int n = 0; n < 6; n++) {
                        int nx = x + NX[n], ny = y + NY[n], nz = z + NZ[n];
                        if (nx < 0 || ny < 0 || nz < 0 || nx >= side || ny >= side || nz >= side) continue;
                        if (!open(blocks, vx + NX[n], vy + NY[n], vz + NZ[n])) continue;
                        int j = ((nz * side + ny) * side + nx) * 4;
                        int detour = out[j + 3] & 0xFF;
                        if (detour < bestDetour) {
                            bestDetour = detour;
                            best = j;
                        }
                    }
                    if (best < 0) continue;
                    int i = ((z * side + y) * side + x) * 4;
                    out[i] = out[best];
                    out[i + 1] = out[best + 1];
                    out[i + 2] = out[best + 2];
                    out[i + 3] = out[best + 3];
                }
            }
        }
    }

    // ---- the render thread ----

    /**
     * Before a pass that samples the slabs: creates the textures when needed, takes the finished rebuilds (swapping each slab's
     * data and uploading it to its slot) and binds the three textures on their units. True when the slabs are up. Render thread.
     */
    public static boolean bind() {
        if (!enabled) return false;
        if (!RenderSystem.isOnRenderThread()) return textureIds[0] > 0;
        int previousUnit = GlStateManager._getActiveTexture();
        if (textureIds[0] <= 0) {
            int max3d = GL11C.glGetInteger(GL12C.GL_MAX_3D_TEXTURE_SIZE);
            int largest = 0;
            for (int c = 0; c < 3; c++) {
                int side = 2 * HALF[c] + 1;
                slots[c] = side > max3d ? 0 : Math.min(WANT_SLOTS[c], max3d / side);
                bySlot[c] = new Slab[slots[c]];
                if (slots[c] > 0) largest = Math.max(largest, side * side * side * 4);
            }
            upload = MemoryUtil.memAlloc(Math.max(16, largest));
            unpackState();
            for (int c = 0; c < 3; c++) {
                textureIds[c] = GL11C.glGenTextures();
                if (slots[c] == 0) continue;
                int side = 2 * HALF[c] + 1;
                GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + TEXTURE_UNITS[c]);
                GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, textureIds[c]);
                GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
                GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
                GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
                GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
                GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL12C.GL_TEXTURE_WRAP_R, GL12C.GL_CLAMP_TO_EDGE);
                GL12C.glTexImage3D(GL12C.GL_TEXTURE_3D, 0, GL11C.GL_RGBA8, side, side, side * slots[c], 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            }
            ColorfulLighting.LOGGER.info("Dynamic light slabs: {} of {} cells, {} of {}, {} of {} (3D textures up to {})", slots[0], 2 * HALF[0] + 1, slots[1],
                    2 * HALF[1] + 1, slots[2], 2 * HALF[2] + 1, max3d);
        }
        Result r;
        boolean any = false;
        while ((r = results.poll()) != null) {
            Slab s = r.job.slab;
            s.building = false;
            s.blocksId = r.blocksId;
            s.regionHash = r.regionHash;
            s.tinted = r.tinted;
            if (byKey.get(s.key) != s) {
                // freed while building
                if (r.data != null) recycle(s.cls, r.data);
                if (!byKey.containsKey(s.key) && bySlot[s.cls] != null && s.slot < bySlot[s.cls].length && bySlot[s.cls][s.slot] == s) bySlot[s.cls][s.slot] = null;
                continue;
            }
            if (r.data == null) continue; // the blocks under it were unchanged
            Light l = r.light;
            s.builtX = l.x;
            s.builtY = l.y;
            s.builtZ = l.z;
            s.builtTick = ticks;
            s.buildMs = r.ms;
            s.cellX = l.cellX();
            s.cellY = l.cellY();
            s.cellZ = l.cellZ();
            s.round = l.round();
            s.spot = l.spot;
            s.dx = l.dx;
            s.dy = l.dy;
            s.dz = l.dz;
            byte[] old = dataByKey.put(s.key, r.data);
            if (old != null) recycle(s.cls, old);
            if (!any) {
                unpackState();
                any = true;
            }
            int side = 2 * HALF[s.cls] + 1;
            GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + TEXTURE_UNITS[s.cls]);
            GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, textureIds[s.cls]);
            long uploadStart = System.nanoTime();
            upload.clear();
            upload.put(r.data, 0, side * side * side * 4).flip();
            GL12C.glTexSubImage3D(GL12C.GL_TEXTURE_3D, 0, 0, 0, s.slot * side, side, side, side, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, upload);
            s.uploaded = true;
            long uploadTook = System.nanoTime() - uploadStart;
            synchronized (stats) {
                stats[s.cls][6]++;
                stats[s.cls][7] += uploadTook;
            }
        }
        for (int c = 0; c < 3; c++) {
            if (slots[c] == 0) continue;
            GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + TEXTURE_UNITS[c]);
            GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, textureIds[c]);
        }
        GlStateManager._activeTexture(previousUnit);
        return true;
    }

    /** The unpack state is whatever the last upload left (vanilla's 16-wide lightmap every frame); the slabs want it plain. */
    private static void unpackState() {
        GlStateManager._pixelStore(GL11C.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11C.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11C.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11C.GL_UNPACK_ALIGNMENT, 4);
        GlStateManager._pixelStore(GL12C.GL_UNPACK_IMAGE_HEIGHT, 0);
        GlStateManager._pixelStore(GL12C.GL_UNPACK_SKIP_IMAGES, 0);
    }

    private static void forgetSlabs() {
        queue.clear();
        for (Slab s : new ArrayList<>(byKey.values())) free(s);
        byKey.clear();
        dataByKey.clear();
        for (int c = 0; c < 3; c++) if (bySlot[c] != null) Arrays.fill(bySlot[c], null);
        held = java.util.Collections.emptySet();
        lightsHeld = 0;
    }

    private static void forgetAll() {
        forgetSlabs();
        results.clear();
    }

    public static void onLevelUnload() {
        forgetAll();
        builtFor = null;
    }
}
