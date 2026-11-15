package net.camacraft.colorfullighting.compat.vanilla;

import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.camacraft.colorfullighting.ColorfulLighting;
import net.camacraft.colorfullighting.common.ColorfulLightingConfig;
import net.camacraft.colorfullighting.compat.dynamiclights.BlacklightField;
import net.camacraft.colorfullighting.compat.dynamiclights.ShaderDynamicLights;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL20C;

import java.util.Map;
import java.util.function.Function;

/**
 * Fluorescence (2.14.0): entities that glow under ultraviolet whatever their own colours, as a scorpion does. A blacklight
 * makes what is saturated glow ({@link EntityBlacklight}); a body on a mod's list glows in the colour the list gives it,
 * a dark one too.
 *
 * <p>Who is on the list is another mod's to say ({@link #setProvider}; Lively Lighting reads it from its definition files).
 * Nothing is added to a vertex and no buffer or render type is wrapped: the game already draws an entity's body in batches
 * of one texture, so the glow goes by texture. When an entity on the list is about to be drawn while ultraviolet is about,
 * its renderer's texture is noted for the frame; when an entity shader is bound for a batch, the texture on its first unit
 * is looked up and the glow's two uniforms are set if they differ from what the program holds. An entity that is not on
 * the list costs one lookup by its type while ultraviolet is about and nothing without; a batch costs one lookup by its
 * texture's number. What a renderer draws with other textures (a saddle, wool, what a mob holds) does not glow, unless a
 * definition names that texture ({@link #setTextures}).
 *
 * <p>Parameters, eight floats: the glow's red, green, blue (0..1) and strength; the part of the texture that glows by its
 * brightness, from and to (0 and 1 for all of it); a hue (0..1, below 0 for any) and how far from it (0..0.5).
 *
 * <p>{@code -Dcolorfullighting.entityFluorescence=false} switches it off; so does the config's {@code blacklightEntities}.
 */
