package net.camacraft.colorfullighting.mixin.compat.sodium;

import net.camacraft.colorfullighting.common.ColoredLightEngine;
import net.camacraft.colorfullighting.common.Config;
import net.camacraft.colorfullighting.compat.sodium.ChunkShaderInterfaceExtension;
import me.jellysquid.mods.sodium.client.gl.device.CommandList;
import me.jellysquid.mods.sodium.client.gl.shader.GlProgram;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import me.jellysquid.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import me.jellysquid.mods.sodium.client.render.viewport.CameraTransform;
import net.camacraft.colorfullighting.compat.sodium.SodiumCompat;
import net.camacraft.colorfullighting.compat.sodium.SodiumShaderCompat;
import net.irisshaders.iris.compat.sodium.impl.shader_overrides.ShaderChunkRendererExt;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DefaultChunkRenderer.class)
public abstract class DefaultChunkRendererMixin {

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lme/jellysquid/mods/sodium/client/render/chunk/shader/ChunkShaderInterface;setProjectionMatrix(Lorg/joml/Matrix4fc;)V"), remap = false)
    private void onRender(ChunkRenderMatrices matrices, CommandList commandList, ChunkRenderListIterable renderLists, TerrainRenderPass renderPass, CameraTransform camera, CallbackInfo ci) {
        // Use the accessor to get the active program from the parent class
        GlProgram<ChunkShaderInterface> activeProgram = ((ShaderChunkRendererAccessor) this).getActiveProgram();
	    if (activeProgram == null) return;
	    
	    ChunkShaderInterface shader = activeProgram.getInterface();
	    
	    if (shader instanceof ChunkShaderInterfaceExtension extension) {
		    SodiumShaderCompat.setupShader(extension);
	    }
	    // the dynamic lights of this frame (2.8.0), to this pass's program. Valkyrien Skies draws a ship's blocks through
	    // this renderer with a camera of the ship's own, far from the game's: such a draw is a ship's (2.13.0)
	    net.minecraft.world.phys.Vec3 eye = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
	    double dx = camera.x - eye.x, dy = camera.y - eye.y, dz = camera.z - eye.z;
	    net.camacraft.colorfullighting.compat.dynamiclights.ShaderDynamicLights.uploadTerrain(activeProgram.handle(), dx * dx + dy * dy + dz * dz > 4.0);
    }
}
