package net.camacraft.colorfullighting.mixin.compat.vanilla;

import net.camacraft.colorfullighting.common.BlockEntityNbtCache;
import net.camacraft.colorfullighting.common.ColoredLightEngine;
import net.camacraft.colorfullighting.common.accessors.LevelAccessor;
import net.camacraft.colorfullighting.common.accessors.mixin.LevelAttachments;
import net.camacraft.colorfullighting.compat.CompatRegistry;
import net.camacraft.colorfullighting.compat.flywheel.FlywheelCompat;
import net.camacraft.colorfullighting.compat.dynamiclights.DynamicLightsCompat;
import net.camacraft.colorfullighting.compat.valkyrienskies.VsCompat;
import net.camacraft.colorfullighting.compat.distanthorizons.DhColorCache;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(RenderChunkRegion.class)
public class RenderChunkRegionMixin implements LevelAttachments, CompatRegistry<Level> {
	@Shadow
	@Final
	protected Level level;
	
	@Override
	public ColoredLightEngine colorfullighting$getEngine() {
		return ((LevelAttachments) level).colorfullighting$getEngine();
	}
	
	@Override
	public VsCompat colorfullighting$getVSCompat() {
		return ((LevelAttachments) level).colorfullighting$getVSCompat();
	}

	@Override
	public DynamicLightsCompat colorfullighting$getDynamicLights() {
		return ((LevelAttachments) level).colorfullighting$getDynamicLights();
	}
	
	@Override
	public LevelAccessor colorfullighting$getAccessor() {
		return ((LevelAttachments) level).colorfullighting$getAccessor();
	}
	
	@Override
	public BlockEntityNbtCache colorfullighting$getNbtCache() {
		return ((LevelAttachments) level).colorfullighting$getNbtCache();
	}
	
	@Override
	public FlywheelCompat colorfullighting$getFlywheelCompat() {
		return ((LevelAttachments) level).colorfullighting$getFlywheelCompat();
	}

	@Override
	public DhColorCache colorfullighting$getDhColorCache() {
		return ((LevelAttachments) level).colorfullighting$getDhColorCache();
	}

	@Override
	public void colorfullighting$setDhColorCache(DhColorCache cache) {
		((LevelAttachments) level).colorfullighting$setDhColorCache(cache);
	}
	
	@Override
	public <T> T colorfullighting$getCompatInstance(CompatKey<Level, T> key) {
		return ((CompatRegistry<Level>) level).colorfullighting$getCompatInstance(key);
	}
}
