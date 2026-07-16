package me.erykczy.colorfullighting.resourcemanager;

import com.mojang.datafixers.util.Pair;
import me.erykczy.colorfullighting.ColorfulLighting;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.forgespi.language.IModFileInfo;
import net.minecraftforge.forgespi.language.IModInfo;

import java.awt.*;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.List;
import java.util.stream.Stream;

public class CoreShaderRegistration {
    private static final String BUILT_IN_PACK_FOLDER = "resourcepacks";
    private static Path cachedPackPath;
    private static final List<Pair<ResourceLocation, Component>> packs = new ArrayList<>();
	private static final List<InternalPack> registerPacks = new ArrayList<>();

    public static void register(IEventBus bus) {
		registerPacks.add(makePack(
				ResourceLocation.parse("colorful_lighting:colorful_lighting_assets"),
				Component.literal("Colorful Lighting Main Assets"),
				Pack.Position.BOTTOM
		));
		registerPacks.add(makePack(
				ResourceLocation.parse("colorful_lighting:colorful_lighting_core_shaders"),
				Component.literal("Colorful Lighting Core Shaders")
		));
		if (ModList.get().isLoaded("embeddium")) {
			registerPacks.add(makePack(
					ResourceLocation.parse("colorful_lighting:colorful_lighting_embeddium_shaders"),
					Component.literal("Colorful Lighting Sodium Shaders")
			));
		} else if (ModList.get().isLoaded("rubidium")) {
			registerPacks.add(makePack(
					ResourceLocation.parse("colorful_lighting:colorful_lighting_rubidium_shaders"),
					Component.literal("Colorful Lighting Rubidium Shaders")
			));
		} else if (ModList.get().isLoaded("sodium")) {
			registerPacks.add(makePack(
					ResourceLocation.parse("colorful_lighting:colorful_lighting_sodium_shaders"),
					Component.literal("Colorful Lighting Sodium Shaders")
			));
		}
		
        bus.addListener(EventPriority.LOWEST, CoreShaderRegistration::addPackFinders);
    }
	
	private static InternalPack makePack(ResourceLocation id, MutableComponent displayName) {
		IModFileInfo info = getPackInfo(id);
		// Important: findResource(String... path) expects path *segments*, not a single "a/b/c" string.
		// Passing a single string can produce a non-existent path inside the mod jar, making the pack empty.
		Path resourcePath = info.getFile().findResource(BUILT_IN_PACK_FOLDER, id.getPath());
		
		return new InternalPack(
				id, displayName,
				info, resourcePath
		);
	}
	
	private static InternalPack makePack(ResourceLocation id, MutableComponent displayName, Pack.Position position) {
		IModFileInfo info = getPackInfo(id);
		// Important: findResource(String... path) expects path *segments*, not a single "a/b/c" string.
		// Passing a single string can produce a non-existent path inside the mod jar, making the pack empty.
		Path resourcePath = info.getFile().findResource(BUILT_IN_PACK_FOLDER, id.getPath());
		
		return new InternalPack(
				id, displayName,
				info, resourcePath,
				position
		);
	}
	
	public static void addPackFinders(AddPackFindersEvent event) {
        if (event.getPackType() == PackType.CLIENT_RESOURCES) {
	        for (InternalPack registerPack : registerPacks) {
		        if (!Files.exists(registerPack.resourcePath)) {
			        ColorfulLighting.LOGGER.error(
					        "Built-in core shader pack root not found at {} (mod file: {}). The pack will not load.",
					        registerPack.resourcePath, registerPack.info.getFile().getFilePath()
			        );
			        return;
		        }
		        
		        final Pack.Info packInfo = createInfoForLatest(registerPack.displayName, false);
		        final Pack pack = Pack.create(
				        registerPack.addID, registerPack.displayName,
				        false,
				        (path) -> new PathPackResources(path, registerPack.resourcePath, true),
				        packInfo, PackType.CLIENT_RESOURCES, registerPack.position, true, PackSource.BUILT_IN);
		        event.addRepositorySource((packConsumer) ->
				        packConsumer.accept(pack));
	        }
			
	        seedInitialPackSelection();
        }
    }

    /**
     * Pre-selects the core shader pack in the options' saved pack list so the initial resource
     * load already includes it. This event fires from ClientModLoader.begin, after Options is
     * constructed but before Minecraft calls options.loadSelectedResourcePacks, so the id seeded
     * here is picked up by the very first resource load and updateShaderPack() has nothing to
     * reload at the title screen.
     * The pack is selected unconditionally (even when the engine is disabled): the patched
     * shaders must always be active to decode chunk meshes baked in the colored vertex format;
     * they render vanilla lighting when the engine is off via u_ColoredLightingEnabled.
     * Note: fixed-position packs are never written back by Options.updateResourcePacks, so this
     * has to run every boot; it cannot rely on a previous session having persisted the selection.
     */
    private static void seedInitialPackSelection() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.options == null) return;
        List<String> selectedIds = minecraft.options.resourcePacks;
	    for (InternalPack registerPack : registerPacks) {
		    if (!selectedIds.contains(registerPack.addID)) {
			    selectedIds.add(registerPack.addID);
		    }
	    }
    }

    private static Pack.Info createInfoForLatest(Component description, boolean hidden) {
        return new Pack.Info(
                description,
                SharedConstants.getCurrentVersion().getPackVersion(PackType.SERVER_DATA),
                SharedConstants.getCurrentVersion().getPackVersion(PackType.CLIENT_RESOURCES),
                FeatureFlagSet.of(),
                hidden
        );
    }

    private static boolean fileExists(IModInfo info, String path) {
        return Files.exists(info.getOwningFile().getFile().findResource(path.split("/")));
    }

    private static IModFileInfo getPackInfo(ResourceLocation pack) {
        if (!FMLLoader.isProduction()) {
            for (IModInfo mod : ModList.get().getMods()) {
                if (mod.getModId().startsWith("generated_") && fileExists(mod, "resourcepacks/" + pack.getPath())) {
                    return mod.getOwningFile();
                }
            }
        }
        return ModList.get().getModFileById(pack.getNamespace());
    }
	
	public static void enforcePacks(Minecraft mc, PackRepository repo) {
		boolean changed = false;
		for (InternalPack registerPack : registerPacks) {
			if (repo.getPack(registerPack.addID) != null && !repo.getSelectedIds().contains(registerPack.addID)) {
				repo.addPack(registerPack.addID);
				changed = true;
			}
		}
		if (changed)
			mc.reloadResourcePacks();
	}
}
