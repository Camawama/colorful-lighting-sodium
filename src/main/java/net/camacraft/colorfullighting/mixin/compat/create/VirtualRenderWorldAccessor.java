package net.camacraft.colorfullighting.mixin.compat.create;

import com.simibubi.create.foundation.virtualWorld.VirtualRenderWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = VirtualRenderWorld.class, remap = false)
public interface VirtualRenderWorldAccessor {
    @Accessor("externalPackedLight")
    int colorfullighting$getExternalPackedLight();
}
