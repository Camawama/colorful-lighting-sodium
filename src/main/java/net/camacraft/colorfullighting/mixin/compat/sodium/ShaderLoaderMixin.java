package net.camacraft.colorfullighting.mixin.compat.sodium;

import me.jellysquid.mods.sodium.client.gl.shader.ShaderLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

@Mixin(value = ShaderLoader.class, remap = false)
public class ShaderLoaderMixin {
    /**
     * Embeddium/Sodium loads shader sources from its own classpath resources, bypassing the resource pack stack.
     * This hook allows resource packs (including our built-in pack) to override Sodium shader sources.
     *
     * The loader maps {@code ResourceLocation(namespace, path)} to {@code /assets/<ns>/shaders/<path>}.
     * Resource packs expose those files at {@code assets/<ns>/shaders/<path>}, which are addressed as
     * {@code ResourceLocation(namespace, "shaders/" + path)}.
     */
    @Inject(method = "getShaderSource", at = @At("HEAD"), cancellable = true)
    private static void colorfulLighting$shaderFromResourcePacks(ResourceLocation id, CallbackInfoReturnable<String> cir) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }

        // The requested id is e.g. "sodium:blocks/block_layer_opaque.vsh".
        // Resource packs store it at "assets/sodium/shaders/blocks/block_layer_opaque.vsh".
        ResourceLocation packId = ResourceLocation.fromNamespaceAndPath(id.getNamespace(), "shaders/" + id.getPath());

        Optional<Resource> res;
        try {
            res = mc.getResourceManager().getResource(packId);
        } catch (Throwable ignored) {
            // If anything goes wrong, fall back to Sodium's original classpath lookup.
            return;
        }

        if (res.isEmpty()) {
            return;
        }

        try (InputStream in = res.get().open()) {
            String src = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (Boolean.getBoolean("colorfullighting.debugShaderSources")) {
                org.slf4j.LoggerFactory.getLogger("ColorfulLighting").info("shader source {} from the resource packs ({})", id, res.get().sourcePackId());
            }
            // Embeddium's own sources come this way too (a mod's assets are resources): the terrain fragment shader is wrapped here
            cir.setReturnValue(net.camacraft.colorfullighting.compat.dynamiclights.FragmentSpots.patch(id, src));
        } catch (Throwable ignored) {
            // Fall back to Sodium's original classpath lookup.
        }
    }

    /**
     * Embeddium's own terrain fragment shader, wrapped so its fragments draw the spot lights (2.10.3, FragmentSpots), where it
     * came from the classpath (the hook above returns before this one for a source the resource packs hold).
     */
    @Inject(method = "getShaderSource", at = @At("RETURN"), cancellable = true)
    private static void colorfulLighting$spotLightsInFragments(ResourceLocation id, CallbackInfoReturnable<String> cir) {
        String source = cir.getReturnValue();
        if (Boolean.getBoolean("colorfullighting.debugShaderSources")) {
            org.slf4j.LoggerFactory.getLogger("ColorfulLighting").info("shader source {} ({} chars)", id, source == null ? -1 : source.length());
        }
        String patched = net.camacraft.colorfullighting.compat.dynamiclights.FragmentSpots.patch(id, source);
        if (patched != source) cir.setReturnValue(patched);
    }
}

