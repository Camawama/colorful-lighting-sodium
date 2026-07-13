package me.erykczy.colorfullighting.compat.valkyrienskies;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.erykczy.colorfullighting.ColorfulLighting;
import me.erykczy.colorfullighting.common.ViewArea;
import net.minecraft.world.level.ChunkPos;

/**
 * Valkyrien Skies compat: keeps a colored light region (see ColoredLightEngine.LightRegion) alive
 * for every loaded ship, following the ship's shipyard chunks, so colored light propagates on ships
 * exactly like in the world. This class deliberately references no VS types — it is safe to load
 * when VS is absent; everything VS-specific lives in {@link VsCompatImpl}, loaded on first tick.
 */
public final class VsCompat {
    private static boolean available;
    /**
     * Immutable per-ship snapshot published for other threads. The light propagator consults it via
     * {@link #isKnownEmptyShipChunk} while the client thread replaces the array; entries are never
     * mutated after publication.
     */
    private static volatile ShipSnapshot[] snapshot = new ShipSnapshot[0];

    private VsCompat() {}

    public static void init() {
        available = true;
    }

    public static boolean isAvailable() {
        return available;
    }

    /** Called once per client tick from ClientEventListener; no-op when VS is absent. */
    public static void clientTick() {
        if (!available) return;
        try {
            VsCompatImpl.tick();
        } catch (Throwable t) {
            available = false;
            snapshot = new ShipSnapshot[0];
            ColorfulLighting.LOGGER.error("Valkyrien Skies compat failed and has been disabled for this session", t);
        }
    }

    /**
     * Whether this chunk lies inside a tracked ship's light region but holds no ship blocks. Such
     * shipyard chunks are never sent to the client, so a missing chunk there is known to be empty
     * air rather than "not loaded yet" — the light engine may propagate through it instead of
     * waiting forever for a chunk that will never arrive.
     * Called from the light propagator thread; reads the volatile snapshot published by clientTick.
     */
    public static boolean isKnownEmptyShipChunk(int chunkX, int chunkZ) {
        ShipSnapshot[] ships = snapshot;
        for (ShipSnapshot ship : ships) {
            if (ship.area().contains(chunkX, chunkZ) && !ship.activeChunks().contains(ChunkPos.asLong(chunkX, chunkZ)))
                return true;
        }
        return false;
    }

    static void publish(ShipSnapshot[] ships) {
        snapshot = ships;
    }

    /** A ship's light region area plus the shipyard chunks that actually contain its blocks. */
    record ShipSnapshot(ViewArea area, LongOpenHashSet activeChunks) {}
}
