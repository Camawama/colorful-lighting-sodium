package net.camacraft.colorfullighting.compat.create;

import com.simibubi.create.foundation.virtualWorld.VirtualRenderWorld;
import net.camacraft.colorfullighting.accessors.BlockStateWrapper;
import net.camacraft.colorfullighting.api.CLPackedLight;
import net.camacraft.colorfullighting.common.Config;
import net.camacraft.colorfullighting.common.accessors.BlockStateAccessor;
import net.camacraft.colorfullighting.common.util.ColorRGB8;
import net.camacraft.colorfullighting.common.util.PackedLightData;
import net.camacraft.colorfullighting.mixin.compat.create.VirtualRenderWorldAccessor;
import net.createmod.ponder.api.level.PonderLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

public class CreateCompat {
    private static CreateCompat instance;

    public static void init() {
        instance = new CreateCompat();
    }

    public static CreateCompat getInstance() {
        return instance;
    }

    public static boolean isAvailable() {
        return instance != null;
    }

    public boolean colorfullighting$getLightColor(BlockAndTintGetter level, BlockState state, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        if(level instanceof PonderLevel) {
            cir.setReturnValue(PackedLightData.packData(0, 15*15, 15*15, 15*15));
            return true;
        }
        if(level instanceof VirtualRenderWorld vrw) {
            int light = ((VirtualRenderWorldAccessor) vrw).colorfullighting$getExternalPackedLight();
            if (state.emissiveRendering(vrw, pos)) {
                BlockStateAccessor stateAccessor = new BlockStateWrapper(state);
                if (Config.getEmissionBrightness(stateAccessor) > 0) {
                    var emission = Config.getLightColor(state);
                    int skyLight = CLPackedLight.isColored(light) ? CLPackedLight.sky4(light) : (light >> 20) & 0xF;
                    cir.setReturnValue(PackedLightData.packData(skyLight, ColorRGB8.fromRGB4(emission)));
                    return true;
                }
            }
            int emission = state.getLightEmission(vrw, pos);
            if (emission > 0) {
                light = CLPackedLight.maxWithLightLevel(light, emission);
            }
            try {
                int internalBlock = vrw.getLightEngine().getLayerListener(LightLayer.BLOCK).getLightValue(pos);
                if (internalBlock > 0) {
                    light = CLPackedLight.maxWithLightLevel(light, internalBlock);
                }
            } catch (Exception ignored) {}
            cir.setReturnValue(light);
            return true;
        }
        return false;
    }
}
