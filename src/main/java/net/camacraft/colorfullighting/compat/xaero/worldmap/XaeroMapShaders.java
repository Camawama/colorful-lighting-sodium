package net.camacraft.colorfullighting.compat.xaero.worldmap;

import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

/**
 * Colorful Lighting's versions of the shaders Xaero's World Map textures are drawn with (Xaero's library
 * {@code LibShaders.WORLD_MAP} and {@code MINIMAP_WORLD_MAP}, made from {@code xaerolib/map}): the same picture, plus
 * the block-lit part of a pixel tinted by the colors texture on unit 1 ({@link WorldMapColorTextures}). An empty
 * colors texture gives exactly Xaero's result. Made after each resource reload in place of Xaero's
 * ({@code XaeroLibShadersMixin}); with XaeroPlus, a variant that keeps XaeroPlus's transparent background (its
 * {@code xaeroplus/custom_map} shader, found by XaeroPlus through the word "xaeroplus" in the name). The files are
 * {@code assets/minecraft/shaders/core/colorful_lighting_xaero/}.
 *
 * <p>Xaero's library is nested in Xaero's mods and not on Colorful Lighting's compile classpath, hence the
 * reflection; if anything fails Xaero's own shaders stay and the maps are grey as before.
 */
public final class XaeroMapShaders {
    private static volatile ShaderInstance worldMap;
    private static volatile ShaderInstance minimapWorldMap;

    private XaeroMapShaders() {
    }

    /** Whether a shader is one of these (the colors texture is bound only for them). */
    public static boolean isColored(ShaderInstance shader) {
        return shader != null && (shader == worldMap || shader == minimapWorldMap);
    }

    /** Xaero's library has just (re)made its shaders: replace the two map shaders with these. */
    public static void afterReload(ResourceProvider resources) {
        try {
            Class<?> libShaders = Class.forName("xaero.lib.client.graphics.shader.LibShaders");
            Class<?> worldMapShader = Class.forName("xaero.lib.client.graphics.shader.WorldMapShader");
            Constructor<?> make = worldMapShader.getConstructor(String.class, ResourceProvider.class);
            String name = ModList.get().isLoaded("xaeroplus") ? "colorful_lighting_xaero/map_xaeroplus" : "colorful_lighting_xaero/map";
            ShaderInstance map = (ShaderInstance) make.newInstance(name, resources);
            ShaderInstance minimap;
            try {
                minimap = (ShaderInstance) make.newInstance("colorful_lighting_xaero/minimap_map", resources);
            } catch (Throwable t) {
                map.close();
                throw t;
            }
            ShaderInstance oldMap = replace(libShaders.getField("WORLD_MAP"), map);
            ShaderInstance oldMinimap = replace(libShaders.getField("MINIMAP_WORLD_MAP"), minimap);
            worldMap = map;
            minimapWorldMap = minimap;
            if (oldMap != null) oldMap.close();
            if (oldMinimap != null) oldMinimap.close();
            WorldMapColorStore.LOGGER.info("Xaero's map shaders replaced with colored-light versions ({})", name);
        } catch (Throwable t) {
            worldMap = null;
            minimapWorldMap = null;
            WorldMapColorStore.LOGGER.error("Couldn't make the colored Xaero map shaders; Xaero's maps keep their grey lighting", t);
        }
    }

    private static ShaderInstance replace(Field field, ShaderInstance shader) throws IllegalAccessException {
        ShaderInstance old = (ShaderInstance) field.get(null);
        field.set(null, shader);
        return old;
    }
}
