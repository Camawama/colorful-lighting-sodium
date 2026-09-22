package net.camacraft.colorfullighting.mixin.compat.dev;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfig;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfigValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.api.internal.ClientApi", remap = false)
public class DHMixin {
	@Inject(at = @At("HEAD"), method = "renderLods", cancellable = true)
	public void disableLodsIfDisabled(CallbackInfo ci) {
		IDhApiConfigValue<Boolean> value = DhApi.Delayed.configs.graphics().renderingEnabled();
		if (value == null) {
			// ?
//			ci.cancel();
			return;
		}
		Boolean b = value.getValue();
		if (b == null) {
			// ?
			return;
		}
		if (!b) {
			ci.cancel();
		}
	}
}
