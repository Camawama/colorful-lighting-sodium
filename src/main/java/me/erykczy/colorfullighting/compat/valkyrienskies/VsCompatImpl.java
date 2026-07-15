package me.erykczy.colorfullighting.compat.valkyrienskies;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.erykczy.colorfullighting.common.ColoredLightEngine;
import me.erykczy.colorfullighting.common.ViewArea;
import me.erykczy.colorfullighting.common.accessors.LevelAttachments;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.core.internal.world.VsiShipWorld;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The only class that touches Valkyrien Skies types; VsCompat loads it lazily once VS is known to
 * be installed.
 *
 * <p>The ship world is reached through reflection because the declared return type of
 * VSGameUtilsKt.getShipObjectWorld has changed across VS releases (ClientShipWorldCore in older
 * builds, VsiClientShipWorld in 2.4.9+) and the return type is part of the call's linkage —
 * binding to either at compile time would NoSuchMethodError on the other. Ships themselves are
 * used through the stable {@code org.valkyrienskies.core.api} types.
 */
final class VsCompatImpl {
    /** Per-ship state from the previous tick, so unchanged ships are not re-snapshotted. */
	// TODO: needs to not be global state
    private static final Map<Long, TrackedShip> tracked = new HashMap<>();

    /** Cheap change signature: the region only needs rebuilding when one of these moves. */
    private record ShipShape(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int activeChunkCount) {}
    private record TrackedShip(ShipShape shape, VsCompat.ShipSnapshot snapshot, ColoredLightEngine.LightRegion region) {}

    private VsCompatImpl() {}

    /**
     * The top three rows of the affine transform, laid out the way
     * {@link VsCompat.ShipMirror#apply} expects. Copied out so the published mirror never holds a
     * live VS matrix that physics could mutate under a reader thread.
     */
    private static double[] affineRows(org.joml.Matrix4dc m) {
        return new double[]{
                m.m00(), m.m10(), m.m20(), m.m30(),
                m.m01(), m.m11(), m.m21(), m.m31(),
                m.m02(), m.m12(), m.m22(), m.m32()
        };
    }

    static void tick(Level level) throws ReflectiveOperationException {
        ColoredLightEngine engine = ((LevelAttachments) level).colorfullighting$getEngine();
        if (engine == null) {
            if (!tracked.isEmpty()) {
                tracked.clear();
                VsCompat.publish(new VsCompat.ShipSnapshot[0]);
            }
            VsCompat.publishMirrors(new VsCompat.ShipMirror[0]);
            return;
        }

        VsiShipWorld shipWorld = VSGameUtilsKt.getShipObjectWorld(level);
        Collection<?> ships;
        if (shipWorld == null) {
            ships = List.of();
        } else {
            // QueryableShipData extends java.util.Collection in every VS version
            ships = shipWorld.getLoadedShips();
        }

        boolean snapshotChanged = false;
        Set<Long> seen = new HashSet<>();
        List<VsCompat.ShipMirror> mirrors = new java.util.ArrayList<>();
        for (Object obj : ships) {
            if (!(obj instanceof Ship ship)) continue;
            var aabb = ship.getShipAABB();
            var activeChunksSet = ship.getActiveChunksSet();
            if (aabb == null || activeChunksSet == null || activeChunksSet.getSize() == 0) continue; // no blocks yet
            long id = ship.getId();
            seen.add(id);

            // The coordinate mirror is republished every tick — the region below only cares about
            // the shipyard chunk footprint, but the transform changes whenever the ship moves.
            var worldAabb = ship.getWorldAABB();
            if (worldAabb != null) mirrors.add(new VsCompat.ShipMirror(
                    aabb.minX(), aabb.minY(), aabb.minZ(),
                    aabb.maxX() + 1, aabb.maxY() + 1, aabb.maxZ() + 1,
                    worldAabb.minX(), worldAabb.minY(), worldAabb.minZ(),
                    worldAabb.maxX(), worldAabb.maxY(), worldAabb.maxZ(),
                    affineRows(ship.getShipToWorld()), affineRows(ship.getWorldToShip())));

            ShipShape shape = new ShipShape(aabb.minX(), aabb.minY(), aabb.minZ(),
                    aabb.maxX(), aabb.maxY(), aabb.maxZ(), activeChunksSet.getSize());
            TrackedShip previous = tracked.get(id);
            if (previous != null && previous.shape().equals(shape)) continue;

            // Collect the chunks that hold ship blocks, and their bounds. The region spans both the
            // AABB's chunks and the active set's (they should agree, but the AABB's max-bound
            // convention is not worth trusting) plus a 1-chunk border, mirroring ViewArea's border.
            LongOpenHashSet activeChunks = new LongOpenHashSet();
            Set<ChunkPos> chunksToQueue = new HashSet<>();
            int[] bounds = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE}; // minX, minZ, maxX, maxZ
            activeChunksSet.forEach((x, z) -> {
                activeChunks.add(ChunkPos.asLong(x, z));
                chunksToQueue.add(new ChunkPos(x, z));
                bounds[0] = Math.min(bounds[0], x);
                bounds[1] = Math.min(bounds[1], z);
                bounds[2] = Math.max(bounds[2], x);
                bounds[3] = Math.max(bounds[3], z);
            });
            ViewArea area = new ViewArea(
                    Math.min(bounds[0], aabb.minX() >> 4) - 1,
                    Math.min(bounds[1], aabb.minZ() >> 4) - 1,
                    Math.max(bounds[2], aabb.maxX() >> 4) + 1,
                    Math.max(bounds[3], aabb.maxZ() >> 4) + 1
            );
            tracked.put(id, new TrackedShip(shape,
                    new VsCompat.ShipSnapshot(area, activeChunks),
                    new ColoredLightEngine.LightRegion(area, chunksToQueue)));
            snapshotChanged = true;
        }
        if (tracked.keySet().retainAll(seen)) snapshotChanged = true;

        VsCompat.publishMirrors(mirrors.toArray(new VsCompat.ShipMirror[0]));

        if (snapshotChanged) {
            // Publish before syncing the engine: once sections exist, propagation may immediately
            // consult isKnownEmptyShipChunk from the propagator thread.
            VsCompat.publish(tracked.values().stream().map(TrackedShip::snapshot).toArray(VsCompat.ShipSnapshot[]::new));
        }

        Map<Long, ColoredLightEngine.LightRegion> desired = new HashMap<>();
        for (Map.Entry<Long, TrackedShip> entry : tracked.entrySet()) {
            desired.put(entry.getKey(), entry.getValue().region());
        }
        engine.syncExtraRegions(desired);
    }
}
