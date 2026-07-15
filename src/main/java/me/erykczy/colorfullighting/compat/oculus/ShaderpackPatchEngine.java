package me.erykczy.colorfullighting.compat.oculus;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Rewrites Iris/Oculus shaderpacks so they understand Colorful Lighting's packed lightmap format.
 *
 * The mod stores RGB block light in the two 16-bit lightmap coordinates:
 * x = red8 | green8 << 8, y = sky4 | blue8 << 4 | 0xF << 12 (the 0xF nibble is the format magic).
 * Unpatched packs read those coordinates as vanilla (block, sky) values and render garbage, which is
 * why the engine currently turns itself off while a shaderpack is active.
 *
 * The patch is textual, mirroring what Euphoria Patches does for Complementary:
 *  - every vertex-stage read of gl_MultiTexCoord1/2 is wrapped in cl_decodeLight(), which returns
 *    vanilla-equivalent coordinates and captures the RGB tint into globals (GLSL-120-safe, float math
 *    only, no-op for vanilla data so DH/handheld/unpacked paths are unaffected);
 *  - packs with a known forward-lighting structure (Complementary family, BSL v8/Insanity family,
 *    newer BSL/AstraLex family) get the tint plumbed through a varying and multiplied into their
 *    block-light term (true colored light);
 *  - other packs get a weighted vertex-color tint as an approximation (deferred packs like photon and
 *    Bliss apply block light in composite passes where the varying cannot reach); the weight fades
 *    under skylight in proportion to the actual sky brightness (cl_skyWash, a custom uniform derived
 *    from sunAngle/moonPhase in shaders.properties), matching the vanilla-path washout instead of the
 *    raw skylight level, so moonlight no longer bleaches colored sources;
 *  - every family's warm block-light color (blue channel far weaker than red) is replaced by a
 *    neutral white of equal peak wherever colored data is present (the same basis the Sodium core
 *    shaders use), otherwise white light renders orange and saturated tints blend with the warm
 *    color into off-hues: via cl_colorize() where a tint varying exists, via constant rewrites
 *    (WARM_LIGHT_CONSTANTS) in deferred packs, via cl_blockLightW in Sildur's Enhanced Default,
 *    and directly on candleColor in MakeUp's vertex stage.
 *
 * This class is deliberately free of Minecraft/Forge imports so it can be exercised standalone.
 */
public final class ShaderpackPatchEngine {
    public static final String OUTPUT_SUFFIX = " + ColorfulLighting";
    public static final String MARKER_PATH = "shaders/colorful_lighting_patch.txt";
    public static final int PATCH_FORMAT_VERSION = 6;

    /** Pure functions + globals; safe to inject into any stage, guarded against duplicate inclusion. */
    private static final String SHIM = """
            // >>> Colorful Lighting auto-patch (do not edit) >>>
            #ifndef COLORFUL_LIGHTING_PATCH
            #define COLORFUL_LIGHTING_PATCH
            vec3 cl_tint = vec3(1.0);
            float cl_blockW = 0.0;
            float cl_washW = 0.0;   // hue strength after skylight washout; 0.35 floor keeps color visible at noon
            float cl_colored = 0.0; // 1.0 when this vertex carries colored-light data
            uniform float cl_skyWash; // day/night-aware sky wash strength, set via shaders.properties; 0.0 if absent
            vec4 cl_decodeLight(vec4 cl_raw) {
                float cl_x = cl_raw.x;
                float cl_y = cl_raw.y;
                if (cl_x < 0.0) cl_x += 65536.0;
                if (cl_y < 0.0) cl_y += 65536.0;
                if (cl_y < 61440.0) return cl_raw;
                float cl_sky4 = mod(cl_y, 16.0);
                float cl_b = floor(mod(cl_y, 4096.0) * 0.0625);
                float cl_g = floor(cl_x * 0.00390625);
                float cl_r = cl_x - cl_g * 256.0;
                float cl_m = max(cl_r, max(cl_g, cl_b));
                if (cl_m > 0.5) {
                    cl_tint = vec3(cl_r, cl_g, cl_b) / cl_m;
                    cl_colored = 1.0;
                    cl_washW = max(0.35, 1.0 - cl_skyWash * cl_sky4 * 0.0666667);
                }
                cl_blockW = clamp(cl_m * 0.00392157, 0.0, 1.0) * cl_washW;
                return vec4(cl_m * 0.94117647 + 8.0, cl_sky4 * 16.0 + 8.0, cl_raw.z, cl_raw.w);
            }
            #endif
            // <<< Colorful Lighting auto-patch <<<
            """;

