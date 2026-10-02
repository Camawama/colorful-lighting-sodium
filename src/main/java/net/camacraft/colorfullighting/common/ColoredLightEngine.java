package net.camacraft.colorfullighting.common;

import net.camacraft.colorfullighting.ColorfulLighting;
import net.camacraft.colorfullighting.common.accessors.BlockStateAccessor;
import net.camacraft.colorfullighting.common.accessors.ClientAccessor;
import net.camacraft.colorfullighting.common.accessors.LevelAccessor;
import net.camacraft.colorfullighting.common.accessors.mixin.LevelAttachments;
import net.camacraft.colorfullighting.common.util.ColorRGB4;
import net.camacraft.colorfullighting.common.util.ColorRGB8;
import net.camacraft.colorfullighting.common.util.WeakList;
import net.camacraft.colorfullighting.compat.distanthorizons.DhCompat;
import net.camacraft.colorfullighting.compat.dynamiclights.DynamicLightsCompat;
import net.camacraft.colorfullighting.compat.flywheel.FlywheelCompat;
import net.camacraft.colorfullighting.compat.oculus.OculusCompat;
import net.camacraft.colorfullighting.compat.sodium.SodiumCompat;
import net.camacraft.colorfullighting.mixin.compat.sodium.SodiumWorldRendererAccessor;
import net.camacraft.colorfullighting.resourcemanager.InternalPackRegistration;
import net.camacraft.colorfullighting.compat.distanthorizons.DhColorCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;


/**
 * Class responsible for managing light color values in the client's world and sampling those values.
 * Most work is delegated to LightPropagator thread.
 */
public class ColoredLightEngine {
	private static final WeakList<ColoredLightEngine> TRACKED = new WeakList<>(new ArrayList<>());
	
	private final ClientAccessor clientAccessor;
	protected final LevelAccessor level;
	/** This level's dynamic (entity/held-item) light state; may be null for exotic levels. */
	private final DynamicLightsCompat dynamicLights;
	protected final ColoredLightStorage storage = new ColoredLightStorage();
	protected final ColoredLightStorage darknessStorage = new ColoredLightStorage();
    /**
     * Guards writers against each other only. Sampling never takes it: the storages are concurrent maps
     * of volatile-published sections, so readers race with the propagator by design and lose at worst a
     * single frame of colour on a block that is already queued for a re-mesh. Taking this lock per sample
     * serialised all ten Sodium chunk-build workers behind one monitor.
     */
    protected final Object storageLock = new Object();
    /**
     * Bumped after sections are added to or removed from either storage. Sampling threads cache the
     * section they last touched and discard that cache when this moves, so a section swapped out from
     * under a reader is never read for more than one sample. Bumped from both the client thread
     * (updateViewArea) and the propagator thread (performRegionRebuild), hence atomic.
     */
    protected final AtomicInteger structureVersion = new AtomicInteger();
	protected final ThreadLocal<SectionCursor> sectionCursor = ThreadLocal.withInitial(SectionCursor::new);
	protected volatile boolean running = true;
	private ViewArea viewArea = new ViewArea();
    /**
     * Extra light regions beyond the player's view area, keyed by owner (a Valkyrien Skies ship id,
     * or a remote-level chunk cell — see the key namespaces below). Written and read on the client
     * thread only, like viewArea. Regions may touch each other and the view area (adjacent cells
     * share border columns); overlap is safe because {@link ColoredLightStorage#addSection} never
     * replaces an existing section.
     */
    private final Map<Long, LightRegion> extraRegions = new HashMap<>();

