package me.erykczy.colorfullighting.mixin.render;

import me.erykczy.colorfullighting.common.BlockEntityNbtCache;
import me.erykczy.colorfullighting.common.ColoredLightEngine;
import me.erykczy.colorfullighting.common.accessors.LevelAccessor;
import me.erykczy.colorfullighting.common.accessors.mixin.LevelAttachments;
import me.erykczy.colorfullighting.compat.valkyrienskies.VsCompat;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(RenderChunkRegion.class)
public class RenderChunkRegionMixin implements LevelAttachments {
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
	public LevelAccessor colorfullighting$getAccessor() {
		return ((LevelAttachments) level).colorfullighting$getAccessor();
	}
	
	@Override
	public BlockEntityNbtCache colorfullighting$getNbtCache() {
		return ((LevelAttachments) level).colorfullighting$getNbtCache();
	}
}
