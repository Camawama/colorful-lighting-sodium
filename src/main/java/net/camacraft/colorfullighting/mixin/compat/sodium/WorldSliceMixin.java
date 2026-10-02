package net.camacraft.colorfullighting.mixin.compat.sodium;

import net.camacraft.colorfullighting.common.BlockEntityNbtCache;
import net.camacraft.colorfullighting.common.ColoredLightEngine;
import net.camacraft.colorfullighting.common.accessors.LevelAccessor;
import net.camacraft.colorfullighting.common.accessors.mixin.LevelAttachments;
import net.camacraft.colorfullighting.compat.CompatRegistry;
import net.camacraft.colorfullighting.compat.flywheel.FlywheelCompat;
import net.camacraft.colorfullighting.compat.dynamiclights.DynamicLightsCompat;
import net.camacraft.colorfullighting.compat.valkyrienskies.VsCompat;
import me.jellysquid.mods.sodium.client.world.WorldSlice;
import net.camacraft.colorfullighting.compat.distanthorizons.DhColorCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(WorldSlice.class)
public class WorldSliceMixin implements LevelAttachments, CompatRegistry<Level> {
	@Shadow
	@Final
	public ClientLevel world;
	
	@Override
	public ColoredLightEngine colorfullighting$getEngine() {
		return ((LevelAttachments) world).colorfullighting$getEngine();
	}
	
	@Override
	public VsCompat colorfullighting$getVSCompat() {
		return ((LevelAttachments) world).colorfullighting$getVSCompat();
	}

	@Override
	public DynamicLightsCompat colorfullighting$getDynamicLights() {
		return ((LevelAttachments) world).colorfullighting$getDynamicLights();
	}
	
	@Override
	public LevelAccessor colorfullighting$getAccessor() {
		return ((LevelAttachments) world).colorfullighting$getAccessor();
	}
	
	@Override
	public BlockEntityNbtCache colorfullighting$getNbtCache() {
		return ((LevelAttachments) world).colorfullighting$getNbtCache();
	}
	
	@Override
	public FlywheelCompat colorfullighting$getFlywheelCompat() {
		return ((LevelAttachments) world).colorfullighting$getFlywheelCompat();
	}

	@Override
	public DhColorCache colorfullighting$getDhColorCache() {
		return ((LevelAttachments) world).colorfullighting$getDhColorCache();
	}

	@Override
	public void colorfullighting$setDhColorCache(DhColorCache cache) {
		((LevelAttachments) world).colorfullighting$setDhColorCache(cache);
	}
	
	@Override
	public <T> T colorfullighting$getCompatInstance(CompatKey<Level, T> key) {
		return ((CompatRegistry<Level>) world).colorfullighting$getCompatInstance(key);
	}
}
