package me.erykczy.colorfullighting.common.accessors;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

@Deprecated(forRemoval = true)
public interface PlayerAccessor {
    ChunkPos getChunkPos();
    BlockPos getBlockPos();
}
