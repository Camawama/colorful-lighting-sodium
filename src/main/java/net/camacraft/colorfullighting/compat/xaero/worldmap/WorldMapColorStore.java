package net.camacraft.colorfullighting.compat.xaero.worldmap;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Every {@link RegionColors} of the session, by file: read once (on the World Map's own loading thread when it
 * loads a region, or in the background for a region only seen zoomed out), written in the background a few seconds
 * after it changes and when leaving the world. Uses no Xaero class.
 */
@Mod.EventBusSubscriber(modid = "colorful_lighting", value = Dist.CLIENT)
public final class WorldMapColorStore {
    static final Logger LOGGER = LoggerFactory.getLogger("ColorfulLighting/XaeroWorldMap");
    private static final long SAVE_DELAY_SECONDS = 5;

    private static final Map<Path, RegionColors> REGIONS = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService IO = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "CL-XaeroMapColors");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicBoolean SAVE_SCHEDULED = new AtomicBoolean();

    private WorldMapColorStore() {
    }

    /** The region's colors, read now if they aren't yet (blocks the caller for the read). */
    public static RegionColors loaded(Path file) {
        RegionColors region = REGIONS.computeIfAbsent(file, RegionColors::new);
        if (!region.isLoaded()) region.load();
        return region;
    }

    /** The region's colors if they have been read; else starts reading them in the background and answers null. */
    public static RegionColors loadedOrRequest(Path file) {
        RegionColors region = REGIONS.computeIfAbsent(file, RegionColors::new);
        if (region.isLoaded()) return region;
        if (region.requestLoad()) IO.execute(region::load);
        return null;
    }

    /** Something in a region changed: write the changed regions in a few seconds. */
    public static void changed() {
        if (SAVE_SCHEDULED.compareAndSet(false, true)) {
            IO.schedule(() -> {
                SAVE_SCHEDULED.set(false);
                saveAll();
            }, SAVE_DELAY_SECONDS, TimeUnit.SECONDS);
        }
    }

    private static void saveAll() {
        for (RegionColors region : REGIONS.values()) {
            region.saveIfDirty();
        }
    }

    /** Leaving the world: write what changed and forget the session's regions. */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        saveAndForget();
    }

    /** Writes what changed and forgets every region (they are read again from their files when next needed). */
    public static void saveAndForget() {
        try {
            IO.submit(() -> {
                saveAll();
                REGIONS.clear();
            }).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOGGER.warn("Couldn't save Xaero map colors: {}", e.toString());
        }
    }

    /** How many regions are in memory, and how many of their tile chunks have colors (pack harness). */
    public static int[] census() {
        int regions = 0, tiles = 0;
        for (RegionColors region : REGIONS.values()) {
            regions++;
            for (int i = 0; i < 64; i++) {
                if (region.any(i)) tiles++;
            }
        }
        return new int[]{regions, tiles};
    }
}
