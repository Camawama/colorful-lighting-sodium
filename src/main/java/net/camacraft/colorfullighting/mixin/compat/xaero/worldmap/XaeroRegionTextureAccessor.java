package net.camacraft.colorfullighting.mixin.compat.xaero.worldmap;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import xaero.map.region.LeveledRegion;
import xaero.map.region.texture.RegionTexture;

/** The region a World Map texture belongs to (WorldMapColorTextures). */
@Mixin(value = RegionTexture.class, remap = false)
public interface XaeroRegionTextureAccessor {
    @Accessor("region")
    LeveledRegion<?> colorfullighting$region();
}
