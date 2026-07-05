package me.erykczy.colorfullighting.compat.oculus;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 *  - packs with a known forward-lighting structure (Complementary family, BSL/AstraLex family) get the
 *    tint plumbed through a varying and multiplied into their block-light term (true colored light);
 *  - other packs get a weighted vertex-color tint as an approximation (deferred packs like photon and
 *    Bliss apply block light in composite passes where the varying cannot reach);
 *  - every family's warm block-light color (blue channel far weaker than red) is additionally
 *    faded toward a neutral white of equal peak while colored light is present, otherwise a
 *    saturated blue tint is crushed to near black: via cl_colorize() where a tint varying exists,
 *    via constant rewrites (WARM_LIGHT_CONSTANTS) in deferred packs, via cl_blockLightW in Sildur's
 *    Enhanced Default, and directly on candleColor in MakeUp's vertex stage.
 *
 * This class is deliberately free of Minecraft/Forge imports so it can be exercised standalone.
 */
public final class ShaderpackPatchEngine {
    public static final String OUTPUT_SUFFIX = " + ColorfulLighting";
    public static final String MARKER_PATH = "shaders/colorful_lighting_patch.txt";
    public static final int PATCH_FORMAT_VERSION = 3;

    /** Pure functions + globals; safe to inject into any stage, guarded against duplicate inclusion. */
    private static final String SHIM = """
            // >>> Colorful Lighting auto-patch (do not edit) >>>
            #ifndef COLORFUL_LIGHTING_PATCH
            #define COLORFUL_LIGHTING_PATCH
            vec3 cl_tint = vec3(1.0);
            float cl_blockW = 0.0;
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
                if (cl_m > 0.5) cl_tint = vec3(cl_r, cl_g, cl_b) / cl_m;
                cl_blockW = clamp(cl_m * 0.00392157 - cl_sky4 * 0.044, 0.0, 1.0);
                return vec4(cl_m * 0.94117647 + 8.0, cl_sky4 * 16.0 + 8.0, cl_raw.z, cl_raw.w);
            }
            #endif
            // <<< Colorful Lighting auto-patch <<<
            """;

    /**
     * Fragment-side helper injected next to the cl_blockLightTint varying. Packs multiply block
     * light by a warm color whose blue channel is weak, which crushes blue tints; this fades that
     * color toward a neutral white of equal peak in proportion to the tint's saturation, so the
     * tint keeps its full hue while vanilla-looking tints leave the pack's palette untouched.
     */
    private static final String CL_COLORIZE_FN = """

            vec3 cl_colorize(vec3 cl_col) { // Colorful Lighting
                float cl_w = 1.0 - min(cl_blockLightTint.r, min(cl_blockLightTint.g, cl_blockLightTint.b));
                return mix(cl_col, vec3(max(cl_col.r, max(cl_col.g, cl_col.b))), cl_w) * cl_blockLightTint;
            }""";

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
    };

    private static final Pattern LIGHT_TOKEN = Pattern.compile("\\bgl_MultiTexCoord([12])\\b");
    private static final Pattern GL_COLOR_CAPTURE = Pattern.compile("(?m)^([ \\t]*)(\\w+)(\\.rgb)?\\s*=\\s*gl_Color\\s*;");
    private static final Pattern MAIN_START = Pattern.compile("void\\s+main\\s*\\(\\s*(?:void)?\\s*\\)\\s*\\{");
    private static final String TINT_STMT = ".rgb *= mix(vec3(1.0), cl_tint, cl_blockW); // Colorful Lighting";

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
                "shaders/lib/util/commonFunctions.glsl", "out vec3 cl_blockLightTint;",
                "shaders/lib/lighting/mainLighting.glsl", "in vec3 cl_blockLightTint;" + CL_COLORIZE_FN,
                "lightmapXM * blocklightCol;",
                "lightmapXM * cl_colorize(blocklightCol);");

        // Same idea for BSL-derived packs (AstraLex).
        patched += wireForwardTint(files, notes, "BSL family",
                "shaders/settings/globalSettings.glsl", "varying vec3 cl_blockLightTint;",
                "shaders/lib/lighting/forwardLighting.glsl", "varying vec3 cl_blockLightTint;" + CL_COLORIZE_FN,
                "blocklightCol * pow2(newLightmap)",
                "cl_colorize(blocklightCol) * pow2(newLightmap)");

        // Sildur's Enhanced Default forces torchlight to vec3(emissive_R, emissive_G, emissive_B)
        // (default 3.0/1.5/0.5); the vertex-color tint below cannot survive that blue channel, so
        // fade the color to a neutral white of equal peak while colored block light is present.
        patched += neutralizeSildurEmissive(files, notes);

        // Everything else: weighted vertex-color tint, applied at the end of each patched vertex program.
        int tinted = 0;
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            String path = entry.getKey();
            if (!path.startsWith("shaders/") || !path.toLowerCase(Locale.ROOT).endsWith(".vsh")) continue;
            String src = text(entry.getValue());
            if (!src.contains("cl_decodeLight(")) continue;
            Matcher capture = GL_COLOR_CAPTURE.matcher(src);
            if (!capture.find()) continue;
            String var = capture.group(2);
            String withTint;
            if (capture.start() > src.indexOf("cl_decodeLight(gl_MultiTexCoord")) {
                // The capture runs after the lightmap decode (e.g. Mellow's init_generic, which has no
                // main() in this file), so the tint is already known here.
                withTint = src.substring(0, capture.end())
                        + "\n" + capture.group(1) + var + TINT_STMT
                        + src.substring(capture.end());
            } else {
                withTint = appendBeforeMainEnd(src, "    " + var + TINT_STMT);
            }
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
                        + " * cl_tint, 1.0 - min(cl_tint.r, min(cl_tint.g, cl_tint.b))); // Colorful Lighting";
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

        return patched;
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
                + "\n" + indent + "cl_blockLightTint = cl_tint; // Colorful Lighting"
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

    /** Inserts a statement right before the closing brace of main(). Returns null if main() cannot be delimited. */
    static String appendBeforeMainEnd(String src, String statement) {
        Matcher m = MAIN_START.matcher(src);
        if (!m.find()) return null;
        int depth = 1;
        int i = m.end();
        boolean lineComment = false, blockComment = false;
        while (i < src.length() && depth > 0) {
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
                if (depth == 0) {
                    return src.substring(0, i) + statement + "\n" + src.substring(i);
                }
            }
            i++;
        }
        return null;
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
