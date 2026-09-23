package net.camacraft.colorfullighting;

import com.mojang.logging.LogUtils;
import net.camacraft.colorfullighting.accessors.MinecraftWrapper;
import net.camacraft.colorfullighting.common.ColoredLightEngine;
import net.camacraft.colorfullighting.common.ColorfulLightingConfig;
import net.camacraft.colorfullighting.common.accessors.ClientAccessor;
import net.camacraft.colorfullighting.compat.dynamiclights.DynamicLightsCompat;
import net.camacraft.colorfullighting.compat.oculus.OculusCompat;
import net.camacraft.colorfullighting.event.ClientEventListener;
import net.camacraft.colorfullighting.compat.create.CreateCompat;
import net.camacraft.colorfullighting.compat.flywheel.FlywheelCompat;
import net.camacraft.colorfullighting.compat.oculus.ShaderpackAutoPatcher;
import net.camacraft.colorfullighting.resourcemanager.InternalPackRegistration;
import net.camacraft.colorfullighting.resourcemanager.ModResourceManagers;
import net.camacraft.colorfullighting.compat.distanthorizons.DhCompat;
import net.camacraft.colorfullighting.compat.truedarkness.TrueDarknessCompat;
import net.camacraft.colorfullighting.compat.valkyrienskies.VsCompat;
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
    public static final String BUILT_IN_RESOURCE_PACK_ID = "colorful_lighting_assets";
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
        if (ColorfulLightingConfig.flywheelForceTextureMode()) {
            System.setProperty("flw.dumpShaderSource", "true");
            LOGGER.info("flywheelForceTextureMode: enabling flywheel shader source dumps (flywheel_sources/)");
        }

        DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> new DistExecutor.SafeRunnable() {
            @Override
            public void run() {
                ModResourceManagers.register(context.getModEventBus());
                InternalPackRegistration.register(context.getModEventBus());
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
            ShaderpackAutoPatcher.runOnStartup();
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
            VsCompat.init();
            LOGGER.info("Valkyrien Skies detected!");
        }
        if (ModList.get().isLoaded("immersive_portals") || ModList.get().isLoaded("imm_ptl_core")) {
            // The remote-level light regions themselves are always on (they are inert without a
            // second client level); this is just detection logging.
            LOGGER.info("Immersive Portals detected! Colored light will follow dimensions seen through portals.");
        }
        if (ModList.get().isLoaded("darkness")) {
            // The LightTexture mixin does the work; this just confirms the hook resolved.
            LOGGER.info("True Darkness detected! Its lightmap darkening will follow the dimension being rendered (Immersive Portals views){}",
                    TrueDarknessCompat.isAvailable() ? "." : " - but its Darkness class could not be hooked, see the warning above.");
        }
        if (ModList.get().isLoaded("nvidium") || ModList.get().isLoaded("acedium")) {
            LOGGER.info("Nvidium/Acedium detected! Terrain light will be converted in their vertex encoder.");
        }
        if(ModList.get().isLoaded("distanthorizons")) {
            DhCompat.init();
            LOGGER.info("Distant horizons detected!");
        }
        DynamicLightsCompat.init();
    }
}
