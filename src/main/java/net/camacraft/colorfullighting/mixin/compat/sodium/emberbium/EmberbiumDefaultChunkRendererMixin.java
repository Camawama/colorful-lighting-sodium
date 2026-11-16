package net.camacraft.colorfullighting.mixin.compat.sodium.emberbium;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import me.jellysquid.mods.sodium.client.gl.shader.GlProgram;
import me.jellysquid.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import me.jellysquid.mods.sodium.client.render.viewport.CameraTransform;
import net.camacraft.colorfullighting.compat.dynamiclights.ShaderDynamicLights;
import net.camacraft.colorfullighting.compat.sodium.ChunkShaderInterfaceExtension;
import net.camacraft.colorfullighting.compat.sodium.EmberbiumFadeInProgram;
import net.camacraft.colorfullighting.compat.sodium.SodiumShaderCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Emberbium only (applied when {@code ShaderChunkRenderer} has {@code bindFadeInProgram}, see the mixin plugin): Emberbium's
 * chunk fade-in draws a region whose sections have just appeared with a program of its own, bound in the middle of the pass,
 * which never got the uniforms {@code DefaultChunkRendererMixin} sets on the pass's program at its start: the coloured light
 * switch and the night vibrancy stayed 0 and the dynamic lights were missing there for as long as the region faded. Each time
 * the fade-in program is bound they are set on it the same way.
 */
@Mixin(value = DefaultChunkRenderer.class, remap = false)
public abstract class EmberbiumDefaultChunkRendererMixin {
    @ModifyExpressionValue(method = "render", at = @At(value = "INVOKE",
            target = "Lme/jellysquid/mods/sodium/client/render/chunk/DefaultChunkRenderer;bindFadeInProgram(Lme/jellysquid/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;Lme/jellysquid/mods/sodium/client/render/chunk/ChunkRenderMatrices;)Lme/jellysquid/mods/sodium/client/render/chunk/shader/ChunkShaderInterface;"),
            remap = false)
    private ChunkShaderInterface colorfullighting$setUpFadeInProgram(ChunkShaderInterface shader, @Local(argsOnly = true) CameraTransform camera) {
        if (shader instanceof ChunkShaderInterfaceExtension extension) {
            SodiumShaderCompat.setupShader(extension);
        }
        GlProgram<ChunkShaderInterface> program = ((EmberbiumFadeInProgram) this).colorfullighting$fadeInProgram();
        if (program != null) {
            // as DefaultChunkRendererMixin: a draw with a camera of its own, far from the game's, is a Valkyrien Skies ship's
            Vec3 eye = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            double dx = camera.x - eye.x, dy = camera.y - eye.y, dz = camera.z - eye.z;
            ShaderDynamicLights.uploadTerrain(program.handle(), dx * dx + dy * dy + dz * dz > 4.0);
        }
        return shader;
    }
}
