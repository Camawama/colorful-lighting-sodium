package me.erykczy.colorfullighting.mixin.compat.sodium;

import net.caffeinemc.mods.sodium.client.gl.shader.ShaderLoader;
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
            cir.setReturnValue(src);
        } catch (Throwable ignored) {
            // Fall back to Sodium's original classpath lookup.
        }
    }
}

