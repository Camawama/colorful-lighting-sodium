package net.camacraft.colorfullighting.bloom;

import com.mojang.blaze3d.systems.RenderSystem;
import net.camacraft.colorfullighting.api.dynamic.DynamicLightSink;
import net.camacraft.colorfullighting.compat.dynamiclights.DynamicLightsCompat;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * The bloom's light sources that are items (2.12.0): a glowstone in the hand, a torch in an item frame, a lantern on the
 * ground or in a mob's hand. The bloom finds light sources by the block a pixel shows, and an item is no block. Whenever the
 * game draws an item that gives light (as Colorful Lighting knows items to: a block item whose block emits, an item with a
 * colour of its own in the light tables), its place and size as drawn are noted here: the middle of its model through the
 * pose it is drawn with, and half its size as a radius. The first pass takes a bright pixel within such a ball for a light
 * source. Items in the world are noted while the level is drawn; the hand is drawn after the level, over a cleared depth, so
 * what it holds is noted apart and gets a pass of its own after the hand, and only in frames where the hand holds a light.
 *
 * <p>An item that gives no light costs one lookup by its type when it is drawn; nothing is noted while the bloom is off.
 */
public final class ItemGlows {
    public static final int MAX = 12;

    /** A ball a slot: x, y, z in the space the item was drawn in (the camera's, turned as the view), and its radius. */
    private static final float[] levelPos = new float[MAX * 4], handPos = new float[MAX * 4];
    /** Red, green, blue of the item's light (0..1) and what the first pass takes for a cell's alpha (its level and lamp bit over 15). */
    private static final float[] levelCol = new float[MAX * 4], handCol = new float[MAX * 4];
    private static int levelCount, handCount;
    private static int levelDepth;
    private static boolean inHand;
    private static final Matrix4f HAND_PROJECTION = new Matrix4f();

    /** What an item type is to the bloom: 0 for no light, else red, green, blue in four bits each and the alpha nibble. */
    private static final Map<Item, Integer> VALUES = new IdentityHashMap<>();
    private static final Vector3f SCRATCH = new Vector3f();

    private ItemGlows() {}

    public static void levelStart() {
        if (levelDepth++ == 0) levelCount = 0;
    }

    public static void levelEnd() {
        if (levelDepth > 0) levelDepth--;
    }

    public static void handStart() {
        inHand = true;
        handCount = 0;
    }

    public static void handEnd() {
        inHand = false;
    }

    /** The light tables changed (a resource reload): item types are looked at again. */
    public static void forget() {
        VALUES.clear();
    }

    /** An item's model is about to be drawn with this pose (the model fills the unit cube). Render thread. */
    public static void drawn(ItemStack stack, Matrix4f pose) {
        if ((levelDepth == 0 && !inHand) || stack == null || stack.isEmpty() || !RenderSystem.isOnRenderThread() || !Bloom.active()) return;
        int value = valueOf(stack);
        if (value == 0) return;
        boolean hand = inHand;
        int n = hand ? handCount : levelCount;
        if (n >= MAX) return;
        float[] pos = hand ? handPos : levelPos, col = hand ? handCol : levelCol;
        pose.transformPosition(0.5f, 0.5f, 0.5f, SCRATCH);
        float scale = (float) Math.sqrt(pose.m00() * pose.m00() + pose.m01() * pose.m01() + pose.m02() * pose.m02());
        int o = n * 4;
        pos[o] = SCRATCH.x;
        pos[o + 1] = SCRATCH.y;
        pos[o + 2] = SCRATCH.z;
        pos[o + 3] = Math.max(0.05f, scale * 0.6f);
        col[o] = ((value >> 12) & 15) / 15f;
        col[o + 1] = ((value >> 8) & 15) / 15f;
        col[o + 2] = ((value >> 4) & 15) / 15f;
        col[o + 3] = (value & 15) / 15f;
        if (hand) {
            if (handCount == 0) HAND_PROJECTION.set(RenderSystem.getProjectionMatrix());
            handCount = n + 1;
        } else {
            levelCount = n + 1;
        }
    }

    private static int valueOf(ItemStack stack) {
        Item item = stack.getItem();
        boolean plain = !stack.hasTag();
        if (plain) {
            Integer known = VALUES.get(item);
            if (known != null) return known;
        }
        int value = 0;
        try {
            if (item instanceof BlockItem blockItem) {
                short cell = EmitterVolume.valueOf(blockItem.getBlock().defaultBlockState());
                if (EmitterVolume.isEmitter(cell)) value = cell & 0xFFFF;
            }
            if (value == 0) {
                int rgb = DynamicLightsCompat.itemLightColor(stack);
                if (rgb >= 0 && rgb != DynamicLightSink.BLACKLIGHT && (rgb & DynamicLightSink.ABSORB) == 0) {
                    value = ((rgb >> 20) & 15) << 12 | ((rgb >> 12) & 15) << 8 | ((rgb >> 4) & 15) << 4 | 7;
                }
            }
        } catch (RuntimeException e) {
            value = 0;
        }
        if (plain) VALUES.put(item, value);
        return value;
    }

    /**
     * The items drawn in the level this frame, for the first pass: their places turned from the view's space to the world's
     * axes (still relative to the camera). Returns how many.
     */
    static int level(Matrix4f view, float[] posOut, float[] colOut) {
        if (levelCount == 0) return 0;
        Matrix4f back = new Matrix4f(view).invert();
        for (int i = 0; i < levelCount; i++) {
            int o = i * 4;
            back.transformPosition(levelPos[o], levelPos[o + 1], levelPos[o + 2], SCRATCH);
            posOut[o] = SCRATCH.x;
            posOut[o + 1] = SCRATCH.y;
            posOut[o + 2] = SCRATCH.z;
            posOut[o + 3] = levelPos[o + 3];
        }
        System.arraycopy(levelCol, 0, colOut, 0, levelCount * 4);
        return levelCount;
    }

    /** The items the hand holds this frame, in the hand's own space. */
    static int hand(float[] posOut, float[] colOut) {
        System.arraycopy(handPos, 0, posOut, 0, handCount * 4);
        System.arraycopy(handCol, 0, colOut, 0, handCount * 4);
        return handCount;
    }

    public static int handCount() {
        return handCount;
    }

    public static int levelCount() {
        return levelCount;
    }

    static Matrix4f handProjection() {
        return HAND_PROJECTION;
    }
}
