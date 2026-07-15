package me.erykczy.colorfullighting.mixin.compat.starlight;

import me.erykczy.colorfullighting.common.accessors.mixin.LevelAttachments;
import me.erykczy.colorfullighting.common.accessors.mixin.LightEngineAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "ca.spottedleaf.starlight.common.light.StarLightInterface", remap = false)
public class StarlightInterfaceMixin {
	
	@Shadow
	@Final
	public LevelLightEngine lightEngine;
	
	@Inject(method = "blockChange", at = @At("TAIL"))
    private void colorfullighting$blockChange(BlockPos pos, CallbackInfoReturnable<?> cir) {
        if (!Minecraft.getInstance().isSameThread()) return; // only client side
	    LightChunkGetter getter = ((LightEngineAccessor) lightEngine).colorfullighting$getChunkGetter();
        ((LevelAttachments) getter.getLevel()).colorfullighting$getEngine().onBlockLightPropertiesChanged(pos);
    }
}
