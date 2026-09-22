package net.camacraft.colorfullighting.common.engine;

import net.camacraft.colorfullighting.common.engine.cl.ColoredLightSection;
import net.camacraft.colorfullighting.common.util.ColorRGB4;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;

import javax.annotation.Nullable;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Class responsible for storing light color values for each block in each section of the world
 */
public class ColoredLightStorage {
    private ConcurrentHashMap<Long, AbstractColoredLightSection> map = new ConcurrentHashMap<>();

    @Nullable
    public ColorRGB4 getEntry(BlockPos blockPos) { return getEntry(blockPos.getX(), blockPos.getY(), blockPos.getZ()); }
    @Nullable
    public ColorRGB4 getEntry(int x, int y, int z) {
        long sectionPos = SectionPos.blockToSection(BlockPos.asLong(x, y, z));
	    AbstractColoredLightSection layer = getSection(sectionPos);
        if(layer == null) return null;
        return layer.get(
                SectionPos.sectionRelative(x),
                SectionPos.sectionRelative(y),
                SectionPos.sectionRelative(z)
        );
    }

    /*public void setEntry(BlockPos blockPos, ColorRGB4 value) { setEntry(blockPos.getX(), blockPos.getY(), blockPos.getZ(), value); }
    public void setEntry(int x, int y, int z, ColorRGB4 value) {
        long sectionPos = SectionPos.blockToSection(BlockPos.asLong(x, y, z));
        map.computeIfPresent(sectionPos, (pos, layer) -> {
            layer.set(
                    SectionPos.sectionRelative(x),
                    SectionPos.sectionRelative(y),
                    SectionPos.sectionRelative(z),
                    value
            );
            return layer;
        });
    }*/
    public void setEntryUnsafe(BlockPos blockPos, ColorRGB4 value) { setEntryUnsafe(blockPos.getX(), blockPos.getY(), blockPos.getZ(), value); }
    public void setEntryUnsafe(int x, int y, int z, ColorRGB4 value) {
        long sectionPos = SectionPos.blockToSection(BlockPos.asLong(x, y, z));
        var layer = map.get(sectionPos);
        if(layer == null) return;
        layer.set(
                SectionPos.sectionRelative(x),
                SectionPos.sectionRelative(y),
                SectionPos.sectionRelative(z),
                value
        );
    }

    public boolean containsEntry(BlockPos blockPos) { return containsEntry(blockPos.getX(), blockPos.getY(), blockPos.getZ()); }
    public boolean containsEntry(int x, int y, int z) {
        return containsSection(SectionPos.blockToSection(BlockPos.asLong(x, y, z)));
    }

    public boolean containsSection(long sectionPos) {
        return map.containsKey(sectionPos);
    }

    public AbstractColoredLightSection getSection(long sectionPos) {
        return map.get(sectionPos);
    }

    /**
     * Adds an empty section unless one already exists. Never replaces: the view area and extra
     * region rectangles may overlap (adjacent remote-level cells share border columns, a region can
     * overlap a stale view area), and whichever owner adds a column second must not wipe light
     * already propagated there. A caller that needs a fresh section removes it first (see
     * LightPropagator's region rebuild).
     */
    public void addSection(long sectionPos) {
        map.computeIfAbsent(sectionPos, pos -> new ColoredLightSection());
    }

    /** Number of sections currently held; diagnostics only. */
    public int sectionCount() {
        return map.size();
    }

    public void removeSection(long sectionPos) {
        map.remove(sectionPos);
    }

    /** Visits every stored section that actually holds colour data (lazy sections are skipped). */
    public void forEachPopulatedSection(java.util.function.LongConsumer action) {
        map.forEach((pos, section) -> {
            if (section.hasData()) action.accept(pos);
        });
    }

    public void clear() {
        map.clear();
    }
}
