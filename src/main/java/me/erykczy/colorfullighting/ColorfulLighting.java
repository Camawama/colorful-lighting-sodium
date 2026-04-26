package me.erykczy.colorfullighting;

import com.mojang.logging.LogUtils;
import me.erykczy.colorfullighting.accessors.MinecraftWrapper;
import me.erykczy.colorfullighting.common.ColoredLightEngine;
import me.erykczy.colorfullighting.common.accessors.ClientAccessor;
import me.erykczy.colorfullighting.compat.oculus.OculusCompat;
import me.erykczy.colorfullighting.event.ClientEventListener;
import me.erykczy.colorfullighting.compat.create.CreateCompat;
import me.erykczy.colorfullighting.compat.flywheel.FlywheelCompat;
import me.erykczy.colorfullighting.resourcemanager.CoreShaderRegistration;
import me.erykczy.colorfullighting.resourcemanager.ModResourceManagers;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(value = ColorfulLighting.MOD_ID)
public class ColorfulLighting
{
    public static final String MOD_ID = "colorful_lighting";
    public static final String BUILT_IN_RESOURCE_PACK_ID = "colorful_lighting_core_shaders";
    public static final String BUILT_IN_LIGHT_RESOURCE_PATH = "/resourcepacks/" + BUILT_IN_RESOURCE_PACK_ID + "/assets/colorful_lighting/light";
    public static final Logger LOGGER = LogUtils.getLogger();
    public static ClientAccessor clientAccessor;

    public ColorfulLighting(FMLJavaModLoadingContext context)
    {
        DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> new DistExecutor.SafeRunnable() {
            @Override
            public void run() {
                ModResourceManagers.register(context.getModEventBus());
                CoreShaderRegistration.register(context.getModEventBus());
                MinecraftForge.EVENT_BUS.register(new ClientEventListener());
                context.getModEventBus().addListener(ColorfulLighting::onClientSetup);
                context.getModEventBus().addListener(ColorfulLighting::onLoadingComplete);
            }
        });
    }

    public static void onClientSetup(FMLClientSetupEvent event) {
        clientAccessor = new MinecraftWrapper(Minecraft.getInstance());
        ColoredLightEngine.create(clientAccessor);
    }

    public static void onLoadingComplete(FMLLoadCompleteEvent event) {
        if (ModList.get().isLoaded("rubidium") || ModList.get().isLoaded("embeddium") || ModList.get().isLoaded("sodium")) {
            LOGGER.info("Sodium/Embeddium detected!");
        }
        if (ModList.get().isLoaded("oculus") || ModList.get().isLoaded("iris")) {
            OculusCompat.init();
            LOGGER.info("Iris/Oculus detected!");
        }
        if(ModList.get().isLoaded("flywheel")) {
            FlywheelCompat.init();
            LOGGER.info("Flywheel detected!");
        }
        if(ModList.get().isLoaded("create")) {
            CreateCompat.init();
            LOGGER.info("Create detected!");
        }
    }
}
