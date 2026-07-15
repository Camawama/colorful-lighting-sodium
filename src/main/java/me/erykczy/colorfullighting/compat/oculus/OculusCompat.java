package me.erykczy.colorfullighting.compat.oculus;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.zip.ZipFile;

import me.erykczy.colorfullighting.common.accessors.iris.CustomShaderProperties;
import me.erykczy.colorfullighting.mixin.compat.iris.ShaderPackAccessor;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.properties.ShaderProperties;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;

public class OculusCompat {
	private static Object irisApiInstance;
	private static Method isShaderPackInUseMethod;
	private static Method getCurrentPackNameMethod;
	private static Method getShaderpacksDirectoryMethod;
	private static boolean initialized = false;
	private static final Map<String, Boolean> patchedPackCache = new HashMap<>();
	
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
		try {
			Class<?> irisClass = Class.forName("net.irisshaders.iris.Iris");
			getCurrentPackNameMethod = irisClass.getMethod("getCurrentPackName");
			getShaderpacksDirectoryMethod = irisClass.getMethod("getShaderpacksDirectory");
		} catch (Exception e) {
			getCurrentPackNameMethod = null;
			getShaderpacksDirectoryMethod = null;
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
	
	/**
	 * Name of the shaderpack folder/zip currently selected by Iris/Oculus, or null if unknown.
	 */
	public static String getCurrentShaderPackName() {
		if (getCurrentPackNameMethod == null) return null;
		try {
			Object name = getCurrentPackNameMethod.invoke(null);
			return name != null ? name.toString() : null;
		} catch (Exception e) {
			return null;
		}
	}
	
	public static Path getShaderpacksDirectory() {
		if (getShaderpacksDirectoryMethod != null) {
			try {
				Object dir = getShaderpacksDirectoryMethod.invoke(null);
				if (dir instanceof Path path) return path;
			} catch (Exception ignored) {
			}
		}
		return FMLPaths.GAMEDIR.get().resolve("shaderpacks");
	}
	
	/**
	 * Whether the given shaderpack carries the Colorful Lighting patch marker, meaning it understands
	 * the packed colored-lightmap format and the engine can stay enabled while it is active.
	 */
	public static boolean isShaderPackPatched(String packName) {
		Optional<ShaderPack> pack = Iris.getCurrentPack();
		
		if (pack.isPresent()) {
			ShaderProperties properties = ((ShaderPackAccessor) pack.get()).getShaderProperties();
			OptionalInt status = ((CustomShaderProperties) properties).colorfullighting$getCompatStatus();
			return status.orElse(1) != 0;
		}
		
		return isShaderLegacyPatched(packName);
	}
	
	/**
	 * Invalidate after the auto-patcher writes new packs so re-selection picks up the marker.
	 */
	public static void clearPatchedPackCache() {
		patchedPackCache.clear();
	}
	
	private static boolean checkPackPatched(String packName) {
		try {
			Path pack = getShaderpacksDirectory().resolve(packName);
			if (Files.isDirectory(pack)) {
				return Files.exists(pack.resolve(ShaderpackPatchEngine.MARKER_PATH));
			}
			if (Files.isRegularFile(pack) && packName.toLowerCase().endsWith(".zip")) {
				try (ZipFile zip = new ZipFile(pack.toFile())) {
					return zip.getEntry(ShaderpackPatchEngine.MARKER_PATH) != null;
				}
			}
		} catch (Exception ignored) {
		}
		return false;
	}
	
	public static boolean isShaderLegacyPatched(String packName) {
		if (packName == null || packName.isBlank()) return false;
		return patchedPackCache.computeIfAbsent(packName, OculusCompat::checkPackPatched);
		
	}
	
	public static void reloadPack() {
		try {
			if (isShaderPackInUse())
				Iris.reload();
		} catch (Throwable ignored) {
		}
	}
}
