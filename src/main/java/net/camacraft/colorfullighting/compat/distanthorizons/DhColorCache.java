package net.camacraft.colorfullighting.compat.distanthorizons;

import net.camacraft.colorfullighting.ColorfulLighting;
import net.camacraft.colorfullighting.common.engine.AbstractColoredLightSection;
import net.camacraft.colorfullighting.common.engine.cl.ColoredLightSection;
import net.minecraft.core.SectionPos;

import javax.annotation.Nullable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Remembered net light colour (light minus darkness) per section, kept after the section leaves the
 * engine's view area so far LODs can replay it. The engine only stores colour for loaded chunks;
 * Distant Horizons renders far beyond them, so this cache is the only colour source out there. Data
 * is possibly stale by design: whatever the chunk looked like when it was last loaded.
 *
 * <p>Stored downsampled, not at block resolution: the render volume samples at 4 blocks per texel at
 * best ({@link DhColorVolume}), so each section keeps a 4x4x4 RGB mip (192 bytes) plus a whole-section
 * average. That keeps a long play session's cache in the low tens of MB instead of GBs.
 *
 * <p>Thread model: entries are written by the {@link DhCompat} worker thread and read by the render
 * thread (volume rebuild) via the concurrent map. Load/save also run on the worker.
 */
public final class DhColorCache {
    /** 4x4x4 texels per section, 4 bytes each (net RGB + absorption). */
    public static final int MIP_TEXELS = 64;
    public static final int MIP_BYTES = MIP_TEXELS * 4;
    /** ~34MB of RAM/disk at worst; beyond this the farthest sections from the player are dropped. */
    public static final int MAX_SECTIONS = 131_072;
    private static final int MAGIC = 0x434C4432; // "CLD2"
    /**
     * v3: each cluster's RGB is the dominant (most colorful) block's HUE scaled to the cluster's
     * AVERAGE net level (the dominant block's own level dilated light fields toward the 4-block
     * grid, visibly skewing a small diamond's corners), plus a 4th byte of absorption (darkness
     * peak) so absorbers (end portals etc.) can darken LODs. A version bump discards old files.
     */
    private static final int FORMAT_VERSION = 3;

    /** Immutable snapshot of one section's remembered colour. */
    public static final class Entry {
        /**
         * 4 bytes per 4x4x4-block cluster, indexed {@code (y>>2)<<4 | (z>>2)<<2 | (x>>2)}, each
         * times 4: net RGB (dominant hue at the cluster's average level) plus absorption
         * (darkness peak times 17).
         */
        public final byte[] mip;
        /**
         * The section's colour for the coarse far volume (one texel per section). The HUE is the
         * most COLORFUL cluster's (highest chroma), not the brightest: brightest picked a beacon's
         * white core over the blue light its stained glass casts, turning the whole area white at
         * distance; white light is "vanilla" anyway, so a real colour must always win over it
         * (falls back to the brightest cluster when the whole section is white/gray light).
         *
         * <p>The BRIGHTNESS is halfway between the section's average level (mean of the mip
         * cells' levels, unlit cells included) and its brightest cluster's. Pure brightest made
         * every distant LOD with a light in it glow at full intensity across the whole section;
         * pure average made a lone light nearly invisible at far/ultra range once the remembered
         * level became the authoritative LOD light. See fromMip.
         */
        public final byte farR, farG, farB;
        /**
         * The section's absorption for the far volume: the strongest cluster's, not the average.
         * Absorbers are compact (an end portal is 25 blocks in a 4096-block section), so an
         * average would erase them; overshoot is benign because the shader only uses absorption
         * to cap DH's baked light down to the remembered net level, a no-op where they agree.
         */
        public final byte farAbsorption;

        Entry(byte[] mip, int farR, int farG, int farB, int farAbsorption) {
            this.mip = mip;
            this.farR = (byte) farR;
            this.farG = (byte) farG;
            this.farB = (byte) farB;
            this.farAbsorption = (byte) farAbsorption;
        }

