package net.camacraft.colorfullighting.mixin.compat.xaero.worldmap;

import net.camacraft.colorfullighting.compat.xaero.worldmap.WorldMapColorTextures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import xaero.common.graphics.renderer.multitexture.MultiTextureRenderTypeRenderer;

import java.util.function.IntConsumer;

/**
 * The minimap showing World Map chunks draws the World Map's textures with its own copy of the batch renderer: bind
 * their colors textures the same way (see XaeroWorldMapTextureDrawMixin).
 */
@Mixin(value = MultiTextureRenderTypeRenderer.class, remap = false)
public abstract class XaeroMinimapTextureDrawMixin {
    @Shadow private IntConsumer textureBinderShader;

    @Redirect(method = "draw", at = @At(value = "INVOKE", target = "Ljava/util/function/IntConsumer;accept(I)V"), require = 0)
    private void colorfullighting$bindColors(IntConsumer binder, int texture) {
        binder.accept(texture);
        WorldMapColorTextures.afterBind(binder == this.textureBinderShader, texture);
    }
}