    /**
     * Fragment-side helper injected next to the cl_blockLightTint varying (rgb = sky-washed tint,
     * a = colored-data flag). Packs multiply block light by a warm color whose blue channel is weak,
     * which crushes blue tints and turns white light orange; wherever colored data is present that
     * color is replaced outright by a neutral white of equal peak times the tint — the same basis
     * the mod's Sodium core shaders use — while vanilla-format data keeps the pack's own palette.
     * The alpha mix (rather than a saturation heuristic) is what keeps white colored light white
     * and stops partially saturated tints from blending with the warm color into magenta.
     */
    private static final String CL_COLORIZE_FN = """

            vec3 cl_colorize(vec3 cl_col) { // Colorful Lighting
                return mix(cl_col, vec3(max(cl_col.r, max(cl_col.g, cl_col.b))) * cl_blockLightTint.rgb, cl_blockLightTint.a);
            }""";

    /**
     * Include-guarded tint varying for packs whose vertex and fragment stages share source files
     * (BSL v8 style combined programs): the same declaration may reach one translation unit through
     * both the program file and an #include, and an unguarded duplicate would fail to compile.
     */
    private static final String TINT_VARYING = """
            #ifndef CL_TINT_VARYING
            #define CL_TINT_VARYING
            varying vec4 cl_blockLightTint; // Colorful Lighting
            #endif""";

    /** Vertex-side write of the tint varying; must run right after the lightmap decode. */
    private static final String TINT_WRITE =
            "cl_blockLightTint = vec4(mix(vec3(1.0), cl_tint, cl_washW), cl_colored); // Colorful Lighting";

    /**
     * Custom uniform appended to shaders.properties: how strongly skylight washes the tint out,
     * ported from the mod's Sodium core shaders (effectiveSkyBrightness = skyBrightness *
     * mix(3, 0, starBrightness * moonVibrancy) there). Day factor and star brightness follow the
     * vanilla celestial-angle curves; moon vibrancy approximates the mod's built-in moon_phases.json
     * (0.45 at full moon .. 1.0 at new moon). Iris evaluates this per frame, so the washout tracks
     * the day/night cycle and moon phase instead of the static skylight level. If a pack's
     * properties parsing drops these lines the uniform reads 0.0 and colors simply stay saturated.
     *
     * sunAngle is NOT the raw celestial angle: it is 0.0 at sunrise, 0.25 at noon, 0.75 at midnight,
     * so the vanilla cos(celestialAngle * 2pi) day curve becomes sin(sunAngle * 2pi) here (+1 noon,
     * -1 midnight). With cos() the curve peaks at sunrise and zeroes at BOTH noon and midnight,
     * which pins the washout at maximum around the whole clock and kills the moon-phase response.
     */
    private static final String SKY_WASH_PROPERTIES = """

            # >>> Colorful Lighting auto-patch (do not edit) >>>
            variable.float.cl_dayT = sin(sunAngle * 6.2831853)
            variable.float.cl_dayF = clamp(cl_dayT * 2.0 + 0.5, 0.0, 1.0)
            variable.float.cl_starRaw = clamp(1.0 - (cl_dayT * 2.0 + 0.25), 0.0, 1.0)
            variable.float.cl_moonVib = 1.0 - 0.55 * abs(moonPhase - 4.0) * 0.25
            uniform.float.cl_skyWash = clamp((0.15 + 0.85 * cl_dayF) * 3.0 * (1.0 - cl_starRaw * cl_starRaw * 0.5 * cl_moonVib), 0.0, 1.0)
            colorful_lighting.compat_status=4
            # <<< Colorful Lighting auto-patch <<<
            """;