    /**
     * Region keys carry their owner in the top two bits so independent owners can each reconcile
     * their own regions every tick (see {@link #syncExtraRegions(long, Map)}) without removing one
     * another's.
     */
    public static final long REGION_NAMESPACE_MASK = 0xC000_0000_0000_0000L;
    /** Default namespace: Valkyrien Skies ship ids (nonnegative, so their top bits are clear). */
    public static final long REGION_NAMESPACE_DEFAULT = 0L;
    /** Loaded-chunk cells of levels the player is not in (Immersive Portals compat). */
    public static final long REGION_NAMESPACE_REMOTE_LEVEL = 0x4000_0000_0000_0000L;
    /**
     * The extra regions' areas, republished on every change for threads that must not touch the map:
     * the propagator's ChunkOrder reads this to prioritise region chunks the way it prioritises
     * frustum-visible ones (a ship is usually on screen even though its shipyard chunks never are).
     */
    protected volatile ViewArea[] extraRegionAreas = new ViewArea[0];
    protected final ConcurrentLinkedQueue<LightUpdateRequest> blockUpdateDecreaseRequests = new ConcurrentLinkedQueue<>(); // those first added will be executed first (this order is required by decrease propagation algorithm)
    protected final ConcurrentLinkedQueue<BlockRequests> blockUpdateIncreaseRequests = new ConcurrentLinkedQueue<>(); // those nearest to the player will be executed first
    protected final ConcurrentLinkedQueue<LightUpdateRequest> darknessUpdateDecreaseRequests = new ConcurrentLinkedQueue<>();
    protected final ConcurrentLinkedQueue<BlockRequests> darknessUpdateIncreaseRequests = new ConcurrentLinkedQueue<>();
    // Sets, not queues: ConcurrentLinkedQueue.remove is O(n) and ran once per propagated chunk.
    // Ordering now comes from LightPropagator.ChunkOrder instead of rescanning the collection.
    protected final Set<ChunkPos> chunksWaitingForPropagation = ConcurrentHashMap.newKeySet();
	protected final Set<ChunkPos> chunksWaitingForDarknessPropagation = ConcurrentHashMap.newKeySet();
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
    protected final LongOpenHashSet dirtySections = new LongOpenHashSet();
	protected final Set<Long> sectionsToRebuildLater = ConcurrentHashMap.newKeySet();

    protected final ConcurrentLinkedQueue<DelayedChunkUpdate> delayedChunkUpdates = new ConcurrentLinkedQueue<>();
    protected final Set<ChunkPos> pendingDelayedUpdates = ConcurrentHashMap.newKeySet();

    protected LightPropagator lightPropagator;
    private Thread lightPropagatorThread;
    
    // volatile: read from Sodium chunk-build worker threads and the light propagator thread
    private static volatile boolean enabled = true;
    private static boolean packsInitialized = false;
	
	public static final String CORE_SHADER_PACK_ID = ColorfulLighting.CORE_SHADER_PACK_ID;

    /** How long a computed chunk ordering stays usable before it is rebuilt. */
    public static final long CHUNK_ORDER_TTL_NANOS = 100_000_000L; // 100 ms

    /**
     * Only report drains at least this large. Moving queues a strip of chunks every second or so, and
     * logging each one buries the log; the rate is meaningless for a handful of chunks anyway, since
     * the timer includes however long the queue waited on chunk data rather than time spent working.
     */
    public static final int DRAIN_LOG_MIN_CHUNKS = 256;
	
	public static final long IDLE_SLEEP_MILLIS = 10L;
    public static final long MIN_BLOCKED_SLEEP_MILLIS = 5L;
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
    public static final long MAX_BLOCKED_SLEEP_MILLIS = 20L;

    // volatile: written on the render thread each frame, read on the light propagator thread
    protected volatile Frustum frustum;

    public static ColoredLightEngine create(Level level, ClientAccessor clientAccessor) {
        return new ColoredLightEngine(level, clientAccessor);
    }

    private ColoredLightEngine(Level level, ClientAccessor clientAccessor) {
		this.level = ((LevelAttachments) level).colorfullighting$getAccessor();
        // Cached here because the sampling hot path consults it per sample; LevelMixin creates the
        // attachment before the engine. Per-level, so one dimension's held-item lights can never
        // tint another dimension's samples (Immersive Portals renders several levels at once).
        this.dynamicLights = ((LevelAttachments) level).colorfullighting$getDynamicLights();
        this.clientAccessor = clientAccessor;
        reset();
	    synchronized (TRACKED) {
		    TRACKED.add(this);
			TRACKED.prune();
	    }
    }
	
