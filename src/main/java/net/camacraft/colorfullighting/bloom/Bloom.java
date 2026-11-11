package net.camacraft.colorfullighting.bloom;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.camacraft.colorfullighting.ColorfulLighting;
import net.camacraft.colorfullighting.common.ColorfulLightingConfig;
import net.camacraft.colorfullighting.compat.dynamiclights.BlacklightField;
import net.camacraft.colorfullighting.compat.dynamiclights.ShaderDynamicLights;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL33C;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Bloom (2.11.0, off by default, {@code /cl bloom on|off}): the blocks that emit light, and what glows under blacklight,
 * spread a soft glow over the picture round them.
 *
 * <p>A pass over the finished world picture, after the level and before the hand. Nothing of the terrain changes for it: no
 * vertex format, no shader of the chunks, no second render target for them. What glows is found from the picture alone: the
 * depth of a pixel gives the block it shows, and the {@link EmitterVolume} says whether that block emits light. The glow
 * is the pixel's own colour times its brightness and the block's level, so a torch's flame glows and its stick does not.
 * Under Fabulous graphics the main target's depth holds the solid world alone, so a lamp behind stained glass or under
 * water glows in the colour it is seen in; otherwise the pixel shows the glass, and the lamp behind it does not glow.
 *
 * <p>The camera's rotation is taken at the level's stages ({@link #view}): Forge hands the stage after the level the
 * projection in the place of the level's pose.
 *
 * <p>The glow is blurred down and up a pyramid of half-size targets (five taps down, eight up) and laid over the picture as a
 * screen.
 *
 * <p>The pass changes GL state with plain GL calls and puts back exactly what it found, so the game's own record of that
 * state stays as right as it was. It stands down under an Oculus shader pack (packs have their own bloom) and when its
 * shaders do not compile (said once in the log).
 */
public final class Bloom {
    /** The most levels the pyramid has; a picture 600 high takes five, one 1080 high six, so the glow is as wide on either. */
    private static final int LEVELS = 7;
    private static final int MAX_UV_LIGHTS = 4;
    private static final String PATH = "/assets/colorful_lighting/shaders/bloom/";

    private static boolean failed;
    private static int prefilter, down, up, vao;
    // prefilter
    private static int pColor, pDepth, pSize, pInvViewProj, pVolume, pEmitters, pVolumeSize, pCamInVolume, pWindowMin, pThreshold, pGain, pDebug;
    private static int pUvGain, pUvStrength, pUvField, pUvFieldTex, pUvFieldOrigin, pUvFieldSize, pUvLights, pUvPos, pUvDir;
    private static int pHistory, pPrevViewProj, pCamDelta, pKeep;
    private static int pShips, pShipFromRel, pShipEmitters;
    private static int pGlows, pGlowPos, pGlowColor;
    private static final float[] glowPos = new float[ItemGlows.MAX * 4], glowCol = new float[ItemGlows.MAX * 4];
    private static final FloatBuffer GLOW_POS = BufferUtils.createFloatBuffer(ItemGlows.MAX * 4), GLOW_COL = BufferUtils.createFloatBuffer(ItemGlows.MAX * 4);
    /** While the pass draws over the hand (2.12.0): the picture's depth holds the hand alone, in the hand's own space. */
    private static boolean handPass;
    /**
     * {@code -Dcolorfullighting.bloomGpuTime=true}: what the pass takes on the graphics card, by a timer query round it (one
     * frame in eight, read back a frame later, left alone while another timer runs). In {@link #stats}.
     */
    private static final boolean GPU_TIME = Boolean.getBoolean("colorfullighting.bloomGpuTime");
    private static int gpuQuery;
    private static boolean gpuPending;
    private static double gpuMs, gpuHandMs;
    private static boolean gpuPendingHand;
    private static long handFrames;

    /** After the hand is drawn: a pass over it when it holds something that gives light. */
    public static void renderHand() {
        if (ItemGlows.handCount() == 0 || !RenderSystem.isOnRenderThread() || !active()) return;
        handPass = true;
        try {
            render(ItemGlows.handProjection(), Minecraft.getInstance().gameRenderer.getMainCamera());
        } finally {
            handPass = false;
        }
    }

    public static long handFrames() {
        return handFrames;
    }
    private static final float[] shipMatrices = new float[ShipEmitters.SLOTS * 16];
    private static final FloatBuffer SHIP_MATRICES = BufferUtils.createFloatBuffer(ShipEmitters.SLOTS * 16);
    /**
     * What glowed the frame before, at the first level's size (2.11.1): each frame's glow is blended with it, read where the
     * pixel's place in the world was on the screen then. The picture has no soft edges: a lamp's outline moves a whole pixel
     * column at a time, and the glow's strength stepped with it as the camera moved. Blended over a few frames the steps
     * even out, and reading the old glow at the old place keeps it from trailing behind a turn.
     */
    private static int historyTex, historyFbo;
    private static boolean historyValid;
    private static final Matrix4f PREV_VIEW_PROJ = new Matrix4f();
    private static double prevCamX, prevCamY, prevCamZ;
    private static long prevNanos;
    /** The time over which the glow is evened out, in seconds ({@code -Dcolorfullighting.bloomSmoothSeconds}). */
    private static final float SMOOTH_SECONDS = Float.parseFloat(System.getProperty("colorfullighting.bloomSmoothSeconds", "0.035"));
    private static int dTex, dTexel, uTex, uTexel, uScale, uSoft;

    private static final int[] fbo = new int[LEVELS], tex = new int[LEVELS], widths = new int[LEVELS], heights = new int[LEVELS];
    private static int targetsW, targetsH, levels;

    private static final FloatBuffer MATRIX = BufferUtils.createFloatBuffer(16);
    private static final FloatBuffer UV_POS = BufferUtils.createFloatBuffer(MAX_UV_LIGHTS * 4), UV_DIR = BufferUtils.createFloatBuffer(MAX_UV_LIGHTS * 4);
    private static final float[] uvPos = new float[MAX_UV_LIGHTS * 4], uvDir = new float[MAX_UV_LIGHTS * 4];
    private static final int[] VIEWPORT = new int[4];
    private static final ByteBuffer COLOR_MASK = BufferUtils.createByteBuffer(4);

    private static long frames;
    private static double lastMs, averageMs;
    private static boolean shaderPack;
    private static long shaderPackCheckedAt = -1;

    private Bloom() {}

    /** Whether the pass runs this frame. */
    public static boolean active() {
        return ColorfulLightingConfig.bloom() && !failed && !shaderPackNow();
    }

    /** Looked up twice a second: the answer comes by reflection. */
    private static boolean shaderPackNow() {
        long now = System.nanoTime() / 500_000_000L;
        if (now != shaderPackCheckedAt) {
            shaderPackCheckedAt = now;
            shaderPack = ShaderDynamicLights.shaderPackActive();
        }
        return shaderPack;
    }

    /** For tests and '/cl bloom': how many frames the pass drew and what it took on the CPU side. */
    public static String stats() {
        return String.format(java.util.Locale.ROOT, "%d frames drawn, %.3f ms a frame submitting (%.3f the last)%s%s", frames, averageMs, lastMs,
                GPU_TIME ? String.format(java.util.Locale.ROOT, ", gpu %.3f ms a pass, the hand's %.3f", gpuMs, gpuHandMs) : "",
                failed ? ", its shaders failed (see the log)" : shaderPack ? ", standing down for the shader pack" : "");
    }

    public static long frames() {
        return frames;
    }

    private static final Matrix4f VIEW = new Matrix4f(), PROJECTION = new Matrix4f();
    private static boolean viewKnown;

    /** At a stage of the level's drawing: the pose and the projection the level is drawn with this frame. */
    public static void view(Matrix4f pose, Matrix4f projection) {
        if (!ColorfulLightingConfig.bloom() || failed) return;
        VIEW.set(pose);
        PROJECTION.set(projection);
        viewKnown = true;
    }

    /** After the level is drawn: the pass. */
    public static void render(Matrix4f lastProjection, Camera camera) {
        if (!RenderSystem.isOnRenderThread() || !active()) return;
        final boolean hand = handPass;
        if (hand) {
            // the hand is drawn in the camera's own space with a projection of its own
            VIEW.identity();
            PROJECTION.set(lastProjection);
        } else if (!viewKnown) {
            // no stage this frame: the camera's own turn, without what another mod added to the pose
            VIEW.rotationX((float) Math.toRadians(camera.getXRot())).rotateY((float) Math.toRadians(camera.getYRot() + 180f));
            PROJECTION.set(lastProjection);
        }
        if (!hand) viewKnown = false;
        Matrix4f view = VIEW, projection = PROJECTION;
        Vec3 cam = camera.getPosition();
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        int w = main.width, h = main.height;
        if (w < 64 || h < 64 || main.getColorTextureId() <= 0 || main.getDepthTextureId() <= 0) return;
        long started = System.nanoTime();
        boolean timed = false;
        if (GPU_TIME) {
            if (gpuQuery == 0) gpuQuery = GL15C.glGenQueries();
            if (gpuPending && GL15C.glGetQueryObjecti(gpuQuery, GL15C.GL_QUERY_RESULT_AVAILABLE) != 0) {
                double ms = GL33C.glGetQueryObjecti64(gpuQuery, GL15C.GL_QUERY_RESULT) / 1.0e6;
                if (gpuPendingHand) gpuHandMs = gpuHandMs == 0 ? ms : gpuHandMs * 0.9 + ms * 0.1;
                else gpuMs = gpuMs == 0 ? ms : gpuMs * 0.9 + ms * 0.1;
                gpuPending = false;
            }
            if (!gpuPending && ((frames + (handPass ? 4 : 0)) & 7) == 0 && GL15C.glGetQueryi(GL33C.GL_TIME_ELAPSED, GL15C.GL_CURRENT_QUERY) == 0) {
                GL15C.glBeginQuery(GL33C.GL_TIME_ELAPSED, gpuQuery);
                timed = true;
                gpuPendingHand = handPass;
            }
        }

        // ---- what is there now (the real state, not the game's record of it) ----
        int program0 = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        int vao0 = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
        int drawFbo0 = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        int readFbo0 = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        int active0 = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, VIEWPORT);
        boolean blend0 = GL11C.glIsEnabled(GL11C.GL_BLEND);
        int srcRgb0 = GL11C.glGetInteger(GL14C.GL_BLEND_SRC_RGB), dstRgb0 = GL11C.glGetInteger(GL14C.GL_BLEND_DST_RGB);
        int srcA0 = GL11C.glGetInteger(GL14C.GL_BLEND_SRC_ALPHA), dstA0 = GL11C.glGetInteger(GL14C.GL_BLEND_DST_ALPHA);
        boolean depth0 = GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST);
        boolean depthMask0 = GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK);
        boolean cull0 = GL11C.glIsEnabled(GL11C.GL_CULL_FACE);
        boolean scissor0 = GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST);
        boolean stencil0 = GL11C.glIsEnabled(GL11C.GL_STENCIL_TEST);
        COLOR_MASK.clear();
        GL11C.glGetBooleanv(GL11C.GL_COLOR_WRITEMASK, COLOR_MASK);
        boolean maskR = COLOR_MASK.get(0) != 0, maskG = COLOR_MASK.get(1) != 0, maskB = COLOR_MASK.get(2) != 0, maskA = COLOR_MASK.get(3) != 0;
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
        int tex00 = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D), sampler00 = GL11C.glGetInteger(GL33C.GL_SAMPLER_BINDING);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE1);
        int tex10 = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D), sampler10 = GL11C.glGetInteger(GL33C.GL_SAMPLER_BINDING);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE2);
        int tex20 = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D), sampler20 = GL11C.glGetInteger(GL33C.GL_SAMPLER_BINDING);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + EmitterVolume.TEXTURE_UNIT);
        int volume0 = GL11C.glGetInteger(GL12C.GL_TEXTURE_BINDING_3D);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + ShipEmitters.TEXTURE_UNIT);
        int ships0 = GL11C.glGetInteger(GL12C.GL_TEXTURE_BINDING_3D);

        try {
            if (prefilter == 0 && !compile()) return;
            GL33C.glBindSampler(0, 0);
            GL33C.glBindSampler(1, 0);
            if (targetsW != w || targetsH != h) targets(w, h);

            GL11C.glDisable(GL11C.GL_BLEND);
            GL11C.glDisable(GL11C.GL_DEPTH_TEST);
            GL11C.glDepthMask(false);
            GL11C.glDisable(GL11C.GL_CULL_FACE);
            GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
            GL11C.glDisable(GL11C.GL_STENCIL_TEST);
            GL11C.glColorMask(true, true, true, true);
            GL30C.glBindVertexArray(vao);
            if (DEBUG > 0 && frames % 600 == 300) probe(main, view, projection, cam, w, h);

            // ---- what glows, at half the picture's size ----
            boolean volume = !hand && EmitterVolume.bind();
            // the light sources on ships near the camera, in each ship's own grid
            int ships = hand || Minecraft.getInstance().level == null ? 0 : ShipEmitters.bind(Minecraft.getInstance().level, cam, shipMatrices);
            // the items that give light, as they were drawn
            int glows = hand ? ItemGlows.hand(glowPos, glowCol) : ItemGlows.level(view, glowPos, glowCol);
            boolean blacklight = ColorfulLightingConfig.blacklight();
            boolean field = !hand && blacklight && BlacklightField.bind();
            int uvLights = blacklight && !hand ? ShaderDynamicLights.blacklights(uvPos, uvDir, MAX_UV_LIGHTS) : 0;
            GL20C.glUseProgram(prefilter);
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo[0]);
            GL11C.glViewport(0, 0, widths[0], heights[0]);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE1);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, main.getDepthTextureId());
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, main.getColorTextureId());
            GL20C.glUniform1i(pColor, 0);
            GL20C.glUniform1i(pDepth, 1);
            GL20C.glUniform2i(pSize, w, h);
            MATRIX.clear();
            new Matrix4f(projection).mul(view).invert().get(MATRIX);
            GL20C.glUniformMatrix4fv(pInvViewProj, false, MATRIX);
            GL20C.glUniform1i(pEmitters, EmitterVolume.TEXTURE_UNIT);
            GL20C.glUniform1i(pVolume, volume ? 1 : 0);
            GL20C.glUniform1i(pShipEmitters, ShipEmitters.TEXTURE_UNIT);
            GL20C.glUniform1i(pGlows, glows);
            if (glows > 0) {
                GLOW_POS.clear();
                GLOW_POS.put(glowPos, 0, glows * 4).flip();
                GL20C.glUniform4fv(pGlowPos, GLOW_POS);
                GLOW_COL.clear();
                GLOW_COL.put(glowCol, 0, glows * 4).flip();
                GL20C.glUniform4fv(pGlowColor, GLOW_COL);
            }
            GL20C.glUniform1i(pShips, ships);
            if (ships > 0) {
                SHIP_MATRICES.clear();
                SHIP_MATRICES.put(shipMatrices, 0, ships * 16).flip();
                GL20C.glUniformMatrix4fv(pShipFromRel, false, SHIP_MATRICES);
            }
            if (volume) {
                int[] origin = EmitterVolume.origin();
                GL20C.glUniform3f(pVolumeSize, EmitterVolume.SX, EmitterVolume.SY, EmitterVolume.SZ);
                GL20C.glUniform3f(pCamInVolume, (float) mod(cam.x, EmitterVolume.SX), (float) mod(cam.y, EmitterVolume.SY), (float) mod(cam.z, EmitterVolume.SZ));
                GL20C.glUniform3f(pWindowMin, (float) (origin[0] - cam.x), (float) (origin[1] - cam.y), (float) (origin[2] - cam.z));
            }
            GL20C.glUniform1f(pThreshold, ColorfulLightingConfig.bloomThreshold());
            GL20C.glUniform1f(pGain, GAIN);
            GL20C.glUniform1i(pDebug, DEBUG);
            GL20C.glUniform1f(pUvGain, UV_GAIN);
            GL20C.glUniform1i(pUvFieldTex, BlacklightField.TEXTURE_UNIT);
            GL20C.glUniform1f(pUvStrength, field || uvLights > 0 ? ColorfulLightingConfig.blacklightStrength() : 0f);
            GL20C.glUniform1i(pUvField, field ? 1 : 0);
            if (field) {
                int[] origin = BlacklightField.origin();
                GL20C.glUniform3f(pUvFieldOrigin, (float) (origin[0] - cam.x), (float) (origin[1] - cam.y), (float) (origin[2] - cam.z));
                GL20C.glUniform1f(pUvFieldSize, BlacklightField.SIZE);
            }
            // the glow of the frame before, where this frame's pixels were then
            Matrix4f viewProj = new Matrix4f(projection).mul(view);
            float seconds = (started - prevNanos) / 1.0e9f;
            float keep = !hand && historyValid && DEBUG == 0 && SMOOTH_SECONDS > 0f && seconds > 0f && seconds < 0.25f && ColorfulLightingConfig.bloomSmoothing()
                    ? Math.min(0.85f, (float) Math.exp(-seconds / SMOOTH_SECONDS)) : 0f;
            double dx = cam.x - prevCamX, dy = cam.y - prevCamY, dz = cam.z - prevCamZ;
            if (dx * dx + dy * dy + dz * dz > 64.0) keep = 0f; // a teleport: nothing of the old picture is this one
            GL20C.glUniform1f(pKeep, keep);
            GL20C.glUniform1i(pHistory, 2);
            if (keep > 0f) {
                GL33C.glBindSampler(2, 0);
                GL13C.glActiveTexture(GL13C.GL_TEXTURE2);
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, historyTex);
                GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
                MATRIX.clear();
                PREV_VIEW_PROJ.get(MATRIX);
                GL20C.glUniformMatrix4fv(pPrevViewProj, false, MATRIX);
                GL20C.glUniform3f(pCamDelta, (float) dx, (float) dy, (float) dz);
            }
            if (!hand) {
                PREV_VIEW_PROJ.set(viewProj);
                prevCamX = cam.x;
                prevCamY = cam.y;
                prevCamZ = cam.z;
                prevNanos = started;
            }
            GL20C.glUniform1i(pUvLights, uvLights);
            if (uvLights > 0) {
                UV_POS.clear();
                UV_POS.put(uvPos, 0, uvLights * 4).flip();
                GL20C.glUniform4fv(pUvPos, UV_POS);
                UV_DIR.clear();
                UV_DIR.put(uvDir, 0, uvLights * 4).flip();
                GL20C.glUniform4fv(pUvDir, UV_DIR);
            }
            GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);
            // kept for the next frame, before the levels below are added onto it
            if (!hand && DEBUG == 0 && historyFbo != 0) {
                GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, fbo[0]);
                GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, historyFbo);
                GL30C.glBlitFramebuffer(0, 0, widths[0], heights[0], 0, 0, widths[0], heights[0], GL11C.GL_COLOR_BUFFER_BIT, GL11C.GL_NEAREST);
                historyValid = true;
            }

            // ---- down the pyramid ----
            GL20C.glUseProgram(down);
            GL20C.glUniform1i(dTex, 0);
            int n = DEBUG > 0 ? 1 : levels;
            for (int i = 1; i < n; i++) {
                GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo[i]);
                GL11C.glViewport(0, 0, widths[i], heights[i]);
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, tex[i - 1]);
                GL20C.glUniform2f(dTexel, 1f / widths[i - 1], 1f / heights[i - 1]);
                GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);
            }

            // ---- and up again, each level added to the one above ----
            GL20C.glUseProgram(up);
            GL20C.glUniform1i(uTex, 0);
            GL20C.glUniform1f(uSoft, 0f);
            GL11C.glEnable(GL11C.GL_BLEND);
            GL14C.glBlendFuncSeparate(GL11C.GL_ONE, GL11C.GL_ONE, GL11C.GL_ZERO, GL11C.GL_ONE);
            for (int i = n - 2; i >= 0; i--) {
                GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo[i]);
                GL11C.glViewport(0, 0, widths[i], heights[i]);
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, tex[i + 1]);
                GL20C.glUniform2f(uTexel, 1f / widths[i + 1], 1f / heights[i + 1]);
                // the two widest levels count for more: the glow reaches past a lamp's edges instead of whitening the lamp
                GL20C.glUniform1f(uScale, 1f + 0.5f * Math.max(0, i - (levels - 4)));
                GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);
            }

            // ---- onto the picture as a screen (what is bright already takes less, so a lamp keeps its texture), its alpha
            // left alone (Fabulous composes by it) ----
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, main.frameBufferId);
            GL11C.glViewport(0, 0, w, h);
            GL11C.glColorMask(true, true, true, false);
            GL14C.glBlendFuncSeparate(GL11C.GL_ONE_MINUS_DST_COLOR, GL11C.GL_ONE, GL11C.GL_ZERO, GL11C.GL_ONE);
            GL20C.glUniform1f(uSoft, 1f);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, tex[0]);
            GL20C.glUniform2f(uTexel, 1f / widths[0], 1f / heights[0]);
            GL20C.glUniform1f(uScale, ColorfulLightingConfig.bloomStrength() * STRENGTH_UNIT);
            if (DEBUG > 0) {
                GL11C.glDisable(GL11C.GL_BLEND);
                GL20C.glUniform1f(uSoft, 0f);
                GL20C.glUniform1f(uScale, 1f);
            }
            GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);
            if (hand) handFrames++;
            else frames++;
        } catch (RuntimeException e) {
            failed = true;
            ColorfulLighting.LOGGER.error("Bloom: the pass failed and is off until the game restarts", e);
        } finally {
            // ---- everything back as it was ----
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + ShipEmitters.TEXTURE_UNIT);
            GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, ships0);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + EmitterVolume.TEXTURE_UNIT);
            GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, volume0);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE2);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, tex20);
            GL33C.glBindSampler(2, sampler20);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE1);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, tex10);
            GL33C.glBindSampler(1, sampler10);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, tex00);
            GL33C.glBindSampler(0, sampler00);
            GL13C.glActiveTexture(active0);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, drawFbo0);
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, readFbo0);
            GL11C.glViewport(VIEWPORT[0], VIEWPORT[1], VIEWPORT[2], VIEWPORT[3]);
            GL11C.glColorMask(maskR, maskG, maskB, maskA);
            GL14C.glBlendFuncSeparate(srcRgb0, dstRgb0, srcA0, dstA0);
            set(GL11C.GL_BLEND, blend0);
            set(GL11C.GL_DEPTH_TEST, depth0);
            GL11C.glDepthMask(depthMask0);
            set(GL11C.GL_CULL_FACE, cull0);
            set(GL11C.GL_SCISSOR_TEST, scissor0);
            set(GL11C.GL_STENCIL_TEST, stencil0);
            GL30C.glBindVertexArray(vao0);
            GL20C.glUseProgram(program0);
            if (timed) {
                GL15C.glEndQuery(GL33C.GL_TIME_ELAPSED);
                gpuPending = true;
            }
            lastMs = (System.nanoTime() - started) / 1.0e6;
            averageMs = averageMs == 0 ? lastMs : averageMs * 0.98 + lastMs * 0.02;
        }
    }

    /** What a strength of 1 is: the share of the pyramid's sum that goes onto the picture. */
    private static final float STRENGTH_UNIT = Float.parseFloat(System.getProperty("colorfullighting.bloomUnit", "0.2"));
    /** A lamp shines more than the picture can show: its glow is this many times its pixels' colour. */
    private static final float GAIN = Float.parseFloat(System.getProperty("colorfullighting.bloomGain", "2.0"));
    /**
     * {@code -Dcolorfullighting.bloomDebug=1|2}: the picture is replaced by what the first pass sees. Red where the block a pixel
     * shows emits light; green and blue where in its block the pixel lies (1: along x and z, 2: up and its distance over 32).
     */
    private static final int DEBUG = Integer.getInteger("colorfullighting.bloomDebug", 0);
    /** The same for what glows under blacklight. */
    private static final float UV_GAIN = Float.parseFloat(System.getProperty("colorfullighting.bloomUvGain", "1.5"));

    /** The debug switch's log line: the depth the picture holds at three places, and what the pass is given. */
    private static void probe(RenderTarget main, Matrix4f view, Matrix4f projection, Vec3 cam, int w, int h) {
        StringBuilder out = new StringBuilder("Bloom probe: ");
        int[][] at = {{w / 2, h / 2}, {w / 2, h / 6}, {w / 2, h * 9 / 10}};
        float[] one = new float[1];
        for (RenderTarget target : new RenderTarget[] {main}) {
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, target.frameBufferId);
            out.append("main ").append(target.width).append('x').append(target.height).append(" depth");
            for (int[] p : at) {
                GL11C.glReadPixels(p[0], p[1], 1, 1, GL11C.GL_DEPTH_COMPONENT, GL11C.GL_FLOAT, one);
                out.append(' ').append(one[0]);
            }
            out.append("; ");
        }
        out.append("projection ").append(projection.m00()).append(' ').append(projection.m11()).append(' ').append(projection.m22()).append(' ')
                .append(projection.m32()).append(' ').append(projection.m23()).append("; view row ").append(view.m00()).append(' ').append(view.m10())
                .append(' ').append(view.m20()).append(' ').append(view.m30()).append("; cam ").append(cam).append("; volume origin ")
                .append(java.util.Arrays.toString(EmitterVolume.origin()));
        ColorfulLighting.LOGGER.info(out.toString());
    }

    private static void set(int capability, boolean on) {
        if (on) GL11C.glEnable(capability);
        else GL11C.glDisable(capability);
    }

    private static double mod(double value, int size) {
        double m = value % size;
        return m < 0 ? m + size : m;
    }

    /** The pyramid's targets for a picture of this size; unit 0 is the active one and loses its binding. */
    private static void targets(int w, int h) {
        for (int i = 0; i < LEVELS; i++) {
            if (fbo[i] != 0) GL30C.glDeleteFramebuffers(fbo[i]);
            if (tex[i] != 0) GL11C.glDeleteTextures(tex[i]);
            fbo[i] = tex[i] = 0;
        }
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
        int lw = w, lh = h;
        levels = 0;
        for (int i = 0; i < LEVELS; i++) {
            lw = Math.max(1, (lw + 1) / 2);
            lh = Math.max(1, (lh + 1) / 2);
            if (i > 1 && (lw < 16 || lh < 16)) break;
            widths[i] = lw;
            heights[i] = lh;
            tex[i] = GL11C.glGenTextures();
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, tex[i]);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL12C.GL_TEXTURE_BASE_LEVEL, 0);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL12C.GL_TEXTURE_MAX_LEVEL, 0);
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL30C.GL_R11F_G11F_B10F, lw, lh, 0, GL11C.GL_RGB, GL11C.GL_FLOAT, (ByteBuffer) null);
            fbo[i] = GL30C.glGenFramebuffers();
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo[i]);
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, tex[i], 0);
            int status = GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER);
            if (status != GL30C.GL_FRAMEBUFFER_COMPLETE) throw new IllegalStateException("bloom target " + i + " (" + lw + "x" + lh + ") is incomplete: 0x" + Integer.toHexString(status));
            levels++;
        }
        // the glow of the frame before, at the first level's size
        if (historyFbo != 0) GL30C.glDeleteFramebuffers(historyFbo);
        if (historyTex != 0) GL11C.glDeleteTextures(historyTex);
        historyValid = false;
        historyTex = GL11C.glGenTextures();
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, historyTex);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL12C.GL_TEXTURE_BASE_LEVEL, 0);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL12C.GL_TEXTURE_MAX_LEVEL, 0);
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL30C.GL_R11F_G11F_B10F, widths[0], heights[0], 0, GL11C.GL_RGB, GL11C.GL_FLOAT, (ByteBuffer) null);
        historyFbo = GL30C.glGenFramebuffers();
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, historyFbo);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, historyTex, 0);
        if (GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER) != GL30C.GL_FRAMEBUFFER_COMPLETE) {
            GL30C.glDeleteFramebuffers(historyFbo);
            historyFbo = 0;
        }
        targetsW = w;
        targetsH = h;
    }

    private static boolean compile() {
        try {
            String vertex = read("fullscreen.vsh");
            prefilter = link("prefilter", vertex, read("prefilter.fsh"));
            down = link("down", vertex, read("down.fsh"));
            up = link("up", vertex, read("up.fsh"));
            vao = GL30C.glGenVertexArrays();
            pColor = GL20C.glGetUniformLocation(prefilter, "u_Color");
            pDepth = GL20C.glGetUniformLocation(prefilter, "u_Depth");
            pSize = GL20C.glGetUniformLocation(prefilter, "u_Size");
            pInvViewProj = GL20C.glGetUniformLocation(prefilter, "u_InvViewProj");
            pVolume = GL20C.glGetUniformLocation(prefilter, "u_Volume");
            pEmitters = GL20C.glGetUniformLocation(prefilter, "u_Emitters");
            pVolumeSize = GL20C.glGetUniformLocation(prefilter, "u_VolumeSize");
            pCamInVolume = GL20C.glGetUniformLocation(prefilter, "u_CamInVolume");
            pWindowMin = GL20C.glGetUniformLocation(prefilter, "u_WindowMin");
            pThreshold = GL20C.glGetUniformLocation(prefilter, "u_Threshold");
            pGain = GL20C.glGetUniformLocation(prefilter, "u_Gain");
            pDebug = GL20C.glGetUniformLocation(prefilter, "u_Debug");
            pUvGain = GL20C.glGetUniformLocation(prefilter, "u_UvGain");
            pUvStrength = GL20C.glGetUniformLocation(prefilter, "u_UvStrength");
            pUvField = GL20C.glGetUniformLocation(prefilter, "u_UvField");
            pUvFieldTex = GL20C.glGetUniformLocation(prefilter, "u_UvFieldTex");
            pUvFieldOrigin = GL20C.glGetUniformLocation(prefilter, "u_UvFieldOrigin");
            pUvFieldSize = GL20C.glGetUniformLocation(prefilter, "u_UvFieldSize");
            pUvLights = GL20C.glGetUniformLocation(prefilter, "u_UvLights");
            pUvPos = GL20C.glGetUniformLocation(prefilter, "u_UvPos");
            pUvDir = GL20C.glGetUniformLocation(prefilter, "u_UvDir");
            pHistory = GL20C.glGetUniformLocation(prefilter, "u_History");
            pPrevViewProj = GL20C.glGetUniformLocation(prefilter, "u_PrevViewProj");
            pCamDelta = GL20C.glGetUniformLocation(prefilter, "u_CamDelta");
            pKeep = GL20C.glGetUniformLocation(prefilter, "u_Keep");
            pGlows = GL20C.glGetUniformLocation(prefilter, "u_Glows");
            pGlowPos = GL20C.glGetUniformLocation(prefilter, "u_GlowPos");
            pGlowColor = GL20C.glGetUniformLocation(prefilter, "u_GlowColor");
            pShips = GL20C.glGetUniformLocation(prefilter, "u_Ships");
            pShipFromRel = GL20C.glGetUniformLocation(prefilter, "u_ShipFromRel");
            pShipEmitters = GL20C.glGetUniformLocation(prefilter, "u_ShipEmitters");
            dTex = GL20C.glGetUniformLocation(down, "u_Tex");
            dTexel = GL20C.glGetUniformLocation(down, "u_Texel");
            uTex = GL20C.glGetUniformLocation(up, "u_Tex");
            uTexel = GL20C.glGetUniformLocation(up, "u_Texel");
            uScale = GL20C.glGetUniformLocation(up, "u_Scale");
            uSoft = GL20C.glGetUniformLocation(up, "u_Soft");
            return true;
        } catch (RuntimeException | IOException e) {
            failed = true;
            prefilter = 0;
            ColorfulLighting.LOGGER.error("Bloom: its shaders could not be built; the bloom is off until the game restarts", e);
            return false;
        }
    }

    private static String read(String name) throws IOException {
        try (InputStream in = Bloom.class.getResourceAsStream(PATH + name)) {
            if (in == null) throw new IOException("missing " + PATH + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int link(String name, String vertex, String fragment) {
        int vs = shader(name + " (vertex)", GL20C.GL_VERTEX_SHADER, vertex);
        int fs = shader(name, GL20C.GL_FRAGMENT_SHADER, fragment);
        int program = GL20C.glCreateProgram();
        GL20C.glAttachShader(program, vs);
        GL20C.glAttachShader(program, fs);
        GL20C.glLinkProgram(program);
        GL20C.glDeleteShader(vs);
        GL20C.glDeleteShader(fs);
        if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) == 0) {
            String log = GL20C.glGetProgramInfoLog(program);
            GL20C.glDeleteProgram(program);
            throw new IllegalStateException("the bloom's " + name + " program does not link: " + log);
        }
        return program;
    }

    private static int shader(String name, int type, String source) {
        int id = GL20C.glCreateShader(type);
        GL20C.glShaderSource(id, source);
        GL20C.glCompileShader(id);
        if (GL20C.glGetShaderi(id, GL20C.GL_COMPILE_STATUS) == 0) {
            String log = GL20C.glGetShaderInfoLog(id);
            GL20C.glDeleteShader(id);
            throw new IllegalStateException("the bloom's " + name + " shader does not compile: " + log);
        }
        return id;
    }
}
