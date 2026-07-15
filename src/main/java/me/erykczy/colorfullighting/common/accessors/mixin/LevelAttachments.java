package me.erykczy.colorfullighting.common.accessors.mixin;

import me.erykczy.colorfullighting.common.ColoredLightEngine;
import me.erykczy.colorfullighting.common.accessors.LevelAccessor;
import me.erykczy.colorfullighting.compat.valkyrienskies.VsCompat;

public interface LevelAttachments {
	ColoredLightEngine colorfullighting$getEngine();
	
	VsCompat colorfullighting$getVSCompat();
	
	LevelAccessor colorfullighting$getAccessor();
}
