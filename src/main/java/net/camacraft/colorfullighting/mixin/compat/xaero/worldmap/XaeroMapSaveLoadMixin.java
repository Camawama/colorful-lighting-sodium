package net.camacraft.colorfullighting.mixin.compat.xaero.worldmap;

import net.camacraft.colorfullighting.compat.xaero.XaeroMapColors;
import net.camacraft.colorfullighting.compat.xaero.worldmap.WorldMapColorStore;
import net.camacraft.colorfullighting.compat.xaero.worldmap.WorldMapColors;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.map.biome.BiomeGetter;
import xaero.map.file.MapSaveLoad;
import xaero.map.region.MapRegion;

import java.nio.file.Path;

/**
 * The World Map loads a region (on its own thread): read the region's recorded colors along with it, before any of
 * its tiles is drawn.
 */
@Mixin(value = MapSaveLoad.class, remap = false)
public abstract class XaeroMapSaveLoadMixin {
    @Inject(method = "loadRegion", at = @At("HEAD"), require = 0)
    private void colorfullighting$loadColors(MapRegion region, HolderLookup<Block> blockLookup, Registry<Block> blockRegistry,
                                             Registry<Fluid> fluidRegistry, BiomeGetter biomeGetter, boolean debugConfig,
                                             int extraAttempts, CallbackInfoReturnable<Boolean> cir) {
        if (!XaeroMapColors.worldMapOn()) return;
        Path file = WorldMapColors.file(region);
        if (file != null) WorldMapColorStore.loaded(file);
    }
}
