package me.erykczy.colorfullighting.common;

import com.google.gson.JsonElement;
import me.erykczy.colorfullighting.common.accessors.BlockStateAccessor;
import me.erykczy.colorfullighting.common.accessors.LevelAccessor;
import me.erykczy.colorfullighting.common.accessors.mixin.LevelAttachments;
import me.erykczy.colorfullighting.common.config.VariantList;
import me.erykczy.colorfullighting.common.util.ColorRGB4;
import me.erykczy.colorfullighting.common.util.JsonHelper;
import me.erykczy.colorfullighting.compat.dynamiclights.DynamicLightsCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
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
    public static ColorRGB4 getColorEmission(@NotNull LevelAccessor level, BlockPos pos, @NotNull BlockStateAccessor blockState) {
        float lightEmission = blockState.getLightEmission(level, pos)/15.0f;
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
                return emitter.color().mul(emitter.overriddenBrightness4 < 0 ? lightEmission : emitter.overriddenBrightness4 / 15.0f);
            }
        }
        return defaultColor.mul(lightEmission);
    }

    @Nullable
    public static ColorEmitter resolveEmitter(@NotNull BlockStateAccessor blockState, @Nullable CompoundTag nbt) {
        VariantList<ColorEmitter> config = colorEmitters.get(blockState.getBlock());
        return config == null ? null : config.resolve(blockState, nbt);
    }

    @Nullable
    public static ColorFilter resolveFilter(@NotNull BlockStateAccessor blockState, @Nullable CompoundTag nbt) {
        VariantList<ColorFilter> config = colorFilters.get(blockState.getBlock());
        return config == null ? null : config.resolve(blockState, nbt);
    }

    @Nullable
    public static ColorEmitter resolveAbsorber(@NotNull BlockStateAccessor blockState, @Nullable CompoundTag nbt) {
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

	@Deprecated
    public static ColorRGB4 getLightColor(@NotNull BlockStateAccessor blockState) {
        return getLightColor(blockState.getBlock());
    }
    public static ColorRGB4 getLightColor(@NotNull BlockState blockState) {
        return getLightColor(blockState.getBlock());
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
    public static ColorRGB4 getColoredLightTransmittance(@NotNull LevelAccessor level, BlockPos pos, @NotNull BlockStateAccessor blockState) {
        VariantList<ColorFilter> config = colorFilters.get(blockState.getBlock());
        if(config == null) return ColorRGB4.fromRGB4(15, 15, 15);
        ColorFilter filter = config.resolve(blockState, nbtFor(level, config, pos));
        return filter != null ? filter.transmittance : ColorRGB4.fromRGB4(15, 15, 15);
    }

    public static ColorRGB4 getColoredLightTransmittance(@NotNull LevelAccessor level, BlockPos pos, @NotNull BlockStateAccessor blockState, Direction direction) {
        ColorRGB4 baseColor = getColoredLightTransmittance(level, pos, blockState);
        if (baseColor.equals(ColorRGB4.WHITE)) return baseColor; // No filter

        // Check for Glass Pane logic
        String north = blockState.getPropertyString("north");
        String south = blockState.getPropertyString("south");
        String east = blockState.getPropertyString("east");
        String west = blockState.getPropertyString("west");

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

    public static int getLightAbsorption(@NotNull LevelAccessor level, BlockPos pos, @NotNull BlockStateAccessor blockState) {
        VariantList<ColorFilter> config = colorFilters.get(blockState.getBlock());
        if(config == null) return -1;
        ColorFilter filter = config.resolve(blockState, nbtFor(level, config, pos));
        return filter != null ? filter.absorption : -1;
    }

    public static int getEmissionBrightness(@NotNull LevelAccessor level, BlockPos pos, int defaultValue) {
        var blockState = level.getBlockState(pos);
        return blockState == null ? defaultValue : getEmissionBrightness(level, pos, blockState);
    }
    public static int getEmissionBrightness(@NotNull LevelAccessor level, BlockPos pos, @NotNull BlockStateAccessor blockState) {
        VariantList<ColorEmitter> config = colorEmitters.get(blockState.getBlock());
        if(config != null) {
            ColorEmitter emitter = config.resolve(blockState, nbtFor(level, config, pos));
            if (emitter != null && emitter.overriddenBrightness4 >= 0) {
                return emitter.overriddenBrightness4;
            }
        }
        return blockState.getLightEmission(level, pos);
    }
    /** Position-less lookup used by the block renderers; NBT rules cannot resolve here and fall through to the default. */
    public static int getEmissionBrightness(BlockStateAccessor blockState) {
        VariantList<ColorEmitter> config = colorEmitters.get(blockState.getBlock());
        if(config != null) {
            ColorEmitter emitter = config.resolve(blockState, null);
            if (emitter != null && emitter.overriddenBrightness4 >= 0) {
                return emitter.overriddenBrightness4;
            }
        }
        return blockState.getLightEmission();
    }

    public static int getAbsorption(LevelAccessor level, BlockPos blockPos, BlockStateAccessor blockState) {
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

    public static ColorRGB4 getAbsorptionColor(LevelAccessor level, BlockPos pos, BlockStateAccessor blockState) {
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
     */
    public record ColorEmitter(ColorRGB4 color, int overriddenBrightness4) {
        public static ColorEmitter fromJsonElement(JsonElement value) throws IllegalArgumentException {
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
    public record ColorFilter(ColorRGB4 transmittance, int absorption) {
        public static ColorFilter fromJsonElement(JsonElement value) throws IllegalArgumentException {
            ColorRGB4 color = getColorFromJsonElement(value);
            Integer absorption = getAbsorptionFromJsonElement(value);
            if(color == null) throw new IllegalArgumentException("Invalid color.");
            return new ColorFilter(color, absorption != null ? absorption : -1);
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
