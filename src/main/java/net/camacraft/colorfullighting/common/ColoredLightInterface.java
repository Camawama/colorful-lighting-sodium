package net.camacraft.colorfullighting.common;

import net.camacraft.colorfullighting.common.accessors.ClientAccessor;
import net.camacraft.colorfullighting.common.engine.AbstractColoredLightEngine;
import net.camacraft.colorfullighting.common.engine.cl.CLEngine;
import net.camacraft.colorfullighting.common.engine.reference.TripleVanillaEngine;
import net.minecraft.world.level.Level;

public class ColoredLightInterface extends ColoredLightEngine {
	public static ColoredLightInterface create(Level level, ClientAccessor clientAccessor) {
		return new ColoredLightInterface(level, clientAccessor);
	}
	
	public ColoredLightInterface(Level level, ClientAccessor clientAccessor) {
		super(level, clientAccessor);
	}
	
	/* Outlined: convenient place for mixing into to swap out the engine */
	protected AbstractColoredLightEngine createEngine(Level level, ClientAccessor clientAccessor) {
		return new CLEngine(this);
//		return new TripleVanillaEngine(this);
	}
}
