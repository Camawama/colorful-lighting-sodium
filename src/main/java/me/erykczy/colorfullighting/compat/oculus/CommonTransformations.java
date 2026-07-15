package me.erykczy.colorfullighting.compat.oculus;

import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.abstract_node.ASTNode;
import io.github.douira.glsl_transformer.ast.node.expression.Expression;
import io.github.douira.glsl_transformer.ast.node.expression.ReferenceExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.FunctionCallExpression;
import io.github.douira.glsl_transformer.ast.node.external_declaration.ExternalDeclaration;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.query.match.AutoHintedMatcher;
import io.github.douira.glsl_transformer.ast.query.match.HintedMatcher;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;
import io.github.douira.glsl_transformer.parser.ParseShape;
import me.erykczy.colorfullighting.common.ColoredLightEngine;
import me.erykczy.colorfullighting.common.accessors.iris.CustomShaderProperties;
import me.erykczy.colorfullighting.compat.oculus.specific.ShaderSpecificPatcher;
import me.erykczy.colorfullighting.mixin.compat.iris.ShaderPackAccessor;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.transform.Patch;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.parameter.Parameters;
import net.irisshaders.iris.shaderpack.properties.ShaderProperties;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

public class CommonTransformations {
	private static final HintedMatcher<ExternalDeclaration> LOCATE_LIGHTMAP = new AutoHintedMatcher<>(
			"uniform sampler2D lightmap;",
			ParseShape.EXTERNAL_DECLARATION
	);
	
	private static final HintedMatcher<Expression> LOCATE_LIGHTMAP_EXPR = new AutoHintedMatcher<>(
			"lightmap",
			ParseShape.EXPRESSION
	);
	
	private static final HintedMatcher<Expression> LOCATE_SAMPLE_LIGHTMAP = new AutoHintedMatcher<>(
			"lightmap",
			ParseShape.EXPRESSION
	) {
		@Override
		public boolean matches(Expression tree) {
			if (tree.getExpressionType() == Expression.ExpressionType.FUNCTION_CALL) {
				FunctionCallExpression func = (FunctionCallExpression) tree;
				if (func.getFunctionName() == null) return false; // what?
				
				if (
						func.getFunctionName().getName().equals("texture") ||
						func.getFunctionName().getName().equals("texture2D")
				) {
					return LOCATE_LIGHTMAP_EXPR.matches(func.getParameters().get(0));
				}
			}
			
			return false;
		}
		
		@Override
		public boolean matchesExtract(Expression tree) {
			return matches(tree);
		}
		
		@Override
		public boolean matchesExtract(Expression tree, Map<String, Object> dataMatches, Map<String, ASTNode> nodeMatches) {
			return matches(tree);
		}
	};
	
