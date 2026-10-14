package net.camacraft.colorfullighting.compat.xaero.worldmap;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.camacraft.colorfullighting.compat.xaero.XaeroMapColors;
import net.camacraft.colorfullighting.mixin.compat.xaero.worldmap.XaeroRegionTextureAccessor;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL21;
import org.lwjgl.system.MemoryUtil;
import xaero.map.region.LeveledRegion;
import xaero.map.region.MapTileChunk;
import xaero.map.region.texture.LeafRegionTexture;
import xaero.map.region.texture.RegionTexture;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Draws the recorded colors under the World Map's textures. Next to each World Map texture (a 64 x 64 tile chunk,
 * or a zoomed-out texture covering 2, 4 or 8 times as many blocks, always inside one region) that has colored light
 * recorded in its footprint, a 64 x 64 texture of the light's hue per pixel ({@code rgb = channel / brightest
 * channel}, premultiplied by the share of the pixel's blocks that have colored light, which is the alpha), built from
 * the region's {@link RegionColors} and rebuilt when they change. It is bound on texture unit 1 for each World Map
 * texture drawn with Colorful Lighting's map shaders ({@link XaeroMapShaders}), which tint the block-lit part of the
 * pixel with it; any other texture gets an empty one, which leaves Xaero's picture exactly as it was.
 *
 * <p>Render thread, except {@link #register}/{@link #unregister}.
 */
public final class WorldMapColorTextures {
    private static final int SIZE = 64;

    /** World Map texture id -> its texture object, from RegionTexture.bindColorTexture to deleteTexturesAndBuffers. */
    private static final Map<Integer, RegionTexture<?>> TEXTURES = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<Integer> DELETED = new ConcurrentLinkedQueue<>();
    private static final Int2ObjectOpenHashMap<Built> BUILT = new Int2ObjectOpenHashMap<>();

    private static int emptyTexture = -1;
    /** The shader texture 1 before a draw, put back after it (vanilla's overlay texture slot). */
    private static int savedShaderTexture = -1;

    /** Counters for checking it in a running game (read by reflection: pack harness, debugging). */
    public static volatile long texturesBuilt, colorBinds;

    private WorldMapColorTextures() {
    }

    private static final class Built {
        RegionTexture<?> owner;
        Footprint footprint;
        int texture = -1;
        long stamp = -1;
        boolean legible;
    }

    private record Footprint(Path file, int level, int localX, int localZ, int[] tiles, boolean cave) {
    }

    public static void register(int texture, RegionTexture<?> owner) {
        TEXTURES.put(texture, owner);
    }

    public static void unregister(int texture) {
        if (TEXTURES.remove(texture) != null) DELETED.add(texture);
    }

    /**
     * A World Map texture was just bound for drawing ({@code texture} 0: the drawing is over) by
     * MultiTextureRenderTypeRenderer.draw. {@code throughShader}: bound for the first draw, which applies the shader
     * (and with it the shader textures); later draws bind texture units directly.
     */
    public static void afterBind(boolean throughShader, int texture) {
        if (!XaeroMapShaders.isColored(RenderSystem.getShader())) return;
        freeDeleted();
        if (texture == 0) {
            if (savedShaderTexture != -1) {
                RenderSystem.setShaderTexture(1, savedShaderTexture);
                savedShaderTexture = -1;
            }
            GlStateManager._activeTexture(GL13.GL_TEXTURE1);
            GlStateManager._bindTexture(0);
            GlStateManager._activeTexture(GL13.GL_TEXTURE0);
            return;
        }
        GlStateManager._activeTexture(GL13.GL_TEXTURE1);
        int colors = XaeroMapColors.worldMapOn() ? colorTexture(texture) : emptyTexture();
        GlStateManager._bindTexture(colors);
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
        if (throughShader) {
            if (savedShaderTexture == -1) savedShaderTexture = RenderSystem.getShaderTexture(1);
            RenderSystem.setShaderTexture(1, colors);
        }
        if (colors != emptyTexture) colorBinds++;
    }

    /** The colors texture for a World Map texture: built or rebuilt as needed (on the active unit), or the empty one. */
    private static int colorTexture(int texture) {
        RegionTexture<?> owner = TEXTURES.get(texture);
        if (owner == null) return emptyTexture();
        Built built = BUILT.get(texture);
        if (built == null || built.owner != owner) {
            if (built != null) free(built);
            built = new Built();
            built.owner = owner;
            built.footprint = footprint(owner);
            BUILT.put(texture, built);
        }
        Footprint footprint = built.footprint;
        if (footprint == null) return emptyTexture();
        RegionColors colors = WorldMapColorStore.loadedOrRequest(footprint.file());
        if (colors == null) return emptyTexture();
        long stamp = -1;
        for (int tile : footprint.tiles()) {
            if (colors.any(tile)) stamp = Math.max(stamp, colors.stamp(tile));
        }
        if (stamp < 0) {
            free(built);
            return emptyTexture();
        }
        boolean legible = footprint.cave() && WorldMapColors.legibleCaveMaps;
        if (built.texture == -1 || built.stamp != stamp || built.legible != legible) {
            if (!build(built, colors, legible)) {
                free(built);
                return emptyTexture();
            }
            built.stamp = stamp;
            built.legible = legible;
        }
        return built.texture;
    }

    /** Which blocks a World Map texture covers: one tile chunk, or 2x2, 4x4 or 8x8 of them zoomed out. */
    private static Footprint footprint(RegionTexture<?> owner) {
        LeveledRegion<?> region = ((XaeroRegionTextureAccessor) owner).colorfullighting$region();
        if (region == null) return null;
        int o = -1, p = -1;
        if (owner instanceof LeafRegionTexture leaf) {
            MapTileChunk tileChunk = leaf.getTileChunk();
            o = tileChunk.getX() & 7;
            p = tileChunk.getZ() & 7;
        } else {
            search:
            for (int i = 0; i < 8; i++) {
                for (int j = 0; j < 8; j++) {
                    if (region.getTexture(i, j) == owner) {
                        o = i;
                        p = j;
                        break search;
                    }
                }
            }
        }
        int level = region.getLevel();
        if (o < 0 || level < 0 || level > 3) return null;
        int blocks = SIZE << level;
        long originX = ((long) region.getRegionX() << (9 + level)) + (long) o * blocks;
        long originZ = ((long) region.getRegionZ() << (9 + level)) + (long) p * blocks;
        int regionX = (int) Math.floorDiv(originX, 512L), regionZ = (int) Math.floorDiv(originZ, 512L);
        int localX = (int) (originX - regionX * 512L), localZ = (int) (originZ - regionZ * 512L);
        Path file = WorldMapColors.file(region, regionX, regionZ);
        if (file == null) return null;
        int side = Math.max(1, blocks >> 6);
        int[] tiles = new int[side * side];
        for (int i = 0; i < side; i++) {
            for (int j = 0; j < side; j++) {
                tiles[j * side + i] = ((localZ >> 6) + j) << 3 | ((localX >> 6) + i);
            }
        }
        return new Footprint(file, level, localX, localZ, tiles, region.getCaveLayer() != Integer.MAX_VALUE);
    }

    /** Fills the texture from the region's colors; false when nothing in its footprint is colored after all. */
    private static boolean build(Built built, RegionColors colors, boolean legible) {
        Footprint footprint = built.footprint;
        int step = 1 << footprint.level();
        char[][] tiles = new char[64][];
        for (int tile : footprint.tiles()) tiles[tile] = colors.copy(tile);
        ByteBuffer pixels = MemoryUtil.memAlloc(SIZE * SIZE * 4);
        boolean any = false;
        try {
            float blocks = step * step;
            for (int v = 0; v < SIZE; v++) {
                for (int u = 0; u < SIZE; u++) {
                    float r = 0, g = 0, b = 0, w = 0;
                    int x0 = footprint.localX() + u * step, z0 = footprint.localZ() + v * step;
                    for (int dz = 0; dz < step; dz++) {
                        int z = z0 + dz;
                        for (int dx = 0; dx < step; dx++) {
                            int x = x0 + dx;
                            char[] values = tiles[(z >> 6) << 3 | (x >> 6)];
                            if (values == null) continue;
                            int key = values[(z & 63) << 6 | (x & 63)];
                            if (key == 0) continue;
                            // legible cave maps light every pixel fully: the color shows by the light's strength
                            float weight = legible ? XaeroMapColors.light(key) / 15F : 1F;
                            r += XaeroMapColors.hue(key, 8) * weight;
                            g += XaeroMapColors.hue(key, 4) * weight;
                            b += XaeroMapColors.hue(key, 0) * weight;
                            w += weight;
                        }
                    }
                    if (w > 0) any = true;
                    pixels.put(channel(r / blocks)).put(channel(g / blocks)).put(channel(b / blocks)).put(channel(w / blocks));
                }
            }
            pixels.flip();
            if (!any) return false;
            if (built.texture == -1) built.texture = newTexture();
            else GlStateManager._bindTexture(built.texture);
            upload(SIZE, pixels);
            texturesBuilt++;
            return true;
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }

    private static byte channel(float value) {
        return (byte) Math.round(Math.min(1F, Math.max(0F, value)) * 255F);
    }

    /** An empty colors texture: the shader leaves the pixel as Xaero drew it. */
    private static int emptyTexture() {
        if (emptyTexture == -1) {
            int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            emptyTexture = newTexture();
            ByteBuffer pixel = MemoryUtil.memCalloc(4);
            try {
                upload(1, pixel);
            } finally {
                MemoryUtil.memFree(pixel);
            }
            GlStateManager._bindTexture(previous);
        }
        return emptyTexture;
    }

    /** A new texture, bound, with the World Map's own sampling (RegionTexture.setupTextureParameters). */
    private static int newTexture() {
        int texture = GlStateManager._genTexture();
        GlStateManager._bindTexture(texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_BASE_LEVEL, 0);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
        GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MIN_LOD, 0.0F);
        GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LOD, 1.0F);
        GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_LOD_BIAS, 0.0F);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        return texture;
    }

    /** Uploads to the bound texture, with the unpack state reset (vanilla leaves a row length; Xaero uses PBOs). */
    private static void upload(int size, ByteBuffer pixels) {
        GlStateManager._glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, size, size, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
    }

    private static void free(Built built) {
        if (built.texture != -1) {
            GlStateManager._deleteTexture(built.texture);
            built.texture = -1;
        }
        built.stamp = -1;
    }

    private static void freeDeleted() {
        Integer texture;
        while ((texture = DELETED.poll()) != null) {
            Built built = BUILT.remove(texture.intValue());
            if (built != null) free(built);
        }
    }
}
