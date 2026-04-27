package me.erykczy.colorfullighting.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.io.InputStream;
import java.util.List;
import java.util.Set;

public class ColorfulLightingMixinPlugin implements IMixinConfigPlugin {

    private boolean hasClass(String className) {
        try (InputStream is = this.getClass().getClassLoader().getResourceAsStream(className.replace('.', '/') + ".class")) {
            return is != null;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.contains(".flywheel.")) {
            // Ensure Flywheel 1.0+ is installed before applying flywheel mixins
            return hasClass("dev.engine_room.flywheel.backend.engine.LightStorage");
        }
        if (mixinClassName.contains(".create.")) {
            // Ensure Create 0.6+ is installed before applying create mixins
            return hasClass("net.createmod.catnip.render.ShadeSeparatingSuperByteBuffer");
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
