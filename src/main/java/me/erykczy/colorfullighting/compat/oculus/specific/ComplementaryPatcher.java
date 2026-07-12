package me.erykczy.colorfullighting.compat.oculus.specific;

import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.expression.ReferenceExpression;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.parameter.Parameters;

public class ComplementaryPatcher {
	public static void patchComplementary(
			ASTParser t, TranslationUnit tree,
			Root root, Parameters parameters,
			boolean core, PatchShaderType type
	) {
		if (type == PatchShaderType.FRAGMENT) {
			for (ReferenceExpression referenceExpression : root.nodeIndex.get(ReferenceExpression.class)) {
				if (referenceExpression.getIdentifier().getName().equals("blocklightCol")) {
					// replace with cl value
				}
			}
		}
	}
}
