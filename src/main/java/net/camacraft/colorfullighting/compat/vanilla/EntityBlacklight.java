package net.camacraft.colorfullighting.compat.vanilla;

import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import net.camacraft.colorfullighting.ColorfulLighting;
import net.camacraft.colorfullighting.common.ColorfulLightingConfig;
import net.camacraft.colorfullighting.compat.Resources;
import net.camacraft.colorfullighting.compat.dynamiclights.BlacklightField;
import net.camacraft.colorfullighting.compat.dynamiclights.DynamicLightField;
import net.camacraft.colorfullighting.compat.dynamiclights.ShaderDynamicLights;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Blacklight on entities (2.11.1): what is saturated on a mob, a player, an item in a frame or a block entity glows under
 * ultraviolet as blocks do. The game's entity shaders are the ones whose vertex shader hands the lightmap's colour to the
 * fragments as {@code lightMapColor} and whose fragment shader multiplies the texture by it: here the vertex shader also
 * works out how much ultraviolet reaches the vertex (the blacklight blocks' field and the ultraviolet dynamic lights, with
 * what is in their way: the terrain shaders' uniforms) and sends it in that colour's alpha as 1 plus the amount, and the
 * fragment shader adds the glow by the texture's own colour. A shader that doesn't have exactly those lines is left as it
 * is; a vertex shader patched under a fragment shader that isn't multiplies an alpha of 1 to 2 into the colour, which
 * shows nowhere on what is opaque.
 *
 * <p>The vertex format and the packed light are untouched. The uniforms go to a program once a frame, the first time it is
 * used while the level is drawn and something ultraviolet is about; without ultraviolet a program costs one comparison a
 * use. Outside the level's drawing (the inventory's figure, item icons) the strength is 0: positions there are not places
 * in the world. The hand is drawn after the level and does not glow.
 *
 * <p>{@code -Dcolorfullighting.entityBlacklight=false} leaves the shaders alone (read when they are compiled); the client
 * config's {@code blacklightEntities} switches the glow at once.
 */
public final class EntityBlacklight {
    private static final boolean PATCH = !"false".equalsIgnoreCase(System.getProperty("colorfullighting.entityBlacklight"));

