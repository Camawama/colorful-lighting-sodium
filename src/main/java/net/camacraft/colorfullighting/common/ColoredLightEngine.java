package net.camacraft.colorfullighting.common;

import net.camacraft.colorfullighting.ColorfulLighting;
import net.camacraft.colorfullighting.common.accessors.ClientAccessor;
import net.camacraft.colorfullighting.common.accessors.LevelAccessor;
import net.camacraft.colorfullighting.common.accessors.mixin.LevelAttachments;
import net.camacraft.colorfullighting.common.engine.AbstractColoredLightEngine;
import net.camacraft.colorfullighting.common.engine.CLEngine;
import net.camacraft.colorfullighting.common.engine.cl.DefaultBlockLightEngine;
import net.camacraft.colorfullighting.common.engine.cl.LightPropagator;
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


// TODO: rename to ColoredLightInterface
/**
 * Class responsible for managing light color values in the client's world and sampling those values.
 * Most work is delegated to LightPropagator thread.
 */
public class ColoredLightEngine {
	private static final WeakList<ColoredLightEngine> TRACKED = new WeakList<>(new ArrayList<>());
	
	private final ClientAccessor clientAccessor;
	protected final LevelAccessor level;
    /**
     * Bumped after sections are added to or removed from either storage. Sampling threads cache the
     * section they last touched and discard that cache when this moves, so a section swapped out from
     * under a reader is never read for more than one sample. Bumped from both the client thread
     * (updateViewArea) and the propagator thread (performRegionRebuild), hence atomic.
     */
    protected final AtomicInteger structureVersion = new AtomicInteger();
	protected final ThreadLocal<SectionCursor> sectionCursor = ThreadLocal.withInitial(SectionCursor::new);
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
	private AbstractColoredLightEngine engine;
    protected volatile ViewArea[] extraRegionAreas = new ViewArea[0];
    /**
     * Chunks already queued for this view area. Replaces the old "skip anything in the previous inner
     * area" rule, which could never re-queue a chunk that was skipped because the server had not sent
     * its neighbours yet. Client thread only (updateViewArea and reset).
     */
	// seems to be more so used as a "currently tracked chunks" than a "queued chunks"?
    private final Set<ChunkPos> queuedChunks = new HashSet<>();
	
