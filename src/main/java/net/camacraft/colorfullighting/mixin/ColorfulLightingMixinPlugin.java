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

    /** Whether Xaero's Minimap / World Map have what the Xaero mixins need (worked out once). */
    private Boolean xaeroMinimap, xaeroWorldMap;

    /** Whether the class exists and declares all these fields and methods (any descriptor). */
    private boolean hasMembers(String className, String[] fields, String[] methods) {
        if (!hasClass(className)) return false;
        try (InputStream is = this.getClass().getClassLoader().getResourceAsStream(className.replace('.', '/') + ".class")) {
            if (is == null) return false;
            ClassNode node = new ClassNode();
            new org.objectweb.asm.ClassReader(is).accept(node, org.objectweb.asm.ClassReader.SKIP_CODE);
            Set<String> have = new java.util.HashSet<>();
            node.fields.forEach(f -> have.add("f:" + f.name));
            node.methods.forEach(m -> have.add("m:" + m.name));
            for (String f : fields) if (!have.contains("f:" + f)) return false;
            for (String m : methods) if (!have.contains("m:" + m)) return false;
            return true;
        } catch (Exception e) {
            return false;
        }
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
        if (mixinClassName.contains(".amendments.")) {
            // Only where Amendments adds its dragon's breath light override (the mixin injects into that method)
            return hasClass("net.mehvahdjukaar.amendments.mixins.DragonBreathParticleMixin");
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
        if (mixinClassName.contains(".xaero.minimap.")) {
            // Xaero's Minimap, and only one whose writer and tiles still have every member these mixins shadow
            // or inject into (checked in their bytes, so another Xaero version is skipped, not a crash)
            if (xaeroMinimap == null) {
                xaeroMinimap = hasMembers("xaero.common.minimap.write.MinimapWriter",
                        new String[]{"sun", "blockY", "firstBlockY", "isglowing", "loadingLevels", "loadingSingleLevelBrightness",
                                "loadingLightOverlayType", "loadingLegibleCaveMode"},
                        new String[]{"findBlock", "calculateBlockColors", "loadBlockColor"})
                        && hasMembers("xaero.common.minimap.region.MinimapTile", new String[0],
                        new String[]{"pixelChanged", "setCode", "create"});
            }
            return xaeroMinimap;
        }
        if (mixinClassName.contains(".xaero.worldmap.")) {
            // Xaero's World Map (colors recorded with its map and drawn under its textures), likewise checked
            if (xaeroWorldMap == null) {
                xaeroWorldMap = hasMembers("xaero.map.MapWriter", new String[]{"topH"}, new String[]{"loadPixel"})
                        && hasMembers("xaero.map.region.MapPixel", new String[]{"light", "glowing"}, new String[0])
                        && hasMembers("xaero.map.region.MapBlock", new String[0], new String[]{"prepareForWriting", "equalsSlopesExcluded"})
                        && hasMembers("xaero.map.region.MapTileChunk", new String[0], new String[]{"updateBuffers", "putColour"})
                        && hasMembers("xaero.map.region.LeveledRegion", new String[]{"worldId", "dimId", "mwId"}, new String[0])
                        && hasMembers("xaero.map.region.texture.RegionTexture", new String[]{"glColorTexture", "region"},
                        new String[]{"bindColorTexture", "deleteTexturesAndBuffers"})
                        && hasMembers("xaero.map.file.MapSaveLoad", new String[0], new String[]{"loadRegion"})
                        && hasMembers("xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRenderer",
                        new String[]{"textureBinderShader"}, new String[]{"draw"})
                        && hasMembers("xaero.lib.client.graphics.shader.LibShaders",
                        new String[]{"WORLD_MAP", "MINIMAP_WORLD_MAP"}, new String[]{"onResourceReload"});
            }
            if (!xaeroWorldMap) return false;
            if (mixinClassName.endsWith(".XaeroMinimapTextureDrawMixin")) {
                // the minimap's own copy of the batch renderer, which draws World Map chunks on the minimap
                return hasMembers("xaero.common.graphics.renderer.multitexture.MultiTextureRenderTypeRenderer",
                        new String[]{"textureBinderShader"}, new String[]{"draw"});
            }
            return true;
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
