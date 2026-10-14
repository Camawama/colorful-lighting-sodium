package net.camacraft.colorfullighting.mixin.compat.xaero.minimap;

import net.camacraft.colorfullighting.compat.xaero.XaeroMinimapTileKeys;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.common.minimap.region.MinimapTile;

/**
 * Each minimap tile keeps the colored-light key its pixels were drawn with (see {@code XaeroMinimapWriterMixin}),
 * allocated only for a tile that has one. Tiles are recycled: a reused tile starts without keys.
 */
@Mixin(value = MinimapTile.class, remap = false)
public abstract class XaeroMinimapTileMixin implements XaeroMinimapTileKeys {
    @Unique private char[] colorfullighting$keys;

    @Override
    public int colorfullighting$key(int x, int z) {
        char[] keys = this.colorfullighting$keys;
        return keys == null ? 0 : keys[z << 4 | x];
    }

    @Override
    public void colorfullighting$setKey(int x, int z, int key) {
        char[] keys = this.colorfullighting$keys;
        if (keys == null) {
            if (key == 0) return;
            keys = this.colorfullighting$keys = new char[256];
        }
        keys[z << 4 | x] = (char) key;
    }

    @Inject(method = "create", at = @At("HEAD"), require = 0)
    private void colorfullighting$recycled(CallbackInfo ci) {
        this.colorfullighting$keys = null;
    }
}
