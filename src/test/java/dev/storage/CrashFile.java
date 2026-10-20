package dev.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import dev.storage.file.ChannelOpener;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * A FileChannel that models the OS page cache, for crash tests. A write lands in memory, at 512-byte sector
 * granularity, and reads see it; force() moves it to the real file. The cache belongs to the path, as the OS's does,
 * so a file closed and opened again still sees what was written; closing doesn't flush. Each call to write or force
 * ticks a countdown shared by every file of one Crash. When it reaches zero that call throws, as a power cut would,
 * and so does every call after it.
 *
 * Crash.materialize() then decides what the disk ended up with: each sector written since the last force is kept or
 * lost independently, at random. So a page can tear at any sector boundary, but a sector is all old or all new (the
 * atomic-sector assumption the log header and meta page rely on).
 *
 * Simplification: truncate() is durable at once. The engine only truncates the log during a reset, and forces it
 * right after.
 */
final class CrashFile extends FileChannel {

    static final int SECTOR = 512;

    /** One simulated machine: the crash point, and the files that lose their caches together. */
    static final class Crash {
        private final Random random;
        private long countdown;
        private volatile boolean crashed;
        private final Map<Path, Cache> caches = new HashMap<>();
        private final List<FileChannel> opened = new ArrayList<>();

        Crash(long ticksBeforeCrash, long seed) {
            this.countdown = ticksBeforeCrash;
            this.random = new Random(seed);
        }

        /** An opener for the engine whose files share this machine's caches. */
        ChannelOpener opener() {
            return (path, create) -> {
                FileChannel real = ChannelOpener.DEFAULT.open(path, create);
                synchronized (this) {
                    opened.add(real);
                    Cache cache = caches.computeIfAbsent(path.toAbsolutePath(), p -> new Cache());
                    if (create) {
                        cache.dirty.clear();
                        cache.size = 0;
                    } else if (cache.size < 0) {
                        cache.size = real.size();
                    }
                    cache.channel = real;
                    return new CrashFile(real, this, cache);
                }
            };
        }

        boolean crashed() {
            return crashed;
        }

        synchronized void tick() throws IOException {
            if (crashed) {
                throw new IOException("simulated power loss (already crashed)");
            }
            if (--countdown <= 0) {
                crashed = true;
                throw new IOException("simulated power loss");
            }
        }

        /** The power cut: each unforced sector reaches the disk or doesn't, at random. */
        synchronized void materialize() throws IOException {
            crashed = true;
            for (Map.Entry<Path, Cache> e : caches.entrySet()) {
                Cache cache = e.getValue();
                // Through the file's own channel if it's still open: it follows the file through a rename (the page
                // file is created under a temporary name). Dropping a renamed file's sectors would roll the page
                // file back to its last fsync every time, and hide every bug where a page reaches it too early.
                FileChannel real = cache.channel != null && cache.channel.isOpen() ? cache.channel
                        : Files.exists(e.getKey()) ? FileChannel.open(e.getKey(), StandardOpenOption.WRITE) : null;
                if (real == null) {
                    continue;
                }
                for (Map.Entry<Long, byte[]> sector : cache.dirty.entrySet()) {
                    if (random.nextBoolean()) {
                        real.write(ByteBuffer.wrap(sector.getValue()), sector.getKey() * SECTOR);
                    }
                }
                cache.dirty.clear();
                if (real != cache.channel) {
                    real.close();
                }
            }
            for (FileChannel real : opened) {
                real.close(); // the crashed process's files
            }
        }
    }

    /** What the OS holds for one path: the sectors written since the last force, and the file's size. */
    private static final class Cache {
        final TreeMap<Long, byte[]> dirty = new TreeMap<>();
        long size = -1;
        FileChannel channel; // the latest one opened on this path
    }

    private final FileChannel real;
    private final Crash crash;
    private final Cache cache;

    private CrashFile(FileChannel real, Crash crash, Cache cache) {
        this.real = real;
        this.crash = crash;
        this.cache = cache;
        if (cache.size < 0) {
            cache.size = 0;
        }
    }

