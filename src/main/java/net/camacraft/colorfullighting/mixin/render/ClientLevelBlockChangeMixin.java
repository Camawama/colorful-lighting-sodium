package net.camacraft.colorfullighting.mixin.render;

import net.camacraft.colorfullighting.compat.dynamiclights.VoxelOcclusion;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A block changed in view: the block volume the dynamic lights are propagated through is rebuilt on the next tick (2.9.0). */
@Mixin(ClientLevel.class)
public abstract class ClientLevelBlockChangeMixin {
    @Inject(method = "setBlocksDirty", at = @At("HEAD"))
    private void colorfullighting$blockChanged(BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
        VoxelOcclusion.blockChanged(pos.getX(), pos.getY(), pos.getZ());
        net.camacraft.colorfullighting.compat.dynamiclights.BlacklightField.blockChanged(pos.getX(), pos.getY(), pos.getZ());
        net.camacraft.colorfullighting.bloom.EmitterVolume.blockChanged(pos.getX(), pos.getY(), pos.getZ());
    }
}
