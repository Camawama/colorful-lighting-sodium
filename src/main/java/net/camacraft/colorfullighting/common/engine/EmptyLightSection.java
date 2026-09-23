package net.camacraft.colorfullighting.common.engine;

import net.camacraft.colorfullighting.common.util.ColorRGB4;

public class EmptyLightSection extends AbstractColoredLightSection {
	ColorRGB4 BLACK = ColorRGB4.fromRGB4(0, 0, 0);
	
	@Override
	public ColorRGB4 get(int x, int y, int z) {
		return BLACK;
	}
	
	@Override
	public ColorRGB4 get(int colorIndex) {
		return BLACK;
	}
	
	@Override
	public int getPacked(int x, int y, int z) {
		return 0;
	}
	
	@Override
	public int getPacked(int colorIndex) {
		return 0;
	}
	
	@Override
	public void set(int x, int y, int z, ColorRGB4 value) {
	}
	
	@Override
	public void set(int colorIndex, ColorRGB4 value) {
	}
	
	@Override
	public boolean hasData() {
		return false;
	}
	
	@Override
	public void clear() {
	}
}