        static Entry fromMip(byte[] mip) {
            int best = 0;
            int bestChroma = -1;
            int bestPeak = -1;
            int levelSum = 0;
            int maxLevel = 0;
            int maxAbsorption = 0;
            for (int i = 0; i < MIP_TEXELS; ++i) {
                int r = mip[i * 4] & 0xFF, g = mip[i * 4 + 1] & 0xFF, b = mip[i * 4 + 2] & 0xFF;
                int peak = Math.max(r, Math.max(g, b));
                int chroma = peak - Math.min(r, Math.min(g, b));
                levelSum += peak;
                maxLevel = Math.max(maxLevel, peak);
                maxAbsorption = Math.max(maxAbsorption, mip[i * 4 + 3] & 0xFF);
                if (chroma > bestChroma || (chroma == bestChroma && peak > bestPeak)) {
                    bestChroma = chroma;
                    bestPeak = peak;
                    best = i;
                }
            }
            int domR = mip[best * 4] & 0xFF, domG = mip[best * 4 + 1] & 0xFF, domB = mip[best * 4 + 2] & 0xFF;
            int domPeak = Math.max(domR, Math.max(domG, domB));
            // Halfway between the section average and its brightest cluster. The plain average
            // was chosen when far brightness only LIFTED DH's bake; now that the remembered level
            // is authoritative it must carry perceived brightness itself, and a lone light
            // averaged over a 4096-block section rendered "extremely dim" at far/ultra range.
            // The brightest-cluster share keeps a small source visible; the average share keeps
            // a whole section from glowing at its hottest point's level (the test #7 complaint).
            int farLevel = (levelSum / MIP_TEXELS + maxLevel) / 2;
            if (domPeak == 0) return new Entry(mip, 0, 0, 0, maxAbsorption);
            return new Entry(mip,
                    Math.round(domR * (float) farLevel / domPeak),
                    Math.round(domG * (float) farLevel / domPeak),
                    Math.round(domB * (float) farLevel / domPeak),
                    maxAbsorption);
        }
    }

    private final ConcurrentHashMap<Long, Entry> sections = new ConcurrentHashMap<>();
    /** Bumped on every content change. */
    private final AtomicInteger version = new AtomicInteger();
    /**
     * Bumped only on BULK changes (load, prune) where patching section-by-section would be
     * pointless; the render volume does a full rebuild when this moves. Single-section stores
     * land in {@link #dirtySections} instead and are patched incrementally — re-uploading all
     * three 28MB volume textures on every store ran the rebuild throttle at its ceiling and
     * dropped frames four times a second (2026-08-07 profile).
     */
    private final AtomicInteger structureVersion = new AtomicInteger();
    /** Sections changed since the volume last drained; written by the worker, drained render-side. */
    private final java.util.Set<Long> dirtySections = ConcurrentHashMap.newKeySet();
    private final Path file;
    private volatile boolean dirtySinceSave = false;

    public DhColorCache(Path file) {
        this.file = file;
    }

    public int getVersion() { return version.get(); }
    public int getStructureVersion() { return structureVersion.get(); }
    public int getSectionCount() { return sections.size(); }
    public Path getFile() { return file; }

    /** Iteration for the volume rebuild (render thread); weakly consistent, which is fine here. */
    public Iterable<Map.Entry<Long, Entry>> entries() { return sections.entrySet(); }

    /** Point lookup for incremental volume patches (render thread). */
    @Nullable
    public Entry getEntry(long sectionPos) { return sections.get(sectionPos); }

    /**
     * Hands every section changed since the last drain to {@code consumer} and unmarks it.
     * Render thread. A store racing the drain either lands in this batch or stays marked for
     * the next one; nothing is lost.
     */
    public void drainDirtySections(java.util.function.LongConsumer consumer) {
        var it = dirtySections.iterator();
        while (it.hasNext()) {
            long pos = it.next();
            it.remove();
            consumer.accept(pos);
        }
    }

