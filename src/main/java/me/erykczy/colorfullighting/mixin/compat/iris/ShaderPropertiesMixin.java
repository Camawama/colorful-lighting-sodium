package me.erykczy.colorfullighting.mixin.compat.iris;

import me.erykczy.colorfullighting.common.accessors.iris.CustomShaderProperties;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.helpers.OptionalBoolean;
import net.irisshaders.iris.shaderpack.properties.ShaderProperties;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.Consumer;

@Mixin(ShaderProperties.class)
public abstract class ShaderPropertiesMixin implements CustomShaderProperties {
	@Shadow
	private static void handleBooleanDirective(String key, String value, String expectedKey, Consumer<OptionalBoolean> handler) {
		throw new RuntimeException("wat?");
	}
	
	@Shadow
	private static boolean handleIntDirective(String key, String value, String expectedKey, Consumer<Integer> handler) {
		throw new RuntimeException("wat?");
	}
	
	@Unique
	private static void colorfullighting$handleStringDirective(String key, String value, String expectedKey, Consumer<String> handler) {
		if (expectedKey.equals(key)) {
			handler.accept(value);
		}
	}
	
	// 0: incompatible (shader developer has tested, it does not work)
	// 1: attempt patch
	// 2: standardized patch will work (guarantee provided by shader developer)
	//    this option disables all internal pack-specific patches
	// 3: natively compatible (support explicitly implemented by shader developer)
	//    enables any specialized helpers meant for pack devs instead of only using internal utils
	// 4: custom compat (support explicitly implemented by shader developer)
	//    disables all helpers, the developer is assumed to have implemented the helpers on their own with this option
	@Unique
	private OptionalInt colorfullighting$compatStatus = OptionalInt.empty();
	@Unique
	private String colorfullighting$patcherFamily = null;
	
	@Inject(at = @At("TAIL"), method = "lambda$new$48")
	public void colorfullighting$postInit(Object keyObject, Object valueObject, CallbackInfo ci) {
		handleIntDirective((String) keyObject, (String) valueObject, "colorful_lighting.compat_status", value -> colorfullighting$compatStatus = OptionalInt.of(value));
		colorfullighting$handleStringDirective((String) keyObject, (String) valueObject, "colorful_lighting.patcher_family", value -> colorfullighting$patcherFamily = value);
	}
	
	@Override
	public OptionalInt colorfullighting$getCompatStatus() {
		return colorfullighting$compatStatus;
	}
	
	@Override
	public String colorfullighting$getPatcherFamily() {
		return colorfullighting$patcherFamily;
	}
}
