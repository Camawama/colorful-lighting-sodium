package me.erykczy.colorfullighting.compat.oculus.specific;

import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.abstract_node.ASTNode;
import io.github.douira.glsl_transformer.ast.node.declaration.Declaration;
import io.github.douira.glsl_transformer.ast.node.declaration.TypeAndInitDeclaration;
import io.github.douira.glsl_transformer.ast.node.expression.Expression;
import io.github.douira.glsl_transformer.ast.node.expression.ReferenceExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.AssignmentExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.FunctionCallExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.MemberAccessExpression;
import io.github.douira.glsl_transformer.ast.node.external_declaration.DeclarationExternalDeclaration;
import io.github.douira.glsl_transformer.ast.node.external_declaration.ExternalDeclaration;
import io.github.douira.glsl_transformer.ast.node.statement.Statement;
import io.github.douira.glsl_transformer.ast.node.statement.terminal.ExpressionStatement;
import io.github.douira.glsl_transformer.ast.node.type.FullySpecifiedType;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.StorageQualifier;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.TypeQualifier;
import io.github.douira.glsl_transformer.ast.node.type.specifier.BuiltinNumericTypeSpecifier;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.query.match.AutoHintedMatcher;
import io.github.douira.glsl_transformer.ast.query.match.HintedMatcher;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;
import io.github.douira.glsl_transformer.parser.ParseShape;
import io.github.douira.glsl_transformer.util.Type;
import net.irisshaders.iris.pipeline.transform.Patch;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.parameter.Parameters;

import java.util.Objects;
import java.util.stream.Stream;

import static me.erykczy.colorfullighting.compat.oculus.specific.ShaderSpecificPatcher.*;

public class SildursVibrantPatcher {
	private static final HintedMatcher<ExternalDeclaration> LOCATE_TEXCOORD = new AutoHintedMatcher<>(
			"varying vec4 texcoord;",
			ParseShape.EXTERNAL_DECLARATION
	);
	
	private static final HintedMatcher<ExternalDeclaration> LOCATE_LIGHTING_DATA = new AutoHintedMatcher<>(
			"varying vec4 cl_lighting_value;",
			ParseShape.EXTERNAL_DECLARATION
	);
	
	private static final HintedMatcher<ExternalDeclaration> LOCATE_COL_TEX_1 = new AutoHintedMatcher<>(
			"uniform sampler2D colortex1;",
			ParseShape.EXTERNAL_DECLARATION
	);
	
	private static void patchSildursVibrantComposite(
			ASTParser t, TranslationUnit tree,
			Root root, Parameters parameters,
			boolean core, PatchShaderType type
	) {
		for (ExternalDeclaration child : tree.getChildren()) {
			if (LOCATE_COL_TEX_1.matches(child)) {
				int indx = tree.getChildren().indexOf(child);
				tree.getChildren().add(
						indx,
						ASTParser._getInternalInstance().parseExternalDeclaration(
								root,
								"uniform sampler2D colortex5;"
						)
				);
				break;
			}
		}
		
		for (ReferenceExpression referenceExpression : root.nodeIndex.get(ReferenceExpression.class)) {
			if (referenceExpression.getIdentifier().getName().equals("emissiveLightC")) {
//				if (referenceExpression.getParent() instanceof FunctionCallExpression call) {
				referenceExpression.replaceBy(expr(
						root,
						"texture2D(colortex5,texcoord).yzw"
				));
				break;
//				}
			}
		}
	}
	
