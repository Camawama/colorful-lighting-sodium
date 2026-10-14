package net.camacraft.colorfullighting.mixin.compat.xaero.worldmap;

import net.camacraft.colorfullighting.compat.xaero.worldmap.XaeroMapShaders;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Xaero's library (nested in Xaero's mods, so named here) has made its shaders: put Colorful Lighting's colored map
 * shaders in place of the two that draw World Map textures (XaeroMapShaders).
 */
@Pseudo
@Mixin(targets = "xaero.lib.client.graphics.shader.LibShaders", remap = false)
public abstract class XaeroLibShadersMixin {
    @Inject(method = "onResourceReload", at = @At("RETURN"), require = 0)
    private static void colorfullighting$coloredMapShaders(ResourceManager resourceManager, CallbackInfo ci) {
        XaeroMapShaders.afterReload(resourceManager);
    }
}
