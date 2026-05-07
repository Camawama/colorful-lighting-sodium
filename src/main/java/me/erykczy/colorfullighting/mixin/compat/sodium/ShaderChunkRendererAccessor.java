package me.erykczy.colorfullighting.mixin.compat.sodium;

import net.caffeinemc.mods.sodium.client.gl.shader.GlProgram;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ShaderChunkRenderer.class)
public interface ShaderChunkRendererAccessor {
    @Accessor(value = "activeProgram", remap = false)
    GlProgram<ChunkShaderInterface> getActiveProgram();
}
