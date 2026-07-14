package me.erykczy.colorfullighting.common;

import me.erykczy.colorfullighting.ColorfulLighting;
import me.erykczy.colorfullighting.common.accessors.BlockStateAccessor;
import me.erykczy.colorfullighting.common.accessors.ClientAccessor;
import me.erykczy.colorfullighting.common.accessors.LevelAccessor;
import me.erykczy.colorfullighting.common.accessors.PlayerAccessor;
import me.erykczy.colorfullighting.common.util.ColorRGB4;
import me.erykczy.colorfullighting.common.util.ColorRGB8;
import me.erykczy.colorfullighting.common.util.MathExt;
import me.erykczy.colorfullighting.common.util.ShapeOcclusion;
import me.erykczy.colorfullighting.compat.dynamiclights.DynamicLightsCompat;
import me.erykczy.colorfullighting.compat.flywheel.FlywheelCompat;
import me.erykczy.colorfullighting.compat.oculus.OculusCompat;
import me.erykczy.colorfullighting.compat.sodium.SodiumCompat;
import me.erykczy.colorfullighting.mixin.compat.sodium.SodiumWorldRendererAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Class responsible for managing light color values in the client's world and sampling those values.
 * Most work is delegated to LightPropagator thread.
 */
public class ColoredLightEngine {
    private ClientAccessor clientAccessor;
    private final ColoredLightStorage storage = new ColoredLightStorage();
    private final ColoredLightStorage darknessStorage = new ColoredLightStorage();
    /**
     * Guards writers against each other only. Sampling never takes it: the storages are concurrent maps
     * of volatile-published sections, so readers race with the propagator by design and lose at worst a
     * single frame of colour on a block that is already queued for a re-mesh. Taking this lock per sample
     * serialised all ten Sodium chunk-build workers behind one monitor.
     */
    private final Object storageLock = new Object();
    /**
     * Bumped after sections are added to or removed from either storage. Sampling threads cache the
     * section they last touched and discard that cache when this moves, so a section swapped out from
     * under a reader is never read for more than one sample. Bumped from both the client thread
     * (updateViewArea) and the propagator thread (performRegionRebuild), hence atomic.
     */
    private final AtomicInteger structureVersion = new AtomicInteger();
    private final ThreadLocal<SectionCursor> sectionCursor = ThreadLocal.withInitial(SectionCursor::new);
    private ViewArea viewArea = new ViewArea();
    /**
     * Extra light regions beyond the player's view area, keyed by owner (e.g. a Valkyrien Skies ship
     * id). Written and read on the client thread only, like viewArea. Regions are assumed to lie far
     * from the view area and from each other (VS chunk claims are disjoint), so overlap is only
     * guarded where stored data would be clobbered.
     */
    private final Map<Long, LightRegion> extraRegions = new HashMap<>();
    /**
     * The extra regions' areas, republished on every change for threads that must not touch the map:
     * the propagator's ChunkOrder reads this to prioritise region chunks the way it prioritises
     * frustum-visible ones (a ship is usually on screen even though its shipyard chunks never are).
     */
    private volatile ViewArea[] extraRegionAreas = new ViewArea[0];
    private final ConcurrentLinkedQueue<LightUpdateRequest> blockUpdateDecreaseRequests = new ConcurrentLinkedQueue<>(); // those first added will be executed first (this order is required by decrease propagation algorithm)
    private final ConcurrentLinkedQueue<BlockRequests> blockUpdateIncreaseRequests = new ConcurrentLinkedQueue<>(); // those nearest to the player will be executed first
    private final ConcurrentLinkedQueue<LightUpdateRequest> darknessUpdateDecreaseRequests = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<BlockRequests> darknessUpdateIncreaseRequests = new ConcurrentLinkedQueue<>();
    // Sets, not queues: ConcurrentLinkedQueue.remove is O(n) and ran once per propagated chunk.
    // Ordering now comes from LightPropagator.ChunkOrder instead of rescanning the collection.
    private final Set<ChunkPos> chunksWaitingForPropagation = ConcurrentHashMap.newKeySet();
    private final Set<ChunkPos> chunksWaitingForDarknessPropagation = ConcurrentHashMap.newKeySet();
    /**
     * Chunks already queued for this view area. Replaces the old "skip anything in the previous inner
     * area" rule, which could never re-queue a chunk that was skipped because the server had not sent
     * its neighbours yet. Client thread only (updateViewArea and reset).
     */
    private final Set<ChunkPos> queuedChunks = new HashSet<>();
    /**
     * Primitive: this fills and drains fast enough during chunk loading that boxing a Long per section
     * showed up on the render thread.
     */
    private final LongOpenHashSet dirtySections = new LongOpenHashSet();
    private final Set<Long> sectionsToRebuildLater = ConcurrentHashMap.newKeySet();

    private final ConcurrentLinkedQueue<DelayedChunkUpdate> delayedChunkUpdates = new ConcurrentLinkedQueue<>();
    private final Set<ChunkPos> pendingDelayedUpdates = ConcurrentHashMap.newKeySet();

    private LightPropagator lightPropagator;
    private Thread lightPropagatorThread;
    
    // volatile: read from Sodium chunk-build worker threads and the light propagator thread
    private volatile boolean enabled = true;
    private boolean packsInitialized = false;

    private static final String CORE_SHADER_PACK_ID = ColorfulLighting.CORE_SHADER_PACK_ID;

    /** How long a computed chunk ordering stays usable before it is rebuilt. */
    private static final long CHUNK_ORDER_TTL_NANOS = 100_000_000L; // 100 ms

    /**
     * Only report drains at least this large. Moving queues a strip of chunks every second or so, and
     * logging each one buries the log; the rate is meaningless for a handful of chunks anyway, since
     * the timer includes however long the queue waited on chunk data rather than time spent working.
     */
    private static final int DRAIN_LOG_MIN_CHUNKS = 256;

    private static final long IDLE_SLEEP_MILLIS = 10L;
    private static final long MIN_BLOCKED_SLEEP_MILLIS = 5L;
    /**
     * Deliberately short. The chunk queue never empties: ViewArea is a square, but since 1.18 the server
     * only sends chunks inside a disc (see ChunkMap.isChunkInRange). Chunks in the square-but-not-disc
     * corners, and their inward neighbours, can never have all eight neighbours loaded, so they stay
     * queued for the whole session and the propagator lives in the backed-off branch. Measured at render
     * distance 24: 336 of the 2209 queued chunks are permanently unpropagatable.
     *
     * <p>A block update arriving mid-sleep waits that sleep out, so a long backoff would make every
     * placed torch light up late. 20ms is under half a tick and still removes three quarters of the
     * wakeups.
     */
    private static final long MAX_BLOCKED_SLEEP_MILLIS = 20L;

    // volatile: written on the render thread each frame, read on the light propagator thread
    private volatile Frustum frustum;

    private static ColoredLightEngine instance;
    public static ColoredLightEngine getInstance() {
        return instance;
    }
    public static void create(ClientAccessor clientAccessor) {
        instance = new ColoredLightEngine(clientAccessor);
    }

    private ColoredLightEngine(ClientAccessor clientAccessor) {
        this.clientAccessor = clientAccessor;
        reset();
    }
    
    public void setEnabled(boolean enabled) {
        if (this.enabled != enabled) {
            this.enabled = enabled;
            ColorfulLightingConfig.ENABLED.set(enabled);
            ColorfulLightingConfig.save();
            reset();
            updateShaderPack();
			
			if (OculusCompat.isOculusLoaded())
				OculusCompat.reloadPack();

            // Flywheel keeps colored light in its own GPU buffers, refreshed only through
            // onLightUpdate (which is gated on 'enabled'). Recollect everything it tracks so
            // the buffers immediately reflect the new state instead of freezing stale light.
            if (net.minecraftforge.fml.ModList.get().isLoaded("flywheel") && FlywheelCompat.isAvailable()) {
                FlywheelCompat.getInstance().flywheelColoredLightStorage.recollectAllTracked();
            }
        }
    }
    
    public boolean isEnabled() {
        return enabled;
    }
    
    public void onPacksInitialized() {
        packsInitialized = true;
        updateShaderPack();
    }

    private void updateShaderPack() {
        if (!packsInitialized) return;
        Minecraft mc = Minecraft.getInstance();
        PackRepository repo = mc.getResourcePackRepository();
        // The pack stays installed even while the engine is disabled. Chunk meshes built while
        // the engine was on carry light data in the packed colored vertex format, and Sodium
        // only rebuilds sections progressively — the stock shaders misdecode those stale
        // vertices as garbage (often full-bright) light. The patched shaders understand both
        // formats and render plain vanilla lighting when u_ColoredLightingEnabled is 0, so
        // toggling needs no shader swap (and therefore no resource reload) at all.
        if (repo.getPack(CORE_SHADER_PACK_ID) != null && !repo.getSelectedIds().contains(CORE_SHADER_PACK_ID)) {
            repo.addPack(CORE_SHADER_PACK_ID);
            mc.reloadResourcePacks();
        }
    }

    public void updateFrustum(Frustum frustum) {
        this.frustum = frustum;
    }

    /**
     * Per-thread memo of the last section sampled. Sodium walks a chunk build in section order and takes
     * roughly ten samples per block face, nearly all of them landing in the section already cached here,
     * so this turns two concurrent-map lookups per sample into two per section.
     */
    public static final class SectionCursor {
        private long sectionPos = Long.MIN_VALUE;
        private int version = -1;
        private ColoredLightSection light;
        private ColoredLightSection darkness;
        private final BlockPos.MutableBlockPos fallbackPos = new BlockPos.MutableBlockPos();
    }

    /**
     * Fetch once and pass to {@link #sampleLightColorPacked(SectionCursor, int, int, int)} for a run of
     * nearby samples. The returned cursor belongs to the calling thread and must not outlive the call
     * that acquired it or cross to another thread.
     */
    public SectionCursor acquireCursor() {
        return sectionCursor.get();
    }

    public ColorRGB4 sampleLightColor(BlockPos pos) { return sampleLightColor(pos.getX(), pos.getY(), pos.getZ()); }
    public ColorRGB4 sampleLightColor(int x, int y, int z) {
        int packed = sampleLightColorPacked(x, y, z);
        if (packed == 0) return ColorRGB4.BLACK;
        return ColorRGB4.fromRGB4((packed >>> 8) & 0x0F, (packed >>> 4) & 0x0F, packed & 0x0F);
    }

    public int sampleLightColorPacked(int x, int y, int z) {
        return sampleLightColorPacked(sectionCursor.get(), x, y, z);
    }

