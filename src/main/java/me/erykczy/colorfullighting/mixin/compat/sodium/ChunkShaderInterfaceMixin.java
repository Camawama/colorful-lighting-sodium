package me.erykczy.colorfullighting.mixin.compat.sodium;

import me.erykczy.colorfullighting.compat.sodium.ChunkShaderInterfaceExtension;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformFloat;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderOptions;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ShaderBindingContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkShaderInterface.class)
public class ChunkShaderInterfaceMixin implements ChunkShaderInterfaceExtension {
    @Unique
    private ShaderBindingContext shaderBindingContext;

    @Unique
    private GlUniformFloat uniformNightVibrancy;

    @Unique
    private GlUniformFloat uniformColoredLightingEnabled;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(ShaderBindingContext context, ChunkShaderOptions options, CallbackInfo ci) {
        this.shaderBindingContext = context;
        this.tryBindUniforms(context);
    }

    @Override
    public void setNightVibrancy(float vibrancy) {
        if (this.uniformNightVibrancy == null) {
            this.tryBindUniforms(this.shaderBindingContext);
        }

        if (this.uniformNightVibrancy != null) {
            this.uniformNightVibrancy.setFloat(vibrancy);
        }
    }

    @Override
    public void setColoredLightingEnabled(boolean enabled) {
        if (this.uniformColoredLightingEnabled == null) {
            this.tryBindUniforms(this.shaderBindingContext);
        }

        if (this.uniformColoredLightingEnabled != null) {
            this.uniformColoredLightingEnabled.setFloat(enabled ? 1.0f : 0.0f);
        }
    }

    @Override
    public void onShaderReload() {
        this.tryBindUniforms(this.shaderBindingContext);
    }

    @Unique
    private void tryBindUniforms(ShaderBindingContext context) {
        if (context == null) {
            return;
        }

        if (this.uniformNightVibrancy == null) {
            try {
                this.uniformNightVibrancy = context.bindUniform("u_NightVibrancy", GlUniformFloat::new);
            } catch (NullPointerException ignored) {
                // Uniform missing; we will retry after the next resource reload.
            }
        }

        if (this.uniformColoredLightingEnabled == null) {
            try {
                this.uniformColoredLightingEnabled = context.bindUniform("u_ColoredLightingEnabled", GlUniformFloat::new);
            } catch (NullPointerException ignored) {
                // Uniform missing; we will retry after the next resource reload.
            }
        }
    }
}