    private static final Pattern MAIN = Pattern.compile("^[ \\t]*void\\s+main\\s*\\(\\s*\\)", Pattern.MULTILINE);
    private static final Pattern VERT_OUT = Pattern.compile("^[ \\t]*out\\s+vec4\\s+lightMapColor\\s*;", Pattern.MULTILINE);
    private static final Pattern VERT_POSITION = Pattern.compile("^[ \\t]*in\\s+vec3\\s+Position\\s*;", Pattern.MULTILINE);
    private static final Pattern VERT_MODEL_VIEW = Pattern.compile("^[ \\t]*uniform\\s+mat4\\s+ModelViewMat\\s*;", Pattern.MULTILINE);
    private static final Pattern VERT_ASSIGN = Pattern.compile("^([ \\t]*)lightMapColor\\s*=\\s*minecraft_sample_lightmap\\(\\s*Sampler2\\s*,\\s*UV2\\s*\\)\\s*;", Pattern.MULTILINE);
    private static final Pattern FRAG_IN = Pattern.compile("^[ \\t]*in\\s+vec4\\s+lightMapColor\\s*;", Pattern.MULTILINE);
    private static final Pattern FRAG_COLOR = Pattern.compile("\\bvec4\\s+color\\b");
    private static final Pattern FRAG_MULTIPLY = Pattern.compile("^([ \\t]*)color\\s*\\*=\\s*lightMapColor\\s*;", Pattern.MULTILINE);
    // 2.12.0: the shaders whose vertices fold the light into the colour (armor, flat items, decals), and the particles'
    private static final Pattern VERT_NORMAL_OUT = Pattern.compile("^[ \\t]*out\\s+vec4\\s+normal\\s*;", Pattern.MULTILINE);
    private static final Pattern VERT_NORMAL_ASSIGN = Pattern.compile("^([ \\t]*)normal\\s*=\\s*ProjMat\\s*\\*\\s*ModelViewMat\\s*\\*\\s*vec4\\(\\s*Normal\\s*,\\s*0\\.0\\s*\\)\\s*;", Pattern.MULTILINE);
    private static final Pattern VERT_FOLDED = Pattern.compile("^[ \\t]*vertexColor\\s*=\\s*minecraft_mix_light\\(\\s*Light0_Direction\\s*,\\s*Light1_Direction\\s*,\\s*Normal\\s*,\\s*Color\\s*\\)\\s*\\*\\s*minecraft_sample_lightmap\\(\\s*Sampler2\\s*,\\s*UV2\\s*\\)\\s*;", Pattern.MULTILINE);
    private static final Pattern VERT_PARTICLE = Pattern.compile("^([ \\t]*)vertexColor\\s*=\\s*Color\\s*\\*\\s*minecraft_sample_lightmap\\(\\s*Sampler2\\s*,\\s*UV2\\s*\\)\\s*;", Pattern.MULTILINE);
    private static final Pattern FRAG_NORMAL_IN = Pattern.compile("^[ \\t]*in\\s+vec4\\s+normal\\s*;", Pattern.MULTILINE);
    private static final Pattern FRAG_TEXCOORD = Pattern.compile("^[ \\t]*in\\s+vec2\\s+texCoord0\\s*;", Pattern.MULTILINE);
    private static final Pattern FRAG_SAMPLER = Pattern.compile("^[ \\t]*uniform\\s+sampler2D\\s+Sampler0\\s*;", Pattern.MULTILINE);
    private static final Pattern FRAG_FOG = Pattern.compile("^([ \\t]*)fragColor\\s*=\\s*linear_fog\\(\\s*color\\s*,", Pattern.MULTILINE);

    private static volatile int patchedVertex, patchedFragment;
    /** The name of the shader being looked at (its program's, as the game compiles it). */
    private static final ThreadLocal<String> NAME = ThreadLocal.withInitial(() -> "");
    /** How deep in level renders the render thread is (a portal's level inside the level's). */
    private static int levelDepth;
    /** Per program: the frame its uniforms were last uploaded for, or {@link #ZEROED} while its strength is 0. */
    private static final Int2LongOpenHashMap UPLOADED = new Int2LongOpenHashMap();
    private static final long ZEROED = Long.MIN_VALUE;
    private static long uploads;

    static {
        UPLOADED.defaultReturnValue(ZEROED);
    }

    private EntityBlacklight() {}

    /** A core shader's source before its imports are resolved: with the blacklight's part if it is an entity shader's. */
    public static String patch(String source, boolean vertex, String name) {
        if (!PATCH) return source;
        try {
            NAME.set(name == null ? "" : name.toLowerCase(java.util.Locale.ROOT));
            String patched = vertex ? patchVertex(source) : patchFragment(source);
            if (!patched.equals(source)) PATCHED.add((vertex ? "v:" : "f:") + name);
            return patched;
        } catch (RuntimeException e) {
            ColorfulLighting.LOGGER.warn("Blacklight on entities: a shader is left as it is", e);
            return source;
        }
    }

    private static String patchVertex(String source) {
        if (!VERT_POSITION.matcher(source).find() || source.contains("cl_entityUv")) return source;
        if (!VERT_OUT.matcher(source).find()) return patchOtherVertex(source);
        Matcher assign = VERT_ASSIGN.matcher(source);
        if (!assign.find()) return source;
        int at = assign.end();
        String indent = assign.group(1);
        if (assign.find()) return source;
        Matcher main = MAIN.matcher(source);
        if (!main.find() || main.start() > at) return source;
        String eye = relative(source, false);
        patchedVertex++;
        return source.substring(0, main.start()) + Resources.CL_ENTITY_UV_VERT + "\n" + source.substring(main.start(), at)
                + "\n" + indent + "lightMapColor.a = 1.0 + cl_entityUv(" + eye + ");" + source.substring(at);
    }

