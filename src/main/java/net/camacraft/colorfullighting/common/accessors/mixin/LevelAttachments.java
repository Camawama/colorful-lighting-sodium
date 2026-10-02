package net.camacraft.colorfullighting.common.accessors.mixin;

import net.camacraft.colorfullighting.common.BlockEntityNbtCache;
import net.camacraft.colorfullighting.common.ColoredLightEngine;
import net.camacraft.colorfullighting.common.accessors.LevelAccessor;
import net.camacraft.colorfullighting.compat.distanthorizons.DhColorCache;
import net.camacraft.colorfullighting.compat.dynamiclights.DynamicLightsCompat;
import net.camacraft.colorfullighting.compat.flywheel.FlywheelCompat;
import net.camacraft.colorfullighting.compat.valkyrienskies.VsCompat;

/**
 * Anything implementing level attachments should also implement {@link net.camacraft.colorfullighting.compat.CompatRegistry}
 * If you are making a wrapper level, you should probably implement {@link net.camacraft.colorfullighting.api.CLWrapperAttachments} instead
 */
public interface LevelAttachments {
	ColoredLightEngine colorfullighting$getEngine();

	VsCompat colorfullighting$getVSCompat();

	/** Per-level dynamic (entity/held-item) light state; null on levels without an engine. */
	DynamicLightsCompat colorfullighting$getDynamicLights();

	LevelAccessor colorfullighting$getAccessor();

	BlockEntityNbtCache colorfullighting$getNbtCache();

	FlywheelCompat colorfullighting$getFlywheelCompat();

	/** Distant Horizons LOD colour memory; created lazily by DhCompat on the client thread. */
	DhColorCache colorfullighting$getDhColorCache();

	void colorfullighting$setDhColorCache(DhColorCache cache);
}
