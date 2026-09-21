package net.camacraft.colorfullighting.accessors;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * To be converted into a static utility class and renamed to BlockStateHelper
 */
public class BlockStateWrapper {
    public static String getPropertyString(BlockState state, String propertyName) {
        for (Property<?> prop : state.getProperties()) {
            if (prop.getName().equals(propertyName)) {
                return state.getValue(prop).toString();
            }
        }
        return null;
    }
}
