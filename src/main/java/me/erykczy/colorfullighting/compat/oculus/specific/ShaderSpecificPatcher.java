package me.erykczy.colorfullighting.compat.oculus.specific;

import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;
import me.erykczy.colorfullighting.mixin.compat.iris.ShaderPackAccessor;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.parameter.Parameters;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.properties.ShaderProperties;

public class ShaderSpecificPatcher {
	public static void runAll(
			ASTParser t, TranslationUnit tree,
			Root root, Parameters parameters,
			boolean core, PatchShaderType type
	) {
		ShaderPack properties = Iris.getCurrentPack().get();
		// TODO: check resolved shader name, run patches
	}
}
