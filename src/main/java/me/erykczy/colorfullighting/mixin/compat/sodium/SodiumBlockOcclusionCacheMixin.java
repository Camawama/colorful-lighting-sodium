package me.erykczy.colorfullighting.mixin.compat.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockOcclusionCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(value = BlockOcclusionCache.class, remap = false)
public class SodiumBlockOcclusionCacheMixin {
    
}
