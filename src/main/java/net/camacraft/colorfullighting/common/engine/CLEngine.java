package net.camacraft.colorfullighting.common.engine;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.camacraft.colorfullighting.common.ColoredLightEngine;
import net.camacraft.colorfullighting.common.ViewArea;
import net.camacraft.colorfullighting.common.accessors.LevelAccessor;
import net.camacraft.colorfullighting.common.accessors.mixin.LevelAttachments;
import net.camacraft.colorfullighting.common.engine.cl.ColoredLightSection;
import net.camacraft.colorfullighting.common.engine.cl.DefaultBlockLightEngine;
import net.camacraft.colorfullighting.common.engine.cl.LightPropagator;
import net.camacraft.colorfullighting.compat.distanthorizons.DhColorCache;
import net.camacraft.colorfullighting.compat.dynamiclights.DynamicLightsCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static net.camacraft.colorfullighting.common.ColoredLightEngine.MAX_BLOCKED_SLEEP_MILLIS;

public class CLEngine extends AbstractColoredLightEngine {
	public final DefaultBlockLightEngine lightEngine = new DefaultBlockLightEngine(true, this);
	public final DefaultBlockLightEngine darkEngine = new DefaultBlockLightEngine(false, this);
	public LightPropagator lightPropagator;
	private final DynamicLightsCompat dynamicLights;
	
