package net.camacraft.colorfullighting.compat.dynamiclights;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.lwjgl.opengl.GL20C;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The shader path for dynamic lights (2.8.0): the external providers' lights drawn in the Embeddium terrain shaders
 * ({@code light.glsl}, {@code cl_dynamicLight}) and in Flywheel's ({@code colored_light.glsl}, {@code common.frag}), so a
 * light that moves rebuilds no mesh. The arrays uploaded here carry every light nearest the camera (at most
 * {@link #maxLights}, within {@link #maxDistance}), camera-relative, for the analytic falloff the shaders compute at each
 * point: position and level (the falloff folded into the level's 32s), colour and range, direction and the cosine of the
 * cone's half angle (1 for a point light), and since 2.10.0 each light's slab in the {@link DynamicLightField} (where its light
 * may go through the blocks; the shaders scale the analytic light by it), plus the inverse of the camera's rotation so a vertex
 * of a Valkyrien Skies ship, drawn in the ship's own frame, finds its place in the world.
 * Available where Embeddium's pipeline runs Colorful Lighting's shaders and no Oculus shader pack replaces them (under a pack
 * the lights go through the packed light the pack decodes instead). Uniform locations are cached per program handle; a
 * program without the uniforms (the define off, a pack's program) is skipped.
 */
public final class ShaderDynamicLights {
    /** The highest level a point light is taken at (2.10.1); below the 32 the falloff is folded in as. */
    public static final float MAX_LEVEL = 31f;
    public static final int MAX_LIGHTS = 64;
    private static volatile boolean wired;
    private static volatile int maxLights = 16;
    private static volatile float maxDistance = 64f;

    private static volatile boolean transmission;

    /** Light through blocks for the lights (the provider's client.transmission): propagated through the blocks round the camera. */
    public static void setTransmission(boolean value) {
        transmission = value;
        VoxelOcclusion.setEnabled(value);
        DynamicLightField.setEnabled(value);
    }

    public static boolean transmission() {
        return transmission;
    }

    /** Uniform locations of one program: count, pos[], color[], dir[], invView, slabs, slab[], the three slab textures, their sizes and counts; -1 where missing. */
    private record Locations(int count, int pos, int color, int dir, int invView, int slabs, int slab, int slabTex0, int slabTex1, int slabTex2, int slabSize, int slabCount, int fragSpots,
                             int uvBits, int uvStrength, int uvField, int uvFieldTex, int uvFieldOrigin, int uvFieldSize, int absBits) {
        boolean usable() {
            return count >= 0 && pos >= 0 && color >= 0 && dir >= 0;
        }
    }

    private static final Map<Integer, Locations> LOCATIONS = new ConcurrentHashMap<>();
    private static final Locations NONE = new Locations(-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1);

    // the frame's light arrays, built once per frame (keyed by the frame's partial tick and level) and uploaded per pass
    private static float[] pos = new float[MAX_LIGHTS * 4], color = new float[MAX_LIGHTS * 4], dir = new float[MAX_LIGHTS * 4], slab = new float[MAX_LIGHTS * 4];
    private static final float[] SLAB_SCRATCH = new float[4];
    private static final float[] invView = new float[9];
    // LWJGL takes direct buffers only (a heap buffer's address is garbage: an access violation that kills the JVM); one of each, reused
    private static final java.nio.FloatBuffer POS_BUF = org.lwjgl.BufferUtils.createFloatBuffer(MAX_LIGHTS * 4);
    private static final java.nio.FloatBuffer COLOR_BUF = org.lwjgl.BufferUtils.createFloatBuffer(MAX_LIGHTS * 4);
    private static final java.nio.FloatBuffer DIR_BUF = org.lwjgl.BufferUtils.createFloatBuffer(MAX_LIGHTS * 4);
    private static final java.nio.FloatBuffer SLAB_BUF = org.lwjgl.BufferUtils.createFloatBuffer(MAX_LIGHTS * 4);
    private static final java.nio.FloatBuffer INV_VIEW_BUF = org.lwjgl.BufferUtils.createFloatBuffer(9);
    private static int count;
    /** How many of the frame's lights are spot lights: they come first in the arrays (the fragments draw them, 2.10.3). */
    private static int spots;
    /** Which of the frame's lights are blacklights (2.11.0): a bit a light, the first 32 and the rest. */
    private static int uvBits0, uvBits1;
    /** Which of the frame's lights absorb light (2.12.0), a bit a light as the ultraviolet ones. */
    private static int absBits0, absBits1;
    private static Vec3 builtCam = Vec3.ZERO;
    private static float builtPartialTick = Float.NaN;
    private static ClientLevel builtLevel;
    private static long builtFrame = -1;
    private static long frameCounter;
    private static long builtNanos;

    private ShaderDynamicLights() {}

    /** Set once a Sodium pass has uploaded the uniforms: the terrain path is live. */
    public static void setWired(boolean value) {
        wired = value;
    }

    public static boolean available() {
        return wired && net.camacraft.colorfullighting.compat.sodium.SodiumCompat.isSodiumLoaded() && !shaderPackActive() && DynamicLightsCompat.hasExternalProviders();
    }

    /** The provider's limits (Lively Lighting's client.shader_max_lights and shader_max_distance). */
    public static void setLimits(int lights, float distance) {
        maxLights = Math.max(0, Math.min(MAX_LIGHTS, lights));
        maxDistance = Math.max(1f, distance);
    }

    /** Whether an Oculus/Iris shader pack is in use (by reflection: Oculus is optional). */
    public static boolean shaderPackActive() {
        try {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object instance = api.getMethod("getInstance").invoke(null);
            return (boolean) api.getMethod("isShaderPackInUse").invoke(instance);
        } catch (Throwable t) {
            return false;
        }
    }

    /** A new frame began (called once a frame from the level renderer): the light arrays are rebuilt on the next upload. */
    public static void newFrame() {
        frameCounter++;
    }

    /** The frame's number (one up per level render), for what is gathered once a frame. */
    public static long frame() {
        return frameCounter;
    }

    /** Whether this frame has lights to draw; builds the arrays for the frame if not yet done. Render thread. */
    private static boolean prepare() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            count = 0;
            uvBits0 = uvBits1 = 0;
            absBits0 = absBits1 = 0;
            builtLevel = null;
            return false;
        }
        float partialTick = mc.getFrameTime();
        // the same frame (the frame counter, or failing its hook, a fresh build within the last 40 ms) reuses the arrays
        long now = System.nanoTime();
        if (builtFrame == frameCounter && builtLevel == level && builtPartialTick == partialTick && now - builtNanos < 40_000_000L) return count > 0;
        builtFrame = frameCounter;
        builtNanos = now;
        builtLevel = level;
        builtPartialTick = partialTick;
        count = 0;
        uvBits0 = uvBits1 = 0;
            absBits0 = absBits1 = 0;
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 cam = camera.getPosition();
        builtCam = cam;
        // eye space back to the world's axes: the inverse of the view rotation the game builds for the frame (pitch about X,
        // then yaw plus 180 about Y, as GameRenderer.renderLevel does; a rotation's inverse is its transpose). The camera's own
        // quaternion is not that inverse: used as such it turned every vertex half round the camera, and the lights landed behind
        // the player (2026-11-09, the owner's torch lighting far rectangles and not the ground at their feet). Built whether or
        // not there are lights: the blacklight field is read at the same places (2.11.0)
        Matrix3f view = new Matrix3f().rotation(com.mojang.math.Axis.XP.rotationDegrees(camera.getXRot()))
                .rotate(com.mojang.math.Axis.YP.rotationDegrees(camera.getYRot() + 180f));
        view.transpose().get(invView);
        // the shaders draw only while meshes don't bake the lights (else the light would show twice); the count stays 0 otherwise
        if (!DynamicLightsCompat.hasExternalProviders() || DynamicLightsCompat.externalMeshLighting()) return false;
        List<DynamicLightsCompat.FrameLight> lights = DynamicLightsCompat.externalLightsForFrame(level, partialTick);
        List<DynamicLightsCompat.FrameLight> near = new ArrayList<>(lights.size());
        double maxD2 = (double) maxDistance * maxDistance;
        for (DynamicLightsCompat.FrameLight fl : lights) {
            float[] l = fl.v();
            double dx = l[0] - cam.x, dy = l[1] - cam.y, dz = l[2] - cam.z;
            double d2 = dx * dx + dy * dy + dz * dz;
            double reach = l[7] + 1.0;
            if (d2 - reach * reach > maxD2) continue;
            near.add(fl);
        }
        near.sort((a, b) -> Double.compare(dist2(a.v(), cam), dist2(b.v(), cam)));
        int n = Math.min(maxLights, near.size());
        // the spot lights first (the terrain's fragments draw the first of the arrays, its vertices the rest), each kind nearest first
        List<DynamicLightsCompat.FrameLight> picked = new ArrayList<>(n);
        for (int i = 0; i < n; i++) if (near.get(i).v()[11] < 1f) picked.add(near.get(i));
        spots = picked.size();
        for (int i = 0; i < n; i++) if (!(near.get(i).v()[11] < 1f)) picked.add(near.get(i));
        for (int i = 0; i < n; i++) {
            DynamicLightsCompat.FrameLight fl = picked.get(i);
            float[] l = fl.v();
            int o = i * 4;
            pos[o] = (float) (l[0] - cam.x);
            pos[o + 1] = (float) (l[1] - cam.y);
            pos[o + 2] = (float) (l[2] - cam.z);
            // the level, with the falloff (0 diamond, 1 round) folded in as 32s; a spot is always round. A point light's level
            // may exceed 15 (2.10.1): the shaders cap the light itself at 15, so the blocks above are a core of full light
            pos[o + 3] = Math.min(MAX_LEVEL, l[3]) + (l[13] >= 0.5f ? 32f : 0f);
            color[o] = l[4];
            color[o + 1] = l[5];
            color[o + 2] = l[6];
            color[o + 3] = Math.max(1f, l[7]);
            dir[o] = l[8];
            dir[o + 1] = l[9];
            dir[o + 2] = l[10];
            dir[o + 3] = l[11] < 1f ? l[11] : 1f;
            // the light's slab (2.10.0): its corner relative to the camera and its class and slot, or -1 for none
            DynamicLightField.slabUniform(fl.key(), cam, SLAB_SCRATCH);
            slab[o] = SLAB_SCRATCH[0];
            slab[o + 1] = SLAB_SCRATCH[1];
            slab[o + 2] = SLAB_SCRATCH[2];
            slab[o + 3] = SLAB_SCRATCH[3];
            if (l.length > 14 && l[14] >= 0.5f) {
                if (i < 32) uvBits0 |= 1 << i;
                else uvBits1 |= 1 << (i - 32);
            }
            if (l.length > 15 && l[15] >= 0.5f) {
                if (i < 32) absBits0 |= 1 << i;
                else absBits1 |= 1 << (i - 32);
            }
        }
        count = n;
        return count > 0;
    }

    private static double dist2(float[] l, Vec3 cam) {
        double dx = l[0] - cam.x, dy = l[1] - cam.y, dz = l[2] - cam.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Uploads the frame's lights to a bound Embeddium terrain program; a program without the uniforms is left alone. */
    public static void uploadTerrain(int programHandle) {
        wired = true;
        upload(programHandle, true);
    }

    /** Uploads the frame's lights to a bound Flywheel program (their positions are camera-relative, as its fragments compute). */
    public static void uploadFlywheel(int programHandle) {
        upload(programHandle, false);
    }

    /**
     * Blacklight on entities (2.11.1): the frame's lights and the blacklight field to one of the game's entity programs, bound.
     * False, with nothing uploaded, while nothing ultraviolet is about (the caller keeps the program's strength at 0).
     */
    public static boolean uploadEntityBlacklight(int programHandle) {
        boolean any = prepare();
        if (builtLevel == null || !net.camacraft.colorfullighting.common.ColorfulLightingConfig.blacklight()) return false;
        boolean lights = any && (uvBits0 | uvBits1) != 0;
        if (!lights && !BlacklightField.bind()) return false;
        upload(programHandle, true);
        return true;
    }

    private static void upload(int programHandle, boolean withInvView) {
        Locations loc = LOCATIONS.get(programHandle);
        if (loc == null) {
            loc = new Locations(GL20C.glGetUniformLocation(programHandle, "cl_dynCount"), GL20C.glGetUniformLocation(programHandle, "cl_dynPos"),
                    GL20C.glGetUniformLocation(programHandle, "cl_dynColor"), GL20C.glGetUniformLocation(programHandle, "cl_dynDir"),
                    GL20C.glGetUniformLocation(programHandle, "cl_dynInvView"), GL20C.glGetUniformLocation(programHandle, "cl_dynSlabs"),
                    GL20C.glGetUniformLocation(programHandle, "cl_dynSlab"), GL20C.glGetUniformLocation(programHandle, "cl_dynSlabTex0"),
                    GL20C.glGetUniformLocation(programHandle, "cl_dynSlabTex1"), GL20C.glGetUniformLocation(programHandle, "cl_dynSlabTex2"),
                    GL20C.glGetUniformLocation(programHandle, "cl_dynSlabSize"), GL20C.glGetUniformLocation(programHandle, "cl_dynSlabCount"),
                    GL20C.glGetUniformLocation(programHandle, "cl_dynFragSpots"),
                    GL20C.glGetUniformLocation(programHandle, "cl_dynUvBits"), GL20C.glGetUniformLocation(programHandle, "cl_uvStrength"),
                    GL20C.glGetUniformLocation(programHandle, "cl_uvField"), GL20C.glGetUniformLocation(programHandle, "cl_uvFieldTex"),
                    GL20C.glGetUniformLocation(programHandle, "cl_uvFieldOrigin"), GL20C.glGetUniformLocation(programHandle, "cl_uvFieldSize"),
                    GL20C.glGetUniformLocation(programHandle, "cl_dynAbsBits"));
            if (DEBUG_DUMP) dump(programHandle, loc);
            if (!loc.usable()) loc = NONE;
            LOCATIONS.put(programHandle, loc);
        }
        if (loc == NONE) return;
        // the slabs first (their upload decides which lights have one), then the arrays, which name each light's slab
        boolean slabsOn = transmission && !DynamicLightsCompat.externalMeshLighting() && DynamicLightField.bind();
        boolean any = prepare();
        if (loc.slabs >= 0) {
            GL20C.glUniform1i(loc.slabs, slabsOn ? 1 : 0);
            // the samplers' units always (2.11.0): a 3D sampler left on unit 0 shares it with the atlas' 2D sampler
            if (loc.slabTex0 >= 0) GL20C.glUniform1i(loc.slabTex0, DynamicLightField.TEXTURE_UNITS[0]);
            if (loc.slabTex1 >= 0) GL20C.glUniform1i(loc.slabTex1, DynamicLightField.TEXTURE_UNITS[1]);
            if (loc.slabTex2 >= 0) GL20C.glUniform1i(loc.slabTex2, DynamicLightField.TEXTURE_UNITS[2]);
            if (slabsOn) {
                float[] sizes = DynamicLightField.slabSizes(), counts = DynamicLightField.slabCounts();
                if (loc.slabSize >= 0) GL20C.glUniform3f(loc.slabSize, sizes[0], sizes[1], sizes[2]);
                if (loc.slabCount >= 0) GL20C.glUniform3f(loc.slabCount, counts[0], counts[1], counts[2]);
                if (loc.uvFieldTex < 0 && loc.uvStrength < 0) checkUnits(programHandle);
            }
        }
        if (withInvView && loc.invView >= 0) {
            INV_VIEW_BUF.clear();
            INV_VIEW_BUF.put(invView).flip();
            GL20C.glUniformMatrix3fv(loc.invView, false, INV_VIEW_BUF);
        }
        // blacklight (2.11.0): which lights are ultraviolet, where the blacklight blocks' reaches, and how strongly things glow
        if (loc.uvStrength >= 0 || loc.uvFieldTex >= 0) {
            boolean on = builtLevel != null && net.camacraft.colorfullighting.common.ColorfulLightingConfig.blacklight();
            // the field for Flywheel's programs too (2.12.0): their fragments know their place relative to the camera
            boolean field = on && loc.uvField >= 0 && loc.uvFieldTex >= 0 && BlacklightField.bind();
            boolean lights = on && any && (uvBits0 | uvBits1) != 0;
            if (loc.uvFieldTex >= 0) GL20C.glUniform1i(loc.uvFieldTex, BlacklightField.TEXTURE_UNIT);
            if (loc.uvStrength >= 0) GL20C.glUniform1f(loc.uvStrength, field || lights ? (float) net.camacraft.colorfullighting.common.ColorfulLightingConfig.blacklightStrength() : 0f);
            if (loc.uvBits >= 0) GL20C.glUniform2i(loc.uvBits, lights ? uvBits0 : 0, lights ? uvBits1 : 0);
            if (loc.uvField >= 0) GL20C.glUniform1i(loc.uvField, field ? 1 : 0);
            if (field) {
                int[] origin = BlacklightField.origin();
                if (loc.uvFieldOrigin >= 0) GL20C.glUniform3f(loc.uvFieldOrigin, (float) (origin[0] - builtCam.x), (float) (origin[1] - builtCam.y), (float) (origin[2] - builtCam.z));
                if (loc.uvFieldSize >= 0) GL20C.glUniform1f(loc.uvFieldSize, BlacklightField.SIZE);
            }
            uvFrame = frameCounter;
            uvFieldThisFrame = field;
            checkUnits(programHandle);
        }
        if (loc.absBits >= 0) GL20C.glUniform2i(loc.absBits, any ? absBits0 : 0, any ? absBits1 : 0);
        if (!any) {
            GL20C.glUniform1i(loc.count, 0);
            if (loc.fragSpots >= 0) GL20C.glUniform1i(loc.fragSpots, 0);
            return;
        }
        GL20C.glUniform1i(loc.count, count);
        if (loc.fragSpots >= 0) GL20C.glUniform1i(loc.fragSpots, fragmentSpotsThisFrame());
        if (DEBUG_DUMP && DUMPED_VALUES.add(programHandle)) {
            org.slf4j.LoggerFactory.getLogger("ColorfulLighting").info("Dynamic lights debug: program {} bound {} gets count {} fragSpots {} (spots {}) slabs {}",
                    programHandle, GL20C.glGetInteger(GL20C.GL_CURRENT_PROGRAM), count, fragmentSpotsThisFrame(), spots, slabsOn);
            StringBuilder units = new StringBuilder();
            for (String name : new String[] {"u_BlockTex", "u_LightTex", "cfi_sky", "cl_dynSlabTex0", "cl_dynSlabTex1", "cl_dynSlabTex2"}) {
                int at = GL20C.glGetUniformLocation(programHandle, name);
                units.append(' ').append(name).append('=').append(at < 0 ? "none" : String.valueOf(GL20C.glGetUniformi(programHandle, at)));
            }
            int active = GL20C.glGetInteger(GL20C.GL_ACTIVE_TEXTURE);
            for (int unit = 0; unit < 16; unit++) {
                GL20C.glActiveTexture(GL20C.GL_TEXTURE0 + unit);
                units.append(" [").append(unit).append(": 2D ").append(GL20C.glGetInteger(GL20C.GL_TEXTURE_BINDING_2D)).append(" 3D ").append(GL20C.glGetInteger(GL20C.GL_TEXTURE_BINDING_3D)).append(']');
            }
            GL20C.glActiveTexture(active);
            org.slf4j.LoggerFactory.getLogger("ColorfulLighting").info("Dynamic lights debug: program {} samplers{}", programHandle, units);
        }
        POS_BUF.clear();
        POS_BUF.put(pos, 0, count * 4).flip();
        GL20C.glUniform4fv(loc.pos, POS_BUF);
        COLOR_BUF.clear();
        COLOR_BUF.put(color, 0, count * 4).flip();
        GL20C.glUniform4fv(loc.color, COLOR_BUF);
        DIR_BUF.clear();
        DIR_BUF.put(dir, 0, count * 4).flip();
        GL20C.glUniform4fv(loc.dir, DIR_BUF);
        if (loc.slab >= 0) {
            SLAB_BUF.clear();
            SLAB_BUF.put(slab, 0, count * 4).flip();
            GL20C.glUniform4fv(loc.slab, SLAB_BUF);
        }
    }

    private static long uvFrame = -1;
    private static boolean uvFieldThisFrame;

    /**
     * For the bloom: the frame's blacklights, nearest of each kind first, at most {@code max}. Four floats a light into each
     * array: its place relative to the camera and its range, and its direction and the cosine of its cone (1 for a point).
     */
    public static int blacklights(float[] posOut, float[] dirOut, int max) {
        int n = 0;
        if ((uvBits0 | uvBits1) == 0 || builtFrame != frameCounter) return 0;
        for (int i = 0; i < count && n < max; i++) {
            if ((((i < 32 ? uvBits0 : uvBits1) >> (i & 31)) & 1) == 0) continue;
            int o = i * 4, t = n * 4;
            posOut[t] = pos[o];
            posOut[t + 1] = pos[o + 1];
            posOut[t + 2] = pos[o + 2];
            posOut[t + 3] = color[o + 3];
            dirOut[t] = dir[o];
            dirOut[t + 1] = dir[o + 1];
            dirOut[t + 2] = dir[o + 2];
            dirOut[t + 3] = dir[o + 3];
            n++;
        }
        return n;
    }

    /** For tests: the blacklights among the lights uploaded this frame. */
    public static int blacklightsThisFrame() {
        return Integer.bitCount(uvBits0) + Integer.bitCount(uvBits1);
    }

    /** For tests: whether the terrain read the blacklight field this frame. */
    public static boolean blacklightFieldThisFrame() {
        return uvFieldThisFrame && frameCounter - uvFrame <= 1;
    }

    private static final Map<Integer, int[]> UNIT_CHECKS = new ConcurrentHashMap<>();
    private static final java.util.Set<String> UNIT_CLASHES = ConcurrentHashMap.newKeySet();

    /**
     * Whether another mod's sampler in this program sits on a unit the slabs use (2.10.3). Two samplers of different kinds on
     * one unit make a program the driver may not draw with, and nothing says so: the terrain is simply gone. Looked at on a
     * program's 2nd, 60th and 1200th upload (the other mod sets its unit when it likes), and named in the log once.
     */
    private static void checkUnits(int program) {
        int[] uploads = UNIT_CHECKS.computeIfAbsent(program, p -> new int[1]);
        int n = ++uploads[0];
        if (n != 2 && n != 60 && n != 1200) return;
        int first = DynamicLightField.FIRST_TEXTURE_UNIT;
        int count = GL20C.glGetProgrami(program, GL20C.GL_ACTIVE_UNIFORMS);
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            java.nio.IntBuffer size = stack.mallocInt(1), type = stack.mallocInt(1);
            for (int i = 0; i < count; i++) {
                String name = GL20C.glGetActiveUniform(program, i, 256, size, type);
                int t = type.get(0);
                boolean sampler = t >= 0x8B5D && t <= 0x8B64 || t >= 0x8DC0 && t <= 0x8DD8 || t >= 0x9108 && t <= 0x910D;
                if (!sampler || name.startsWith("cl_dynSlabTex") || name.equals("cl_uvFieldTex")) continue;
                int at = GL20C.glGetUniformLocation(program, name);
                if (at < 0) continue;
                int unit = GL20C.glGetUniformi(program, at);
                if (unit < first || unit > first + 3 || !UNIT_CLASHES.add(name)) continue;
                org.slf4j.LoggerFactory.getLogger("ColorfulLighting").error("Dynamic lights: the sampler {} of another mod uses texture unit {}, "
                        + "one of the four ({} to {}) the dynamic lights' slabs and the blacklight field are bound at. The terrain may not be drawn. "
                        + "Start with -Dcolorfullighting.slabTextureUnit=<a free unit, 8 to 43> to move them.", name, unit, first, first + 3);
            }
        }
    }

    /** For finding a fault ({@code -Dcolorfullighting.spotFragDebug=true}): each program's shader sources as the driver has them. */
    private static final boolean DEBUG_DUMP = Boolean.getBoolean("colorfullighting.spotFragDebug");
    private static final java.util.Set<Integer> DUMPED_VALUES = new java.util.HashSet<>();

    private static void dump(int program, Locations loc) {
        org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger("ColorfulLighting");
        try {
            java.nio.file.Path dir = java.nio.file.Path.of("cl-shaderdump");
            java.nio.file.Files.createDirectories(dir);
            int[] n = new int[1];
            int[] shaders = new int[8];
            GL20C.glGetAttachedShaders(program, n, shaders);
            for (int i = 0; i < n[0]; i++) {
                int type = GL20C.glGetShaderi(shaders[i], GL20C.GL_SHADER_TYPE);
                java.nio.file.Files.writeString(dir.resolve(program + (type == GL20C.GL_FRAGMENT_SHADER ? ".fsh" : ".vsh")), GL20C.glGetShaderSource(shaders[i]));
            }
            log.info("Dynamic lights debug: program {} has {} shaders; locations count {} fragSpots {} linked {}", program, n[0], loc.count, loc.fragSpots,
                    GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS));
        } catch (Exception e) {
            log.warn("Dynamic lights debug: program {}: {}", program, e.toString());
        }
    }

    /** Programs were rebuilt (a resource reload): the cached locations are stale. */
    public static void forgetPrograms() {
        LOCATIONS.clear();
        UNIT_CHECKS.clear();
    }

    /** For tests: the spot lights the terrain's fragments draw this frame (0 while the vertices draw them). */
    public static int fragmentSpotsThisFrame() {
        return FragmentSpots.active() ? Math.min(Math.min(spots, count), FragmentSpots.MAX) : 0;
    }

    /** For tests: the lights uploaded this frame. */
    public static int lightsThisFrame() {
        return count;
    }
}
