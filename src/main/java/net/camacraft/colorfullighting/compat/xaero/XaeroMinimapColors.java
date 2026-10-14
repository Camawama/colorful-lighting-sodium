package net.camacraft.colorfullighting.compat.xaero;

/**
 * Counts of the minimap compat's work (XaeroMinimapWriterMixin), for checking it in a running game (read by
 * reflection; "/cl debug" style tooling and the pack harness): how many minimap channel values got the light's hue.
 */
public final class XaeroMinimapColors {
    /** Minimap pixel channels (one per sky level and color channel) whose value the light's hue changed. */
    public static volatile long tintedChannels;
    /** The last such change: level, channel, Xaero's value and ours. */
    public static volatile String lastTint = "";

    private XaeroMinimapColors() {
    }

    public static void tinted(int level, int shift, int before, int after) {
        tintedChannels++;
        lastTint = "level " + level + " " + (shift == 8 ? "r" : shift == 4 ? "g" : "b") + " " + before + "->" + after;
    }
}
