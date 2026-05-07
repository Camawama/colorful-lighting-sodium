package me.erykczy.colorfullighting.mixin.compat.sodium;

import me.erykczy.colorfullighting.common.Config;
import me.erykczy.colorfullighting.compat.sodium.DefaultShaderInterfaceExtension;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.gl.shader.GlProgram;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DefaultChunkRenderer.class)
public abstract class DefaultChunkRendererMixin {

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/shader/ChunkShaderInterface;setProjectionMatrix(Lorg/joml/Matrix4fc;)V"), remap = false)
    private void onRender(ChunkRenderMatrices matrices, CommandList commandList, ChunkRenderListIterable renderLists, TerrainRenderPass renderPass, CameraTransform camera, CallbackInfo ci) {
        // Use the accessor to get the active program from the parent class
        GlProgram<ChunkShaderInterface> activeProgram = ((ShaderChunkRendererAccessor) this).getActiveProgram();
        if (activeProgram == null) return;
        
        ChunkShaderInterface shader = activeProgram.getInterface();
        
        if (shader instanceof DefaultShaderInterfaceExtension extension) {
            ClientLevel level = Minecraft.getInstance().level;
            if (level != null) {
                // getStarBrightness is 1.0 at midnight, 0.0 at noon.
                // This directly serves as our "night factor".
                float nightFactor = level.getStarBrightness(Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false));

                int phase = level.getMoonPhase();
                float moonVibrancy = Config.getMoonVibrancy(phase);

                float totalVibrancy = nightFactor * moonVibrancy;
                
                // Ensure it's clamped 0..1
                totalVibrancy = Math.max(0.0f, Math.min(1.0f, totalVibrancy));

                extension.setNightVibrancy(totalVibrancy);
            }
        }
    }
}