    /**
     * Warm block-light constants of deferred packs, replaced by a neutral white of equal peak:
     * {file, exact target, replacement}. The vertex-color tint baked into the albedo supplies the
     * hue, so with vanilla data (tint white × mod's own warm light colors) the look barely changes,
     * while saturated tints keep their full depth. Slider defines keep their option lists.
     */
    private static final String[][] WARM_LIGHT_CONSTANTS = {
            // photon (from_srgb is monotonic per channel, so max may move inside)
            {"shaders/include/lighting/colors/blocklight_color.glsl",
             "from_srgb(vec3(BLOCKLIGHT_R, BLOCKLIGHT_G, BLOCKLIGHT_B)) * BLOCKLIGHT_I;",
             "from_srgb(vec3(max(BLOCKLIGHT_R, max(BLOCKLIGHT_G, BLOCKLIGHT_B)))) * BLOCKLIGHT_I; // Colorful Lighting"},
            // Bliss / Chocapic13 edits (TORCH_R already 1.0)
            {"shaders/lib/settings.glsl", "#define TORCH_G 0.75 ", "#define TORCH_G 1.0 "},
            {"shaders/lib/settings.glsl", "#define TORCH_B 0.65 ", "#define TORCH_B 1.0 "},
            // Mellow (to_linear is monotonic per channel)
            {"shaders/global/lighting.glsl",
             "const vec3 TorchColor = to_linear(vec3(f_LM_RED, f_LM_GREEN, f_LM_BLUE));",
             "const vec3 TorchColor = to_linear(vec3(max(f_LM_RED, max(f_LM_GREEN, f_LM_BLUE)))); // Colorful Lighting"},
            // miniature
            {"shaders/shader.h",
             "const vec3 TORCH_INNER_COLOR = vec3(TORCH_INNER_R, TORCH_INNER_G, TORCH_INNER_B);",
             "const vec3 TORCH_INNER_COLOR = vec3(max(TORCH_INNER_R, max(TORCH_INNER_G, TORCH_INNER_B))); // Colorful Lighting"},
            {"shaders/shader.h",
             "const vec3 TORCH_MIDDLE_COLOR = vec3(TORCH_MIDDLE_R, TORCH_MIDDLE_G, TORCH_MIDDLE_B);",
             "const vec3 TORCH_MIDDLE_COLOR = vec3(max(TORCH_MIDDLE_R, max(TORCH_MIDDLE_G, TORCH_MIDDLE_B))); // Colorful Lighting"},
            {"shaders/shader.h",
             "const vec3 TORCH_OUTER_COLOR = vec3(TORCH_OUTER_R, TORCH_OUTER_G, TORCH_OUTER_B);",
             "const vec3 TORCH_OUTER_COLOR = vec3(max(TORCH_OUTER_R, max(TORCH_OUTER_G, TORCH_OUTER_B))); // Colorful Lighting"},
            // Nostalgia: blackbody(2600K) is a deep orange used in forward AND deferred (SSPT)
            // passes; its blackbody() is luminance-normalized (Y = 1), so vec3(1.0) is the
            // equal-brightness neutral. String.replace rewrites every occurrence in the file.
            {"shaders/lib/atmos/colorsDefault.glsl",
             "blackbody(float(blocklightBaseTemp))", "vec3(1.0) /* Colorful Lighting */"},
            {"shaders/lib/atmos/colorsEnd.glsl",
             "blackbody(float(blocklightBaseTemp))", "vec3(1.0) /* Colorful Lighting */"},
            {"shaders/lib/atmos/colorsNether.glsl",
             "blackbody(float(blocklightBaseTemp))", "vec3(1.0) /* Colorful Lighting */"},
            {"shaders/world0/deferred3.vsh",
             "blackbody(float(blocklightBaseTemp))", "vec3(1.0) /* Colorful Lighting */"},
            {"shaders/world1/deferred3.vsh",
             "blackbody(float(blocklightBaseTemp))", "vec3(1.0) /* Colorful Lighting */"},
            {"shaders/world-1/deferred3.vsh",
             "blackbody(float(blocklightBaseTemp))", "vec3(1.0) /* Colorful Lighting */"},
    };

    private static final Pattern LIGHT_TOKEN = Pattern.compile("\\bgl_MultiTexCoord([12])\\b");
    private static final Pattern GL_COLOR_CAPTURE = Pattern.compile("(?m)^([ \\t]*)(\\w+)(\\.rgb)?\\s*=\\s*gl_Color(\\.rgb)?\\s*;");
    // Derived captures like "color = vec4(gl_Color.rgb * light, gl_Color.a);" (CTR-VCR, Cursed Fog).
    private static final Pattern GL_COLOR_COMPOSITE = Pattern.compile("(?m)^([ \\t]*)(\\w+)\\s*=\\s*(vec[34])\\(\\(?gl_Color\\.rgb\\b[^;]*\\);");
    private static final Pattern MAIN_START = Pattern.compile("void\\s+main\\s*\\(\\s*(?:void)?\\s*\\)\\s*\\{");
    private static final String TINT_STMT_TAIL = " *= mix(vec3(1.0), cl_tint, cl_blockW); // Colorful Lighting";

    public record Result(String sourceName, String outputName, int patchedFiles, boolean skipped, String message) {}

    private ShaderpackPatchEngine() {}

    /** Patches every recognizable pack in the directory. Existing up-to-date outputs are skipped. */
    public static List<Result> patchAll(Path shaderpacksDir, Consumer<String> log) {
        List<Result> results = new ArrayList<>();
        if (!Files.isDirectory(shaderpacksDir)) return results;
        List<Path> sources = new ArrayList<>();
        try (Stream<Path> children = Files.list(shaderpacksDir)) {
            children.sorted().forEach(sources::add);
        } catch (IOException e) {
            log.accept("Cannot list " + shaderpacksDir + ": " + e);
            return results;
        }
        for (Path source : sources) {
            String base = outputBaseName(source);
            if (base == null) continue; // not a pack, or one of our own outputs
            try {
                results.add(patchPack(source, shaderpacksDir.resolve(base + OUTPUT_SUFFIX), log));
            } catch (Exception e) {
                results.add(new Result(source.getFileName().toString(), null, 0, true, "failed: " + e));
                log.accept("Failed to patch " + source.getFileName() + ": " + e);
            }
        }
        return results;
    }
	
