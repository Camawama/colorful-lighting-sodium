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
    /**
     * Passed as {@code rgb}: a blacklight. Ultraviolet: little to see of it but a dim violet, and what it falls on glows by
     * how saturated its own colour is (whites a pale blue), where the terrain's fragments draw the dynamic lights. A build
     * before 2.11.0 has no such field: look it up by reflection and pass a violet instead when it is missing.
     *
     * @since 2.11.0
     */
    int BLACKLIGHT = 0x1000000;

    /**
     * Or'd into {@code rgb}: a light that absorbs light. Where it reaches, the block light and the sky light are taken down
     * by what an ordinary light of its level, shape and reach would have added; its red, green and blue say how much of each
     * channel ({@code ABSORB | 0xFFFFFF} takes everything, {@code ABSORB | 0x00FFFF} leaves red light alone). Drawn where
     * the dynamic lights are drawn by the shaders (terrain, entities, Flywheel); elsewhere it is no light at all. A build
     * before 2.12.0 has no such field: look it up by reflection and send no light when it is missing.
     *
     * @since 2.12.0
     */
    int ABSORB = 0x2000000;

    void point(long ownerKey, double x, double y, double z, float level, int rgb, int falloff, float range);

    /**
     * A point light that loses a level every {@code blocksPerLevel} blocks instead of every block: as bright at its source
     * as {@code level} says and reaching {@code level * blocksPerLevel} blocks (a lamp on a ship drawn at twice its size
     * passes 2, at half its size 0.5). {@code range} is then that reach, or less to cut the light short. 1 is the plain
     * {@link #point(long, double, double, double, float, int, int, float)}, which a sink that doesn't know better falls
     * back to.
     *
     * @since 2.10.6
     */
    default void point(long ownerKey, double x, double y, double z, float level, int rgb, int falloff, float range, float blocksPerLevel) {
        point(ownerKey, x, y, z, level, rgb, falloff, range);
    }

    /**
     * A cone: {@code dir} its axis (unit), {@code outerDegrees} the full angle where it ends, {@code innerDegrees} the
     * full angle that is fully lit; the light dims along the beam over {@code range}.
     */
    void spot(long ownerKey, double x, double y, double z, double dirX, double dirY, double dirZ, float outerDegrees, float innerDegrees,
              float level, int rgb, float range);
}