public final class EntityFluorescence {
    private static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("colorfullighting.entityFluorescence"));
    public static final int PARAMETERS = 8;
    private static final float[] NONE = new float[PARAMETERS];

    @Nullable
    private static volatile Function<Entity, float[]> provider;
    private static volatile Map<ResourceLocation, float[]> fixedTextures = Map.of();

    /** Per texture number: the glow of what is drawn with it, and the frame that was said for. */
    private static final Int2ObjectOpenHashMap<float[]> MARKS = new Int2ObjectOpenHashMap<>();
    private static final Int2LongOpenHashMap MARKED = new Int2LongOpenHashMap();
    /** Per program: where its two uniforms are ({@code null} for a program without them), and what they hold. */
    private static final Int2ObjectOpenHashMap<int[]> LOCATIONS = new Int2ObjectOpenHashMap<>();
    private static final Int2ObjectOpenHashMap<float[]> SENT = new Int2ObjectOpenHashMap<>();
    private static final int[] WITHOUT = new int[0];

    private static long activeFrame = Long.MIN_VALUE;
    private static boolean active;
    private static long marksThisFrame, marksFrame = Long.MIN_VALUE, lastMarks, uploads;

    static {
        MARKED.defaultReturnValue(Long.MIN_VALUE);
    }

    private EntityFluorescence() {}

    /**
     * Who says whether an entity fluoresces: {@code null} for one that does not, else its eight parameters (the same array
     * for the same glow, so batches of one kind are told apart by identity). Asked on the render thread, once for each
     * entity drawn while ultraviolet is about: answer from a table by the entity's type.
     *
     * @since 2.14.0
     */
    public static void setProvider(@Nullable Function<Entity, float[]> entities) {
        provider = entities;
    }

    /**
     * Textures that fluoresce whoever draws them with an entity shader (a layer of a mob, a block entity's texture), each
     * with its eight parameters. Replaces the set given before.
     *
     * @since 2.14.0
     */
    public static void setTextures(@Nullable Map<ResourceLocation, float[]> textures) {
        fixedTextures = textures == null ? Map.of() : Map.copyOf(textures);
    }

    /** Whether anything can fluoresce in this frame: a list is there, the level is being drawn and ultraviolet is about. */
    private static boolean active() {
        long frame = ShaderDynamicLights.frame();
        if (frame != activeFrame) {
            activeFrame = frame;
            active = ENABLED && (provider != null || !fixedTextures.isEmpty()) && EntityBlacklight.patchedFragment() > 0
                    && ColorfulLightingConfig.blacklight() && ColorfulLightingConfig.blacklightEntities()
                    && (ShaderDynamicLights.ultravioletLightsThisFrame() || BlacklightField.shown())
                    // a shader pack draws entities with its own programs: nothing to look textures up for
                    && !net.camacraft.colorfullighting.compat.oculus.OculusCompat.isShaderPackInUse();
            if (active && !fixedTextures.isEmpty()) {
                for (Map.Entry<ResourceLocation, float[]> e : fixedTextures.entrySet()) mark(e.getKey(), e.getValue(), frame);
            }
        }
        return active;
    }

    /** An entity is about to be drawn (the dispatcher's render, at its head). Render thread. */
    public static void drawing(EntityRenderDispatcher dispatcher, Entity entity) {
        Function<Entity, float[]> entities = provider;
        if (entities == null || !EntityBlacklight.drawingLevel() || !active()) return;
        try {
            float[] glow = entities.apply(entity);
            if (glow == null || glow.length < PARAMETERS || glow[3] <= 0f) return;
            EntityRenderer<? super Entity> renderer = dispatcher.getRenderer(entity);
            ResourceLocation texture = renderer == null ? null : renderer.getTextureLocation(entity);
            if (texture != null) mark(texture, glow, activeFrame);
        } catch (RuntimeException e) {
            // a renderer that cannot name its texture for this entity: it does not glow
            if (!warned) {
                warned = true;
                ColorfulLighting.LOGGER.warn("Fluorescence: {} cannot be asked for its texture and does not glow", entity.getType(), e);
            }
        }
    }

    private static boolean warned;

    private static void mark(ResourceLocation texture, float[] glow, long frame) {
        // only a texture the game has loaded: asking for another would load it here
        AbstractTexture loaded = Minecraft.getInstance().getTextureManager().getTexture(texture, null);
        if (loaded == null) return;
        int id = loaded.getId();
        if (MARKS.size() > 512 && !MARKS.containsKey(id)) {
            MARKS.clear();
            MARKED.clear();
        }
        MARKS.put(id, glow);
        MARKED.put(id, frame);
        if (marksFrame != frame) {
            lastMarks = marksThisFrame;
            marksFrame = frame;
            marksThisFrame = 0;
        }
        marksThisFrame++;
    }

    /**
     * An entity program with the blacklight's part is bound for a draw and ultraviolet reaches it this frame: its glow
     * uniforms follow the texture on the first unit. Render thread, from {@link EntityBlacklight#apply}.
     */
    static void apply(int program) {
        float[] glow = NONE;
        if (active() && !MARKS.isEmpty()) {
            int texture = RenderSystem.getShaderTexture(0);
            if (MARKED.get(texture) == activeFrame) glow = MARKS.get(texture);
        }
        float[] held = SENT.get(program);
        if (held == glow || (held == null && glow == NONE)) return;
        int[] at = LOCATIONS.get(program);
        if (at == null) {
            int fluor = GL20C.glGetUniformLocation(program, "cl_fluor");
            at = fluor < 0 ? WITHOUT : new int[]{fluor, GL20C.glGetUniformLocation(program, "cl_fluorMask")};
            LOCATIONS.put(program, at);
        }
        if (at == WITHOUT) return;
        GL20C.glUniform4f(at[0], glow[0], glow[1], glow[2], glow[3]);
        if (at[1] >= 0 && glow != NONE) GL20C.glUniform4f(at[1], glow[4], glow[5], glow[6], glow[7]);
        SENT.put(program, glow);
        if (glow != NONE) uploads++;
    }

    /** A program was linked (or linked again under an old number): what was known of it is forgotten. */
    static void forget(int program) {
        LOCATIONS.remove(program);
        SENT.remove(program);
    }

    /** For tests and the blacklight command. */
    public static String stats() {
        if (!ENABLED) return "fluorescence off";
        if (provider == null && fixedTextures.isEmpty()) return "no fluorescence list";
        long marks = marksFrame == ShaderDynamicLights.frame() ? marksThisFrame : marksFrame == ShaderDynamicLights.frame() - 1 ? Math.max(marksThisFrame, lastMarks) : 0;
        return marks + " fluorescent bodies noted in the frame, " + uploads + " glow uploads";
    }

    /** For tests: how often a glow went to a program. */
    public static long uploads() {
        return uploads;
    }

    /** For tests: the textures noted in the last finished frame or this one. */
    public static long marks() {
        long frame = ShaderDynamicLights.frame();
        return marksFrame == frame ? Math.max(marksThisFrame, lastMarks) : marksFrame == frame - 1 ? marksThisFrame : 0;
    }
}
