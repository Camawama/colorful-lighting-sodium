package net.camacraft.colorfullighting.mixin.compat.iris;

import me.jellysquid.mods.sodium.client.gl.device.CommandList;
import me.jellysquid.mods.sodium.client.gl.shader.GlProgram;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import me.jellysquid.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import me.jellysquid.mods.sodium.client.render.viewport.CameraTransform;
import net.camacraft.colorfullighting.compat.sodium.ChunkShaderInterfaceExtension;
import net.camacraft.colorfullighting.compat.sodium.SodiumShaderCompat;
import net.camacraft.colorfullighting.mixin.compat.sodium.ShaderChunkRendererAccessor;
import net.irisshaders.iris.compat.sodium.impl.shader_overrides.IrisChunkShaderInterface;
import net.irisshaders.iris.compat.sodium.impl.shader_overrides.ShaderChunkRendererExt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DefaultChunkRenderer.class)
public abstract class IrisChunkRendererMixin {

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lme/jellysquid/mods/sodium/client/render/chunk/shader/ChunkShaderInterface;setProjectionMatrix(Lorg/joml/Matrix4fc;)V"), remap = false)
    private void onRender(ChunkRenderMatrices matrices, CommandList commandList, ChunkRenderListIterable renderLists, TerrainRenderPass renderPass, CameraTransform camera, CallbackInfo ci) {
        // Use the accessor to get the active program from the parent class
        GlProgram<IrisChunkShaderInterface> activeProgram = ((ShaderChunkRendererExt) this).iris$getOverride();
	    if (activeProgram == null) return;
	    
	    ChunkShaderInterface shader = activeProgram.getInterface();
	    
	    if (shader instanceof ChunkShaderInterfaceExtension extension) {
		    SodiumShaderCompat.setupShader(extension);
	    }
    }
}
