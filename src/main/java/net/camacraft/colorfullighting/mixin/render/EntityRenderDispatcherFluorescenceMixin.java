package net.camacraft.colorfullighting.mixin.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.camacraft.colorfullighting.compat.vanilla.EntityFluorescence;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fluorescence (2.14.0): an entity is about to be drawn, and {@link EntityFluorescence} notes its texture if it is on a
 * mod's list of what glows under ultraviolet. An inject at the head that changes nothing, which other mods' hooks into the
 * same method stack with; missing its place only leaves such entities without their glow.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherFluorescenceMixin {
    @Inject(method = "render(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"), require = 0)
    private void colorfullighting$fluorescent(Entity entity, double x, double y, double z, float yaw, float partialTick, PoseStack pose,
                                              MultiBufferSource buffers, int light, CallbackInfo ci) {
        EntityFluorescence.drawing((EntityRenderDispatcher) (Object) this, entity);
    }
}
