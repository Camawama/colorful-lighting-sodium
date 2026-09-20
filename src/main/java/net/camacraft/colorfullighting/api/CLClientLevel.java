package net.camacraft.colorfullighting.api;

import net.minecraft.client.renderer.LevelRenderer;

/**
 * Implement on a custom client level (see {@link CLSupportingLevel}) that is rendered by
 * something other than the vanilla {@link LevelRenderer}. When colored light changes, Colorful
 * Lighting needs to tell the renderer which sections to re-mesh; for vanilla levels it calls
 * {@link LevelRenderer#setSectionDirty(int, int, int)}, and for levels implementing this interface it calls
 * {@link #colorfullighting$setSectionDirty(int, int, int)} instead, so your renderer can react.
 *
 * <p>Coordinates are section coordinates (block coordinates {@code >> 4}).
 */
public interface CLClientLevel {
	void colorfullighting$setSectionDirty(int x, int y, int z);
}
