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
		// dynamic lights drawn in the terrain shaders (2.8.0), compiled in once a provider is registered
		boolean dynamic = net.camacraft.colorfullighting.compat.dynamiclights.DynamicLightsCompat.hasExternalProviders();
		// blacklight (2.11.0) rides on the same path: compiled in with the config on, where there are lights or blacklight blocks
		boolean blacklight = net.camacraft.colorfullighting.common.ColorfulLightingConfig.blacklight()
				&& (dynamic || !net.camacraft.colorfullighting.common.Config.uvBlocks().isEmpty());
		if (dynamic || blacklight) instance.add("CL_DYNAMIC_LIGHTS");
		if (blacklight) instance.add("CL_BLACKLIGHT");
	}
}
