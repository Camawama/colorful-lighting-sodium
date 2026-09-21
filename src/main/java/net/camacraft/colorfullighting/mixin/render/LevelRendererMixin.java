package net.camacraft.colorfullighting.mixin.render;

import net.camacraft.colorfullighting.common.ColoredLightEngine;
import net.camacraft.colorfullighting.common.Config;
import net.camacraft.colorfullighting.common.accessors.mixin.LevelAttachments;
import net.camacraft.colorfullighting.common.accessors.mixin.LevelRendererAccessor;
import net.camacraft.colorfullighting.common.util.ColorRGB8;
import net.camacraft.colorfullighting.common.util.PackedLightData;
import net.camacraft.colorfullighting.compat.create.CreateCompat;
import net.camacraft.colorfullighting.compat.sodium.SodiumPackedLightData;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.annotation.Nullable;

@Mixin(LevelRenderer.class)
public class LevelRendererMixin implements LevelRendererAccessor {
	@Shadow
	@Nullable
	private ClientLevel level;
	
	@Inject(method = "getLightColor(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)I", at = @At("HEAD"), cancellable = true)
    private static void colorfullighting$getLightColor(BlockAndTintGetter level, BlockState state, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        if (!ColoredLightEngine.isEnabled()) {
            return;
        }

        if(CreateCompat.isAvailable() && CreateCompat.getInstance().colorfullighting$getLightColor(level, state, pos, cir))
            return;

        // Not every BlockAndTintGetter carries our attachments: flywheel bakes instance meshes
        // against EmptyVirtualBlockGetter (on its worker threads) and catnip's SuperByteBuffer
        // path uses its own virtual getter — neither is a Level, so there is no engine to sample.
        // Leave those to vanilla lighting instead of casting blindly.
        if (!(level instanceof LevelAttachments attachments)) {
            return;
        }

        int skyLight = level.getBrightness(LightLayer.SKY, pos);
        if(state.emissiveRendering(level, pos)) {
	        BlockState stateAccessor = state;
            if (Config.getEmissionBrightness(stateAccessor) > 0) {
                var emission = Config.getLightColor(state);
                cir.setReturnValue(PackedLightData.packData(skyLight, ColorRGB8.fromRGB4(emission)));
                return;
            }
        }
		
        int color = attachments.colorfullighting$getEngine().sampleLightColorInt(pos);
        cir.setReturnValue(SodiumPackedLightData.packDataFromRGB4(skyLight, color));
    }
	
	@Override
	public ClientLevel colorfullighting$getClientLevel() {
		return level;
	}
}
