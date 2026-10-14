package net.camacraft.colorfullighting.mixin.compat.xaero.worldmap;

import net.camacraft.colorfullighting.compat.xaero.worldmap.WorldMapColorTextures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRenderer;

import java.util.function.IntConsumer;

/**
 * The World Map screen draws its textures in batches, binding each texture before its draw: bind its colors texture
 * beside it (WorldMapColorTextures.afterBind; only while Colorful Lighting's map shader is the one drawing).
 */
@Mixin(value = MultiTextureRenderTypeRenderer.class, remap = false)
public abstract class XaeroWorldMapTextureDrawMixin {
    @Shadow private IntConsumer textureBinderShader;

    @Redirect(method = "draw", at = @At(value = "INVOKE", target = "Ljava/util/function/IntConsumer;accept(I)V"), require = 0)
    private void colorfullighting$bindColors(IntConsumer binder, int texture) {
        binder.accept(texture);
        WorldMapColorTextures.afterBind(binder == this.textureBinderShader, texture);
    }
}
