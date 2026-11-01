package net.camacraft.colorfullighting.compat.distanthorizons;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.FogType;

/**
 * Distant Horizons 3.3.3's "special" fog state ({@code MinecraftRenderWrapper.isFogStateSpecial}), mirrored without DH's
 * internals: the camera's fog type isn't NONE (water, lava, powder snow), or the fluid at the camera's block isn't water,
 * lava or empty (DH compares registry ids, so a modded fluid or flowing water counts), or the player is blind or in
 * darkness. DH then fogs every LOD whole in the fog colour ({@code FogRenderParamFactory}: far fog from 0, thickness 1).
 */
final class DhFogState {
    private DhFogState() {
    }

    static boolean special() {
        Minecraft mc = Minecraft.getInstance();
        Camera camera = mc.gameRenderer.getMainCamera();
        if (camera.getFluidInCamera() != FogType.NONE) return true;
        if (mc.level != null) {
            FluidState fluid = mc.level.getFluidState(camera.getBlockPosition());
            if (fluid.getType() != Fluids.WATER && fluid.getType() != Fluids.LAVA && fluid.getType() != Fluids.EMPTY) return true;
        }
        var player = mc.player;
        return player != null && (player.getEffect(MobEffects.BLINDNESS) != null || player.getEffect(MobEffects.DARKNESS) != null);
    }
}
