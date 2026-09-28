package net.camacraft.colorfullighting.common.util;

// int because java doesn't support unsigned shorts (for some reason)
public class ColorRGB16 {
    public int red, green, blue;

    public static ColorRGB16 fromRGB4(ColorRGB4 value) {
		// 4369 = 17 * 257
        return new ColorRGB16(value.red4 * 4369, value.green4 * 4369, value.blue4 * 4369);
    }
	
    public static ColorRGB16 fromRGB4Int(int value) {
	    int r = (value >>> 8) & 0x0F;
		int g = (value >>> 4) & 0x0F;
		int b = value & 0x0F;
        return new ColorRGB16(r * 4369, g * 4369, b * 4369);
    }

    public static ColorRGB16 fromRGB8(int r, int g, int b) {
        return new ColorRGB16(r * 257, g * 257, b * 257);
    }

    private ColorRGB16(int red, int green, int blue) {
        this.red = red;
        this.green = green;
        this.blue = blue;
    }

    public boolean isInValidState() {
        return  red >= 0 && red < 65536 &&
                green >= 0 && green < 65536 &&
                blue >= 0 && blue < 65536;
    }

    public ColorRGB16 clamp() {
        return new ColorRGB16(
            MathExt.clamp(red, 0, 65536),
            MathExt.clamp(green, 0, 65536),
            MathExt.clamp(blue, 0, 65536)
        );
    }

    public boolean isZero() {
        return red == 0 && green == 0 && blue == 0;
    }

    public ColorRGB16 add(ColorRGB16 other) {
        return new ColorRGB16(red + other.red, green + other.green, blue + other.blue);
    }

    public ColorRGB16 mul(float scalar) {
        return new ColorRGB16((int)(red * scalar), (int)(green * scalar), (int)(blue * scalar));
    }

    public static ColorRGB16 linearInterpolation(ColorRGB16 a, ColorRGB16 b, double x) {
        if(a.isZero()) return b;
        if(b.isZero()) return a;
        return a.mul(1.0f - (float)x).add(b.mul((float)x));
    }

    public ColorRGB4 toRGB4() {
        return ColorRGB4.fromRGB16(red, green, blue);
    }
	
	public ColorRGB8 toRGB8() {
		return ColorRGB8.fromRGB16(red, green, blue);
	}
}
