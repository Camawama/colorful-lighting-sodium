package me.erykczy.colorfullighting.compat.oculus;

import java.lang.reflect.Method;
import net.minecraftforge.fml.ModList;

public class OculusCompat {
    private static Object irisApiInstance;
    private static Method isShaderPackInUseMethod;
    private static boolean initialized = false;

    public static void init() {
        try {
            Class<?> apiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Method getInstanceMethod = apiClass.getMethod("getInstance");
            irisApiInstance = getInstanceMethod.invoke(null);
            isShaderPackInUseMethod = apiClass.getMethod("isShaderPackInUse");
            initialized = true;
        } catch (Exception e) {
            initialized = false;
        }
    }

    public static boolean isOculusLoaded() {
        return ModList.get().isLoaded("oculus");
    }

    public static boolean isShaderPackInUse() {
        if (!initialized) return false;
        try {
            return (boolean) isShaderPackInUseMethod.invoke(irisApiInstance);
        } catch (Exception e) {
            return false;
        }
    }
}