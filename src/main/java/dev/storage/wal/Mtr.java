package dev.storage.wal;

import dev.storage.buffer.PageGuard;
import dev.storage.page.CorruptPageException;
import dev.storage.page.PageHeader;
import dev.storage.page.PageType;
import dev.storage.page.SlottedPage;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A mini-transaction: a set of page changes that reach the log as one group, so recovery redoes all of them or none.
 * Every change to a logged page goes through one, and a page it changed stays latched exclusively until commit, so
 * no eviction can write a page whose log record doesn't exist yet.
 *
 * <pre>
 * try (Mtr mtr = store.begin()) {
 *     try (PageGuard leaf = mtr.fetchExclusive(id)) {   // the caller's guard; the mtr keeps its own latch
 *         mtr.insert(leaf, slot, cell);
 *     }
 *     mtr.commit();
 * }
 * </pre>
 *
 * There's no undo: if anything fails before commit, or the mtr is closed uncommitted after changing a page, the
 * store fails for good, because pages in memory now differ from the log. Reopening recovers.
 */
public final class Mtr implements AutoCloseable {

    // Test builds turn this on: every commit replays its own group the way recovery would and compares the bytes.
    static final boolean REDO_CHECK = Boolean.getBoolean("dev.storage.redoCheck");

    private static final class Entry {
        final PageGuard held;
        final long beforeLsn;
        final ByteBuffer before; // REDO_CHECK only
        final List<LogRecord.PageRecord> deltas = new ArrayList<>();
        int deltaBytes;
        boolean image;
        boolean changed;

        Entry(PageGuard held, long beforeLsn, ByteBuffer before) {
            this.held = held;
            this.beforeLsn = beforeLsn;
            this.before = before;
        }
    }

    private final PageStore store;
    private final Map<Integer, Entry> pages = new LinkedHashMap<>();
    private boolean holdsAllocator;
    private boolean metaChanged;
    private boolean done;

    Mtr(PageStore store) {
        this.store = store;
    }

    /** The page, pinned and latched exclusively for the caller; the mtr keeps its own latch on it until commit. */
    public PageGuard fetchExclusive(int id) {
        checkActive();
        track(id, false);
        return callerGuard(id);
    }

    /**
     * A page for a new node: the free list's head if there is one, else a new page at the end of the file. Its
     * contents are undefined; the caller formats it and calls rewritten.
     */
    public PageGuard allocate() {
        checkActive();
        PageAllocator a = lockAllocator();
        int id;
        if (a.freeHead != 0) {
            id = a.freeHead;
            Entry e = track(id, false);
            ByteBuffer page = e.held.buffer();
            if (PageHeader.type(page) != PageType.FREE) {
                throw new CorruptPageException("free-list page " + Integer.toUnsignedString(id) + " is a "
                        + PageHeader.type(page) + " page");
            }
            a.freeHead = PageHeader.aux(page);
        } else {
            id = a.pageCount;
            if (id == -1) {
                throw new IllegalStateException("the file has the maximum of 2^32 - 1 pages");
            }
            a.pageCount = id + 1;
            store.file().setPageCount(a.pageCount);
            track(id, true);
        }
        metaChanged = true;
        Entry e = pages.get(id);
        e.image = true;
        e.changed = true;
        return callerGuard(id);
    }

    /** Returns the page to the free list. The caller must not use it again. */
    public void free(PageGuard page) {
        Entry e = entry(page);
        PageAllocator a = lockAllocator();
        ByteBuffer buffer = page.buffer();
        PageHeader.format(buffer, page.pageId(), PageType.FREE);
        PageHeader.setAux(buffer, a.freeHead);
        a.freeHead = page.pageId();
        metaChanged = true;
        e.image = true;
        e.changed = true;
    }

    public void setCatalogRoot(int root) {
        checkActive();
        PageAllocator a = lockAllocator();
        a.catalogRoot = root;
        metaChanged = true;
    }

    /** Inserts cell at slot, as SlottedPage.insert does. Returns false, changing and logging nothing, if it can't. */
    public boolean insert(PageGuard page, int slot, byte[] cell) {
        Entry e = entry(page);
        if (!SlottedPage.wrap(page.buffer()).insert(slot, cell)) {
            return false;
        }
        add(e, new LogRecord.Insert(page.pageId(), slot, cell.clone()));
        return true;
    }

    public void delete(PageGuard page, int slot) {
        Entry e = entry(page);
        SlottedPage.wrap(page.buffer()).delete(slot);
        add(e, new LogRecord.Delete(page.pageId(), slot));
    }

    public void setAux(PageGuard page, int value) {
        Entry e = entry(page);
        PageHeader.setAux(page.buffer(), value);
        add(e, new LogRecord.SetAux(page.pageId(), value));
    }

    /** The caller rewrote the whole page directly: it's logged as an image. */
    public void rewritten(PageGuard page) {
        Entry e = entry(page);
        e.image = true;
        e.changed = true;
    }

