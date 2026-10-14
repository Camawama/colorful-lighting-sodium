package net.camacraft.colorfullighting.mixin.compat.xaero.worldmap;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import xaero.map.region.LeveledRegion;

/** A World Map region's identity, for finding its colors file (WorldMapColors.file). */
@Mixin(value = LeveledRegion.class, remap = false)
public interface XaeroLeveledRegionAccessor {
    @Accessor("worldId")
    String colorfullighting$worldId();

    @Accessor("dimId")
    String colorfullighting$dimId();

    @Accessor("mwId")
    String colorfullighting$mwId();
}
