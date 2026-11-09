package net.camacraft.colorfullighting.api.dynamic;

import net.minecraft.client.multiplayer.ClientLevel;

/**
 * A mod's dynamic lights (held torches, burning mobs, lamps on moving ships), given to Colorful Lighting every client tick
 * and, for its shader renderer, every frame. Colorful Lighting mixes them into every light sample it answers (entities,
 * block entities, particles and, when meshes take dynamic light, chunk meshes with the rebuilds that need), in colour, and
 * under a shader pack through the packed light the pack decodes. Register with
 * {@code DynamicLightsCompat.registerExternalProvider}; Lively Lighting's client module is one.
 *
 * @since 2.8.0
 */
@FunctionalInterface
public interface DynamicLightProvider {
    /**
     * @param level       the level being lit
     * @param partialTick 1 for the tick's positions (the CPU paths and rebuild tracking), else the moment of the frame
     * @param sink        where the lights go
     */
    void collect(ClientLevel level, float partialTick, DynamicLightSink sink);
}
