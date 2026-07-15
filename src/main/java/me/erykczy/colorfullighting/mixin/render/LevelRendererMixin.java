package me.erykczy.colorfullighting.mixin.render;

import me.erykczy.colorfullighting.ColorfulLighting;
import me.erykczy.colorfullighting.accessors.BlockStateWrapper;
import me.erykczy.colorfullighting.common.ColoredLightEngine;
import me.erykczy.colorfullighting.common.Config;
import me.erykczy.colorfullighting.common.accessors.BlockStateAccessor;
import me.erykczy.colorfullighting.common.accessors.LevelAccessor;
import me.erykczy.colorfullighting.common.accessors.mixin.LevelAttachments;
import me.erykczy.colorfullighting.common.accessors.mixin.LevelRendererAccessor;
import me.erykczy.colorfullighting.common.util.ColorRGB4;
import me.erykczy.colorfullighting.common.util.ColorRGB8;
import me.erykczy.colorfullighting.common.util.PackedLightData;
import me.erykczy.colorfullighting.compat.create.CreateCompat;
import me.erykczy.colorfullighting.compat.sodium.SodiumPackedLightData;
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

        int skyLight = level.getBrightness(LightLayer.SKY, pos);
        if(state.emissiveRendering(level, pos)) {
            BlockStateAccessor stateAccessor = new BlockStateWrapper(state);
            if (Config.getEmissionBrightness(stateAccessor) > 0) {
                var emission = Config.getLightColor(state);
                cir.setReturnValue(PackedLightData.packData(skyLight, ColorRGB8.fromRGB4(emission)));
                return;
            }
        }
		
        int color = ((LevelAttachments) level).colorfullighting$getEngine().sampleLightColorInt(pos);
        cir.setReturnValue(SodiumPackedLightData.packDataFromRGB4(skyLight, color));
    }
	
	@Override
	public ClientLevel colorfullighting$getClientLevel() {
		return level;
	}
}
