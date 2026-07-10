package me.erykczy.colorfullighting.common;

import me.erykczy.colorfullighting.common.util.ColorRGB4;

/**
 * Stores light color for each block in the section
 */
public class ColoredLightSection {
    private static final int LAYER_SIZE = 6144; // = 16 * 16 * 16 * 1.5

    /**
     * Written only by the light propagator thread, read without synchronisation by the render thread
     * and every Sodium chunk-build worker. Volatile gives readers safe publication of the array itself;
     * the byte writes inside it are racy by design. A reader that catches a half-applied write sees one
     * frame of slightly-wrong colour on one block, and the section is marked dirty and re-meshed anyway.
     */
    private volatile byte[] data;

    public ColoredLightSection() {}

    public static int getColorIndex(int x, int y, int z) {
        return (y << 8 | z << 4 | x);
    }

    public ColorRGB4 get(int x, int y, int z) { return get(getColorIndex(x, y, z)); }
    public ColorRGB4 get(int colorIndex) {
        int packed = getPacked(colorIndex);
        return ColorRGB4.fromRGB4((packed >>> 8) & 0x0F, (packed >>> 4) & 0x0F, packed & 0x0F);
    }

    public int getPacked(int x, int y, int z) { return getPacked(getColorIndex(x, y, z)); }

    /**
     * @return the colour as a packed 12-bit {@code r << 8 | g << 4 | b}, zero when the section is empty.
     */
    public int getPacked(int colorIndex) {
        byte[] d = this.data;
        if (d == null) return 0;

        int startBit = colorIndex * 12;
        int bitOffset = (startBit & 0x7);
        int byteIndex = startBit >> 3;
        int rawData = ((d[byteIndex] << 8) & 0xFFFF) | (d[byteIndex + 1] & 0xFF);
        int offsetData = (rawData << bitOffset);

        return (offsetData >>> 4) & 0xFFF;
    }

    public void set(int x, int y, int z, ColorRGB4 value) { set(getColorIndex(x, y, z), value); }
    public void set(int colorIndex, ColorRGB4 value) {
        if(!value.isInValidState()) {
            throw new IllegalArgumentException("Invalid ColoredLightSection.Entry: "+value);
        }
        byte[] d = this.data;
        if(d == null)
            d = new byte[LAYER_SIZE];

        int startBit = colorIndex * 12;
        int byteIndex = startBit >> 3;
        int bitOffset = (startBit & 0x7);

        if(bitOffset == 0) { // whether startBit is divisible by 8 (this means that the color starts at the beginning of the byte)
            d[byteIndex] = (byte) ((value.red4 << 4) | value.green4);
            d[byteIndex + 1] = (byte) (value.blue4 << 4 | (d[byteIndex + 1] & 0x0F));
        }
        else {
            d[byteIndex] = (byte) ((d[byteIndex] & 0xF0) | value.red4);
            d[byteIndex + 1] = (byte) ((value.green4 << 4) | value.blue4);
        }

        this.data = d; // release: readers that observe this store also observe the bytes written above
    }


    public void clear() {
        this.data = null;
    }
}
