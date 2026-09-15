package net.camacraft.colorfullighting.mixin.compat.nvidium.moon_vibrancy;

import me.cortex.nvidium.gl.GlObject;
import me.cortex.nvidium.gl.shader.Shader;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderOptions;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ShaderBindingContext;
import net.camacraft.colorfullighting.compat.sodium.ChunkShaderInterfaceExtension;
import org.lwjgl.opengl.GL20;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Shader.class, remap = false)
public class ShaderMixin implements ChunkShaderInterfaceExtension {
	@Unique
	private int unightVibrancy;
	
	@Inject(method = "<init>", at = @At("RETURN"))
	private void onInit(int program, CallbackInfo ci) {
		unightVibrancy = GL20.glGetUniformLocation(program, "colorfullighting_mod_injected_u_NightVibrancy");
	}
	
	@Override
	public void setNightVibrancy(float vibrancy) {
		GL20.glUniform1f(unightVibrancy, vibrancy);
	}
	
	@Override
	public void setColoredLightingEnabled(boolean enabled) {
		// no-op
	}
	
	@Override
	public void onShaderReload() {
		unightVibrancy = GL20.glGetUniformLocation(((GlObject) (Object) this).getId(), "colorfullighting_mod_injected_u_NightVibrancy");
	}
}
