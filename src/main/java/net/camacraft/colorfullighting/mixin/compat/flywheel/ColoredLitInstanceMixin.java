package net.camacraft.colorfullighting.mixin.compat.flywheel;

import net.camacraft.colorfullighting.compat.vanillin.VanillinChestLight;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * The packed light set on a Flywheel instance: while Vanillin's chest visual relights ({@link VanillinChestLight}), the
 * value it rebuilt from vanilla's nibbles (the sky nibble of a colored value gone) is replaced by the colored max of
 * the answers {@code getLightColor} gave it. Any other call passes through unchanged.
 */
@Pseudo
@Mixin(targets = "dev.engine_room.flywheel.lib.instance.ColoredLitInstance", remap = false)
public abstract class ColoredLitInstanceMixin {
    @ModifyVariable(method = {"light(I)Ldev/engine_room/flywheel/lib/instance/ColoredLitInstance;",
            "light(I)Ldev/engine_room/flywheel/api/instance/FlatLit;"}, at = @At("HEAD"), argsOnly = true, require = 0, remap = false)
    private int colorfullighting$chestLight(int packed) {
        return VanillinChestLight.instanceLight(packed);
    }
}
