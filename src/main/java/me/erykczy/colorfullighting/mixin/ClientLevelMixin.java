package me.erykczy.colorfullighting.mixin;

import me.erykczy.colorfullighting.common.accessors.mixin.ClientLevelAccessor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ClientLevel.class)
public class ClientLevelMixin implements ClientLevelAccessor {
	@Shadow
	@Final
	private LevelRenderer levelRenderer;
	
	@Override
	public LevelRenderer colorfullighting$getLevelRenderer() {
		return levelRenderer;
	}
}
