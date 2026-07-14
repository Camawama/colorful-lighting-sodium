package me.erykczy.colorfullighting.common.config;

import me.erykczy.colorfullighting.common.accessors.BlockStateAccessor;

import java.util.ArrayList;
import java.util.List;

/**
 * A parsed {@code "lit=true,signal=false"} block state predicate. Parsing happens once when the
 * light configs are loaded, so matching a block state costs one property lookup per condition.
 */
public final class StateCondition {
    public static final StateCondition ALWAYS = new StateCondition(new String[0], new String[0]);

    private final String[] names;
    private final String[] values;

    private StateCondition(String[] names, String[] values) {
        this.names = names;
        this.values = values;
    }

    /**
     * @throws IllegalArgumentException if a condition is not of the form {@code name=value}. The old
     *         parser silently ignored malformed pairs, which made a typo match every block state.
     */
    public static StateCondition parse(String spec) {
        String trimmed = spec.trim();
        if (trimmed.isEmpty()) return ALWAYS;

        List<String> names = new ArrayList<>();
        List<String> values = new ArrayList<>();
        for (String pair : trimmed.split(",")) {
            int equals = pair.indexOf('=');
            if (equals < 0) {
                throw new IllegalArgumentException("Malformed block state condition '" + pair.trim() + "', expected name=value");
            }
            String name = pair.substring(0, equals).trim();
            String value = pair.substring(equals + 1).trim();
            if (name.isEmpty() || value.isEmpty()) {
                throw new IllegalArgumentException("Malformed block state condition '" + pair.trim() + "', expected name=value");
            }
            names.add(name);
            values.add(value);
        }
        return new StateCondition(names.toArray(new String[0]), values.toArray(new String[0]));
    }

    /** Number of properties this condition constrains; used to rank legacy {@code states} entries. */
    public int size() {
        return names.length;
    }

    public boolean matches(BlockStateAccessor blockState) {
        for (int i = 0; i < names.length; i++) {
            String actual = blockState.getPropertyString(names[i]);
            if (actual == null || !actual.equals(values[i])) return false;
        }
        return true;
    }
}
