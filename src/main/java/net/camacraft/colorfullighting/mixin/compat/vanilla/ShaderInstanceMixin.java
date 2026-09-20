package net.camacraft.colorfullighting.mixin.compat.vanilla;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.Program;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.camacraft.colorfullighting.common.Config;
import net.camacraft.colorfullighting.common.accessors.mixin.CLExtendedShader;
import net.camacraft.colorfullighting.compat.vanilla.Preprocessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

@Mixin(ShaderInstance.class)
public abstract class ShaderInstanceMixin implements CLExtendedShader {
	@Shadow
	@Final
	private int programId;
	
	@Shadow
	@Final
	private List<Uniform> uniforms;
	
	@Shadow
	@Final
	private List<Integer> uniformLocations;
	
	@Shadow
	@Final
	private Map<String, Uniform> uniformMap;
	
	@Shadow
	@Nullable
	public abstract Uniform getUniform(String p_173349_);
	
	@Unique
	private Uniform colorfullighting$NIGHT_VIBRANCY;
	
	@Inject(at = @At("TAIL"), method = "<init>(Lnet/minecraft/server/packs/resources/ResourceProvider;Lnet/minecraft/resources/ResourceLocation;Lcom/mojang/blaze3d/vertex/VertexFormat;)V")
	public void postInit(ResourceProvider p_173336_, ResourceLocation shaderLocation, VertexFormat p_173338_, CallbackInfo ci) {
		colorfullighting$NIGHT_VIBRANCY = colorfullighting$injectUniform("colorfullighting_mod_injected_u_NightVibrancy", "float", 1);
	}
	
	@Unique
	protected Uniform colorfullighting$injectUniform(String name, String dtype, int vecSize) {
		int k = Uniform.glGetUniformLocation(this.programId, name);
		if (k == -1) {
			return getUniform(name);
		}
		
		int i = Uniform.getTypeFromString(dtype);
		ShaderInstance par = (ShaderInstance) (Object) this;
		Uniform u = new Uniform(name, i, vecSize, par);
		u.setLocation(k);
		uniforms.add(u);
		uniformLocations.add(k);
		uniformMap.put(name, u);
		
		return u;
	}
	
	@Override
	public Uniform colorfullighting$getNightVibrancy() {
		return colorfullighting$NIGHT_VIBRANCY;
	}
	
	@Inject(at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/GlStateManager;_getActiveTexture()I"), method = "apply")
	public void preApply(CallbackInfo ci) {
		if (colorfullighting$NIGHT_VIBRANCY != null) {
			ClientLevel level = Minecraft.getInstance().level;
			if (level != null) {
				float nightFactor = level.getStarBrightness(Minecraft.getInstance().getFrameTime());
				int phase = level.getMoonPhase();
				float moonVibrancy = Config.getMoonVibrancy(phase);
				float totalVibrancy = nightFactor * moonVibrancy;
				totalVibrancy = Math.max(0.0f, Math.min(1.0f, totalVibrancy));
				colorfullighting$NIGHT_VIBRANCY.set(totalVibrancy);
				colorfullighting$NIGHT_VIBRANCY.upload();
			} else {
				colorfullighting$NIGHT_VIBRANCY.set(1.0f);
				colorfullighting$NIGHT_VIBRANCY.upload();
			}
		}
	}
	
	@WrapOperation(
			method = "getOrCreate",
			at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/shaders/Program;compileShader(Lcom/mojang/blaze3d/shaders/Program$Type;Ljava/lang/String;Ljava/io/InputStream;Ljava/lang/String;Lcom/mojang/blaze3d/preprocessor/GlslPreprocessor;)Lcom/mojang/blaze3d/shaders/Program;")
	)
	private static Program preCompile(Program.Type type, String str0, InputStream stream, String str1, GlslPreprocessor processor, Operation<Program> original) {
//		return switch (str0) {
//			case "blit_screen", "rendertype_gui", "rendertype_gui_overlay", "position", "position_color",
//			     "position_color_tex", "position_tex", "position_tex_color", "rendertype_text" -> original.call(
//					type,
//					str0,
//					stream,
//					str1,
//					processor
//			);
//			default -> original.call(
//					type,
//					str0,
//					stream,
//					str1,
//					new Preprocessor(processor)
//			);
//		};
		
		return original.call(
				type,
				str0,
				stream,
				str1,
				new Preprocessor(processor)
		);
	}
}
