package net.camacraft.colorfullighting.common;

import com.google.gson.JsonElement;
import net.camacraft.colorfullighting.accessors.BlockStateWrapper;
import net.camacraft.colorfullighting.common.accessors.LevelAccessor;
import net.camacraft.colorfullighting.common.accessors.mixin.LevelAttachments;
import net.camacraft.colorfullighting.common.api.ApiProviderRegistry;
import net.camacraft.colorfullighting.common.config.VariantList;
import net.camacraft.colorfullighting.common.util.BiomeTint;
import net.camacraft.colorfullighting.common.util.ColorRGB4;
import net.camacraft.colorfullighting.common.util.JsonHelper;
import net.camacraft.colorfullighting.compat.dynamiclights.DynamicLightsCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class Config {
    public static final ColorRGB4 defaultColor = ColorRGB4.fromRGB4(15, 15, 15);
    // Keyed by Block, not ResourceLocation: these are read for every neighbour of every propagated
    // block, and hashing a ResourceLocation there cost ~10% of the light propagator thread.
    // Block has no equals/hashCode override, so lookups are identity-hash + reference compare.
    private static Map<Block, VariantList<ColorEmitter>> colorEmitters = Collections.emptyMap();
    private static Map<Block, VariantList<ColorFilter>> colorFilters = Collections.emptyMap();
    private static Map<Block, VariantList<ColorEmitter>> colorAbsorbers = Collections.emptyMap();
    private static HashMap<ResourceLocation, VariantList<ColorEmitter>> entityEmitters = new HashMap<>();
    private static HashMap<ResourceLocation, VariantList<ColorEmitter>> itemEmitters = new HashMap<>();
    private static Map<Integer, ColorMoonPhase> moonPhases = new HashMap<>();

    /**
     * Blocks with at least one NBT-conditioned rule, resolved to Block instances once per config load.
     * Every block entity registration tests against this, so it must not cost a registry lookup:
     * Block has no equals/hashCode override, so contains() is a reference compare.
     */
    private static volatile Set<Block> blocksNeedingNbt = Collections.emptySet();

    public static void setColorEmitters(HashMap<ResourceLocation, VariantList<ColorEmitter>> colors) {
        colorEmitters = byBlock(colors);
        recomputeBlocksNeedingNbt();
    }

    public static void setColorFilters(HashMap<ResourceLocation, VariantList<ColorFilter>> filters) {
        colorFilters = byBlock(filters);
        recomputeBlocksNeedingNbt();
    }

    public static void setColorAbsorbers(HashMap<ResourceLocation, VariantList<ColorEmitter>> absorbers) {
        colorAbsorbers = byBlock(absorbers);
        recomputeBlocksNeedingNbt();
    }

    /** Resolves ids to Block instances once, at config load, so lookups never touch the registry. */
    private static <T> Map<Block, T> byBlock(Map<ResourceLocation, T> byId) {
        Map<Block, T> result = new HashMap<>(byId.size() * 2);
        for (var entry : byId.entrySet()) {
            Block block = ForgeRegistries.BLOCKS.getValue(entry.getKey());
            if (block != null && block != Blocks.AIR) result.put(block, entry.getValue());
        }
        return result;
    }

    public static void setEntityEmitters(HashMap<ResourceLocation, VariantList<ColorEmitter>> emitters) {
        entityEmitters = emitters;
    }

    public static void setItemEmitters(HashMap<ResourceLocation, VariantList<ColorEmitter>> emitters) {
        itemEmitters = emitters;
    }

    public static void setMoonPhases(Map<Integer, ColorMoonPhase> phases) {
        moonPhases = new HashMap<>(phases);
    }

    private static void recomputeBlocksNeedingNbt() {
        Set<Block> blocks = new HashSet<>();
        collectNbtBlocks(colorEmitters, blocks);
        collectNbtBlocks(colorFilters, blocks);
        collectNbtBlocks(colorAbsorbers, blocks);
        blocksNeedingNbt = blocks;
    }

    private static void collectNbtBlocks(Map<Block, ? extends VariantList<?>> configs, Set<Block> blocks) {
        for (var entry : configs.entrySet()) {
            if (entry.getValue().needsNbt()) blocks.add(entry.getKey());
        }
    }

    /** False for every pack without NBT rules, which is the common case: block entity tracking stays off. */
    public static boolean anyBlockNeedsNbt() {
        return !blocksNeedingNbt.isEmpty();
    }

    /** Whether a block entity at this block needs its NBT snapshotted for the light rules to resolve. */
    public static boolean blockNeedsNbt(Block block) {
        return blocksNeedingNbt.contains(block);
    }

    /**
     * NBT of the block entity at {@code pos}, but only when {@code config} has a rule that inspects it.
     * Blocks with no NBT rules never pay for the lookup.
     */
    @Nullable
    private static CompoundTag nbtFor(LevelAccessor level, VariantList<?> config, BlockPos pos) {
        return config.needsNbt() ? ((LevelAttachments) level).colorfullighting$getNbtCache().get(pos) : null;
    }

    public static ColorRGB4 getColorEmission(@NotNull LevelAccessor level, BlockPos pos) { return getColorEmission(level, pos, level.getBlockState(pos)); }
    public static ColorRGB4 getColorEmission(@NotNull LevelAccessor level, BlockPos pos, @NotNull BlockState blockState) {
        float lightEmission = blockState.getLightEmission(level.getLevel(), pos)/15.0f;
        Block block = blockState.getBlock();

        // Light blocks placed by dynamic lighting mods (Lively Lighting) are colored by the
        // nearby entity that caused them; client-lighting mods are handled by the entity
        // tracking in DynamicLightsCompat instead
        if (DynamicLightsCompat.isDynamicLightBlock(block)) {
            ColorRGB4 dynamicColor = DynamicLightsCompat.getDynamicBlockLightColor(level, pos);
            if (dynamicColor != null) {
                return dynamicColor.mul(lightEmission);
            }
        }

        VariantList<ColorEmitter> config = colorEmitters.get(block);
        if(config != null) {
            ColorEmitter emitter = config.resolve(blockState, nbtFor(level, config, pos));
            if (emitter != null) {
                ColorRGB4 color = emitter.autoColor
                        ? AutoEmitterColors.get(blockState, level.getLevel(), pos)
                        : emitter.color();
                return color.mul(emitter.overriddenBrightness4 < 0 ? lightEmission : emitter.overriddenBrightness4 / 15.0f);
            }
        }
        // API providers: dynamic colors JSON cannot express. Run after explicit emitters.json
        // config (user/pack intent wins) and before the automatic texture heuristic.
        if (lightEmission > 0 && ApiProviderRegistry.hasBlockProviders()) {
            ColorRGB4 providerColor = ApiProviderRegistry.getBlockColor(level.getLevel(), pos, blockState);
            if (providerColor != null) {
                return providerColor.mul(lightEmission);
            }
        }

        // unconfigured light source (typically modded): derive the color from its texture
        if (lightEmission > 0 && ColorfulLightingConfig.autoEmitterColors()) {
            return AutoEmitterColors.get(blockState, level.getLevel(), pos).mul(lightEmission);
        }
        return defaultColor.mul(lightEmission);
    }

    @Nullable
    public static ColorEmitter resolveEmitter(@NotNull BlockState blockState, @Nullable CompoundTag nbt) {
        VariantList<ColorEmitter> config = colorEmitters.get(blockState.getBlock());
        return config == null ? null : config.resolve(blockState, nbt);
    }

    @Nullable
    public static ColorFilter resolveFilter(@NotNull BlockState blockState, @Nullable CompoundTag nbt) {
        VariantList<ColorFilter> config = colorFilters.get(blockState.getBlock());
        return config == null ? null : config.resolve(blockState, nbt);
    }

    @Nullable
    public static ColorEmitter resolveAbsorber(@NotNull BlockState blockState, @Nullable CompoundTag nbt) {
        VariantList<ColorEmitter> config = colorAbsorbers.get(blockState.getBlock());
        return config == null ? null : config.resolve(blockState, nbt);
    }

    @Nullable
    public static VariantList<ColorEmitter> getEntityEmitterConfig(ResourceLocation entityId) {
        return entityEmitters.get(entityId);
    }

    @Nullable
    public static VariantList<ColorEmitter> getItemEmitterConfig(ResourceLocation itemId) {
        return itemEmitters.get(itemId);
    }

    /** Cold path: resolves an id through the registry. Never call this during light propagation. */
    @Nullable
    public static VariantList<ColorEmitter> getBlockEmitterConfig(ResourceLocation blockId) {
        Block block = ForgeRegistries.BLOCKS.getValue(blockId);
        return block == null || block == Blocks.AIR ? null : colorEmitters.get(block);
    }

    @Nullable
    public static VariantList<ColorEmitter> getBlockEmitterConfig(Block block) {
        return colorEmitters.get(block);
    }

    public static float getMoonVibrancy(int phase) {
        ColorMoonPhase moonPhase = moonPhases.get(phase);
        if (moonPhase != null) {
            return moonPhase.vibrancy();
        }
        // Fallback to original calculation if not defined
        int dist = Math.abs(phase - 4);
        float normalizedDist = dist / 4.0f;
        return 1.0f - normalizedDist;
    }

    /** Position-less lookup used by the block renderers; auto colors resolve untinted here. */
    public static ColorRGB4 getLightColor(@NotNull BlockState blockState) {
        VariantList<ColorEmitter> config = colorEmitters.get(blockState.getBlock());
        if(config != null && config.getDefault() != null) {
            ColorEmitter emitter = config.getDefault();
            return emitter.autoColor ? AutoEmitterColors.get(blockState, null, null) : emitter.color();
        }
        if (blockState.getLightEmission() > 0 && ColorfulLightingConfig.autoEmitterColors()) {
            return AutoEmitterColors.get(blockState, null, null);
        }
        return defaultColor;
    }
    public static ColorRGB4 getLightColor(@Nullable Block block) {
        if(block != null) {
            VariantList<ColorEmitter> config = colorEmitters.get(block);
            if(config != null && config.getDefault() != null)
                return config.getDefault().color();
        }
        return defaultColor;
    }

    public static ColorRGB4 getColoredLightTransmittance(@NotNull LevelAccessor level, BlockPos pos, ColorRGB4 defaultValue) {
        var blockState = level.getBlockState(pos);
        return blockState == null ? defaultValue : getColoredLightTransmittance(level, pos, blockState);
    }
    public static ColorRGB4 getColoredLightTransmittance(@NotNull LevelAccessor level, BlockPos pos, @NotNull BlockState blockState) {
        VariantList<ColorFilter> config = colorFilters.get(blockState.getBlock());
        if(config == null) return ColorRGB4.WHITE;
        ColorFilter filter = config.resolve(blockState, nbtFor(level, config, pos));
        if(filter == null) return ColorRGB4.WHITE;
        if(!filter.biomeWater) return filter.transmittance;
        ColorRGB4 color = BiomeTint.waterColor(level, pos, filter.transmittance);
        return filter.strength < 1.0f ? ColorRGB4.towardWhite(color, filter.strength) : color;
    }

    /**
     * Whether the filter at this block applies its color multiplicatively (per block crossed)
     * instead of as a ceiling clamp; the propagator uses this to pick how to apply the color
     * returned by {@link #getColoredLightTransmittance}.
     */
    public static boolean isMultiplyFilter(@NotNull LevelAccessor level, BlockPos pos, @NotNull BlockState blockState) {
        VariantList<ColorFilter> config = colorFilters.get(blockState.getBlock());
        if(config == null) return false;
        ColorFilter filter = config.resolve(blockState, nbtFor(level, config, pos));
        return filter != null && filter.multiply;
    }

    public static ColorRGB4 getColoredLightTransmittance(@NotNull LevelAccessor level, BlockPos pos, @NotNull BlockState blockState, Direction direction) {
        ColorRGB4 baseColor = getColoredLightTransmittance(level, pos, blockState);
        if (baseColor.equals(ColorRGB4.WHITE)) return baseColor; // No filter

        // Check for Glass Pane logic
        String north = BlockStateWrapper.getPropertyString(blockState, "north");
        String south = BlockStateWrapper.getPropertyString(blockState, "south");
        String east = BlockStateWrapper.getPropertyString(blockState, "east");
        String west = BlockStateWrapper.getPropertyString(blockState, "west");

        if (north != null && south != null && east != null && west != null) {
            // It's a pane-like block
            if (direction.getAxis().isVertical()) return ColorRGB4.WHITE;

            boolean hasNorth = north.equals("true");
            boolean hasSouth = south.equals("true");
            boolean hasEast = east.equals("true");
            boolean hasWest = west.equals("true");

            if (direction.getAxis() == Direction.Axis.X) { // East/West movement
                if (hasNorth || hasSouth) return baseColor; // Blocked by N-S glass
                return ColorRGB4.WHITE;
            }
            if (direction.getAxis() == Direction.Axis.Z) { // North/South movement
                if (hasEast || hasWest) return baseColor; // Blocked by E-W glass
                return ColorRGB4.WHITE;
            }
        }

        return baseColor;
    }

    public static int getLightAbsorption(@NotNull LevelAccessor level, BlockPos pos, @NotNull BlockState blockState) {
        VariantList<ColorFilter> config = colorFilters.get(blockState.getBlock());
        if(config == null) return -1;
        ColorFilter filter = config.resolve(blockState, nbtFor(level, config, pos));
        return filter != null ? filter.absorption : -1;
    }

    public static int getEmissionBrightness(@NotNull LevelAccessor level, BlockPos pos, int defaultValue) {
        var blockState = level.getBlockState(pos);
        return blockState == null ? defaultValue : getEmissionBrightness(level, pos, blockState);
    }
    public static int getEmissionBrightness(@NotNull LevelAccessor level, BlockPos pos, @NotNull BlockState blockState) {
        VariantList<ColorEmitter> config = colorEmitters.get(blockState.getBlock());
        if(config != null) {
            ColorEmitter emitter = config.resolve(blockState, nbtFor(level, config, pos));
            if (emitter != null && emitter.overriddenBrightness4 >= 0) {
                return emitter.overriddenBrightness4;
            }
        }
        return blockState.getLightEmission(level.getLevel(), pos);
    }
    /** Position-less lookup used by the block renderers; NBT rules cannot resolve here and fall through to the default. */
    public static int getEmissionBrightness(BlockState blockState) {
        VariantList<ColorEmitter> config = colorEmitters.get(blockState.getBlock());
        if(config != null) {
            ColorEmitter emitter = config.resolve(blockState, null);
            if (emitter != null && emitter.overriddenBrightness4 >= 0) {
                return emitter.overriddenBrightness4;
            }
        }
        return blockState.getLightEmission();
    }

    public static int getAbsorption(LevelAccessor level, BlockPos blockPos, BlockState blockState) {
        VariantList<ColorEmitter> config = colorAbsorbers.get(blockState.getBlock());
        if(config != null) {
            ColorEmitter emitter = config.resolve(blockState, nbtFor(level, config, blockPos));
            if (emitter != null && emitter.overriddenBrightness4 >= 0) {
                return emitter.overriddenBrightness4;
            }
        }
        return 0;
    }

    public static ColorRGB4 getAbsorptionColor(LevelAccessor level, BlockPos blockPos) {
        return getAbsorptionColor(level, blockPos, level.getBlockState(blockPos));
    }

    public static ColorRGB4 getAbsorptionColor(LevelAccessor level, BlockPos pos, BlockState blockState) {
        float absorption = getAbsorption(level, pos, blockState) / 15.0f;
        VariantList<ColorEmitter> config = colorAbsorbers.get(blockState.getBlock());
        if(config != null) {
            ColorEmitter emitter = config.resolve(blockState, nbtFor(level, config, pos));
            if (emitter != null) {
                return emitter.color().mul(emitter.overriddenBrightness4 < 0 ? absorption : emitter.overriddenBrightness4 / 15.0f);
            }
        }
        return ColorRGB4.BLACK;
    }

    /**
     * @param color light color
     * @param overriddenBrightness4 4 bit value in range 0..15, by which light color is multiplied, if -1, vanilla emission for given block is used
     * @param autoColor when true the color is sampled from the block's texture (and position tint)
     *                  at lookup time via {@link AutoEmitterColors}; {@code color} is only a fallback
     */
    public record ColorEmitter(ColorRGB4 color, int overriddenBrightness4, boolean autoColor) {
        public ColorEmitter(ColorRGB4 color, int overriddenBrightness4) {
            this(color, overriddenBrightness4, false);
        }

        public static ColorEmitter fromJsonElement(JsonElement value) throws IllegalArgumentException {
            if (!value.isJsonArray() && value.getAsString().split(";")[0].trim().equalsIgnoreCase("auto")) {
                Integer brightness = getBrightnessFromJsonElement(value);
                if(brightness == null) throw new IllegalArgumentException("Invalid brightness.");
                return new ColorEmitter(defaultColor, brightness, true);
            }
            ColorRGB4 color = getColorFromJsonElement(value);
            Integer brightness = getBrightnessFromJsonElement(value);
            if(color == null) throw new IllegalArgumentException("Invalid color.");
            if(brightness == null) throw new IllegalArgumentException("Invalid brightness.");
            return new ColorEmitter(color, brightness);
        }

        private static ColorRGB4 getColorFromJsonElement(JsonElement value) {
            if(value.isJsonArray()) {
                var array = value.getAsJsonArray();
                if(array.size() < 3) return null;
                return JsonHelper.getColor4FromJsonElements(array.get(0), array.get(1), array.get(2));
            }
            return JsonHelper.getColor4FromString(value.getAsString().split(";")[0].trim());
        }

        private static Integer getBrightnessFromJsonElement(JsonElement value) {
            if(value.isJsonArray()) {
                var array = value.getAsJsonArray();
                if(array.size() < 4) return -1;
                return JsonHelper.getInt4FromJsonElement(array.get(3));
            }
            String[] args = value.getAsString().split(";");
            if(args.length < 2) return -1;
            String brightnessArg = args[1].trim();
            try {
                int brightness = Integer.parseInt(brightnessArg);
                if(brightness >= 0 && brightness <= 15) return brightness;
            }
            catch (NumberFormatException ignore) {}

            try {
                int brightness = Integer.parseInt(brightnessArg, 16);
                if(brightness >= 0 && brightness <= 15) return brightness;
            }
            catch (NumberFormatException ignore) {}

            return null;
        }
    }
    /**
     * @param transmittance filter color; for {@code biomeWater} filters this is only the fallback
     *                      when the biome cannot be resolved. Static colors are pre-blended toward
     *                      white by {@code strength} at parse time.
     * @param absorption    light level lost per block crossed, -1 = use the vanilla value
     * @param multiply      apply the color as a per-block multiplicative tint (Beer-Lambert style,
     *                      deepens with distance) instead of the legacy per-channel ceiling clamp
     * @param biomeWater    resolve the color from the biome's water color at the filtering block
     * @param strength      0..1 blend of the (biome) color toward white, applied at lookup time for
     *                      biome colors; 1 = full color
     *
     * <p>String syntax: {@code "<color|biome_water>[;<absorption>[;<multiply|clamp>[;<strength>]]]"},
     * e.g. {@code "biome_water;1;multiply;0.5"}. The array form {@code [r, g, b, absorption]} keeps
     * its legacy clamp behavior.
     */
    public record ColorFilter(ColorRGB4 transmittance, int absorption, boolean multiply, boolean biomeWater, float strength) {
        /** Fallback water color when the biome is unavailable (vanilla ocean water, normalized). */
        private static final ColorRGB4 WATER_FALLBACK = ColorRGB4.fromRGB8(70, 131, 255);

        public ColorFilter(ColorRGB4 transmittance, int absorption) {
            this(transmittance, absorption, false, false, 1.0f);
        }

        public static ColorFilter fromJsonElement(JsonElement value) throws IllegalArgumentException {
            if (value.isJsonArray()) {
                ColorRGB4 color = getColorFromJsonElement(value);
                Integer absorption = getAbsorptionFromJsonElement(value);
                if(color == null) throw new IllegalArgumentException("Invalid color.");
                return new ColorFilter(color, absorption != null ? absorption : -1);
            }

            String[] args = value.getAsString().split(";");
            String colorArg = args[0].trim();
            boolean biomeWater = colorArg.equalsIgnoreCase("biome_water");
            ColorRGB4 color = biomeWater ? WATER_FALLBACK : JsonHelper.getColor4FromString(colorArg);
            if(color == null) throw new IllegalArgumentException("Invalid color.");
            Integer absorption = getAbsorptionFromJsonElement(value);

            boolean multiply = false;
            float strength = 1.0f;
            for (int i = 2; i < args.length; i++) {
                String arg = args[i].trim();
                if (arg.equalsIgnoreCase("multiply")) multiply = true;
                else if (arg.equalsIgnoreCase("clamp")) multiply = false;
                else {
                    try {
                        strength = Math.max(0.0f, Math.min(1.0f, Float.parseFloat(arg)));
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("Unknown filter option: " + arg);
                    }
                }
            }
            // static colors can be softened once here; biome colors blend at lookup time
            if (!biomeWater && strength < 1.0f) color = ColorRGB4.towardWhite(color, strength);
            return new ColorFilter(color, absorption != null ? absorption : -1, multiply, biomeWater, strength);
        }

        private static ColorRGB4 getColorFromJsonElement(JsonElement value) {
            if(value.isJsonArray()) {
                var array = value.getAsJsonArray();
                if(array.size() < 3) return null;
                return JsonHelper.getColor4FromJsonElements(array.get(0), array.get(1), array.get(2));
            }
            return JsonHelper.getColor4FromString(value.getAsString().split(";")[0].trim());
        }

        private static Integer getAbsorptionFromJsonElement(JsonElement value) {
            if(value.isJsonArray()) {
                var array = value.getAsJsonArray();
                if(array.size() < 4) return -1;
                return JsonHelper.getInt4FromJsonElement(array.get(3));
            }
            String[] args = value.getAsString().split(";");
            if(args.length < 2) return -1;
            String absorptionArg = args[1].trim();
            try {
                int absorption = Integer.parseInt(absorptionArg);
                if(absorption >= 0 && absorption <= 15) return absorption;
            }
            catch (NumberFormatException ignore) {}

            try {
                int absorption = Integer.parseInt(absorptionArg, 16);
                if(absorption >= 0 && absorption <= 15) return absorption;
            }
            catch (NumberFormatException ignore) {}

            return null;
        }
    }

    public record ColorMoonPhase(float vibrancy) {
        public static ColorMoonPhase fromJsonElement(JsonElement value) throws IllegalArgumentException {
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                float vibrancy = value.getAsFloat();
                if (vibrancy < 0.0f || vibrancy > 1.0f) {
                    throw new IllegalArgumentException("Vibrancy must be between 0.0 and 1.0.");
                }
                return new ColorMoonPhase(vibrancy);
            }
            throw new IllegalArgumentException("Invalid moon phase value.");
        }
    }
}
