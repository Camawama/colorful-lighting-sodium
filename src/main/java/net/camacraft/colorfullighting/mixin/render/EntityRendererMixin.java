package net.camacraft.colorfullighting.mixin.render;

import net.camacraft.colorfullighting.common.ColoredLightEngine;
import net.camacraft.colorfullighting.common.Config;
import net.camacraft.colorfullighting.common.accessors.mixin.LevelAttachments;
import net.camacraft.colorfullighting.common.util.ColorRGB8;
import net.camacraft.colorfullighting.common.util.PackedLightData;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

@Mixin(EntityRenderer.class)
public class EntityRendererMixin {
    private static final Set<EntityType<?>> FIRE_LIT_ENTITIES = new HashSet<>(Arrays.asList(
            EntityType.BLAZE,
            EntityType.MAGMA_CUBE
    ));
    private static final Set<EntityType<?>> LIT_ENTITIES = new HashSet<>(Arrays.asList(
            EntityType.ALLAY,
            EntityType.DRAGON_FIREBALL,
            EntityType.EXPERIENCE_ORB,
            EntityType.GLOW_SQUID,
            // EntityType.ITEM_FRAME, // Removed to allow colored lighting on Item Frames
            EntityType.SHULKER_BULLET,
            EntityType.EYE_OF_ENDER,
            EntityType.FIREBALL,
            EntityType.SMALL_FIREBALL,
            EntityType.VEX,
            EntityType.WITHER,
            EntityType.WITHER_SKULL
    ));

    @Inject(method = "getPackedLightCoords", at = @At("HEAD"), cancellable = true)
    private <T extends Entity>void colorfullighting$getPackedLightCoords(T entity, float partialTicks, CallbackInfoReturnable<Integer> cir) {
        if (!ColoredLightEngine.isEnabled()) {
            return;
        }
        BlockPos blockpos = BlockPos.containing(entity.getLightProbePosition(partialTicks));
        int skyLight = entity.level().getBrightness(LightLayer.SKY, blockpos);
        ColorRGB8 color = ((LevelAttachments) entity.level()).colorfullighting$getEngine().sampleTrilinearLightColor(entity.getLightProbePosition(partialTicks));
        if(entity.isOnFire() || FIRE_LIT_ENTITIES.contains(entity.getType())) {
            ColorRGB8 fireColor = ColorRGB8.fromRGB4(Config.getLightColor(Blocks.FIRE));
            color = ColorRGB8.fromRGB8(
                Math.max(fireColor.red, color.red),
                Math.max(fireColor.green, color.green),
                Math.max(fireColor.blue, color.blue)
            );
        }
        if(LIT_ENTITIES.contains(entity.getType())) {
            color = ColorRGB8.fromRGB8(255, 255, 255);
        }
        // an entity carrying a dynamic light that asks to be lit by it (a kart under its headlights) is drawn at least that bright (2.10.0)
        int glow = net.camacraft.colorfullighting.compat.dynamiclights.DynamicLightsCompat.entityGlow(entity);
        if (glow > 0) {
            int g8 = glow * 17;
            color = ColorRGB8.fromRGB8(Math.max(g8, color.red), Math.max(g8, color.green), Math.max(g8, color.blue));
        }

        cir.setReturnValue(PackedLightData.packData(skyLight, color));
    }
}