    @Override
    public int write(ByteBuffer src, long position) throws IOException {
        synchronized (crash) {
            crash.tick();
            int n = src.remaining();
            long at = position;
            while (src.hasRemaining()) {
                long index = at / SECTOR;
                int within = (int) (at % SECTOR);
                byte[] sector = sector(index);
                int length = Math.min(SECTOR - within, src.remaining());
                src.get(sector, within, length);
                at += length;
            }
            cache.size = Math.max(cache.size, position + n);
            return n;
        }
    }

    @Override
    public int read(ByteBuffer dst, long position) throws IOException {
        synchronized (crash) {
            if (position >= cache.size) {
                return -1;
            }
            int n = (int) Math.min(dst.remaining(), cache.size - position);
            long at = position;
            for (int done = 0; done < n; ) {
                long index = at / SECTOR;
                int within = (int) (at % SECTOR);
                int length = Math.min(SECTOR - within, n - done);
                byte[] sector = cache.dirty.get(index);
                if (sector == null) {
                    sector = readSector(index);
                }
                dst.put(sector, within, length);
                at += length;
                done += length;
            }
            return n;
        }
    }

    @Override
    public void force(boolean metaData) throws IOException {
        synchronized (crash) {
            crash.tick();
            for (Map.Entry<Long, byte[]> sector : cache.dirty.entrySet()) {
                real.write(ByteBuffer.wrap(sector.getValue()), sector.getKey() * SECTOR);
            }
            cache.dirty.clear();
            // No real fsync: the real file stands in for the disk, and this machine never really loses power.
        }
    }

    @Override
    public long size() {
        synchronized (crash) {
            return cache.size;
        }
    }

    @Override
    public FileChannel truncate(long newSize) throws IOException {
        synchronized (crash) {
            if (newSize < cache.size) {
                real.truncate(newSize);
                cache.dirty.tailMap(newSize / SECTOR + (newSize % SECTOR == 0 ? 0 : 1)).clear();
                Map.Entry<Long, byte[]> partial = cache.dirty.floorEntry(newSize / SECTOR);
                if (partial != null && partial.getKey() == newSize / SECTOR) {
                    Arrays.fill(partial.getValue(), (int) (newSize % SECTOR), SECTOR, (byte) 0);
                }
                cache.size = newSize;
            }
            return this;
        }
    }

    // The sector's bytes for writing into: its dirty copy, or a new one read from the real file.
    private byte[] sector(long index) throws IOException {
        byte[] sector = cache.dirty.get(index);
        if (sector == null) {
            sector = readSector(index);
            cache.dirty.put(index, sector);
        }
        return sector;
    }

    private byte[] readSector(long index) throws IOException {
        byte[] sector = new byte[SECTOR];
        ByteBuffer b = ByteBuffer.wrap(sector);
        long at = index * SECTOR;
        while (b.hasRemaining()) {
            int n = real.read(b, at + b.position());
            if (n < 0) {
                break; // past the real end: zeros
            }
        }
        return sector;
    }

    @Override
    protected void implCloseChannel() throws IOException {
        real.close(); // closing doesn't flush: unforced sectors stay in the cache
    }

    // Positional I/O is all the engine uses.
    @Override
    public int read(ByteBuffer dst) {
        throw new UnsupportedOperationException();
    }

    @Override
    public long read(ByteBuffer[] dsts, int offset, int length) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int write(ByteBuffer src) {
        throw new UnsupportedOperationException();
    }

    @Override
    public long write(ByteBuffer[] srcs, int offset, int length) {
        throw new UnsupportedOperationException();
    }

    @Override
    public long position() {
        throw new UnsupportedOperationException();
    }

    @Override
    public FileChannel position(long newPosition) {
        throw new UnsupportedOperationException();
    }

    @Override
    public long transferTo(long position, long count, WritableByteChannel target) {
        throw new UnsupportedOperationException();
    }

    @Override
    public long transferFrom(ReadableByteChannel src, long position, long count) {
        throw new UnsupportedOperationException();
    }

    @Override
    public MappedByteBuffer map(MapMode mode, long position, long size) {
        throw new UnsupportedOperationException();
    }

    @Override
    public FileLock lock(long position, long size, boolean shared) {
        throw new UnsupportedOperationException();
    }

    @Override
    public FileLock tryLock(long position, long size, boolean shared) {
        throw new UnsupportedOperationException();
    }
}