	public static void colorfullighting$preTransform(
			ASTParser t, TranslationUnit tree,
			Root root, Parameters parameters,
			boolean core, CallbackInfo ci
	) {
		if (!ColoredLightEngine.getInstance().isEnabled()) return;
		
		TextureStage stage = parameters.getTextureStage();
		Patch patch = parameters.patch;
		PatchShaderType type = parameters.type;
		
		// TODO: this is ugly, and I'd like to change it, but it should work...
		//       it's just ugly is all
		ShaderProperties properties = ((ShaderPackAccessor) Iris.getCurrentPack().get()).getShaderProperties();
		OptionalInt value = ((CustomShaderProperties) properties).colorfullighting$getCompatStatus();
		int status = value.orElse(1);
		
		if (status == 0) return; // incompatible, skip
		if (status == 4) return; // full custom compat, skip
		
		boolean isShaderPackInUse = OculusCompat.isShaderPackInUse();
		String packName = isShaderPackInUse ? OculusCompat.getCurrentShaderPackName() : null;
		if (OculusCompat.isShaderLegacyPatched(packName)) return;
		
		if (!stage.equals(TextureStage.GBUFFERS_AND_SHADOW)) {
			if (status == 1) {
				ShaderSpecificPatcher.runAll(
						t, tree,
						root, parameters,
						core, type
				);
			}
			
			return;
		}
		
		if (status == 3) {
			if (type == PatchShaderType.VERTEX) {
				TranslationUnit unit = ASTParser._getInternalInstance().parseTranslationUnit(
						root,
						Resources.DECODE_LIGHT_FULL
				);
				
				for (int i = unit.getChildren().size() - 1; i >= 0; i--) {
					tree.injectNode(ASTInjectionPoint.BEFORE_ALL, unit.getChildren().get(i));
				}
			} else if (type == PatchShaderType.FRAGMENT) {
				TranslationUnit unit = ASTParser._getInternalInstance().parseTranslationUnit(
						root,
						Resources.BLEND_LIGHT_FULL
				);
				
				for (int i = unit.getChildren().size() - 1; i >= 0; i--) {
					ExternalDeclaration declr = unit.getChildren().get(i);
					tree.injectNode(ASTInjectionPoint.BEFORE_FUNCTIONS, declr);
				}
			}
			
			TranslationUnit unit = ASTParser._getInternalInstance().parseTranslationUnit(
					root,
					Resources.BLEND_LIGHT_FULL
			);
			
			for (int i = unit.getChildren().size() - 1; i >= 0; i--) {
				ExternalDeclaration declr = unit.getChildren().get(i);
				tree.injectNode(ASTInjectionPoint.BEFORE_FUNCTIONS, declr);
			}
		} else {
			if (type == PatchShaderType.FRAGMENT) {
				// https://github.com/djefrey/Colorwheel/blob/4b83830bb8d9e3dcb11825011166e0407a7d93fb/common/src/main/java/dev/djefrey/colorwheel/compile/transform/ClrwlTransformPatcher.java#L223-L232
				boolean found = false;
				int index = -1;
				int indexFunction = -1;
				for (ExternalDeclaration child : tree.getChildren()) {
					if (!found) {
						index++;
						if (LOCATE_LIGHTMAP.matches(child)) {
							found = true;
							indexFunction = index;
						}
					} else {
						indexFunction++;
						if (child.getExternalDeclarationType() == ExternalDeclaration.ExternalDeclarationType.FUNCTION_DEFINITION) {
							break;
						}
					}
				}
				
				if (index != -1 && found) {
					Set<FunctionCallExpression> exprs = root.nodeIndex.get(FunctionCallExpression.class);
					for (FunctionCallExpression exp : exprs) {
						if (LOCATE_SAMPLE_LIGHTMAP.matches(exp)) {
							System.out.println(exp);
							exp.getFunctionName().setName("colorful_lighting_blendLight");
						}
					}
					
					TranslationUnit unit = ASTParser._getInternalInstance().parseTranslationUnit(
							root,
							Resources.BLEND_LIGHT_INTERNAL
					);
					
					for (int i = unit.getChildren().size() - 1; i >= 0; i--) {
						ExternalDeclaration declr = unit.getChildren().get(i);
						if (declr.getExternalDeclarationType() != ExternalDeclaration.ExternalDeclarationType.FUNCTION_DEFINITION) {
							tree.getChildren().add(index + 1, unit.getChildren().get(i));
							indexFunction++;
						}
					}
					
					for (int i = unit.getChildren().size() - 1; i >= 0; i--) {
						ExternalDeclaration declr = unit.getChildren().get(i);
						if (declr.getExternalDeclarationType() == ExternalDeclaration.ExternalDeclarationType.FUNCTION_DEFINITION) {
							tree.getChildren().add(indexFunction, unit.getChildren().get(i));
						}
					}
				}
			} else if (type == PatchShaderType.VERTEX) {
				Set<ReferenceExpression> exprs = root.nodeIndex.get(ReferenceExpression.class);
				boolean found = false;
				for (ReferenceExpression expr : exprs) {
					if (expr.getIdentifier() == null) continue;
					if (expr.getIdentifier().getName().equals("gl_MultiTexCoord1")) {
						found = true;
						break;
					}
				}
				
				if (found) {
					TranslationUnit unit = ASTParser._getInternalInstance().parseTranslationUnit(
							root,
							Resources.DECODE_LIGHT_INTERNAL
					);
					
					for (int i = unit.getChildren().size() - 1; i >= 0; i--) {
						tree.injectNode(ASTInjectionPoint.BEFORE_FUNCTIONS, unit.getChildren().get(i));
					}
					
					root.replaceReferenceExpressions(
							t,
							"gl_MultiTexCoord1",
							"colorful_lighting_decodeLight(gl_MultiTexCoord1)"
					);
				}
			}
			
			if (status == 1) {
				ShaderSpecificPatcher.runAll(
						t, tree,
						root, parameters,
						core, type
				);
			}
		}
	}
	
	// last place to find file name is: TransformPatcher#transformInternal
}
