package net.camacraft.colorfullighting.compat.valkyrienskies;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.camacraft.colorfullighting.ColorfulLighting;
import net.camacraft.colorfullighting.common.ViewArea;
import net.camacraft.colorfullighting.common.accessors.mixin.LevelAttachments;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

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
	// TODO: needs to not be global state
    private volatile ShipSnapshot[] snapshot = new ShipSnapshot[0];

    /**
     * Per-ship coordinate mapping, republished every client tick (ship transforms change every
     * tick). Read from the light propagator and chunk-build threads via the volatile array; the
     * records and their transform arrays are never mutated after publication.
     */
    // TODO: needs to not be global state
    private volatile ShipMirror[] mirrors = new ShipMirror[0];

    /** How far outside a ship's shipyard block bounds a position still counts as that shipyard. */
    private static final double SHIPYARD_MARGIN = 8.0;

	private final VsCompatImpl impl;
	
    public VsCompat() {
		impl = new VsCompatImpl(this);
    }

    public static void init() {
        available = true;
    }

    public static boolean isAvailable() {
        return available;
    }
	
	/** Called once per client tick from ClientEventListener; no-op when VS is absent. */
    public void clientTick(Level level) {
        if (!available) return;
        try {
            impl.tick(level);
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
    public boolean isKnownEmptyShipChunk(int chunkX, int chunkZ) {
        ShipSnapshot[] ships = snapshot;
        for (ShipSnapshot ship : ships) {
            if (ship.area().contains(chunkX, chunkZ) && !ship.activeChunks().contains(ChunkPos.asLong(chunkX, chunkZ)))
                return true;
        }
        return false;
    }

    void publish(ShipSnapshot[] ships) {
        snapshot = ships;
    }

    void publishMirrors(ShipMirror[] ships) {
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
    record ShipMirror(long id, double shipyardMinX, double shipyardMinY, double shipyardMinZ,
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
    public boolean hasShipMirrors() {
        return mirrors.length > 0;
    }

    /**
     * The world-space mirror of a position that lies in some ship's shipyard, or null for ordinary
     * world positions. Shipyard regions are disjoint, so the first containing ship decides.
     * Thread-safe (reads the volatile mirror snapshot).
     */
    public double[] shipyardToWorld(double x, double y, double z) {
        for (ShipMirror ship : mirrors) {
            if (ship.shipyardContains(x, y, z)) {
                return ShipMirror.apply(ship.shipToWorld(), x, y, z);
            }
        }
        return null;
    }

    /**
     * Whether a shipyard position lies inside the block bounds of the ship whose shipyard it is in
     * (false for world positions). Thread-safe (reads the volatile mirror snapshot).
     */
    public boolean insideShipBlocks(double x, double y, double z) {
        for (ShipMirror ship : mirrors) {
            if (ship.shipyardContains(x, y, z)) {
                return x >= ship.shipyardMinX() && x < ship.shipyardMaxX()
                        && y >= ship.shipyardMinY() && y < ship.shipyardMaxY()
                        && z >= ship.shipyardMinZ() && z < ship.shipyardMaxZ();
            }
        }
        return false;
    }

    /**
     * Calls the consumer with the shipyard-space mirror of a world position, for every ship whose
     * world bounds inflated by {@code inflate} contain it. Thread-safe (reads the volatile mirror
     * snapshot).
     */
    public void forEachShipyardMirror(double x, double y, double z, double inflate, PositionConsumer consumer) {
        for (ShipMirror ship : mirrors) {
            if (!ship.worldContains(x, y, z, inflate)) continue;
            double[] mirrored = ShipMirror.apply(ship.worldToShip(), x, y, z);
            consumer.accept(mirrored[0], mirrored[1], mirrored[2]);
        }
    }

    /** Receives one mirrored position and the scale of the ship it is on (blocks of the world per block of the ship). */
    @FunctionalInterface
    public interface ScaledPositionConsumer {
        void accept(double x, double y, double z, double shipScale);
    }

    /** {@link #forEachShipyardMirror(double, double, double, double, PositionConsumer)} with each ship's scale. */
    public void forEachScaledShipyardMirror(double x, double y, double z, double inflate, ScaledPositionConsumer consumer) {
        for (ShipMirror ship : mirrors) {
            if (!ship.worldContains(x, y, z, inflate)) continue;
            double[] mirrored = ShipMirror.apply(ship.worldToShip(), x, y, z);
            double[] m = ship.shipToWorld();
            consumer.accept(mirrored[0], mirrored[1], mirrored[2], Math.sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2]));
        }
    }
	
	/**
	 * A ship as the bloom needs it (2.11.2): its id, its block bounds in its own grid (the maxima past the last block), its
	 * bounds in the world and its world-to-ship transform of this tick (rows, as {@link ShipMirror}).
	 */
	public record ShipFrame(long id, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
	                        double worldMinX, double worldMinY, double worldMinZ, double worldMaxX, double worldMaxY, double worldMaxZ,
	                        double[] worldToShip) {
		/** How far a place in the world is from the ship's bounds there (0 inside). */
		public double distanceTo(double x, double y, double z) {
			double dx = Math.max(0, Math.max(worldMinX - x, x - worldMaxX));
			double dy = Math.max(0, Math.max(worldMinY - y, y - worldMaxY));
			double dz = Math.max(0, Math.max(worldMinZ - z, z - worldMaxZ));
			return Math.sqrt(dx * dx + dy * dy + dz * dz);
		}

		/** A place in the world in the ship's block grid. */
		public double[] toShip(double x, double y, double z) {
			return ShipMirror.apply(worldToShip, x, y, z);
		}
	}

	private static final ShipFrame[] NO_SHIPS = new ShipFrame[0];

	/** The loaded ships of a level as of this tick; none without Valkyrien Skies. */
	public static ShipFrame[] ships(LevelAttachments level) {
		VsCompat compat = level.colorfullighting$getVSCompat();
		if (compat == null || !available) return NO_SHIPS;
		ShipMirror[] now = compat.mirrors;
		if (now.length == 0) return NO_SHIPS;
		ShipFrame[] out = new ShipFrame[now.length];
		for (int i = 0; i < now.length; i++) {
			ShipMirror m = now[i];
			out[i] = new ShipFrame(m.id(), (int) Math.floor(m.shipyardMinX()), (int) Math.floor(m.shipyardMinY()), (int) Math.floor(m.shipyardMinZ()),
					(int) Math.ceil(m.shipyardMaxX()), (int) Math.ceil(m.shipyardMaxY()), (int) Math.ceil(m.shipyardMaxZ()),
					m.worldMinX(), m.worldMinY(), m.worldMinZ(), m.worldMaxX(), m.worldMaxY(), m.worldMaxZ(), m.worldToShip());
		}
		return out;
	}

	/**
	 * A ship's world-to-ship transform as it is drawn this frame (rows, as {@link ShipMirror}), or null when the ship is not
	 * loaded. Render thread.
	 */
	public static double[] renderWorldToShip(Level level, long shipId) {
		if (!available || !(level instanceof LevelAttachments attachments)) return null;
		VsCompat compat = attachments.colorfullighting$getVSCompat();
		if (compat == null) return null;
		try {
			return compat.impl.renderWorldToShip(level, shipId);
		} catch (RuntimeException | LinkageError e) {
			return null;
		}
	}

	public static boolean isKnownEmptyShipChunk(LevelAttachments level, int x, int z) {
		VsCompat compat = level.colorfullighting$getVSCompat();
		if (compat == null) return false;
		return compat.isKnownEmptyShipChunk(x, z);
	}
	
	public static boolean hasShipMirrors(LevelAttachments level) {
		VsCompat compat = level.colorfullighting$getVSCompat();
		if (compat == null) return false;
		return compat.hasShipMirrors();
	}
	
	public static double[] shipyardToWorld(LevelAttachments level, double x, double y, double z) {
		VsCompat compat = level.colorfullighting$getVSCompat();
		if (compat == null) return null;
		return compat.shipyardToWorld(x, y, z);
	}
	
	public static boolean insideShipBlocks(LevelAttachments level, double x, double y, double z) {
		VsCompat compat = level.colorfullighting$getVSCompat();
		if (compat == null) return false;
		return compat.insideShipBlocks(x, y, z);
	}

	public static void forEachShipyardMirror(LevelAttachments level, double x, double y, double z, double inflate, PositionConsumer consumer) {
		VsCompat compat = level.colorfullighting$getVSCompat();
		if (compat == null) return;
		compat.forEachShipyardMirror(x, y, z, inflate, consumer);
	}

	public static void forEachScaledShipyardMirror(LevelAttachments level, double x, double y, double z, double inflate, ScaledPositionConsumer consumer) {
		VsCompat compat = level.colorfullighting$getVSCompat();
		if (compat == null) return;
		compat.forEachScaledShipyardMirror(x, y, z, inflate, consumer);
	}
}