    /**
     * Allocation-free equivalent of {@link #sampleLightColor(int, int, int)}.
     *
     * @param cursor from {@link #acquireCursor()}, so a caller taking many samples pays one ThreadLocal
     *               lookup rather than one per sample.
     * @return light minus darkness as a packed 12-bit {@code r << 8 | g << 4 | b}.
     */
    public int sampleLightColorPacked(SectionCursor cursor, int x, int y, int z) {
        if (!enabled) return 0;

        long sectionPos = SectionPos.asLong(x >> 4, y >> 4, z >> 4);
        int version = this.structureVersion.get();

        if (cursor.sectionPos != sectionPos || cursor.version != version) {
            cursor.light = storage.getSection(sectionPos);
            cursor.darkness = darknessStorage.getSection(sectionPos);
            cursor.sectionPos = sectionPos;
            cursor.version = version;
        }

        int colorIndex = ColoredLightSection.getColorIndex(x & 15, y & 15, z & 15);
        int light;
        int darkness;
        if (cursor.light == null && cursor.darkness == null) {
            // No stored section: the position is outside every region the engine tracks. Chunks can
            // legitimately be meshed there — Valkyrien Skies ships live in shipyard chunks millions of
            // blocks from the player, so they can never enter the view area. Colored light never
            // propagates there, but vanilla block light does; render it as white instead of black so
            // such chunks keep their vanilla lighting rather than losing block light entirely.
            light = vanillaBlockLightAsWhitePacked(cursor, x, y, z);
            darkness = 0;
        } else {
            light = cursor.light == null ? 0 : cursor.light.getPacked(colorIndex);
            darkness = cursor.darkness == null ? 0 : cursor.darkness.getPacked(colorIndex);
        }

        // held/dropped-item light from renderer-based dynamic lighting mods (no-op without sources);
        // applied before the darkness subtraction so darkness absorbers dampen it like any other light
        light = DynamicLightsCompat.maxWithDynamicLightPacked(x, y, z, light);

        if (light == 0 || light == darkness) return 0;
        if (darkness == 0) return light;

        int red = Math.max(0, ((light >>> 8) & 0x0F) - ((darkness >>> 8) & 0x0F));
        int green = Math.max(0, ((light >>> 4) & 0x0F) - ((darkness >>> 4) & 0x0F));
        int blue = Math.max(0, (light & 0x0F) - (darkness & 0x0F));
        return red << 8 | green << 4 | blue;
    }

