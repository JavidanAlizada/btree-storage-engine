package dev.storage.buffer;

import dev.storage.file.PageFile;
import dev.storage.page.PageHeader;
import dev.storage.page.PageType;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A fixed number of in-memory frames caching the pages of one PageFile. The pool, not the OS, decides when a changed
 * page goes back to disk: when its frame is needed for another page, or on flush.
 *
 * One lock guards the page table, the free frames, pin counts and the replacer, and no I/O ever happens under it.
 * A page being loaded is in the table before the read starts, so a second fetcher waits for that read instead of
 * starting its own; a page has at most one frame. A dirty victim stays in the table, pinned, while it's written, so a
 * concurrent fetch finds the frame rather than reading the old version from disk.
 *
 * The frames live in direct memory allocated once, so the pool's size is fixed. Closing the pool flushes it but
 * leaves the PageFile open: whoever opened the file closes it.
 */
public final class BufferPool implements AutoCloseable {

    // Direct buffers are int-indexed; frames are carved out of arenas of up to 1 GiB.
    private static final int ARENA_BYTES = 1 << 30;

    private final PageFile file;
    private final int pageSize;
    private final Replacer replacer;
    private final LogFlusher flusher;
    private volatile Throwable failure;
    private final Frame[] frames;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<Integer, Frame> table = new HashMap<>(); // guarded by lock
    private final ArrayDeque<Frame> freeFrames = new ArrayDeque<>(); // guarded by lock

    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder evictions = new LongAdder();
    private final LongAdder writeBacks = new LongAdder();

    /** Counters since the pool was created. */
    public record Stats(long hits, long misses, long evictions, long writeBacks) { }

    public BufferPool(PageFile file, int frameCount, Replacer replacer) {
        this(file, frameCount, replacer, LogFlusher.NONE);
    }

    /** A pool that keeps the write-ahead rule: flusher runs before any page is written back. */
    public BufferPool(PageFile file, int frameCount, Replacer replacer, LogFlusher flusher) {
        if (frameCount < 1) {
            throw new IllegalArgumentException("frameCount must be >= 1: " + frameCount);
        }
        this.file = file;
        this.pageSize = file.pageSize();
        this.replacer = replacer;
        this.flusher = flusher;
        this.frames = new Frame[frameCount];
        int perArena = ARENA_BYTES / pageSize;
        ByteBuffer arena = null;
        for (int i = 0; i < frameCount; i++) {
            if (i % perArena == 0) {
                arena = ByteBuffer.allocateDirect(Math.min(perArena, frameCount - i) * pageSize);
            }
            frames[i] = new Frame(i, arena.slice((i % perArena) * pageSize, pageSize));
            freeFrames.add(frames[i]);
        }
    }

    public int frameCount() {
        return frames.length;
    }

    /** Pins page id, reading it from the file if it isn't cached. */
    public PageGuard fetch(int id) {
        if (id == 0) {
            throw new IllegalArgumentException("page 0 is the meta page; only PageFile reads it");
        }
        if (Integer.compareUnsigned(id, file.pageCount()) >= 0) {
            throw new IllegalArgumentException("page " + Integer.toUnsignedString(id) + " doesn't exist");
        }
        return pin(id, false);
    }

    /**
     * Pins page id without reading it: if it isn't cached, its frame starts as a blank FREE page. For a caller about to
     * overwrite the whole page, such as recovery restoring an image, where the copy on disk may be torn.
     */
    public PageGuard pinBlank(int id) {
        if (id == 0 || Integer.compareUnsigned(id, file.pageCount()) >= 0) {
            throw new IllegalArgumentException("page " + Integer.toUnsignedString(id) + " doesn't exist");
        }
        return pin(id, true);
    }

    /**
     * Stops all write-back for good: pages changed in memory without a log record must never reach the file. Used
     * when the layer above fails part-way through a change it can't undo.
     */
    public void fail(Throwable cause) {
        if (failure == null) {
            failure = cause;
        }
    }

    /** Allocates a page in the file and pins it. It holds an empty FREE page until the caller formats it. */
    public PageGuard newPage() {
        return pin(file.allocate(), true);
    }

    /**
     * Drops the page from the pool and returns it to the file's free list. The guard must be the page's only pin; it is
     * closed by this call.
     */
    public void free(PageGuard guard) {
        Frame frame = guard.frame();
        int id = guard.pageId();
        lock.lock();
        try {
            if (frame.pins != 1) {
                throw new IllegalStateException("page " + Integer.toUnsignedString(id) + " has " + frame.pins
                        + " pins; only the freeing caller may hold one");
            }
            guard.released();
            frame.dirty = false;
            unmapLocked(frame);
            frame.pins = 0;
            freeFrames.add(frame);
        } finally {
            lock.unlock();
        }
        file.free(id);
    }

    /** Writes page id if it's cached and dirty. Not durable until the file is synced. */
    public void flush(int id) {
        Frame frame;
        lock.lock();
        try {
            frame = table.get(id);
            if (frame == null || !frame.dirty || !frame.loading.isDone()) {
                return;
            }
            pinLocked(frame);
        } finally {
            lock.unlock();
        }
        try {
            writeBack(frame);
        } finally {
            unpin(frame);
        }
    }

    /** Writes every dirty page, then syncs the file. */
    public void flushAll() {
        List<Frame> dirty = new ArrayList<>();
        lock.lock();
        try {
            for (Frame frame : table.values()) {
                if (frame.dirty && frame.loading.isDone()) {
                    pinLocked(frame);
                    dirty.add(frame);
                }
            }
        } finally {
            lock.unlock();
        }
        RuntimeException failure = null;
        for (Frame frame : dirty) {
            try {
                writeBack(frame);
            } catch (RuntimeException e) {
                failure = failure == null ? e : failure;
            } finally {
                unpin(frame);
            }
        }
        if (failure != null) {
            throw failure;
        }
        file.sync();
    }

