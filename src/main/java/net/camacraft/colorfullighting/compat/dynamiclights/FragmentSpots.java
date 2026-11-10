package net.camacraft.colorfullighting.compat.dynamiclights;

import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spot lights drawn by the terrain's fragments (2.10.3). The Embeddium terrain shaders took every dynamic light at the
 * vertices, which suits a lamp and not a beam: a cone is narrower than a block close to its light, so it lit whole block
 * corners or none of them, and a flashlight's patch on the ground was jagged and all but gone from close up.
 *
 * <p>Embeddium's own terrain fragment shader is kept and wrapped when its source is loaded: {@code include/light.glsl} and
 * {@code include/light_frag.glsl} are put before its {@code main}, which becomes {@code cl_mainInner}, and a new
 * {@code main} computes the lit colour first (the shader's {@code v_Color} then reads it). Nothing of the shader's body is
 * relied on beyond a {@code main} and the {@code v_Color} our vertex shaders write. A source without them is left alone,
 * and the vertices keep drawing every light ({@link #active()} false). Off with
 * {@code -Dcolorfullighting.spotPerFragment=false}; {@code -Dcolorfullighting.spotPerFragmentMax} (24) is the most spot
 * lights a fragment takes, the nearest ones.
 */
public final class FragmentSpots {
    private static final Logger LOGGER = LoggerFactory.getLogger("ColorfulLighting");
    public static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("colorfullighting.spotPerFragment", "true"));
    private static final Pattern MAIN = Pattern.compile("void\\s+main\\s*\\(\\s*(?:void)?\\s*\\)");
    /** The most spot lights the fragments draw (the nearest); the vertices draw the ones after them, as before. */
    public static final int MAX = Math.max(0, Math.min(64, Integer.getInteger("colorfullighting.spotPerFragmentMax", 24)));
    private static volatile boolean patched;

    private FragmentSpots() {}

    /** Whether the terrain's fragment shader draws the spot lights (the vertices leave them out then). */
    public static boolean active() {
        return ENABLED && patched;
    }

    /** A shader source as Embeddium loaded it; the terrain fragment shader comes back wrapped, anything else as it is. */
    public static String patch(ResourceLocation id, String source) {
        if (!ENABLED || source == null || id == null) return source;
        String path = id.getPath();
        if (!"sodium".equals(id.getNamespace()) || !path.startsWith("blocks/") || !path.endsWith(".fsh")) return source;
        Matcher main = MAIN.matcher(source);
        if (source.contains("cl_mainInner")) return source;
        if (!source.contains("v_Color") || !main.find()) {
            patched = false;
            LOGGER.warn("Dynamic lights: {} has no main or v_Color to wrap; spot lights stay with the vertices", id);
            return source;
        }
        StringBuilder out = new StringBuilder(source.length() + 512);
        out.append(source, 0, main.start());
        out.append("\n#if defined(COLORFUL_LIGHTING_MOD_PRESENT) && defined(CL_DYNAMIC_LIGHTS)\n");
        if (!source.contains("u_LightTex")) out.append("uniform sampler2D u_LightTex;\n");
        // for finding a fault: the picture in four bands (the counts, the lights alone, the place, the face)
        if (Boolean.getBoolean("colorfullighting.spotFragDebug")) out.append("#define CL_FRAG_DEBUG\n");
        out.append("#import <sodium:include/light.glsl>\n");
        out.append("#import <sodium:include/light_frag.glsl>\n");
        out.append("#endif\n");
        out.append("void cl_mainInner()");
        out.append(source, main.end(), source.length());
        out.append("\nvoid main() {\n#ifdef CL_FRAG_LIGHT\n    cl_color = cl_litColor();\n#endif\n    cl_mainInner();\n#ifdef CL_FRAG_DEBUG\n    if (gl_FragCoord.y < 450.0) fragColor = vec4(cl_color.rgb, 1.0);\n#endif\n}\n");
        patched = true;
        LOGGER.info("Dynamic lights: spot lights are drawn by the fragments of {} (v_Color read {} times after main)", id,
                source.substring(main.end()).split("v_Color", -1).length - 1);
        return out.toString();
    }
}
