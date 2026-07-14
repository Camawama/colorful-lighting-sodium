package me.erykczy.colorfullighting.mixin;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The client's beacon never learns its own effect from the server, so BeaconEffectSync writes it in
 * directly. These fields are package-private in vanilla.
 */
@Mixin(BeaconBlockEntity.class)
public interface BeaconBlockEntityAccessor {
    @Accessor("primaryPower")
    void colorfullighting$setPrimaryPower(MobEffect effect);

    @Accessor("secondaryPower")
    void colorfullighting$setSecondaryPower(MobEffect effect);
}