	public static void resetAll() {
		synchronized (TRACKED) {
			for (ColoredLightEngine coloredLightEngine : TRACKED) {
				if (coloredLightEngine == null)
					continue;
				
				coloredLightEngine.reset();
			}
			TRACKED.prune();
		}
//        reset();
	}
    
    /**
     * Diagnostic snapshot for {@code /cl debug queue}, built for the long-standing "colored light
     * silently stops until /cl purge" bug: shows whether this level's propagator thread is even
     * alive, the queue depths, and the nearest waiting chunks with the neighbour availability the
     * queue's readiness check consults.
     */
    public String describeQueues(ChunkPos center) {
        StringBuilder sb = new StringBuilder();
        Thread thread = lightPropagatorThread;
        sb.append("engine enabled: ").append(enabled);
        sb.append("\npropagator thread: ").append(
                thread == null ? "none" : thread.isAlive() ? "alive" : "DEAD <- the bug; run /cl purge and report your log");
        sb.append("\nchunks waiting: light ").append(chunksWaitingForPropagation.size())
                .append(", darkness ").append(chunksWaitingForDarknessPropagation.size());
        sb.append("\nblock updates queued: light +").append(blockUpdateIncreaseRequests.size())
                .append(" -").append(blockUpdateDecreaseRequests.size())
                .append(", darkness +").append(darknessUpdateIncreaseRequests.size())
                .append(" -").append(darknessUpdateDecreaseRequests.size());
        int listed = 0;
        for (ChunkPos pos : chunksWaitingForPropagation) {
            if (pos.getChessboardDistance(center) > 8) continue;
            if (listed == 0) sb.append("\nwaiting chunks near you:");
            sb.append("\n  ").append(pos).append(" dist=").append(pos.getChessboardDistance(center))
                    .append(" missingNeighbours[");
            boolean first = true;
            for (int ox = -1; ox <= 1; ++ox) {
                for (int oz = -1; oz <= 1; ++oz) {
                    if (!level.hasChunk(new ChunkPos(pos.x + ox, pos.z + oz))) {
                        if (!first) sb.append(' ');
                        sb.append(ox).append(',').append(oz);
                        first = false;
                    }
                }
            }
            sb.append(']');
            if (++listed >= 5) break;
        }
        if (listed == 0) sb.append("\nno waiting chunks within 8 chunks of you");
        return sb.toString();
    }

    public static void setEnabled(boolean enabled) {
        if (ColoredLightEngine.enabled != enabled) {
            ColoredLightEngine.enabled = enabled;
            ColorfulLightingConfig.ENABLED.set(enabled);
            ColorfulLightingConfig.save();
	        resetAll();
            updateShaderPack();
			
			if (OculusCompat.isOculusLoaded())
				OculusCompat.reloadPack();
		}
    }
    
    public static boolean isEnabled() {
        return enabled;
    }
    
    public static void onPacksInitialized() {
        packsInitialized = true;
        updateShaderPack();
    }

    private static void updateShaderPack() {
        if (!packsInitialized) return;
        Minecraft mc = Minecraft.getInstance();
        PackRepository repo = mc.getResourcePackRepository();
        // The pack stays installed even while the engine is disabled. Chunk meshes built while
        // the engine was on carry light data in the packed colored vertex format, and Sodium
        // only rebuilds sections progressively — the stock shaders misdecode those stale
        // vertices as garbage (often full-bright) light. The patched shaders understand both
        // formats and render plain vanilla lighting when u_ColoredLightingEnabled is 0, so
        // toggling needs no shader swap (and therefore no resource reload) at all.
	    InternalPackRegistration.enforcePacks(mc, repo);
    }
	
	@Deprecated(forRemoval = true)
	public static ColoredLightEngine getInstance() {
		throw new RuntimeException("Unsupported.");
	}
	
	public static void forEach(Consumer<ColoredLightEngine> engineConsumer) {
		for (ColoredLightEngine coloredLightEngine : TRACKED) {
			if (coloredLightEngine == null) continue;
			engineConsumer.accept(coloredLightEngine);
		}
	}
	
