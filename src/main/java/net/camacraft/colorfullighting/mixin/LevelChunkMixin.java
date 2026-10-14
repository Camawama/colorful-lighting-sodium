package net.camacraft.colorfullighting.mixin;

import net.camacraft.colorfullighting.common.BlockEntityNbtCache;
import net.camacraft.colorfullighting.common.ColoredLightEngine;
import net.camacraft.colorfullighting.common.Config;
import net.camacraft.colorfullighting.common.accessors.mixin.LevelAttachments;
import net.camacraft.colorfullighting.common.util.ShapeOcclusion;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla only triggers a light check when LightEngine.hasDifferentLightProperties is true.
 * Doors/trapdoors toggling open/closed keep identical light properties (lightBlock 0, emission 0,
 * useShapeForLightOcclusion false), so no light update ever fires for them. This hook catches
 * those geometry-only state changes and queues a colored-light repropagation.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
	@Shadow
	@Final
	private Level level;
	
	/**
	 * A light source about to be replaced: its light is recorded now, while it is certain (ColoredLightEngine
	 * .onBlockReplacing). The light check that reports the change can come after another change's decrease has already
	 * cleared the cell.
	 */
	@Inject(method = "setBlockState", at = @At("HEAD"))
	private void colorfullighting$beforeSetBlockState(BlockPos pos, BlockState newState, boolean isMoving, CallbackInfoReturnable<BlockState> cir) {
		if (!ColoredLightEngine.isEnabled() || !level.isClientSide || !Minecraft.getInstance().isSameThread()) return;
		BlockState oldState = ((LevelChunk) (Object) this).getBlockState(pos);
		if (oldState == newState || oldState.getLightEmission() <= 0) return;
		ColoredLightEngine engine = ((LevelAttachments) level).colorfullighting$getEngine();
		if (engine != null) engine.onBlockReplacing(pos, oldState);
	}

	@Inject(method = "setBlockState", at = @At("RETURN"))
    private void colorfullighting$onSetBlockState(BlockPos pos, BlockState newState, boolean isMoving, CallbackInfoReturnable<BlockState> cir) {
        ColoredLightEngine engine = ((LevelAttachments) level).colorfullighting$getEngine();
        if (engine == null || !ColoredLightEngine.isEnabled()) return;

        BlockState oldState = cir.getReturnValue();
        if (oldState == null) return; // no change happened

        Level level = ((LevelChunk) (Object) this).getLevel();
        if (!level.isClientSide) return;
        if (!Minecraft.getInstance().isSameThread()) return;

        // Doors and trapdoors change shape without changing vanilla's light properties; a block with a colored-light
        // filter or absorber (stained glass, tinted glass, honey...) swapped for one with none, or another, changes
        // colored light only. Vanilla fires no light check for either, so the engine is told here. Without this,
        // stained glass broken (or placed) left the light beyond it tinted (or untinted) until something else relit it.
        if (!ShapeOcclusion.isDynamicShapeBlocker(oldState) && !ShapeOcclusion.isDynamicShapeBlocker(newState)
                && !Config.filtersOrAbsorbs(oldState) && !Config.filtersOrAbsorbs(newState)) return;
        // if light properties differ, vanilla (or Starlight) fires a light check and the existing hooks handle it
        if (LightEngine.hasDifferentLightProperties(level, pos, oldState, newState)) return;

        engine.onBlockLightPropertiesChanged(pos);
    }

    /** Starts tracking a block entity whose block has an NBT-conditioned light rule. */
    @Inject(method = "addAndRegisterBlockEntity", at = @At("TAIL"))
    private void colorfullighting$onBlockEntityAdded(BlockEntity blockEntity, CallbackInfo ci) {
        if (!((LevelChunk) (Object) this).getLevel().isClientSide) return;
        if (!Minecraft.getInstance().isSameThread()) return;
		
	    Level level = ((LevelChunk) (Object) this).getLevel();
	    BlockEntityNbtCache nbtCache = ((LevelAttachments) level).colorfullighting$getNbtCache();
	    if (nbtCache == null) return; // virtual levels have no colored lighting attachments
	    nbtCache.onBlockEntityAdded(blockEntity);
    }

    @Inject(method = "removeBlockEntity", at = @At("TAIL"))
    private void colorfullighting$onBlockEntityRemoved(BlockPos pos, CallbackInfo ci) {
        if (!((LevelChunk) (Object) this).getLevel().isClientSide) return;
        if (!Minecraft.getInstance().isSameThread()) return;
	    
		Level level = ((LevelChunk) (Object) this).getLevel();
        BlockEntityNbtCache nbtCache = ((LevelAttachments) level).colorfullighting$getNbtCache();
        if (nbtCache == null) return; // virtual levels have no colored lighting attachments
        nbtCache.onBlockEntityRemoved(pos);
    }
}