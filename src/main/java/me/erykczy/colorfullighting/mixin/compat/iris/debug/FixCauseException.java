package me.erykczy.colorfullighting.mixin.compat.iris.debug;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.Program;
import net.irisshaders.iris.gl.shader.ShaderCompileException;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import java.io.InputStream;

@Mixin(value = {Program.class}, priority = 999)
public class FixCauseException {
	@Inject(
			method = {"compileShaderInternal"},
			at = {@At(
					value = "INVOKE",
					target = "Lcom/mojang/blaze3d/platform/GlStateManager;glGetShaderInfoLog(II)Ljava/lang/String;"
			)},
			locals = LocalCapture.CAPTURE_FAILHARD,
			cancellable = true
	)
	private static void iris$causeException(Program.Type arg, String string, InputStream inputStream, String string2, GlslPreprocessor arg2, CallbackInfoReturnable<Integer> cir, String string3, int i) {
		cir.cancel();
		throw new ShaderCompileException(string + arg.getExtension(), GlStateManager.glGetShaderInfoLog(i, 32768));
	}
}