    /**
     * Vanilla block light at the position, as a packed white 12-bit colour. Reading the client light
     * engine from Sodium's chunk-build workers matches what vanilla meshing does (RenderChunkRegion
     * reads it from workers too), so it is as thread-safe as vanilla itself. Positions outside the
     * build height keep returning 0, same as the missing-section behaviour this falls back from.
     */
    private static int vanillaBlockLightAsWhitePacked(SectionCursor cursor, int x, int y, int z) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || level.isOutsideBuildHeight(y)) return 0;
        int brightness = level.getBrightness(LightLayer.BLOCK, cursor.fallbackPos.set(x, y, z));
        if (brightness <= 0) return 0;
        return brightness << 8 | brightness << 4 | brightness;
    }
    /**
     * Mixes light color from blocks neighbouring given position using trilinear interpolation.
     */
    public ColorRGB8 sampleTrilinearLightColor(Vec3 pos) {
        if (!enabled) return ColorRGB8.fromRGB4(ColorRGB4.BLACK);
        int cornerX = (int)Math.floor(pos.x);
        int cornerY = (int)Math.floor(pos.y);
        int cornerZ = (int)Math.floor(pos.z);

        ColorRGB8 c000 = ColorRGB8.fromRGB4(sampleLightColor(cornerX, cornerY, cornerZ));
        ColorRGB8 c100 = ColorRGB8.fromRGB4(sampleLightColor(cornerX + 1, cornerY, cornerZ));
        ColorRGB8 c101 = ColorRGB8.fromRGB4(sampleLightColor(cornerX + 1, cornerY, cornerZ + 1));
        ColorRGB8 c001 = ColorRGB8.fromRGB4(sampleLightColor(cornerX, cornerY, cornerZ + 1));
        ColorRGB8 c010 = ColorRGB8.fromRGB4(sampleLightColor(cornerX, cornerY + 1, cornerZ));
        ColorRGB8 c110 = ColorRGB8.fromRGB4(sampleLightColor(cornerX + 1, cornerY + 1, cornerZ));
        ColorRGB8 c111 = ColorRGB8.fromRGB4(sampleLightColor(cornerX + 1, cornerY + 1, cornerZ + 1));
        ColorRGB8 c011 = ColorRGB8.fromRGB4(sampleLightColor(cornerX, cornerY + 1, cornerZ + 1));

        double x = pos.x - cornerX;
        double y = pos.y - cornerY;
        double z = pos.z - cornerZ;

        ColorRGB8 c00 = ColorRGB8.linearInterpolation(c000, c100, x);
        ColorRGB8 c10 = ColorRGB8.linearInterpolation(c010, c110, x);
        ColorRGB8 c01 = ColorRGB8.linearInterpolation(c001, c101, x);
        ColorRGB8 c11 = ColorRGB8.linearInterpolation(c011, c111, x);

        ColorRGB8 c0 = ColorRGB8.linearInterpolation(c00, c10, y);
        ColorRGB8 c1 = ColorRGB8.linearInterpolation(c01, c11, y);

        return ColorRGB8.linearInterpolation(c0, c1, z);
    }


    /**
     * Whether every neighbour of this chunk is one the server will send, so the chunk can eventually
     * propagate.
     *
     * <p>ViewArea is a square, but since 1.18 the server sends chunks inside a disc
     * ({@link ChunkMap#isChunkInRange}). Propagating a chunk needs all eight of its neighbours loaded,
     * so chunks near the square's corners could never propagate: they sat in the queue for the entire
     * session, kept it permanently non-empty, and were re-sorted by every ChunkOrder rebuild. Measured
     * at render distance 24, 336 of the 2209 queued chunks were unpropagatable.
     *
     * <p>Callers pass the client's effective render distance. When a server's view distance is larger
     * this is conservative and skips a sliver at the square's corners - chunks the renderer culls by
     * euclidean distance anyway, so nothing visible is lost.
     */
    private static boolean canEverPropagate(int centerX, int centerZ, int viewDistance, int x, int z) {
        for (int dx = -1; dx <= 1; ++dx) {
            for (int dz = -1; dz <= 1; ++dz) {
                if (!ChunkMap.isChunkInRange(x + dx, z + dz, centerX, centerZ, viewDistance)) return false;
            }
        }
        return true;
    }

    public void updateViewArea(ViewArea newArea) {
        if (!enabled) return;
        LevelAccessor level = clientAccessor.getLevel();
        if(level == null) return;
        if(viewArea.equals(newArea)) return;

        // unload sections
        // remove propagation requests which are not in newArea's inner area (or an extra region's)
        blockUpdateIncreaseRequests.removeIf(blockUpdate -> !newArea.containsBlockInner(blockUpdate.blockPos) && !extraRegionsContainBlockInner(blockUpdate.blockPos));
        blockUpdateDecreaseRequests.removeIf(blockUpdate -> !newArea.containsBlockInner(blockUpdate.blockPos) && !extraRegionsContainBlockInner(blockUpdate.blockPos));
        darknessUpdateIncreaseRequests.removeIf(blockUpdate -> !newArea.containsBlockInner(blockUpdate.blockPos) && !extraRegionsContainBlockInner(blockUpdate.blockPos));
        darknessUpdateDecreaseRequests.removeIf(blockUpdate -> !newArea.containsBlockInner(blockUpdate.blockPos) && !extraRegionsContainBlockInner(blockUpdate.blockPos));
        chunksWaitingForPropagation.removeIf(chunkPos -> !newArea.containsInner(chunkPos.x, chunkPos.z) && !extraRegionsContainInner(chunkPos.x, chunkPos.z));
        chunksWaitingForDarknessPropagation.removeIf(chunkPos -> !newArea.containsInner(chunkPos.x, chunkPos.z) && !extraRegionsContainInner(chunkPos.x, chunkPos.z));
        queuedChunks.removeIf(chunkPos -> !newArea.containsInner(chunkPos.x, chunkPos.z) && !extraRegionsContainInner(chunkPos.x, chunkPos.z));
        // remove sections from storage
        synchronized (storageLock) {
            for(int x = viewArea.minX; x <= viewArea.maxX; ++x) {
                for(int z = viewArea.minZ; z <= viewArea.maxZ; ++z) {
                    if(newArea.contains(x, z)) continue;
                    if(extraRegionsContain(x, z)) continue;
                    for(int y = level.getMinSectionY(); y <= level.getMaxSectionY(); y++) {
                        long sectionPos = SectionPos.asLong(x, y, z);
                        storage.removeSection(sectionPos);
                        darknessStorage.removeSection(sectionPos);
                    }
                }
            }
        }
        structureVersion.incrementAndGet();

        // load sections
        // add sections to storage and queue chunks for propagation
        // ViewArea is centred on the player and spans +/- the effective render distance.
        int centerX = (newArea.minX + newArea.maxX) / 2;
        int centerZ = (newArea.minZ + newArea.maxZ) / 2;
        int viewDistance = (newArea.maxX - newArea.minX) / 2;
        synchronized (storageLock) {
            for(int x = newArea.minX; x <= newArea.maxX; ++x) {
                for(int z = newArea.minZ; z <= newArea.maxZ; ++z) {
                    if(!viewArea.contains(x, z)) { // section data is not carried over from the old area
                        for(int y = level.getMinSectionY(); y <= level.getMaxSectionY(); y++) {
                            long pos = SectionPos.asLong(x, y, z);
                            storage.addSection(pos);
                            darknessStorage.addSection(pos);
                        }
                    }
                    if(newArea.containsInner(x, z) && canEverPropagate(centerX, centerZ, viewDistance, x, z)) {
                        ChunkPos chunkPos = new ChunkPos(x, z);
                        if(queuedChunks.add(chunkPos)) { // not already queued or propagated for this area
                            chunksWaitingForPropagation.add(chunkPos);
                            chunksWaitingForDarknessPropagation.add(chunkPos);
                        }
                    }
                }
            }
        }
        structureVersion.incrementAndGet();
        viewArea = newArea;
    }

    /**
     * A rectangle of chunks to keep colored light data for, plus the chunks inside it that actually
     * contain blocks and therefore need propagation (for a ship: its active chunk set). Chunks in the
     * area but not queued act like ViewArea's border: they hold data written by propagation from the
     * queued chunks but are never propagated themselves.
     */
    public record LightRegion(ViewArea area, Set<ChunkPos> chunksToQueue) {}

    /**
     * Reconciles the extra regions with {@code desired}. Callers may pass the same LightRegion
     * instances every tick; unchanged regions are skipped by identity before equality. Client thread
     * only, like {@link #updateViewArea}.
     */
    public void syncExtraRegions(Map<Long, LightRegion> desired) {
        if (!enabled) return;
        LevelAccessor level = clientAccessor.getLevel();
        if (level == null) return;
        if (extraRegions.isEmpty() && desired.isEmpty()) return;

        boolean changed = false;
        for (Long key : List.copyOf(extraRegions.keySet())) {
            LightRegion target = desired.get(key);
            LightRegion current = extraRegions.get(key);
            if (current == target || current.equals(target)) continue;
            applyRegionChange(level, key, target);
            changed = true;
        }
        for (Map.Entry<Long, LightRegion> entry : desired.entrySet()) {
            if (!extraRegions.containsKey(entry.getKey())) {
                applyRegionChange(level, entry.getKey(), entry.getValue());
                changed = true;
            }
        }
        if (changed) {
            extraRegionAreas = extraRegions.values().stream().map(LightRegion::area).toArray(ViewArea[]::new);
        }
    }

    private void applyRegionChange(LevelAccessor level, Long key, LightRegion target) {
        LightRegion current = extraRegions.get(key);
        ViewArea oldArea = current == null ? null : current.area();
        ViewArea newArea = target == null ? null : target.area();

        // Update the map first so the tracked-elsewhere checks below see the final state.
        if (target == null) extraRegions.remove(key);
        else extraRegions.put(key, target);

        if (oldArea != null && !oldArea.equals(newArea)) {
            // Drop pending work for chunks that left every tracked area.
            blockUpdateIncreaseRequests.removeIf(update -> oldArea.containsBlockInner(update.blockPos) && !isBlockTrackedInner(update.blockPos));
            blockUpdateDecreaseRequests.removeIf(update -> oldArea.containsBlockInner(update.blockPos) && !isBlockTrackedInner(update.blockPos));
            darknessUpdateIncreaseRequests.removeIf(update -> oldArea.containsBlockInner(update.blockPos) && !isBlockTrackedInner(update.blockPos));
            darknessUpdateDecreaseRequests.removeIf(update -> oldArea.containsBlockInner(update.blockPos) && !isBlockTrackedInner(update.blockPos));
            chunksWaitingForPropagation.removeIf(chunkPos -> oldArea.containsInner(chunkPos.x, chunkPos.z) && !isChunkTrackedInner(chunkPos.x, chunkPos.z));
            chunksWaitingForDarknessPropagation.removeIf(chunkPos -> oldArea.containsInner(chunkPos.x, chunkPos.z) && !isChunkTrackedInner(chunkPos.x, chunkPos.z));
            queuedChunks.removeIf(chunkPos -> oldArea.containsInner(chunkPos.x, chunkPos.z) && !isChunkTrackedInner(chunkPos.x, chunkPos.z));

            synchronized (storageLock) {
                for (int x = oldArea.minX; x <= oldArea.maxX; ++x) {
                    for (int z = oldArea.minZ; z <= oldArea.maxZ; ++z) {
                        if (isColumnTracked(x, z)) continue;
                        for (int y = level.getMinSectionY(); y <= level.getMaxSectionY(); y++) {
                            long sectionPos = SectionPos.asLong(x, y, z);
                            storage.removeSection(sectionPos);
                            darknessStorage.removeSection(sectionPos);
                        }
                    }
                }
            }
            structureVersion.incrementAndGet();
        }

        if (target != null) {
            if (!newArea.equals(oldArea)) {
                synchronized (storageLock) {
                    for (int x = newArea.minX; x <= newArea.maxX; ++x) {
                        for (int z = newArea.minZ; z <= newArea.maxZ; ++z) {
                            if (oldArea != null && oldArea.contains(x, z)) continue;
                            if (viewArea.contains(x, z)) continue; // addSection overwrites; never clobber the view area
                            for (int y = level.getMinSectionY(); y <= level.getMaxSectionY(); y++) {
                                long sectionPos = SectionPos.asLong(x, y, z);
                                storage.addSection(sectionPos);
                                darknessStorage.addSection(sectionPos);
                            }
                        }
                    }
                }
                structureVersion.incrementAndGet();
            }
            for (ChunkPos chunkPos : target.chunksToQueue()) {
                if (!newArea.containsInner(chunkPos.x, chunkPos.z)) continue;
                if (queuedChunks.add(chunkPos)) {
                    chunksWaitingForPropagation.add(chunkPos);
                    chunksWaitingForDarknessPropagation.add(chunkPos);
                }
            }
        }
    }

    private static boolean inAnyArea(ViewArea[] areas, int x, int z) {
        for (ViewArea area : areas) {
            if (area.contains(x, z)) return true;
        }
        return false;
    }

    private boolean extraRegionsContain(int x, int z) {
        if (extraRegions.isEmpty()) return false;
        for (LightRegion region : extraRegions.values()) {
            if (region.area().contains(x, z)) return true;
        }
        return false;
    }

    private boolean extraRegionsContainInner(int x, int z) {
        if (extraRegions.isEmpty()) return false;
        for (LightRegion region : extraRegions.values()) {
            if (region.area().containsInner(x, z)) return true;
        }
        return false;
    }

    private boolean extraRegionsContainBlockInner(BlockPos pos) {
        return extraRegionsContainInner(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
    }

    /** Whether the chunk is an inner (actively updated) chunk of the view area or any extra region. */
    private boolean isChunkTrackedInner(int x, int z) {
        return viewArea.containsInner(x, z) || extraRegionsContainInner(x, z);
    }

    private boolean isBlockTrackedInner(BlockPos pos) {
        return isChunkTrackedInner(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
    }

    /** Whether the section column is held by the view area (incl. border) or any extra region. */
    private boolean isColumnTracked(int x, int z) {
        return viewArea.contains(x, z) || extraRegionsContain(x, z);
    }

    public void onBlockLightPropertiesChanged(BlockPos blockPos) {
        if (!enabled) return;
        LevelAccessor level = clientAccessor.getLevel();
        if (level == null) return;

        SectionPos sectionPos = SectionPos.of(blockPos);
        if (!isChunkTrackedInner(sectionPos.x(), sectionPos.z())) return;

        BlockRequests increaseRequests = new BlockRequests(blockPos);
        handleBlockUpdate(level, increaseRequests.increaseRequests, blockUpdateDecreaseRequests, blockPos);
        if (!increaseRequests.increaseRequests.isEmpty()) {
            blockUpdateIncreaseRequests.add(increaseRequests);
        }

        BlockRequests darknessIncreaseRequests = new BlockRequests(blockPos);
        handleDarknessUpdate(level, darknessIncreaseRequests.increaseRequests, darknessUpdateDecreaseRequests, blockPos);
        if (!darknessIncreaseRequests.increaseRequests.isEmpty()) {
            darknessUpdateIncreaseRequests.add(darknessIncreaseRequests);
        }
    }

    private void handleBlockUpdate(LevelAccessor level, Queue<LightUpdateRequest> increaseRequests, Queue<LightUpdateRequest> decreaseRequests, BlockPos blockPos) {
        ColorRGB4 lightColor = storage.getEntry(blockPos);
        if (lightColor == null) lightColor = ColorRGB4.fromRGB4(0,0,0);

        if(lightColor.red4 == 0 && lightColor.green4 == 0 && lightColor.blue4 == 0)
            requestLightPullIn(increaseRequests, blockPos);  // block probably destroyed/replaced with transparent, light pull in might be needed
        else
            decreaseRequests.add(new LightUpdateRequest(blockPos, lightColor, false)); // block probably placed/replaced with non-transparent, light might need to be decreased

        // propagate light if new blockState emits light (single lookup for both brightness and color)
        BlockStateAccessor blockState = level.getBlockState(blockPos);
        if (blockState != null && Config.getEmissionBrightness(level, blockPos, blockState) > 0)
            increaseRequests.add(new LightUpdateRequest(blockPos, Config.getColorEmission(level, blockPos, blockState), false, true, false));
    }

    private void handleDarknessUpdate(LevelAccessor level, Queue<LightUpdateRequest> increaseRequests, Queue<LightUpdateRequest> decreaseRequests, BlockPos blockPos) {
        ColorRGB4 darknessColor = darknessStorage.getEntry(blockPos);
        if (darknessColor == null) darknessColor = ColorRGB4.fromRGB4(0,0,0);

        if(darknessColor.red4 == 0 && darknessColor.green4 == 0 && darknessColor.blue4 == 0)
            requestDarknessPullIn(increaseRequests, blockPos);
        else
            decreaseRequests.add(new LightUpdateRequest(blockPos, darknessColor, false));

        // propagate darkness if new blockState absorbs light (single lookup)
        BlockStateAccessor blockState = level.getBlockState(blockPos);
        if (blockState != null && Config.getAbsorption(level, blockPos, blockState) > 0)
            increaseRequests.add(new LightUpdateRequest(blockPos, Config.getAbsorptionColor(level, blockPos, blockState), false, true, false));
    }

    private void requestLightPullIn(Queue<LightUpdateRequest> increaseRequests, BlockPos blockPos) {
        for(var direction : Direction.values()) {
            BlockPos neighbourPos = blockPos.relative(direction);
            ColorRGB4 neighbourLight = storage.getEntry(neighbourPos);
            if(neighbourLight == null) continue;

            if(neighbourLight.red4 == 0 && neighbourLight.green4 == 0 && neighbourLight.blue4 == 0) continue;
            increaseRequests.add(new LightUpdateRequest(neighbourPos, null, true, false, true));
        }
    }

    private void requestDarknessPullIn(Queue<LightUpdateRequest> increaseRequests, BlockPos blockPos) {
        for(var direction : Direction.values()) {
            BlockPos neighbourPos = blockPos.relative(direction);
            ColorRGB4 neighbourDarkness = darknessStorage.getEntry(neighbourPos);
            if(neighbourDarkness == null) continue;

            if(neighbourDarkness.red4 == 0 && neighbourDarkness.green4 == 0 && neighbourDarkness.blue4 == 0) continue;
            increaseRequests.add(new LightUpdateRequest(neighbourPos, null, true, false, true));
        }
    }

    public void onLightUpdate() {
        if (!enabled) return;
        LevelAccessor level = clientAccessor.getLevel();
        if(level == null) return;

        lightPropagator.applyReadyChanges();

        long[] sectionsToUpdate;
        synchronized (dirtySections) {
            if (dirtySections.isEmpty()) {
                return;
            }
            sectionsToUpdate = dirtySections.toLongArray();
            dirtySections.clear();
        }

        // Hoisted out of the loop below: it runs once per dirty section per frame, and flying through
        // fresh terrain makes that thousands of iterations. ModList.isLoaded hashes a string every call.
        SodiumWorldRendererAccessor sodiumRenderer = null;
        if (SodiumCompat.isSodiumLoaded() && Minecraft.getInstance().levelRenderer instanceof SodiumWorldRendererAccessor accessor) {
            sodiumRenderer = accessor;
        }
        boolean flywheelTracking = net.minecraftforge.fml.ModList.get().isLoaded("flywheel") && FlywheelCompat.isAvailable();

        for (long dirtySection : sectionsToUpdate) {
            int sectionX = SectionPos.x(dirtySection);
            int sectionY = SectionPos.y(dirtySection);
            int sectionZ = SectionPos.z(dirtySection);
            level.setSectionDirty(sectionX, sectionY, sectionZ);

            // Force Sodium rebuild if present
            if (sodiumRenderer != null) {
                sodiumRenderer.scheduleRebuild(sectionX, sectionY, sectionZ, true);
            }

            if (flywheelTracking) {
                FlywheelCompat.getInstance().flywheelColoredLightStorage.recollectSectionIfTracked(dirtySection);
            }
        }
    }
    
    public void rebuildChunk(ChunkPos chunkPos) {
        rebuildChunk(chunkPos, 0);
    }

    public void rebuildChunk(ChunkPos chunkPos, long delay) {
        if (!enabled) return;
        if (pendingDelayedUpdates.add(chunkPos)) {
            delayedChunkUpdates.add(new DelayedChunkUpdate(chunkPos, System.currentTimeMillis() + delay));
        }
    }

    public void reset() {
        if(lightPropagator != null) {
            lightPropagator.stop();
            try {
                lightPropagatorThread.join();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        storage.clear();
        darknessStorage.clear();
        structureVersion.incrementAndGet();
        viewArea = new ViewArea();
        extraRegions.clear(); // region owners (e.g. VS compat) re-sync them on the next tick
        extraRegionAreas = new ViewArea[0];
        dirtySections.clear();
        blockUpdateIncreaseRequests.clear();
        blockUpdateDecreaseRequests.clear();
        darknessUpdateIncreaseRequests.clear();
        darknessUpdateDecreaseRequests.clear();
        chunksWaitingForPropagation.clear();
        chunksWaitingForDarknessPropagation.clear();
        queuedChunks.clear();
        delayedChunkUpdates.clear();
        pendingDelayedUpdates.clear();
        
        if (enabled) {
            lightPropagator = new LightPropagator();
            lightPropagatorThread = new Thread(lightPropagator, "CL-LightPropagator");
            lightPropagatorThread.setPriority(Thread.MIN_PRIORITY);
            lightPropagatorThread.start();
            // Log the setting actually in force: an invalid or clobbered config value is corrected
            // silently by Forge, so the file on disk is not evidence of what the engine is using.
            ColorfulLighting.LOGGER.info("Colored light engine reset (lightUpdateSpeed={})",
                    ColorfulLightingConfig.lightUpdateSpeed());
        } else {
            ColorfulLighting.LOGGER.info("Colored light engine disabled");
        }
    }

    /**
     * LightPropagator calculates changes to light values. It runs on another thread to avoid lag on the main thread.
     * It propagates increases (increases of light values, e.g. new light source has been placed).
     * It propagates decreases (decreases of light values, e.g. light source has been destroyed, solid block has been placed in the path of light).
     * Changes caused by block updates are applied on the main thread to avoid light flickering
     */
    private class LightPropagator implements Runnable {
        /**
         * light changes that are not yet ready to be visible on main thread
         */
        private ConcurrentHashMap<BlockPos, ColorRGB4> lightChangesInProgress = new ConcurrentHashMap<>();
        private ConcurrentHashMap<BlockPos, ColorRGB4> darknessChangesInProgress = new ConcurrentHashMap<>();
        /**
         * light changes ready to be visible on main thread
         */
        private final ConcurrentHashMap<BlockPos, ColorRGB4> lightChangesReady = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<BlockPos, ColorRGB4> darknessChangesReady = new ConcurrentHashMap<>();
        private final Lock lightChangesReadyLock = new ReentrantLock();
        private final Lock darknessChangesReadyLock = new ReentrantLock();
        /**
         * Sections touched by the matching ready batch, computed on this thread when the batch is published
         * rather than on the render thread when it is applied. Each guarded by the lock of its batch, so a
         * section mark is never visible to the renderer before the storage write it belongs to.
         */
        private final LongOpenHashSet lightReadyDirtySections = new LongOpenHashSet();
        private final LongOpenHashSet darknessReadyDirtySections = new LongOpenHashSet();
        private volatile boolean running;

        private final ChunkOrder lightChunkOrder = new ChunkOrder();
        private final ChunkOrder darknessChunkOrder = new ChunkOrder();

        /** Direct measurement of a drain: terrain makes profiler frame times a poor proxy for throughput. */
        private long drainStartNanos;
        private long lastChunkNanos;
        private int drainChunks;

        /** Backoff for a queue that cannot progress; see the blocked branch in run(). */
        private long blockedSleepMillis = MIN_BLOCKED_SLEEP_MILLIS;
        private int lastChunksRemaining = -1;

        public boolean hasReadyLightChanges() {
            return !this.lightChangesReady.isEmpty();
        }

        public boolean hasReadyDarknessChanges() {
            return !this.darknessChangesReady.isEmpty();
        }

        public boolean hasReadyChanges() {
            return hasReadyLightChanges() || hasReadyDarknessChanges();
        }
        
        @Override
        public void run() {
            running = true;
            while (running) {
                // Process delayed chunk updates
                long now = System.currentTimeMillis();
                Iterator<DelayedChunkUpdate> it = delayedChunkUpdates.iterator();
                while (it.hasNext()) {
                    DelayedChunkUpdate update = it.next();
                    if (now >= update.executeTime) {
                        it.remove();
                        pendingDelayedUpdates.remove(update.chunkPos);
                        performRegionRebuild(update.chunkPos);
                    }
                }

                boolean hasLightWork = !blockUpdateDecreaseRequests.isEmpty() || !blockUpdateIncreaseRequests.isEmpty() || !chunksWaitingForPropagation.isEmpty();
                boolean hasDarknessWork = !darknessUpdateDecreaseRequests.isEmpty() || !darknessUpdateIncreaseRequests.isEmpty() || !chunksWaitingForDarknessPropagation.isEmpty();
                boolean hasWork = hasLightWork || hasDarknessWork;

                // Re-read every pass so changing the config takes effect without a restart.
                ColorfulLightingConfig.LightUpdateSpeed speed = ColorfulLightingConfig.lightUpdateSpeed();

                // Track the CHUNK queue only. hasWork also covers single-block updates, and in the
                // Nether flowing lava and fire fire checkBlock constantly, so hasWork can essentially
                // never go false - the chunk fill-in would finish and the drain would never close.
                // Report a drain when the chunk queue empties OR when no chunk has propagated for 2s.
                // The latter matters: the queue never empties, because the corners of this square view
                // area fall outside the disc of chunks the server actually sends. Waiting for an empty
                // queue would mean never reporting at all.
                int chunksRemaining = chunksWaitingForPropagation.size();
                if (chunksRemaining != lastChunksRemaining) {
                    lastChunksRemaining = chunksRemaining;
                    blockedSleepMillis = MIN_BLOCKED_SLEEP_MILLIS; // queue changed: something may be ready now
                }
                if (chunksRemaining > 0 && drainStartNanos == 0L) {
                    drainStartNanos = System.nanoTime();
                    lastChunkNanos = drainStartNanos;
                }
                if (drainStartNanos != 0L && drainChunks > 0) {
                    boolean finished = chunksRemaining == 0;
                    boolean stalled = System.nanoTime() - lastChunkNanos > 2_000_000_000L;
                    if (finished || stalled) {
                        if (drainChunks >= DRAIN_LOG_MIN_CHUNKS) {
                            long elapsedMillis = Math.max(1L, (lastChunkNanos - drainStartNanos) / 1_000_000L);
                            ColorfulLighting.LOGGER.info(
                                    "Colored light drain: {} chunks in {} ms ({} chunks/s), {} still queued [{}], lightUpdateSpeed={}",
                                    drainChunks, elapsedMillis,
                                    String.format("%.1f", drainChunks * 1000.0 / elapsedMillis),
                                    chunksRemaining, finished ? "finished" : "stalled", speed);
                        }
                        drainStartNanos = 0L;
                        drainChunks = 0;
                    }
                }

                long passStartNanos = System.nanoTime();
                boolean progressed = false;
                if (hasWork) {
                    // Keep propagating for a budget instead of sleeping 1ms after every single chunk:
                    // profiling put ~16% of this thread inside Thread.sleep while work was queued.
                    long deadline = System.nanoTime() + speed.budgetNanos();
                    boolean progressedThisPass;
                    do {
                        progressedThisPass = false;
                        if (hasLightWork) progressedThisPass |= propagateLight();
                        if (hasDarknessWork) progressedThisPass |= propagateDarkness();
                        progressed |= progressedThisPass;

                        hasLightWork = !blockUpdateDecreaseRequests.isEmpty() || !blockUpdateIncreaseRequests.isEmpty() || !chunksWaitingForPropagation.isEmpty();
                        hasDarknessWork = !darknessUpdateDecreaseRequests.isEmpty() || !darknessUpdateIncreaseRequests.isEmpty() || !chunksWaitingForDarknessPropagation.isEmpty();
                        // stop early when nothing moved: the queue is waiting on chunks to load
                    } while (running && progressedThisPass && (hasLightWork || hasDarknessWork) && System.nanoTime() < deadline);
                } else {
                    // If idle, check if we have sections to rebuild from explosions
                    if (!sectionsToRebuildLater.isEmpty()) {
                        synchronized (dirtySections) {
                            dirtySections.addAll(sectionsToRebuildLater);
                        }
                        sectionsToRebuildLater.clear();
                        Minecraft.getInstance().execute(ColoredLightEngine.this::onLightUpdate);
                    }
                }

                if (this.hasReadyChanges()) {
                    Minecraft.getInstance().execute(ColoredLightEngine.this::onLightUpdate);
                }

                if (hasWork && progressed) {
                    // Work remains. Pausing here is what lightUpdateSpeed controls: every finished chunk
                    // makes the renderer rebuild and re-upload its mesh, so spreading passes out trades
                    // fill-in speed for a smoother framerate. The pause scales with the work actually
                    // done, because a pass cannot stop mid-chunk and one Nether chunk dwarfs any fixed
                    // budget - a constant pause therefore throttles almost nothing.
                    blockedSleepMillis = MIN_BLOCKED_SLEEP_MILLIS;
                    double pauseFactor = speed.pauseFactor();
                    if (pauseFactor <= 0.0) {
                        Thread.yield();
                    } else {
                        long workedMillis = (System.nanoTime() - passStartNanos) / 1_000_000L;
                        long pauseMillis = Math.min(200L, Math.max(1L, (long) (workedMillis * pauseFactor)));
                        try {
                            Thread.sleep(pauseMillis);
                        } catch (InterruptedException e) {
                            running = false;
                        }
                    }
                } else {
                    long sleepMillis;
                    if (!hasWork) {
                        blockedSleepMillis = MIN_BLOCKED_SLEEP_MILLIS;
                        sleepMillis = IDLE_SLEEP_MILLIS;
                    } else if (!blockUpdateIncreaseRequests.isEmpty() || !blockUpdateDecreaseRequests.isEmpty()
                            || !darknessUpdateIncreaseRequests.isEmpty() || !darknessUpdateDecreaseRequests.isEmpty()) {
                        // A placed torch must light up immediately: never back off on block updates.
                        blockedSleepMillis = MIN_BLOCKED_SLEEP_MILLIS;
                        sleepMillis = MIN_BLOCKED_SLEEP_MILLIS;
                    } else {
                        // Only chunks remain and none can propagate: the ones left are outside the disc
                        // of chunks the server sends, or neighbour one that is, so they will never have a
                        // full 3x3 of loaded neighbours. Without a backoff the propagator would wake every
                        // 5ms for the rest of the session, walk the chunk order, and find nothing. The
                        // queue-size check above resets the backoff as soon as anything changes.
                        sleepMillis = blockedSleepMillis;
                        blockedSleepMillis = Math.min(MAX_BLOCKED_SLEEP_MILLIS, blockedSleepMillis * 2L);
                    }
                    try {
                        Thread.sleep(sleepMillis);
                    } catch (InterruptedException e) {
                        running = false;
                    }
                }
            }
        }

        public void stop() {
            running = false;
        }

        private void addLightColorChange(BlockPos blockPos, ColorRGB4 color) {
            lightChangesInProgress.put(blockPos, color);
        }

        private void addDarknessColorChange(BlockPos blockPos, ColorRGB4 color) {
            darknessChangesInProgress.put(blockPos, color);
        }

        public ColorRGB4 getLatestLightColor(BlockPos blockPos) {
            ColorRGB4 inProgress = lightChangesInProgress.get(blockPos);
            if (inProgress != null) return inProgress;
            
            ColorRGB4 ready = lightChangesReady.get(blockPos);
            if (ready != null) return ready;

            return storage.getEntry(blockPos);
        }

        public ColorRGB4 getLatestDarknessColor(BlockPos blockPos) {
            ColorRGB4 inProgress = darknessChangesInProgress.get(blockPos);
            if (inProgress != null) return inProgress;

            ColorRGB4 ready = darknessChangesReady.get(blockPos);
            if (ready != null) return ready;

            return darknessStorage.getEntry(blockPos);
        }

        private void performRegionRebuild(ChunkPos centerChunk) {
            LevelAccessor level = clientAccessor.getLevel();
            if (level == null) return;

            int radius = 1; // 3x3 area
            int minChunkX = centerChunk.x - radius;
            int maxChunkX = centerChunk.x + radius;
            int minChunkZ = centerChunk.z - radius;
            int maxChunkZ = centerChunk.z + radius;

            // 0. Clear pending changes for the region to avoid contaminating the rebuild with stale data
            lightChangesInProgress.entrySet().removeIf(entry -> {
                ChunkPos pos = new ChunkPos(entry.getKey());
                return pos.x >= minChunkX && pos.x <= maxChunkX && pos.z >= minChunkZ && pos.z <= maxChunkZ;
            });
            darknessChangesInProgress.entrySet().removeIf(entry -> {
                ChunkPos pos = new ChunkPos(entry.getKey());
                return pos.x >= minChunkX && pos.x <= maxChunkX && pos.z >= minChunkZ && pos.z <= maxChunkZ;
            });
            
            lightChangesReadyLock.lock();
            try {
                lightChangesReady.entrySet().removeIf(entry -> {
                    ChunkPos pos = new ChunkPos(entry.getKey());
                    return pos.x >= minChunkX && pos.x <= maxChunkX && pos.z >= minChunkZ && pos.z <= maxChunkZ;
                });
            } finally {
                lightChangesReadyLock.unlock();
            }
            darknessChangesReadyLock.lock();
            try {
                darknessChangesReady.entrySet().removeIf(entry -> {
                    ChunkPos pos = new ChunkPos(entry.getKey());
                    return pos.x >= minChunkX && pos.x <= maxChunkX && pos.z >= minChunkZ && pos.z <= maxChunkZ;
                });
            } finally {
                darknessChangesReadyLock.unlock();
            }

            // 1. Clear storage for the 3x3 region and mark dirty
            synchronized (storageLock) {
                synchronized (dirtySections) {
                    for (int cx = minChunkX; cx <= maxChunkX; cx++) {
                        for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                            for(int y = level.getMinSectionY(); y <= level.getMaxSectionY(); y++) {
                                long pos = SectionPos.asLong(cx, y, cz);
                                storage.removeSection(pos);
                                darknessStorage.removeSection(pos);
                                storage.addSection(pos);
                                darknessStorage.addSection(pos);
                                dirtySections.add(pos); // Mark as dirty so renderer updates even if no new light is found
                            }
                        }
                    }
                }
            }
            structureVersion.incrementAndGet();

            Queue<LightUpdateRequest> increaseRequests = new LinkedList<>();
            Queue<LightUpdateRequest> darknessIncreaseRequests = new LinkedList<>();

            // 2. Find internal sources for all chunks in region
            for (int cx = minChunkX; cx <= maxChunkX; cx++) {
                for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                    level.findLightSources(new ChunkPos(cx, cz), (blockPos -> {
                        increaseRequests.add(new LightUpdateRequest(blockPos, Config.getColorEmission(level, blockPos), false, true, false));
                    }));
                    level.findDarknessSources(new ChunkPos(cx, cz), (blockPos -> {
                        darknessIncreaseRequests.add(new LightUpdateRequest(blockPos, Config.getAbsorptionColor(level, blockPos), false, true, false));
                    }));
                }
            }

            // 3. Pull from neighbors OUTSIDE the region
            int minBlockY = level.getMinSectionY() * 16;
            int maxBlockY = (level.getMaxSectionY() + 1) * 16 - 1;

            int regionMinBlockX = minChunkX * 16;
            int regionMaxBlockX = (maxChunkX * 16) + 15;
            int regionMinBlockZ = minChunkZ * 16;
            int regionMaxBlockZ = (maxChunkZ * 16) + 15;

            for (int y = minBlockY; y <= maxBlockY; y++) {
                // North border of the whole region (check z-1)
                checkNeighborAndAdd(increaseRequests, regionMinBlockX, regionMaxBlockX, y, regionMinBlockZ - 1, true);
                checkNeighborDarknessAndAdd(darknessIncreaseRequests, regionMinBlockX, regionMaxBlockX, y, regionMinBlockZ - 1, true);
                // South border (check z+1)
                checkNeighborAndAdd(increaseRequests, regionMinBlockX, regionMaxBlockX, y, regionMaxBlockZ + 1, true);
                checkNeighborDarknessAndAdd(darknessIncreaseRequests, regionMinBlockX, regionMaxBlockX, y, regionMaxBlockZ + 1, true);
                // West border (check x-1)
                checkNeighborAndAdd(increaseRequests, regionMinBlockZ, regionMaxBlockZ, y, regionMinBlockX - 1, false);
                checkNeighborDarknessAndAdd(darknessIncreaseRequests, regionMinBlockZ, regionMaxBlockZ, y, regionMinBlockX - 1, false);
                // East border (check x+1)
                checkNeighborAndAdd(increaseRequests, regionMinBlockZ, regionMaxBlockZ, y, regionMaxBlockX + 1, false);
                checkNeighborDarknessAndAdd(darknessIncreaseRequests, regionMinBlockZ, regionMaxBlockZ, y, regionMaxBlockX + 1, false);
            }

            propagateIncreases(level, increaseRequests);
            propagateDarknessIncreases(level, darknessIncreaseRequests);
            applyChangesDirectly();
        }

        private void checkNeighborAndAdd(Queue<LightUpdateRequest> requests, int start, int end, int y, int fixed, boolean isZFixed) {
            for (int i = start; i <= end; i++) {
                BlockPos pos = isZFixed ? new BlockPos(i, y, fixed) : new BlockPos(fixed, y, i);
                ColorRGB4 color = getLatestLightColor(pos);
                if (color != null && (color.red4 > 0 || color.green4 > 0 || color.blue4 > 0)) {
                    requests.add(new LightUpdateRequest(pos, color, true));
                }
            }
        }

        private void checkNeighborDarknessAndAdd(Queue<LightUpdateRequest> requests, int start, int end, int y, int fixed, boolean isZFixed) {
            for (int i = start; i <= end; i++) {
                BlockPos pos = isZFixed ? new BlockPos(i, y, fixed) : new BlockPos(fixed, y, i);
                ColorRGB4 color = getLatestDarknessColor(pos);
                if (color != null && (color.red4 > 0 || color.green4 > 0 || color.blue4 > 0)) {
                    requests.add(new LightUpdateRequest(pos, color, true));
                }
            }
        }

        private record NearestBlockRequestsResult(BlockRequests blockUpdate, int distanceBlocks) {}
        private NearestBlockRequestsResult getNearestBlockRequests(PlayerAccessor player) {
            // find chunk nearest player
            var iterator = blockUpdateIncreaseRequests.iterator();
            int minDistance = Integer.MAX_VALUE;
            BlockRequests nearestUpdate = null;
            while (iterator.hasNext()) {
                BlockRequests update = iterator.next();
                int distance = update.blockPos.distManhattan(player.getBlockPos());
                if (distance < minDistance) {
                    minDistance = distance;
                    nearestUpdate = update;
                }
            }
            return nearestUpdate == null ? null : new NearestBlockRequestsResult(nearestUpdate, minDistance);
        }

        private NearestBlockRequestsResult getNearestDarknessRequests(PlayerAccessor player) {
            // find chunk nearest player
            var iterator = darknessUpdateIncreaseRequests.iterator();
            int minDistance = Integer.MAX_VALUE;
            BlockRequests nearestUpdate = null;
            while (iterator.hasNext()) {
                BlockRequests update = iterator.next();
                int distance = update.blockPos.distManhattan(player.getBlockPos());
                if (distance < minDistance) {
                    minDistance = distance;
                    nearestUpdate = update;
                }
            }
            return nearestUpdate == null ? null : new NearestBlockRequestsResult(nearestUpdate, minDistance);
        }

        private record NearestChunkResult(ChunkPos chunkPos, int distanceBlocks) {}
        /**
         * A cached ordering of the chunks still awaiting propagation: frustum-visible first, then
         * nearest to the player.
         *
         * <p>The nearest chunk used to be found by scanning the entire waiting collection — testing
         * nine neighbours for availability and allocating an AABB for a frustum test — once for every
         * chunk propagated. That is O(n) per chunk and O(n^2) to drain the queue; profiling put the
         * two scans at ~20% of this thread during a Nether dimension change.
         *
         * <p>The order is computed once and reused. It is rebuilt when the player crosses a chunk
         * boundary, when it is exhausted, or after a short TTL so camera movement still re-prioritises.
         * Priority is advisory, so a slightly stale ordering costs nothing but ordering.
         */
        private final class ChunkOrder {
            private final ArrayList<ChunkPos> order = new ArrayList<>();
            private int cursor;
            private long rebuiltAtNanos = Long.MIN_VALUE;
            private ChunkPos rebuiltCenter;

            NearestChunkResult next(LevelAccessor level, PlayerAccessor player, Set<ChunkPos> waiting) {
                if (waiting.isEmpty()) return null;
                ChunkPos center = player.getChunkPos();
                long now = System.nanoTime();
                // Deliberately not rebuilding on cursor exhaustion: when every remaining chunk is
                // still loading, that would rebuild on every poll and reinstate the O(n)-per-poll cost.
                if (!center.equals(rebuiltCenter) || now - rebuiltAtNanos > CHUNK_ORDER_TTL_NANOS) {
                    rebuild(level, center, waiting, now);
                }

                while (cursor < order.size()) {
                    ChunkPos chunkPos = order.get(cursor++);
                    if (!waiting.contains(chunkPos)) continue; // already propagated, or left the view area
                    if (!level.hasChunkAndNeighbours(chunkPos)) continue; // block state data not available yet
                    return new NearestChunkResult(chunkPos, chunkPos.getChessboardDistance(center) * 16);
                }
                return null;
            }

            private void rebuild(LevelAccessor level, ChunkPos center, Set<ChunkPos> waiting, long now) {
                order.clear();
                cursor = 0;
                rebuiltAtNanos = now;
                rebuiltCenter = center;

                Frustum currentFrustum = frustum;
                int minY = level.getLevel().getMinBuildHeight();
                int maxY = level.getLevel().getMaxBuildHeight();

                List<ChunkPos> visible = new ArrayList<>();
                List<ChunkPos> hidden = new ArrayList<>();
                ViewArea[] regionAreas = extraRegionAreas; // volatile read; written on the client thread
                for (ChunkPos chunkPos : waiting) {
                    // Extra-region chunks count as visible: a ship is usually on screen even though
                    // its shipyard chunks never pass the frustum test at their real coordinates.
                    boolean inView = inAnyArea(regionAreas, chunkPos.x, chunkPos.z)
                            || (currentFrustum != null && currentFrustum.isVisible(new AABB(
                                    chunkPos.getMinBlockX(), minY, chunkPos.getMinBlockZ(),
                                    chunkPos.getMaxBlockX() + 1, maxY, chunkPos.getMaxBlockZ() + 1)));
                    (inView ? visible : hidden).add(chunkPos);
                }

                Comparator<ChunkPos> byDistance = Comparator.comparingInt(c -> c.getChessboardDistance(center));
                visible.sort(byDistance);
                hidden.sort(byDistance);
                order.addAll(visible); // anything the player can see outranks anything they cannot
                order.addAll(hidden);
            }
        }

        private NearestChunkResult getNearestWaitingChunk(LevelAccessor level, PlayerAccessor player) {
            return lightChunkOrder.next(level, player, chunksWaitingForPropagation);
        }

        private NearestChunkResult getNearestWaitingDarknessChunk(LevelAccessor level, PlayerAccessor player) {
            return darknessChunkOrder.next(level, player, chunksWaitingForDarknessPropagation);
        }

        /**
         * apply ready light changes to storage
         */
        private void applyReadyChanges() {
            lightChangesReadyLock.lock();
            try {
                if (!lightChangesReady.isEmpty()) {
                    synchronized (storageLock) {
                        for (var entry : lightChangesReady.entrySet()) {
                            storage.setEntryUnsafe(entry.getKey(), entry.getValue());
                        }
                    }
                    lightChangesReady.clear();
                }
                // After the writes above, so the renderer never rebuilds a section before its colours land.
                publishDirtySections(lightReadyDirtySections);
            } finally {
                lightChangesReadyLock.unlock();
            }

            darknessChangesReadyLock.lock();
            try {
                if (!darknessChangesReady.isEmpty()) {
                    synchronized (storageLock) {
                        for (var entry : darknessChangesReady.entrySet()) {
                            darknessStorage.setEntryUnsafe(entry.getKey(), entry.getValue());
                        }
                    }
                    darknessChangesReady.clear();
                }
                publishDirtySections(darknessReadyDirtySections);
            } finally {
                darknessChangesReadyLock.unlock();
            }
        }

        /**
         * Caller must hold the lock guarding {@code batchDirtySections}. May hold more sections than the
         * batch that was just applied, because performRegionRebuild drops ready entries without dropping
         * their marks; a redundant section rebuild is harmless, a missing one is not.
         */
        private void publishDirtySections(LongOpenHashSet batchDirtySections) {
            if (batchDirtySections.isEmpty()) return;
            synchronized (dirtySections) {
                dirtySections.addAll(batchDirtySections);
            }
            batchDirtySections.clear();
        }

        /**
         * move light changes in progress to collection of ready light changes
         */
        private void markChangesReady() {
            if (!lightChangesInProgress.isEmpty()) {
                lightChangesReadyLock.lock();
                try {
                    for (var entry : lightChangesInProgress.entrySet()) {
                        markReady(lightChangesReady, lightReadyDirtySections, entry.getKey(), entry.getValue());
                    }
                } finally {
                    lightChangesReadyLock.unlock();
                }
                lightChangesInProgress = new ConcurrentHashMap<>();
            }

            if (!darknessChangesInProgress.isEmpty()) {
                darknessChangesReadyLock.lock();
                try {
                    for (var entry : darknessChangesInProgress.entrySet()) {
                        markReady(darknessChangesReady, darknessReadyDirtySections, entry.getKey(), entry.getValue());
                    }
                } finally {
                    darknessChangesReadyLock.unlock();
                }
                darknessChangesInProgress = new ConcurrentHashMap<>();
            }
        }

        /**
         * Propagation relaxes the same block many times, so a block reaches the ready batch again and again.
         * Only its first arrival needs the sections around it marked — every later one would recompute marks
         * the batch already holds. Caller must hold the lock guarding both collections.
         */
        private void markReady(Map<BlockPos, ColorRGB4> ready, LongOpenHashSet readyDirty, BlockPos blockPos, ColorRGB4 color) {
            if (ready.put(blockPos, color) == null) {
                SectionPos.aroundAndAtBlockPos(blockPos, readyDirty::add);
            }
        }


        /**
         * apply light changes in progress directly to storage
         */
        private void applyChangesDirectly() {
            if (!lightChangesInProgress.isEmpty()) {
                synchronized (storageLock) {
                    for (var entry : lightChangesInProgress.entrySet()) {
                        storage.setEntryUnsafe(entry.getKey(), entry.getValue());
                    }
                }
                markDirty(lightChangesInProgress.keySet());
                lightChangesInProgress.clear();
            }

            if (!darknessChangesInProgress.isEmpty()) {
                synchronized (storageLock) {
                    for (var entry : darknessChangesInProgress.entrySet()) {
                        darknessStorage.setEntryUnsafe(entry.getKey(), entry.getValue());
                    }
                }
                markDirty(darknessChangesInProgress.keySet());
                darknessChangesInProgress.clear();
            }
        }

        /**
         * Marks straight into the accumulated set rather than into a per-batch one: sections repeat heavily
         * across batches, and an add that hits an existing entry is far cheaper than growing a fresh set.
         * Runs on the propagator thread, after the storage writes the marks refer to.
         */
        private void markDirty(Set<BlockPos> changedBlocks) {
            synchronized (dirtySections) {
                for (BlockPos blockPos : changedBlocks) {
                    SectionPos.aroundAndAtBlockPos(blockPos, dirtySections::add);
                }
            }
        }

        /**
         * propagate light in the nearest waiting chunk, handle block light updates
         */
        /** @return true when this pass actually did work; false means the queue is blocked (chunks still loading) */
        private boolean propagateLight() {
            LevelAccessor level = clientAccessor.getLevel();
            if(level == null) return false;
            PlayerAccessor player = clientAccessor.getPlayer();
            if(player == null) return false;
            boolean progressed = false;

            // decrease requests are always executed
            if(!blockUpdateDecreaseRequests.isEmpty()) {
                progressed = true;
                Queue<LightUpdateRequest> newIncreaseRequests = new LinkedList<>();
                propagateDecreases(level, blockUpdateDecreaseRequests, newIncreaseRequests);
                propagateIncreases(level, newIncreaseRequests);

                markChangesReady();
            }
            
            var nearestChunkResult = getNearestWaitingChunk(level, player);
            var nearestBlockRequests = getNearestBlockRequests(player);

            if(nearestChunkResult != null && (nearestBlockRequests == null || nearestChunkResult.distanceBlocks() < nearestBlockRequests.distanceBlocks())) {
                // propagate chunk
                ChunkPos chunkPos = nearestChunkResult.chunkPos();
                chunksWaitingForPropagation.remove(chunkPos);

                Queue<LightUpdateRequest> increaseRequests = new LinkedList<>();
                // find light sources and request their propagation
                level.findLightSources(chunkPos, (blockPos -> {
                    increaseRequests.add(new LightUpdateRequest(blockPos, Config.getColorEmission(level, blockPos), false, true, false));
                }));
                propagateIncreases(level, increaseRequests);
                // new chunks' light propagation is not synchronized with main thread
                applyChangesDirectly();
                progressed = true;
                drainChunks++;
                lastChunkNanos = System.nanoTime();
            }
            else if(nearestBlockRequests != null) {
                blockUpdateIncreaseRequests.remove(nearestBlockRequests.blockUpdate);
                propagateIncreases(level, nearestBlockRequests.blockUpdate.increaseRequests);
                markChangesReady();
                progressed = true;
            }
            return progressed;
        }

        /** @return true when this pass actually did work; false means the queue is blocked (chunks still loading) */
        private boolean propagateDarkness() {
            LevelAccessor level = clientAccessor.getLevel();
            if(level == null) return false;
            PlayerAccessor player = clientAccessor.getPlayer();
            if(player == null) return false;
            boolean progressed = false;

            // decrease requests are always executed
            if(!darknessUpdateDecreaseRequests.isEmpty()) {
                progressed = true;
                Queue<LightUpdateRequest> newIncreaseRequests = new LinkedList<>();
                propagateDarknessDecreases(level, darknessUpdateDecreaseRequests, newIncreaseRequests);
                propagateDarknessIncreases(level, newIncreaseRequests);

                markChangesReady();
            }

            var nearestChunkResult = getNearestWaitingDarknessChunk(level, player);
            var nearestBlockRequests = getNearestDarknessRequests(player);

            if(nearestChunkResult != null && (nearestBlockRequests == null || nearestChunkResult.distanceBlocks() < nearestBlockRequests.distanceBlocks())) {
                // propagate chunk
                ChunkPos chunkPos = nearestChunkResult.chunkPos();
                chunksWaitingForDarknessPropagation.remove(chunkPos);

                Queue<LightUpdateRequest> increaseRequests = new LinkedList<>();
                // find darkness sources and request their propagation
                level.findDarknessSources(chunkPos, (blockPos -> {
                    increaseRequests.add(new LightUpdateRequest(blockPos, Config.getAbsorptionColor(level, blockPos), false, true, false));
                }));
                propagateDarknessIncreases(level, increaseRequests);
                // new chunks' darkness propagation is not synchronized with main thread
                applyChangesDirectly();
                progressed = true;
            }
            else if(nearestBlockRequests != null) {
                darknessUpdateIncreaseRequests.remove(nearestBlockRequests.blockUpdate);
                propagateDarknessIncreases(level, nearestBlockRequests.blockUpdate.increaseRequests);
                markChangesReady();
                progressed = true;
            }
            return progressed;
        }

        /**
         * Handles all increase propagation requests.
         */
        private void propagateIncreases(LevelAccessor level, Queue<LightUpdateRequest> requests) {
            while(!requests.isEmpty()) {
                propagateIncrease(requests, requests.poll(), level);
            }
        }

        private void propagateDarknessIncreases(LevelAccessor level, Queue<LightUpdateRequest> requests) {
            while(!requests.isEmpty()) {
                propagateDarknessIncrease(requests, requests.poll(), level);
            }
        }

        private ColorRGB4 attenuateLight(ColorRGB4 source, int lightBlocked) {
            return ColorRGB4.fromRGB4(
                    Math.max(0, source.red4 - lightBlocked),
                    Math.max(0, source.green4 - lightBlocked),
                    Math.max(0, source.blue4 - lightBlocked)
            );
        }

        private boolean propagateIncrease(Queue<LightUpdateRequest> increaseRequests, LightUpdateRequest request, LevelAccessor level) {
            if (request.checkSource) {
                 BlockStateAccessor blockState = level.getBlockState(request.blockPos);
                 if (blockState == null || Config.getEmissionBrightness(level, request.blockPos, blockState) == 0) {
                     return false;
                 }
            }

            if (request.repropagate) {
                 if (request.lightColor == null) {
                    request.lightColor = getLatestLightColor(request.blockPos);
                 }
            }

            ColorRGB4 oldLightColor = getLatestLightColor(request.blockPos);
            if(oldLightColor == null) return false; // section might have got unloaded and propagation should stop
            ColorRGB4 newLightColor = ColorRGB4.fromRGB4(
                    Math.max(oldLightColor.red4, request.lightColor.red4),
                    Math.max(oldLightColor.green4, request.lightColor.green4),
                    Math.max(oldLightColor.blue4, request.lightColor.blue4)
            );

            // if light color didn't change (check is ignored if request is forced)
            if(!request.force && newLightColor.red4 == oldLightColor.red4 && newLightColor.green4 == oldLightColor.green4 && newLightColor.blue4 == oldLightColor.blue4) return true;
            addLightColorChange(request.blockPos, newLightColor);

            // Cache source block state and geometry info once, not per-direction
            BlockStateAccessor sourceState = level.getBlockState(request.blockPos);
            boolean sourceStateExists = sourceState != null;
            BlockState sourceBlockState = sourceStateExists ? sourceState.getBlockState() : null;
            boolean sourceOccludes = sourceStateExists && sourceBlockState.useShapeForLightOcclusion();
            boolean sourceDynamic = sourceStateExists && ShapeOcclusion.isDynamicShapeBlocker(sourceBlockState);
            ColorRGB4 sourceBaseTransmittance = sourceStateExists ? Config.getColoredLightTransmittance(level, request.blockPos, sourceState) : ColorRGB4.WHITE;

            for(var direction : Direction.values()) {
                BlockPos neighbourPos = request.blockPos.relative(direction);
                if(!level.isInBounds(neighbourPos)) continue;
                BlockStateAccessor neighbourState = level.getBlockState(neighbourPos);
                if(neighbourState == null) return false; // section might have got unloaded and propagation should stop

                // Start with vanilla light blocking
                int lightBlocked = Math.max(1, neighbourState.getLightBlock(level, neighbourPos));

                BlockState neighborBlockState = neighbourState.getBlockState();
                boolean neighbourDynamic = ShapeOcclusion.isDynamicShapeBlocker(neighborBlockState);

                // Override with custom absorption if it's defined.
                // Doors/trapdoors are handled by the panel logic below instead: their filter must
                // apply only across the panel face, not omnidirectionally.
                int customAbsorption = neighbourDynamic ? -1 : Config.getLightAbsorption(level, neighbourPos, neighbourState);

                boolean geometryOccludes = false;
                if (sourceStateExists) {
                    boolean neighborOccludes = neighborBlockState.useShapeForLightOcclusion();
                    
                    if (sourceOccludes || neighborOccludes) {
                        VoxelShape sourceFaceShape = sourceOccludes ? sourceBlockState.getFaceOcclusionShape(level.getLevel(), request.blockPos, direction) : Shapes.empty();
                        VoxelShape neighbourFaceShape = neighborOccludes ? neighborBlockState.getFaceOcclusionShape(level.getLevel(), neighbourPos, direction.getOpposite()) : Shapes.empty();
                        geometryOccludes = Shapes.faceShapeOccludes(sourceFaceShape, neighbourFaceShape);
                    }
                }

                if (customAbsorption >= 0) {
                    if (customAbsorption < 15) {
                        lightBlocked = Math.max(1, customAbsorption);
                    } else {
                        lightBlocked = geometryOccludes ? 15 : 1;
                    }
                } else if (geometryOccludes) {
                    lightBlocked = 15;
                }

                // Door/trapdoor panels block only the one cell face they are flush against; the
                // other faces of the cell stay fully open. Crossing a panel face costs the block's
                // filter absorption (partial for doors with windows), or is opaque without a filter.
                boolean sourcePanelBlocks = sourceDynamic && ShapeOcclusion.panelCoversFace(level.getLevel(), sourceBlockState, request.blockPos, direction);
                boolean neighbourPanelBlocks = neighbourDynamic && ShapeOcclusion.panelCoversFace(level.getLevel(), neighborBlockState, neighbourPos, direction.getOpposite());
                if (sourcePanelBlocks) {
                    int panelAbsorption = Config.getLightAbsorption(level, request.blockPos, sourceState);
                    lightBlocked = Math.max(lightBlocked, panelAbsorption >= 0 ? Math.max(1, panelAbsorption) : 15);
                }
                if (neighbourPanelBlocks) {
                    int panelAbsorption = Config.getLightAbsorption(level, neighbourPos, neighbourState);
                    lightBlocked = Math.max(lightBlocked, panelAbsorption >= 0 ? Math.max(1, panelAbsorption) : 15);
                }

                // Calculate transmittance based on both source exit and destination entry.
                // A door/trapdoor tint likewise applies only to light crossing its panel face.
                ColorRGB4 exitTransmittance;
                if (sourceDynamic) {
                    exitTransmittance = sourcePanelBlocks ? sourceBaseTransmittance : ColorRGB4.WHITE;
                } else if (!sourceBaseTransmittance.equals(ColorRGB4.WHITE)) {
                    exitTransmittance = Config.getColoredLightTransmittance(level, request.blockPos, sourceState, direction);
                } else {
                    exitTransmittance = sourceBaseTransmittance;
                }
                ColorRGB4 entryTransmittance;
                if (neighbourDynamic) {
                    entryTransmittance = neighbourPanelBlocks ? Config.getColoredLightTransmittance(level, neighbourPos, neighbourState) : ColorRGB4.WHITE;
                } else {
                    entryTransmittance = Config.getColoredLightTransmittance(level, neighbourPos, neighbourState, direction.getOpposite());
                }
                
                ColorRGB4 coloredLightTransmittance = ColorRGB4.min(exitTransmittance, entryTransmittance);
                
                ColorRGB4 attenuated = attenuateLight(request.lightColor, lightBlocked);
                ColorRGB4 neighbourLightColor = ColorRGB4.fromRGB4(
                        MathExt.clamp(attenuated.red4, 0, coloredLightTransmittance.red4),
                        MathExt.clamp(attenuated.green4, 0, coloredLightTransmittance.green4),
                        MathExt.clamp(attenuated.blue4, 0, coloredLightTransmittance.blue4)
                );
                // if no more color to propagate
                if(neighbourLightColor.red4 == 0 && neighbourLightColor.green4 == 0 && neighbourLightColor.blue4 == 0) continue;

                increaseRequests.add(new LightUpdateRequest(neighbourPos, neighbourLightColor, false));
            }
            return true;
        }

        private boolean propagateDarknessIncrease(Queue<LightUpdateRequest> increaseRequests, LightUpdateRequest request, LevelAccessor level) {
            if (request.checkSource) {
                 BlockStateAccessor blockState = level.getBlockState(request.blockPos);
                 if (blockState == null || Config.getAbsorption(level, request.blockPos, blockState) == 0) {
                     return false;
                 }
            }

            if (request.repropagate) {
                 if (request.lightColor == null) {
                    request.lightColor = getLatestDarknessColor(request.blockPos);
                 }
            }

            ColorRGB4 oldDarknessColor = getLatestDarknessColor(request.blockPos);
            if(oldDarknessColor == null) return false; // section might have got unloaded and propagation should stop
            ColorRGB4 newDarknessColor = ColorRGB4.fromRGB4(
                    Math.max(oldDarknessColor.red4, request.lightColor.red4),
                    Math.max(oldDarknessColor.green4, request.lightColor.green4),
                    Math.max(oldDarknessColor.blue4, request.lightColor.blue4)
            );

            // if light color didn't change (check is ignored if request is forced)
            if(!request.force && newDarknessColor.red4 == oldDarknessColor.red4 && newDarknessColor.green4 == oldDarknessColor.green4 && newDarknessColor.blue4 == oldDarknessColor.blue4) return true;
            addDarknessColorChange(request.blockPos, newDarknessColor);

            // Cache source block state and geometry info once, not per-direction
            BlockStateAccessor sourceState = level.getBlockState(request.blockPos);
            boolean sourceStateExists = sourceState != null;
            BlockState sourceBlockState = sourceStateExists ? sourceState.getBlockState() : null;
            boolean sourceOccludes = sourceStateExists && sourceBlockState.useShapeForLightOcclusion();
            boolean sourceDynamic = sourceStateExists && ShapeOcclusion.isDynamicShapeBlocker(sourceBlockState);

            for(var direction : Direction.values()) {
                BlockPos neighbourPos = request.blockPos.relative(direction);
                if(!level.isInBounds(neighbourPos)) continue;
                BlockStateAccessor neighbourState = level.getBlockState(neighbourPos);
                if(neighbourState == null) return false; // section might have got unloaded and propagation should stop

                int lightBlocked = Math.max(1, neighbourState.getLightBlock(level, neighbourPos));

                BlockState neighborBlockState = neighbourState.getBlockState();
                boolean neighbourDynamic = ShapeOcclusion.isDynamicShapeBlocker(neighborBlockState);

                if (sourceStateExists) {
                    boolean neighborOccludes = neighborBlockState.useShapeForLightOcclusion();

                    if (sourceOccludes || neighborOccludes) {
                        VoxelShape sourceFaceShape = sourceOccludes ? sourceBlockState.getFaceOcclusionShape(level.getLevel(), request.blockPos, direction) : Shapes.empty();
                        VoxelShape neighbourFaceShape = neighborOccludes ? neighborBlockState.getFaceOcclusionShape(level.getLevel(), neighbourPos, direction.getOpposite()) : Shapes.empty();

                        if (Shapes.faceShapeOccludes(sourceFaceShape, neighbourFaceShape)) {
                            lightBlocked = 15;
                        }
                    }
                }

                // Door/trapdoor panels block darkness across their covered face the same way they
                // block light, using the same filter absorption so both stay consistent.
                if (sourceDynamic && ShapeOcclusion.panelCoversFace(level.getLevel(), sourceBlockState, request.blockPos, direction)) {
                    int panelAbsorption = Config.getLightAbsorption(level, request.blockPos, sourceState);
                    lightBlocked = Math.max(lightBlocked, panelAbsorption >= 0 ? Math.max(1, panelAbsorption) : 15);
                }
                if (neighbourDynamic && ShapeOcclusion.panelCoversFace(level.getLevel(), neighborBlockState, neighbourPos, direction.getOpposite())) {
                    int panelAbsorption = Config.getLightAbsorption(level, neighbourPos, neighbourState);
                    lightBlocked = Math.max(lightBlocked, panelAbsorption >= 0 ? Math.max(1, panelAbsorption) : 15);
                }

                ColorRGB4 attenuated = attenuateLight(request.lightColor, lightBlocked);
                // if no more color to propagate
                if(attenuated.red4 == 0 && attenuated.green4 == 0 && attenuated.blue4 == 0) continue;

                increaseRequests.add(new LightUpdateRequest(neighbourPos, attenuated, false));
            }
            return true;
        }

        /**
         * Handles all decrease propagation requests.
         */
        private void propagateDecreases(LevelAccessor level, Queue<LightUpdateRequest> decreaseRequests, Queue<LightUpdateRequest> increaseRequests) {
            Map<BlockPos, ColorRGB4> visited = new HashMap<>();
            while(!decreaseRequests.isEmpty()) {
                LightUpdateRequest req = decreaseRequests.poll();
                ColorRGB4 prev = visited.get(req.blockPos);
                if (prev != null && prev.red4 >= req.lightColor.red4 && prev.green4 >= req.lightColor.green4 && prev.blue4 >= req.lightColor.blue4) {
                    continue;
                }
                if (prev == null) {
                    visited.put(req.blockPos, req.lightColor);
                } else {
                    visited.put(req.blockPos, ColorRGB4.max(prev, req.lightColor));
                }
                propagateDecrease(increaseRequests, decreaseRequests, req, level);
            }
        }

        private void propagateDarknessDecreases(LevelAccessor level, Queue<LightUpdateRequest> decreaseRequests, Queue<LightUpdateRequest> increaseRequests) {
            Map<BlockPos, ColorRGB4> visited = new HashMap<>();
            while(!decreaseRequests.isEmpty()) {
                LightUpdateRequest req = decreaseRequests.poll();
                ColorRGB4 prev = visited.get(req.blockPos);
                if (prev != null && prev.red4 >= req.lightColor.red4 && prev.green4 >= req.lightColor.green4 && prev.blue4 >= req.lightColor.blue4) {
                    continue;
                }
                if (prev == null) {
                    visited.put(req.blockPos, req.lightColor);
                } else {
                    visited.put(req.blockPos, ColorRGB4.max(prev, req.lightColor));
                }
                propagateDarknessDecrease(increaseRequests, decreaseRequests, req, level);
            }
        }

        private boolean propagateDecrease(Queue<LightUpdateRequest> increaseRequests, Queue<LightUpdateRequest> decreaseRequests, LightUpdateRequest request, LevelAccessor level) {
            ColorRGB4 oldLightColor = getLatestLightColor(request.blockPos);
            if(oldLightColor == null) return false; // section might have got unloaded and propagation should stop

            addLightColorChange(request.blockPos, ColorRGB4.fromRGB4(0, 0, 0));

            BlockStateAccessor blockState = level.getBlockState(request.blockPos);
            if(blockState == null) return false; // section might have got unloaded and propagation should stop
            // repropagate removed light (single lookup for both brightness and color)
            if(Config.getEmissionBrightness(level, request.blockPos, blockState) > 0) {
                increaseRequests.add(new LightUpdateRequest(request.blockPos, Config.getColorEmission(level, request.blockPos, blockState), false, true, false));
            }

            // attenuation
            ColorRGB4 neighbourLightDecrease = attenuateLight(request.lightColor, 1);

            // whether neighbours' light should be decreased or increased (to repropagate), true on "light edges"
            boolean repropagateNeighbours = neighbourLightDecrease.red4 == 0 && neighbourLightDecrease.green4 == 0 && neighbourLightDecrease.blue4 == 0;

            for(var direction : Direction.values()) {
                BlockPos neighbourPos = request.blockPos.relative(direction);
                if(!level.isInBounds(neighbourPos)) continue;

                if(!repropagateNeighbours) {
                    // propagate decrease
                    decreaseRequests.add(new LightUpdateRequest(neighbourPos, neighbourLightDecrease, false));
                }
                else {
                    ColorRGB4 neighbourLightColor = getLatestLightColor(neighbourPos);
                    if(neighbourLightColor == null) return false; // section might have got unloaded and propagation should stop
                    // if neighbour doesn't have any light
                    if(neighbourLightColor.red4 == 0 && neighbourLightColor.green4 == 0 && neighbourLightColor.blue4 == 0)
                        continue;

                    // force neighbour to propagate light to the region that has been just cleared (decreased)
                    increaseRequests.add(new LightUpdateRequest(neighbourPos, null, true, false, true));
                }
            }
            return true;
        }

        private boolean propagateDarknessDecrease(Queue<LightUpdateRequest> increaseRequests, Queue<LightUpdateRequest> decreaseRequests, LightUpdateRequest request, LevelAccessor level) {
            ColorRGB4 oldDarknessColor = getLatestDarknessColor(request.blockPos);
            if(oldDarknessColor == null) return false; // section might have got unloaded and propagation should stop

            addDarknessColorChange(request.blockPos, ColorRGB4.fromRGB4(0, 0, 0));

            BlockStateAccessor blockState = level.getBlockState(request.blockPos);
            if(blockState == null) return false; // section might have got unloaded and propagation should stop
            // repropagate removed darkness (single lookup for both absorption and color)
            if(Config.getAbsorption(level, request.blockPos, blockState) > 0) {
                increaseRequests.add(new LightUpdateRequest(request.blockPos, Config.getAbsorptionColor(level, request.blockPos, blockState), false, true, false));
            }

            // attenuation
            ColorRGB4 neighbourDarknessDecrease = attenuateLight(request.lightColor, 1);

            // whether neighbours' light should be decreased or increased (to repropagate), true on "light edges"
            boolean repropagateNeighbours = neighbourDarknessDecrease.red4 == 0 && neighbourDarknessDecrease.green4 == 0 && neighbourDarknessDecrease.blue4 == 0;

            for(var direction : Direction.values()) {
                BlockPos neighbourPos = request.blockPos.relative(direction);
                if(!level.isInBounds(neighbourPos)) continue;

                if(!repropagateNeighbours) {
                    // propagate decrease
                    decreaseRequests.add(new LightUpdateRequest(neighbourPos, neighbourDarknessDecrease, false));
                }
                else {
                    ColorRGB4 neighbourDarknessColor = getLatestDarknessColor(neighbourPos);
                    if(neighbourDarknessColor == null) return false; // section might have got unloaded and propagation should stop
                    // if neighbour doesn't have any light
                    if(neighbourDarknessColor.red4 == 0 && neighbourDarknessColor.green4 == 0 && neighbourDarknessColor.blue4 == 0)
                        continue;

                    // force neighbour to propagate light to the region that has been just cleared (decreased)
                    increaseRequests.add(new LightUpdateRequest(neighbourPos, null, true, false, true));
                }
            }
            return true;
        }
    }

    private static class BlockRequests {
        public BlockPos blockPos;
        public Queue<LightUpdateRequest> increaseRequests = new LinkedList<>();

        public BlockRequests(BlockPos blockPos) {
            this.blockPos = blockPos;
        }
    }

    private static class LightUpdateRequest {
        BlockPos blockPos;
        ColorRGB4 lightColor;
        boolean force;
        boolean checkSource;
        boolean repropagate;

        public LightUpdateRequest(BlockPos blockPos, ColorRGB4 lightColor, boolean force) {
            this(blockPos, lightColor, force, false, false);
        }

        public LightUpdateRequest(BlockPos blockPos, ColorRGB4 lightColor, boolean force, boolean checkSource) {
            this(blockPos, lightColor, force, checkSource, false);
        }

        public LightUpdateRequest(BlockPos blockPos, ColorRGB4 lightColor, boolean force, boolean checkSource, boolean repropagate) {
            this.blockPos = blockPos;
            this.lightColor = lightColor;
            this.force = force;
            this.checkSource = checkSource;
            this.repropagate = repropagate;
        }
    }

    private record DelayedChunkUpdate(ChunkPos chunkPos, long executeTime) {}
}