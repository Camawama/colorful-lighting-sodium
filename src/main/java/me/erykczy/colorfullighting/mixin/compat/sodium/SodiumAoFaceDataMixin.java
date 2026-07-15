package me.erykczy.colorfullighting.mixin.compat.sodium;

import me.erykczy.colorfullighting.ColorfulLighting;
import me.erykczy.colorfullighting.accessors.BlockStateWrapper;
import me.erykczy.colorfullighting.common.ColoredLightEngine;
import me.erykczy.colorfullighting.common.Config;
import me.erykczy.colorfullighting.common.accessors.BlockStateAccessor;
import me.erykczy.colorfullighting.common.accessors.LevelAccessor;
import me.erykczy.colorfullighting.common.accessors.mixin.LevelAttachments;
import me.erykczy.colorfullighting.common.util.ColorRGB8;
import me.erykczy.colorfullighting.compat.sodium.SodiumAoFaceDataExtension;
import me.erykczy.colorfullighting.compat.sodium.SodiumPackedLightData;
import me.jellysquid.mods.sodium.client.model.light.data.LightDataAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "me.jellysquid.mods.sodium.client.model.light.smooth.AoFaceData", remap = false)
public abstract class SodiumAoFaceDataMixin implements SodiumAoFaceDataExtension {

    @Shadow public final int[] lm = new int[4];
    @Shadow public final float[] ao = new float[4];
    @Shadow public final float[] bl = new float[4];
    @Shadow public final float[] sl = new float[4];
    @Shadow private int flags;

    @Unique
    public final float[] gl = new float[4];
    @Unique
    public final float[] bll = new float[4];

    @Shadow public abstract boolean hasUnpackedLightData();
    @Shadow public abstract boolean hasLightData();

