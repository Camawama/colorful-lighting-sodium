package net.camacraft.colorfullighting.common.accessors;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.function.Consumer;

public interface LevelAccessor {
    int getSectionsCount();
    int getMinSectionY();
    int getMaxSectionY();
    boolean hasChunk(ChunkPos chunkPos);
    boolean hasChunkAndNeighbours(ChunkPos chunkPos);
    void findLightSources(ChunkPos chunkPos, Consumer<BlockPos> consumer);
    void findDarknessSources(ChunkPos chunkPos, Consumer<BlockPos> consumer);
    @Nullable
    BlockState getBlockState(BlockPos pos);
    boolean isInBounds(BlockPos pos);
    void setSectionDirty(int x, int y, int z);
    Level getLevel();
}
