package me.erykczy.colorfullighting.common;

import me.erykczy.colorfullighting.ColorfulLighting;
import me.erykczy.colorfullighting.common.accessors.BlockStateAccessor;
import me.erykczy.colorfullighting.common.accessors.ClientAccessor;
import me.erykczy.colorfullighting.common.accessors.LevelAccessor;
import me.erykczy.colorfullighting.common.accessors.PlayerAccessor;
import me.erykczy.colorfullighting.common.util.ColorRGB4;
import me.erykczy.colorfullighting.common.util.ColorRGB8;
import me.erykczy.colorfullighting.common.util.MathExt;
import me.erykczy.colorfullighting.compat.flywheel.FlywheelCompat;
import me.erykczy.colorfullighting.compat.sodium.SodiumCompat;
import me.erykczy.colorfullighting.mixin.compat.sodium.SodiumWorldRendererAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
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
    private final Object storageLock = new Object();
    private ViewArea viewArea = new ViewArea();
    private final ConcurrentLinkedQueue<LightUpdateRequest> blockUpdateDecreaseRequests = new ConcurrentLinkedQueue<>(); // those first added will be executed first (this order is required by decrease propagation algorithm)
    private final ConcurrentLinkedQueue<BlockRequests> blockUpdateIncreaseRequests = new ConcurrentLinkedQueue<>(); // those nearest to the player will be executed first
    private final ConcurrentLinkedQueue<LightUpdateRequest> darknessUpdateDecreaseRequests = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<BlockRequests> darknessUpdateIncreaseRequests = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<ChunkPos> chunksWaitingForPropagation = new ConcurrentLinkedQueue<>(); // those nearest to the player will be executed first
    private final ConcurrentLinkedQueue<ChunkPos> chunksWaitingForDarknessPropagation = new ConcurrentLinkedQueue<>();
    private final Set<Long> dirtySections = new HashSet<>();
    private final Set<Long> sectionsToRebuildLater = ConcurrentHashMap.newKeySet();

    private final ConcurrentLinkedQueue<DelayedChunkUpdate> delayedChunkUpdates = new ConcurrentLinkedQueue<>();
    private final Set<ChunkPos> pendingDelayedUpdates = ConcurrentHashMap.newKeySet();

    private LightPropagator lightPropagator;
    private Thread lightPropagatorThread;
    
    private boolean enabled = true;

    private Frustum frustum;

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
            reset();
        }
    }
    
    public boolean isEnabled() {
        return enabled;
    }

    public void updateFrustum(Frustum frustum) {
        this.frustum = frustum;
    }

    public ColorRGB4 sampleLightColor(BlockPos pos) { return sampleLightColor(pos.getX(), pos.getY(), pos.getZ()); }
    public ColorRGB4 sampleLightColor(int x, int y, int z) {
        if (!enabled) return ColorRGB4.fromRGB4(0, 0, 0);
        synchronized (storageLock) {
            ColorRGB4 lightColor = storage.getEntry(x, y, z);
            if (lightColor == null) {
                lightColor = ColorRGB4.BLACK;
            }

            ColorRGB4 darknessColor = darknessStorage.getEntry(x, y, z);
            if (darknessColor == null) {
                darknessColor = ColorRGB4.BLACK;
            }

            if (lightColor == ColorRGB4.BLACK && darknessColor == ColorRGB4.BLACK) {
                return ColorRGB4.BLACK;
            }

            return ColorRGB4.fromRGB4(
                    Math.max(0, lightColor.red4 - darknessColor.red4),
                    Math.max(0, lightColor.green4 - darknessColor.green4),
                    Math.max(0, lightColor.blue4 - darknessColor.blue4)
            );
        }
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


    public void updateViewArea(ViewArea newArea) {
        if (!enabled) return;
        LevelAccessor level = clientAccessor.getLevel();
        if(level == null) return;
        if(viewArea.equals(newArea)) return;

        // unload sections
        // remove propagation requests which are not in newArea's inner area
        blockUpdateIncreaseRequests.removeIf(blockUpdate -> !newArea.containsBlockInner(blockUpdate.blockPos));
        blockUpdateDecreaseRequests.removeIf(blockUpdate -> !newArea.containsBlockInner(blockUpdate.blockPos));
        darknessUpdateIncreaseRequests.removeIf(blockUpdate -> !newArea.containsBlockInner(blockUpdate.blockPos));
        darknessUpdateDecreaseRequests.removeIf(blockUpdate -> !newArea.containsBlockInner(blockUpdate.blockPos));
        chunksWaitingForPropagation.removeIf(chunkPos -> !newArea.containsInner(chunkPos.x, chunkPos.z));
        chunksWaitingForDarknessPropagation.removeIf(chunkPos -> !newArea.containsInner(chunkPos.x, chunkPos.z));
        // remove sections from storage
        synchronized (storageLock) {
            for(int x = viewArea.minX; x <= viewArea.maxX; ++x) {
                for(int z = viewArea.minZ; z <= viewArea.maxZ; ++z) {
                    if(newArea.contains(x, z)) continue;
                    for(int y = level.getMinSectionY(); y <= level.getMaxSectionY(); y++) {
                        long sectionPos = SectionPos.asLong(x, y, z);
                        storage.removeSection(sectionPos);
                        darknessStorage.removeSection(sectionPos);
                    }
                }
            }
        }

        // load sections
        // add sections to storage and queue chunks for propagation
        synchronized (storageLock) {
            for(int x = newArea.minX; x <= newArea.maxX; ++x) {
                for(int z = newArea.minZ; z <= newArea.maxZ; ++z) {
                    if(viewArea.containsInner(x, z)) continue; // old area already contains propagated section
                    boolean viewAreaContainsOuter = viewArea.contains(x, z);
                    if(!viewAreaContainsOuter) {
                        for(int y = level.getMinSectionY(); y <= level.getMaxSectionY(); y++) {
                            long pos = SectionPos.asLong(x, y, z);
                            storage.addSection(pos);
                            darknessStorage.addSection(pos);
                        }
                    }
                    if(newArea.containsInner(x, z)) {
                        chunksWaitingForPropagation.add(new ChunkPos(x, z));
                        chunksWaitingForDarknessPropagation.add(new ChunkPos(x, z));
                    }
                }
            }
        }
        viewArea = newArea;
    }

    public void onBlockLightPropertiesChanged(BlockPos blockPos) {
        if (!enabled) return;
        LevelAccessor level = clientAccessor.getLevel();
        if (level == null) return;

        SectionPos sectionPos = SectionPos.of(blockPos);
        if (!viewArea.containsInner(sectionPos.x(), sectionPos.z())) return;

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
        ColorRGB4 lightColor;
        synchronized (storageLock) {
            lightColor = storage.getEntry(blockPos);
        }
        if (lightColor == null) lightColor = ColorRGB4.fromRGB4(0,0,0);

        if(lightColor.red4 == 0 && lightColor.green4 == 0 && lightColor.blue4 == 0)
            requestLightPullIn(increaseRequests, blockPos);  // block probably destroyed/replaced with transparent, light pull in might be needed
        else
            decreaseRequests.add(new LightUpdateRequest(blockPos, lightColor, false)); // block probably placed/replaced with non-transparent, light might need to be decreased

        // propagate light if new blockState emits light
        if(Config.getEmissionBrightness(level, blockPos, 0) > 0)
            increaseRequests.add(new LightUpdateRequest(blockPos, Config.getColorEmission(level, blockPos), false, true, false));
    }

    private void handleDarknessUpdate(LevelAccessor level, Queue<LightUpdateRequest> increaseRequests, Queue<LightUpdateRequest> decreaseRequests, BlockPos blockPos) {
        ColorRGB4 darknessColor;
        synchronized (storageLock) {
            darknessColor = darknessStorage.getEntry(blockPos);
        }
        if (darknessColor == null) darknessColor = ColorRGB4.fromRGB4(0,0,0);

        if(darknessColor.red4 == 0 && darknessColor.green4 == 0 && darknessColor.blue4 == 0)
            requestDarknessPullIn(increaseRequests, blockPos);
        else
            decreaseRequests.add(new LightUpdateRequest(blockPos, darknessColor, false));

        // propagate darkness if new blockState absorbs light
        if(Config.getAbsorption(level, blockPos, level.getBlockState(blockPos)) > 0)
            increaseRequests.add(new LightUpdateRequest(blockPos, Config.getAbsorptionColor(level, blockPos), false, true, false));
    }

    private void requestLightPullIn(Queue<LightUpdateRequest> increaseRequests, BlockPos blockPos) {
        for(var direction : Direction.values()) {
            BlockPos neighbourPos = blockPos.relative(direction);
            ColorRGB4 neighbourLight;
            synchronized (storageLock) {
                neighbourLight = storage.getEntry(neighbourPos);
            }
            if(neighbourLight == null) continue;

            if(neighbourLight.red4 == 0 && neighbourLight.green4 == 0 && neighbourLight.blue4 == 0) continue;
            increaseRequests.add(new LightUpdateRequest(neighbourPos, null, true, false, true));
        }
    }

    private void requestDarknessPullIn(Queue<LightUpdateRequest> increaseRequests, BlockPos blockPos) {
        for(var direction : Direction.values()) {
            BlockPos neighbourPos = blockPos.relative(direction);
            ColorRGB4 neighbourDarkness;
            synchronized (storageLock) {
                neighbourDarkness = darknessStorage.getEntry(neighbourPos);
            }
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

        Set<Long> sectionsToUpdate;
        synchronized (dirtySections) {
            if (dirtySections.isEmpty()) {
                return;
            }
            sectionsToUpdate = new HashSet<>(dirtySections);
            dirtySections.clear();
        }

        for (Long dirtySection : sectionsToUpdate) {
            SectionPos sectionPos = SectionPos.of(dirtySection);
            level.setSectionDirty(sectionPos.x(), sectionPos.y(), sectionPos.z());

            // Force Sodium rebuild if present
            if (SodiumCompat.isSodiumLoaded()) {
                var renderer = Minecraft.getInstance().levelRenderer;
                if (renderer instanceof SodiumWorldRendererAccessor sodiumRenderer) {
                    sodiumRenderer.scheduleRebuild(sectionPos.x(), sectionPos.y(), sectionPos.z(), true);
                }
            }

            if (net.neoforged.fml.ModList.get().isLoaded("flywheel") && FlywheelCompat.isAvailable()) {
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
        viewArea = new ViewArea();
        dirtySections.clear();
        blockUpdateIncreaseRequests.clear();
        blockUpdateDecreaseRequests.clear();
        darknessUpdateIncreaseRequests.clear();
        darknessUpdateDecreaseRequests.clear();
        chunksWaitingForPropagation.clear();
        chunksWaitingForDarknessPropagation.clear();
        delayedChunkUpdates.clear();
        pendingDelayedUpdates.clear();
        
        if (enabled) {
            lightPropagator = new LightPropagator();
            lightPropagatorThread = new Thread(lightPropagator);
            lightPropagatorThread.start();
            ColorfulLighting.LOGGER.info("Colored light engine reset");
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
        private volatile boolean running;

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

                if (hasWork) {
                    if (hasLightWork) propagateLight();
                    if (hasDarknessWork) propagateDarkness();
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

                try {
                    // Sleep to prevent busy-waiting, sleep longer if there was no work
                    Thread.sleep(hasWork ? 1L : 10L);
                } catch (InterruptedException e) {
                    running = false;
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
            
            synchronized (storageLock) {
                return storage.getEntry(blockPos);
            }
        }

        public ColorRGB4 getLatestDarknessColor(BlockPos blockPos) {
            ColorRGB4 inProgress = darknessChangesInProgress.get(blockPos);
            if (inProgress != null) return inProgress;

            ColorRGB4 ready = darknessChangesReady.get(blockPos);
            if (ready != null) return ready;

            synchronized (storageLock) {
                return darknessStorage.getEntry(blockPos);
            }
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
        private NearestChunkResult getNearestWaitingChunk(LevelAccessor level, PlayerAccessor player) {
            // find chunk nearest player
            var iterator = chunksWaitingForPropagation.iterator();
            int minDistance = Integer.MAX_VALUE;
            ChunkPos nearestChunkPos = null;

            if (frustum != null) {
                List<ChunkPos> visibleChunks = new ArrayList<>();
                while (iterator.hasNext()) {
                    ChunkPos chunkPos = iterator.next();
                    if (!level.hasChunkAndNeighbours(chunkPos)) continue;

                    AABB chunkBox = new AABB(chunkPos.getMinBlockX(), level.getLevel().getMinBuildHeight(), chunkPos.getMinBlockZ(), chunkPos.getMaxBlockX() + 1, level.getLevel().getMaxBuildHeight(), chunkPos.getMaxBlockZ() + 1);
                    if (frustum.isVisible(chunkBox)) {
                        visibleChunks.add(chunkPos);
                    }
                }

                if (!visibleChunks.isEmpty()) {
                    for (ChunkPos chunkPos : visibleChunks) {
                        int distance = chunkPos.getChessboardDistance(player.getChunkPos());
                        if (distance < minDistance) {
                            minDistance = distance;
                            nearestChunkPos = chunkPos;
                        }
                    }
                    return nearestChunkPos == null ? null : new NearestChunkResult(nearestChunkPos, minDistance * 16);
                }
            }

            iterator = chunksWaitingForPropagation.iterator(); // Reset iterator
            while (iterator.hasNext()) {
                ChunkPos chunkPos = iterator.next();
                if(!level.hasChunkAndNeighbours(chunkPos)) continue; // chunk and neighbours must have available block state data
                int distance = chunkPos.getChessboardDistance(player.getChunkPos());
                if (distance < minDistance) {
                    minDistance = distance;
                    nearestChunkPos = chunkPos;
                }
            }
            return nearestChunkPos == null ? null : new NearestChunkResult(nearestChunkPos, minDistance * 16); // distanceBlocks is in blocks
        }

        private NearestChunkResult getNearestWaitingDarknessChunk(LevelAccessor level, PlayerAccessor player) {
            // find chunk nearest player
            var iterator = chunksWaitingForDarknessPropagation.iterator();
            int minDistance = Integer.MAX_VALUE;
            ChunkPos nearestChunkPos = null;

            if (frustum != null) {
                List<ChunkPos> visibleChunks = new ArrayList<>();
                while (iterator.hasNext()) {
                    ChunkPos chunkPos = iterator.next();
                    if (!level.hasChunkAndNeighbours(chunkPos)) continue;

                    AABB chunkBox = new AABB(chunkPos.getMinBlockX(), level.getLevel().getMinBuildHeight(), chunkPos.getMinBlockZ(), chunkPos.getMaxBlockX() + 1, level.getLevel().getMaxBuildHeight(), chunkPos.getMaxBlockZ() + 1);
                    if (frustum.isVisible(chunkBox)) {
                        visibleChunks.add(chunkPos);
                    }
                }

                if (!visibleChunks.isEmpty()) {
                    for (ChunkPos chunkPos : visibleChunks) {
                        int distance = chunkPos.getChessboardDistance(player.getChunkPos());
                        if (distance < minDistance) {
                            minDistance = distance;
                            nearestChunkPos = chunkPos;
                        }
                    }
                    return nearestChunkPos == null ? null : new NearestChunkResult(nearestChunkPos, minDistance * 16);
                }
            }

            iterator = chunksWaitingForDarknessPropagation.iterator(); // Reset iterator
            while (iterator.hasNext()) {
                ChunkPos chunkPos = iterator.next();
                if(!level.hasChunkAndNeighbours(chunkPos)) continue; // chunk and neighbours must have available block state data
                int distance = chunkPos.getChessboardDistance(player.getChunkPos());
                if (distance < minDistance) {
                    minDistance = distance;
                    nearestChunkPos = chunkPos;
                }
            }
            return nearestChunkPos == null ? null : new NearestChunkResult(nearestChunkPos, minDistance * 16); // distanceBlocks is in blocks
        }

        /**
         * apply ready light changes to storage
         */
        private void applyReadyChanges() {
            lightChangesReadyLock.lock();
            try {
                if (!lightChangesReady.isEmpty()) {
                    synchronized (storageLock) {
                        synchronized (dirtySections) {
                            for (var entry : lightChangesReady.entrySet()) {
                                storage.setEntryUnsafe(entry.getKey(), entry.getValue());
                                SectionPos.aroundAndAtBlockPos(entry.getKey(), dirtySections::add);
                            }
                        }
                    }
                    lightChangesReady.clear();
                }
            } finally {
                lightChangesReadyLock.unlock();
            }

            darknessChangesReadyLock.lock();
            try {
                if (!darknessChangesReady.isEmpty()) {
                    synchronized (storageLock) {
                        synchronized (dirtySections) {
                            for (var entry : darknessChangesReady.entrySet()) {
                                darknessStorage.setEntryUnsafe(entry.getKey(), entry.getValue());
                                SectionPos.aroundAndAtBlockPos(entry.getKey(), dirtySections::add);
                            }
                        }
                    }
                    darknessChangesReady.clear();
                }
            } finally {
                darknessChangesReadyLock.unlock();
            }
        }

        /**
         * move light changes in progress to collection of ready light changes
         */
        private void markChangesReady() {
            if (!lightChangesInProgress.isEmpty()) {
                lightChangesReadyLock.lock();
                try {
                    lightChangesReady.putAll(lightChangesInProgress);
                } finally {
                    lightChangesReadyLock.unlock();
                }
                lightChangesInProgress = new ConcurrentHashMap<>();
            }

            if (!darknessChangesInProgress.isEmpty()) {
                darknessChangesReadyLock.lock();
                try {
                    darknessChangesReady.putAll(darknessChangesInProgress);
                } finally {
                    darknessChangesReadyLock.unlock();
                }
                darknessChangesInProgress = new ConcurrentHashMap<>();
            }
        }

        /**
         * apply light changes in progress directly to storage
         */
        private void applyChangesDirectly() {
            if (!lightChangesInProgress.isEmpty()) {
                synchronized (storageLock) {
                    synchronized (dirtySections) {
                        for (var entry : lightChangesInProgress.entrySet()) {
                            storage.setEntryUnsafe(entry.getKey(), entry.getValue());
                            SectionPos.aroundAndAtBlockPos(entry.getKey(), dirtySections::add);
                        }
                    }
                }
                lightChangesInProgress.clear();
            }

            if (!darknessChangesInProgress.isEmpty()) {
                synchronized (storageLock) {
                    synchronized (dirtySections) {
                        for (var entry : darknessChangesInProgress.entrySet()) {
                            darknessStorage.setEntryUnsafe(entry.getKey(), entry.getValue());
                            SectionPos.aroundAndAtBlockPos(entry.getKey(), dirtySections::add);
                        }
                    }
                }
                darknessChangesInProgress.clear();
            }
        }

        /**
         * propagate light in the nearest waiting chunk, handle block light updates
         */
        private void propagateLight() {
            LevelAccessor level = clientAccessor.getLevel();
            if(level == null) return;
            PlayerAccessor player = clientAccessor.getPlayer();
            if(player == null) return;

            // decrease requests are always executed
            if(!blockUpdateDecreaseRequests.isEmpty()) {
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
            }
            else if(nearestBlockRequests != null) {
                blockUpdateIncreaseRequests.remove(nearestBlockRequests.blockUpdate);
                propagateIncreases(level, nearestBlockRequests.blockUpdate.increaseRequests);
                markChangesReady();
            }
        }

        private void propagateDarkness() {
            LevelAccessor level = clientAccessor.getLevel();
            if(level == null) return;
            PlayerAccessor player = clientAccessor.getPlayer();
            if(player == null) return;

            // decrease requests are always executed
            if(!darknessUpdateDecreaseRequests.isEmpty()) {
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
            }
            else if(nearestBlockRequests != null) {
                darknessUpdateIncreaseRequests.remove(nearestBlockRequests.blockUpdate);
                propagateDarknessIncreases(level, nearestBlockRequests.blockUpdate.increaseRequests);
                markChangesReady();
            }
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

            for(var direction : Direction.values()) {
                BlockPos neighbourPos = request.blockPos.relative(direction);
                if(!level.isInBounds(neighbourPos)) continue;
                BlockStateAccessor neighbourState = level.getBlockState(neighbourPos);
                if(neighbourState == null) return false; // section might have got unloaded and propagation should stop

                // Start with vanilla light blocking
                int lightBlocked = Math.max(1, neighbourState.getLightBlock(level, neighbourPos));

                // Override with custom absorption if it's defined
                int customAbsorption = Config.getLightAbsorption(level, neighbourPos, neighbourState);
                
                boolean geometryOccludes = false;
                BlockStateAccessor sourceStateAcc = level.getBlockState(request.blockPos);
                if (sourceStateAcc != null) {
                    BlockState sourceBlockState = sourceStateAcc.getBlockState();
                    BlockState neighborBlockState = neighbourState.getBlockState();
                    
                    boolean sourceOccludes = sourceBlockState.useShapeForLightOcclusion();
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
                        if (geometryOccludes) {
                            lightBlocked = 15;
                        } else {
                            lightBlocked = 1;
                        }
                    }
                } else {
                    if (geometryOccludes) {
                        lightBlocked = 15;
                    }
                }
                
                // Calculate transmittance based on both source exit and destination entry
                BlockStateAccessor sourceState = level.getBlockState(request.blockPos);
                ColorRGB4 exitTransmittance = sourceState == null ? ColorRGB4.WHITE : Config.getColoredLightTransmittance(level, request.blockPos, sourceState, direction);
                ColorRGB4 entryTransmittance = Config.getColoredLightTransmittance(level, neighbourPos, neighbourState, direction.getOpposite());
                
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

            for(var direction : Direction.values()) {
                BlockPos neighbourPos = request.blockPos.relative(direction);
                if(!level.isInBounds(neighbourPos)) continue;
                BlockStateAccessor neighbourState = level.getBlockState(neighbourPos);
                if(neighbourState == null) return false; // section might have got unloaded and propagation should stop

                int lightBlocked = Math.max(1, neighbourState.getLightBlock(level, neighbourPos));
                
                BlockStateAccessor sourceStateAcc = level.getBlockState(request.blockPos);
                if (sourceStateAcc != null) {
                    BlockState sourceBlockState = sourceStateAcc.getBlockState();
                    BlockState neighborBlockState = neighbourState.getBlockState();
                    
                    boolean sourceOccludes = sourceBlockState.useShapeForLightOcclusion();
                    boolean neighborOccludes = neighborBlockState.useShapeForLightOcclusion();
                    
                    if (sourceOccludes || neighborOccludes) {
                        VoxelShape sourceFaceShape = sourceOccludes ? sourceBlockState.getFaceOcclusionShape(level.getLevel(), request.blockPos, direction) : Shapes.empty();
                        VoxelShape neighbourFaceShape = neighborOccludes ? neighborBlockState.getFaceOcclusionShape(level.getLevel(), neighbourPos, direction.getOpposite()) : Shapes.empty();
                        
                        if (Shapes.faceShapeOccludes(sourceFaceShape, neighbourFaceShape)) {
                            lightBlocked = 15;
                        }
                    }
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
            // repropagate removed light
            if(Config.getEmissionBrightness(level, request.blockPos, blockState) > 0) {
                increaseRequests.add(new LightUpdateRequest(request.blockPos, Config.getColorEmission(level, request.blockPos), false, true, false));
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
            // repropagate removed darkness
            if(Config.getAbsorption(level, request.blockPos, blockState) > 0) {
                increaseRequests.add(new LightUpdateRequest(request.blockPos, Config.getAbsorptionColor(level, request.blockPos), false, true, false));
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