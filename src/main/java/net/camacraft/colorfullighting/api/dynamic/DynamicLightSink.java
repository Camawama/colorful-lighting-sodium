package net.camacraft.colorfullighting.api.dynamic;

/**
 * Where a {@link DynamicLightProvider} puts its lights. Positions are world coordinates (a light on a Valkyrien Skies
 * ship at its place in the world; Colorful Lighting mirrors it into the shipyard itself). {@code ownerKey} names the same
 * light across ticks, so Colorful Lighting can tell a light that moved from a new one and rebuild only what changed.
 * {@code level} is 0 to 15 (fractional while fading; a point light's may go to 31 since 2.10.1: the light itself stays at
 * 15 for the blocks above and reaches that much farther), {@code rgb} is {@code 0xRRGGBB} or -1 for white, {@code range} the
 * reach in blocks, {@code falloff} 0 for a level lost per block of Manhattan distance (vanilla's diamond) or 1 per block
 * of straight distance (a round light).
 *
 * @since 2.8.0
 */
public interface DynamicLightSink {
    void point(long ownerKey, double x, double y, double z, float level, int rgb, int falloff, float range);

    /**
     * A cone: {@code dir} its axis (unit), {@code outerDegrees} the full angle where it ends, {@code innerDegrees} the
     * full angle that is fully lit; the light dims along the beam over {@code range}.
     */
    void spot(long ownerKey, double x, double y, double z, double dirX, double dirY, double dirZ, float outerDegrees, float innerDegrees,
              float level, int rgb, float range);
}
