package net.camacraft.colorfullighting.mixin.compat.xaero.worldmap;

import net.camacraft.colorfullighting.compat.xaero.worldmap.WorldMapColorTextures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.map.region.texture.RegionTexture;

/** Which World Map texture object a texture id drawn on the map belongs to (WorldMapColorTextures). */
@Mixin(value = RegionTexture.class, remap = false)
public abstract class XaeroRegionTextureMixin {
    @Shadow protected int glColorTexture;

    @Inject(method = "bindColorTexture", at = @At("RETURN"), require = 0)
    private void colorfullighting$bound(boolean create, CallbackInfoReturnable<Integer> cir) {
        int texture = cir.getReturnValueI();
        if (texture != -1) WorldMapColorTextures.register(texture, (RegionTexture<?>) (Object) this);
    }

    @Inject(method = "deleteTexturesAndBuffers", at = @At("HEAD"), require = 0)
    private void colorfullighting$deleted(CallbackInfo ci) {
        if (this.glColorTexture != -1) WorldMapColorTextures.unregister(this.glColorTexture);
    }
}
