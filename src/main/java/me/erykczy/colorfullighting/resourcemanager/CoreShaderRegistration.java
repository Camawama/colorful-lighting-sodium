package me.erykczy.colorfullighting.resourcemanager;

import com.mojang.datafixers.util.Pair;
import me.erykczy.colorfullighting.ColorfulLighting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.event.AddPackFindersEvent;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.language.IModInfo;

import java.util.Optional;

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

    public static void register(IEventBus bus) {
        bus.addListener(EventPriority.LOWEST, CoreShaderRegistration::addPackFinders);
    }

    public static void addPackFinders(AddPackFindersEvent event) {
        if (event.getPackType() == PackType.CLIENT_RESOURCES) {
            var id = ResourceLocation.parse("colorful_lighting:colorful_lighting_core_shaders");
            var displayName = Component.literal("Colorful Lighting Core Shaders");

            IModFileInfo info = getPackInfo(id);
            // Important: findResource(String... path) expects path *segments*, not a single "a/b/c" string.
            // Passing a single string can produce a non-existent path inside the mod jar, making the pack empty.
            Path resourcePath = info.getFile().findResource(BUILT_IN_PACK_FOLDER, id.getPath());
            if (!Files.exists(resourcePath)) {
                ColorfulLighting.LOGGER.error(
                        "Built-in core shader pack root not found at {} (mod file: {}). The pack will not load.",
                        resourcePath, info.getFile().getFilePath()
                );
                return;
            }

            PackLocationInfo locationInfo = new PackLocationInfo(
                    id.toString(),
                    displayName,
                    PackSource.BUILT_IN,
                    Optional.empty()
            );

            Pack.ResourcesSupplier supplier = new Pack.ResourcesSupplier() {
                @Override
                public PackResources openPrimary(PackLocationInfo location) {
                    return new PathPackResources(location, resourcePath);
                }

                @Override
                public PackResources openFull(PackLocationInfo location, Pack.Metadata metadata) {
                    return new PathPackResources(location, resourcePath);
                }
            };

            Pack pack = Pack.readMetaAndCreate(
                    locationInfo,
                    supplier,
                    PackType.CLIENT_RESOURCES,
                    new PackSelectionConfig(true, Pack.Position.TOP, true)
            );

            if (pack != null) {
                event.addRepositorySource(packConsumer -> packConsumer.accept(pack));
            }
        }
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

    private static Path locateResourcePack() {
        if (cachedPackPath != null) {
            return cachedPackPath;
        }

        try {
            Path modFilePath = ModList.get().getModFileById(ColorfulLighting.MOD_ID).getFile().getFilePath();
            cachedPackPath = resolvePackPath(modFilePath);
            return cachedPackPath;
        } catch (Exception e) {
            ColorfulLighting.LOGGER.error("Failed to register core shader resource pack", e);
            return null;
        }
    }

    private static Path resolvePackPath(Path modFilePath) throws IOException {
        if (Files.isDirectory(modFilePath)) {
            Path packDir = modFilePath.resolve(BUILT_IN_PACK_FOLDER).resolve(ColorfulLighting.BUILT_IN_RESOURCE_PACK_ID);
            if (!Files.exists(packDir)) {
                throw new IOException("Built-in resource pack " + ColorfulLighting.BUILT_IN_RESOURCE_PACK_ID + " missing in " + packDir);
            }
            return packDir;
        }

        return extractPackFromJar(modFilePath);
    }

    private static Path extractPackFromJar(Path jarPath) throws IOException {
        Path unpackDir = Files.createTempDirectory("colorful_lighting_core_shaders_pack");
        unpackDir.toFile().deleteOnExit();
        Path packRoot = unpackDir.resolve(ColorfulLighting.BUILT_IN_RESOURCE_PACK_ID);
        Files.createDirectories(packRoot);

        URI jarUri = URI.create("jar:" + jarPath.toUri());
        try (FileSystem fileSystem = FileSystems.newFileSystem(jarUri, Map.of())) {
            Path jarPackRoot = fileSystem.getPath(BUILT_IN_PACK_FOLDER, ColorfulLighting.BUILT_IN_RESOURCE_PACK_ID);
            if (!Files.exists(jarPackRoot)) {
                throw new IOException("Built-in resource pack " + jarPackRoot + " missing inside mod jar");
            }
            copyDirectory(jarPackRoot, packRoot);
        }

        return packRoot;
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        try (Stream<Path> stream = Files.walk(source)) {
            Iterator<Path> iterator = stream.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                Path relative = source.relativize(path);
                Path destination = target.resolve(relative.toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Path parent = destination.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
