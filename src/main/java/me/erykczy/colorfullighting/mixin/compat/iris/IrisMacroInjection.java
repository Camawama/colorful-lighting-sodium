package me.erykczy.colorfullighting.mixin.compat.iris;

import com.google.common.collect.ImmutableList;
import net.irisshaders.iris.gl.shader.StandardMacros;
import net.irisshaders.iris.helpers.StringPair;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import java.util.ArrayList;
import java.util.List;

// https://github.com/djefrey/Colorwheel/blob/1.20.1/dev/common/src/main/java/dev/djefrey/colorwheel/mixin/iris/StandardMacrosMixin.java#L5
@Mixin(StandardMacros.class)
public class IrisMacroInjection {
	@Shadow
	private static void define(List<StringPair> defines, String key) {
		throw new RuntimeException();
	}
	
	@Shadow
	private static void define(List<StringPair> defines, String key, String value) {
		throw new RuntimeException();
	}
	
	@Inject(method = "createStandardEnvironmentDefines()Lcom/google/common/collect/ImmutableList;",
			at = @At(value = "CONSTANT", args = "stringValue=IS_IRIS"),
			locals = LocalCapture.CAPTURE_FAILHARD,
			remap = false
	)
	private static void colorfullighting$injectClStandardDefines(CallbackInfoReturnable<ImmutableList<StringPair>> cir, ArrayList<StringPair> standardDefines)
	{
		define(standardDefines, "HAS_COLORFUL_LIGHTING");
	}
}
