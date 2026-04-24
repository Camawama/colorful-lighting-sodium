package me.erykczy.colorfullighting.resourcemanager;

import me.erykczy.colorfullighting.ColorfulLighting;
import me.erykczy.colorfullighting.common.ColoredLightEngine;
import me.erykczy.colorfullighting.common.Config;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.ToNumberPolicy;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import org.slf4j.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

public class ConfigResourceManager implements ResourceManagerReloadListener {
    private static final Gson GSON = new GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create();
    private static final Logger LOGGER = ColorfulLighting.LOGGER;
    private static final String BUILT_IN_LIGHT_RESOURCE_PATH = ColorfulLighting.BUILT_IN_LIGHT_RESOURCE_PATH;

    @Override
    public void onResourceManagerReload(ResourceManager resourceManager) {
        HashMap<ResourceLocation, Config.BlockEmitterConfig> emitters = new HashMap<>();
        HashMap<ResourceLocation, Config.BlockFilterConfig> filters = new HashMap<>();
        HashMap<ResourceLocation, Config.BlockAbsorberConfig> absorbers = new HashMap<>();
        HashMap<ResourceLocation, Config.ColorEmitter> entityEmitters = new HashMap<>();
        HashMap<ResourceLocation, Config.ColorEmitter> itemEmitters = new HashMap<>();
        Map<Integer, Config.ColorMoonPhase> moonPhases = new HashMap<>();

        loadBuiltInLightConfigs(emitters, filters, absorbers, entityEmitters, itemEmitters, moonPhases);

        resourceManager.listPacks().forEach((pack) -> {
            for(String namespace : pack.getNamespaces(PackType.CLIENT_RESOURCES)) {
                for(Resource resource : resourceManager.getResourceStack(ResourceLocation.tryBuild(namespace, "light/emitters.json"))) {
                    try {
                        JsonObject object = GSON.fromJson(resource.openAsReader(), JsonObject.class);
                        if (object != null) {
                            processEmitterEntries(object, resource.sourcePackId(), emitters);
                        }
                    }
                    catch (Exception e) {
                        LOGGER.warn("Failed to load light emitters from pack {}", resource.sourcePackId(), e);
                    }
                }

                for(Resource resource : resourceManager.getResourceStack(ResourceLocation.tryBuild(namespace, "light/filters.json"))) {
                    try {
                        JsonObject object = GSON.fromJson(resource.openAsReader(), JsonObject.class);
                        if (object != null) {
                            processFilterEntries(object, resource.sourcePackId(), filters);
                        }
                    }
                    catch (Exception e) {
                        LOGGER.warn("Failed to load light color filters from pack {}", resource.sourcePackId(), e);
                    }
                }

                for(Resource resource : resourceManager.getResourceStack(ResourceLocation.tryBuild(namespace, "light/absorbers.json"))) {
                    try {
                        JsonObject object = GSON.fromJson(resource.openAsReader(), JsonObject.class);
                        if (object != null) {
                            processAbsorberEntries(object, resource.sourcePackId(), absorbers);
                        }
                    }
                    catch (Exception e) {
                        LOGGER.warn("Failed to load light color absorbers from pack {}", resource.sourcePackId(), e);
                    }
                }

                for(Resource resource : resourceManager.getResourceStack(ResourceLocation.tryBuild(namespace, "light/entities.json"))) {
                    try {
                        JsonObject object = GSON.fromJson(resource.openAsReader(), JsonObject.class);
                        if (object != null) {
                            processEntityEntries(object, resource.sourcePackId(), entityEmitters);
                        }
                    }
                    catch (Exception e) {
                        LOGGER.warn("Failed to load light entities from pack {}", resource.sourcePackId(), e);
                    }
                }

                for(Resource resource : resourceManager.getResourceStack(ResourceLocation.tryBuild(namespace, "light/items.json"))) {
                    try {
                        JsonObject object = GSON.fromJson(resource.openAsReader(), JsonObject.class);
                        if (object != null) {
                            processItemEntries(object, resource.sourcePackId(), itemEmitters);
                        }
                    }
                    catch (Exception e) {
                        LOGGER.warn("Failed to load light items from pack {}", resource.sourcePackId(), e);
                    }
                }

                for(Resource resource : resourceManager.getResourceStack(ResourceLocation.tryBuild(namespace, "light/moon_phases.json"))) {
                    try {
                        JsonObject object = GSON.fromJson(resource.openAsReader(), JsonObject.class);
                        if (object != null) {
                            processMoonPhaseEntries(object, resource.sourcePackId(), moonPhases);
                        }
                    }
                    catch (Exception e) {
                        LOGGER.warn("Failed to load moon phases from pack {}", resource.sourcePackId(), e);
                    }
                }
            }
        });

        Config.setColorEmitters(emitters);
        Config.setColorFilters(filters);
        Config.setColorAbsorbers(absorbers);
        Config.setEntityEmitters(entityEmitters);
        Config.setItemEmitters(itemEmitters);
        Config.setMoonPhases(moonPhases);
        if(ColorfulLighting.clientAccessor.getLevel() != null)
            ColoredLightEngine.getInstance().reset();
    }

