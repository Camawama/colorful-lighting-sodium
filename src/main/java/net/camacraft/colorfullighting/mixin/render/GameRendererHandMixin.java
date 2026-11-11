package net.camacraft.colorfullighting.mixin.render;

import net.camacraft.colorfullighting.bloom.Bloom;
import net.camacraft.colorfullighting.bloom.ItemGlows;
import net.camacraft.colorfullighting.compat.vanilla.EntityBlacklight;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The hand (2.12.0). It is drawn after the level, in the camera's own space: while it is, the entity shaders' blacklight
 * stays on (what the hand holds is half a block before the camera, which is near enough to where the player stands), and
 * the items it holds are noted for the bloom, which draws a pass of its own over the hand once it is done.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererHandMixin {
    @Inject(method = "renderItemInHand", at = @At("HEAD"), require = 0)
    private void colorfullighting$handStart(CallbackInfo ci) {
        EntityBlacklight.levelStart();
        ItemGlows.handStart();
    }

    @Inject(method = "renderItemInHand", at = @At("RETURN"), require = 0)
    private void colorfullighting$handDone(CallbackInfo ci) {
        ItemGlows.handEnd();
        EntityBlacklight.levelEnd();
        Bloom.renderHand();
    }
}
