package me.erykczy.colorfullighting.common.config;

import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

/**
 * One conditional entry of a {@link VariantList}: a block state predicate, an NBT predicate, or both.
 *
 * @param state block state condition, {@link StateCondition#ALWAYS} when unconstrained
 * @param nbt   NBT that must be contained in the target's NBT, null when unconstrained
 * @param value the emitter/filter to use when both conditions hold
 */
public record LightVariant<T>(StateCondition state, @Nullable CompoundTag nbt, T value) {
}
