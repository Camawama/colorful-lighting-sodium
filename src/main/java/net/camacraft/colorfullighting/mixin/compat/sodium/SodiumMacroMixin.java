package net.camacraft.colorfullighting.mixin.compat.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.jellysquid.mods.sodium.client.gl.shader.ShaderConstants;
import me.jellysquid.mods.sodium.client.gl.shader.ShaderLoader;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderOptions;
import net.camacraft.colorfullighting.common.ColoredLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(value = ChunkShaderOptions.class, remap = false)
public class SodiumMacroMixin {
	@WrapOperation(
			method = "constants",
			at = @At(value = "INVOKE", target = "Lme/jellysquid/mods/sodium/client/gl/shader/ShaderConstants$Builder;addAll(Ljava/util/List;)V")
	)
	public void postAddFogDefines(ShaderConstants.Builder instance, List<String> strings, Operation<Void> original) {
		original.call(instance, strings);
		
		if (!ColoredLightEngine.isEnabled()) return;
		
		instance.add("COLORFUL_LIGHTING_MOD_PRESENT");
	}
}
