package net.camacraft.colorfullighting.common.util;

import net.camacraft.colorfullighting.api.CLPackedLight;
import net.camacraft.colorfullighting.common.ColorfulLightingConfig;
import net.minecraft.client.renderer.LightTexture;

public class PackedLightData {
    public int skyLight4;
    public int red8;
    public int blue8;
    public int green8;
    public int alpha4;

    public static int packData(int skyLight4, ColorRGB8 color) { return packData(skyLight4, color.red, color.green, color.blue); }
    public static int packData(int skyLight4, int red8, int green8, int blue8) {
        //blockLight = Math.clamp(blockLight, 0, 15);
        skyLight4 = MathExt.clamp(skyLight4, 0, 15);
        red8 = MathExt.clamp(red8, 0, 255);
        green8 = MathExt.clamp(green8, 0, 255);
        blue8 = MathExt.clamp(blue8, 0, 255);
        int alpha4 = 15;
        // TODO: big-endian
        return red8 | green8 << 8 | skyLight4 << 16 | blue8 << 20 | alpha4 << 28;
    }

    public static PackedLightData unpackData(int packedData) {
        PackedLightData data = new PackedLightData();
        if (CLPackedLight.isColored(packedData)) {
            data.red8 = CLPackedLight.red8(packedData);
            data.green8 = CLPackedLight.green8(packedData);
            data.blue8 = CLPackedLight.blue8(packedData);
            data.skyLight4 = CLPackedLight.sky4(packedData);
            data.alpha4 = 15;
        } else {
            int block4 = LightTexture.block(packedData);
            int sky4 = LightTexture.sky(packedData);
            int lum8 = block4 * 17;
            data.red8 = lum8;
            data.green8 = lum8;
            data.blue8 = lum8;
            data.skyLight4 = sky4;
            data.alpha4 = 0;
        }
        return data;
    }

    public static boolean isBlack(int packedData) {
        return packedData == 0xF0000000 || packedData == 0;
    }

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

    private static int minNonZero(int a, int b) {
        if (a == 0) return b;
        if (b == 0) return a;
        return Math.min(a, b);
    }

    private static int blockLum(PackedLightData data) {
        return Math.max(data.red8, Math.max(data.green8, data.blue8));
    }

    /** Scales the block light down (hue kept) so its brightest channel is at most {@code limit8}. */
    private static void limitBlockLight(PackedLightData data, int limit8) {
        int lum = blockLum(data);
        if (lum <= limit8) return;
        if (limit8 <= 0) {
            data.red8 = data.green8 = data.blue8 = 0;
            return;
        }
        data.red8 = data.red8 * limit8 / lum;
        data.green8 = data.green8 * limit8 / lum;
        data.blue8 = data.blue8 * limit8 / lum;
    }

