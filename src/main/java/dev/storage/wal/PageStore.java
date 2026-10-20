package dev.storage.wal;

import dev.storage.buffer.BufferPool;
import dev.storage.buffer.Replacer;
import dev.storage.file.PageFile;
import dev.storage.file.StorageFailedException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.IntFunction;

/**
 * The logged page layer: a page file, its buffer pool, its write-ahead log and the page allocator. Every change goes
 * through a mini-transaction from begin(); the pool writes a page back only once the log holds it.
 *
 * Mini-transactions hold the store's lock shared, so a checkpoint, which takes it exclusively, sees no change half
 * made.
 */
public final class PageStore implements AutoCloseable {

    /** How a store is opened. replacer builds the pool's replacer for a frame count. */
    public record Config(int pageSize, int poolFrames, IntFunction<Replacer> replacer, Durability durability,
            long checkpointLogBytes) { }

    private final PageFile file;
    private final Wal wal;
    private final BufferPool pool;
    private final PageAllocator allocator;
    private final Config config;
    private final ReentrantReadWriteLock changes = new ReentrantReadWriteLock();
    private volatile long redoStart;
    private volatile Throwable failure;

    private PageStore(PageFile file, Wal wal, Config config) {
        this.file = file;
        this.wal = wal;
        this.config = config;
        this.pool = new BufferPool(file, config.poolFrames(), config.replacer().apply(config.poolFrames()),
                this::flushTo);
        this.allocator = new PageAllocator(file.pageCount(), file.freeListHead(), file.catalogRoot());
        this.redoStart = file.checkpointLsn();
    }

    /** A new store: a page file at path and its log at path + "-wal". Fails if either exists. */
    public static PageStore create(Path path, Config config) {
        PageFile file = PageFile.create(path, config.pageSize());
        try {
            Wal wal = Wal.create(open(logPath(path), true), file.checkpointLsn());
            return new PageStore(file, wal, config);
        } catch (RuntimeException e) {
            file.close();
            throw e;
        }
    }

    public static Path logPath(Path path) {
        return path.resolveSibling(path.getFileName() + "-wal");
    }

    /** Starts a mini-transaction. */
    public Mtr begin() {
        checkUsable();
        changes.readLock().lock();
        try {
            checkUsable();
        } catch (RuntimeException e) {
            changes.readLock().unlock();
            throw e;
        }
        return new Mtr(this);
    }

    void endMtr() {
        changes.readLock().unlock();
    }

    public BufferPool pool() {
        return pool;
    }

    public int pageSize() {
        return file.pageSize();
    }

    public Durability durability() {
        return config.durability();
    }

    /** The catalog's root page, or 0 if there's none yet. */
    public int catalogRoot() {
        allocator.lock.lock();
        try {
            return allocator.catalogRoot;
        } finally {
            allocator.lock.unlock();
        }
    }

    PageFile file() {
        return file;
    }

    Wal wal() {
        return wal;
    }

    PageAllocator allocator() {
        return allocator;
    }

    long redoStart() {
        return redoStart;
    }

    void flushTo(long lsn) {
        try {
            wal.flushTo(lsn);
        } catch (RuntimeException e) {
            fail(e);
            throw e;
        }
    }

    /** Stops the store for good: nothing more is logged or written back. Reopening recovers from the log. */
    void fail(Throwable cause) {
        if (failure == null) {
            failure = cause;
        }
        pool.fail(cause);
    }

    public void checkUsable() {
        if (failure != null) {
            throw new StorageFailedException("the store failed earlier and must be reopened", failure);
        }
    }

    @Override
    public void close() {
        if (failure != null) {
            // Only the log is trusted now: write nothing else.
            try {
                wal.close();
            } finally {
                file.abandon();
            }
            return;
        }
        try {
            pool.close();
        } finally {
            try {
                wal.close();
            } finally {
                file.close();
            }
        }
    }

    private static FileChannel open(Path path, boolean create) {
        try {
            return create
                    ? FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE,
                            StandardOpenOption.CREATE_NEW)
                    : FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
