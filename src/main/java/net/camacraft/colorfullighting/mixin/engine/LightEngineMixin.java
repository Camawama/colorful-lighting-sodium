package net.camacraft.colorfullighting.mixin.engine;

import net.camacraft.colorfullighting.ColorfulLighting;
import net.camacraft.colorfullighting.common.ColoredLightEngine;
import net.camacraft.colorfullighting.common.Config;
import net.camacraft.colorfullighting.common.accessors.LevelAccessor;
import net.camacraft.colorfullighting.common.accessors.mixin.LightEngineAccessor;
import net.camacraft.colorfullighting.common.util.ColorRGB4;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LightEngine.class)
public class LightEngineMixin implements LightEngineAccessor {
	@Shadow
	@Final
	protected LightChunkGetter chunkSource;
	
	@Inject(method = "hasDifferentLightProperties", at = @At("HEAD"), cancellable = true)
    private static void colorfullighting$hasDifferentLightProperties(BlockGetter level, BlockPos pos, BlockState state1, BlockState state2, CallbackInfoReturnable<Boolean> cir) {
        if (!ColoredLightEngine.isEnabled()) {
            return;
        }
        if(!Minecraft.getInstance().isSameThread()) return; // only client side
        LevelAccessor clientLevel = ColorfulLighting.clientAccessor.getLevel();
		if(clientLevel == null) return;
        ColorRGB4 color1 = Config.getColorEmission(clientLevel, pos, state1);
        ColorRGB4 color2 = Config.getColorEmission(clientLevel, pos, state2);
        if(!color1.equals(color2)) {
            cir.setReturnValue(true);
            return;
        }
        color1 = Config.getColoredLightTransmittance(clientLevel, pos, state1);
        color2 = Config.getColoredLightTransmittance(clientLevel, pos, state2);
        if(!color1.equals(color2)) {
            cir.setReturnValue(true);
            return;
        }
        int absorption1 = Config.getLightAbsorption(clientLevel, pos, state1);
        int absorption2 = Config.getLightAbsorption(clientLevel, pos, state2);
        if(absorption1 != absorption2) {
            cir.setReturnValue(true);
        }
    }
	
	@Override
	public LightChunkGetter colorfullighting$getChunkGetter() {
		return chunkSource;
	}
}
