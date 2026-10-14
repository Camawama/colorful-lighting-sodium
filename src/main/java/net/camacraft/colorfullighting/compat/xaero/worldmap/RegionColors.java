package net.camacraft.colorfullighting.compat.xaero.worldmap;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * The colored light Xaero's World Map recorded for one of its regions (512 x 512 blocks of one cave layer): for each
 * pixel the key {@code (r4 << 8 | g4 << 4 | b4) << 4 | blockLight} the World Map last drew it with
 * ({@link net.camacraft.colorfullighting.compat.xaero.XaeroMapColors#key}), 0 where there was none. Kept per
 * 64 x 64 tile chunk (Xaero's texture), allocated only for tile chunks that have some. Saved beside the World Map's
 * region file as {@code <x>_<z>.clcolors}: a compressed list of the tile chunks that have colors.
 *
 * <p>Every change to a tile chunk stamps it, so a texture built from it knows when it is out of date. Uses no Xaero
 * class.
 */
public final class RegionColors {
    private static final int MAGIC = 0x434C5843; // "CLXC"
    private static final int VERSION = 1;
    private static final int TILE_PIXELS = 64 * 64;
    private static final AtomicLong STAMPS = new AtomicLong();

    final Path file;
    private final char[][] tiles = new char[64][];
    private final long[] stamps = new long[64];
    private volatile boolean loaded;
    private boolean loadRequested;
    private boolean dirty;

    RegionColors(Path file) {
        this.file = file;
    }

    public boolean isLoaded() {
        return this.loaded;
    }

    /** True the first time a background read is asked for. */
    synchronized boolean requestLoad() {
        if (this.loadRequested) return false;
        this.loadRequested = true;
        return true;
    }

    /** The key at a pixel of tile chunk {@code tile} ({@code (z & 7) << 3 | (x & 7)}), pixel {@code y * 64 + x}. */
    public synchronized int get(int tile, int pixel) {
        char[] values = this.tiles[tile];
        return values == null ? 0 : values[pixel];
    }

    /** @return whether the key there changed */
    public synchronized boolean set(int tile, int pixel, int key) {
        char[] values = this.tiles[tile];
        if (values == null) {
            if (key == 0) return false;
            values = this.tiles[tile] = new char[TILE_PIXELS];
        }
        if (values[pixel] == key) return false;
        values[pixel] = (char) key;
        this.stamps[tile] = STAMPS.incrementAndGet();
        this.dirty = true;
        return true;
    }

    public synchronized boolean any(int tile) {
        return this.tiles[tile] != null;
    }

    public synchronized long stamp(int tile) {
        return this.stamps[tile];
    }

    /** A copy of a tile chunk's keys, or null when it has none. */
    public synchronized char[] copy(int tile) {
        char[] values = this.tiles[tile];
        return values == null ? null : values.clone();
    }

    /** Reads the file once; a missing or unreadable file leaves the region empty. */
    synchronized void load() {
        if (this.loaded) return;
        try {
            if (this.file != null && Files.isRegularFile(this.file)) {
                try (DataInputStream in = new DataInputStream(new BufferedInputStream(new InflaterInputStream(Files.newInputStream(this.file))))) {
                    if (in.readInt() == MAGIC && in.readInt() == VERSION) {
                        int count = in.readInt();
                        for (int i = 0; i < count; i++) {
                            int tile = in.readUnsignedByte();
                            char[] values = new char[TILE_PIXELS];
                            for (int p = 0; p < TILE_PIXELS; p++) values[p] = in.readChar();
                            if (tile < 64 && this.tiles[tile] == null) {
                                this.tiles[tile] = values;
                                this.stamps[tile] = STAMPS.incrementAndGet();
                            }
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            WorldMapColorStore.LOGGER.warn("Couldn't read Xaero map colors {}: {}", this.file, e.toString());
        }
        this.loaded = true;
    }

    /** Writes the file if anything changed since it was read or last written (a temp file, then moved over it). */
    void saveIfDirty() {
        char[][] snapshot = new char[64][];
        synchronized (this) {
            if (!this.dirty || this.file == null) return;
            this.dirty = false;
            for (int i = 0; i < 64; i++) {
                if (this.tiles[i] != null) snapshot[i] = this.tiles[i].clone();
            }
        }
        int count = 0;
        for (int i = 0; i < 64; i++) {
            if (snapshot[i] != null && !empty(snapshot[i])) count++;
            else snapshot[i] = null;
        }
        try {
            if (count == 0) {
                Files.deleteIfExists(this.file);
                return;
            }
            Files.createDirectories(this.file.getParent());
            Path temp = this.file.resolveSibling(this.file.getFileName() + ".tmp");
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new DeflaterOutputStream(Files.newOutputStream(temp))))) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeInt(count);
                for (int i = 0; i < 64; i++) {
                    if (snapshot[i] == null) continue;
                    out.writeByte(i);
                    for (char value : snapshot[i]) out.writeChar(value);
                }
            }
            Files.move(temp, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            synchronized (this) {
                this.dirty = true;
            }
            WorldMapColorStore.LOGGER.warn("Couldn't save Xaero map colors {}: {}", this.file, e.toString());
        }
    }

    synchronized boolean isDirty() {
        return this.dirty;
    }

    private static boolean empty(char[] values) {
        for (char value : values) {
            if (value != 0) return false;
        }
        return true;
    }
}