    /**
     * Logs every change as one group, stamps its end LSN into the pages, and releases them. With SYNC durability,
     * returns once the group is on disk. Returns the group's end LSN, or -1 if nothing changed.
     */
    public long commit() {
        checkActive();
        long lsn = -1;
        try {
            List<LogRecord> records = new ArrayList<>();
            if (metaChanged) {
                records.add(store.allocator().state());
            }
            long redoStart = store.redoStart();
            for (Entry e : pages.values()) {
                if (!e.changed) {
                    continue;
                }
                ByteBuffer page = e.held.buffer();
                // An image on the first change since the checkpoint, so a page torn on disk can be rebuilt.
                if (e.image || e.beforeLsn <= redoStart || e.deltaBytes >= LogRecord.Image.sizeOf(page)) {
                    LogRecord.Image.zeroGap(page);
                    records.add(LogRecord.Image.of(e.held.pageId(), page));
                } else {
                    records.addAll(e.deltas);
                }
            }
            if (!records.isEmpty()) {
                ByteBuffer body = LogRecord.encode(records);
                lsn = store.wal().append(body);
                for (Entry e : pages.values()) {
                    if (e.changed) {
                        PageHeader.setPageLsn(e.held.buffer(), lsn);
                        e.held.markDirty();
                    }
                }
                if (REDO_CHECK) {
                    checkRedo(body, lsn);
                }
            }
        } catch (RuntimeException | Error e) {
            store.fail(e);
            throw e;
        } finally {
            release();
        }
        if (lsn >= 0 && store.durability() == Durability.SYNC) {
            store.flushTo(lsn);
        }
        return lsn;
    }

    /** Releases everything. If a page changed and commit didn't run, the store fails: memory and log now differ. */
    @Override
    public void close() {
        if (done) {
            return;
        }
        boolean changed = metaChanged || pages.values().stream().anyMatch(e -> e.changed);
        if (changed) {
            store.fail(new IllegalStateException("a mini-transaction ended without committing its changes"));
        }
        release();
    }

    private Entry track(int id, boolean blank) {
        Entry e = pages.get(id);
        if (e != null) {
            return e;
        }
        PageGuard held = blank ? store.pool().pinBlank(id) : store.pool().fetch(id);
        try {
            held.latchExclusive();
            ByteBuffer page = held.buffer();
            ByteBuffer before = null;
            if (REDO_CHECK) {
                before = ByteBuffer.allocate(page.capacity()).put(0, page, 0, page.capacity());
            }
            e = new Entry(held, blank ? 0 : PageHeader.pageLsn(page), before);
        } catch (RuntimeException ex) {
            held.close();
            throw ex;
        }
        pages.put(id, e);
        return e;
    }

    // A second pin and a reentrant exclusive latch, for the caller to close as it likes.
    private PageGuard callerGuard(int id) {
        PageGuard guard = store.pool().fetch(id);
        guard.latchExclusive();
        return guard;
    }

    private Entry entry(PageGuard page) {
        checkActive();
        Entry e = pages.get(page.pageId());
        if (e == null || !page.isLatchedExclusive()) {
            throw new IllegalStateException("page " + Integer.toUnsignedString(page.pageId())
                    + " wasn't fetched through this mini-transaction");
        }
        return e;
    }

    private void add(Entry e, LogRecord.PageRecord record) {
        e.deltas.add(record);
        e.deltaBytes += record.size();
        e.changed = true;
    }

    private PageAllocator lockAllocator() {
        PageAllocator a = store.allocator();
        if (!holdsAllocator) {
            a.lock.lock();
            holdsAllocator = true;
        }
        return a;
    }

    private void release() {
        done = true;
        List<Entry> entries = new ArrayList<>(pages.values());
        for (int i = entries.size() - 1; i >= 0; i--) {
            entries.get(i).held.close();
        }
        if (holdsAllocator) {
            store.allocator().lock.unlock();
            holdsAllocator = false;
        }
        store.endMtr();
    }

    private void checkActive() {
        if (done) {
            throw new IllegalStateException("mini-transaction already ended");
        }
    }

    // Replays the encoded group on copies of the pages as they were, exactly as recovery would, and compares.
    private void checkRedo(ByteBuffer body, long lsn) {
        Map<Integer, ByteBuffer> rebuilt = new HashMap<>();
        for (LogRecord r : LogRecord.decode(body)) {
            if (r instanceof LogRecord.PageRecord p) {
                ByteBuffer page = rebuilt.computeIfAbsent(p.pageId(), id -> {
                    ByteBuffer copy = ByteBuffer.allocate(store.pageSize());
                    copy.put(0, pages.get(id).before, 0, copy.capacity());
                    return copy;
                });
                p.apply(page);
            }
        }
        for (Map.Entry<Integer, ByteBuffer> r : rebuilt.entrySet()) {
            ByteBuffer replayed = r.getValue();
            PageHeader.setPageLsn(replayed, lsn);
            ByteBuffer live = pages.get(r.getKey()).held.buffer();
            if (replayed.slice(4, replayed.capacity() - 4).mismatch(live.slice(4, live.capacity() - 4)) != -1) {
                throw new IllegalStateException("redo of page " + Integer.toUnsignedString(r.getKey())
                        + " doesn't rebuild the page as it is in memory");
            }
        }
    }
}