	public void updateFrustum(Frustum frustum) {
        this.frustum = frustum;
    }
	
	public void unload() {
		clear();
		synchronized (TRACKED) {
			TRACKED.remove(this);
		}
		
		if (FlywheelCompat.isAvailable()) {
			FlywheelCompat compat = ((LevelAttachments) level).colorfullighting$getFlywheelCompat();
			if (compat != null)
				compat.getStorage().delete();
		}
	}
	
	public LevelAccessor getLevel() {
		return level;
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

    public int sampleLightColorInt(BlockPos pos) { return sampleLightColorInt(pos.getX(), pos.getY(), pos.getZ()); }
    public int sampleLightColorInt(int x, int y, int z) {
        int packed = sampleLightColorPacked(x, y, z);
        return packed;
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
        if (dynamicLights != null) {
            light = dynamicLights.maxWithDynamicLightPacked(x, y, z, light);
        }

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
    private int vanillaBlockLightAsWhitePacked(SectionCursor cursor, int x, int y, int z) {
        fallbackSamples.incrementAndGet();
		Level level = this.level.getLevel();
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
     * Reconciles the extra regions in {@link #REGION_NAMESPACE_DEFAULT} with {@code desired}.
     * Callers may pass the same LightRegion instances every tick; unchanged regions are skipped by
     * identity before equality. Client thread only, like {@link #updateViewArea}.
     */
    public void syncExtraRegions(Map<Long, LightRegion> desired) {
        syncExtraRegions(REGION_NAMESPACE_DEFAULT, desired);
    }

    /**
     * Same as {@link #syncExtraRegions(Map)} but reconciles only the regions whose keys carry the
     * given namespace bits, so independent owners (VS ships, remote-level cells) can each sync
     * every tick without wiping the other's regions. Every key in {@code desired} must carry the
     * namespace.
     */
    public void syncExtraRegions(long namespace, Map<Long, LightRegion> desired) {
        if (!enabled) return;
        if (extraRegions.isEmpty() && desired.isEmpty()) return;

        boolean changed = false;
        for (Long key : List.copyOf(extraRegions.keySet())) {
            if ((key & REGION_NAMESPACE_MASK) != namespace) continue;
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
                            if (viewArea.contains(x, z)) continue; // already held by the view area
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

	// no harm in having this be public
    public static boolean inAnyArea(ViewArea[] areas, int x, int z) {
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
        
        lightPropagator.applyReadyChanges(this);

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
        // The accessor is the current dimension's renderer, so it only applies when this engine's
        // level IS the current one — a remote level (Immersive Portals) must not schedule its
        // sections there; its own renderer is reached through level.setSectionDirty below.
        SodiumWorldRendererAccessor sodiumRenderer = null;
        if (SodiumCompat.isSodiumLoaded() && Minecraft.getInstance().level == level.getLevel()
                && Minecraft.getInstance().levelRenderer instanceof SodiumWorldRendererAccessor accessor) {
            sodiumRenderer = accessor;
        }
        boolean flywheelTracking = FlywheelCompat.isAvailable();

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
	            ((LevelAttachments) this.level).colorfullighting$getFlywheelCompat().getStorage().recollectSectionIfTracked(dirtySection);
            }
        }

        // Remember these sections' colour for Distant Horizons LODs (no-op unless /cl dh is on)
        if (DhCompat.isOverrideEnabled()) {
            DhCompat.onSectionsDirty(this, sectionsToUpdate);
        }
    }

    /**
     * DH compat accessors: the {@link DhColorCache}
     * capture worker reads sections directly, racing the propagator the same benign way render-thread
     * sampling does. Null when the section has left every tracked area.
     */
    /**
     * Diagnostics: samples that hit the out-of-area white fallback (no stored section). Incremented
     * from chunk-build workers, so atomic.
     */
    private final AtomicInteger fallbackSamples = new AtomicInteger();

    public int debugFallbackSamples() {
        return fallbackSamples.get();
    }

    /** Diagnostics: sections currently stored (view area plus extra regions). */
    public int debugStoredSectionCount() {
        return storage.sectionCount();
    }

    /** Diagnostics: chunks queued or already propagated for the current coverage. */
    public int debugQueuedChunkCount() {
        return queuedChunks.size();
    }

    /** Diagnostics: the view area as chunk bounds, or "none" before the first level tick. */
    public String debugViewArea() {
        if (viewArea.maxX < viewArea.minX) return "none";
        return "[" + viewArea.minX + ".." + viewArea.maxX + ", " + viewArea.minZ + ".." + viewArea.maxZ + "]";
    }

    public ColoredLightSection dhGetLightSection(long sectionPos) {
        return storage.getSection(sectionPos);
    }

    public ColoredLightSection dhGetDarknessSection(long sectionPos) {
        return darknessStorage.getSection(sectionPos);
    }

    /**
     * Visits every section that currently holds colour data. Used by the Nvidium compat to
     * re-mesh only the sections whose baked tint went stale, instead of the whole world.
     */
    public void forEachPopulatedSection(java.util.function.LongConsumer action) {
        storage.forEachPopulatedSection(action);
    }

    /**
     * Whether a section's light data is trustworthy enough to remember for DH LODs: only inner
     * view-area chunks are fully propagated. Border chunks hold partial spill-in, and a chunk on
     * the trailing edge of a moving view area can get re-propagated with its neighbours already
     * unloaded — capturing that would overwrite a good remembered state with clipped light.
     * Client thread (viewArea is client-thread state, like updateViewArea).
     */
    public boolean dhIsSectionCaptureSafe(long sectionPos) {
        return viewArea.containsInner(SectionPos.x(sectionPos), SectionPos.z(sectionPos));
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
		clear();
        
        if (enabled) {
            lightPropagator = new LightPropagator(this);
            lightPropagatorThread = new Thread(lightPropagator, "CL-LightPropagator");
            lightPropagatorThread.setPriority(Thread.MIN_PRIORITY);
	        running = true;
	        lightPropagatorThread.start();
			
            // Log the setting actually in force: an invalid or clobbered config value is corrected
            // silently by Forge, so the file on disk is not evidence of what the engine is using.
            ColorfulLighting.LOGGER.info("Colored light engine reset (lightUpdateSpeed={})",
                    ColorfulLightingConfig.lightUpdateSpeed());
        } else {
            ColorfulLighting.LOGGER.info("Colored light engine disabled");
        }
    }
	
	private void clear() {
		if(lightPropagator != null) {
			running = false;
			lightPropagator.stop();
			try {
				lightPropagatorThread.join(MAX_BLOCKED_SLEEP_MILLIS);
			} catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
			if (lightPropagatorThread.isAlive()) {
				lightPropagatorThread.interrupt();
			}
//			if (lightPropagatorThread.isAlive()) {
//				lightPropagatorThread.stop();
//			}
			if (lightPropagatorThread.isAlive()) {
				try {
					lightPropagatorThread.join(MAX_BLOCKED_SLEEP_MILLIS * 4);
				} catch (InterruptedException e) {
					throw new RuntimeException(e);
				}
			}
			lightPropagator = null;
			lightPropagatorThread = null;
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
		
		if (FlywheelCompat.isAvailable()) {
			FlywheelCompat compat = ((LevelAttachments) level).colorfullighting$getFlywheelCompat();
			if (compat != null)
				compat.getStorage().recollectAllTracked();
		}
	}
	
	public static class BlockRequests {
        public BlockPos blockPos;
        // ArrayDeque, not LinkedList: propagation enqueues millions of requests per minute and
        // LinkedList allocates a Node per element (visible in the 2026-08-06 JFR captures)
        public Queue<LightUpdateRequest> increaseRequests = new ArrayDeque<>();

        public BlockRequests(BlockPos blockPos) {
            this.blockPos = blockPos;
        }
    }

    public static class LightUpdateRequest {
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

    public record DelayedChunkUpdate(ChunkPos chunkPos, long executeTime) {}
}