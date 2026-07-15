package me.erykczy.colorfullighting.mixin.compat.iris;

import com.google.common.collect.ImmutableList;
import me.erykczy.colorfullighting.common.accessors.mixin.iris.ResolvedShaderPack;
import me.erykczy.colorfullighting.compat.oculus.specific.ShaderSpecificPatcher;
import net.irisshaders.iris.shaderpack.ShaderPack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Path;
import java.util.Map;

@Mixin(ShaderPack.class)
public class ShaderPackMixin implements ResolvedShaderPack {
	String name = null;
	Path root;
	
	@Inject(at = @At("RETURN"), method = "<init>(Ljava/nio/file/Path;Ljava/util/Map;Lcom/google/common/collect/ImmutableList;)V")
	public void postInit(Path root, Map changedConfigs, ImmutableList environmentDefines, CallbackInfo ci) {
		this.root = root;
	}
	
	@Override
	public String colorfullighting$getResolvedName() {
		if (name == null) name = ShaderSpecificPatcher.resolveShader((ShaderPack) (Object) this);
		return name;
	}
	
	@Override
	public Path colorfullighting$path() {
		return root;
	}
}
