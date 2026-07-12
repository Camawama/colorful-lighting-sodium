package me.erykczy.colorfullighting.mixin.compat.iris;

import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.include.AbsolutePackPath;
import net.irisshaders.iris.shaderpack.properties.ShaderProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.function.Function;

@Mixin(ShaderPack.class)
public interface ShaderPackAccessor {
	@Accessor
	ShaderProperties getShaderProperties();
	
	@Accessor
	Function<AbsolutePackPath, String> getSourceProvider();
}
