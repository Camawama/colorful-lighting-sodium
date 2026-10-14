package net.camacraft.colorfullighting.mixin.compat.xaero.worldmap;

import net.camacraft.colorfullighting.compat.xaero.worldmap.WorldMapColors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.map.MapWriter;
import xaero.map.region.MapBlock;

/**
 * The World Map's writer read a pixel of a loaded chunk near the player: read the color of the light that lights it
 * too (WorldMapColors.capture). {@code topH} is the pixel's top (the highest block or see-through layer); Xaero read
 * the pixel's light in the cell above it.
 */
@Mixin(value = MapWriter.class, remap = false)
public abstract class XaeroMapWriterMixin {
    @Shadow private int topH;

    @Inject(method = "loadPixel", at = @At("RETURN"), require = 0)
    private void colorfullighting$readColor(Level world, Registry<Block> blockRegistry, MapBlock pixel, MapBlock currentPixel,
                                            LevelChunk bchunk, int insideX, int insideZ, int highY, int lowY, boolean cave,
                                            boolean fullCave, int mappedHeight, boolean canReuseBiomeColours,
                                            boolean ignoreHeightmaps, Registry<Biome> biomeRegistry, boolean flowers,
                                            int worldBottomY, BlockPos.MutableBlockPos mutableBlockPos3, CallbackInfo ci) {
        WorldMapColors.capture(world, pixel, (bchunk.getPos().x << 4) + insideX, this.topH + 1, (bchunk.getPos().z << 4) + insideZ);
    }
}
