package net.camacraft.colorfullighting.compat.xaero.worldmap;

import net.camacraft.colorfullighting.compat.xaero.XaeroMapColors;
import net.camacraft.colorfullighting.mixin.compat.xaero.worldmap.XaeroLeveledRegionAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import xaero.map.WorldMap;
import xaero.map.region.LeveledRegion;
import xaero.map.region.MapBlock;
import xaero.map.region.MapRegion;
import xaero.map.region.MapTile;
import xaero.map.region.MapTileChunk;

import java.nio.file.Path;

/**
 * Colored light on Xaero's World Map, recorded the way the World Map records everything else: only when its own
 * writer draws a chunk that is loaded around the player, never by asking about places the player hasn't been.
 *
 * <ul>
 * <li>{@link #capture}: as the writer reads a pixel (MapWriter.loadPixel) it also reads the light's color at the cell
 * the pixel's block light came from, onto the pixel ({@link XaeroMapBlockKey}); a pixel whose color changed counts
 * as changed, so the World Map redraws its tile ({@code XaeroMapBlockMixin}).</li>
 * <li>{@link #recordPixel}: when the World Map draws a tile chunk's texture, the colors its pixels were read with go
 * into the region's {@link RegionColors}, saved beside the region file. A pixel loaded from disk, or rebuilt from the
 * world save in singleplayer, has none and leaves what was recorded.</li>
 * <li>{@link WorldMapColorTextures} draws the recorded colors under every World Map texture, zoomed in or out.</li>
 * </ul>
 */
public final class WorldMapColors {
    private static final BlockPos.MutableBlockPos POS = new BlockPos.MutableBlockPos();
    /** Xaero's "legible cave maps" as of the last tile it drew (they ignore light: colors show by strength). */
    static volatile boolean legibleCaveMaps;
    /** Counters for checking it in a running game (read by reflection: pack harness). */
    public static volatile long captures, recorded, unsettled, skyLit, colored;

    private WorldMapColors() {
    }

    /**
     * The writer read a pixel whose top (the highest block or see-through layer) is at {@code y - 1}: Xaero took its
     * block light at {@code x, y, z}. Render thread.
     */
    public static void capture(Level level, MapBlock pixel, int x, int y, int z) {
        if (!XaeroMapColors.worldMapOn()) return;
        captures++;
        XaeroMapBlockKey keyed = (XaeroMapBlockKey) pixel;
        int light = keyed.colorfullighting$light();
        if (light <= 0 || keyed.colorfullighting$glowing()) {
            keyed.colorfullighting$setKey(0);
            return;
        }
        // light not yet fully propagated here: keep what was recorded rather than a clipped color
        if (!XaeroMapColors.settled(level, x, y, z)) {
            unsettled++;
            return;
        }
        // a cave pixel lit through an opening carries sky light: white
        int blockLight = level.getBrightness(LightLayer.BLOCK, POS.set(x, y, z));
        if (blockLight != light) {
            skyLit++;
            keyed.colorfullighting$setKey(0);
            return;
        }
        int key = XaeroMapColors.key(level, x, y, z, light);
        if (key != 0) colored++;
        keyed.colorfullighting$setKey(key);
    }

    /** The World Map is drawing a tile chunk's texture: whether its cave maps are legible. */
    public static void beginTileChunk(boolean legible) {
        legibleCaveMaps = legible;
    }

    /** The tile chunk's region colors, or null when the World Map's colors are off or the region has no file. */
    public static RegionColors regionColors(MapTileChunk tileChunk) {
        if (!XaeroMapColors.worldMapOn()) return null;
        Path file = file(tileChunk.getInRegion());
        return file == null ? null : WorldMapColorStore.loaded(file);
    }

    /** The pixel {@code x, y} (0..63) of a tile chunk's texture was drawn: record the color it was read with. */
    public static boolean recordPixel(MapTileChunk tileChunk, RegionColors colors, int x, int y) {
        MapTile tile = tileChunk.getTile(x >> 4, y >> 4);
        if (tile == null || !tile.isLoaded()) return false;
        MapBlock block = tile.getBlock(x & 15, y & 15);
        if (block == null) return false;
        int key = ((XaeroMapBlockKey) block).colorfullighting$key();
        if (key == XaeroMapBlockKey.UNKNOWN) return false;
        boolean changed = colors.set(tileIndex(tileChunk), y << 6 | x, key);
        if (changed) recorded++;
        return changed;
    }

    static int tileIndex(MapTileChunk tileChunk) {
        return (tileChunk.getZ() & 7) << 3 | (tileChunk.getX() & 7);
    }

    /** The colors file of a leaf region (512 blocks), beside its World Map region file. */
    public static Path file(MapRegion region) {
        String mw = region.isNormalMapData() ? region.getMwId() : "cm$converted";
        return file(region.getWorldId(), region.getDimId(), mw, region.getCaveLayer(), region.getRegionX(), region.getRegionZ());
    }

    /** The colors file of leaf region {@code regionX, regionZ} in the same world, dimension and layer as {@code region}. */
    static Path file(LeveledRegion<?> region, int regionX, int regionZ) {
        XaeroLeveledRegionAccessor ids = (XaeroLeveledRegionAccessor) region;
        boolean normal = region instanceof MapRegion leaf ? leaf.isNormalMapData()
                : region.getDim() == null || !region.getDim().isUsingWorldSave();
        String mw = normal ? ids.colorfullighting$mwId() : "cm$converted";
        return file(ids.colorfullighting$worldId(), ids.colorfullighting$dimId(), mw, region.getCaveLayer(), regionX, regionZ);
    }

    /** As MapSaveLoad.getNormalFile lays the World Map's own region files out. */
    private static Path file(String worldId, String dimId, String mwId, int caveLayer, int regionX, int regionZ) {
        if (worldId == null || dimId == null || WorldMap.saveFolder == null) return null;
        Path folder = WorldMap.saveFolder.toPath().resolve(worldId).resolve(dimId);
        if (mwId != null) folder = folder.resolve(mwId);
        if (caveLayer != Integer.MAX_VALUE) folder = folder.resolve("caves").resolve(Integer.toString(caveLayer));
        return folder.resolve(regionX + "_" + regionZ + ".clcolors");
    }
}
