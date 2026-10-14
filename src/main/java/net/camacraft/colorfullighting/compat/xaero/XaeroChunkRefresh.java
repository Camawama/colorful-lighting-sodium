package net.camacraft.colorfullighting.compat.xaero;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import java.lang.reflect.Field;

/**
 * Tells Xaero's maps that colored light changed in a chunk, the way Xaero itself learns of block and light
 * updates: by clearing the "clean" flag its mixins add to {@link LevelChunk} ({@code xaero_chunkClean} for the
 * minimap, {@code xaero_wm_chunkClean} for the World Map), so its writers redraw that chunk the next time they
 * pass it. Colored light settles on the propagator thread after Xaero has already redrawn a changed chunk, so
 * without this a map kept the color the light had a moment before (a beacon walled in blue glass stayed white;
 * breaking the glass left it blue).
 *
 * <p>Uses no Xaero class; the flags are found by name, so without Xaero this does nothing.
 */
public final class XaeroChunkRefresh {
    private static final Field MINIMAP_CLEAN = field("xaero_chunkClean");
    private static final Field WORLD_MAP_CLEAN = field("xaero_wm_chunkClean");

    private XaeroChunkRefresh() {
    }

    private static Field field(String name) {
        try {
            Field field = LevelChunk.class.getDeclaredField(name);
            return field.getType() == boolean.class ? field : null;
        } catch (NoSuchFieldException | SecurityException e) {
            return null;
        }
    }

    /** The engine marked these sections dirty after their colored light changed. Client thread. */
    public static void coloredSectionsChanged(Level level, long[] sections) {
        if (MINIMAP_CLEAN == null && WORLD_MAP_CLEAN == null) return;
        if (!(level instanceof ClientLevel client) || Minecraft.getInstance().level != client) return;
        boolean minimap = MINIMAP_CLEAN != null && XaeroMapColors.minimapOn();
        boolean worldMap = WORLD_MAP_CLEAN != null && XaeroMapColors.worldMapOn();
        if (!minimap && !worldMap) return;
        LongOpenHashSet chunks = new LongOpenHashSet();
        for (long section : sections) {
            int x = SectionPos.x(section), z = SectionPos.z(section);
            if (!chunks.add(ChunkPos.asLong(x, z))) continue;
            LevelChunk chunk = client.getChunkSource().getChunk(x, z, false);
            if (chunk == null) continue;
            try {
                if (minimap) MINIMAP_CLEAN.setBoolean(chunk, false);
                if (worldMap) WORLD_MAP_CLEAN.setBoolean(chunk, false);
            } catch (IllegalAccessException ignored) {
            }
        }
    }
}
