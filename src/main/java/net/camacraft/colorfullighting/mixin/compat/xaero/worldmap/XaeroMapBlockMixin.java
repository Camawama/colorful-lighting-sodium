package net.camacraft.colorfullighting.mixin.compat.xaero.worldmap;

import net.camacraft.colorfullighting.compat.xaero.worldmap.XaeroMapBlockKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.map.region.MapBlock;
import xaero.map.region.MapPixel;

/**
 * A World Map pixel keeps the colored-light key its writer read ({@link XaeroMapBlockKey}; WorldMapColors.capture),
 * and a pixel whose light changed color counts as changed, so the World Map redraws its tile. A pixel loaded from
 * disk has no key ({@link XaeroMapBlockKey#UNKNOWN}): a newly read pixel with a color differs from it.
 */
@Mixin(value = MapBlock.class, remap = false)
public abstract class XaeroMapBlockMixin extends MapPixel implements XaeroMapBlockKey {
    @Unique private int colorfullighting$key = UNKNOWN;

    @Override
    public int colorfullighting$key() {
        return this.colorfullighting$key;
    }

    @Override
    public void colorfullighting$setKey(int key) {
        this.colorfullighting$key = key;
    }

    @Override
    public int colorfullighting$light() {
        return this.light;
    }

    @Override
    public boolean colorfullighting$glowing() {
        return this.glowing;
    }

    @Inject(method = "prepareForWriting", at = @At("HEAD"), require = 0)
    private void colorfullighting$reset(int defaultHeight, CallbackInfo ci) {
        this.colorfullighting$key = UNKNOWN;
    }

    @Inject(method = "equalsSlopesExcluded", at = @At("RETURN"), cancellable = true, require = 0)
    private void colorfullighting$colorChanged(MapBlock other, CallbackInfoReturnable<Boolean> cir) {
        int mine = this.colorfullighting$key;
        if (!cir.getReturnValueZ() || mine == UNKNOWN) return;
        int theirs = ((XaeroMapBlockKey) other).colorfullighting$key();
        if (theirs == UNKNOWN ? mine != 0 : theirs != mine) cir.setReturnValue(false);
    }
}
