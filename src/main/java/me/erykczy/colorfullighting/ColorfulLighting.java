package me.erykczy.colorfullighting;

import com.mojang.logging.LogUtils;
import me.erykczy.colorfullighting.accessors.MinecraftWrapper;
import me.erykczy.colorfullighting.common.ColoredLightEngine;
import me.erykczy.colorfullighting.common.ColorfulLightingConfig;
import me.erykczy.colorfullighting.common.accessors.ClientAccessor;
import me.erykczy.colorfullighting.compat.dynamiclights.DynamicLightsCompat;
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
    public static final String CORE_SHADER_PACK_ID = MOD_ID + ":add_pack/" + BUILT_IN_RESOURCE_PACK_ID;
    public static final String BUILT_IN_LIGHT_RESOURCE_PATH = "/resourcepacks/" + BUILT_IN_RESOURCE_PACK_ID + "/assets/colorful_lighting/light";
    public static final Logger LOGGER = LogUtils.getLogger();
    public static ClientAccessor clientAccessor;

    public ColorfulLighting(FMLJavaModLoadingContext context)
    {
        context.registerConfig(net.minecraftforge.fml.config.ModConfig.Type.CLIENT, ColorfulLightingConfig.SPEC);

        // The texture-mode force exists to debug the flywheel buffer-texture path, so make every
        // such session self-documenting: flywheel dumps each assembled shader to
        // <gameDir>/flywheel_sources/. Set here because this runs before flywheel's Compilation
        // class loads (it reads the property once in its static initializer).
        if (me.erykczy.colorfullighting.common.ColorfulLightingConfig.flywheelForceTextureMode()) {
            System.setProperty("flw.dumpShaderSource", "true");
            LOGGER.info("flywheelForceTextureMode: enabling flywheel shader source dumps (flywheel_sources/)");
        }

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
        ColoredLightEngine.setEnabled(ColorfulLightingConfig.ENABLED.get());
    }

    public static void onLoadingComplete(FMLLoadCompleteEvent event) {
        ColoredLightEngine.onPacksInitialized();
        if (ModList.get().isLoaded("rubidium") || ModList.get().isLoaded("embeddium") || ModList.get().isLoaded("sodium")) {
            LOGGER.info("Sodium/Embeddium detected!");
        }
        if (ModList.get().isLoaded("oculus") || ModList.get().isLoaded("iris")) {
            OculusCompat.init();
            LOGGER.info("Iris/Oculus detected!");
            me.erykczy.colorfullighting.compat.oculus.ShaderpackAutoPatcher.runOnStartup();
        }
        if(ModList.get().isLoaded("flywheel")) {
            FlywheelCompat.init();
            LOGGER.info("Flywheel detected!");
        }
        if(ModList.get().isLoaded("create")) {
            CreateCompat.init();
            LOGGER.info("Create detected!");
        }
        if(ModList.get().isLoaded("valkyrienskies")) {
            me.erykczy.colorfullighting.compat.valkyrienskies.VsCompat.init();
            LOGGER.info("Valkyrien Skies detected!");
        }
        DynamicLightsCompat.init();
    }
}
