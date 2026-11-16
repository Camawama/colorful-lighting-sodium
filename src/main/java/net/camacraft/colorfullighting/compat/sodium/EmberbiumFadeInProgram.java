package net.camacraft.colorfullighting.compat.sodium;

import me.jellysquid.mods.sodium.client.gl.shader.GlProgram;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import org.jetbrains.annotations.Nullable;

/**
 * Emberbium (a fork of Embeddium that keeps the {@code embeddium} mod id) draws a region whose sections have just appeared
 * with a terrain program of its own for 750 ms, which fades them in from the fog colour
 * ({@code ShaderChunkRenderer.bindFadeInProgram}, compiled with {@code USE_CHUNK_FADE_IN}). Colorful Lighting sets its
 * per-frame uniforms (the coloured light switch, the night vibrancy, the dynamic lights) on the regular program at the start
 * of a pass; this is the fade-in program the renderer last bound, noted by
 * {@code mixin.compat.sodium.emberbium.EmberbiumShaderChunkRendererMixin}, so they are set on it too (2.14.1). Embeddium has
 * no such method and none of this applies there.
 */
public interface EmberbiumFadeInProgram {
    /** The fade-in program bound last, or null before the first fade. */
    @Nullable
    GlProgram<ChunkShaderInterface> colorfullighting$fadeInProgram();
}
