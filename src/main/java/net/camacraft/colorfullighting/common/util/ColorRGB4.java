package net.camacraft.colorfullighting.common.util;

public class ColorRGB4 {
    public static final int SIZE = 12;

    // Only 4096 values exist (three 4-bit channels), so every instance is interned. The light
    // propagator churned millions of short-lived ColorRGB4s per minute (~5% of the game's total
    // allocation pressure in the 2026-08-06 JFR capture), feeding the once-a-second GC stutter.
    private static final ColorRGB4[] TABLE = new ColorRGB4[4096];
    static {
        for (int i = 0; i < 4096; ++i) {
            TABLE[i] = new ColorRGB4((i >>> 8) & 0xF, (i >>> 4) & 0xF, i & 0xF);
        }
    }

    public static final ColorRGB4 BLACK = fromRGB4(0, 0, 0);
    public static final ColorRGB4 WHITE = fromRGB4(15, 15, 15);
    public static final ColorRGB4 RED = fromRGB4(15, 0, 0);
    public static final ColorRGB4 GREEN = fromRGB4(0, 15, 0);
    public static final ColorRGB4 BLUE = fromRGB4(0, 0, 15);
    /**
     * Blacklight (2.11.0): ultraviolet, written {@code "uv"} where a colour is. The one colour that is not in the table: it
     * is told by identity ({@code color == ColorRGB4.UV}), and what is seen of it is this dim violet. Any arithmetic on it
     * gives an ordinary colour of the table, so only a config's own colour objects and a dynamic light's colour stay it.
     */
    public static final ColorRGB4 UV = new ColorRGB4(3, 1, 8);

    /**
     * A dynamic light that takes light away (2.12.0, {@code DynamicLightSink.ABSORB}): its channels say how much of each it
     * absorbs. Told by {@link #absorbs}; as with {@link #UV}, any arithmetic on one gives an ordinary colour of the table.
     */
    private static final ColorRGB4[] ABSORBING = new ColorRGB4[4096];
    static {
        for (int i = 0; i < 4096; ++i) {
            ABSORBING[i] = new ColorRGB4((i >>> 8) & 0xF, (i >>> 4) & 0xF, i & 0xF, true);
        }
    }

    public final int red4, green4, blue4;
    /** Whether this is the colour of a light that absorbs light. */
    public final boolean absorbs;

    public static ColorRGB4 absorbing(int r8, int g8, int b8) {
        return ABSORBING[((r8 >> 4) & 15) << 8 | ((g8 >> 4) & 15) << 4 | ((b8 >> 4) & 15)];
    }

    public static ColorRGB4 fromRGB8(int r, int g, int b) {
        return fromRGB4(r >> 4, g >> 4, b >> 4); // 0..255 range to 0..15 range
    }

    public static ColorRGB4 fromRGB4(int r, int g, int b) {
        // clamp instead of masking: masking would silently wrap out-of-range inputs to a wrong hue
        if (r < 0) r = 0; else if (r > 15) r = 15;
        if (g < 0) g = 0; else if (g > 15) g = 15;
        if (b < 0) b = 0; else if (b > 15) b = 15;
        return TABLE[r << 8 | g << 4 | b];
    }

    private ColorRGB4(int r4, int g4, int b4) {
        this(r4, g4, b4, false);
    }

    private ColorRGB4(int r4, int g4, int b4, boolean absorbing) {
        red4 = r4;
        green4 = g4;
        blue4 = b4;
        absorbs = absorbing;
    }

    public boolean isInValidState() {
        return  red4 >= 0 && red4 < 16 &&
                green4 >= 0 && green4 < 16 &&
                blue4 >= 0 && blue4 < 16;
    }

    public ColorRGB4 mul(float scalar) {
        return fromRGB4((int)(red4 * scalar), (int)(green4 * scalar), (int)(blue4 * scalar));
    }

    public boolean isBlack() {
        return red4 == 0 && green4 == 0 && blue4 == 0;
    }

    public static ColorRGB4 max(ColorRGB4 a, ColorRGB4 b) {
        return fromRGB4(
                Math.max(a.red4, b.red4),
                Math.max(a.green4, b.green4),
                Math.max(a.blue4, b.blue4)
        );
    }

    public static ColorRGB4 min(ColorRGB4 a, ColorRGB4 b) {
        return fromRGB4(
                Math.min(a.red4, b.red4),
                Math.min(a.green4, b.green4),
                Math.min(a.blue4, b.blue4)
        );
    }

    /** Per-channel multiply with b treated as a 0..1 factor (15 = identity), rounded. */
    public static ColorRGB4 mul(ColorRGB4 a, ColorRGB4 b) {
        return fromRGB4(
                (a.red4 * b.red4 + 7) / 15,
                (a.green4 * b.green4 + 7) / 15,
                (a.blue4 * b.blue4 + 7) / 15
        );
    }

    /** Blends toward white: strength 1 = the color itself, 0 = white. */
    public static ColorRGB4 towardWhite(ColorRGB4 color, float strength) {
        return fromRGB4(
                Math.round(15 - (15 - color.red4) * strength),
                Math.round(15 - (15 - color.green4) * strength),
                Math.round(15 - (15 - color.blue4) * strength)
        );
    }

    @Override
    public String toString() {
        return "ColorRGB4["+ red4 +", " + green4 + ", " + blue4 + "]";
    }

    @Override
    public int hashCode() {
        return red4 << 8 | green4 << 4 | blue4;
    }

    @Override
    public boolean equals(Object obj) {
        if(this == obj) return true;
        return  obj instanceof ColorRGB4 other &&
                other.red4 == red4 &&
                other.green4 == green4 &&
                other.blue4 == blue4;
    }
}
