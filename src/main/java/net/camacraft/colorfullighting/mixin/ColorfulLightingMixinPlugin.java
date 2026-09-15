package net.camacraft.colorfullighting.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ColorfulLightingMixinPlugin implements IMixinConfigPlugin {
    // shouldApplyMixin runs once per mixin/target pair; cache the probes so a missing
    // mod doesn't trigger repeated full-classpath resource lookups in large modpacks
    private final Map<String, Boolean> classExistsCache = new HashMap<>();

    private boolean hasClass(String className) {
        return classExistsCache.computeIfAbsent(className, name -> {
            try (InputStream is = this.getClass().getClassLoader().getResourceAsStream(name.replace('.', '/') + ".class")) {
                return is != null;
            } catch (Exception e) {
                return false;
            }
        });
    }

    @Override
    public void onLoad(String mixinPackage) {
        // Runs during mixin bootstrap, before any flywheel class can load. Flywheel's Compilation
        // class reads flw.dumpShaderSource exactly once in its static initializer, and flywheel's
        // GL probing (which pulls that class in) runs before our mod constructor — setting the
        // property there was 144ms too late. No Forge/MC classes may be touched here, so the
        // config is read as a plain file; the game's working directory is the game dir.
        try {
            java.nio.file.Path config = java.nio.file.Paths.get("config", "colorful_lighting-client.toml");
            if (java.nio.file.Files.exists(config)) {
                for (String line : java.nio.file.Files.readAllLines(config)) {
                    if (line.trim().replace(" ", "").startsWith("flywheelForceTextureMode=true")) {
                        System.setProperty("flw.dumpShaderSource", "true");
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
            // no config yet (first launch) or unreadable: no dumps, nothing else affected
        }
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.contains(".flywheel.")) {
            // Ensure Flywheel 1.0+ is installed before applying flywheel mixins
            return hasClass("dev.engine_room.flywheel.backend.engine.LightStorage");
        }
        if (mixinClassName.contains(".create.")) {
            // Ensure Create 0.6+ is installed before applying create mixins
            return hasClass("net.createmod.catnip.render.ShadeSeparatingSuperByteBuffer");
        }
        if (mixinClassName.contains(".flerovium.")) {
            // Ensure Flerovium is installed before applying flerovium compat mixins
            return hasClass("com.moepus.flerovium.functions.FastSimpleBakedModelRenderer");
        }
        if (mixinClassName.contains(".asyncparticles.")) {
            // Ensure AsyncParticles is installed before applying its compat mixin. Its jar keeps
            // both loader variants of every class, rooted per loader — on Forge the runtime
            // package is forge.fun.qu_an... (probing the unprefixed name silently disabled this
            // compat, leaving the byte light cache to mangle the colored packed int: sky nibble
            // dropped → particles black in daylight, red bits in the block nibble → white near
            // torches). Keep the unprefixed probe for potential single-loader ports.
            return hasClass("forge.fun.qu_an.minecraft.asyncparticles.client.addon.LightCachedParticleAddon")
                    || hasClass("fun.qu_an.minecraft.asyncparticles.client.addon.LightCachedParticleAddon");
        }
        if (mixinClassName.contains(".dynamiclights.")) {
            // Ensure SodiumDynamicLights (DynamicLights Reforged) is installed
            return hasClass("toni.sodiumdynamiclights.SodiumDynamicLights");
        }
        if (mixinClassName.contains(".epicfight.")) {
            // Ensure Epic Fight is installed before applying its compat mixin
            return hasClass("yesman.epicfight.client.renderer.patched.entity.PatchedLivingEntityRenderer");
        }
        if (mixinClassName.contains(".subtleeffects.")) {
            // Ensure Subtle Effects is installed before applying its compat mixins
            return hasClass("einstein.subtle_effects.particle.SplashParticle");
        }
        if (mixinClassName.contains(".flopper.")) {
            // Ensure Flopper is installed before applying its compat mixin
            return hasClass("org.cyclops.flopper.client.render.blockentity.RenderBlockEntityFlopper");
        }
        if (mixinClassName.contains(".hbm.")) {
            // Ensure HBM Modernized is installed before applying its compat mixins
            return hasClass("com.hbm_m.client.render.LightSampleCache");
        }
        if (mixinClassName.contains(".truedarkness.")) {
            // Ensure True Darkness (any fork keeping grondag's package) is installed
            return hasClass("grondag.darkness.Darkness");
        }
        if (mixinClassName.contains(".nvidium.")) {
            // Ensure Nvidium or an Acedium port is installed; probe the target class itself
            // since the mod id differs between the forks (nvidium / acedium)
            return hasClass("me.cortex.nvidium.sodiumCompat.NvidiumCompactChunkVertex");
        }
        if (mixinClassName.contains(".iris.")) {
            // Ensure Iris or Oculus is installed
            return hasClass("net.irisshaders.iris.Iris");
        }
        if (mixinClassName.contains(".embeddium.")) {
            // Ensure SPECIFICALLY embeddium is installed
            return hasClass("org.embeddedt.embeddium_integrity.MixinTaintDetector");
        }
	    if (mixinClassName.contains(".mixin.compat.sodium.sodium.")) {
		    // Ensure any sodium version EXCEPT embeddium is installed
		    return !hasClass("org.embeddedt.embeddium_integrity.MixinTaintDetector");
	    }
        if (mixinClassName.contains(".sodium.")) {
            // Ensure Sodium/Rubidium/Embeddium is installed
            return hasClass("me.jellysquid.mods.sodium.client.SodiumClientMod");
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