    // volatile: read from Sodium chunk-build worker threads and the light propagator thread
    private static volatile boolean enabled = true;
    private static boolean packsInitialized = false;
	
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
        this.clientAccessor = clientAccessor;
        reset();
	    synchronized (TRACKED) {
		    TRACKED.add(this);
			TRACKED.prune();
	    }
    }
	
	/* Outlined: convenient place for mixing into to swap out the engine */
	private AbstractColoredLightEngine createEngine(Level level, ClientAccessor clientAccessor) {
		return new CLEngine(this);
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
	}
    
    /**
     * Diagnostic snapshot for {@code /cl debug queue}, built for the long-standing "colored light
     * silently stops until /cl purge" bug: shows whether this level's propagator thread is even
     * alive, the queue depths, and the nearest waiting chunks with the neighbour availability the
     * queue's readiness check consults.
     */
    public String describeQueues(ChunkPos center) {
        StringBuilder sb = new StringBuilder();
        sb.append("engine enabled: ").append(enabled);
//		sb.append("\n").append(lightEngine.describeQueue());
	    sb.append("\n").append(engine.describeQueue(center));
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
	
	public int getStructureVersion() {
		return structureVersion.get();
	}
	
	public int incrementStructureVersion() {
		return structureVersion.incrementAndGet();
	}
	
	public int debugFallbackSamples() {
		return engine.debugFallbackSamples();
	}
	
	public Frustum getFrustum() {
		return frustum;
	}
	
	public ViewArea[] getExtraRegionAreas() {
		return extraRegionAreas;
	}
	
	/**
     * Per-thread memo of the last section sampled. Sodium walks a chunk build in section order and takes
     * roughly ten samples per block face, nearly all of them landing in the section already cached here,
     * so this turns two concurrent-map lookups per sample into two per section.
     */
    public static final class SectionCursor {
        public long sectionPos = Long.MIN_VALUE;
		public int version = -1;
		public ColoredLightSection light;
		public ColoredLightSection darkness;
		public final BlockPos.MutableBlockPos fallbackPos = new BlockPos.MutableBlockPos();
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

        return engine.sampleLightColorPacked(cursor, x, y, z);
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
        engine.remove(newArea);
        queuedChunks.removeIf(chunkPos -> !newArea.containsInner(chunkPos.x, chunkPos.z) && !extraRegionsContainInner(chunkPos.x, chunkPos.z));
        // remove sections from storage
        synchronized (engine.getStorageLock()) {
            for(int x = viewArea.minX; x <= viewArea.maxX; ++x) {
                for(int z = viewArea.minZ; z <= viewArea.maxZ; ++z) {
                    if(newArea.contains(x, z)) continue;
                    if(extraRegionsContain(x, z)) continue;
                    for(int y = level.getMinSectionY(); y <= level.getMaxSectionY(); y++) {
                        long sectionPos = SectionPos.asLong(x, y, z);
                        engine.removeSection(sectionPos);
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
        synchronized (engine.getStorageLock()) {
            for(int x = newArea.minX; x <= newArea.maxX; ++x) {
                for(int z = newArea.minZ; z <= newArea.maxZ; ++z) {
                    if(!viewArea.contains(x, z)) { // section data is not carried over from the old area
                        for(int y = level.getMinSectionY(); y <= level.getMaxSectionY(); y++) {
                            long pos = SectionPos.asLong(x, y, z);
                            engine.addSection(pos);
                        }
                    }
                    if(newArea.containsInner(x, z) && canEverPropagate(centerX, centerZ, viewDistance, x, z)) {
                        ChunkPos chunkPos = new ChunkPos(x, z);
                        if(queuedChunks.add(chunkPos)) { // not already queued or propagated for this area
                            engine.queuePropagation(chunkPos);
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
	        engine.removeAlt(oldArea);
            queuedChunks.removeIf(chunkPos -> oldArea.containsInner(chunkPos.x, chunkPos.z) && !isChunkTrackedInner(chunkPos.x, chunkPos.z));

            synchronized (engine.getStorageLock()) {
                for (int x = oldArea.minX; x <= oldArea.maxX; ++x) {
                    for (int z = oldArea.minZ; z <= oldArea.maxZ; ++z) {
                        if (isColumnTracked(x, z)) continue;
                        for (int y = level.getMinSectionY(); y <= level.getMaxSectionY(); y++) {
                            long sectionPos = SectionPos.asLong(x, y, z);
                            engine.removeSection(sectionPos);
                        }
                    }
                }
            }
            structureVersion.incrementAndGet();
        }

        if (target != null) {
            if (!newArea.equals(oldArea)) {
                synchronized (engine.getStorageLock()) {
                    for (int x = newArea.minX; x <= newArea.maxX; ++x) {
                        for (int z = newArea.minZ; z <= newArea.maxZ; ++z) {
                            if (oldArea != null && oldArea.contains(x, z)) continue;
                            if (viewArea.contains(x, z)) continue; // already held by the view area
                            for (int y = level.getMinSectionY(); y <= level.getMaxSectionY(); y++) {
                                long sectionPos = SectionPos.asLong(x, y, z);
                                engine.addSection(sectionPos);
                            }
                        }
                    }
                }
                structureVersion.incrementAndGet();
            }
            for (ChunkPos chunkPos : target.chunksToQueue()) {
                if (!newArea.containsInner(chunkPos.x, chunkPos.z)) continue;
                if (queuedChunks.add(chunkPos)) {
                    engine.queuePropagation(chunkPos);
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
	
	public boolean extraRegionsContain(int x, int z) {
        if (extraRegions.isEmpty()) return false;
        for (LightRegion region : extraRegions.values()) {
            if (region.area().contains(x, z)) return true;
        }
        return false;
    }
	
	public boolean extraRegionsContainInner(int x, int z) {
        if (extraRegions.isEmpty()) return false;
        for (LightRegion region : extraRegions.values()) {
            if (region.area().containsInner(x, z)) return true;
        }
        return false;
    }

    public boolean extraRegionsContainBlockInner(BlockPos pos) {
        return extraRegionsContainInner(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
    }

    /** Whether the chunk is an inner (actively updated) chunk of the view area or any extra region. */
    public boolean isChunkTrackedInner(int x, int z) {
        return viewArea.containsInner(x, z) || extraRegionsContainInner(x, z);
    }

    public boolean isBlockTrackedInner(BlockPos pos) {
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

		engine.blockUpdated(level, blockPos);
    }

    public void onLightUpdate() {
        if (!enabled) return;

        long[] sectionsToUpdate = engine.applyReadyChanges();
		if (sectionsToUpdate == null)
			return;

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

    /** Diagnostics: sections currently stored (view area plus extra regions). */
    public int debugStoredSectionCount() {
        return engine.sectionCount();
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
	
	public ColoredLightSection getSection(boolean forLight, long pos) {
		return engine.getSection(forLight, pos);
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
        engine.rebuildChunk(chunkPos, delay);
    }

    public void reset() {
		clear();
        
        if (enabled) {
			engine.start();
			
            // Log the setting actually in force: an invalid or clobbered config value is corrected
            // silently by Forge, so the file on disk is not evidence of what the engine is using.
            ColorfulLighting.LOGGER.info("Colored light engine reset (lightUpdateSpeed={})",
                    ColorfulLightingConfig.lightUpdateSpeed());
        } else {
            ColorfulLighting.LOGGER.info("Colored light engine disabled");
        }
    }
	
	private void clear() {
		if (engine != null) {
			engine.stop();
			engine.clear();
		}
		engine = createEngine(level.getLevel(), clientAccessor);
		structureVersion.incrementAndGet();
		viewArea = new ViewArea();
		extraRegions.clear(); // region owners (e.g. VS compat) re-sync them on the next tick
		extraRegionAreas = new ViewArea[0];
		queuedChunks.clear();
		
		if (FlywheelCompat.isAvailable()) {
			FlywheelCompat compat = ((LevelAttachments) level).colorfullighting$getFlywheelCompat();
			if (compat != null)
				compat.getStorage().recollectAllTracked();
		}
	}
	
	public void tick() {
		engine.tick();
	}
}