package net.camacraft.colorfullighting.compat.xaero.worldmap;

/**
 * What Colorful Lighting keeps on a World Map pixel ({@code xaero.map.region.MapBlock}, by
 * {@code XaeroMapBlockMixin}): the colored-light key the World Map's writer read for it, or {@link #UNKNOWN} for a
 * pixel loaded from disk or written while the engine had no settled color there (then the region's recorded colors
 * stand).
 */
public interface XaeroMapBlockKey {
    int UNKNOWN = -1;

    int colorfullighting$key();

    void colorfullighting$setKey(int key);

    /** The block light Xaero stored for the pixel (the top layer's, or the surface's). */
    int colorfullighting$light();

    boolean colorfullighting$glowing();
}