    public static int blend(int lightColor0, int lightColor1, int lightColor2, int lightColor3) {
        if (!CLPackedLight.isColored(lightColor0) && !CLPackedLight.isColored(lightColor1) &&
            !CLPackedLight.isColored(lightColor2) && !CLPackedLight.isColored(lightColor3)) {
            if (lightColor0 == 0) lightColor0 = lightColor3;
            if (lightColor1 == 0) lightColor1 = lightColor3;
            if (lightColor2 == 0) lightColor2 = lightColor3;
            return (lightColor0 + lightColor1 + lightColor2 + lightColor3) >> 2 & 0x00FF00FF;
        }

        if (lightColor0 == 0) lightColor0 = lightColor3;
        if (lightColor1 == 0) lightColor1 = lightColor3;
        if (lightColor2 == 0) lightColor2 = lightColor3;

        var data0 = unpackData(lightColor0);
        var data1 = unpackData(lightColor1);
        var data2 = unpackData(lightColor2);
        var data3 = unpackData(lightColor3);

        // Light loses at least a level (17) per block: the edges (0, 1) are never more than a level
        // brighter than the face's own cell (3) in open air, the corner (2) never more than a level
        // brighter than the brighter edge. Brighter samples came round something that stops light (a
        // beacon walled in tinted glass lit the floor under and round the glass) and are scaled down to
        // that bound. Full-bright (emissive) samples keep their glow.
        if (ColorfulLightingConfig.smoothLightStaysBehindBlockers()) {
            int edgeLimit = blockLum(data3) + 17;
            if (lightColor0 != LightTexture.FULL_BRIGHT) limitBlockLight(data0, edgeLimit);
            if (lightColor1 != LightTexture.FULL_BRIGHT) limitBlockLight(data1, edgeLimit);
            if (lightColor2 != LightTexture.FULL_BRIGHT) limitBlockLight(data2, Math.max(blockLum(data0), blockLum(data1)) + 17);
        }

        int sky = blendChannel(data0.skyLight4, data1.skyLight4, data2.skyLight4, data3.skyLight4);

        int lum0 = Math.max(data0.red8, Math.max(data0.green8, data0.blue8));
        int lum1 = Math.max(data1.red8, Math.max(data1.green8, data1.blue8));
        int lum2 = Math.max(data2.red8, Math.max(data2.green8, data2.blue8));
        int lum3 = Math.max(data3.red8, Math.max(data3.green8, data3.blue8));

        int minLum = 256;
        PackedLightData minData = null;
        if (lum0 > 0 && lum0 < minLum) { minLum = lum0; minData = data0; }
        if (lum1 > 0 && lum1 < minLum) { minLum = lum1; minData = data1; }
        if (lum2 > 0 && lum2 < minLum) { minLum = lum2; minData = data2; }
        if (lum3 > 0 && lum3 < minLum) { minLum = lum3; minData = data3; }

        int r0 = lum0 > 0 ? data0.red8 : (minData != null ? minData.red8 : 0);
        int g0 = lum0 > 0 ? data0.green8 : (minData != null ? minData.green8 : 0);
        int b0 = lum0 > 0 ? data0.blue8 : (minData != null ? minData.blue8 : 0);

        int r1 = lum1 > 0 ? data1.red8 : (minData != null ? minData.red8 : 0);
        int g1 = lum1 > 0 ? data1.green8 : (minData != null ? minData.green8 : 0);
        int b1 = lum1 > 0 ? data1.blue8 : (minData != null ? minData.blue8 : 0);

        int r2 = lum2 > 0 ? data2.red8 : (minData != null ? minData.red8 : 0);
        int g2 = lum2 > 0 ? data2.green8 : (minData != null ? minData.green8 : 0);
        int b2 = lum2 > 0 ? data2.blue8 : (minData != null ? minData.blue8 : 0);

        int r3 = lum3 > 0 ? data3.red8 : (minData != null ? minData.red8 : 0);
        int g3 = lum3 > 0 ? data3.green8 : (minData != null ? minData.green8 : 0);
        int b3 = lum3 > 0 ? data3.blue8 : (minData != null ? minData.blue8 : 0);

        return packData(
                sky,
                (r0 + r1 + r2 + r3) >> 2,
                (g0 + g1 + g2 + g3) >> 2,
                (b0 + b1 + b2 + b3) >> 2
        );
    }

    public static int blend(int lightColor0, int lightColor1, int lightColor2, int lightColor3, float weight0, float weight1, float weight2, float weight3) {
        if (!CLPackedLight.isColored(lightColor0) && !CLPackedLight.isColored(lightColor1) &&
            !CLPackedLight.isColored(lightColor2) && !CLPackedLight.isColored(lightColor3)) {
            int i = (int)((float)(lightColor0 >> 16 & 255) * weight0 + (float)(lightColor1 >> 16 & 255) * weight1 + (float)(lightColor2 >> 16 & 255) * weight2 + (float)(lightColor3 >> 16 & 255) * weight3) & 255;
            int j = (int)((float)(lightColor0 & 255) * weight0 + (float)(lightColor1 & 255) * weight1 + (float)(lightColor2 & 255) * weight2 + (float)(lightColor3 & 255) * weight3) & 255;
            return i << 16 | j;
        }

        var data0 = unpackData(lightColor0);
        var data1 = unpackData(lightColor1);
        var data2 = unpackData(lightColor2);
        var data3 = unpackData(lightColor3);

        return packData(
                (int)(data0.skyLight4 * weight0 + data1.skyLight4 * weight1 + data2.skyLight4 * weight2 + data3.skyLight4 * weight3),
                (int)(data0.red8 * weight0 + data1.red8 * weight1 + data2.red8 * weight2 + data3.red8 * weight3),
                (int)(data0.green8 * weight0 + data1.green8 * weight1 + data2.green8 * weight2 + data3.green8 * weight3),
                (int)(data0.blue8 * weight0 + data1.blue8 * weight1 + data2.blue8 * weight2 + data3.blue8 * weight3)
        );
    }

    public static int max(int lightColor0, int lightColor1) {
        var firstData = unpackData(lightColor0);
        var secondData = unpackData(lightColor1);
        if(firstData.alpha4 == 0 || secondData.alpha4 == 0) {
            if(firstData.alpha4 == secondData.alpha4) {
                int blockLight0 = LightTexture.block(lightColor0);
                int skyLight0 = LightTexture.sky(lightColor0);
                int blockLight1 = LightTexture.block(lightColor1);
                int skyLight1 = LightTexture.sky(lightColor1);
                return LightTexture.pack(Math.max(blockLight0, blockLight1), Math.max(skyLight0, skyLight1));
            }
            else {
                return firstData.alpha4 == 0 ? lightColor1 : lightColor0;
            }
        }

        return packData(
                Math.max(firstData.skyLight4, secondData.skyLight4),
                Math.max(firstData.red8, secondData.red8),
                Math.max(firstData.green8, secondData.green8),
                Math.max(firstData.blue8, secondData.blue8)
        );
    }
}