package dev.storage.wal;

import dev.storage.buffer.BufferPool;
import dev.storage.buffer.PageGuard;
import dev.storage.buffer.Replacer;
import dev.storage.file.ChannelOpener;
import dev.storage.file.PageFile;
import dev.storage.file.StorageFailedException;
import dev.storage.file.StorageFormatException;
import dev.storage.page.PageHeader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.System.Logger.Level;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.IntFunction;

/**
 * The logged page layer: a page file, its buffer pool, its write-ahead log and the page allocator. Every change goes
 * through a mini-transaction from begin(); the pool writes a page back only once the log holds it.
 *
 * Mini-transactions hold the store's lock shared, so a checkpoint, which takes it exclusively, sees no change half
 * made. Callers that also take their own locks (a tree's) take them after begin(), never before, or a checkpoint
 * waiting for the store lock could deadlock them.
 *
 * Opening an existing store recovers it: the meta page is the last checkpoint, and every log group after it is
 * redone, skipping pages whose LSN shows they already have it. Then it checkpoints, so the log starts empty.
 */
public final class PageStore implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(PageStore.class.getName());

    /** How a store is opened. replacer builds the pool's replacer for a frame count. */
    public record Config(int pageSize, int poolFrames, IntFunction<Replacer> replacer, Durability durability,
            long checkpointLogBytes) { }

    private final PageFile file;
    private final BufferPool pool;
    private final PageAllocator allocator;
    private final Config config;
    private final ReentrantReadWriteLock changes = new ReentrantReadWriteLock();
    private volatile Wal wal; // set once, right after the log is opened
    private volatile long redoStart;
    private volatile Throwable failure;
    private volatile boolean closed;

    private PageStore(PageFile file, Config config) {
        this.file = file;
        this.config = config;
        this.pool = new BufferPool(file, config.poolFrames(), config.replacer().apply(config.poolFrames()),
                this::flushTo);
        this.allocator = new PageAllocator(file.pageCount(), file.freeListHead(), file.catalogRoot());
        this.redoStart = file.checkpointLsn();
    }

    /** Opens the store at path (its log is path + "-wal"), recovering it if needed, or creates it. */
    public static PageStore open(Path path, Config config) {
        return open(path, config, ChannelOpener.DEFAULT);
    }

    /** Opens the store at path, recovering it if needed, or creates it if the page file doesn't exist. */
    public static PageStore open(Path path, Config config, ChannelOpener opener) {
        boolean exists = Files.exists(path);
        PageFile file = exists ? PageFile.open(path, opener) : createFiles(path, config.pageSize(), opener);
        PageStore store = null;
        try {
            store = new PageStore(file, config);
            Path logPath = logPath(path);
            if (!exists) {
                store.wal = Wal.open(opener.open(logPath, false), group -> { });
                return store;
            }
            long checkpoint = file.checkpointLsn();
            if (!Files.exists(logPath)) {
                // A clean store can lose its log safely; one with redo pending can't, but then nothing can be done.
                LOG.log(Level.WARNING, "{0} has no log; starting a new one at LSN {1}", path, checkpoint);
                store.wal = Wal.create(opener.open(logPath, true), checkpoint);
                return store;
            }
            List<Wal.Group> groups = new ArrayList<>();
            // Wal.open forces the log before it returns, so nothing redone below can reach the page file ahead of it.
            Wal opened = Wal.open(opener.open(logPath, false), groups::add);
            store.wal = opened;
            if (opened.baseLsn() > checkpoint) {
                throw new StorageFormatException("the log starts at LSN " + opened.baseLsn()
                        + ", after the last checkpoint at " + checkpoint + ": log records are missing");
            }
            int redone = store.redo(groups, checkpoint);
            if (redone > 0) {
                LOG.log(Level.INFO, "{0}: recovered {1} log groups", path, redone);
            }
            store.checkpoint();
            return store;
        } catch (IOException e) {
            closeAfterFailedOpen(store, file);
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            closeAfterFailedOpen(store, file);
            throw e;
        }
    }

    /**
     * Creates the page file under a temporary name, then the log, then renames the page file into place. The rename is
     * atomic, so the page file exists only once both are complete. A log with no page file is left from a creation
     * that crashed and is replaced; so is a temporary page file.
     */
    private static PageFile createFiles(Path path, int pageSize, ChannelOpener opener) {
        Path temporary = path.resolveSibling(path.getFileName() + ".creating");
        Path logPath = logPath(path);
        PageFile file = null;
        try {
            Files.deleteIfExists(temporary);
            Files.deleteIfExists(logPath);
            file = PageFile.create(temporary, pageSize, opener);
            Wal.create(opener.open(logPath, true), file.checkpointLsn()).close();
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE);
            return file; // the open channel follows the file through the rename
        } catch (IOException e) {
            if (file != null) {
                file.abandon();
            }
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            if (file != null) {
                file.abandon();
            }
            throw e;
        }
    }

    private static void closeAfterFailedOpen(PageStore store, PageFile file) {
        if (store != null && store.wal != null) {
            store.fail(new IllegalStateException("open failed"));
            store.close();
        } else {
            file.abandon();
        }
    }

    // Redoes every group that ends after the checkpoint. Returns how many.
    private int redo(List<Wal.Group> groups, long checkpoint) {
        int redone = 0;
        for (Wal.Group group : groups) {
            if (group.endLsn() <= checkpoint) {
                continue; // the checkpoint has it; the log reset after it just didn't happen
            }
            long lsn = group.endLsn();
            Set<Integer> skipped = new HashSet<>();
            Set<Integer> applied = new HashSet<>();
            for (LogRecord r : LogRecord.decode(group.body())) {
                if (r instanceof LogRecord.Meta meta) {
                    allocator.restore(meta);
                    if (Integer.compareUnsigned(meta.pageCount(), file.pageCount()) > 0) {
                        file.setPageCount(meta.pageCount());
                    }
                    continue;
                }
                LogRecord.PageRecord p = (LogRecord.PageRecord) r;
                if (skipped.contains(p.pageId())) {
                    continue;
                }
                // An image never reads the page, so a torn copy on disk doesn't matter.
                boolean image = p instanceof LogRecord.Image;
                try (PageGuard page = image ? pool.pinBlank(p.pageId()) : pool.fetch(p.pageId())) {
                    page.latchExclusive();
                    ByteBuffer buffer = page.buffer();
                    // Decided once per page per group: the first record stamps the LSN the rest would be compared to.
                    if (!applied.contains(p.pageId()) && !image && PageHeader.pageLsn(buffer) >= lsn) {
                        skipped.add(p.pageId());
                        continue;
                    }
                    applied.add(p.pageId());
                    p.apply(buffer);
                    PageHeader.setPageLsn(buffer, lsn);
                    page.markDirty();
                }
            }
            redone++;
        }
        return redone;
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

    /**
     * Writes every changed page and makes the page file the new starting point for recovery, then empties the log.
     * Waits for running mini-transactions and holds new ones back meanwhile. Not to be called inside one.
     */
    public void checkpoint() {
        changes.writeLock().lock();
        try {
            checkUsable();
            wal.flush();
            // Pages first, forced, and only then the meta page that says they're there.
            pool.flushAll();
            long end = wal.endLsn();
            file.setFreeListHead(allocator.freeHead);
            file.setCatalogRoot(allocator.catalogRoot);
            file.setCheckpointLsn(end);
            file.sync();
            wal.reset(end);
            redoStart = end;
        } catch (RuntimeException e) {
            fail(e);
            throw e;
        } finally {
            changes.writeLock().unlock();
        }
    }

    /** Checkpoints if the log has grown past the configured size. Call between operations, not inside one. */
    public void maybeCheckpoint() {
        if (wal.size() >= config.checkpointLogBytes()) {
            checkpoint();
        }
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

    /** Pages in the file, counting the meta page and free pages. */
    public int pageCount() {
        allocator.lock.lock();
        try {
            return allocator.pageCount;
        } finally {
            allocator.lock.unlock();
        }
    }

    /** Bytes in the log since the last checkpoint. */
    public long logSize() {
        return wal.size();
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
        Wal log = wal;
        if (log == null) {
            return; // recovery: the groups being redone were forced before redo began
        }
        try {
            log.flushTo(lsn);
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
        if (closed) {
            throw new IllegalStateException("store is closed");
        }
    }

    /** For tests: stops as a killed process would. Nothing more is written; the OS keeps what it already has. */
    void crash() {
        closed = true;
        fail(new IllegalStateException("crashed"));
        wal.abandon();
        file.abandon();
    }

    /** Checkpoints and closes. After a failure, writes nothing but closes the files. Safe to call twice. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        RuntimeException checkpointFailure = null;
        if (failure == null) {
            try {
                checkpoint();
            } catch (RuntimeException e) {
                checkpointFailure = e; // fail() ran, so the files are closed as after any failure
            }
        }
        closed = true;
        if (failure != null) {
            // Only the log is trusted now: write nothing else.
            try {
                if (wal != null) {
                    wal.close();
                }
            } finally {
                file.abandon();
            }
            if (checkpointFailure != null) {
                throw checkpointFailure;
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
}