    /** Discards pending dirty marks; called before a full volume rebuild, which covers them. */
    public void clearDirtySections() { dirtySections.clear(); }

    /**
     * Builds the downsampled entry for one section from the engine's live storage, or null when the
     * section holds no colour at all. Reading the sections off-thread races the propagator the same
     * way render-thread sampling does: worst case one stale block, and the section goes dirty (and is
     * recaptured) on the next change anyway.
     */
    @Nullable
    public static Entry buildEntry(@Nullable AbstractColoredLightSection light, @Nullable AbstractColoredLightSection darkness) {
        if (light == null && darkness == null) return null;
        // Each 4x4x4 cluster keeps its dominant (most colorful, then brightest) block's HUE at the
        // cluster's AVERAGE net level. The hue must be a single block's (averaging RGB over
        // mostly-unlit blocks washed fringe hues to gray), but the LEVEL must be the average: the
        // dominant block's own level dilated every light field toward the 4-block cluster grid,
        // which visibly skewed a small diamond's corners depending on where the source sat in its
        // cluster. Absorption (darkness peak) is captured per cluster so absorbers darken LODs.
        byte[] mip = null;
        int[] bestChroma = null;
        int[] bestPeak = null;
        int[] domR = null, domG = null, domB = null;
        int[] levelSum = null; // per cluster, sum of net peaks over all 64 blocks (nibble units)
        for (int idx = 0; idx < 4096; ++idx) {
            int l = light == null ? 0 : light.getPacked(idx);
            int d = darkness == null ? 0 : darkness.getPacked(idx);
            if (l == 0 && d == 0) continue;
            if (mip == null) {
                mip = new byte[MIP_BYTES];
                bestChroma = new int[MIP_TEXELS];
                bestPeak = new int[MIP_TEXELS];
                domR = new int[MIP_TEXELS];
                domG = new int[MIP_TEXELS];
                domB = new int[MIP_TEXELS];
                levelSum = new int[MIP_TEXELS];
                java.util.Arrays.fill(bestChroma, -1);
            }
            // getColorIndex is y<<8 | z<<4 | x
            int x = idx & 15, z = (idx >>> 4) & 15, y = (idx >>> 8) & 15;
            int mi = (y >> 2) << 4 | (z >> 2) << 2 | (x >> 2);

            int dr = (d >>> 8) & 0xF, dg = (d >>> 4) & 0xF, db = d & 0xF;
            int dPeak = Math.max(dr, Math.max(dg, db));
            int storedAbsorption = mip[mi * 4 + 3] & 0xFF;
            if (dPeak * 17 > storedAbsorption) mip[mi * 4 + 3] = (byte) (dPeak * 17);

            int r = Math.max(0, ((l >>> 8) & 0xF) - dr);
            int g = Math.max(0, ((l >>> 4) & 0xF) - dg);
            int b = Math.max(0, (l & 0xF) - db);
            if ((r | g | b) == 0) continue;
            int peak = Math.max(r, Math.max(g, b));
            int chroma = peak - Math.min(r, Math.min(g, b));
            levelSum[mi] += peak;
            if (chroma > bestChroma[mi] || (chroma == bestChroma[mi] && peak > bestPeak[mi])) {
                bestChroma[mi] = chroma;
                bestPeak[mi] = peak;
                domR[mi] = r;
                domG[mi] = g;
                domB[mi] = b;
            }
        }
        if (mip == null) return null;
        for (int mi = 0; mi < MIP_TEXELS; ++mi) {
            if (bestPeak[mi] <= 0) continue;
            // Average in the fine 0..255 scale, floored to 1, so faint fringes (one level-1 block
            // would be 17/64) survive instead of flooring to zero and cutting glow edges off.
            int avgLevel = Math.max(1, levelSum[mi] * 17 / 64);
            mip[mi * 4] = (byte) Math.min(255, Math.round(domR[mi] * (float) avgLevel / bestPeak[mi]));
            mip[mi * 4 + 1] = (byte) Math.min(255, Math.round(domG[mi] * (float) avgLevel / bestPeak[mi]));
            mip[mi * 4 + 2] = (byte) Math.min(255, Math.round(domB[mi] * (float) avgLevel / bestPeak[mi]));
        }
        return Entry.fromMip(mip);
    }

