package net.camacraft.colorfullighting.compat.xaero;

/**
 * The colored-light key ({@link XaeroMapColors#key}) each pixel of a Xaero minimap tile was drawn with, kept on the
 * tile by {@code XaeroMinimapTileMixin}, so the minimap redraws a pixel whose light changed color even when its
 * blocks and light levels didn't.
 */
public interface XaeroMinimapTileKeys {
    /** 0 when the pixel was drawn without a colored light. */
    int colorfullighting$key(int x, int z);

    void colorfullighting$setKey(int x, int z, int key);
}