    /**
     * The vertex shaders without {@code lightMapColor} (2.12.0). Armor, flat items and decals fold the light into the vertex
     * colour and have a varying {@code normal} their fragments don't read: it carries the colour before its light and 2 plus
     * the ultraviolet. The particles' shader has no such varying; its vertices add the glow by the particle's tint alone.
     */
    private static final Pattern VERT_INVERSE_VIEW = Pattern.compile("^[ \\t]*uniform\\s+mat3\\s+IViewRotMat\\s*;", Pattern.MULTILINE);

    /**
     * The GLSL for a vertex's place relative to the camera along the world's axes. A particle's position is that as it is.
     * An entity's is in the view's space: the game's own inverse view rotation turns it back where the shader has it (it
     * follows whatever turned the view, a gravity mod included), Colorful Lighting's own from the camera's angles else.
     */
    private static String relative(String source, boolean particle) {
        if (particle) return "Position";
        String view = VERT_MODEL_VIEW.matcher(source).find() ? "(ModelViewMat * vec4(Position, 1.0)).xyz" : "Position";
        return (VERT_INVERSE_VIEW.matcher(source).find() ? "IViewRotMat * " : "cl_dynInvView * ") + view;
    }

    private static String patchOtherVertex(String source) {
        Matcher main = MAIN.matcher(source);
        if (!main.find()) return source;
        String name = NAME.get();
        String eye = relative(source, name.contains("particle"));
        if (VERT_NORMAL_OUT.matcher(source).find() && VERT_FOLDED.matcher(source).find()) {
            Matcher normal = VERT_NORMAL_ASSIGN.matcher(source);
            if (!normal.find() || normal.start() < main.start()) return source;
            int at = normal.end();
            String indent = normal.group(1);
            if (normal.find()) return source;
            patchedVertex++;
            return source.substring(0, main.start()) + Resources.CL_ENTITY_UV_VERT + "\n" + source.substring(main.start(), at)
                    + "\n" + indent + "if (cl_uvStrength > 0.0) normal = vec4(minecraft_mix_light(Light0_Direction, Light1_Direction, Normal, Color).rgb, 2.0 + cl_entityUv(" + eye + "));"
                    + source.substring(at);
        }
        // by its name too: the terrain's and the text's vertex shaders have the same line, and their vertices are elsewhere
        Matcher particle = VERT_PARTICLE.matcher(source);
        if (name.contains("particle") && !source.contains("ChunkOffset") && particle.find() && particle.start() > main.start()) {
            int at = particle.end();
            String indent = particle.group(1);
            if (particle.find()) return source;
            patchedVertex++;
            return source.substring(0, main.start()) + Resources.CL_ENTITY_UV_VERT + "\n" + source.substring(main.start(), at)
                    + "\n" + indent + "if (cl_uvStrength > 0.0) vertexColor.rgb += cl_tintGlow(Color.rgb) * cl_entityUv(" + eye + ");"
                    + source.substring(at);
        }
        return source;
    }

    /** The fragment shaders of the folded-light family: the glow goes in before the fog. */
    private static String patchOtherFragment(String source) {
        // by its name too: the clouds' and the terrain's fragment shaders look the same and have nothing to glow by
        String name = NAME.get();
        if (!(name.contains("entity") || name.contains("armor") || name.contains("item"))) return source;
        if (!FRAG_NORMAL_IN.matcher(source).find() || !FRAG_TEXCOORD.matcher(source).find() || !FRAG_SAMPLER.matcher(source).find()
                || !FRAG_COLOR.matcher(source).find() || source.contains("cl_entityGlow")) return source;
        Matcher fog = FRAG_FOG.matcher(source);
        if (!fog.find()) return source;
        int at = fog.start();
        String indent = fog.group(1);
        if (fog.find()) return source;
        Matcher main = MAIN.matcher(source);
        if (!main.find() || main.start() > at) return source;
        patchedFragment++;
        return source.substring(0, main.start()) + Resources.CL_ENTITY_UV_FRAG + "\n" + source.substring(main.start(), at)
                + indent + "color.rgb += cl_entityGlow(texture(Sampler0, texCoord0).rgb, normal);\n" + source.substring(at);
    }