	protected static void patchSildursVibrant(
			ASTParser t, TranslationUnit tree,
			Root root, Parameters parameters,
			boolean core, PatchShaderType type
	) {
		if (
				parameters.patch == Patch.COMPOSITE ||
						parameters.patch == Patch.COMPUTE
		) {
			patchSildursVibrantComposite(t, tree, root, parameters, core, type);
			return;
		}
		if (
				parameters.patch == Patch.DH_GENERIC
		) {
			return;
		}
		
		for (ExternalDeclaration child : tree.getChildren()) {
			if (LOCATE_TEXCOORD.matches(child)) {
				DeclarationExternalDeclaration declr = (DeclarationExternalDeclaration) child;
				if (Objects.requireNonNull(declr.getDeclaration().getDeclarationType()) == Declaration.DeclarationType.TYPE_AND_INIT) {
					TypeAndInitDeclaration typeAndInitDeclaration = (TypeAndInitDeclaration) declr.getDeclaration();
					typeAndInitDeclaration.setType(new FullySpecifiedType(
							new TypeQualifier(Stream.of(new StorageQualifier(StorageQualifier.StorageType.VARYING))),
							new BuiltinNumericTypeSpecifier(Type.F32VEC2)
					));
				} else {
					throw new RuntimeException("wat?");
				}
				
				int indx = tree.getChildren().indexOf(child);
				tree.getChildren().add(
						indx,
						ASTParser._getInternalInstance().parseExternalDeclaration(
								root,
								"varying vec4 cl_lighting_value;"
						)
				);
				break;
			}
		}
		
		if (type == PatchShaderType.VERTEX) {
			boolean valid = false;
			for (ExternalDeclaration child : tree.getChildren()) {
				if (LOCATE_LIGHTING_DATA.matches(child)) {
					valid = true;
					break;
				}
			}
			
			if (valid) {
				for (ReferenceExpression referenceExpression : root.nodeIndex.get(ReferenceExpression.class)) {
					if (referenceExpression.getIdentifier().getName().equals("gl_MultiTexCoord1")) {
						AssignmentExpression expr = referenceExpression.getAncestor(AssignmentExpression.class);
						if (expr != null) {
							ASTNode exp = expr.getParent();
							if (exp instanceof ExpressionStatement) {
								expr.replaceBy(
										expr(
												root,
												"cl_lighting_value = vec4(colorful_lighting_decodeLight(gl_MultiTexCoord1).y, colorful_lighting_color).yzwx"
										)
								);
								break;
							}
						}
					}
				}
				
				boolean didReplacement = true;
				
				while (didReplacement) {
					didReplacement = false;
					
					for (ReferenceExpression referenceExpression : root.nodeIndex.get(ReferenceExpression.class)) {
						if (referenceExpression.getIdentifier().getName().equals("texcoord")) {
							MemberAccessExpression acc = referenceExpression.getAncestor(MemberAccessExpression.class);
							if (acc != null) {
								if (acc.getMember().getName().equals("z")) {
									AssignmentExpression expr = acc.getAncestor(AssignmentExpression.class);
									if (expr.getParent() == null)
										continue;
									
									AssignmentExpression replacement = (AssignmentExpression) expr(
											root,
											"cl_lighting_value.yzw = max(cl_lighting_value.yzw, vec3(0.85))"
									);
									FunctionCallExpression call = (FunctionCallExpression) replacement.getRight();
									FunctionCallExpression subcall = (FunctionCallExpression) call.getParameters().get(1);
									subcall.getParameters().set(0, expr.getRight());
									
									expr.replaceBy(
											replacement
									);
									didReplacement = true;
									break;
								} else if (acc.getMember().getName().equals("w")) {
									AssignmentExpression expr = acc.getAncestor(AssignmentExpression.class);
									if (expr.getParent() == null)
										continue;
									
									AssignmentExpression replacement = (AssignmentExpression) expr(
											root,
											"cl_lighting_value.x = 0.0f"
									);
									replacement.setRight(expr.getRight());
									
									expr.replaceBy(
											replacement
									);
									didReplacement = true;
									break;
								}
							}
						}
					}
				}
			} else {
				// TODO:
			}
		} else {
			boolean patched = false;
			
			for (ReferenceExpression referenceExpression : root.nodeIndex.get(ReferenceExpression.class)) {
				if (referenceExpression.getIdentifier().getName().equals("texcoord")) {
					ASTNode expr = referenceExpression.getParent();
					if (expr instanceof MemberAccessExpression) {
						MemberAccessExpression acc = (MemberAccessExpression) expr;
						if (acc.getMember().getName().equals("z") || acc.getMember().getName().equals("w")) {
							ASTNode nd = acc.getParent();
							if (nd instanceof FunctionCallExpression call) {
								ASTNode p3 = call.getParameters().get(2);
								if (p3 instanceof MemberAccessExpression acc1) {
									if (acc1.getMember().getName().equals("w")) {
										// assume at this point it's what we want
										
										call.getParameters().remove(1);
										call.getParameters().remove(1);
										call.getParameters().add(
												expr(
														root,
														"vec2(0)"
												)
										);
										patched = true;
										break;
									}
								}
							}
						} else if (acc.getMember().getName().equals("zw")) {
							ASTNode nd = acc.getParent();
							if (nd instanceof FunctionCallExpression call) {
								// assume at this point it's what we want
								
								call.getParameters().remove(1);
								call.getParameters().add(
										expr(
												root,
												"vec2(0)"
										)
								);
								patched = true;
								break;
							}
						}
					}
				}
			}
			
			boolean didReplacement = true;
			
			while (didReplacement) {
				didReplacement = false;
				
				for (ReferenceExpression referenceExpression : root.nodeIndex.get(ReferenceExpression.class)) {
					if (referenceExpression.getIdentifier().getName().equals("texcoord")) {
						MemberAccessExpression acc = referenceExpression.getAncestor(MemberAccessExpression.class);
						if (acc != null) {
							if (acc.getParent() == null)
								continue;
							
							if (acc.getMember().getName().equals("z")) { // block light, max of yzw
								acc.replaceBy(
										expr(
												root,
												"max(cl_lighting_value.y, max(cl_lighting_value.z, cl_lighting_value.w))"
										)
								);
								didReplacement = true;
								break;
							} else if (acc.getMember().getName().equals("w")) { // sky light, x on colored lighting value
								acc.replaceBy(
										expr(
												root,
												"cl_lighting_value.x"
										)
								);
								didReplacement = true;
								break;
							}
						}
					}
				}
			}
			
			if (patched) {
				boolean valid = false;
				for (ExternalDeclaration child : tree.getChildren()) {
					if (LOCATE_LIGHTING_DATA.matches(child)) {
						valid = true;
						break;
					}
				}
				if (valid) {
					tree.appendMainFunctionBody(
							statement(
									root,
									"gl_FragData[5] = cl_lighting_value;"
							)
					);
				}
			}
		}
	}
}
