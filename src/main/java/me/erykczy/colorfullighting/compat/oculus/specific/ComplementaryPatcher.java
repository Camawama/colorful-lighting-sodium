package me.erykczy.colorfullighting.compat.oculus.specific;

import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.declaration.TypeAndInitDeclaration;
import io.github.douira.glsl_transformer.ast.node.expression.ReferenceExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.FunctionCallExpression;
import io.github.douira.glsl_transformer.ast.node.external_declaration.DeclarationExternalDeclaration;
import io.github.douira.glsl_transformer.ast.node.external_declaration.ExternalDeclaration;
import io.github.douira.glsl_transformer.ast.node.statement.CompoundStatement;
import io.github.douira.glsl_transformer.ast.node.statement.Statement;
import io.github.douira.glsl_transformer.ast.node.type.specifier.BuiltinNumericTypeSpecifier;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.query.match.AutoHintedMatcher;
import io.github.douira.glsl_transformer.ast.query.match.HintedMatcher;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;
import io.github.douira.glsl_transformer.parser.ParseShape;
import kroppeb.stareval.expression.CallExpression;
import me.erykczy.colorfullighting.compat.oculus.Resources;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.parameter.Parameters;

import java.util.ArrayList;
import java.util.List;

import static me.erykczy.colorfullighting.compat.oculus.specific.ShaderSpecificPatcher.*;

public class ComplementaryPatcher {
	private static final HintedMatcher<ExternalDeclaration> LOCATE_TEXCOORD_out = new AutoHintedMatcher<>(
			"flat out vec2 lmCoord;",
			ParseShape.EXTERNAL_DECLARATION
	);
	
	public static void patchComplementary(
			ASTParser t, TranslationUnit tree,
			Root root, Parameters parameters,
			boolean core, PatchShaderType type
	) {
		HintedMatcher<ExternalDeclaration> LOCATE_TEXCOORD_out = new AutoHintedMatcher<>(
				"flat out vec2 lmCoord;",
				ParseShape.EXTERNAL_DECLARATION
		);
		HintedMatcher<ExternalDeclaration> LOCATE_TEXCOORD_out_n = new AutoHintedMatcher<>(
				"out vec2 lmCoord;",
				ParseShape.EXTERNAL_DECLARATION
		);
		
		if (type == PatchShaderType.FRAGMENT) {
			boolean injectedCl = false;
			
			root.rename("lightmap", "complementary_lightmap");
			
			for (ExternalDeclaration child : tree.getChildren()) {
				if (child instanceof DeclarationExternalDeclaration ext) {
					if (ext.getDeclaration() instanceof TypeAndInitDeclaration declr) {
						if (declr.getMembers().get(0).getName().getName().equals("blocklightCol")) {
							if (declr.getType().getTypeSpecifier() instanceof BuiltinNumericTypeSpecifier spec) {
								if (spec.type.getExplicitName().equals("f32vec3")) {
									int indx = tree.getChildren().indexOf(child);
									tree.getChildren().add(indx, declr(
											root,
											"varying vec3 cl_lighting_value;"
									));
									tree.getChildren().add(indx, declr(
											root,
											"uniform sampler2D lightmap;"
									));
									injectedCl = true;
									break;
								}
							}
						}
					}
				}
			}
			
			if (!injectedCl) return;
			
			List<ReferenceExpression> exprs = new ArrayList<>();
			boolean lmCoordFound = false;
			
			for (ReferenceExpression referenceExpression : root.nodeIndex.get(ReferenceExpression.class)) {
				if (referenceExpression.getIdentifier().getName().equals("blocklightCol")) {
					exprs.add(referenceExpression);
				}
				if (referenceExpression.getIdentifier().getName().equals("lmCoord")) {
					lmCoordFound = true;
				}
			}
			
			if (!lmCoordFound) return;
			
			for (ReferenceExpression expr : exprs) {
				expr.replaceBy(expr(
						root,
						"cl_lighting_value"
				));
			}
		} else if (type == PatchShaderType.VERTEX) {
			boolean injectedCl = false;
			
			for (ExternalDeclaration child : tree.getChildren()) {
				if (
						LOCATE_TEXCOORD_out.matches(child) ||
								LOCATE_TEXCOORD_out_n.matches(child)
				) {
					int indx = tree.getChildren().indexOf(child);
					tree.getChildren().add(indx, declr(
							root,
							"varying vec3 cl_lighting_value;"
					));
					injectedCl = true;
					break;
				}
			}
			
			if (!injectedCl) return;
			
			for (FunctionCallExpression call : root.nodeIndex.get(FunctionCallExpression.class)) {
				if (call.getFunctionName() == null) {
					continue;
				}
				
				if (!call.getFunctionName().getName().equals("GetLightMapCoordinates")) {
					continue;
				}
				
				Statement st = call.getAncestor(Statement.class);
				CompoundStatement block = st.getAncestor(CompoundStatement.class);
				int indx = block.getChildren().indexOf(st);
				block.getChildren().add(
						indx + 1,
						statement(
								root,
								"cl_lighting_value = colorful_lighting_color;"
						)
				);
				break;
			}
		}
	}
}
