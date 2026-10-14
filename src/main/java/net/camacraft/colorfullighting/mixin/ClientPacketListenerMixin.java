package net.camacraft.colorfullighting.mixin;

import net.camacraft.colorfullighting.common.BeaconEffectSync;
import net.camacraft.colorfullighting.common.ColoredLightEngine;
import net.camacraft.colorfullighting.common.accessors.mixin.LevelAttachments;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ServerboundSetBeaconPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {
	
	@Shadow
	private ClientLevel level;
	
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
        if (!ColoredLightEngine.isEnabled()) return;
	    ((LevelAttachments) level).colorfullighting$getNbtCache().onBlockEntityDataChanged(packet.getPos());
    }

    /**
     * A beacon's effect is never sent back to us (see BeaconEffectSync), but we know what we asked
     * for, so apply it to the client's own block entity as the request goes out.
     */
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"))
    private void colorfullighting$send(Packet<?> packet, CallbackInfo ci) {
        if (!(packet instanceof ServerboundSetBeaconPacket beaconPacket)) return;
        if (!ColoredLightEngine.isEnabled()) return;
        Minecraft mc = Minecraft.getInstance();
        BeaconEffectSync.onSetBeaconEffects(level, mc.player, beaconPacket.getPrimary(), beaconPacket.getSecondary());
    }
}
