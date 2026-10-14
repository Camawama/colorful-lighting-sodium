package net.camacraft.colorfullighting.mixin.compat.xaero.minimap;

import net.camacraft.colorfullighting.compat.xaero.XaeroMapColors;
import net.camacraft.colorfullighting.compat.xaero.XaeroMinimapColors;
import net.camacraft.colorfullighting.compat.xaero.XaeroMinimapTileKeys;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.common.minimap.region.MinimapTile;
import xaero.common.minimap.write.MinimapWriter;
import xaero.common.mods.SupportMods;

import java.util.List;

/**
 * Xaero's Minimap "Lighting": at night a map pixel is lit by the block light above it, as a grey brightness.
 * This gives the block-lit part of that brightness the hue of the colored light there, per channel with Xaero's
 * own formula, so a pixel lit by a red lamp shows red at night, and by day (sky light dominating) nothing changes.
 * The minimap's own chunks only: with Xaero's World Map installed the minimap usually shows the World Map's chunks,
 * which the {@code worldmap} mixins color.
 *
 * <p>Xaero computes a pixel in {@code loadBlockColor}: {@code findBlock} walks down to the surface block and records
 * the block light above it and above each see-through layer ({@code pixelBlockLights}); the pixel is recomputed
 * ({@code calculateBlockColors}) only when {@code MinimapTile.pixelChanged} says its blocks or light levels changed,
 * else the old tile's colour is reused; the result is stored per sky level with {@code MinimapTile.setRGB}. Each
 * level's brightness is {@code (9 + max(skyTerm, blockLight)) / 24}; here each channel gets
 * {@code (9 + max(skyTerm, blockLight * channel / brightestChannel)) / 24} instead. A see-through top (water, glass)
 * uses the light above it, as Xaero's top-layer brightness does.
 *
 * <p>The light's color is part of the reuse test: each tile keeps the key ({@link XaeroMapColors#key}) its pixels
 * were drawn with ({@code XaeroMinimapTileMixin}), and a pixel whose key changed is recomputed, so walling a lamp in
 * blue glass, or breaking the glass, redraws it ({@code XaeroChunkRefresh} brings the minimap back to the chunk
 * when the colors settle).
 *
 * <p>Cave mode shows colored light the same way (its brightness is block light too); with Xaero's "legible cave
 * maps", which shade by depth instead of light, the light's color shows as a tint by its strength. Glowing blocks
 * and Xaero's light-level overlay keep Xaero's own colours. Applied only when Xaero's Minimap has every member used
 * here (ColorfulLightingMixinPlugin checks the classes).
 */
@Mixin(value = MinimapWriter.class, remap = false)
public abstract class XaeroMinimapWriterMixin {
    @Shadow private int sun;
    @Shadow private int blockY;
    @Shadow private int firstBlockY;
    @Shadow boolean isglowing;
    @Shadow private int loadingLevels;
    @Shadow private float loadingSingleLevelBrightness;
    @Shadow public int loadingLightOverlayType;
    @Shadow private boolean loadingLegibleCaveMode;

    @Unique private Level colorfullighting$level;
    @Unique private int colorfullighting$x, colorfullighting$z;
    /** The cells above the surface block and above the top layer, where findBlock read their block light. */
    @Unique private int colorfullighting$surfaceY, colorfullighting$topY;
    /** The sky term findBlock left for the pixel, before loadBlockColor raises it for see-through layers. */
    @Unique private int colorfullighting$firstSun;
    @Unique private List<Integer> colorfullighting$lights;
    @Unique private List<Integer> colorfullighting$layers;
    @Unique private boolean colorfullighting$lighting;
    @Unique private boolean colorfullighting$cave;
    /** Whether the current pixel's colour is being computed (not reused from the old tile). */
    @Unique private boolean colorfullighting$fresh;
    /** The current pixel's key; -1 until worked out. */
    @Unique private int colorfullighting$key = -1;

    /**
     * In cave mode the minimap draws its own chunks rather than the World Map's cave chunks, as it does when the World
     * Map's cave layers can't be used: the World Map builds its cave layers from the world save in singleplayer, so
     * they carry no light colors. On the surface it keeps using World Map chunks (colored by the worldmap mixins).
     */
    @Redirect(method = "onRender", at = @At(value = "INVOKE", target = "Lxaero/common/mods/SupportMods;shouldUseWorldMapCaveChunks()Z"), require = 0)
    private boolean colorfullighting$ownCaveChunks(SupportMods supportMods) {
        return supportMods.shouldUseWorldMapCaveChunks() && !XaeroMapColors.minimapOn();
    }

    @Inject(method = "findBlock", at = @At("HEAD"), require = 0)
    private void colorfullighting$pixelStart(Level world, LevelChunk bchunk, int insideX, int insideZ, int highY, int lowY,
                                             int loadingCaving, boolean loadingRedstone, BlockPos.MutableBlockPos mutableBlockPos,
                                             BlockPos.MutableBlockPos mutableBlockPos2, int loadingColours, boolean loadingTransparency,
                                             List<Integer> pixelBlockLights, List<BlockState> pixelBlockStates, int loadingLevels,
                                             boolean loadingLighting, List<Integer> pixelTransparentSizes, boolean loadingFlowers,
                                             boolean loadingStainedGlass, BlockPos.MutableBlockPos mutableBlockPos3,
                                             boolean framedBlocksExist, CallbackInfoReturnable<Block> cir) {
        this.colorfullighting$fresh = false;
        this.colorfullighting$key = -1;
        this.colorfullighting$level = world;
        this.colorfullighting$x = (bchunk.getPos().x << 4) + insideX;
        this.colorfullighting$z = (bchunk.getPos().z << 4) + insideZ;
        this.colorfullighting$lights = pixelBlockLights;
        this.colorfullighting$layers = pixelTransparentSizes;
        this.colorfullighting$lighting = loadingLighting;
        this.colorfullighting$cave = loadingCaving != Integer.MAX_VALUE;
    }