    public static Result patch(Path shaderpack, Consumer<String> log) {
//        if (!Files.isDirectory(shaderpack)) return null;
//        List<Path> sources = new ArrayList<>();
//        try (Stream<Path> children = Files.list(shaderpack)) {
//            children.sorted().forEach(sources::add);
//        } catch (IOException e) {
//            log.accept("Cannot list " + shaderpack + ": " + e);
//            return null;
//        }
        
		String base = outputBaseName(shaderpack);
        if (base == null) return null; // not a pack, or one of our own outputs
	    Result res;
        try {
            res = (patchPack(shaderpack, shaderpack.getParent().resolve(base + OUTPUT_SUFFIX), log));
        } catch (Exception e) {
	        res = (new Result(shaderpack.getFileName().toString(), null, 0, true, "failed: " + e));
            log.accept("Failed to patch " + shaderpack.getFileName() + ": " + e);
        }
		
        return res;
    }

    /** @return base name for the patched output, or null if this path should not be patched. */
    static String outputBaseName(Path source) {
        String name = source.getFileName().toString();
        if (name.startsWith(".")) return null;
        if (name.endsWith(OUTPUT_SUFFIX)) return null;
        String base;
        if (Files.isDirectory(source)) {
            if (!Files.isDirectory(source.resolve("shaders"))) return null;
            base = name;
        } else if (name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            base = name.substring(0, name.length() - 4);
        } else {
            return null;
        }
        return base;
    }

    public static Result patchPack(Path source, Path outputDir, Consumer<String> log) throws IOException {
        String sourceName = source.getFileName().toString();
        String fingerprint = fingerprint(source);
        Path markerFile = outputDir.resolve(MARKER_PATH);
        if (Files.exists(markerFile)
                && readMarkerValue(markerFile, "fingerprint").equals(fingerprint)
                && readMarkerValue(markerFile, "format").equals(String.valueOf(PATCH_FORMAT_VERSION))) {
            return new Result(sourceName, outputDir.getFileName().toString(), 0, true, "up to date");
        }

        Map<String, byte[]> files = readPack(source);
        if (!files.containsKey("shaders/shaders.properties") && files.keySet().stream().noneMatch(p -> p.startsWith("shaders/"))) {
            return new Result(sourceName, null, 0, true, "no shaders/ directory found");
        }
        if (files.containsKey(MARKER_PATH)) {
            return new Result(sourceName, null, 0, true, "already patched");
        }

        List<String> notes = new ArrayList<>();
        int patched = applyPatches(files, notes);
        if (patched == 0) {
            return new Result(sourceName, null, 0, true, "no lightmap reads found (unsupported layout)");
        }

        StringBuilder marker = new StringBuilder();
        marker.append("Colorful Lighting shaderpack patch\n");
        marker.append("format=").append(PATCH_FORMAT_VERSION).append('\n');
        marker.append("source=").append(sourceName).append('\n');
        marker.append("fingerprint=").append(fingerprint).append('\n');
        marker.append("patchedFiles=").append(patched).append('\n');
        for (String note : notes) marker.append("note=").append(note).append('\n');
        files.put(MARKER_PATH, marker.toString().getBytes(StandardCharsets.UTF_8));

        deleteRecursively(outputDir);
        writePack(outputDir, files);
        log.accept("Patched shaderpack '" + sourceName + "' -> '" + outputDir.getFileName() + "' (" + patched + " files, " + String.join("; ", notes) + ")");
        return new Result(sourceName, outputDir.getFileName().toString(), patched, false, String.join("; ", notes));
    }

    // ------------------------------------------------------------------ patch rules

    static int applyPatches(Map<String, byte[]> files, List<String> notes) {
        int patched = 0;

        // MakeUp includes shader-body fragments inside main(); function definitions cannot be
        // injected there, so the shim goes into its globally-included config instead.
        boolean makeUpLayout = files.containsKey("shaders/src/basiccoords_vertex.glsl")
                && files.containsKey("shaders/lib/config.glsl");

        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            String path = entry.getKey();
            if (!path.startsWith("shaders/")) continue;
            String lower = path.toLowerCase(Locale.ROOT);
            if (!(lower.endsWith(".vsh") || lower.endsWith(".glsl"))) continue;

            String src = text(entry.getValue());
            if (!LIGHT_TOKEN.matcher(src).find()) continue;

            String out = LIGHT_TOKEN.matcher(src).replaceAll("cl_decodeLight(gl_MultiTexCoord$1)");
            boolean bodyFragment = makeUpLayout && path.startsWith("shaders/src/");
            if (!bodyFragment) {
                out = insertAfterHeader(out, SHIM);
            }
            entry.setValue(bytes(out));
            patched++;
        }
        if (patched == 0) return 0;

        if (makeUpLayout) {
            files.computeIfPresent("shaders/lib/config.glsl", (p, b) -> bytes(insertAfterHeader(text(b), SHIM)));
            patched++;
            notes.add("MakeUp layout: shim in lib/config.glsl");
        }

        // True colored block light for the Complementary family (Reimagined/Unbound/Euphoria/rethinking-voxels).
        patched += wireForwardTint(files, notes, "Complementary family",
                "shaders/lib/util/commonFunctions.glsl", "out vec4 cl_blockLightTint;",
                "shaders/lib/lighting/mainLighting.glsl", "in vec4 cl_blockLightTint;" + CL_COLORIZE_FN,
                "lightmapXM * blocklightCol;",
                "lightmapXM * cl_colorize(blocklightCol);");

