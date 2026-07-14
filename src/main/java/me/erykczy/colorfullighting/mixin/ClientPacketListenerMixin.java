package me.erykczy.colorfullighting.mixin;

import me.erykczy.colorfullighting.common.BeaconEffectSync;
import me.erykczy.colorfullighting.common.BlockEntityNbtCache;
import me.erykczy.colorfullighting.common.ColoredLightEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ServerboundSetBeaconPacket;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/*
This mixin is a BAND-AID fix for lingering light from explosions.
Until I find a better way to handle light updates, this will do.
 */

@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {

    @Inject(
            method = "handleExplosion(Lnet/minecraft/network/protocol/game/ClientboundExplodePacket;)V",
            at = @At("TAIL")
    )
    private void colorfullighting$handleExplosion(ClientboundExplodePacket packet, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        List<BlockPos> toBlow = packet.getToBlow();
        if (toBlow == null || toBlow.isEmpty()) return;

        Set<Long> rebuilt = new HashSet<>();
        for (BlockPos pos : toBlow) {
            ChunkPos cp = new ChunkPos(pos);
            if (rebuilt.add(cp.toLong())) {
                ColoredLightEngine.getInstance().rebuildChunk(cp, 500);
            }
        }
    }

    /**
     * A block entity's NBT just changed (a beacon's effect was set, a sign was edited, ...). Nothing
     * about the block state changed, so no vanilla light update fires and colored light would stay
     * stale until the next tick scan; re-resolve it here so the change is visible immediately.
     */
    @Inject(
            method = "handleBlockEntityData(Lnet/minecraft/network/protocol/game/ClientboundBlockEntityDataPacket;)V",
            at = @At("TAIL")
    )
    private void colorfullighting$handleBlockEntityData(ClientboundBlockEntityDataPacket packet, CallbackInfo ci) {
        if (!ColoredLightEngine.getInstance().isEnabled()) return;
        BlockEntityNbtCache.onBlockEntityDataChanged(packet.getPos());
    }

    /**
     * A beacon's effect is never sent back to us (see BeaconEffectSync), but we know what we asked
     * for, so apply it to the client's own block entity as the request goes out.
     */
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"))
    private void colorfullighting$send(Packet<?> packet, CallbackInfo ci) {
        if (!(packet instanceof ServerboundSetBeaconPacket beaconPacket)) return;
        if (!ColoredLightEngine.getInstance().isEnabled()) return;
        Minecraft mc = Minecraft.getInstance();
        BeaconEffectSync.onSetBeaconEffects(mc.level, mc.player, beaconPacket.getPrimary(), beaconPacket.getSecondary());
    }
}
