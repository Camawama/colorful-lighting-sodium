package net.camacraft.colorfullighting.mixin.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.camacraft.colorfullighting.bloom.ItemGlows;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The bloom's items (2.12.0): where an item that gives light is drawn, for {@link ItemGlows}. After the model's own
 * transform and the half-block shift that follow each other in {@code render}, the model fills the unit cube of the pose.
 * An inject, which other mods' hooks into the same method stack with; missing its place only leaves items without a glow.
 */
@Mixin(ItemRenderer.class)
public abstract class ItemRendererGlowMixin {
    @Inject(method = "render(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IILnet/minecraft/client/resources/model/BakedModel;)V",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V", ordinal = 0, shift = At.Shift.AFTER), require = 0)
    private void colorfullighting$itemDrawn(ItemStack stack, ItemDisplayContext context, boolean leftHand, PoseStack pose, MultiBufferSource buffers,
                                            int light, int overlay, BakedModel model, CallbackInfo ci) {
        ItemGlows.drawn(stack, pose.last().pose());
    }
}