    /**
     * Called ten times per block face, so it must neither allocate nor touch the block palette on the
     * common path: the emissive branch is the only one that needs a BlockPos or a BlockState.
     */
    @Unique
    private int getBaseColoredLight(LightDataAccess cache, ColoredLightEngine.SectionCursor cursor, int x, int y, int z) {
	    BlockAndTintGetter level = cache.getWorld();
        ColoredLightEngine engine = ((LevelAttachments) level).colorfullighting$getEngine();
        int word = cache.get(x, y, z);

        if (!ColoredLightEngine.isEnabled()) {
            if (LightDataAccess.unpackEM(word)) {
                return 0xF000F0;
            }
            return LightDataAccess.getLightmap(word);
        }

        int skyLight = LightDataAccess.unpackSL(word);

        if (LightDataAccess.unpackEM(word)) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(pos);
            LevelAccessor levelAccessor = ColorfulLighting.clientAccessor.getLevel();
            if(levelAccessor != null) {
                BlockStateAccessor stateAccessor = new BlockStateWrapper(state);

                var emission = Config.getLightColor(stateAccessor);
                if (!emission.equals(Config.defaultColor)) {
                    return SodiumPackedLightData.packData(skyLight, ColorRGB8.fromRGB4(emission));
                }
            }
        }
        return SodiumPackedLightData.packDataFromRGB4(skyLight, engine.sampleLightColorPacked(cursor, x, y, z));
    }

    @Unique
    private int getFilteredNeighborLight(LightDataAccess cache, ColoredLightEngine.SectionCursor cursor, int x, int y, int z) {
        // Removed aggressive filtering logic to fix sharp lines at block edges.
        // This allows the corner light to properly blend with neighbors, even if they are bright light sources.
        return getBaseColoredLight(cache, cursor, x, y, z);
    }

    @Unique
    private static final Direction[][] NEIGHBOR_FACES = new Direction[6][];
    static {
        NEIGHBOR_FACES[0] = new Direction[] { Direction.WEST, Direction.EAST, Direction.NORTH, Direction.SOUTH };
        NEIGHBOR_FACES[1] = new Direction[] { Direction.EAST, Direction.WEST, Direction.NORTH, Direction.SOUTH };
        NEIGHBOR_FACES[2] = new Direction[] { Direction.UP, Direction.DOWN, Direction.EAST, Direction.WEST };
        NEIGHBOR_FACES[3] = new Direction[] { Direction.WEST, Direction.EAST, Direction.DOWN, Direction.UP };
        NEIGHBOR_FACES[4] = new Direction[] { Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH };
        NEIGHBOR_FACES[5] = new Direction[] { Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH };
    }

    @Unique
    private static int blendChannel(int a, int b, int c, int d) {
        if ((a == 0) || (b == 0) || (c == 0) || (d == 0)) {
            final int min = minNonZero(minNonZero(a, b), minNonZero(c, d));
            a = Math.max(a, min);
            b = Math.max(b, min);
            c = Math.max(c, min);
            d = Math.max(d, min);
        }
        return (a + b + c + d) >> 2;
    }

    @Unique
    private static int minNonZero(int a, int b) {
        if (a == 0) return b;
        if (b == 0) return a;
        return Math.min(a, b);
    }

    @Unique
    private static int blend(int a, int b, int c, int d) {
        if (!ColoredLightEngine.isEnabled()) {
            int sl_a = (a >> 16) & 0xFF;
            int sl_b = (b >> 16) & 0xFF;
            int sl_c = (c >> 16) & 0xFF;
            int sl_d = (d >> 16) & 0xFF;

            int bl_a = a & 0xFF;
            int bl_b = b & 0xFF;
            int bl_c = c & 0xFF;
            int bl_d = d & 0xFF;

            int sl_avg = blendChannel(sl_a, sl_b, sl_c, sl_d);
            int bl_avg = blendChannel(bl_a, bl_b, bl_c, bl_d);

            return (sl_avg << 16) | bl_avg;
        }

        final int ra = SodiumPackedLightData.unpackRed(a), ga = SodiumPackedLightData.unpackGreen(a), ba = SodiumPackedLightData.unpackBlue(a);
        final int rb = SodiumPackedLightData.unpackRed(b), gb = SodiumPackedLightData.unpackGreen(b), bb = SodiumPackedLightData.unpackBlue(b);
        final int rc = SodiumPackedLightData.unpackRed(c), gc = SodiumPackedLightData.unpackGreen(c), bc = SodiumPackedLightData.unpackBlue(c);
        final int rd = SodiumPackedLightData.unpackRed(d), gd = SodiumPackedLightData.unpackGreen(d), bd = SodiumPackedLightData.unpackBlue(d);

        int sky = blendChannel(
                SodiumPackedLightData.unpackSkyLight(a), SodiumPackedLightData.unpackSkyLight(b),
                SodiumPackedLightData.unpackSkyLight(c), SodiumPackedLightData.unpackSkyLight(d)
        );

        int lum_a = Math.max(ra, Math.max(ga, ba));
        int lum_b = Math.max(rb, Math.max(gb, bb));
        int lum_c = Math.max(rc, Math.max(gc, bc));
        int lum_d = Math.max(rd, Math.max(gd, bd));

        // Colour of the dimmest lit corner, substituted into the unlit ones so an unlit corner does not
        // drag the blend towards black. Staying zero when every corner is unlit reproduces the old
        // `minData == null` fallback without needing the object.
        int minLum = 256;
        int minR = 0, minG = 0, minB = 0;

        if (lum_a > 0 && lum_a < minLum) { minLum = lum_a; minR = ra; minG = ga; minB = ba; }
        if (lum_b > 0 && lum_b < minLum) { minLum = lum_b; minR = rb; minG = gb; minB = bb; }
        if (lum_c > 0 && lum_c < minLum) { minLum = lum_c; minR = rc; minG = gc; minB = bc; }
        if (lum_d > 0 && lum_d < minLum) { minR = rd; minG = gd; minB = bd; }

        int r_a = lum_a > 0 ? ra : minR;
        int g_a = lum_a > 0 ? ga : minG;
        int b_a = lum_a > 0 ? ba : minB;

        int r_b = lum_b > 0 ? rb : minR;
        int g_b = lum_b > 0 ? gb : minG;
        int b_b = lum_b > 0 ? bb : minB;

        int r_c = lum_c > 0 ? rc : minR;
        int g_c = lum_c > 0 ? gc : minG;
        int b_c = lum_c > 0 ? bc : minB;

        int r_d = lum_d > 0 ? rd : minR;
        int g_d = lum_d > 0 ? gd : minG;
        int b_d = lum_d > 0 ? bd : minB;

        int red = (r_a + r_b + r_c + r_d) >> 2;
        int green = (g_a + g_b + g_c + g_d) >> 2;
        int blue = (b_a + b_b + b_c + b_d) >> 2;

        return SodiumPackedLightData.packData(sky, red, green, blue);
    }

    /**
     * @author Erykczy
     * @reason Inject colored lighting logic into AO calculation
     */
    @Inject(method = "initLightData", at = @At("RETURN"))
    public void colorfullighting$initLightData(LightDataAccess cache, BlockPos pos, Direction direction, boolean offset, CallbackInfo ci) {
        if (!ColoredLightEngine.isEnabled()) {
            return;
        }

        final int x = pos.getX();
        final int y = pos.getY();
        final int z = pos.getZ();

        // One ThreadLocal lookup for the ten samples below, instead of one each.
	    BlockAndTintGetter world = cache.getWorld();
        final ColoredLightEngine.SectionCursor cursor = ((LevelAttachments) world).colorfullighting$getEngine().acquireCursor();

        final int adjX;
        final int adjY;
        final int adjZ;

        if (offset) {
            adjX = x + direction.getStepX();
            adjY = y + direction.getStepY();
            adjZ = z + direction.getStepZ();
        } else {
            adjX = x;
            adjY = y;
            adjZ = z;
        }

        final int adjWord = cache.get(adjX, adjY, adjZ);

        Direction[] faces = NEIGHBOR_FACES[direction.get3DDataValue()];

        final int e0 = cache.get(adjX, adjY, adjZ, faces[0]);
        final int e0lm = getFilteredNeighborLight(cache, cursor, adjX + faces[0].getStepX(), adjY + faces[0].getStepY(), adjZ + faces[0].getStepZ());
        final boolean e0op = LightDataAccess.unpackOP(e0);

        final int e1 = cache.get(adjX, adjY, adjZ, faces[1]);
        final int e1lm = getFilteredNeighborLight(cache, cursor, adjX + faces[1].getStepX(), adjY + faces[1].getStepY(), adjZ + faces[1].getStepZ());
        final boolean e1op = LightDataAccess.unpackOP(e1);

        final int e2 = cache.get(adjX, adjY, adjZ, faces[2]);
        final int e2lm = getFilteredNeighborLight(cache, cursor, adjX + faces[2].getStepX(), adjY + faces[2].getStepY(), adjZ + faces[2].getStepZ());
        final boolean e2op = LightDataAccess.unpackOP(e2);

        final int e3 = cache.get(adjX, adjY, adjZ, faces[3]);
        final int e3lm = getFilteredNeighborLight(cache, cursor, adjX + faces[3].getStepX(), adjY + faces[3].getStepY(), adjZ + faces[3].getStepZ());
        final boolean e3op = LightDataAccess.unpackOP(e3);

        final int c0lm;
        if (e2op && e0op) {
            c0lm = e0lm;
        } else {
            c0lm = getFilteredNeighborLight(cache, cursor, adjX + faces[0].getStepX() + faces[2].getStepX(), adjY + faces[0].getStepY() + faces[2].getStepY(), adjZ + faces[0].getStepZ() + faces[2].getStepZ());
        }

        final int c1lm;
        if (e3op && e0op) {
            c1lm = e0lm;
        } else {
            c1lm = getFilteredNeighborLight(cache, cursor, adjX + faces[0].getStepX() + faces[3].getStepX(), adjY + faces[0].getStepY() + faces[3].getStepY(), adjZ + faces[0].getStepZ() + faces[3].getStepZ());
        }

        final int c2lm;
        if (e2op && e1op) {
            c2lm = e1lm;
        } else {
            c2lm = getFilteredNeighborLight(cache, cursor, adjX + faces[1].getStepX() + faces[2].getStepX(), adjY + faces[1].getStepY() + faces[2].getStepY(), adjZ + faces[1].getStepZ() + faces[2].getStepZ());
        }

        final int c3lm;
        if (e3op && e1op) {
            c3lm = e1lm;
        } else {
            c3lm = getFilteredNeighborLight(cache, cursor, adjX + faces[1].getStepX() + faces[3].getStepX(), adjY + faces[1].getStepY() + faces[3].getStepY(), adjZ + faces[1].getStepZ() + faces[3].getStepZ());
        }

        int[] cb = this.lm;

        final int calm;

        if (offset && LightDataAccess.unpackFO(adjWord)) {
            calm = getFilteredNeighborLight(cache, cursor, x, y, z);
        } else {
            calm = getFilteredNeighborLight(cache, cursor, adjX, adjY, adjZ);
        }

        cb[0] = blend(e3lm, e0lm, c1lm, calm);
        cb[1] = blend(e2lm, e0lm, c0lm, calm);
        cb[2] = blend(e2lm, e1lm, c2lm, calm);
        cb[3] = blend(e3lm, e1lm, c3lm, calm);
    }

    /**
     * @author Erykczy
     * @reason Unpack colored light data
     */
    @Overwrite
    public void unpackLightData() {
        int[] lm = this.lm;

        if (!ColoredLightEngine.isEnabled()) {
            // Replicate vanilla/Sodium logic
            float[] bl = this.bl;
            float[] sl = this.sl;
            
            for(int i=0; i<4; i++) {
                int l = lm[i];
                bl[i] = (float)(l & 0xFF) / 255.0F;
                sl[i] = (float)((l >> 16) & 0xFF) / 255.0F;
            }
            this.flags |= 2;
            return;
        }

        float[] bl = this.bl;
        float[] sl = this.sl;
        float[] gl = this.gl;
        float[] bll = this.bll;

        for(int i=0; i<4; i++) {
            int l = lm[i];
            bl[i] = SodiumPackedLightData.unpackRed(l);
            gl[i] = SodiumPackedLightData.unpackGreen(l);
            bll[i] = SodiumPackedLightData.unpackBlue(l);
            sl[i] = SodiumPackedLightData.unpackSkyLight(l);
        }

        this.flags |= 2;
    }

    @Override
    public void ensureUnpacked() {
        if (!this.hasUnpackedLightData()) {
            this.unpackLightData();
        }
    }

    @Override
    public int getBlendedLightMap(float[] w) {
        ensureUnpacked();
        if (!ColoredLightEngine.isEnabled()) {
            // Replicate vanilla/Sodium logic
            float r = weightedSum(this.bl, w);
            float s = weightedSum(this.sl, w);
            int ir = (int)(r * 255.0F);
            int is = (int)(s * 255.0F);
            return ir | (is << 16);
        }

        float r = weightedSum(this.bl, w);
        float g = weightedSum(this.gl, w);
        float b = weightedSum(this.bll, w);
        float s = weightedSum(this.sl, w);

        return SodiumPackedLightData.packData((int)s, (int)r, (int)g, (int)b);
    }

    @Override
    public float getBlendedShade(float[] w) {
        ensureUnpacked();
        return weightedSum(this.ao, w);
    }

    @Override
    public float getBlendedRed(float[] w) {
        ensureUnpacked();
        return weightedSum(this.bl, w);
    }

    @Override
    public float getBlendedGreen(float[] w) {
        ensureUnpacked();
        return weightedSum(this.gl, w);
    }

    @Override
    public float getBlendedBlue(float[] w) {
        ensureUnpacked();
        return weightedSum(this.bll, w);
    }

    @Override
    public float getBlendedSky(float[] w) {
        ensureUnpacked();
        return weightedSum(this.sl, w);
    }

    @Unique
    private static float weightedSum(float[] v, float[] w) {
        float t0 = v[0] * w[0];
        float t1 = v[1] * w[1];
        float t2 = v[2] * w[2];
        float t3 = v[3] * w[3];

        return t0 + t1 + t2 + t3;
    }
}