    @Inject(method = "findBlock", at = @At("RETURN"), require = 0)
    private void colorfullighting$pixelFound(CallbackInfoReturnable<Block> cir) {
        this.colorfullighting$surfaceY = this.blockY + 1;
        this.colorfullighting$topY = this.firstBlockY + 1;
        this.colorfullighting$firstSun = this.sun;
    }

    @Inject(method = "calculateBlockColors", at = @At("HEAD"), require = 0)
    private void colorfullighting$pixelComputed(CallbackInfo ci) {
        this.colorfullighting$fresh = true;
    }

    /** A pixel whose light's color changed is recomputed, like one whose blocks or light levels changed. */
    @Redirect(method = "loadBlockColor", at = @At(value = "INVOKE", target = "Lxaero/common/minimap/region/MinimapTile;pixelChanged(IIJBBBB)Z"), require = 0)
    private boolean colorfullighting$pixelChanged(MinimapTile oldTile, int x, int z, long code, byte add, byte add2,
                                                  byte verticalSlope, byte diagonalSlope) {
        return oldTile.pixelChanged(x, z, code, add, add2, verticalSlope, diagonalSlope)
                || ((XaeroMinimapTileKeys) oldTile).colorfullighting$key(x, z) != colorfullighting$key();
    }

    /** The tile remembers the key each pixel was drawn with. */
    @Redirect(method = "loadBlockColor", at = @At(value = "INVOKE", target = "Lxaero/common/minimap/region/MinimapTile;setCode(IIJBBBB)V"), require = 0)
    private void colorfullighting$setCode(MinimapTile tile, int x, int z, long code, byte add, byte add2,
                                          byte verticalSlope, byte diagonalSlope) {
        tile.setCode(x, z, code, add, add2, verticalSlope, diagonalSlope);
        ((XaeroMinimapTileKeys) tile).colorfullighting$setKey(x, z, colorfullighting$key());
    }

    @ModifyArg(method = "loadBlockColor", at = @At(value = "INVOKE", target = "Lxaero/common/minimap/region/MinimapTile;setRGB(IIIIII)V"), index = 3, require = 0)
    private int colorfullighting$red(int level, int x, int z, int r, int g, int b) {
        return colorfullighting$tint(level, r, 8);
    }

    @ModifyArg(method = "loadBlockColor", at = @At(value = "INVOKE", target = "Lxaero/common/minimap/region/MinimapTile;setRGB(IIIIII)V"), index = 4, require = 0)
    private int colorfullighting$green(int level, int x, int z, int r, int g, int b) {
        return colorfullighting$tint(level, g, 4);
    }

    @ModifyArg(method = "loadBlockColor", at = @At(value = "INVOKE", target = "Lxaero/common/minimap/region/MinimapTile;setRGB(IIIIII)V"), index = 5, require = 0)
    private int colorfullighting$blue(int level, int x, int z, int r, int g, int b) {
        return colorfullighting$tint(level, b, 0);
    }

    /** {@code value} (one channel, 0..255) with the block-lit part of its brightness in the light's hue. */
    @Unique
    private int colorfullighting$tint(int level, int value, int shift) {
        if (!this.colorfullighting$fresh) return value;
        int key = colorfullighting$key();
        if (key == 0) return value;
        float hue = XaeroMapColors.hue(key, shift);
        if (hue >= 1F) return value;
        int light = XaeroMapColors.light(key);
        int result;
        if (this.loadingLegibleCaveMode && this.colorfullighting$cave) {
            // legible cave maps shade by depth, not light: the light's color shows as a tint by its strength
            result = (int) (value * (1F - 0.6F * light / 15F * (1F - hue)));
        } else {
            // Xaero's sky term for this level (MinimapWriter.getBlockBrightness / getFixedSkyLightBlockBrightness)
            int sunTerm = colorfullighting$layered() ? this.colorfullighting$firstSun : this.sun;
            float sky = this.loadingLevels == 1
                    ? this.loadingSingleLevelBrightness * 15.0F
                    : (level == 0 ? 1.0F : (this.loadingLevels - 1.0F - level) / (this.loadingLevels - 1.0F)) * sunTerm;
            float xaero = 9.0F + Math.max(sky, light);
            float tinted = 9.0F + Math.max(sky, light * hue);
            result = (int) (value * tinted / xaero);
        }
        if (result != value) XaeroMinimapColors.tinted(level, shift, value, result);
        return result;
    }

    @Unique
    private boolean colorfullighting$layered() {
        List<Integer> layers = this.colorfullighting$layers;
        return layers != null && !layers.isEmpty();
    }

    /** The current pixel's key, worked out once: the light Xaero lights its top with and that light's color. */
    @Unique
    private int colorfullighting$key() {
        if (this.colorfullighting$key >= 0) return this.colorfullighting$key;
        int key = 0;
        List<Integer> lights = this.colorfullighting$lights;
        if (this.colorfullighting$lighting && !this.isglowing && this.loadingLightOverlayType <= 0
                && lights != null && !lights.isEmpty() && XaeroMapColors.minimapOn()) {
            boolean layered = colorfullighting$layered();
            int light = layered ? lights.get(0) : lights.get(lights.size() - 1);
            int y = layered ? this.colorfullighting$topY : this.colorfullighting$surfaceY;
            key = XaeroMapColors.key(this.colorfullighting$level, this.colorfullighting$x, y, this.colorfullighting$z, light);
        }
        return this.colorfullighting$key = key;
    }
}
