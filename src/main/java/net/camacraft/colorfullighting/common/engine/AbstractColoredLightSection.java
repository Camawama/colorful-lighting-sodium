package net.camacraft.colorfullighting.common.engine;

import net.camacraft.colorfullighting.common.util.ColorRGB4;

/**
 * Stores light color for each block in the section
 */
/*
* abstract: not every lighting engine is going to want to use a compliant storage mechanism, so the engine needs to be able to wrap whatever storage it uses
*/
public abstract class AbstractColoredLightSection {
    public AbstractColoredLightSection() {}

    public abstract ColorRGB4 get(int x, int y, int z);
    public abstract ColorRGB4 get(int colorIndex);

    public abstract int getPacked(int x, int y, int z);

    /**
     * @return the colour as a packed 12-bit {@code r << 8 | g << 4 | b}, zero when the section is empty.
     */
    public abstract int getPacked(int colorIndex);

    public abstract void set(int x, int y, int z, ColorRGB4 value);
    public abstract void set(int colorIndex, ColorRGB4 value);


    /** Whether any colour has ever been written to this section (allocation is lazy). */
    public abstract boolean hasData();

    public abstract void clear();
}
