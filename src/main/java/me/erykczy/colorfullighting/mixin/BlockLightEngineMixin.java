package me.erykczy.colorfullighting.mixin;

import me.erykczy.colorfullighting.common.ColoredLightEngine;
import me.erykczy.colorfullighting.common.accessors.mixin.LevelAttachments;
import me.erykczy.colorfullighting.common.accessors.mixin.LightEngineAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.lighting.BlockLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockLightEngine.class)
public abstract class BlockLightEngineMixin {
    @Inject(method = "checkNode", at = @At("TAIL"))
    private void colorfullighting$checkNode(long packedPos, CallbackInfo ci) {
        if (!ColoredLightEngine.isEnabled()) {
            return;
        }
        if(!Minecraft.getInstance().isSameThread()) return; // only client side
        ((LevelAttachments) ((LightEngineAccessor) this).colorfullighting$getChunkGetter().getLevel()).colorfullighting$getEngine().onBlockLightPropertiesChanged(BlockPos.of(packedPos));
    }
}