    private static String patchFragment(String source) {
        if (!FRAG_IN.matcher(source).find()) return patchOtherFragment(source);
        if (!FRAG_COLOR.matcher(source).find() || source.contains("cl_entityLit")) return source;
        Matcher multiply = FRAG_MULTIPLY.matcher(source);
        if (!multiply.find()) return source;
        int from = multiply.start(), to = multiply.end();
        String indent = multiply.group(1);
        if (multiply.find()) return source;
        Matcher main = MAIN.matcher(source);
        if (!main.find() || main.start() > from) return source;
        patchedFragment++;
        return source.substring(0, main.start()) + Resources.CL_ENTITY_UV_FRAG + "\n" + source.substring(main.start(), from)
                + indent + "color = cl_entityLit(color, lightMapColor);" + source.substring(to);
    }

    /**
     * A program was linked: if it has the blacklight's part, its 3D samplers get their units now. A 3D sampler left on unit 0
     * shares it with the 2D texture sampler, and a draw with both there fails. Returns the strength's location, or -1.
     */
    public static int init(int program) {
        int strength = GL20C.glGetUniformLocation(program, "cl_uvStrength");
        if (strength < 0) return -1;
        int previous = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        GL20C.glUseProgram(program);
        unit(program, "cl_dynSlabTex0", DynamicLightField.TEXTURE_UNITS[0]);
        unit(program, "cl_dynSlabTex1", DynamicLightField.TEXTURE_UNITS[1]);
        unit(program, "cl_dynSlabTex2", DynamicLightField.TEXTURE_UNITS[2]);
        unit(program, "cl_uvFieldTex", BlacklightField.TEXTURE_UNIT);
        GL20C.glUniform1f(strength, 0f);
        GL20C.glUseProgram(previous);
        UPLOADED.remove(program);
        return strength;
    }

    private static void unit(int program, String sampler, int unit) {
        int at = GL20C.glGetUniformLocation(program, sampler);
        if (at >= 0) GL20C.glUniform1i(at, unit);
    }

    public static void levelStart() {
        levelDepth++;
    }

    public static void levelEnd() {
        if (levelDepth > 0) levelDepth--;
    }

    /** The program is bound and about to draw: its blacklight uniforms for this frame, or a strength of 0. Render thread. */
    public static void apply(int program, int strengthLocation) {
        long state = UPLOADED.get(program);
        if (levelDepth > 0 && ColorfulLightingConfig.blacklightEntities()) {
            long frame = ShaderDynamicLights.frame();
            if (state == frame) return;
            if (ShaderDynamicLights.uploadEntityBlacklight(program)) {
                UPLOADED.put(program, frame);
                uploads++;
                return;
            }
        }
        if (state != ZEROED) {
            GL20C.glUniform1f(strengthLocation, 0f);
            UPLOADED.put(program, ZEROED);
        }
    }

    /** The shaders that took the blacklight's part, "v:" or "f:" and the name, for tests. */
    private static final java.util.Set<String> PATCHED = java.util.Collections.synchronizedSet(new java.util.TreeSet<>());

    public static String patchedNames() {
        synchronized (PATCHED) {
            return String.join(" ", PATCHED);
        }
    }

    /** For tests and the blacklight command: what was patched and how often the uniforms went up. */
    public static String stats() {
        return PATCH ? patchedVertex + " vertex and " + patchedFragment + " fragment entity shaders, " + uploads + " uploads" : "entity shaders left alone";
    }

    public static int patchedVertex() {
        return patchedVertex;
    }

    public static int patchedFragment() {
        return patchedFragment;
    }

    public static long uploads() {
        return uploads;
    }
}
