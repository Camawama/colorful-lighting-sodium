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

    /**
     * Per-ship coordinate mapping, republished every client tick (ship transforms change every
     * tick). Read from the light propagator and chunk-build threads via the volatile array; the
     * records and their transform arrays are never mutated after publication.
     */
    private static volatile ShipMirror[] mirrors = new ShipMirror[0];

    /** How far outside a ship's shipyard block bounds a position still counts as that shipyard. */
    private static final double SHIPYARD_MARGIN = 8.0;

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
            mirrors = new ShipMirror[0];
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

    static void publishMirrors(ShipMirror[] ships) {
        mirrors = ships;
    }

    /** A ship's light region area plus the shipyard chunks that actually contain its blocks. */
    record ShipSnapshot(ViewArea area, LongOpenHashSet activeChunks) {}

    /** Receives one mirrored position; see {@link #forEachShipyardMirror}. */
    @FunctionalInterface
    public interface PositionConsumer {
        void accept(double x, double y, double z);
    }

    /**
     * A ship's coordinate mapping for one tick: its shipyard block bounds, its world-space bounds,
     * and the transforms between the two spaces. The transforms are stored as the top three rows of
     * the affine matrix, {@code {m00,m10,m20,m30, m01,m11,m21,m31, m02,m12,m22,m32}} in JOML's
     * column-row property naming, so applying one is three dot products and never touches a VS or
     * JOML type — this class must stay loadable without Valkyrien Skies.
     */
    record ShipMirror(double shipyardMinX, double shipyardMinY, double shipyardMinZ,
                      double shipyardMaxX, double shipyardMaxY, double shipyardMaxZ,
                      double worldMinX, double worldMinY, double worldMinZ,
                      double worldMaxX, double worldMaxY, double worldMaxZ,
                      double[] shipToWorld, double[] worldToShip) {
        boolean shipyardContains(double x, double y, double z) {
            return x >= shipyardMinX - SHIPYARD_MARGIN && x <= shipyardMaxX + SHIPYARD_MARGIN
                    && y >= shipyardMinY - SHIPYARD_MARGIN && y <= shipyardMaxY + SHIPYARD_MARGIN
                    && z >= shipyardMinZ - SHIPYARD_MARGIN && z <= shipyardMaxZ + SHIPYARD_MARGIN;
        }

        boolean worldContains(double x, double y, double z, double inflate) {
            return x >= worldMinX - inflate && x <= worldMaxX + inflate
                    && y >= worldMinY - inflate && y <= worldMaxY + inflate
                    && z >= worldMinZ - inflate && z <= worldMaxZ + inflate;
        }

        static double[] apply(double[] rows, double x, double y, double z) {
            return new double[]{
                    rows[0] * x + rows[1] * y + rows[2] * z + rows[3],
                    rows[4] * x + rows[5] * y + rows[6] * z + rows[7],
                    rows[8] * x + rows[9] * y + rows[10] * z + rows[11]
            };
        }
    }

    /** Whether any ship mirror is published; false whenever VS is absent or no ships are loaded. */
    public static boolean hasShipMirrors() {
        return mirrors.length > 0;
    }

    /**
     * The world-space mirror of a position that lies in some ship's shipyard, or null for ordinary
     * world positions. Shipyard regions are disjoint, so the first containing ship decides.
     * Thread-safe (reads the volatile mirror snapshot).
     */
    public static double[] shipyardToWorld(double x, double y, double z) {
        for (ShipMirror ship : mirrors) {
            if (ship.shipyardContains(x, y, z)) {
                return ShipMirror.apply(ship.shipToWorld(), x, y, z);
            }
        }
        return null;
    }

    /**
     * Calls the consumer with the shipyard-space mirror of a world position, for every ship whose
     * world bounds inflated by {@code inflate} contain it. Thread-safe (reads the volatile mirror
     * snapshot).
     */
    public static void forEachShipyardMirror(double x, double y, double z, double inflate, PositionConsumer consumer) {
        for (ShipMirror ship : mirrors) {
            if (!ship.worldContains(x, y, z, inflate)) continue;
            double[] mirrored = ShipMirror.apply(ship.worldToShip(), x, y, z);
            consumer.accept(mirrored[0], mirrored[1], mirrored[2]);
        }
    }
}
