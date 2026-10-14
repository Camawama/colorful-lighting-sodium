package net.camacraft.colorfullighting.compat.distanthorizons;

import com.seibel.distanthorizons.api.interfaces.override.rendering.IDhApiShaderProgram;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.api.objects.math.DhApiMat4f;
import com.seibel.distanthorizons.api.objects.math.DhApiVec3f;
import net.camacraft.colorfullighting.ColorfulLighting;
import net.camacraft.colorfullighting.common.ColoredLightEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL41;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Replacement for DH's terrain shader program that samples the remembered colour volume and tints
 * the block-light part of every LOD fragment. Registered through {@code DhApi.overrides}, so no DH
 * internals are touched; if anything in here fails, {@link #overrideThisFrame()} starts returning
 * false and DH silently falls back to its own shader.
 *
 * <p>Supports DH 3.1.2 and DH 3.2.0. Under 3.2.0 (detected via the shader contract check) the
 * third vertex attribute carries {@code irisData} (face normal + block texture tile id) and DH
 * binds a block texture atlas on texture unit 1; the tile modulation from DH's terrain frag is
 * replicated so textured LODs keep their textures when colored. 3.2.0 also fixed the
 * always-uses-bound-override quirk (it consults {@code overrideThisFrame()}), and its
 * {@code uClipDistance} value is unchanged (nearClipPlane + 16).
 *
 * <p>Mirrors {@code GlDhTerrainShaderProgram_forge} (DH 3.1.2, decompiled 2026-08-05):
 * <ul>
 * <li>Vertex format, stride 16: attr0 = 4 x u16 as integers ({@code uvec4 vPosition}: xyz local to
 *     the buffer + 16-bit meta whose low byte is {@code sky*16+block} light and bits 8-13 a micro
 *     offset), attr1 = 4 x u8 normalized (albedo), attr2 = 4 x u8 (unused).</li>
 * <li>Attribute names bound pre-link: {@code vPosition} to 0, {@code color} to 1.</li>
 * <li>{@code uCombinedMatrix} = dhProjection x dhModelView. DH's {@code storeMatrixInBuffer} writes
 *     column-major ({@code bufferIndex(row,col) = col*4 + row}); {@code putValuesInArray} is
 *     row-major, so the upload uses transpose=true.</li>
 * <li>{@code uModelOffset} per buffer = minCornerBlockPos - exactCameraPosition, via
 *     {@link #setModelOffsetPos}; vertex world pos is camera-relative, so absolute position =
 *     vertexWorldPos + camera.</li>
 * <li>{@code uClipDistance} = nearClipPlane + 16 with dithered fade, DH's default. (The July 2026
 *     attempt guessed renderDistance*16 here, which drew a wrong-sized transparent ring.)</li>
 * <li>DH calls {@code bind()} FIRST (not {@code overrideThisFrame()}), so GL init is lazy in every
 *     entry point. The element buffer DH binds after {@code bind()} lands in our VAO, as intended.</li>
 * </ul>
 *
 * <p>Every DH-facing callback is wrapped: one failure flips {@link #failed} and the override backs
 * off for the rest of the session instead of blanking DH's LODs.
 */
public final class DhTerrainColorShaderProgram implements IDhApiShaderProgram {
    private static final String VERT_PATH = "/assets/colorful_lighting/shaders/dh/colorful_dh_terrain.vert";
    private static final String FRAG_PATH = "/assets/colorful_lighting/shaders/dh/colorful_dh_terrain.frag";
    private static final int VERTEX_STRIDE_BYTES = 16;
    /** Well clear of unit 0, where DH binds the vanilla lightmap. */
    private static final int NEAR_VOLUME_TEXTURE_UNIT = 4;
    private static final int FAR_VOLUME_TEXTURE_UNIT = 5;
    private static final int ULTRA_VOLUME_TEXTURE_UNIT = 6;

    private boolean initialized;
    private volatile boolean failed;
    private int program;
    private int vao;
    /**
     * GL 4.1 {@code glProgramUniform*} writes a uniform on OUR program regardless of which program
     * is bound, so the hot per-buffer paths skip the GL_CURRENT_PROGRAM query + glUseProgram guard
     * entirely (they were ~5% of render-thread time in the 2026-08-06 profile at DH distances).
     */
    private boolean dsaUniforms;

    private int uCombinedMatrix;
    private int uModelOffset;
    private int uWorldYOffset;
    private int uMircoOffset;
    private int uLightMap;
    private int uClipDistance;
    private int uClVolumeNear;
    private int uClVolumeFar;
    private int uClCameraPos;
    private int uClNearMin;
    private int uClNearInvSize;
    private int uClFarMin;
    private int uClFarInvSize;
    private int uClVolumeUltra;
    private int uClUltraMin;
    private int uClUltraInvSize;
    private int uClDebugMode;
    private int uClHasNormals;
    private int uClHalfTexel;
    private int uClNightVibrancy;
    /** DH 3.2 textured LODs: DH binds its block atlas on this unit before our fillUniformData. */
    private static final int BLOCK_ATLAS_TEXTURE_UNIT = 1;
    private int uClBlockAtlas;
    private int uClTexturedLods;

    private final float[] matrixScratch = new float[16];

    @Override
    public boolean overrideThisFrame() {
        return !failed && DhCompat.isOverrideEnabled() && ColoredLightEngine.isEnabled() && ensureInitialized();
    }

    @Override
    public int getId() {
        ensureInitialized();
        return program;
    }

    /**
     * DH calls {@code bind()} once per LOD buffer, and re-issuing glUseProgram per buffer was
     * ~4% of render-thread time in profiling (a redundant glUseProgram still invalidates the
     * driver's derived state). Also the fallback for uniform updates when GL 4.1's
     * glProgramUniform is unavailable. Skipping is only
     * safe when our program really is current, and that CANNOT be tracked with a flag: DH
     * switches to its own programs (SSAO, fog, clouds) between the opaque and transparent
     * passes without calling {@code unbind()} on the override — a flag-based skip drew the
     * water pass through DH's fog program and made LOD water invisible. GL_CURRENT_PROGRAM is
     * client-side state, so querying it is nanoseconds and immune to whatever DH ran in
     * between.
     */
    private void useProgram() {
        if (GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) != program) {
            GL20.glUseProgram(program);
        }
    }

    @Override
    public void bind() {
        if (!ensureInitialized()) return;
        try {
            useProgram();
            GL30.glBindVertexArray(vao);
        } catch (Throwable t) {
            fail("bind", t);
        }
    }

    @Override
    public void unbind() {
        try {
            GL30.glBindVertexArray(0);
            GL20.glUseProgram(0);
        } catch (Throwable t) {
            fail("unbind", t);
        }
    }

    @Override
    public void bindVertexBuffer(int vbo) {
        if (!ensureInitialized()) return;
        try {
            // Pointer state is per-buffer in core GL, so respecify on every buffer like DH's
            // pre-GL43 path does. Our VAO is bound (bind() ran earlier in DH's draw loop).
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            GL30.glVertexAttribIPointer(0, 4, GL11.GL_UNSIGNED_SHORT, VERTEX_STRIDE_BYTES, 0);
            GL20.glVertexAttribPointer(1, 4, GL11.GL_UNSIGNED_BYTE, true, VERTEX_STRIDE_BYTES, 8);
            if (DhCompatImpl.isTexturedLodContract()) {
                // DH 3.2: the third attribute (bytes 12-15, unused padding in 3.1.2) carries
                // irisData — y = face normal index, zw = block texture tile id.
                GL30.glVertexAttribIPointer(2, 4, GL11.GL_UNSIGNED_BYTE, VERTEX_STRIDE_BYTES, 12);
            }
        } catch (Throwable t) {
            fail("bindVertexBuffer", t);
        }
    }

    @Override
    public void setModelOffsetPos(DhApiVec3f modelPos) {
        if (!ensureInitialized()) return;
        try {
            // Hottest override entry point: called once per LOD buffer.
            uniform3f(uModelOffset, modelPos.x, modelPos.y, modelPos.z);
        } catch (Throwable t) {
            fail("setModelOffsetPos", t);
        }
    }

    // Program-targeted uniform setters: with GL 4.1 they need no current program at all; the
    // fallback keeps the pre-4.1 behaviour (callers ran useProgram() before them anyway).
    private void uniform1i(int location, int value) {
        if (dsaUniforms) GL41.glProgramUniform1i(program, location, value);
        else { useProgram(); GL20.glUniform1i(location, value); }
    }

    private void uniform1f(int location, float value) {
        if (dsaUniforms) GL41.glProgramUniform1f(program, location, value);
        else { useProgram(); GL20.glUniform1f(location, value); }
    }

    private void uniform3f(int location, float x, float y, float z) {
        if (dsaUniforms) GL41.glProgramUniform3f(program, location, x, y, z);
        else { useProgram(); GL20.glUniform3f(location, x, y, z); }
    }

    private void uniformMatrix4(int location, boolean transpose, float[] values) {
        if (dsaUniforms) GL41.glProgramUniformMatrix4fv(program, location, transpose, values);
        else { useProgram(); GL20.glUniformMatrix4fv(location, transpose, values); }
    }

    @Override
    public void fillUniformData(DhApiRenderParam param) {
        if (!ensureInitialized()) return;
        try {
            // Keep the colour volume fresh; internally throttled, usually a no-op.
            Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            DhColorVolume volume = DhCompat.getOrCreateVolume();
            volume.update(DhCompat.getActiveCache(), camera.x, camera.y, camera.z);

            DhApiMat4f combined = new DhApiMat4f(param.dhProjectionMatrix);
            combined.multiply(param.dhModelViewMatrix);
            combined.putValuesInArray(matrixScratch);
            // putValuesInArray is row-major (mRC rows first); DH's own storeMatrixInBuffer writes
            // col*4+row (column-major), so upload with transpose=true to match. Getting this wrong
            // renders the LODs as a camera-glued inverted-rotation sheet (test #2, 2026-08-05).
            uniformMatrix4(uCombinedMatrix, true, matrixScratch);

            uniform1f(uMircoOffset, 0.01f);
            uniform1i(uLightMap, 0);
            uniform1f(uWorldYOffset, param.worldYOffset);
            uniform1f(uClipDistance, param.nearClipPlane + 16.0f);

            uniform1i(uClVolumeNear, NEAR_VOLUME_TEXTURE_UNIT);
            uniform1i(uClVolumeFar, FAR_VOLUME_TEXTURE_UNIT);
            uniform1i(uClVolumeUltra, ULTRA_VOLUME_TEXTURE_UNIT);
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + NEAR_VOLUME_TEXTURE_UNIT);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, volume.nearTextureId());
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + FAR_VOLUME_TEXTURE_UNIT);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, volume.farTextureId());
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + ULTRA_VOLUME_TEXTURE_UNIT);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, volume.ultraTextureId());
            GL13.glActiveTexture(GL13.GL_TEXTURE0);

            uniform3f(uClCameraPos, (float) camera.x, (float) camera.y, (float) camera.z);
            uniform3f(uClNearMin, volume.nearMinX(), volume.nearMinY(), volume.nearMinZ());
            uniform1f(uClNearInvSize, volume.nearInvSizeBlocks());
            uniform3f(uClFarMin, volume.farMinX(), volume.farMinY(), volume.farMinZ());
            uniform1f(uClFarInvSize, volume.farInvSizeBlocks());
            uniform3f(uClUltraMin, volume.ultraMinX(), volume.ultraMinY(), volume.ultraMinZ());
            uniform1f(uClUltraInvSize, volume.ultraInvSizeBlocks());
            uniform1i(uClDebugMode, DhCompat.getDebugMode());
            // The volumes are read half a texel in front of each face (DH 3.2+ sends face normals).
            uniform1i(uClHasNormals, DhCompatImpl.isTexturedLodContract() ? 1 : 0);
            uniform3f(uClHalfTexel, DhColorVolume.NEAR_BLOCKS_PER_TEXEL * 0.5f,
                    DhColorVolume.FAR_BLOCKS_PER_TEXEL * 0.5f, DhColorVolume.ULTRA_BLOCKS_PER_TEXEL * 0.5f);
            uniform1f(uClNightVibrancy, nightVibrancy());

            // DH 3.2 textured LODs: only sample the atlas when DH's config wants it AND DH's
            // meta renderer actually bound an atlas on unit 1 this pass (it skips the bind when
            // the feature is off or shaders are active, leaving the unit's binding arbitrary).
            boolean textured = DhCompatImpl.texturedLodsEnabled();
            if (textured) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + BLOCK_ATLAS_TEXTURE_UNIT);
                textured = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D) != 0;
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
            }
            uniform1i(uClTexturedLods, textured ? 1 : 0);
            uniform1i(uClBlockAtlas, BLOCK_ATLAS_TEXTURE_UNIT);
        } catch (Throwable t) {
            fail("fillUniformData", t);
        }
    }

    /** The in-world shaders' night vibrancy (SodiumShaderCompat), so LODs wash colours the same way. */
    private static float nightVibrancy() {
        var level = Minecraft.getInstance().level;
        if (level == null) return 0.0f;
        float night = level.getStarBrightness(Minecraft.getInstance().getFrameTime());
        float vibrancy = night * net.camacraft.colorfullighting.common.Config.getMoonVibrancy(level.getMoonPhase());
        return Math.max(0.0f, Math.min(1.0f, vibrancy));
    }

    @Override
    public void free() {
        try {
            if (program != 0) GL20.glDeleteProgram(program);
            if (vao != 0) GL30.glDeleteVertexArrays(vao);
        } catch (Throwable ignored) {
        }
        program = 0;
        vao = 0;
        initialized = false;
    }

    /**
     * Lazy GL setup: DH invokes the callbacks on its render thread with a current context, and which
     * callback comes first differs between DH builds (3.1.2 starts with bind()).
     */
    private boolean ensureInitialized() {
        if (initialized) return !failed;
        if (failed) return false;
        try {
            init();
            initialized = true;
            ColorfulLighting.LOGGER.info("[DH override] shader program initialised (program id {})", program);
            return true;
        } catch (Throwable t) {
            fail("init", t);
            return false;
        }
    }

    private void init() throws IOException {
        dsaUniforms = GL.getCapabilities().OpenGL41;
        if (!dsaUniforms) {
            ColorfulLighting.LOGGER.info("[DH override] GL 4.1 not available, using bound-program uniform updates");
        }
        int vert = compileShader(GL20.GL_VERTEX_SHADER, readResource(VERT_PATH), VERT_PATH);
        int frag;
        try {
            frag = compileShader(GL20.GL_FRAGMENT_SHADER, readResource(FRAG_PATH), FRAG_PATH);
        } catch (RuntimeException | IOException e) {
            GL20.glDeleteShader(vert);
            throw e;
        }

        program = GL20.glCreateProgram();
        GL20.glAttachShader(program, vert);
        GL20.glAttachShader(program, frag);
        // Same attribute locations DH assigns to its own program; the vertex buffers expect them.
        GL20.glBindAttribLocation(program, 0, "vPosition");
        GL20.glBindAttribLocation(program, 1, "color");
        GL20.glBindAttribLocation(program, 2, "irisData");
        GL20.glLinkProgram(program);
        GL20.glDetachShader(program, vert);
        GL20.glDetachShader(program, frag);
        GL20.glDeleteShader(vert);
        GL20.glDeleteShader(frag);
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(program);
            GL20.glDeleteProgram(program);
            program = 0;
            throw new RuntimeException("program link failed: " + log);
        }

        uCombinedMatrix = GL20.glGetUniformLocation(program, "uCombinedMatrix");
        uModelOffset = GL20.glGetUniformLocation(program, "uModelOffset");
        uWorldYOffset = GL20.glGetUniformLocation(program, "uWorldYOffset");
        uMircoOffset = GL20.glGetUniformLocation(program, "uMircoOffset");
        uLightMap = GL20.glGetUniformLocation(program, "uLightMap");
        uClipDistance = GL20.glGetUniformLocation(program, "uClipDistance");
        uClVolumeNear = GL20.glGetUniformLocation(program, "uClVolumeNear");
        uClVolumeFar = GL20.glGetUniformLocation(program, "uClVolumeFar");
        uClCameraPos = GL20.glGetUniformLocation(program, "uClCameraPos");
        uClNearMin = GL20.glGetUniformLocation(program, "uClNearMin");
        uClNearInvSize = GL20.glGetUniformLocation(program, "uClNearInvSize");
        uClFarMin = GL20.glGetUniformLocation(program, "uClFarMin");
        uClFarInvSize = GL20.glGetUniformLocation(program, "uClFarInvSize");
        uClVolumeUltra = GL20.glGetUniformLocation(program, "uClVolumeUltra");
        uClUltraMin = GL20.glGetUniformLocation(program, "uClUltraMin");
        uClUltraInvSize = GL20.glGetUniformLocation(program, "uClUltraInvSize");
        uClDebugMode = GL20.glGetUniformLocation(program, "uClDebugMode");
        uClHasNormals = GL20.glGetUniformLocation(program, "uClHasNormals");
        uClHalfTexel = GL20.glGetUniformLocation(program, "uClHalfTexel");
        uClNightVibrancy = GL20.glGetUniformLocation(program, "uClNightVibrancy");
        uClBlockAtlas = GL20.glGetUniformLocation(program, "uClBlockAtlas");
        uClTexturedLods = GL20.glGetUniformLocation(program, "uClTexturedLods");

        vao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vao);
        GL20.glEnableVertexAttribArray(0);
        GL20.glEnableVertexAttribArray(1);
        // Under 3.1.2 the array stays disabled: the bytes at offset 12 are unused padding, and
        // the shader's tile path is gated off by uClTexturedLods anyway.
        if (DhCompatImpl.isTexturedLodContract()) {
            GL20.glEnableVertexAttribArray(2);
        }
        GL30.glBindVertexArray(0);
    }

    private static int compileShader(int type, String source, String name) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            throw new RuntimeException(name + " compile failed: " + log);
        }
        return shader;
    }

    private static String readResource(String path) throws IOException {
        try (InputStream in = DhTerrainColorShaderProgram.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException("missing classpath resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Sticky for the session; a failed program must never be re-bound (bind() would no-op and DH would draw nothing). */
    boolean hasFailed() {
        return failed;
    }

    private void fail(String where, Throwable t) {
        if (!failed) {
            failed = true;
            ColorfulLighting.LOGGER.error(
                    "[DH override] {} failed; disabling colored LOD lighting for this session (DH falls back to its own shader)",
                    where, t);
            // DH 3.1.2 does NOT consult our overrideThisFrame() when picking the frame's program
            // (GlDhMetaRenderer_forge checks the default program's, a DH quirk), so returning false
            // is not enough — a bound-but-broken override would keep glitching LODs forever.
            // Actually unbinding is the only real fallback. (DH 3.2.0 fixed the quirk and does
            // consult the override, but unbinding covers both versions.)
            DhCompat.requestEmergencyUnbind();
        }
    }
}
