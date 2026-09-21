package net.camacraft.colorfullighting.accessors;

import net.camacraft.colorfullighting.api.CLClientLevel;
import net.camacraft.colorfullighting.common.BlockEntityNbtCache;
import net.camacraft.colorfullighting.common.ColoredLightEngine;
import net.camacraft.colorfullighting.common.Config;
import net.camacraft.colorfullighting.common.accessors.LevelAccessor;
import net.camacraft.colorfullighting.common.accessors.mixin.ClientLevelAccessor;
import net.camacraft.colorfullighting.common.accessors.mixin.LevelAttachments;
import net.camacraft.colorfullighting.compat.flywheel.FlywheelCompat;
import net.camacraft.colorfullighting.compat.dynamiclights.DynamicLightsCompat;
import net.camacraft.colorfullighting.compat.valkyrienskies.VsCompat;
import net.camacraft.colorfullighting.compat.distanthorizons.DhColorCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
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
                        Config.getEmissionBrightness(this, blockPos, blockState) != 0,
                (blockPos, blockState) -> // for each found light source
                        consumer.accept(new BlockPos(blockPos))
        );
    }

    @Override
    public void findDarknessSources(ChunkPos chunkPos, Consumer<BlockPos> consumer) {
        ChunkAccess chunk = level.getChunk(chunkPos.x, chunkPos.z);
        chunk.findBlocks(
                (blockState, blockPos) -> // individual block filter
                        Config.getAbsorption(this, blockPos, blockState) > 0,
                (blockPos, blockState) -> // for each found light source
                        consumer.accept(new BlockPos(blockPos))
        );
    }

    private static final BlockState AIR_STATE = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();

    @Override
    public BlockState getBlockState(BlockPos pos) {
        var chunk = level.getChunkSource().getChunk(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()), ChunkStatus.FULL, false);
        if(chunk == null) {
            // see hasChunk: an absent block-less shipyard chunk is known to be air
            if (VsCompat.isKnownEmptyShipChunk((LevelAttachments) level, SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ())))
                return AIR_STATE;
            return null;
        }
        var section = chunk.getSection(chunk.getSectionIndex(pos.getY()));
        // Read the palette container directly instead of section.getBlockState: AsyncParticles
        // wraps getBlockState with a MixinExtras @WrapMethod that allocates an Operation lambda +
        // boxed-args array on EVERY call, and the propagator's block crawl was the single biggest
        // allocation source in the game (2026-08-06 JFR capture). The palette read is exactly what
        // vanilla getBlockState does. A palette resize racing this off-thread read can throw; that
        // race exists with getBlockState too (AsyncParticles rethrows for non-particle threads), so
        // treat it like an unloaded section and let the propagation retry via the dirty path.
        try {
            return section.getStates().get(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
        } catch (RuntimeException e) {
            return null;
        }
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
	public DynamicLightsCompat colorfullighting$getDynamicLights() {
		return ((LevelAttachments) level).colorfullighting$getDynamicLights();
	}
	
	@Override
	public LevelAccessor colorfullighting$getAccessor() {
		return this;
	}
	
	@Override
	public BlockEntityNbtCache colorfullighting$getNbtCache() {
		return ((LevelAttachments) level).colorfullighting$getNbtCache();
	}
	
	@Override
	public FlywheelCompat colorfullighting$getFlywheelCompat() {
		return ((LevelAttachments) level).colorfullighting$getFlywheelCompat();
	}

	@Override
	public DhColorCache colorfullighting$getDhColorCache() {
		return ((LevelAttachments) level).colorfullighting$getDhColorCache();
	}

	@Override
	public void colorfullighting$setDhColorCache(DhColorCache cache) {
		((LevelAttachments) level).colorfullighting$setDhColorCache(cache);
	}
}
