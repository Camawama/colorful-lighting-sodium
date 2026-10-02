package net.camacraft.colorfullighting.common;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.jetbrains.annotations.Nullable;

/**
 * A view of a level in which one position holds a given block state, whatever the level holds there.
 *
 * <p>Fluid tints are read through a getter ({@code IClientFluidTypeExtensions.getTintColor(state,
 * getter, pos)}), and a liquid whose look depends on its block state (Flow Fun!'s Liquid Redstone by
 * its power, a burning liquid) reads that state back from the getter. When a block changes, vanilla
 * asks {@code hasDifferentLightProperties(old, new)} after the level already holds the new state, so
 * read from the level both states would get the new state's color and a color-only change would never
 * be relit. Through this view each state is judged as itself. Without a level (the position-less
 * render paths) the rest of the world is air, and the tint and light questions throw, so the caller
 * falls back to the state-less tint.
 */
final class StateAtPos implements BlockAndTintGetter {
    @Nullable
    private final BlockAndTintGetter level;
    private final BlockPos pos;
    private final BlockState state;

    StateAtPos(@Nullable BlockAndTintGetter level, BlockPos pos, BlockState state) {
        this.level = level;
        this.pos = pos;
        this.state = state;
    }

    @Override
    public BlockState getBlockState(BlockPos at) {
        if (pos.equals(at)) return state;
        return level != null ? level.getBlockState(at) : Blocks.AIR.defaultBlockState();
    }

    @Override
    public FluidState getFluidState(BlockPos at) {
        if (pos.equals(at)) return state.getFluidState();
        return level != null ? level.getFluidState(at) : Fluids.EMPTY.defaultFluidState();
    }

    @Override
    @Nullable
    public BlockEntity getBlockEntity(BlockPos at) {
        return level != null && !pos.equals(at) ? level.getBlockEntity(at) : null;
    }

    @Override
    public float getShade(Direction direction, boolean shade) {
        return level != null ? level.getShade(direction, shade) : 1.0F;
    }

    @Override
    public LevelLightEngine getLightEngine() {
        if (level == null) throw new UnsupportedOperationException("no level");
        return level.getLightEngine();
    }

    @Override
    public int getBlockTint(BlockPos at, ColorResolver resolver) {
        if (level == null) throw new UnsupportedOperationException("no level");
        return level.getBlockTint(at, resolver);
    }

    @Override
    public int getHeight() {
        return level != null ? level.getHeight() : 384;
    }

    @Override
    public int getMinBuildHeight() {
        return level != null ? level.getMinBuildHeight() : -64;
    }
}