    /** Stores or (when {@code entry} is null) erases the remembered colour for a section. */
    public void store(long sectionPos, @Nullable Entry entry) {
        boolean changed;
        if (entry == null) {
            changed = sections.remove(sectionPos) != null;
        } else {
            sections.put(sectionPos, entry);
            changed = true;
        }
        if (changed) {
            version.incrementAndGet();
            dirtySections.add(sectionPos);
            dirtySinceSave = true;
        }
    }

    /** Drops the farthest sections from the given player section once over budget. Worker thread. */
    public void pruneIfNeeded(int centerSectionX, int centerSectionZ) {
        if (sections.size() <= MAX_SECTIONS) return;
        int toDrop = sections.size() - (MAX_SECTIONS - MAX_SECTIONS / 16);
        List<long[]> byDistance = new ArrayList<>(sections.size()); // [distSq, sectionPos]
        for (Long pos : sections.keySet()) {
            long dx = SectionPos.x(pos) - centerSectionX;
            long dz = SectionPos.z(pos) - centerSectionZ;
            byDistance.add(new long[]{dx * dx + dz * dz, pos});
        }
        byDistance.sort((a, b) -> Long.compare(b[0], a[0]));
        for (int i = 0; i < toDrop && i < byDistance.size(); ++i) {
            sections.remove(byDistance.get(i)[1]);
        }
        version.incrementAndGet();
        structureVersion.incrementAndGet();
        dirtySinceSave = true;
        ColorfulLighting.LOGGER.info("[DH color cache] pruned {} far sections ({} kept)", toDrop, sections.size());
    }

    public boolean needsSave() { return dirtySinceSave; }

    /** Worker thread. Failures only lose the remembered colour, so they log and move on. */
    public void load() {
        if (!Files.isRegularFile(file)) return;
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(newInputStream()))) {
            if (in.readInt() != MAGIC) throw new IOException("bad magic");
            int formatVersion = in.readInt();
            if (formatVersion != FORMAT_VERSION) throw new IOException("unknown format version " + formatVersion);
            int count = in.readInt();
            for (int i = 0; i < count; ++i) {
                long pos = in.readLong();
                byte[] mip = new byte[MIP_BYTES];
                in.readFully(mip);
                sections.put(pos, Entry.fromMip(mip));
            }
            version.incrementAndGet();
            structureVersion.incrementAndGet();
            ColorfulLighting.LOGGER.info("[DH color cache] loaded {} remembered sections from {}", count, file.getFileName());
        } catch (Exception e) {
            ColorfulLighting.LOGGER.warn("[DH color cache] failed to load {}: {}", file, e.toString());
        }
    }

    /** Worker thread. Writes to a temp file first so a crash mid-save keeps the previous cache. */
    public void save() {
        if (!dirtySinceSave) return;
        dirtySinceSave = false;
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(tmp)))) {
                out.writeInt(MAGIC);
                out.writeInt(FORMAT_VERSION);
                // Snapshot the entries first: the map may grow while we write, and the count must match.
                List<Map.Entry<Long, Entry>> snapshot = new ArrayList<>(sections.entrySet());
                out.writeInt(snapshot.size());
                for (Map.Entry<Long, Entry> entry : snapshot) {
                    out.writeLong(entry.getKey());
                    out.write(entry.getValue().mip);
                }
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            dirtySinceSave = true;
            ColorfulLighting.LOGGER.warn("[DH color cache] failed to save {}: {}", file, e.toString());
        }
    }

    private InputStream newInputStream() throws IOException {
        return Files.newInputStream(file);
    }
}
