package me.erykczy.colorfullighting.mixin.compat.iris.debug;

import com.github.andrew0030.pandora_core.modules.templater.hook.ShaderLoadHook;
import com.github.andrew0030.pandora_core.modules.templater.loader.impl.iris.IrisTemplateLoader;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.irisshaders.iris.gl.shader.GlShader;
import net.irisshaders.iris.gl.shader.ProgramCreator;
import net.irisshaders.iris.gl.shader.ShaderType;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(value = GlShader.class, remap = false)
public class GlShaderMixin {
	@Inject(
			method = {"createShader"},
			at = @At(value = "HEAD")
	)
	private static void wrapShaderSrc(ShaderType type, String name, String src, CallbackInfoReturnable<Integer> cir) {
		List<String> lst = List.of(src.split("\n"));
		ShaderLoadHook.preSource(IrisTemplateLoader.getInstance(), lst, ResourceLocation.parse("internal:" + name));
	}
}
