package me.erykczy.colorfullighting.mixin.compat.sodium;

import me.erykczy.colorfullighting.ColorfulLighting;
import me.erykczy.colorfullighting.accessors.BlockStateWrapper;
import me.erykczy.colorfullighting.common.ColoredLightEngine;
import me.erykczy.colorfullighting.common.Config;
import me.erykczy.colorfullighting.common.accessors.BlockStateAccessor;
import me.erykczy.colorfullighting.common.accessors.LevelAccessor;
import me.erykczy.colorfullighting.common.util.ColorRGB4;
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

    @Unique
    private int getBaseColoredLight(LightDataAccess cache, int x, int y, int z) {
        if (!ColoredLightEngine.getInstance().isEnabled()) {
            int word = cache.get(x, y, z);
            if (LightDataAccess.unpackEM(word)) {
                return 0xF000F0;
            }
            return LightDataAccess.getLightmap(word);
        }

        BlockPos pos = new BlockPos(x, y, z);
        ColorRGB4 color = ColoredLightEngine.getInstance().sampleLightColor(pos);
        int word = cache.get(x, y, z);
        int skyLight = LightDataAccess.unpackSL(word);
        
        if (LightDataAccess.unpackEM(word)) {
            BlockAndTintGetter level = cache.getWorld();
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
        return SodiumPackedLightData.packData(skyLight, ColorRGB8.fromRGB4(color));
    }

    @Unique
    private int getFilteredNeighborLight(LightDataAccess cache, int x, int y, int z, BlockState centerState, int centerLight) {
        if (!ColoredLightEngine.getInstance().isEnabled()) {
            // Replicate vanilla/Sodium logic
            int word = cache.get(x, y, z);
            if (LightDataAccess.unpackEM(word)) {
                return 0xF000F0;
            }
            return LightDataAccess.getLightmap(word);
        }

        // Removed aggressive filtering logic to fix sharp lines at block edges.
        // This allows the corner light to properly blend with neighbors, even if they are bright light sources.
        return getBaseColoredLight(cache, x, y, z);
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
        if (!ColoredLightEngine.getInstance().isEnabled()) {
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

        var da = SodiumPackedLightData.unpackData(a);
        var db = SodiumPackedLightData.unpackData(b);
        var dc = SodiumPackedLightData.unpackData(c);
        var dd = SodiumPackedLightData.unpackData(d);

        int sky = blendChannel(da.skyLight4, db.skyLight4, dc.skyLight4, dd.skyLight4);

        int lum_a = Math.max(da.red8, Math.max(da.green8, da.blue8));
        int lum_b = Math.max(db.red8, Math.max(db.green8, db.blue8));
        int lum_c = Math.max(dc.red8, Math.max(dc.green8, dc.blue8));
        int lum_d = Math.max(dd.red8, Math.max(dd.green8, dd.blue8));

        int minLum = 256;
        SodiumPackedLightData minData = null;

        if (lum_a > 0 && lum_a < minLum) { minLum = lum_a; minData = da; }
        if (lum_b > 0 && lum_b < minLum) { minLum = lum_b; minData = db; }
        if (lum_c > 0 && lum_c < minLum) { minLum = lum_c; minData = dc; }
        if (lum_d > 0 && lum_d < minLum) { minLum = lum_d; minData = dd; }

        int r_a = lum_a > 0 ? da.red8 : (minData != null ? minData.red8 : 0);
        int g_a = lum_a > 0 ? da.green8 : (minData != null ? minData.green8 : 0);
        int b_a = lum_a > 0 ? da.blue8 : (minData != null ? minData.blue8 : 0);

        int r_b = lum_b > 0 ? db.red8 : (minData != null ? minData.red8 : 0);
        int g_b = lum_b > 0 ? db.green8 : (minData != null ? minData.green8 : 0);
        int b_b = lum_b > 0 ? db.blue8 : (minData != null ? minData.blue8 : 0);

        int r_c = lum_c > 0 ? dc.red8 : (minData != null ? minData.red8 : 0);
        int g_c = lum_c > 0 ? dc.green8 : (minData != null ? minData.green8 : 0);
        int b_c = lum_c > 0 ? dc.blue8 : (minData != null ? minData.blue8 : 0);

        int r_d = lum_d > 0 ? dd.red8 : (minData != null ? minData.red8 : 0);
        int g_d = lum_d > 0 ? dd.green8 : (minData != null ? minData.green8 : 0);
        int b_d = lum_d > 0 ? dd.blue8 : (minData != null ? minData.blue8 : 0);

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
        if (!ColoredLightEngine.getInstance().isEnabled()) {
            return;
        }

        final int x = pos.getX();
        final int y = pos.getY();
        final int z = pos.getZ();

        final BlockState centerState = cache.getWorld().getBlockState(pos);
        final int centerLight = getBaseColoredLight(cache, x, y, z);

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
        final int e0lm = getFilteredNeighborLight(cache, adjX + faces[0].getStepX(), adjY + faces[0].getStepY(), adjZ + faces[0].getStepZ(), centerState, centerLight);
        final boolean e0op = LightDataAccess.unpackOP(e0);

        final int e1 = cache.get(adjX, adjY, adjZ, faces[1]);
        final int e1lm = getFilteredNeighborLight(cache, adjX + faces[1].getStepX(), adjY + faces[1].getStepY(), adjZ + faces[1].getStepZ(), centerState, centerLight);
        final boolean e1op = LightDataAccess.unpackOP(e1);

        final int e2 = cache.get(adjX, adjY, adjZ, faces[2]);
        final int e2lm = getFilteredNeighborLight(cache, adjX + faces[2].getStepX(), adjY + faces[2].getStepY(), adjZ + faces[2].getStepZ(), centerState, centerLight);
        final boolean e2op = LightDataAccess.unpackOP(e2);

        final int e3 = cache.get(adjX, adjY, adjZ, faces[3]);
        final int e3lm = getFilteredNeighborLight(cache, adjX + faces[3].getStepX(), adjY + faces[3].getStepY(), adjZ + faces[3].getStepZ(), centerState, centerLight);
        final boolean e3op = LightDataAccess.unpackOP(e3);

        final int c0lm;
        if (e2op && e0op) {
            c0lm = e0lm;
        } else {
            c0lm = getFilteredNeighborLight(cache, adjX + faces[0].getStepX() + faces[2].getStepX(), adjY + faces[0].getStepY() + faces[2].getStepY(), adjZ + faces[0].getStepZ() + faces[2].getStepZ(), centerState, centerLight);
        }

        final int c1lm;
        if (e3op && e0op) {
            c1lm = e0lm;
        } else {
            c1lm = getFilteredNeighborLight(cache, adjX + faces[0].getStepX() + faces[3].getStepX(), adjY + faces[0].getStepY() + faces[3].getStepY(), adjZ + faces[0].getStepZ() + faces[3].getStepZ(), centerState, centerLight);
        }

        final int c2lm;
        if (e2op && e1op) {
            c2lm = e1lm;
        } else {
            c2lm = getFilteredNeighborLight(cache, adjX + faces[1].getStepX() + faces[2].getStepX(), adjY + faces[1].getStepY() + faces[2].getStepY(), adjZ + faces[1].getStepZ() + faces[2].getStepZ(), centerState, centerLight);
        }

        final int c3lm;
        if (e3op && e1op) {
            c3lm = e1lm;
        } else {
            c3lm = getFilteredNeighborLight(cache, adjX + faces[1].getStepX() + faces[3].getStepX(), adjY + faces[1].getStepY() + faces[3].getStepY(), adjZ + faces[1].getStepZ() + faces[3].getStepZ(), centerState, centerLight);
        }

        int[] cb = this.lm;

        final int calm;

        if (offset && LightDataAccess.unpackFO(adjWord)) {
            calm = getFilteredNeighborLight(cache, x, y, z, centerState, centerLight);
        } else {
            calm = getFilteredNeighborLight(cache, adjX, adjY, adjZ, centerState, centerLight);
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

        if (!ColoredLightEngine.getInstance().isEnabled()) {
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
            var data = SodiumPackedLightData.unpackData(lm[i]);
            bl[i] = data.red8;
            gl[i] = data.green8;
            bll[i] = data.blue8;
            sl[i] = data.skyLight4;
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
        if (!ColoredLightEngine.getInstance().isEnabled()) {
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