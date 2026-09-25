package net.camacraft.colorfullighting.mixin;

import net.camacraft.colorfullighting.ColorfulLighting;
import net.camacraft.colorfullighting.accessors.LevelWrapper;
import net.camacraft.colorfullighting.api.CLSupportingLevel;
import net.camacraft.colorfullighting.common.BlockEntityNbtCache;
import net.camacraft.colorfullighting.common.ColoredLightEngine;
import net.camacraft.colorfullighting.common.ColoredLightInterface;
import net.camacraft.colorfullighting.common.accessors.LevelAccessor;
import net.camacraft.colorfullighting.common.accessors.mixin.ClientLevelAccessor;
import net.camacraft.colorfullighting.common.accessors.mixin.LevelAttachments;
import net.camacraft.colorfullighting.compat.CompatRegistry;
import net.camacraft.colorfullighting.compat.flywheel.FlywheelCompat;
import net.camacraft.colorfullighting.compat.dynamiclights.DynamicLightsCompat;
import net.camacraft.colorfullighting.compat.valkyrienskies.VsCompat;
import net.camacraft.colorfullighting.compat.distanthorizons.DhColorCache;
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

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

@Mixin(Level.class)
public class LevelMixin implements LevelAttachments, CompatRegistry<Level> {
	@Unique
	LevelAccessor colorfullighting$accessor;
	@Unique
	ColoredLightEngine colorfullighting$engine;
	@Unique
	VsCompat colorfullighting$vsCompat;
	@Unique
    DynamicLightsCompat colorfullighting$dynamicLights;
	@Unique
	BlockEntityNbtCache colorfullighting$nbtCache;
	@Unique
	FlywheelCompat colorfullighting$flywheelCompat;
	/** Written on the client thread, read by the render thread; volatile for safe publication. */
	@Unique
	volatile DhColorCache colorfullighting$dhColorCache;
	
	@Inject(at = @At("TAIL"), method = "<init>")
	public void postInit(WritableLevelData p_270739_, ResourceKey p_270683_, RegistryAccess p_270200_, Holder p_270240_, Supplier p_270692_, boolean p_270904_, boolean p_270470_, long p_270248_, int p_270466_, CallbackInfo ci) {
		boolean isClient = false;
		
		Level thisLvl = (Level) (Object) this;
		if (thisLvl instanceof ClientLevel clientLevel) {
			this.colorfullighting$accessor = new LevelWrapper(thisLvl, ((ClientLevelAccessor) clientLevel).colorfullighting$getLevelRenderer());
			isClient = true;
		} else {
			this.colorfullighting$accessor = new LevelWrapper(thisLvl, null);
		}
		
		// don't initialize CL if the level doesn't support colorful lighting
		if (this instanceof CLSupportingLevel) {
			// before the engine: the engine constructor caches this attachment for its sampling hot path
			colorfullighting$dynamicLights = new DynamicLightsCompat();
			colorfullighting$engine = ColoredLightInterface.create((Level) (Object) this, ColorfulLighting.clientAccessor);

			if (VsCompat.isAvailable()) {
				colorfullighting$vsCompat = new VsCompat();
			}
			
			colorfullighting$nbtCache = new BlockEntityNbtCache();
			
			// TODO: check if flywheel supporting world
			if (FlywheelCompat.isAvailable() && isClient) {
				colorfullighting$flywheelCompat = new FlywheelCompat();
			}
		}
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
	public DynamicLightsCompat colorfullighting$getDynamicLights() {
		return colorfullighting$dynamicLights;
	}
	
	@Override
	public LevelAccessor colorfullighting$getAccessor() {
		return colorfullighting$accessor;
	}
	
	@Override
	public BlockEntityNbtCache colorfullighting$getNbtCache() {
		return colorfullighting$nbtCache;
	}
	
	@Override
	public FlywheelCompat colorfullighting$getFlywheelCompat() {
		return colorfullighting$flywheelCompat;
	}

	@Override
	public DhColorCache colorfullighting$getDhColorCache() {
		return colorfullighting$dhColorCache;
	}

	@Override
	public void colorfullighting$setDhColorCache(DhColorCache cache) {
		colorfullighting$dhColorCache = cache;
	}
	
	Map<CompatKey<?, ?>, Object> compats = new HashMap<>();
	
	@Override
	public Object colorfullighting$getCompatInstance(CompatKey key) {
		Object o = compats.get(key);
		if (o == null) {
			o = key.generator().apply(this);
			compats.put(key, o);
		}
		return o;
	}
	
	@Inject(at = @At("HEAD"), method = "close")
	public void preClose(CallbackInfo ci) {
		if (colorfullighting$engine != null) {
			colorfullighting$engine.unload();
		}
	}
}
