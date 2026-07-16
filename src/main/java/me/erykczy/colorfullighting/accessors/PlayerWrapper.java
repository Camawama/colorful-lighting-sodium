package me.erykczy.colorfullighting.accessors;

import me.erykczy.colorfullighting.common.accessors.PlayerAccessor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.NotNull;

// what, purpose does this serve?
@Deprecated(forRemoval = true)
public class PlayerWrapper implements PlayerAccessor {
    final Player player; // oh probably because it was LocalPlayer?

    public PlayerWrapper(@NotNull Player player) {
        this.player = player;
    }

    @Override
    public ChunkPos getChunkPos() {
        return player.chunkPosition();
    }

    @Override
    public BlockPos getBlockPos() {
        return player.blockPosition();
    }
}