        // Same idea for newer BSL-derived packs (AstraLex).
        patched += wireForwardTint(files, notes, "BSL family",
                "shaders/settings/globalSettings.glsl", "varying vec4 cl_blockLightTint;",
                "shaders/lib/lighting/forwardLighting.glsl", "varying vec4 cl_blockLightTint;" + CL_COLORIZE_FN,
                "blocklightCol * pow2(newLightmap)",
                "cl_colorize(blocklightCol) * pow2(newLightmap)");

        // BSL v8 series and its forks (Insanity): combined VSH/FSH program files, lightmap read as a
        // plain assignment (no declaration), block light applied in lib/lighting/forwardLighting.glsl.
        patched += wireBslV8Tint(files, notes);

        // Sildur's Enhanced Default forces torchlight to vec3(emissive_R, emissive_G, emissive_B)
        // (default 3.0/1.5/0.5); the vertex-color tint below cannot survive that blue channel, so
        // fade the color to a neutral white of equal peak while colored block light is present.
        patched += neutralizeSildurEmissive(files, notes);

        // Everything else: weighted vertex-color tint, applied where the vertex color is captured.
        // Covers plain .vsh programs and combined VSH/FSH .glsl programs alike; files already wired
        // with the tint varying (forward families above) are skipped so the tint is not applied twice.
        int tinted = 0;
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            String path = entry.getKey();
            if (!path.startsWith("shaders/")) continue;
            String lower = path.toLowerCase(Locale.ROOT);
            if (!(lower.endsWith(".vsh") || lower.endsWith(".glsl"))) continue;
            if (makeUpLayout && path.startsWith("shaders/src/")) continue; // handled via candleColor below
            String src = text(entry.getValue());
            String withTint = tintVertexColor(src);
            if (withTint != null) {
                entry.setValue(bytes(withTint));
                tinted++;
            }
        }
        if (tinted > 0) {
            patched += tinted;
            notes.add("vertex-color tint in " + tinted + " programs");
        }

        // MakeUp computes its block-light color (candleColor) in the vertex stage, where cl_tint is
        // live: tint it directly instead of the vertex color — true colored light, and the warm
        // CANDLE_BASELIGHT fades to neutral in proportion to the tint's saturation.
        if (makeUpLayout) {
            Pattern candleLine = Pattern.compile("(?m)^([ \\t]*)candleColor = CANDLE_BASELIGHT \\* .*;$");
            int candleTinted = 0;
            for (String path : new String[]{"shaders/src/light_vertex.glsl", "shaders/src/light_vertex_dh.glsl"}) {
                byte[] data = files.get(path);
                if (data == null) continue;
                String src = text(data);
                Matcher capture = candleLine.matcher(src);
                if (!capture.find()) continue;
                String stmt = capture.group(1) + "candleColor = mix(candleColor, vec3(max(candleColor.r, max(candleColor.g, candleColor.b)))"
                        + " * mix(vec3(1.0), cl_tint, cl_washW), cl_colored); // Colorful Lighting";
                files.put(path, bytes(src.substring(0, capture.end()) + "\n" + stmt + src.substring(capture.end())));
                candleTinted++;
            }
            if (candleTinted > 0) {
                patched += candleTinted;
                notes.add("MakeUp: colored candleColor in light_vertex");
            }
        }

        // Deferred packs apply block light in composite passes the varying cannot reach; the hue is
        // baked into the albedo by the vertex-color tint above, so their warm torch constants must
        // go neutral (max component) or a blue tint's only channel gets crushed.
        int neutralized = 0;
        for (String[] rule : WARM_LIGHT_CONSTANTS) {
            byte[] data = files.get(rule[0]);
            if (data == null) continue;
            String src = text(data);
            if (!src.contains(rule[1])) continue;
            files.put(rule[0], bytes(src.replace(rule[1], rule[2])));
            neutralized++;
        }
        if (neutralized > 0) {
            patched += neutralized;
            notes.add("neutralized warm block-light color (" + neutralized + " constants)");
        }

        // Feed cl_skyWash (declared by the shim) through an Iris/OptiFine custom uniform.
        String propsPath = "shaders/shaders.properties";
        String props = files.containsKey(propsPath) ? text(files.get(propsPath)) : "";
        if (!props.contains("cl_skyWash")) {
            if (!props.isEmpty() && !props.endsWith("\n")) props += "\n";
            files.put(propsPath, bytes(props + SKY_WASH_PROPERTIES));
        }

        return patched;
    }

    /**
     * BSL v8 series (v8.x, Insanity, other forks): programs are combined VSH/FSH .glsl files where
     * the vertex stage assigns the pre-declared lmCoord varying, and lib/lighting/forwardLighting.glsl
     * multiplies the block-light term in the fragment stage. Wires cl_tint through cl_blockLightTint
     * into that term, exactly like {@link #wireForwardTint} does for the other forward families.
     */
    private static int wireBslV8Tint(Map<String, byte[]> files, List<String> notes) {
        String fragmentFile = "shaders/lib/lighting/forwardLighting.glsl";
        byte[] fragmentData = files.get(fragmentFile);
        if (fragmentData == null) return 0;
        String fragmentSrc = text(fragmentData);
        String target = "blocklightCol * newLightmap * newLightmap;";
        if (!fragmentSrc.contains(target)) return 0;

        Pattern lmLine = Pattern.compile("(?m)^([ \\t]*)lmCoord = \\(gl_TextureMatrix\\[1\\] \\* cl_decodeLight\\(gl_MultiTexCoord1\\)\\)\\.xy;");
        int wired = 0;
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            String path = entry.getKey();
            if (!path.startsWith("shaders/") || path.equals(fragmentFile)) continue;
            String lower = path.toLowerCase(Locale.ROOT);
            if (!(lower.endsWith(".vsh") || lower.endsWith(".glsl"))) continue;
            String src = text(entry.getValue());
            Matcher m = lmLine.matcher(src);
            if (!m.find()) continue;
            String out = src.substring(0, m.end())
                    + "\n" + m.group(1) + TINT_WRITE
                    + src.substring(m.end());
            // The declaration lands at the top of the combined file, hence outside the stage #ifdefs:
            // it must be visible to both stages, and the include guard absorbs the duplicate that
            // reaches fragment translation units through forwardLighting.glsl.
            out = insertAfterHeader(out, TINT_VARYING);
            entry.setValue(bytes(out));
            wired++;
        }
        if (wired == 0) return 0;

        files.put(fragmentFile, bytes(TINT_VARYING + CL_COLORIZE_FN + "\n"
                + fragmentSrc.replace(target, "cl_colorize(blocklightCol) * newLightmap * newLightmap;")));
        notes.add("BSL v8 family: colored block light wired into " + fragmentFile + " (" + wired + " vertex programs)");
        return wired + 1;
    }

    /**
     * Plumbs cl_tint through a varying: written next to the pack's central lightmap-read function in
     * {@code vertexFile}, consumed by rewriting {@code target -> replacement} in {@code fragmentFile}.
     * Both files must exist and match, otherwise nothing is changed (0 returned).
     */
    private static int wireForwardTint(Map<String, byte[]> files, List<String> notes, String familyName,
                                       String vertexFile, String vertexDecl,
                                       String fragmentFile, String fragmentDecl,
                                       String target, String replacement) {
        byte[] vertexData = files.get(vertexFile);
        byte[] fragmentData = files.get(fragmentFile);
        if (vertexData == null || fragmentData == null) return 0;

        String vertexSrc = text(vertexData);
        String fragmentSrc = text(fragmentData);
        // The decode wrap from tier 1 must already be inside GetLightMapCoordinates().
        Pattern lmLine = Pattern.compile("(?m)^([ \\t]*)vec2 lmCoord = \\(gl_TextureMatrix\\[1\\] \\* cl_decodeLight\\(gl_MultiTexCoord1\\)\\)\\.xy;");
        Matcher m = lmLine.matcher(vertexSrc);
        if (!m.find() || !fragmentSrc.contains(target)) return 0;

        String indent = m.group(1);
        vertexSrc = vertexSrc.substring(0, m.end())
                + "\n" + indent + TINT_WRITE
                + vertexSrc.substring(m.end());
        // Declare the varying right before the enclosing function so it stays inside the same
        // vertex-stage preprocessor guard.
        Pattern fn = Pattern.compile("(?m)^([ \\t]*)vec2 GetLightMapCoordinates\\(\\)");
        Matcher fnM = fn.matcher(vertexSrc);
        if (!fnM.find()) return 0;
        vertexSrc = vertexSrc.substring(0, fnM.start()) + fnM.group(1) + vertexDecl + "\n" + vertexSrc.substring(fnM.start());

        fragmentSrc = fragmentDecl + "\n" + fragmentSrc.replace(target, replacement);

        files.put(vertexFile, bytes(vertexSrc));
        files.put(fragmentFile, bytes(fragmentSrc));
        notes.add(familyName + ": colored block light wired into " + fragmentFile);
        return 2;
    }

    /**
     * Sildur's Enhanced Default: gbuffers_textured.fsh multiplies in a hardcoded warm torch color.
     * Mixes it toward vec3(max component) by cl_blockW so the vertex-color tint keeps its hue.
     * The world-1/world1 variants #include the root files, so patching those covers all dimensions.
     */
    private static int neutralizeSildurEmissive(Map<String, byte[]> files, List<String> notes) {
        String vertexFile = "shaders/gbuffers_textured.vsh";
        String fragmentFile = "shaders/gbuffers_textured.fsh";
        byte[] vertexData = files.get(vertexFile);
        byte[] fragmentData = files.get(fragmentFile);
        if (vertexData == null || fragmentData == null) return 0;

        String vertexSrc = text(vertexData);
        String fragmentSrc = text(fragmentData);
        String target = "vec3(emissive_R,emissive_G,emissive_B)*torchmap";
        Pattern lmLine = Pattern.compile("(?m)^([ \\t]*)lmcoord = \\(gl_TextureMatrix\\[1\\] \\* cl_decodeLight\\(gl_MultiTexCoord1\\)\\)\\.xy;");
        Matcher m = lmLine.matcher(vertexSrc);
        if (!m.find() || !fragmentSrc.contains(target)) return 0;

        String decl = "varying float cl_blockLightW; // Colorful Lighting";
        vertexSrc = vertexSrc.substring(0, m.end())
                + "\n" + m.group(1) + "cl_blockLightW = cl_blockW; // Colorful Lighting"
                + vertexSrc.substring(m.end());
        vertexSrc = insertAfterHeader(vertexSrc, decl);
        fragmentSrc = insertAfterHeader(fragmentSrc.replace(target,
                "mix(vec3(emissive_R,emissive_G,emissive_B), vec3(max(emissive_R, max(emissive_G, emissive_B))), cl_blockLightW)*torchmap"), decl);

        files.put(vertexFile, bytes(vertexSrc));
        files.put(fragmentFile, bytes(fragmentSrc));
        notes.add("Sildur family: neutralized warm torch color in " + fragmentFile);
        return 2;
    }

    // ------------------------------------------------------------------ text helpers

    /**
     * Inserts a block after the leading run of #version/#extension/comment/blank lines so the
     * insertion lands at global scope without displacing the version header.
     */
    static String insertAfterHeader(String src, String block) {
        String[] lines = src.split("\n", -1);
        int insertAt = 0;
        boolean inBlockComment = false;
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            if (inBlockComment) {
                insertAt = i + 1;
                if (t.contains("*/")) inBlockComment = false;
                continue;
            }
            if (t.isEmpty() || t.startsWith("//") || t.startsWith("#version") || t.startsWith("#extension")) {
                insertAt = i + 1;
                continue;
            }
            if (t.startsWith("/*")) {
                insertAt = i + 1;
                if (!t.contains("*/")) inBlockComment = true;
                continue;
            }
            break;
        }
        StringBuilder sb = new StringBuilder(src.length() + block.length() + 2);
        for (int i = 0; i < lines.length; i++) {
            if (i == insertAt) sb.append(block).append('\n');
            sb.append(lines[i]);
            if (i < lines.length - 1) sb.append('\n');
        }
        if (insertAt >= lines.length) sb.append('\n').append(block);
        return sb.toString();
    }

    /**
     * Applies the weighted vertex-color tint to every gl_Color capture in a patched vertex program.
     * All captures are handled, not just the first: alternative preprocessor branches (Cursed Fog's
     * NEW_LIGHTING paths) each carry their own assignment and each active one must be tinted.
     * Captures after the lightmap decode are tinted in place; captures before it (cl_tint is not set
     * yet) are tinted at the end of their enclosing main(). Returns null when the file has no decode,
     * is already wired with the tint varying, or has no recognizable capture.
     */
    static String tintVertexColor(String src) {
        int decodePos = src.indexOf("cl_decodeLight(gl_MultiTexCoord");
        if (decodePos < 0 || src.contains("cl_blockLightTint")) return null;

        record Insertion(int at, String text) {}
        List<Insertion> insertions = new ArrayList<>();
        List<String> deferredKeys = new ArrayList<>();
        for (Pattern pattern : new Pattern[]{GL_COLOR_CAPTURE, GL_COLOR_COMPOSITE}) {
            Matcher capture = pattern.matcher(src);
            while (capture.find()) {
                // "x = gl_Color;" / "x = vec4(gl_Color.rgb ...)" leave a vec4 to tint via .rgb;
                // "x = gl_Color.rgb;" / "x = vec3(gl_Color.rgb ...)" are already vec3.
                String target;
                if (pattern == GL_COLOR_CAPTURE) {
                    target = capture.group(2) + (capture.group(3) != null ? ".rgb" : capture.group(4) != null ? "" : ".rgb");
                } else {
                    target = capture.group(2) + ("vec4".equals(capture.group(3)) ? ".rgb" : "");
                }
                if (capture.start() > decodePos) {
                    insertions.add(new Insertion(capture.end(), "\n" + capture.group(1) + target + TINT_STMT_TAIL));
                } else {
                    int close = enclosingMainEnd(src, capture.start());
                    if (close < 0) continue;
                    String key = close + ":" + target;
                    if (deferredKeys.contains(key)) continue;
                    deferredKeys.add(key);
                    insertions.add(new Insertion(close, "    " + target + TINT_STMT_TAIL + "\n"));
                }
            }
        }
        if (insertions.isEmpty()) return null;
        insertions.sort((a, b) -> Integer.compare(b.at(), a.at()));
        StringBuilder sb = new StringBuilder(src);
        for (Insertion insertion : insertions) sb.insert(insertion.at(), insertion.text());
        return sb.toString();
    }

    /**
     * Index of the closing brace of the main() whose body contains {@code pos}, or -1. Combined
     * VSH/FSH files hold one main() per stage, so the enclosing one must be matched rather than the
     * first in the file.
     */
    static int enclosingMainEnd(String src, int pos) {
        Matcher m = MAIN_START.matcher(src);
        while (m.find()) {
            int close = findBlockEnd(src, m.end());
            if (close < 0) return -1;
            if (pos >= m.end() && pos < close) return close;
        }
        return -1;
    }

    /** Index of the '}' closing the block whose body starts at {@code start} (just past the '{'), or -1. */
    static int findBlockEnd(String src, int start) {
        int depth = 1;
        int i = start;
        boolean lineComment = false, blockComment = false;
        while (i < src.length()) {
            char c = src.charAt(i);
            char n = i + 1 < src.length() ? src.charAt(i + 1) : '\0';
            if (lineComment) {
                if (c == '\n') lineComment = false;
            } else if (blockComment) {
                if (c == '*' && n == '/') { blockComment = false; i++; }
            } else if (c == '/' && n == '/') {
                lineComment = true; i++;
            } else if (c == '/' && n == '*') {
                blockComment = true; i++;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
            i++;
        }
        return -1;
    }

    // ISO-8859-1 round-trips every byte, so untouched parts of the file stay byte-identical and our
    // injected ASCII is encoded correctly regardless of the pack's actual encoding.
    private static String text(byte[] data) {
        return new String(data, StandardCharsets.ISO_8859_1);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    // ------------------------------------------------------------------ pack IO

    /** Reads a pack (zip or directory) into a path->bytes map rooted at the directory containing shaders/. */
    static Map<String, byte[]> readPack(Path source) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        if (Files.isDirectory(source)) {
            try (Stream<Path> walk = Files.walk(source)) {
                for (Path p : (Iterable<Path>) walk::iterator) {
                    if (Files.isRegularFile(p)) {
                        files.put(source.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
                    }
                }
            }
        } else {
            try (ZipFile zip = new ZipFile(source.toFile())) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement();
                    if (e.isDirectory()) continue;
                    try (InputStream in = zip.getInputStream(e)) {
                        files.put(e.getName().replace('\\', '/'), in.readAllBytes());
                    }
                }
            }
        }
        // Some zips nest everything under a single top-level folder; strip it so keys start at shaders/.
        if (files.keySet().stream().noneMatch(p -> p.startsWith("shaders/"))) {
            String prefix = files.keySet().stream()
                    .filter(p -> p.contains("/shaders/"))
                    .map(p -> p.substring(0, p.indexOf("/shaders/") + 1))
                    .findFirst().orElse(null);
            if (prefix != null) {
                Map<String, byte[]> stripped = new LinkedHashMap<>();
                for (Map.Entry<String, byte[]> e : files.entrySet()) {
                    if (e.getKey().startsWith(prefix)) {
                        stripped.put(e.getKey().substring(prefix.length()), e.getValue());
                    }
                }
                return stripped;
            }
        }
        return files;
    }

    static void writePack(Path outputDir, Map<String, byte[]> files) throws IOException {
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            Path target = outputDir.resolve(e.getKey());
            Files.createDirectories(target.getParent());
            Files.write(target, e.getValue());
        }
    }

    static String fingerprint(Path source) throws IOException {
        if (Files.isDirectory(source)) {
            long size = 0, newest = 0, count = 0;
            try (Stream<Path> walk = Files.walk(source)) {
                for (Path p : (Iterable<Path>) walk::iterator) {
                    if (!Files.isRegularFile(p)) continue;
                    size += Files.size(p);
                    newest = Math.max(newest, Files.getLastModifiedTime(p).toMillis());
                    count++;
                }
            }
            return "dir:" + count + ":" + size + ":" + newest;
        }
        return "zip:" + Files.size(source) + ":" + Files.getLastModifiedTime(source).toMillis();
    }

    private static String readMarkerValue(Path marker, String key) {
        String prefix = key + "=";
        try {
            for (String line : Files.readAllLines(marker, StandardCharsets.UTF_8)) {
                if (line.startsWith(prefix)) return line.substring(prefix.length());
            }
        } catch (IOException ignored) {
        }
        return "";
    }

    static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> paths = new ArrayList<>();
            walk.forEach(paths::add);
            for (int i = paths.size() - 1; i >= 0; i--) {
                Files.delete(paths.get(i));
            }
        }
    }

    /** Standalone entry point so the patcher can be run against a shaderpacks folder outside the game. */
    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args.length > 0 ? args[0] : "run/shaderpacks");
        List<Result> results = patchAll(dir, System.out::println);
        System.out.println();
        for (Result r : results) {
            System.out.printf("%-70s %s%n", r.sourceName(),
                    r.skipped() ? "SKIPPED (" + r.message() + ")" : "OK -> " + r.outputName() + " [" + r.patchedFiles() + " files]");
        }
    }
}
