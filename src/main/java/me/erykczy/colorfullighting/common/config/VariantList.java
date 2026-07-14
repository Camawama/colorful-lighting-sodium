package me.erykczy.colorfullighting.common.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import me.erykczy.colorfullighting.common.accessors.BlockStateAccessor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.TagParser;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * An ordered list of conditional light values with a fallback default. The first variant whose
 * conditions all hold wins, so a pack author controls precedence by ordering the {@code variants}
 * array — there is no implicit tie-break.
 *
 * <p>Accepted JSON shapes, for both blocks and entities/items:
 * <pre>
 * "minecraft:glowstone": "yellow"                       // bare value, no conditions
 *
 * "minecraft:beacon": {
 *   "default": "white",
 *   "variants": [
 *     { "nbt": "{primary_effect:\"minecraft:speed\"}", "color": "#00ffff;15" }
 *   ]
 * }
 *
 * "minecraft:furnace": {                                // legacy shape, still supported
 *   "default": "#ff4400;1",
 *   "states": { "lit=true": "orange;7" }
 * }
 * </pre>
 *
 * <p>Legacy {@code states} entries are appended after {@code variants} and sorted most-specific
 * first, so {@code "lit=true,signal=true"} is tested before {@code "lit=true"} regardless of the
 * order they appear in the JSON. Previously they lived in a HashMap and matched in hash order.
 */
public final class VariantList<T> {
    private final List<LightVariant<T>> variants;
    @Nullable
    private final T defaultValue;
    private final boolean needsNbt;

    private VariantList(List<LightVariant<T>> variants, @Nullable T defaultValue) {
        this.variants = variants;
        this.defaultValue = defaultValue;
        boolean nbt = false;
        for (LightVariant<T> variant : variants) {
            if (variant.nbt() != null) {
                nbt = true;
                break;
            }
        }
        this.needsNbt = nbt;
    }

    @Nullable
    public T getDefault() {
        return defaultValue;
    }

    /**
     * Whether any variant tests NBT. Callers use this to avoid serializing a block entity or entity
     * that no rule actually inspects — a config with no NBT rules costs exactly what it used to.
     */
    public boolean needsNbt() {
        return needsNbt;
    }

    /**
     * @param blockState the block state to test, null for entities and items
     * @param nbt        the target's NBT, null when unavailable or when {@link #needsNbt()} is false;
     *                   variants with an NBT condition can never match a null tag
     */
    @Nullable
    public T resolve(@Nullable BlockStateAccessor blockState, @Nullable CompoundTag nbt) {
        for (LightVariant<T> variant : variants) {
            if (variant.state() != StateCondition.ALWAYS) {
                if (blockState == null || !variant.state().matches(blockState)) continue;
            }
            if (variant.nbt() != null) {
                if (nbt == null || !NbtUtils.compareNbt(variant.nbt(), nbt, true)) continue;
            }
            return variant.value();
        }
        return defaultValue;
    }

    /**
     * @param valueParser parses a leaf value ("orange;7", [255, 100, 0, 13], ...)
     * @throws IllegalArgumentException on any malformed condition, so the caller can log and skip the
     *         whole entry rather than silently loading a rule that never fires
     */
    public static <T> VariantList<T> fromJsonElement(JsonElement element, Function<JsonElement, T> valueParser) {
        if (!element.isJsonObject()) {
            return new VariantList<>(Collections.emptyList(), valueParser.apply(element));
        }

        JsonObject object = element.getAsJsonObject();
        T defaultValue = object.has("default") ? valueParser.apply(object.get("default")) : null;
        List<LightVariant<T>> variants = new ArrayList<>();

        if (object.has("variants")) {
            JsonArray array = object.getAsJsonArray("variants");
            for (JsonElement entry : array) {
                variants.add(parseVariant(entry, valueParser));
            }
        }

        if (object.has("states")) {
            List<LightVariant<T>> stateVariants = new ArrayList<>();
            for (var entry : object.getAsJsonObject("states").entrySet()) {
                stateVariants.add(new LightVariant<>(
                        StateCondition.parse(entry.getKey()), null, valueParser.apply(entry.getValue())));
            }
            // stable sort: equal specificity keeps the order the pack author wrote
            stateVariants.sort(Comparator.comparingInt((LightVariant<T> v) -> v.state().size()).reversed());
            variants.addAll(stateVariants);
        }

        return new VariantList<>(variants, defaultValue);
    }

    private static <T> LightVariant<T> parseVariant(JsonElement element, Function<JsonElement, T> valueParser) {
        if (!element.isJsonObject()) {
            throw new IllegalArgumentException("Each entry of \"variants\" must be an object");
        }
        JsonObject object = element.getAsJsonObject();

        StateCondition state = object.has("state")
                ? StateCondition.parse(object.get("state").getAsString())
                : StateCondition.ALWAYS;
        CompoundTag nbt = object.has("nbt") ? parseNbt(object.get("nbt")) : null;

        if (state == StateCondition.ALWAYS && nbt == null) {
            throw new IllegalArgumentException("A variant needs a \"state\" and/or an \"nbt\" condition; use \"default\" for the unconditional value");
        }

        JsonElement value = object.has("color") ? object.get("color") : object.get("value");
        if (value == null) {
            throw new IllegalArgumentException("A variant needs a \"color\"");
        }
        return new LightVariant<>(state, nbt, valueParser.apply(value));
    }

    private static CompoundTag parseNbt(JsonElement element) {
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            // A JSON object would lose NBT's numeric types: {powered:1b} and {powered:1} are different
            // tags and compareNbt treats them as unequal, so SNBT text is the only unambiguous input.
            throw new IllegalArgumentException("\"nbt\" must be an SNBT string, for example \"{powered:1b}\"");
        }
        String snbt = element.getAsString();
        try {
            return TagParser.parseTag(snbt);
        }
        catch (CommandSyntaxException e) {
            throw new IllegalArgumentException("Invalid SNBT in \"nbt\": " + snbt, e);
        }
    }
}
