package net.camacraft.colorfullighting.common.engine.reference;

import net.camacraft.colorfullighting.common.engine.AbstractColoredLightSection;
import net.camacraft.colorfullighting.common.util.ColorRGB4;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.BlockLightEngine;

import java.util.function.Supplier;

public class WrapperLightSection extends AbstractColoredLightSection {
	Supplier<DataLayer> red;
	Supplier<DataLayer> green;
	Supplier<DataLayer> blue;
	
	public WrapperLightSection(Supplier<DataLayer> red, Supplier<DataLayer> green, Supplier<DataLayer> blue) {
		this.red = red;
		this.green = green;
		this.blue = blue;
	}
	
	@Override
	public ColorRGB4 get(int x, int y, int z) {
		return get(getColorIndex(x, y, z));
	}
	
	@Override
	public ColorRGB4 get(int colorIndex) {
		int r = getData(red.get(), colorIndex);
		int g = getData(green.get(), colorIndex);
		int b = getData(blue.get(), colorIndex);
		
		return ColorRGB4.fromRGB4(r, g, b);
	}
	
	public static int getColorIndex(int x, int y, int z) {
		return (y << 8 | z << 4 | x);
	}
	
	@Override
	public int getPacked(int x, int y, int z) {
		return getPacked(getColorIndex(x, y, z));
	}
	
	private int getData(DataLayer dataLayer, int index) {
		if (dataLayer == null) return 0;
		byte b = dataLayer.getData()[index >> 1];
		int nibble = index & 1;
		return b >> 4 * nibble & 15;
	}
	
	@Override
	public int getPacked(int colorIndex) {
		int r = getData(red.get(), colorIndex);
		int g = getData(green.get(), colorIndex);
		int b = getData(blue.get(), colorIndex);
		
		return r << 8 | g << 4 | b;
	}
	
	@Override
	public void set(int x, int y, int z, ColorRGB4 value) {
	
	}
	
	@Override
	public void set(int colorIndex, ColorRGB4 value) {
	
	}
	
	private boolean isPresent(DataLayer dataLayer) {
		if (dataLayer == null) return false;
		return !dataLayer.isEmpty();
	}
	
	@Override
	public boolean hasData() {
		return
				!isPresent(red.get()) ||
						!isPresent(green.get()) ||
						!isPresent(blue.get())
				;
	}
	
	@Override
	public void clear() {
		if (isPresent(red.get())) red.get().fill(0);
		if (isPresent(green.get())) green.get().fill(0);
		if (isPresent(blue.get())) blue.get().fill(0);
	}
}
