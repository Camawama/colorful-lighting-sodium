package net.camacraft.colorfullighting.common;

import com.mojang.blaze3d.platform.NativeImage;
import net.camacraft.colorfullighting.common.util.ColorRGB4;
import net.camacraft.colorfullighting.mixin.render.SpriteContentsAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;
import net.minecraftforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Derives a light emission color from a block's own appearance, for blocks whose color cannot be
 * written down in emitters.json: an emitter configured as {@code "auto"}, or (when the
 * {@code autoEmitterColors} client config is on) any light-emitting block with no configured
 * color at all — modded lamps, ores, crystals and the like then glow in a plausible color
 * automatically instead of plain white.
 *
 * <p>The base color is a luminance-weighted average of the model's particle sprite: bright pixels
 * dominate, so a torch-like texture yields its flame color rather than its handle. The result is
 * normalized to full brightness (emission strength comes from the light level, the sample only
 * supplies the hue) and cached per block state. On top of that, the position-aware lookup applies
 * the block color provider's tint at the actual position, which is what makes coordinate-dependent
 * blocks such as Better End's aurora crystals emit the color they visibly have at that spot.
 *
 * <p>Liquid blocks have no model to speak of (a mod's fluid block usually only names water's
 * texture as its particle, which samples grey and so came out white) and are colored by their
 * fluid type, not by a block color provider. For them the base is the fluid's still texture and
 * the tint is the fluid type's ({@code IClientFluidTypeExtensions}), read through a view of the
 * level in which the position holds the state being judged ({@link StateAtPos}): a liquid whose
 * color follows its block state (Flow Fun!'s Liquid Redstone by its power, a burning liquid)
 * then emits per state, and a change between two such states is seen as a change of light.
 * Grey tinted-water textures get their hue from the tint; textures that carry their own color
 * (molten metals, lava) keep it under a white tint.
 *
 * <p>Runs on the light propagator thread. Model and tint lookups there are as safe as Sodium's
 * worker-thread meshing (which calls the same code), and every step is defensively caught: any
 * failure falls back to white without caching, so a sample attempted mid-resource-reload heals
 * itself on the next light update.
 */
public final class AutoEmitterColors {
    private AutoEmitterColors() {}

    /** Base (untinted) color per block state; identity keys, cleared on resource reload. */
    private static final ConcurrentHashMap<BlockState, ColorRGB4> BASE_COLORS = new ConcurrentHashMap<>();

    /** Sampling stride cap so large (e.g. animated 512-tall) sprites stay cheap to average. */
    private static final int MAX_SAMPLES_PER_AXIS = 64;

    public static void clearCache() {
        BASE_COLORS.clear();
    }

    /**
     * The light colors of every light-emitting block state of one namespace (as the position-less
     * lookup gives them: a configured color, else the automatic one), one line per block: its
     * distinct colors as {@code #rrggbb} with the emission and how many states share each. For
     * {@code /cl debug autocolor <namespace>} and the pack harness. Main thread.
     */
    public static String describe(String namespace) {
        StringBuilder out = new StringBuilder();
        int blocks = 0;
        for (var entry : net.minecraftforge.registries.ForgeRegistries.BLOCKS.getEntries()) {
            ResourceLocation id = entry.getKey().location();
            if (!id.getNamespace().equals(namespace)) continue;
            java.util.Map<String, Integer> colors = new java.util.LinkedHashMap<>();
            for (BlockState state : entry.getValue().getStateDefinition().getPossibleStates()) {
                int emission = state.getLightEmission();
                if (emission <= 0) continue;
                ColorRGB4 c = Config.getLightColor(state);
                String key = String.format(java.util.Locale.ROOT, "#%02x%02x%02x@%d", c.red4 * 17, c.green4 * 17, c.blue4 * 17, emission);
                colors.merge(key, 1, Integer::sum);
            }
            if (colors.isEmpty()) continue;
            blocks++;
            out.append(id).append(':');
            colors.forEach((k, n) -> out.append(' ').append(k).append('x').append(n));
            out.append('\n');
        }
        return blocks + " emitting blocks in " + namespace + "\n" + out;
    }

    /**
     * Emission color for {@code state}, tinted by the block color provider at {@code pos} when a
     * level and position are available (pass null for the position-less render paths).
     */
    public static ColorRGB4 get(BlockState state, @Nullable BlockAndTintGetter level, @Nullable BlockPos pos) {
        FluidState fluid = liquidOf(state);
        if (fluid != null) return liquid(state, fluid, level, pos);
        ColorRGB4 base = BASE_COLORS.get(state);
        if (base == null) {
            base = sampleTexture(state);
            if (base == null) return Config.defaultColor; // failed: retry on a later light update
            BASE_COLORS.put(state, base);
        }
        if (level != null && pos != null) {
            try {
                int tint = Minecraft.getInstance().getBlockColors().getColor(state, level, pos, 0);
                if (tint != -1) return applyTint(base, tint);
            } catch (Throwable ignored) {
                // a non-thread-safe modded color provider: emit untinted rather than crash the propagator
            }
        }
        return base;
    }

    /**
     * The fluid a block shows, when the block is a liquid: any {@link LiquidBlock}, or a modded fluid
     * block drawn only as its fluid. Waterlogged blocks (visible or not) keep their own model and color.
     */
    @Nullable
    private static FluidState liquidOf(BlockState state) {
        FluidState fluid = state.getFluidState();
        if (fluid.isEmpty()) return null;
        if (state.getBlock() instanceof LiquidBlock) return fluid;
        // an invisible block that is only waterlogged (vanilla's light block under water) is not its water
        if (state.getRenderShape() == RenderShape.INVISIBLE
                && !state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)) return fluid;
        return null;
    }

    private static ColorRGB4 liquid(BlockState state, FluidState fluid, @Nullable BlockAndTintGetter level, @Nullable BlockPos pos) {
        IClientFluidTypeExtensions ext;
        try {
            ext = IClientFluidTypeExtensions.of(fluid);
        } catch (Throwable t) {
            return Config.defaultColor;
        }
        BlockPos at = pos != null ? pos : BlockPos.ZERO;
        StateAtPos view = new StateAtPos(level, at, state);
        ColorRGB4 base = BASE_COLORS.get(state);
        if (base == null) {
            base = sampleFluidTexture(state, fluid, ext, view, at);
            if (base == null) return Config.defaultColor; // failed: retry on a later light update
            BASE_COLORS.put(state, base);
        }
        int tint;
        try {
            tint = ext.getTintColor(fluid, view, at);
        } catch (Throwable t) {
            // no level to ask (position-less paths), or a tint that needs more than one block
            try {
                tint = ext.getTintColor();
            } catch (Throwable t2) {
                return base;
            }
        }
        tint &= 0xFFFFFF; // fluid tints are ARGB; the alpha is the liquid's opacity, not its color
        return tint == 0xFFFFFF ? base : tintHue(base, tint);
    }

    /**
     * The texture's hue under the tint, brightest channel back at full (the light level supplies the
     * strength). A texture of one hue under a tint of another (lava frames tinted blue) would
     * otherwise multiply out to almost nothing; when nothing is left the tint's own hue is used.
     */
    private static ColorRGB4 tintHue(ColorRGB4 base, int tintRGB8) {
        double r = base.red4 / 15.0 * ((tintRGB8 >> 16) & 0xFF);
        double g = base.green4 / 15.0 * ((tintRGB8 >> 8) & 0xFF);
        double b = base.blue4 / 15.0 * (tintRGB8 & 0xFF);
        double max = Math.max(r, Math.max(g, b));
        if (max < 8.0) { // under 1/32 of full: the hues don't overlap
            r = (tintRGB8 >> 16) & 0xFF;
            g = (tintRGB8 >> 8) & 0xFF;
            b = tintRGB8 & 0xFF;
            max = Math.max(r, Math.max(g, b));
            if (max <= 0) return base;
        }
        return ColorRGB4.fromRGB8(
                (int) Math.round(r * 255.0 / max),
                (int) Math.round(g * 255.0 / max),
                (int) Math.round(b * 255.0 / max));
    }

    /** The fluid's still texture as this state shows it, falling back to the block model's particle. */
    @Nullable
    private static ColorRGB4 sampleFluidTexture(BlockState state, FluidState fluid, IClientFluidTypeExtensions ext,
                                                BlockAndTintGetter view, BlockPos at) {
        try {
            ResourceLocation texture;
            try {
                texture = ext.getStillTexture(fluid, view, at);
            } catch (Throwable t) {
                texture = ext.getStillTexture();
            }
            if (texture != null) {
                TextureAtlasSprite sprite = Minecraft.getInstance().getModelManager()
                        .getAtlas(InventoryMenu.BLOCK_ATLAS).getSprite(texture);
                if (!MissingTextureAtlasSprite.getLocation().equals(sprite.contents().name())) {
                    ColorRGB4 sampled = sampleSprite(sprite);
                    if (sampled != null) return sampled;
                }
            }
        } catch (Throwable ignored) {
            // fall through to the model's particle
        }
        return sampleTexture(state);
    }

    @Nullable
    private static ColorRGB4 sampleTexture(BlockState state) {
        try {
            BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
            return sampleSprite(model.getParticleIcon(ModelData.EMPTY));
        } catch (Throwable t) {
            return null;
        }
    }

    @Nullable
    private static ColorRGB4 sampleSprite(TextureAtlasSprite sprite) {
        try {
            NativeImage image = ((SpriteContentsAccessor) sprite.contents()).colorfullighting$getOriginalImage();
            if (image == null) return null;

            int width = image.getWidth();
            int height = image.getHeight();
            if (width <= 0 || height <= 0) return null;
            int strideX = Math.max(1, width / MAX_SAMPLES_PER_AXIS);
            int strideY = Math.max(1, height / MAX_SAMPLES_PER_AXIS);

            double sumR = 0, sumG = 0, sumB = 0, sumWeight = 0;
            for (int y = 0; y < height; y += strideY) {
                for (int x = 0; x < width; x += strideX) {
                    int abgr = image.getPixelRGBA(x, y);
                    int alpha = (abgr >>> 24) & 0xFF;
                    if (alpha < 16) continue;
                    int r = abgr & 0xFF;
                    int g = (abgr >>> 8) & 0xFF;
                    int b = (abgr >>> 16) & 0xFF;
                    // luminance-squared weighting: the glowing part of the texture defines the hue
                    double lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
                    double weight = (alpha / 255.0) * (lum * lum + 0.001);
                    sumR += r * weight;
                    sumG += g * weight;
                    sumB += b * weight;
                    sumWeight += weight;
                }
            }
            if (sumWeight <= 0) return Config.defaultColor;

            double r = sumR / sumWeight, g = sumG / sumWeight, b = sumB / sumWeight;
            double max = Math.max(r, Math.max(g, b));
            if (max <= 0) return Config.defaultColor;
            // hue only: scale the brightest channel to full, the light level supplies intensity
            return ColorRGB4.fromRGB8(
                    (int) Math.round(r * 255.0 / max),
                    (int) Math.round(g * 255.0 / max),
                    (int) Math.round(b * 255.0 / max));
        } catch (Throwable t) {
            return null;
        }
    }

    private static ColorRGB4 applyTint(ColorRGB4 base, int tintRGB8) {
        int tr = (tintRGB8 >> 16) & 0xFF;
        int tg = (tintRGB8 >> 8) & 0xFF;
        int tb = tintRGB8 & 0xFF;
        int max = Math.max(tr, Math.max(tg, tb));
        if (max <= 0) return base;
        // normalize the tint too, then combine multiplicatively in 4-bit space
        int r = Math.round(base.red4 * (tr * 255f / max) / 255f);
        int g = Math.round(base.green4 * (tg * 255f / max) / 255f);
        int b = Math.round(base.blue4 * (tb * 255f / max) / 255f);
        return ColorRGB4.fromRGB4(r, g, b);
    }
}
