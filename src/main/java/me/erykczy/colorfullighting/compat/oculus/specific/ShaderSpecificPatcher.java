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
import me.erykczy.colorfullighting.common.accessors.iris.CustomShaderProperties;
import me.erykczy.colorfullighting.common.accessors.iris.ResolvedShaderPack;
import me.erykczy.colorfullighting.compat.oculus.Resources;
import me.erykczy.colorfullighting.mixin.compat.iris.ShaderPackAccessor;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.transform.Patch;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.parameter.Parameters;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.include.AbsolutePackPath;
import net.irisshaders.iris.shaderpack.properties.ShaderProperties;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;

public class ShaderSpecificPatcher {
	public static String resolveShader(ShaderPack pack) {
		ShaderProperties properties = ((ShaderPackAccessor) pack).getShaderProperties();
		String value = ((CustomShaderProperties) properties).colorfullighting$getPatcherFamily();
		if (value != null) return value;
		
		Path pth = ((ResolvedShaderPack) pack).path();
		
		Path pth1 = pth.resolve("shaders.settings");
		
		try {
			InputStream is = Files.newInputStream(pth1);
			String txt = Resources.readStream(is);
			txt = txt.replace("\r", "");
			
			// for sildur's shaders, these notices are good markers
			if (txt.startsWith("""
					/*
					Thank you for downloading Sildur's vibrant shaders, make sure you got it from the official source found here:
					https://sildurs-shaders.github.io/
					*/
					""")) {
				return "Sildur's Vibrant";
			} else if (txt.startsWith("""
					/*
					Sildur's Enhanced Default:
					https://www.patreon.com/Sildur
					https://sildurs-shaders.github.io/
					https://twitter.com/Sildurs_shaders
					https://www.curseforge.com/minecraft/customization/sildurs-enhanced-default
					
					Permissions:
					You are not allowed to edit, copy code or share my shaderpack under a different name or claim it as yours.
					*/""")) {
				return "Sildur's Enhanced Default";
			}
		} catch (Throwable ignored) {
		}
		
		pth1 = pth.resolve("program/composite.glsl");
		
		try {
			InputStream is = Files.newInputStream(pth1);
			String txt = Resources.readStream(is);
			txt = txt.replace("\r", "");
			
			if (txt.startsWith("""
					/////////////////////////////////////
					// Complementary Shaders by EminGT //
					""") || txt.startsWith("""
					//////////////////////////////////////////
					// Complementary Shaders by EminGT      //
					// With Euphoria Patches by SpacEagle17 //
					//////////////////////////////////////////
					"""))
				return "Complementary";
			String[] knownVersions = new String[]{
					"v8", "v9", "v10"
			};
			for (String knownVersion : knownVersions) {
				if (txt.contains("""
					/*\s
					BSL Shaders $version$ Series by Capt Tatsu\s
					https://capttatsu.com\s
					*/\s
					""".replace("$version$", knownVersion))) {
					return "BSL";
				}
			}
			if (txt.contains("""
					/*\s
					Insanity Shader is based from BSL Shaders by Capt Tatsu.
					
					Most of the code is made by Capt Tatsu. They gave me permission to use their code for Insanity.
					
					Support the original author by donating to them.
					*/\s
					"""))
				return "BSL";
		} catch (Throwable ignored) {
		}
		
		return "Other";
	}
	
	protected static boolean matchReference(String refTo, Expression expression) {
		if (expression.getExpressionType() == Expression.ExpressionType.REFERENCE) {
			ReferenceExpression ref = (ReferenceExpression) expression;
			return ref.getIdentifier().getName().equals(refTo);
		}
		return false;
	}
	
	public static void runAll(
			ASTParser t, TranslationUnit tree,
			Root root, Parameters parameters,
			boolean core, PatchShaderType type
	) {
		ShaderPack pack = Iris.getCurrentPack().get();
		// TODO: check resolved shader name, run patches
		
		String name = ((ResolvedShaderPack) pack).getResolvedName();
		
		switch (name) {
			case "Sildur's Vibrant":
//				SildursVibrantPatcher.patchSildursVibrant(t, tree, root, parameters, core, type);
				break;
			case "Complementary":
				ComplementaryPatcher.patchComplementary(t, tree, root, parameters, core, type);
				break;
			case "BSL":
				BSLPatcher.patchComplementary(t, tree, root, parameters, core, type);
				break;
		}
	}
	
	public static Expression expr(Root root, String s) {
		return ASTParser._getInternalInstance().parseExpression(root, s);
	}
	
	public static Statement statement(Root root, String s) {
		return ASTParser._getInternalInstance().parseStatement(root, s);
	}
	
	public static ExternalDeclaration declr(Root root, String s) {
		return ASTParser._getInternalInstance().parseExternalDeclaration(root, s);
	}
}