	public CLEngine(ColoredLightEngine lightInterface) {
		super(lightInterface);
		this.dynamicLights = ((LevelAttachments) lightInterface.getLevel()).colorfullighting$getDynamicLights();
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
	
	@Override
	public String describeQueue(ChunkPos center) {
		Thread thread = lightPropagatorThread;
		StringBuilder sb = new StringBuilder();
		sb.append("propagator thread: ").append(
				thread == null ? "none" : thread.isAlive() ? "alive" : "DEAD <- the bug; run /cl purge and report your log");
		sb.append("\n").append(lightEngine.describeQueue())
				.append("\n").append(darkEngine.describeQueue());
		
		
		sb.append("\nchunks waiting: light ").append(chunksWaitingForPropagation.size())
				.append(", darkness ").append(chunksWaitingForDarknessPropagation.size());
		int listed = 0;
		for (ChunkPos pos : chunksWaitingForPropagation) {
			if (pos.getChessboardDistance(center) > 8) continue;
			if (listed == 0) sb.append("\nwaiting chunks near you:");
			sb.append("\n  ").append(pos).append(" dist=").append(pos.getChessboardDistance(center))
					.append(" missingNeighbours[");
			boolean first = true;
			for (int ox = -1; ox <= 1; ++ox) {
				for (int oz = -1; oz <= 1; ++oz) {
					if (!lightInterface.getLevel().hasChunk(new ChunkPos(pos.x + ox, pos.z + oz))) {
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
	
	/**
	 * Vanilla block light at the position, as a packed white 12-bit colour. Reading the client light
	 * engine from Sodium's chunk-build workers matches what vanilla meshing does (RenderChunkRegion
	 * reads it from workers too), so it is as thread-safe as vanilla itself. Positions outside the
	 * build height keep returning 0, same as the missing-section behaviour this falls back from.
	 */
	private int vanillaBlockLightAsWhitePacked(ColoredLightEngine.SectionCursor cursor, int x, int y, int z) {
		fallbackSamples.incrementAndGet();
		Level level = this.lightInterface.getLevel().getLevel();
		if (level == null || level.isOutsideBuildHeight(y)) return 0;
		int brightness = level.getBrightness(LightLayer.BLOCK, cursor.fallbackPos.set(x, y, z));
		if (brightness <= 0) return 0;
		return brightness << 8 | brightness << 4 | brightness;
	}
	
	public int sampleLightColorPacked(ColoredLightEngine.SectionCursor cursor, int x, int y, int z) {
		if (!ColoredLightEngine.isEnabled()) return 0;
		
		long sectionPos = SectionPos.asLong(x >> 4, y >> 4, z >> 4);
		int version = lightInterface.getStructureVersion();
		
		if (cursor.sectionPos != sectionPos || cursor.version != version) {
			cursor.light = lightEngine.getSection(sectionPos);
			cursor.darkness = darkEngine.getSection(sectionPos);
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
	
	@Override
	public void queuePropagation(ChunkPos chunkPos) {
		chunksWaitingForPropagation.add(chunkPos);
		chunksWaitingForDarknessPropagation.add(chunkPos);
	}
	
	@Override
	public Object getStorageLock() {
		return storageLock;
	}
	
	@Override
	public void remove(ViewArea newArea) {
		lightEngine.remove(newArea);
		darkEngine.remove(newArea);
		chunksWaitingForPropagation.removeIf(chunkPos -> !newArea.containsInner(chunkPos.x, chunkPos.z) && !lightInterface.extraRegionsContainInner(chunkPos.x, chunkPos.z));
		chunksWaitingForDarknessPropagation.removeIf(chunkPos -> !newArea.containsInner(chunkPos.x, chunkPos.z) && !lightInterface.extraRegionsContainInner(chunkPos.x, chunkPos.z));
	}
	
	@Override
	public void removeAlt(ViewArea oldArea) {
		lightEngine.removeAlt(oldArea);
		darkEngine.removeAlt(oldArea);
		chunksWaitingForPropagation.removeIf(chunkPos -> oldArea.containsInner(chunkPos.x, chunkPos.z) && !lightInterface.isChunkTrackedInner(chunkPos.x, chunkPos.z));
		chunksWaitingForDarknessPropagation.removeIf(chunkPos -> oldArea.containsInner(chunkPos.x, chunkPos.z) && !lightInterface.isChunkTrackedInner(chunkPos.x, chunkPos.z));
	}
	
	@Override
	public void removeSection(long sectionPos) {
		lightEngine.removeSection(sectionPos);
		darkEngine.removeSection(sectionPos);
	}
	
	@Override
	public void addSection(long pos) {
		lightEngine.addSection(pos);
		darkEngine.addSection(pos);
	}
	
	@Override
	public long[] applyReadyChanges() {
		lightPropagator.applyReadyChanges(this, lightEngine);
		lightPropagator.applyReadyChanges(this, darkEngine);
		
		long[] sectionsToUpdate;
		synchronized (dirtySections) {
			if (dirtySections.isEmpty()) {
				return null;
			}
			sectionsToUpdate = dirtySections.toLongArray();
			dirtySections.clear();
		}
		
		return sectionsToUpdate;
	}
	
	@Override
	public int sectionCount() {
		return lightEngine.sectionCount();
	}
	
	@Override
	public AbstractColoredLightSection getSection(boolean forLight, long pos) {
		return (forLight ? lightEngine : darkEngine).getSection(pos);
	}
	
	@Override
	public void clear() {
		lightEngine.clear();
		darkEngine.clear();
		delayedChunkUpdates.clear();
		pendingDelayedUpdates.clear();
		chunksWaitingForPropagation.clear();
		chunksWaitingForDarknessPropagation.clear();
		dirtySections.clear();
	}
	
	@Override
	public void blockUpdated(LevelAccessor level, BlockPos blockPos) {
		CLEngineInnerClasses.BlockRequests increaseRequests = new CLEngineInnerClasses.BlockRequests(blockPos);
		lightEngine.handleBlockUpdate(level, increaseRequests, blockPos);
		
		CLEngineInnerClasses.BlockRequests darknessIncreaseRequests = new CLEngineInnerClasses.BlockRequests(blockPos);
		darkEngine.handleBlockUpdate(level, darknessIncreaseRequests, blockPos);
	}
	
	// CODE REGION: threading logic
	public static final boolean USE_THREAD = true;
	public volatile boolean running = true;
	private Thread lightPropagatorThread;
	
	@Override
	public void tick() {
		if (lightPropagatorThread == null) {
			lightPropagator.doWork();
		}
	}
	
	@Override
	public void start() {
		lightPropagator = new LightPropagator(this);
		if (USE_THREAD) {
			lightPropagatorThread = new Thread(lightPropagator, "CL-LightPropagator");
			lightPropagatorThread.setPriority(Thread.MIN_PRIORITY);
			running = true;
			lightPropagatorThread.start();
		} else {
			running = true;
		}
	}
	
	@Override
	public void stop() {
		if(lightPropagator != null) {
			running = false;
			if (lightPropagatorThread != null) {
				lightPropagator.stop();
				try {
					lightPropagatorThread.join(MAX_BLOCKED_SLEEP_MILLIS);
				} catch (InterruptedException e) {
					throw new RuntimeException(e);
				}
				if (lightPropagatorThread.isAlive()) {
					lightPropagatorThread.interrupt();
				}
//				if (lightPropagatorThread.isAlive()) {
//					lightPropagatorThread.stop();
//				}
				if (lightPropagatorThread.isAlive()) {
					try {
						lightPropagatorThread.join(MAX_BLOCKED_SLEEP_MILLIS * 4);
					} catch (InterruptedException e) {
						throw new RuntimeException(e);
					}
				}
			}
			lightPropagator = null;
			lightPropagatorThread = null;
		}
	}
	
	// CODE REGION: propagator variables
	public final ConcurrentLinkedQueue<CLEngineInnerClasses.DelayedChunkUpdate> delayedChunkUpdates = new ConcurrentLinkedQueue<>();
	public final Set<ChunkPos> pendingDelayedUpdates = ConcurrentHashMap.newKeySet();
	// Sets, not queues: ConcurrentLinkedQueue.remove is O(n) and ran once per propagated chunk.
	// Ordering now comes from LightPropagator.ChunkOrder instead of rescanning the collection.
	public final Set<ChunkPos> chunksWaitingForPropagation = ConcurrentHashMap.newKeySet();
	public final Set<ChunkPos> chunksWaitingForDarknessPropagation = ConcurrentHashMap.newKeySet();
	/**
	 * Primitive: this fills and drains fast enough during chunk loading that boxing a Long per section
	 * showed up on the render thread.
	 */
	public final LongOpenHashSet dirtySections = new LongOpenHashSet();
	public final Set<Long> sectionsToRebuildLater = ConcurrentHashMap.newKeySet();
	
	@Override
	public void rebuildChunk(ChunkPos chunkPos, long delay) {
		if (pendingDelayedUpdates.add(chunkPos)) {
			delayedChunkUpdates.add(new CLEngineInnerClasses.DelayedChunkUpdate(chunkPos, System.currentTimeMillis() + delay));
		}
	}
	
	/**
	 * Guards writers against each other only. Sampling never takes it: the storages are concurrent maps
	 * of volatile-published sections, so readers race with the propagator by design and lose at worst a
	 * single frame of colour on a block that is already queued for a re-mesh. Taking this lock per sample
	 * serialised all ten Sodium chunk-build workers behind one monitor.
	 */
	public final Object storageLock = new Object();
}
