package net.camacraft.colorfullighting.common.util;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The state a block has when it is set down with nothing round it (2.14.0): what its item stands for in the hand. A lamp
 * that needs redstone, an unlit candle or a furnace is dark then, whatever its block's default state says. A block's
 * default state is not always that: a block that never names one gets the first of its states, and the first value of a
 * true-or-false property is true (Quark's crystal lamps: "lit" by default, dark when placed without power).
 *
 * <p>Only a block whose states differ in the light they give is asked, once: its own placement rule, at a place below the
 * world where every neighbour is void air (no chunk is touched, nothing powers it, no water is there). A block that cannot
 * answer keeps its default state. Client thread or render thread.
 */
public final class PlacedState {
    private static final Map<Block, BlockState> KNOWN = new ConcurrentHashMap<>();

    private PlacedState() {}

    public static BlockState of(Block block) {
        BlockState known = KNOWN.get(block);
        if (known != null) return known;
        BlockState state = block.defaultBlockState();
        if (!lightDiffers(block, state)) {
            KNOWN.put(block, state);
            return state;
        }
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        // outside a world there is nowhere to ask: the default state for now, asked again later
        if (level == null || mc.player == null) return state;
        try {
            BlockPos pos = new BlockPos(0, level.getMinBuildHeight() - 64, 0);
            BlockPlaceContext context = new BlockPlaceContext(level, mc.player, InteractionHand.MAIN_HAND, new ItemStack(block),
                    new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
            BlockState placed = block.getStateForPlacement(context);
            if (placed != null && placed.getBlock() == block) state = placed;
        } catch (Throwable t) {
            // a placement rule that needs more than this: the default state
        }
        KNOWN.put(block, state);
        return state;
    }

    private static boolean lightDiffers(Block block, BlockState state) {
        int light = state.getLightEmission();
        for (BlockState other : block.getStateDefinition().getPossibleStates()) {
            if (other.getLightEmission() != light) return true;
        }
        return false;
    }

    /** The answers are forgotten (a resource reload: another mod's config may change what a block gives). */
    public static void forget() {
        KNOWN.clear();
    }
}
