package me.erykczy.colorfullighting.common.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Directional light occlusion for blocks whose geometry changes with block state but which are
 * registered noOcclusion() and therefore never take vanilla's useShapeForLightOcclusion path
 * (doors and trapdoors). Their panel is a thin slab flush against exactly one cell face; that face
 * is fully covered while all other faces are open, so testing getShape() face coverage yields
 * blocking that matches the visual opening for every facing/hinge/open combination.
 */
public final class ShapeOcclusion {
    private ShapeOcclusion() {}

    public static boolean isDynamicShapeBlocker(BlockState state) {
        Block block = state.getBlock();
        return block instanceof DoorBlock || block instanceof TrapDoorBlock;
    }

    /**
     * Whether this block's panel fully covers the given face of its cell, i.e. whether light
     * crossing that face passes through the panel (and should receive the panel's filter
     * absorption/tint) rather than through the open part of the doorway.
     */
    public static boolean panelCoversFace(BlockGetter level, BlockState state, BlockPos pos, Direction face) {
        return Block.isFaceFull(state.getShape(level, pos), face);
    }
}