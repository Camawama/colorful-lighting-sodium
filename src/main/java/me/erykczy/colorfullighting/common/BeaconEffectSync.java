package me.erykczy.colorfullighting.common;

import me.erykczy.colorfullighting.mixin.BeaconBlockEntityAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BeaconBlock;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * Makes a beacon's light react to its effect the moment the player sets it.
 *
 * <p>Vanilla never tells the client. {@code BeaconMenu.updateEffects} writes the effect into the
 * server's block entity and then calls only {@code Level::blockEntityChanged}, whose entire body is
 * {@code setUnsaved(true)} — no {@code sendBlockUpdated}, so no block entity packet is ever sent.
 * The client's beacon keeps {@code Primary:-1} until its chunk happens to be re-sent.
 *
 * <p>The client does know one thing though: it sent the {@link net.minecraft.network.protocol.game.ServerboundSetBeaconPacket}
 * itself. So we remember which beacon the player opened, and when that packet goes out we write the
 * effect into the client's own block entity. Everything downstream — the NBT snapshot, the relight —
 * then works exactly as it does for a block entity the server actually syncs.
 *
 * <p>Limits, by construction: this only covers beacons <em>this</em> player changes. Another player's
 * beacon, or one edited with {@code /data}, still updates only when its chunk reloads. If the server
 * rejects the change (the payment slot was empty) the colour is optimistically wrong until then.
 */
public final class BeaconEffectSync {
    /** The player must still be plausibly at the beacon they opened when the packet goes out. */
    private static final double MAX_DISTANCE_SQUARED = 8.0 * 8.0;

    @Nullable
    private static BlockPos lastOpenedBeacon;

    private BeaconEffectSync() {}

    /** Records the beacon the player just right-clicked; any other block clears the memory. */
    public static void onBlockInteract(@Nullable Level level, BlockPos pos) {
        if (level == null) {
            lastOpenedBeacon = null;
            return;
        }
        lastOpenedBeacon = level.getBlockState(pos).getBlock() instanceof BeaconBlock ? pos.immutable() : null;
    }

    public static void clear() {
        lastOpenedBeacon = null;
    }

    /** The local player confirmed an effect in the beacon screen. */
    public static void onSetBeaconEffects(@Nullable Level level, @Nullable Player player,
                                          Optional<MobEffect> primary, Optional<MobEffect> secondary) {
        BlockPos pos = lastOpenedBeacon;
        if (pos == null || level == null || player == null) return;
        if (player.blockPosition().distSqr(pos) > MAX_DISTANCE_SQUARED) return;
        if (!(level.getBlockEntity(pos) instanceof BeaconBlockEntity beacon)) return;

        BeaconBlockEntityAccessor accessor = (BeaconBlockEntityAccessor) beacon;
        accessor.colorfullighting$setPrimaryPower(primary.orElse(null));
        accessor.colorfullighting$setSecondaryPower(secondary.orElse(null));

        BlockEntityNbtCache.onBlockEntityDataChanged(pos);
    }
}
