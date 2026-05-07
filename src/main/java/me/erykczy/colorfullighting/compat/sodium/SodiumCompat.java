package me.erykczy.colorfullighting.compat.sodium;

import net.neoforged.fml.ModList;

public class SodiumCompat {
    public static boolean isSodiumLoaded() {
        return ModList.get().isLoaded("sodium");
    }
}
