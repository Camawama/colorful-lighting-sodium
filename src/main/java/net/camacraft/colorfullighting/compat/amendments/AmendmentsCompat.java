package net.camacraft.colorfullighting.compat.amendments;

import java.lang.reflect.Field;
import java.util.function.Supplier;

/**
 * Amendments' client option {@code dragon_breath_emissive} (its ClientConfigs.DRAGON_BREATH_EMISSIBE), read by
 * reflection so Colorful Lighting needs no Amendments at compile time. Only called from the Amendments compat
 * mixin, which applies only with Amendments installed.
 */
public final class AmendmentsCompat {
    private AmendmentsCompat() {}

    private static Supplier<?> dragonBreathEmissive;
    private static boolean lookedUp;

    /** Whether Amendments draws dragon's breath particles at block light 10 or more; its default (on) if unreadable. */
    public static boolean dragonBreathEmissive() {
        if (!lookedUp) {
            lookedUp = true;
            try {
                Field field = Class.forName("net.mehvahdjukaar.amendments.configs.ClientConfigs").getField("DRAGON_BREATH_EMISSIBE");
                dragonBreathEmissive = (Supplier<?>) field.get(null);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            }
        }
        try {
            return dragonBreathEmissive == null || Boolean.TRUE.equals(dragonBreathEmissive.get());
        } catch (RuntimeException e) {
            return true;
        }
    }
}
