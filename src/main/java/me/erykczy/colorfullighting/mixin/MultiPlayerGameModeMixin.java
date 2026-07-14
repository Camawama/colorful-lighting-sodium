package me.erykczy.colorfullighting.mixin;

import me.erykczy.colorfullighting.common.BeaconEffectSync;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Notes which block the local player right-clicked, so that a subsequent ServerboundSetBeaconPacket
 * can be attributed to a position — the beacon menu itself carries none on the client.
 */
@Mixin(MultiPlayerGameMode.class)
public class MultiPlayerGameModeMixin {
    @Inject(method = "useItemOn", at = @At("HEAD"))
    private void colorfullighting$useItemOn(LocalPlayer player, InteractionHand hand, BlockHitResult hitResult,
                                            CallbackInfoReturnable<InteractionResult> cir) {
        BeaconEffectSync.onBlockInteract(player.level(), hitResult.getBlockPos());
    }
}
