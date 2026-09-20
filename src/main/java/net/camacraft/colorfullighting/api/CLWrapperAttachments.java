package net.camacraft.colorfullighting.api;

import net.camacraft.colorfullighting.common.BlockEntityNbtCache;
import net.camacraft.colorfullighting.common.ColoredLightEngine;
import net.camacraft.colorfullighting.common.accessors.LevelAccessor;
import net.camacraft.colorfullighting.common.accessors.mixin.LevelAttachments;
import net.camacraft.colorfullighting.compat.CompatRegistry;
import net.camacraft.colorfullighting.compat.distanthorizons.DhColorCache;
import net.camacraft.colorfullighting.compat.dynamiclights.DynamicLightsCompat;
import net.camacraft.colorfullighting.compat.flywheel.FlywheelCompat;
import net.camacraft.colorfullighting.compat.valkyrienskies.VsCompat;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.world.level.Level;

/**
 * Simple method of creating a wrapper level, which refers to a wrapped level's lighting engine and information.
 * This is provided as a stable method of creating wrappers; if you use this, you will not have to maintain you wrapper implementation between versions of CL.
 *
 * Some examples of where this would be useful are {@link RenderChunkRegion}, {@link me.jellysquid.mods.sodium.client.world.WorldSlice}, and {@link net.camacraft.colorfullighting.accessors.LevelWrapper}
 * Colorful lighting opts to implement these manually, but doing so is not recommended for mods implementing CL support on their end.
 *
 * Additionally, when utilizing any of these methods, you should be using {@link LevelAttachments}, you should not cast to a LevelWrapper, as doing so provides no real benefit, and makes your code more prone to errors.
 */
public interface CLWrapperAttachments extends LevelAttachments, CompatRegistry<Level> {
	Level colorfullighting$getWrappedLevel();

	private LevelAttachments wrapped() {
		return (LevelAttachments) colorfullighting$getWrappedLevel();
	}

	@Override
	default ColoredLightEngine colorfullighting$getEngine() {
		return wrapped().colorfullighting$getEngine();
	}

	@Override
	default VsCompat colorfullighting$getVSCompat() {
		return wrapped().colorfullighting$getVSCompat();
	}

	@Override
	default DynamicLightsCompat colorfullighting$getDynamicLights() {
		return wrapped().colorfullighting$getDynamicLights();
	}

	@Override
	default LevelAccessor colorfullighting$getAccessor() {
		return wrapped().colorfullighting$getAccessor();
	}

	@Override
	default BlockEntityNbtCache colorfullighting$getNbtCache() {
		return wrapped().colorfullighting$getNbtCache();
	}

	@Override
	default FlywheelCompat colorfullighting$getFlywheelCompat() {
		return wrapped().colorfullighting$getFlywheelCompat();
	}

	@Override
	default DhColorCache colorfullighting$getDhColorCache() {
		return wrapped().colorfullighting$getDhColorCache();
	}

	@Override
	default void colorfullighting$setDhColorCache(DhColorCache cache) {
		wrapped().colorfullighting$setDhColorCache(cache);
	}
	
	@Override
	default <T> T colorfullighting$getCompatInstance(CompatKey<Level, T> key) {
		return ((CompatRegistry<Level>) wrapped()).colorfullighting$getCompatInstance(key);
	}
}
