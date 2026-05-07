package me.erykczy.colorfullighting.mixin.compat.sodium;

import me.erykczy.colorfullighting.compat.sodium.DefaultShaderInterfaceExtension;
import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformFloat;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderOptions;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.DefaultShaderInterface;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ShaderBindingContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DefaultShaderInterface.class)
public class DefaultShaderInterfaceMixin implements DefaultShaderInterfaceExtension {
    @Unique
    private ShaderBindingContext shaderBindingContext;

    @Unique
    private GlUniformFloat uniformNightVibrancy;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(ShaderBindingContext context, ChunkShaderOptions options, CallbackInfo ci) {
        this.shaderBindingContext = context;
        this.tryBindNightVibrancy(context);
    }

    @Override
    public void setNightVibrancy(float vibrancy) {
        if (this.uniformNightVibrancy == null) {
            this.tryBindNightVibrancy(this.shaderBindingContext);
        }

        if (this.uniformNightVibrancy != null) {
            this.uniformNightVibrancy.setFloat(vibrancy);
        }
    }

    @Unique
    private void tryBindNightVibrancy(ShaderBindingContext context) {
        if (context == null || this.uniformNightVibrancy != null) {
            return;
        }

        try {
            this.uniformNightVibrancy = context.bindUniform("u_NightVibrancy", GlUniformFloat::new);
        } catch (NullPointerException ignored) {
            // Uniform missing; we will retry after the next resource reload.
        }
    }
}
