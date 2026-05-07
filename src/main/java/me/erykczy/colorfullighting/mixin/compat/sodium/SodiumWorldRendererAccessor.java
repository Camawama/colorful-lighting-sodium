package me.erykczy.colorfullighting.mixin.compat.sodium;

import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Invoker;

@Pseudo
@Mixin(value = SodiumWorldRenderer.class, remap = false)
public interface SodiumWorldRendererAccessor {
    @Invoker("scheduleRebuildForChunk")
    void scheduleRebuild(int x, int y, int z, boolean important);
}