    public Stats stats() {
        return new Stats(hits.sum(), misses.sum(), evictions.sum(), writeBacks.sum());
    }

    /**
     * Flushes everything, then fails if any page is still pinned: a guard somewhere was never closed. After fail(),
     * writes nothing.
     */
    @Override
    public void close() {
        if (failure != null) {
            return; // nothing in memory may be written
        }
        flushAll();
        List<String> pinned = new ArrayList<>();
        lock.lock();
        try {
            for (Frame frame : frames) {
                if (frame.pins > 0) {
                    pinned.add(Integer.toUnsignedString(frame.pageId) + " (" + frame.pins + ")");
                }
            }
        } finally {
            lock.unlock();
        }
        if (!pinned.isEmpty()) {
            throw new IllegalStateException("pages still pinned at close, so a PageGuard was leaked: " + pinned);
        }
    }

    void unpin(Frame frame) {
        lock.lock();
        try {
            unpinLocked(frame);
        } finally {
            lock.unlock();
        }
    }

    private PageGuard pin(int id, boolean fresh) {
        while (true) {
            Frame frame;
            Frame dirtyVictim = null;
            CompletableFuture<Void> loading;
            lock.lock();
            try {
                frame = table.get(id);
                if (frame != null) {
                    pinLocked(frame);
                    replacer.recordAccess(frame.index);
                    hits.increment();
                    loading = frame.loading;
                } else {
                    frame = freeFrames.poll();
                    if (frame == null) {
                        frame = victimLocked();
                        if (frame.dirty) {
                            // Keep it mapped and pinned while it's written; a fetch of its page finds it meanwhile.
                            frame.pins = 1;
                            dirtyVictim = frame;
                        } else {
                            unmapLocked(frame);
                            evictions.increment();
                        }
                    }
                    if (dirtyVictim == null) {
                        mapLocked(frame, id);
                        misses.increment();
                        loading = null;
                    } else {
                        loading = null;
                        frame = null;
                    }
                }
            } finally {
                lock.unlock();
            }
            if (dirtyVictim != null) {
                cleanVictim(dirtyVictim);
                continue;
            }
            if (loading == null) {
                load(frame, id, fresh);
            } else {
                awaitLoad(frame, loading);
            }
            return new PageGuard(this, frame, id);
        }
    }

    // Writes a dirty victim, then frees its frame if nobody touched it in the meantime.
    private void cleanVictim(Frame victim) {
        try {
            writeBack(victim);
        } catch (RuntimeException e) {
            unpin(victim);
            throw e;
        }
        lock.lock();
        try {
            victim.pins--;
            if (victim.pins == 0 && !victim.dirty) {
                unmapLocked(victim);
                evictions.increment();
                freeFrames.add(victim);
            } else if (victim.pins == 0) {
                replacer.setEvictable(victim.index, true);
            }
        } finally {
            lock.unlock();
        }
    }

    // The thread that mapped the frame fills it; everyone else waits on frame.loading.
    private void load(Frame frame, int id, boolean fresh) {
        try {
            if (fresh) {
                PageHeader.format(frame.buffer, id, PageType.FREE);
            } else {
                file.read(id, frame.buffer);
            }
            frame.loading.complete(null);
        } catch (RuntimeException e) {
            lock.lock();
            try {
                if (table.get(id) == frame) {
                    unmapLocked(frame);
                }
                unpinLocked(frame);
            } finally {
                lock.unlock();
            }
            frame.loading.completeExceptionally(e);
            throw e;
        }
    }

    private void awaitLoad(Frame frame, CompletableFuture<Void> loading) {
        try {
            loading.join();
        } catch (CompletionException e) {
            unpin(frame);
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw e;
        }
    }

    // Copies the page out under its shared latch, so no change is half-done, and writes the copy with no latch held.
    private void writeBack(Frame frame) {
        ByteBuffer copy = ByteBuffer.allocate(pageSize);
        frame.latch.readLock().lock();
        try {
            if (!frame.dirty) {
                return;
            }
            frame.dirty = false;
            copy.put(0, frame.buffer, 0, pageSize);
        } finally {
            frame.latch.readLock().unlock();
        }
        try {
            if (failure != null) {
                throw new IllegalStateException("the pool stopped writing pages after a failure", failure);
            }
            flusher.flushTo(PageHeader.pageLsn(copy));
            file.write(frame.pageId, copy);
            writeBacks.increment();
        } catch (RuntimeException e) {
            frame.dirty = true;
            throw e;
        }
    }

    private Frame victimLocked() {
        int index = replacer.evict();
        if (index < 0) {
            throw new BufferPoolFullException("all " + frames.length + " frames are pinned");
        }
        return frames[index];
    }

    private void mapLocked(Frame frame, int id) {
        frame.mapped = true;
        frame.pageId = id;
        frame.pins = 1;
        frame.dirty = false;
        frame.loading = new CompletableFuture<>();
        table.put(id, frame);
        replacer.recordAccess(frame.index);
    }

    private void unmapLocked(Frame frame) {
        table.remove(frame.pageId);
        frame.mapped = false;
        replacer.remove(frame.index);
    }

    private void pinLocked(Frame frame) {
        if (frame.pins++ == 0) {
            replacer.setEvictable(frame.index, false);
        }
    }

    private void unpinLocked(Frame frame) {
        if (frame.pins <= 0) {
            throw new IllegalStateException("frame " + frame.index + " unpinned more often than pinned");
        }
        if (--frame.pins == 0) {
            if (frame.mapped) {
                replacer.setEvictable(frame.index, true);
            } else {
                freeFrames.add(frame);
            }
        }
    }
}
