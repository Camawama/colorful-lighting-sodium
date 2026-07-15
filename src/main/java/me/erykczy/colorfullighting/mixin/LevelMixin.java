package me.erykczy.colorfullighting.mixin;

import me.erykczy.colorfullighting.ColorfulLighting;
import me.erykczy.colorfullighting.accessors.LevelWrapper;
import me.erykczy.colorfullighting.common.BlockEntityNbtCache;
import me.erykczy.colorfullighting.common.ColoredLightEngine;
import me.erykczy.colorfullighting.common.accessors.LevelAccessor;
import me.erykczy.colorfullighting.common.accessors.mixin.ClientLevelAccessor;
import me.erykczy.colorfullighting.common.accessors.mixin.LevelAttachments;
import me.erykczy.colorfullighting.compat.valkyrienskies.VsCompat;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.WritableLevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

@Mixin(Level.class)
public class LevelMixin implements LevelAttachments {
	@Unique
	LevelAccessor colorfullighting$accessor;
	@Unique
	ColoredLightEngine colorfullighting$engine;
	@Unique
	VsCompat colorfullighting$vsCompat;
	@Unique
	BlockEntityNbtCache colorfullighting$nbtCache;
	
	@Inject(at = @At("TAIL"), method = "<init>")
	public void postInit(WritableLevelData p_270739_, ResourceKey p_270683_, RegistryAccess p_270200_, Holder p_270240_, Supplier p_270692_, boolean p_270904_, boolean p_270470_, long p_270248_, int p_270466_, CallbackInfo ci) {
		Level thisLvl = (Level) (Object) this;
		if (thisLvl instanceof ClientLevel clientLevel) {
			this.colorfullighting$accessor = new LevelWrapper(thisLvl, ((ClientLevelAccessor) clientLevel).colorfullighting$getLevelRenderer());
		} else {
			this.colorfullighting$accessor = new LevelWrapper(thisLvl, null);
		}
		
		colorfullighting$engine = ColoredLightEngine.create((Level) (Object) this, ColorfulLighting.clientAccessor);
		
		if (VsCompat.isAvailable()) {
			colorfullighting$vsCompat = new VsCompat();
		}
		
		colorfullighting$nbtCache = new BlockEntityNbtCache();
	}
	
	@Override
	public ColoredLightEngine colorfullighting$getEngine() {
		return colorfullighting$engine;
	}
	
	@Override
	public VsCompat colorfullighting$getVSCompat() {
		return colorfullighting$vsCompat;
	}
	
	@Override
	public LevelAccessor colorfullighting$getAccessor() {
		return colorfullighting$accessor;
	}
	@Override
	public BlockEntityNbtCache colorfullighting$getNbtCache() {
		return colorfullighting$nbtCache;
	}
}
