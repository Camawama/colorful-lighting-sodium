package me.erykczy.colorfullighting.compat.sodium;

import me.erykczy.colorfullighting.common.ColoredLightEngine;
import me.erykczy.colorfullighting.common.accessors.ClientAccessor;
import me.erykczy.colorfullighting.common.accessors.LevelAccessor;
import me.erykczy.colorfullighting.common.accessors.PlayerAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;

public class SodiumCompat {
    private static Boolean isLoaded = null;

    public static boolean isSodiumLoaded() {
        if (isLoaded == null) {
            try {
                Class.forName("org.embeddedt.embeddium.impl.render.chunk.compile.ChunkBuildBuffers");
                isLoaded = true;
            } catch (ClassNotFoundException e) {
                isLoaded = false;
            }
        }
        return isLoaded;
    }
}
