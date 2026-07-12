package me.erykczy.colorfullighting.mixin.compat.iris;

import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;
import me.erykczy.colorfullighting.compat.oculus.CommonTransformations;
import net.irisshaders.iris.pipeline.transform.parameter.Parameters;
import net.irisshaders.iris.pipeline.transform.transformer.CommonTransformer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = CommonTransformer.class, remap = false)
public class CommonTransformerMixin {
	@Inject(at = @At("HEAD"), method = "transform")
	private static void colorfullighting$preTransform(
			ASTParser t, TranslationUnit tree,
			Root root, Parameters parameters,
			boolean core, CallbackInfo ci
	) {
		CommonTransformations.colorfullighting$preTransform(t, tree, root, parameters, core, ci);
	}
}
