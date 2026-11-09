package net.camacraft.colorfullighting.compat.vanilla;

import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import net.camacraft.colorfullighting.compat.Resources;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Function;

public class Preprocessor extends GlslPreprocessor {
	GlslPreprocessor par;

	private static final net.minecraft.resources.ResourceLocation INCLUDE =
			net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("colorful_lighting", "shaders/include/colored_light.glsl");
	/** Marks where the colored light functions go while they can't be imported (see {@link #coloredLightImport}). */
	private static final String INLINE_MARK = "//colorful_lighting:inline_colored_light";

	/**
	 * The line that brings the colored light functions into a shader: the import while the game's resources hold the include,
	 * a mark that {@link #process} replaces with the functions themselves while they don't (2.10.2). The shaders the game
	 * loads before its first resource reload come from vanilla's own pack alone: the import failed there with a
	 * "Could not open GLSL import" line in the log for each, and the functions were put in after the failure.
	 */
	private static String coloredLightImport() {
		return includeAvailable() ? "#moj_import <colorful_lighting:colored_light.glsl>\n" : INLINE_MARK + "\n";
	}

	private static boolean includeAvailable() {
		try {
			net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
			return mc == null || mc.getResourceManager().getResource(INCLUDE).isPresent();
		} catch (RuntimeException e) {
			return true; // the import's own failure still puts the functions in
		}
	}

	private static String inlined() {
		return "#line 0\n" + Resources.CL_VANILLA.replace("#version 150", "/*#version 150*/") + "\n";
	}
	
	public Preprocessor(GlslPreprocessor par) {
		this.par = par;
	}
	
	private boolean startsWith(String str, int index, String target) {
		int slen = str.length();
		int srem = slen - index;
		
		if (srem < target.length()) return false;
		
		for (int i = 0; i < target.length(); i++) {
			char c = str.charAt(i + index);
			char c1 = target.charAt(i);
			if (c != c1) return false;
		}
		
		return true;
	}
	
	enum Result {
		CONTINUE,
		FAIL,
		PASS;
	}
	
	public String replacePattern(String str, int index, Function<StringBuilder, Result> pattern, String replacement) {
		int slen = str.length();
		int srem = slen - index;
		
		StringBuilder building = new StringBuilder();
		
		building.append(str.substring(0, index));
		
		StringBuilder curr = new StringBuilder();
		for (int i = index; i < str.length(); i++) {
			char chr = str.charAt(i);
			if (Character.isWhitespace(chr)) {
				continue;
			}
			curr.append(chr);
			
			Result res = pattern.apply(curr);
			if (res == Result.FAIL) {
				return null;
			}
			
			if (res == Result.PASS) {
				building.append(replacement).append(str.substring(i + 1));
				return building.toString();
			}
		}
		
		return null;
	}
	
	@Override
	public List<String> process(String text) {
		String raw = text;
		
		String[] strArr = text.split("\n");
		
		String[] matches = new String[]{
				"texelFetch(Sampler2,UV2/16,0)",
				"texelFetch(Sampler2,UV2/16.0,0)",
		};
		int longest = 0;
		for (String match : matches) {
			longest = Math.max(match.length(), longest);
		}
		final int finalLongest = longest;
		
		boolean includesLightGlsl = false;
		boolean needsLightGlsl = false;
		
		StringBuilder builder = new StringBuilder();
		for (String s : strArr) {
			if (s.trim().equals("#moj_import <light.glsl>")) {
				builder.append(s).append("\n");
				builder.append(coloredLightImport());
				includesLightGlsl = true;
			} else {
				String nWS = s.replace(" ", "");
				
				if (nWS.contains("texelFetch(Sampler2,")) {
					boolean anyMatch = false;
					
					char[] charr = s.toCharArray();
					for (int i = 0; i < charr.length; i++) {
						if (startsWith(s, i, "texelFetch")) {
							String res = replacePattern(s, i, (b) -> {
								if (b.length() > finalLongest) return Result.FAIL;
								
								for (String match : matches) {
									if (b.length() == match.length()) {
										if (b.toString().equals(match))
											return Result.PASS;
									}
								}
								
								return Result.CONTINUE;
							}, "minecraft_sample_lightmap(Sampler2, UV2)");
							
							if (res != null) {
								builder.append(res).append("\n");
//								builder.append(s).append("\n");
								anyMatch = true;
								needsLightGlsl = true;
								break;
							}
						}
					}
					
					if (!anyMatch) {
						builder.append(s).append("\n");
					}
				} else {
					builder.append(s).append("\n");
				}
			}
		}
		
		if (!includesLightGlsl && needsLightGlsl) {
			strArr = builder.toString().split("\n");

			builder = new StringBuilder();
			for (String s : strArr) {
				if (!includesLightGlsl && s.trim().startsWith("#moj_import")) {
					builder.append(s).append("\n");
					builder.append(coloredLightImport());
					includesLightGlsl = true;
				} else if (!includesLightGlsl && s.trim().startsWith("uniform sampler2D Sampler2;")) {
					builder.append(s).append("\n");
					builder.append(coloredLightImport());
					includesLightGlsl = true;
				} else {
					builder.append(s).append("\n");
				}
			}
		}
		
		List<String> patched = par.process(builder.toString().replace("minecraft_sample_lightmap(", "sample_lightmap_colored("));
		for (int i = 0; i < patched.size(); i++) {
			String s = patched.get(i);
			if (s.contains("#error colorful_lighting:shaders/include/colored_light.glsl")) {
				patched.set(i, inlined());
			} else if (s.contains(INLINE_MARK)) {
				patched.set(i, s.replace(INLINE_MARK, inlined()));
			}
		}
		return patched;
	}
	
	@Override
	public @Nullable String applyImport(boolean p_166480_, String p_166481_) {
		throw new RuntimeException("Unsupported.");
	}
}
