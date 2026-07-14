package me.erykczy.colorfullighting.mixin;

import me.erykczy.colorfullighting.common.BlockEntityNbtCache;
import me.erykczy.colorfullighting.common.ColoredLightEngine;
import me.erykczy.colorfullighting.common.util.ShapeOcclusion;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Mixin;
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
    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void colorfullighting$onSetBlockState(BlockPos pos, BlockState newState, boolean isMoving, CallbackInfoReturnable<BlockState> cir) {
        ColoredLightEngine engine = ColoredLightEngine.getInstance();
        if (engine == null || !engine.isEnabled()) return;

        BlockState oldState = cir.getReturnValue();
        if (oldState == null) return; // no change happened

        Level level = ((LevelChunk) (Object) this).getLevel();
        if (!level.isClientSide) return;
        if (!Minecraft.getInstance().isSameThread()) return;

        if (!ShapeOcclusion.isDynamicShapeBlocker(oldState) && !ShapeOcclusion.isDynamicShapeBlocker(newState)) return;
        // if light properties differ, vanilla (or Starlight) fires a light check and the existing hooks handle it
        if (LightEngine.hasDifferentLightProperties(level, pos, oldState, newState)) return;

        engine.onBlockLightPropertiesChanged(pos);
    }

    /** Starts tracking a block entity whose block has an NBT-conditioned light rule. */
    @Inject(method = "addAndRegisterBlockEntity", at = @At("TAIL"))
    private void colorfullighting$onBlockEntityAdded(BlockEntity blockEntity, CallbackInfo ci) {
        if (!((LevelChunk) (Object) this).getLevel().isClientSide) return;
        if (!Minecraft.getInstance().isSameThread()) return;
        BlockEntityNbtCache.onBlockEntityAdded(blockEntity);
    }

    @Inject(method = "removeBlockEntity", at = @At("TAIL"))
    private void colorfullighting$onBlockEntityRemoved(BlockPos pos, CallbackInfo ci) {
        if (!((LevelChunk) (Object) this).getLevel().isClientSide) return;
        if (!Minecraft.getInstance().isSameThread()) return;
        BlockEntityNbtCache.onBlockEntityRemoved(pos);
    }
}