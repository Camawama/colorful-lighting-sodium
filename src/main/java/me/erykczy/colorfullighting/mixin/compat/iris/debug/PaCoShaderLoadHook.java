package me.erykczy.colorfullighting.mixin.compat.iris.debug;

import com.github.andrew0030.pandora_core.modules.templater.hook.ShaderLoadHook;
import com.github.andrew0030.pandora_core.modules.templater.loader.TemplateLoader;
import com.github.andrew0030.pandora_core.modules.templater.wrapper.impl.program.attachment.AttachmentType;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Mixin(value = ShaderLoadHook.class, remap = false)
public class PaCoShaderLoadHook {
	private static void dumpShader(ResourceLocation location, String source, String dumpMeta) {
		String pth = "cl_shader_dump/" + dumpMeta + "/" + location.getNamespace() + "/" + location.getPath();
		File fl = new File(pth);
		
		try {
			if (!fl.exists()) {
				fl.getParentFile().mkdirs();
				fl.createNewFile();
			}
			
			FileOutputStream fs = new FileOutputStream(fl);
			fs.write(source.getBytes(StandardCharsets.UTF_8));
			fs.flush();
			fs.close();
		} catch (Throwable err) {
			err.printStackTrace();
		}
	}
	
	@Inject(at = @At("HEAD"), method = "preSource")
	private static void preSource(TemplateLoader loader, List<String> source, ResourceLocation resourceLocation, CallbackInfoReturnable<List<String>> cir) {
		StringBuilder builder = new StringBuilder();
		for (String s : source) {
			builder.append(s).append("\n");
		}
		dumpShader(resourceLocation, builder.toString(), loader.name());
	}
}
