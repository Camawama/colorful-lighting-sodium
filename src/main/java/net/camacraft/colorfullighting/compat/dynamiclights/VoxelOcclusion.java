package net.camacraft.colorfullighting.compat.dynamiclights;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Transmission for the shader path's dynamic lights (2.8.1): what each block round the camera lets through, as a 64x64x64 texture of
 * one byte a block (255 solid, 0 clear), in world space with its origin on a 16-block grid 32 blocks before the camera. The
 * shaders march from a vertex toward each light through this volume and dim the light by what they cross, so a torch
 * behind a wall lights neither the room on the other side nor the ground through the floor. Rebuilt on a worker thread
 * from the client level every {@link #REBUILD_TICKS} ticks or when the camera leaves the inner 16 blocks of the volume, and
 * uploaded on the render thread when ready (262 KB). Light that leaves the volume is unshadowed. Bound on texture unit
 * {@link #TEXTURE_UNIT} for the programs that sample it.
 */
public final class VoxelOcclusion {
    public static final int SIZE = 64;
    public static final int TEXTURE_UNIT = 12;
    private static final int REBUILD_TICKS = 10;
    /** Four bytes a block: what it lets through, per channel (red, green, blue), and a spare. */
    private static final int BYTES = SIZE * SIZE * SIZE * 4;

    private static volatile boolean enabled;
    private static int textureId = -1;
    private static int originX, originY, originZ;
    private static int uploadedOriginX = Integer.MIN_VALUE, uploadedOriginY, uploadedOriginZ;
    private static long lastBuildTick = Long.MIN_VALUE;
    private static CompletableFuture<Void> building;
    private static final AtomicReference<byte[]> ready = new AtomicReference<>();
    private static int readyOriginX, readyOriginY, readyOriginZ;
    private static ByteBuffer upload;
    /** The volume as uploaded, for the CPU samples (entities, block entities, particles) to shadow the same way. */
    private static volatile byte[] current;
    private static volatile int currentX = Integer.MIN_VALUE, currentY, currentZ;

    private VoxelOcclusion() {}

    public static void setEnabled(boolean value) {
        enabled = value;
        if (!value) ready.set(null);
    }

    public static boolean enabled() {
        return enabled && textureId > 0 && uploadedOriginX != Integer.MIN_VALUE;
    }

    /** The volume's origin relative to the camera, for the shaders (so a camera-relative point maps into it). */
    public static float[] originRelative(Vec3 camera) {
        return new float[]{(float) (uploadedOriginX - camera.x), (float) (uploadedOriginY - camera.y), (float) (uploadedOriginZ - camera.z)};
    }

    /** Once a client tick: start a rebuild when due; the upload happens in {@link #bind}. */
    public static void tick(ClientLevel level) {
        if (!enabled) return;
        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        long tick = level.getGameTime();
        int wantX = (Math.floorDiv((int) Math.floor(cam.x), 16) - 2) * 16;
        int wantY = (Math.floorDiv((int) Math.floor(cam.y), 16) - 2) * 16;
        int wantZ = (Math.floorDiv((int) Math.floor(cam.z), 16) - 2) * 16;
        boolean moved = wantX != originX || wantY != originY || wantZ != originZ;
        if (building != null && !building.isDone()) return;
        if (!moved && tick - lastBuildTick < REBUILD_TICKS) return;
        originX = wantX;
        originY = wantY;
        originZ = wantZ;
        lastBuildTick = tick;
        final int ox = wantX, oy = wantY, oz = wantZ;
        building = CompletableFuture.runAsync(() -> build(level, ox, oy, oz));
    }

    private static void build(ClientLevel level, int ox, int oy, int oz) {
        byte[] data = new byte[BYTES];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        net.camacraft.colorfullighting.accessors.LevelWrapper accessor = new net.camacraft.colorfullighting.accessors.LevelWrapper(level);
        int minY = level.getMinBuildHeight(), maxY = level.getMaxBuildHeight();
        for (int y = 0; y < SIZE; y++) {
            int wy = oy + y;
            if (wy < minY || wy >= maxY) continue;
            for (int z = 0; z < SIZE; z++) {
                for (int x = 0; x < SIZE; x++) {
                    pos.set(ox + x, wy, oz + z);
                    if (!level.hasChunkAt(pos)) continue;
                    BlockState state = level.getBlockState(pos);
                    int i = ((z * SIZE + y) * SIZE + x) * 4;
                    if (state.isAir()) {
                        data[i] = data[i + 1] = data[i + 2] = (byte) 255;
                        continue;
                    }
                    // what stops light: an opaque full cube; what tints it: Colorful Lighting's filters (stained glass, water with a
                    // filter); everything else (leaves, slabs, panes without a filter) lets it through, as vanilla's light does mostly
                    if (state.isSolidRender(level, pos)) continue; // zero: blocked
                    net.camacraft.colorfullighting.common.util.ColorRGB4 through = net.camacraft.colorfullighting.common.Config.getColoredLightTransmittance(accessor, pos,
                            new net.camacraft.colorfullighting.accessors.BlockStateWrapper(state));
                    data[i] = (byte) (through.red4 * 17);
                    data[i + 1] = (byte) (through.green4 * 17);
                    data[i + 2] = (byte) (through.blue4 * 17);
                }
            }
        }
        readyOriginX = ox;
        readyOriginY = oy;
        readyOriginZ = oz;
        ready.set(data);
    }

    /**
     * Render thread, before a pass that samples the volume: uploads a finished rebuild and binds the texture on its unit. True
     * when a texture is bound and usable.
     */
    public static boolean bind() {
        if (!enabled) return false;
        if (!RenderSystem.isOnRenderThread()) return uploadedOriginX != Integer.MIN_VALUE;
        // the active texture unit goes through GlStateManager, which tracks it: a raw glActiveTexture left vanilla's state
        // believing another unit was active, and the lightmap and the atlas were then bound to the wrong units (whole
        // sections drawn with garbage light, 2026-11-09)
        int previousUnit = com.mojang.blaze3d.platform.GlStateManager._getActiveTexture();
        if (textureId <= 0) {
            textureId = GL11C.glGenTextures();
            com.mojang.blaze3d.platform.GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + TEXTURE_UNIT);
            GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, textureId);
            GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
            GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
            GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
            GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
            GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL12C.GL_TEXTURE_WRAP_R, GL12C.GL_CLAMP_TO_EDGE);
            upload = MemoryUtil.memAlloc(BYTES);
            GL12C.glTexImage3D(GL12C.GL_TEXTURE_3D, 0, GL11C.GL_RGBA8, SIZE, SIZE, SIZE, 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        }
        byte[] data = ready.getAndSet(null);
        com.mojang.blaze3d.platform.GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + TEXTURE_UNIT);
        GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, textureId);
        if (data != null) {
            upload.clear();
            upload.put(data).flip();
            // the unpack state is whatever the last texture upload left, and vanilla uploads its 16-wide lightmap every frame:
            // with a row length of 16 the 64-wide slices read scrambled and the volume came out solid everywhere (a black
            // field under a held torch in the shader path, 2026-11-09). The layout is x fastest, then y, then z: slice z,
            // row y, as the shaders sample it with (x, y, z) and as transmit() below indexes it.
            com.mojang.blaze3d.platform.GlStateManager._pixelStore(GL11C.GL_UNPACK_ROW_LENGTH, 0);
            com.mojang.blaze3d.platform.GlStateManager._pixelStore(GL11C.GL_UNPACK_SKIP_ROWS, 0);
            com.mojang.blaze3d.platform.GlStateManager._pixelStore(GL11C.GL_UNPACK_SKIP_PIXELS, 0);
            com.mojang.blaze3d.platform.GlStateManager._pixelStore(GL11C.GL_UNPACK_ALIGNMENT, 4);
            com.mojang.blaze3d.platform.GlStateManager._pixelStore(GL12C.GL_UNPACK_IMAGE_HEIGHT, 0);
            com.mojang.blaze3d.platform.GlStateManager._pixelStore(GL12C.GL_UNPACK_SKIP_IMAGES, 0);
            GL12C.glTexSubImage3D(GL12C.GL_TEXTURE_3D, 0, 0, 0, 0, SIZE, SIZE, SIZE, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, upload);
            uploadedOriginX = readyOriginX;
            uploadedOriginY = readyOriginY;
            uploadedOriginZ = readyOriginZ;
            current = data;
            currentX = readyOriginX;
            currentY = readyOriginY;
            currentZ = readyOriginZ;
        }
        com.mojang.blaze3d.platform.GlStateManager._activeTexture(previousUnit);
        return uploadedOriginX != Integer.MIN_VALUE;
    }

    /**
     * The CPU twin of the shaders' march: what share of a light reaches a world point, per channel (1 clear, 0 blocked, a glass
     * filter's colour in between), through the volume as uploaded; 1 outside it or before any upload. Thread-safe; writes into
     * {@code out} (r, g, b) and returns it.
     */
    public static double[] transmit(double fromX, double fromY, double fromZ, double toX, double toY, double toZ, double[] out) {
        out[0] = out[1] = out[2] = 1.0;
        byte[] data = current;
        if (data == null) return out;
        int ox = currentX, oy = currentY, oz = currentZ;
        double dx = toX - fromX, dy = toY - fromY, dz = toZ - fromZ;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.5) return out;
        double start = 0.6, end = len - 0.6;
        int steps = (int) Math.max(1, Math.min(32, Math.ceil(end - start)));
        for (int s = 0; s < steps; s++) {
            double t = start + (end - start) * (s + 0.5) / steps;
            int x = (int) Math.floor(fromX + dx / len * t) - ox, y = (int) Math.floor(fromY + dy / len * t) - oy, z = (int) Math.floor(fromZ + dz / len * t) - oz;
            if (x < 0 || y < 0 || z < 0 || x >= SIZE || y >= SIZE || z >= SIZE) continue;
            int i = ((z * SIZE + y) * SIZE + x) * 4;
            out[0] *= (data[i] & 0xFF) / 255.0;
            out[1] *= (data[i + 1] & 0xFF) / 255.0;
            out[2] *= (data[i + 2] & 0xFF) / 255.0;
            if (out[0] + out[1] + out[2] <= 0.03) {
                out[0] = out[1] = out[2] = 0.0;
                return out;
            }
        }
        return out;
    }

    public static void onLevelUnload() {
        ready.set(null);
        uploadedOriginX = Integer.MIN_VALUE;
        lastBuildTick = Long.MIN_VALUE;
    }
}
