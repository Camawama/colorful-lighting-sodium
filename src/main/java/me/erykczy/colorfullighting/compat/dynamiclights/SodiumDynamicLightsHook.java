package me.erykczy.colorfullighting.compat.dynamiclights;

import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

/**
 * Reflection bridge into SodiumDynamicLights (published as "DynamicLights Reforged" /
 * "Sodium/Embeddium Dynamic Lights", mod id "sodiumdynamiclights"). Reads its tracked dynamic light
 * sources; every source is an Entity or BlockEntity implementing
 * {@code toni.sodiumdynamiclights.DynamicLightSource}.
 */
final class SodiumDynamicLightsHook {
    private final Object instance;
    private final Field sourcesField;
    private final Method getLuminanceMethod;
    private final Method getXMethod;
    private final Method getYMethod;
    private final Method getZMethod;

    private SodiumDynamicLightsHook(Object instance, Field sourcesField, Method getLuminanceMethod,
                                    Method getXMethod, Method getYMethod, Method getZMethod) {
        this.instance = instance;
        this.sourcesField = sourcesField;
        this.getLuminanceMethod = getLuminanceMethod;
        this.getXMethod = getXMethod;
        this.getYMethod = getYMethod;
        this.getZMethod = getZMethod;
    }

    @Nullable
    static SodiumDynamicLightsHook tryCreate() {
        try {
            Class<?> mainClass = Class.forName("toni.sodiumdynamiclights.SodiumDynamicLights");
            Class<?> sourceClass = Class.forName("toni.sodiumdynamiclights.DynamicLightSource");
            Object instance = mainClass.getMethod("get").invoke(null);
            if (instance == null) return null;
            Field sourcesField = mainClass.getDeclaredField("dynamicLightSources");
            sourcesField.setAccessible(true);
            return new SodiumDynamicLightsHook(instance, sourcesField,
                    sourceClass.getMethod("sdl$getLuminance"),
                    sourceClass.getMethod("sdl$getDynamicLightX"),
                    sourceClass.getMethod("sdl$getDynamicLightY"),
                    sourceClass.getMethod("sdl$getDynamicLightZ"));
        } catch (Throwable t) {
            return null;
        }
    }

    /** Copy of the tracked source set, so callers never iterate the live collection. Client thread only. */
    Collection<?> getSources() {
        try {
            return new ArrayList<>((Collection<?>) sourcesField.get(instance));
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    int getLuminance(Object source) {
        try {
            return (int) getLuminanceMethod.invoke(source);
        } catch (Throwable t) {
            return 0;
        }
    }

    double getX(Object source) {
        try {
            return (double) getXMethod.invoke(source);
        } catch (Throwable t) {
            return 0.0;
        }
    }

    double getY(Object source) {
        try {
            return (double) getYMethod.invoke(source);
        } catch (Throwable t) {
            return 0.0;
        }
    }

    double getZ(Object source) {
        try {
            return (double) getZMethod.invoke(source);
        } catch (Throwable t) {
            return 0.0;
        }
    }
}
