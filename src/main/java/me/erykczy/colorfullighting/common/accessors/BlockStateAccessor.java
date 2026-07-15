package me.erykczy.colorfullighting.common.accessors;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

@Deprecated(forRemoval = true)
public interface BlockStateAccessor {
    ResourceKey<Block> getBlockKey();
    /** Identity of the block, for map lookups that must not hash a ResourceLocation on the hot path. */
    Block getBlock();
    int getLightEmission();
    int getLightBlock();
    int getLightEmission(LevelAccessor level, BlockPos pos);
    int getLightBlock(LevelAccessor level, BlockPos pos);
    boolean isAir();
    String getPropertiesAsString();
    String getPropertyString(String propertyName);
    BlockState getBlockState();
}
