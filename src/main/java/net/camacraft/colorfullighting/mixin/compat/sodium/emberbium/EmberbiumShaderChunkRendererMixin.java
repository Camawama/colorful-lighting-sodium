package net.camacraft.colorfullighting.mixin.compat.sodium.emberbium;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import me.jellysquid.mods.sodium.client.gl.shader.GlProgram;
import me.jellysquid.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import net.camacraft.colorfullighting.compat.sodium.EmberbiumFadeInProgram;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Emberbium only (applied when {@code ShaderChunkRenderer} has {@code bindFadeInProgram}, see the mixin plugin): notes the
 * fade-in program the renderer binds for a region whose sections are fading in, for
 * {@link EmberbiumDefaultChunkRendererMixin} to set Colorful Lighting's uniforms on it.
 */
@Mixin(value = ShaderChunkRenderer.class, remap = false)
public abstract class EmberbiumShaderChunkRendererMixin implements EmberbiumFadeInProgram {
    @Unique
    private GlProgram<ChunkShaderInterface> colorfullighting$fadeInProgram;

    @ModifyExpressionValue(method = "bindFadeInProgram", at = @At(value = "INVOKE",
            target = "Lme/jellysquid/mods/sodium/client/render/chunk/ShaderChunkRenderer;compileProgram(Lme/jellysquid/mods/sodium/client/render/chunk/shader/ChunkShaderOptions;)Lme/jellysquid/mods/sodium/client/gl/shader/GlProgram;"),
            remap = false)
    private GlProgram<ChunkShaderInterface> colorfullighting$noteFadeInProgram(GlProgram<ChunkShaderInterface> program) {
        this.colorfullighting$fadeInProgram = program;
        return program;
    }

    @Override
    public GlProgram<ChunkShaderInterface> colorfullighting$fadeInProgram() {
        return this.colorfullighting$fadeInProgram;
    }
}
