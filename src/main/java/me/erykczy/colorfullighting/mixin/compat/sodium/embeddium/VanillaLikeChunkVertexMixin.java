package me.erykczy.colorfullighting.mixin.compat.sodium.embeddium;

import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.Material;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

@Mixin(targets = "me.jellysquid.mods.sodium.client.render.chunk.vertex.format.impl.VanillaLikeChunkVertex", remap = false)
public class VanillaLikeChunkVertexMixin {

    @Unique
    private static final int colorfulLighting$STRIDE = 28;

    /**
     * @author Erykczy
     * @reason Overwrite the entire encoder to handle 32-bit packed colored lighting data correctly,
     * bypassing the faulty bit-shifting in the original implementation for non-compact vertices.
     */
    @Overwrite
    public ChunkVertexEncoder getEncoder() {
        return (ptr, material, vertex, sectionIndex) -> {
            MemoryUtil.memPutFloat(ptr, vertex.x);
            MemoryUtil.memPutFloat(ptr + 4, vertex.y);
            MemoryUtil.memPutFloat(ptr + 8, vertex.z);
            MemoryUtil.memPutInt(ptr + 12, vertex.color);
            MemoryUtil.memPutFloat(ptr + 16, colorfulLighting$encodeTexture(vertex.u));
            MemoryUtil.memPutFloat(ptr + 20, colorfulLighting$encodeTexture(vertex.v));

            int drawParams = colorfulLighting$encodeDrawParameters(material, sectionIndex);

            // Check for our magic number. If present, we write the packed 32-bit color
            // directly to the buffer. Otherwise, we replicate the vanilla behavior
            // to ensure normal lighting is not broken.
            if (((vertex.light >> 28) & 0xF) == 0xF) {
                // Compress 32-bit light data into 16 bits to fit alongside drawParams
                int r = (vertex.light & 0xFF) >> 4; // 4 bits
                int g = ((vertex.light >> 8) & 0xFF) >> 4; // 4 bits
                int b = ((vertex.light >> 20) & 0xFF) >> 5; // 3 bits
                int sky = (vertex.light >> 16) & 0xF; // 4 bits

                // Use bit 0 as the magic bit for the compressed format.
                // Vanilla light levels are multiples of 16, so their lowest 4 bits are always 0.
                int compressedLight = 1 | (sky << 1) | (r << 5) | (g << 9) | (b << 13);

                MemoryUtil.memPutInt(ptr + 24, drawParams | (compressedLight << 16));
            } else {
                int vanillaLight = colorfulLighting$encodeVanillaLight(vertex.light);
                MemoryUtil.memPutInt(ptr + 24, drawParams | (vanillaLight << 16));
            }

            return ptr + colorfulLighting$STRIDE;
        };
    }

    @Unique
    private static int colorfulLighting$encodeDrawParameters(Material material, int sectionIndex) {
        return (((sectionIndex & 0xFF) << 8) | (material.bits() & 0xFF));
    }

    @Unique
    private static int colorfulLighting$encodeVanillaLight(int light) {
        int block = light & 0xFF;
        int sky = (light >> 16) & 0xFF;
        return (block | (sky << 8));
    }

    @Unique
    private static float colorfulLighting$encodeTexture(float value) {
        return Math.min(0.99999997F, value);
    }
}
