package me.erykczy.colorfullighting.accessors;

import me.erykczy.colorfullighting.api.CLClientLevel;
import me.erykczy.colorfullighting.common.BlockEntityNbtCache;
import me.erykczy.colorfullighting.common.ColoredLightEngine;
import me.erykczy.colorfullighting.common.Config;
import me.erykczy.colorfullighting.common.accessors.*;
import me.erykczy.colorfullighting.common.accessors.mixin.ClientLevelAccessor;
import me.erykczy.colorfullighting.common.accessors.mixin.LevelAttachments;
import me.erykczy.colorfullighting.compat.valkyrienskies.VsCompat;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

public class LevelWrapper implements LevelAccessor, LevelAttachments {
    private final Level level;
	@Nullable
    private final LevelRenderer levelRenderer;
	private final boolean isClient;
	private final boolean isClLevel;

    public LevelWrapper(@NotNull Level level, @Nullable LevelRenderer levelRenderer) {
        this.level = level;
	    this.isClient = level instanceof ClientLevel;
	    this.isClLevel = level instanceof CLClientLevel;
		
		this.levelRenderer = levelRenderer;
    }
	
	public LevelWrapper(Level level) {
		this.level = level;
		this.isClient = level instanceof ClientLevel;
		this.isClLevel = level instanceof CLClientLevel;
		
		if (isClient) {
			levelRenderer = ((ClientLevelAccessor) level).colorfullighting$getLevelRenderer();
		} else {
			levelRenderer = null;
		}
	}
	
	public Level getWrappedLevel() {
        return level;
    }

    @Override
    public int getSectionsCount() {
        return level.getSectionsCount();
    }

    @Override
    public int getMinSectionY() {
        return level.getMinSection();
    }

    @Override
    public int getMaxSectionY() {
        return level.getMaxSection()-1;
    }

    @Override
    public boolean hasChunk(ChunkPos chunkPos) {
        if (level.getChunkSource().hasChunk(chunkPos.x, chunkPos.z)) return true;
        // Shipyard chunks that hold no ship blocks are never sent to the client: missing there
        // means empty, not "still loading", so propagation may treat them as loaded air.
        return VsCompat.isKnownEmptyShipChunk((LevelAttachments) level, chunkPos.x, chunkPos.z);
    }

    @Override
    public boolean hasChunkAndNeighbours(ChunkPos chunkPos) {
        for(int ox = -1; ox <= 1; ++ox) {
            for(int oz = -1; oz <= 1; ++oz) {
                if(!hasChunk(new ChunkPos(chunkPos.x+ox, chunkPos.z+oz))) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public void findLightSources(ChunkPos chunkPos, Consumer<BlockPos> consumer) {
        ChunkAccess chunk = level.getChunk(chunkPos.x, chunkPos.z);
        chunk.findBlocks(
                (blockState, blockPos) -> // individual block filter
                        blockState.getLightEmission(chunk, blockPos) != 0 ||
                        Config.getEmissionBrightness(this, blockPos, new BlockStateWrapper(blockState)) != 0,
                (blockPos, blockState) -> // for each found light source
                        consumer.accept(new BlockPos(blockPos))
        );
    }

    @Override
    public void findDarknessSources(ChunkPos chunkPos, Consumer<BlockPos> consumer) {
        ChunkAccess chunk = level.getChunk(chunkPos.x, chunkPos.z);
        chunk.findBlocks(
                (blockState, blockPos) -> // individual block filter
                        Config.getAbsorption(this, blockPos, new BlockStateWrapper(blockState)) > 0,
                (blockPos, blockState) -> // for each found light source
                        consumer.accept(new BlockPos(blockPos))
        );
    }

    private static final BlockStateAccessor AIR_STATE = new BlockStateWrapper(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());

    @Override
    public BlockStateAccessor getBlockState(BlockPos pos) {
        var chunk = level.getChunkSource().getChunk(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()), ChunkStatus.FULL, false);
        if(chunk == null) {
            // see hasChunk: an absent block-less shipyard chunk is known to be air
            if (VsCompat.isKnownEmptyShipChunk((LevelAttachments) level, SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ())))
                return AIR_STATE;
            return null;
        }
        var section = chunk.getSection(chunk.getSectionIndex(pos.getY()));
        return new BlockStateWrapper(section.getBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15)); //level.getBlockState(pos)
    }

    @Override
    public boolean isInBounds(BlockPos pos) {
        return !level.isOutsideBuildHeight(pos);
    }

    @Override
    public void setSectionDirty(int x, int y, int z) {
		if (levelRenderer != null)
			levelRenderer.setSectionDirty(x, y, z);
		else {
			if (isClLevel) {
				((CLClientLevel) level).colorfullighting$setSectionDirty(x, y, z);
			} else if (isClient) {
				// not ideal, but it works as a fallback
				((ClientLevel) level).setSectionDirtyWithNeighbors(x, y, z);
			}
		}
    }

    @Override
    public Level getLevel() {
        return level;
    }
	
	@Override
	public ColoredLightEngine colorfullighting$getEngine() {
		return ((LevelAttachments) level).colorfullighting$getEngine();
	}
	
	@Override
	public VsCompat colorfullighting$getVSCompat() {
		return ((LevelAttachments) level).colorfullighting$getVSCompat();
	}
	
	@Override
	public LevelAccessor colorfullighting$getAccessor() {
		return this;
	}
	
	@Override
	public BlockEntityNbtCache colorfullighting$getNbtCache() {
		return ((LevelAttachments) level).colorfullighting$getNbtCache();
	}
}