    private static void loadBuiltInLightConfigs(HashMap<ResourceLocation, Config.BlockEmitterConfig> emitters,
                                                 HashMap<ResourceLocation, Config.BlockFilterConfig> filters,
                                                 HashMap<ResourceLocation, Config.BlockAbsorberConfig> absorbers,
                                                 HashMap<ResourceLocation, Config.ColorEmitter> entityEmitters,
                                                 HashMap<ResourceLocation, Config.ColorEmitter> itemEmitters,
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

    private static void processEmitterEntries(JsonObject object, String sourcePackId, HashMap<ResourceLocation, Config.BlockEmitterConfig> emitters) {
        for (var entry : object.entrySet()) {
            try {
                var key = ResourceLocation.tryParse(entry.getKey());
                if(!BuiltInRegistries.BLOCK.containsKey(key)) throw new IllegalArgumentException("Couldn't find block "+key);
                emitters.put(key, Config.BlockEmitterConfig.fromJsonElement(entry.getValue()));
            }
            catch (Exception e) {
                LOGGER.warn("Failed to load light emitter entry {} from pack {}", entry.toString(), sourcePackId, e);
            }
        }
    }

    private static void processFilterEntries(JsonObject object, String sourcePackId, HashMap<ResourceLocation, Config.BlockFilterConfig> filters) {
        for (var entry : object.entrySet()) {
            try {
                var key = ResourceLocation.tryParse(entry.getKey());
                if(!BuiltInRegistries.BLOCK.containsKey(key)) throw new IllegalArgumentException("Couldn't find block "+key);
                filters.put(key, Config.BlockFilterConfig.fromJsonElement(entry.getValue()));
            }
            catch (Exception e) {
                LOGGER.warn("Failed to load light color filter entry {} from pack {}", entry.toString(), sourcePackId, e);
            }
        }
    }

    private static void processAbsorberEntries(JsonObject object, String sourcePackId, HashMap<ResourceLocation, Config.BlockAbsorberConfig> absorbers) {
        for (var entry : object.entrySet()) {
            try {
                var key = ResourceLocation.tryParse(entry.getKey());
                if(!BuiltInRegistries.BLOCK.containsKey(key)) throw new IllegalArgumentException("Couldn't find block "+key);
                absorbers.put(key, Config.BlockAbsorberConfig.fromJsonElement(entry.getValue()));
            }
            catch (Exception e) {
                LOGGER.warn("Failed to load light color absorber entry {} from pack {}", entry.toString(), sourcePackId, e);
            }
        }
    }

    private static void processEntityEntries(JsonObject object, String sourcePackId, HashMap<ResourceLocation, Config.ColorEmitter> entityEmitters) {
        for (var entry : object.entrySet()) {
            try {
                var key = ResourceLocation.tryParse(entry.getKey());
                if(!BuiltInRegistries.ENTITY_TYPE.containsKey(key)) throw new IllegalArgumentException("Couldn't find entity type "+key);
                entityEmitters.put(key, Config.ColorEmitter.fromJsonElement(entry.getValue()));
            }
            catch (Exception e) {
                LOGGER.warn("Failed to load light entity entry {} from pack {}", entry.toString(), sourcePackId, e);
            }
        }
    }

    private static void processItemEntries(JsonObject object, String sourcePackId, HashMap<ResourceLocation, Config.ColorEmitter> itemEmitters) {
        for (var entry : object.entrySet()) {
            try {
                var key = ResourceLocation.tryParse(entry.getKey());
                if(!BuiltInRegistries.ITEM.containsKey(key)) throw new IllegalArgumentException("Couldn't find item "+key);
                itemEmitters.put(key, Config.ColorEmitter.fromJsonElement(entry.getValue()));
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
