package net.camacraft.colorfullighting.compat.flywheel;

import net.camacraft.colorfullighting.common.ColoredLightEngine;
import net.camacraft.colorfullighting.common.accessors.mixin.LevelAttachments;
import net.camacraft.colorfullighting.common.util.ColorRGB4;
import net.camacraft.colorfullighting.common.util.ColorRGB8;
import net.camacraft.colorfullighting.common.util.PackedLightData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LevelAccessor;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

public class SlowLightCollector {
    /**
     * The engine of the level this collector's storage belongs to, or null when the level has
     * none. Every Level gets its engine in the Level constructor (LevelMixin.postInit), long
     * before flywheel can build a LightStorage on it, so resolving once here is safe; a
     * LevelAccessor that is not a Level (no LevelAttachments) has no engine and stays null.
     */
    @Nullable
    private final ColoredLightEngine engine;

    public SlowLightCollector(@Nullable LevelAccessor level) {
        this.engine = level instanceof LevelAttachments attachments ? attachments.colorfullighting$getEngine() : null;
    }

    protected void collectLightData(long ptr, long section) {
        // No engine for this level, or the engine is disabled: leave the section zeroed (alpha
        // nibble 0) — the flywheel shaders fall back to the vanilla per-instance lightmap for
        // entries without the colored magic bits. Writing packed black instead would override
        // vanilla block light with darkness on everything flywheel renders. Sampling only this
        // level's engine is what keeps a Nether portal rendered by Immersive Portals from
        // painting Overworld colors onto Nether-side flywheel objects and vice versa.
        if (engine == null || !ColoredLightEngine.isEnabled()) return;

        // the whole 18x18x18 box at once (each section the box touches looked up once), then the same packed
        // value per block that write(..., engine.sampleLightColor(pos)) gave: alpha 15, RGB4 scaled to RGB8
        engine.sampleSectionBoxPacked(section, box);
        for (int i = 0; i < BOX_BLOCKS; i++) {
            int rgb4 = box[i];
            MemoryUtil.memPutInt(ptr + (long) i * 4, (rgb4 >>> 8 & 0x0F) * 17 | ((rgb4 >>> 4 & 0x0F) * 17) << 8
                    | ((rgb4 & 0x0F) * 17) << 20 | 15 << 28);
        }
    }

    private static final int BOX_BLOCKS = ColoredLightEngine.SECTION_BOX * ColoredLightEngine.SECTION_BOX * ColoredLightEngine.SECTION_BOX;
    /** The box being written (render thread: collections run at upload, see ColoredLightFlywheelStorage). */
    private final int[] box = new int[BOX_BLOCKS];

    protected static void write(long ptr, int x, int y, int z, ColorRGB4 color) {
        int x1 = x + 1;
        int y1 = y + 1;
        int z1 = z + 1;

        int offset = (x1 + z1 * 18 + y1 * 18 * 18) * 4;

        MemoryUtil.memPutInt(ptr + offset, PackedLightData.packData(0, ColorRGB8.fromRGB4(color)));
    }
}
