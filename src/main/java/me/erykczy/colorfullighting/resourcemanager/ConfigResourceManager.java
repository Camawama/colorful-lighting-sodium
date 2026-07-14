package me.erykczy.colorfullighting.resourcemanager;

import me.erykczy.colorfullighting.ColorfulLighting;
import me.erykczy.colorfullighting.common.ColoredLightEngine;
import me.erykczy.colorfullighting.common.BlockEntityNbtCache;
import me.erykczy.colorfullighting.common.Config;
import me.erykczy.colorfullighting.common.config.VariantList;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.ToNumberPolicy;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import org.slf4j.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ConfigResourceManager implements ResourceManagerReloadListener {
    private static final Gson GSON = new GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create();
    private static final Logger LOGGER = ColorfulLighting.LOGGER;
    private static final String BUILT_IN_LIGHT_RESOURCE_PATH = ColorfulLighting.BUILT_IN_LIGHT_RESOURCE_PATH;

    private static final String EMITTERS_PATH = "light/emitters.json";
    private static final String FILTERS_PATH = "light/filters.json";
    private static final String ABSORBERS_PATH = "light/absorbers.json";
    private static final String ENTITIES_PATH = "light/entities.json";
    private static final String ITEMS_PATH = "light/items.json";
    private static final String MOON_PHASES_PATH = "light/moon_phases.json";
    private static final Set<String> CONFIG_PATHS = Set.of(
            EMITTERS_PATH, FILTERS_PATH, ABSORBERS_PATH, ENTITIES_PATH, ITEMS_PATH, MOON_PHASES_PATH);

    @Override
    public void onResourceManagerReload(ResourceManager resourceManager) {
        HashMap<ResourceLocation, VariantList<Config.ColorEmitter>> emitters = new HashMap<>();
        HashMap<ResourceLocation, VariantList<Config.ColorFilter>> filters = new HashMap<>();
        HashMap<ResourceLocation, VariantList<Config.ColorEmitter>> absorbers = new HashMap<>();
        HashMap<ResourceLocation, VariantList<Config.ColorEmitter>> entityEmitters = new HashMap<>();
        HashMap<ResourceLocation, VariantList<Config.ColorEmitter>> itemEmitters = new HashMap<>();
        Map<Integer, Config.ColorMoonPhase> moonPhases = new HashMap<>();

        loadBuiltInLightConfigs(emitters, filters, absorbers, entityEmitters, itemEmitters, moonPhases);

        // Enumerate existing light configs in a single pass instead of probing every
        // pack × namespace with getResourceStack: that probes the filesystem for six
        // (almost always missing) files per namespace and re-parses stacks shared by
        // packs declaring the same namespace, which adds 10+ seconds per reload in
        // large modpacks.
        Map<ResourceLocation, List<Resource>> lightConfigs =
                resourceManager.listResourceStacks("light", location -> CONFIG_PATHS.contains(location.getPath()));
        lightConfigs.forEach((location, stack) -> {
            for (Resource resource : stack) {
                try {
                    JsonObject object = GSON.fromJson(resource.openAsReader(), JsonObject.class);
                    if (object == null) continue;
                    switch (location.getPath()) {
                        case EMITTERS_PATH -> processEmitterEntries(object, resource.sourcePackId(), emitters);
                        case FILTERS_PATH -> processFilterEntries(object, resource.sourcePackId(), filters);
                        case ABSORBERS_PATH -> processAbsorberEntries(object, resource.sourcePackId(), absorbers);
                        case ENTITIES_PATH -> processEntityEntries(object, resource.sourcePackId(), entityEmitters);
                        case ITEMS_PATH -> processItemEntries(object, resource.sourcePackId(), itemEmitters);
                        case MOON_PHASES_PATH -> processMoonPhaseEntries(object, resource.sourcePackId(), moonPhases);
                    }
                }
                catch (Exception e) {
                    LOGGER.warn("Failed to load light config {} from pack {}", location, resource.sourcePackId(), e);
                }
            }
        });

        Config.setColorEmitters(emitters);
        Config.setColorFilters(filters);
        Config.setColorAbsorbers(absorbers);
        Config.setEntityEmitters(entityEmitters);
        Config.setItemEmitters(itemEmitters);
        Config.setMoonPhases(moonPhases);

        // which block entities need NBT snapshots depends on the configs that just changed, and the
        // snapshots must be in place before the engine starts re-propagating light below
        var level = ColorfulLighting.clientAccessor.getLevel();
        var player = ColorfulLighting.clientAccessor.getPlayer();
        if (level != null && player != null) {
            ChunkPos playerChunk = player.getChunkPos();
            BlockEntityNbtCache.reload(level.getLevel(), playerChunk.x, playerChunk.z,
                    ColorfulLighting.clientAccessor.getRenderDistance());
        } else {
            BlockEntityNbtCache.clear();
        }

        if(level != null)
            ColoredLightEngine.getInstance().reset();
    }

    private static void loadBuiltInLightConfigs(HashMap<ResourceLocation, VariantList<Config.ColorEmitter>> emitters,
                                                 HashMap<ResourceLocation, VariantList<Config.ColorFilter>> filters,
                                                 HashMap<ResourceLocation, VariantList<Config.ColorEmitter>> absorbers,
                                                 HashMap<ResourceLocation, VariantList<Config.ColorEmitter>> entityEmitters,
                                                 HashMap<ResourceLocation, VariantList<Config.ColorEmitter>> itemEmitters,
                                                 Map<Integer, Config.ColorMoonPhase> moonPhases) {
        JsonObject emitterObject = loadBuiltInLightJson("emitters.json");
        if (emitterObject != null) {
            processEmitterEntries(emitterObject, ColorfulLighting.BUILT_IN_RESOURCE_PACK_ID, emitters);
        }

        JsonObject filterObject = loadBuiltInLightJson("filters.json");
        if (filterObject != null) {
            processFilterEntries(filterObject, ColorfulLighting.BUILT_IN_RESOURCE_PACK_ID, filters);
        }

        JsonObject absorberObject = loadBuiltInLightJson("absorbers.json");
        if (absorberObject != null) {
            processAbsorberEntries(absorberObject, ColorfulLighting.BUILT_IN_RESOURCE_PACK_ID, absorbers);
        }

        JsonObject entityObject = loadBuiltInLightJson("entities.json");
        if (entityObject != null) {
            processEntityEntries(entityObject, ColorfulLighting.BUILT_IN_RESOURCE_PACK_ID, entityEmitters);
        }

        JsonObject itemObject = loadBuiltInLightJson("items.json");
        if (itemObject != null) {
            processItemEntries(itemObject, ColorfulLighting.BUILT_IN_RESOURCE_PACK_ID, itemEmitters);
        }

        JsonObject moonPhasesObject = loadBuiltInLightJson("moon_phases.json");
        if (moonPhasesObject != null) {
            processMoonPhaseEntries(moonPhasesObject, ColorfulLighting.BUILT_IN_RESOURCE_PACK_ID, moonPhases);
        }
    }

    private static void processEmitterEntries(JsonObject object, String sourcePackId, HashMap<ResourceLocation, VariantList<Config.ColorEmitter>> emitters) {
        for (var entry : object.entrySet()) {
            try {
                var key = ResourceLocation.tryParse(entry.getKey());
                if(!BuiltInRegistries.BLOCK.containsKey(key)) throw new IllegalArgumentException("Couldn't find block "+key);
                emitters.put(key, VariantList.fromJsonElement(entry.getValue(), Config.ColorEmitter::fromJsonElement));
            }
            catch (Exception e) {
                LOGGER.warn("Failed to load light emitter entry {} from pack {}", entry.toString(), sourcePackId, e);
            }
        }
    }

    private static void processFilterEntries(JsonObject object, String sourcePackId, HashMap<ResourceLocation, VariantList<Config.ColorFilter>> filters) {
        for (var entry : object.entrySet()) {
            try {
                var key = ResourceLocation.tryParse(entry.getKey());
                if(!BuiltInRegistries.BLOCK.containsKey(key)) throw new IllegalArgumentException("Couldn't find block "+key);
                filters.put(key, VariantList.fromJsonElement(entry.getValue(), Config.ColorFilter::fromJsonElement));
            }
            catch (Exception e) {
                LOGGER.warn("Failed to load light color filter entry {} from pack {}", entry.toString(), sourcePackId, e);
            }
        }
    }

    private static void processAbsorberEntries(JsonObject object, String sourcePackId, HashMap<ResourceLocation, VariantList<Config.ColorEmitter>> absorbers) {
        for (var entry : object.entrySet()) {
            try {
                var key = ResourceLocation.tryParse(entry.getKey());
                if(!BuiltInRegistries.BLOCK.containsKey(key)) throw new IllegalArgumentException("Couldn't find block "+key);
                absorbers.put(key, VariantList.fromJsonElement(entry.getValue(), Config.ColorEmitter::fromJsonElement));
            }
            catch (Exception e) {
                LOGGER.warn("Failed to load light color absorber entry {} from pack {}", entry.toString(), sourcePackId, e);
            }
        }
    }

    private static void processEntityEntries(JsonObject object, String sourcePackId, HashMap<ResourceLocation, VariantList<Config.ColorEmitter>> entityEmitters) {
        for (var entry : object.entrySet()) {
            try {
                var key = ResourceLocation.tryParse(entry.getKey());
                if(!BuiltInRegistries.ENTITY_TYPE.containsKey(key)) throw new IllegalArgumentException("Couldn't find entity type "+key);
                entityEmitters.put(key, VariantList.fromJsonElement(entry.getValue(), Config.ColorEmitter::fromJsonElement));
            }
            catch (Exception e) {
                LOGGER.warn("Failed to load light entity entry {} from pack {}", entry.toString(), sourcePackId, e);
            }
        }
    }

    private static void processItemEntries(JsonObject object, String sourcePackId, HashMap<ResourceLocation, VariantList<Config.ColorEmitter>> itemEmitters) {
        for (var entry : object.entrySet()) {
            try {
                var key = ResourceLocation.tryParse(entry.getKey());
                if(!BuiltInRegistries.ITEM.containsKey(key)) throw new IllegalArgumentException("Couldn't find item "+key);
                itemEmitters.put(key, VariantList.fromJsonElement(entry.getValue(), Config.ColorEmitter::fromJsonElement));
            }
            catch (Exception e) {
                LOGGER.warn("Failed to load light item entry {} from pack {}", entry.toString(), sourcePackId, e);
            }
        }
    }

    private static void processMoonPhaseEntries(JsonObject object, String sourcePackId, Map<Integer, Config.ColorMoonPhase> moonPhases) {
        for (var entry : object.entrySet()) {
            try {
                int phase = Integer.parseInt(entry.getKey());
                if (phase < 0 || phase > 7) throw new IllegalArgumentException("Moon phase must be between 0 and 7.");
                moonPhases.put(phase, Config.ColorMoonPhase.fromJsonElement(entry.getValue()));
            }
            catch (Exception e) {
                LOGGER.warn("Failed to load moon phase entry {} from pack {}", entry.toString(), sourcePackId, e);
            }
        }
    }

    private static JsonObject loadBuiltInLightJson(String fileName) {
        String resourcePath = BUILT_IN_LIGHT_RESOURCE_PATH + "/" + fileName;
        try (InputStream stream = ColorfulLighting.class.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                LOGGER.warn("Built-in light resource {} not found", resourcePath);
                return null;
            }
            try (InputStreamReader reader = new InputStreamReader(stream)) {
                JsonObject object = GSON.fromJson(reader, JsonObject.class);
                if (object == null) {
                    LOGGER.warn("Built-in light resource {} did not contain a JSON object", resourcePath);
                }
                return object;
            }
        }
        catch (Exception e) {
            LOGGER.warn("Failed to load built-in light resource {}", resourcePath, e);
            return null;
        }
    }
}
