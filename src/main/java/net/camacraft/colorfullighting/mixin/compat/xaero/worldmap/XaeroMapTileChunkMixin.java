package net.camacraft.colorfullighting.mixin.compat.xaero.worldmap;

import net.camacraft.colorfullighting.compat.xaero.worldmap.RegionColors;
import net.camacraft.colorfullighting.compat.xaero.worldmap.WorldMapColorStore;
import net.camacraft.colorfullighting.compat.xaero.worldmap.WorldMapColors;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.map.MapProcessor;
import xaero.map.biome.BlockTintProvider;
import xaero.map.cache.BlockStateShortShapeCache;
import xaero.map.region.MapTileChunk;
import xaero.map.region.MapUpdateFastConfig;
import xaero.map.region.OverlayManager;

import java.nio.ByteBuffer;

/**
 * The World Map draws a tile chunk's texture from its pixels (render thread): each pixel's colored-light key, where
 * the writer read one, goes into the region's recorded colors (WorldMapColors.recordPixel).
 */
@Mixin(value = MapTileChunk.class, remap = false)
public abstract class XaeroMapTileChunkMixin {
    @Unique private RegionColors colorfullighting$colors;
    @Unique private boolean colorfullighting$changed;

    @Inject(method = "updateBuffers", at = @At("HEAD"), require = 0)
    private void colorfullighting$begin(MapProcessor mapProcessor, BlockTintProvider blockTintProvider, OverlayManager overlayManager,
                                        boolean detailedDebug, BlockStateShortShapeCache blockStateShortShapeCache,
                                        MapUpdateFastConfig config, CallbackInfo ci) {
        WorldMapColors.beginTileChunk(config.legibleCaveMaps);
        this.colorfullighting$colors = WorldMapColors.regionColors((MapTileChunk) (Object) this);
        this.colorfullighting$changed = false;
    }

    @Redirect(method = "updateBuffers", at = @At(value = "INVOKE", target = "Lxaero/map/region/MapTileChunk;putColour(IIIIIILjava/nio/ByteBuffer;I)V"), require = 0)
    private void colorfullighting$pixelDrawn(MapTileChunk self, int x, int y, int red, int green, int blue, int alpha, ByteBuffer buffer, int size) {
        self.putColour(x, y, red, green, blue, alpha, buffer, size);
        RegionColors colors = this.colorfullighting$colors;
        if (colors != null && WorldMapColors.recordPixel(self, colors, x, y)) this.colorfullighting$changed = true;
    }

    @Inject(method = "updateBuffers", at = @At("RETURN"), require = 0)
    private void colorfullighting$end(CallbackInfo ci) {
        if (this.colorfullighting$changed) WorldMapColorStore.changed();
        this.colorfullighting$colors = null;
    }
}
