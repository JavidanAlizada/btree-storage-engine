package dev.storage.file;

import dev.storage.page.CorruptPageException;
import dev.storage.page.PageHeader;
import dev.storage.page.PageType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.System.Logger.Level;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.CRC32C;

/**
 * A file of fixed-size, checksummed pages. Page n lives at byte n × pageSize; page 0 is the meta page.
 *
 * Pages are checksummed when they cross the disk boundary: write() stamps CRC32C into the page, read() verifies it,
 * along with the page's own id, and treats an all-zero page as corrupt (extending the file always writes a formatted
 * page, so a valid page is never all zeros).
 *
 * Thread-safe. Reads and writes of different pages run concurrently through positional FileChannel calls; the caller
 * makes sure a page has one writer at a time. Allocation and the meta page share a lock.
 *
 * Not crash-safe yet: a crash between extending the file and writing the meta page leaks the new page. The
 * write-ahead log fixes that.
 */
public final class PageFile implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(PageFile.class.getName());

    private final FileChannel channel;
    private final int pageSize;
    private final ReentrantLock lock = new ReentrantLock();
    private final MetaPage meta; // guarded by lock
    private boolean metaDirty; // guarded by lock
    private boolean grew; // guarded by lock: the file got longer since the last sync
    private volatile int pageCount; // mirrors meta.pageCount for lock-free bounds checks
    private volatile Throwable failure;

    private PageFile(FileChannel channel, MetaPage meta) {
        this.channel = channel;
        this.pageSize = meta.pageSize;
        this.meta = meta;
        this.pageCount = meta.pageCount;
    }

    /** Creates a new file holding only the meta page. Fails if path exists. */
    public static PageFile create(Path path, int pageSize) {
        if (!PageHeader.isValidPageSize(pageSize)) {
            throw new IllegalArgumentException("page size must be a power of two from " + PageHeader.MIN_PAGE_SIZE
                    + " to " + PageHeader.MAX_PAGE_SIZE + ": " + pageSize);
        }
        return create(openChannel(path, StandardOpenOption.CREATE_NEW), pageSize);
    }

    /** As create(path, pageSize), opening the file through opener. */
    public static PageFile create(Path path, int pageSize, ChannelOpener opener) {
        if (!PageHeader.isValidPageSize(pageSize)) {
            throw new IllegalArgumentException("page size must be a power of two from " + PageHeader.MIN_PAGE_SIZE
                    + " to " + PageHeader.MAX_PAGE_SIZE + ": " + pageSize);
        }
        try {
            return create(opener.open(path, true), pageSize);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // The channel is a seam for tests that inject I/O faults.
    static PageFile create(FileChannel channel, int pageSize) {
        var file = new PageFile(channel, new MetaPage(pageSize, 1, 0, 0, 0));
        file.lock.lock();
        try {
            file.metaDirty = true;
            file.grew = true;
            file.syncLocked();
        } catch (RuntimeException e) {
            closeQuietly(channel);
            throw e;
        } finally {
            file.lock.unlock();
        }
        return file;
    }

    /** Opens an existing file, checking its format and meta page. */
    public static PageFile open(Path path) {
        return open(openChannel(path), path);
    }

    /** As open(path), opening the file through opener. */
    public static PageFile open(Path path, ChannelOpener opener) {
        try {
            return open(opener.open(path, false), path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static PageFile open(FileChannel channel, Path path) {
        try {
            ByteBuffer prefix = ByteBuffer.allocate(MetaPage.PREFIX_SIZE);
            readFully(channel, prefix, 0, "the file header");
            int pageSize = MetaPage.readPageSize(prefix);
            ByteBuffer page = ByteBuffer.allocate(pageSize);
            readFully(channel, page, 0, "page 0");
            verify(page, 0);
            MetaPage meta = MetaPage.read(page);
            long expected = Integer.toUnsignedLong(meta.pageCount) * pageSize;
            long size = channel.size();
            if (size < expected) {
                throw new CorruptPageException("file is " + size + " bytes but the meta page counts "
                        + Integer.toUnsignedString(meta.pageCount) + " pages of " + pageSize);
            }
            if (size > expected) {
                LOG.log(Level.WARNING, "{0}: {1} bytes past the last counted page, left over from a crash",
                        path, size - expected);
            }
            return new PageFile(channel, meta);
        } catch (IOException e) {
            closeQuietly(channel);
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            closeQuietly(channel);
            throw e;
        }
    }

    public int pageSize() {
        return pageSize;
    }

    /** Pages in the file, the meta page and free pages included. Unsigned. */
    public int pageCount() {
        return pageCount;
    }

    /** The root page of the catalog tree, or 0 if there's no catalog yet. */
    public int catalogRoot() {
        lock.lock();
        try {
            return meta.catalogRoot;
        } finally {
            lock.unlock();
        }
    }

    /** Records the catalog's root. It reaches disk with the next sync. */
    public void setCatalogRoot(int root) {
        checkUsable();
        lock.lock();
        try {
            meta.catalogRoot = root;
            metaDirty = true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Raises the page count to count without writing anything: the new pages reach the file when they're first
     * written. For an allocator that keeps the free list itself (the write-ahead log's); the meta page records the
     * count at the next sync.
     */
    public void setPageCount(int count) {
        checkUsable();
        lock.lock();
        try {
            if (Integer.compareUnsigned(count, meta.pageCount) < 0) {
                throw new IllegalArgumentException("page count can only grow: " + Integer.toUnsignedString(count));
            }
            meta.pageCount = count;
            pageCount = count;
            metaDirty = true;
            grew = true;
        } finally {
            lock.unlock();
        }
    }

    public int freeListHead() {
        lock.lock();
        try {
            return meta.freeListHead;
        } finally {
            lock.unlock();
        }
    }

    /** Records the free list's head for the next sync, for an allocator that keeps the list itself. */
    public void setFreeListHead(int head) {
        checkUsable();
        lock.lock();
        try {
            meta.freeListHead = head;
            metaDirty = true;
        } finally {
            lock.unlock();
        }
    }

    /** The log position the last checkpoint covers: recovery redoes everything after it. */
    public long checkpointLsn() {
        lock.lock();
        try {
            return meta.checkpointLsn;
        } finally {
            lock.unlock();
        }
    }

    public void setCheckpointLsn(long lsn) {
        checkUsable();
        lock.lock();
        try {
            meta.checkpointLsn = lsn;
            metaDirty = true;
        } finally {
            lock.unlock();
        }
    }

    /** Reads page id into dst (capacity = page size) and verifies its checksum and id. */
    public void read(int id, ByteBuffer dst) {
        checkUsable();
        checkPage(id, dst);
        try {
            readFully(channel, dst, offset(id), "page " + Integer.toUnsignedString(id));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        verify(dst, id);
    }

    /**
     * Stamps the checksum into src and writes it as page id. src must hold page id (its header's page id is checked).
     * Not durable until sync().
     */
    public void write(int id, ByteBuffer src) {
        checkUsable();
        checkPage(id, src);
        if (id == 0) {
            throw new IllegalArgumentException("page 0 is the meta page and is written only by PageFile");
        }
        writePage(id, src);
    }

    /**
     * A page for the caller to format and write: the most recently freed page if there is one, otherwise a new page
     * at the end of the file. Either way it reads back as a FREE page until written.
     */
    public int allocate() {
        checkUsable();
        lock.lock();
        try {
            if (meta.freeListHead != 0) {
                return popFree();
            }
            int id = meta.pageCount;
            if (id == -1) {
                throw new IllegalStateException("the file has the maximum of 2^32 - 1 pages");
            }
            ByteBuffer page = ByteBuffer.allocate(pageSize);
            PageHeader.format(page, id, PageType.FREE);
            writePage(id, page);
            meta.pageCount = id + 1;
            pageCount = meta.pageCount;
            metaDirty = true;
            grew = true;
            return id;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns page id to the free list, overwriting it as a FREE page that links to the previous head. The caller must
     * not free a page twice or keep using it: neither is detected, and a double free makes the list a cycle.
     */
    public void free(int id) {
        checkUsable();
        if (id == 0 || Integer.compareUnsigned(id, pageCount) >= 0) {
            throw new IllegalArgumentException("can't free page " + Integer.toUnsignedString(id));
        }
        lock.lock();
        try {
            ByteBuffer page = ByteBuffer.allocate(pageSize);
            PageHeader.format(page, id, PageType.FREE);
            PageHeader.setAux(page, meta.freeListHead);
            writePage(id, page);
            meta.freeListHead = id;
            metaDirty = true;
        } finally {
            lock.unlock();
        }
    }

    // The head of the free list, read to learn the next one. Called with the lock held.
    private int popFree() {
        int id = meta.freeListHead;
        String which = "free-list page " + Integer.toUnsignedString(id);
        if (Integer.compareUnsigned(id, meta.pageCount) >= 0) {
            throw new CorruptPageException(which + " is past the end of the file");
        }
        ByteBuffer page = ByteBuffer.allocate(pageSize);
        read(id, page);
        if (PageHeader.type(page) != PageType.FREE) {
            throw new CorruptPageException(which + " is a " + PageHeader.type(page) + " page, not a free one");
        }
        int next = PageHeader.aux(page);
        if (next == id || Integer.compareUnsigned(next, meta.pageCount) >= 0) {
            throw new CorruptPageException(which + " links to page " + Integer.toUnsignedString(next));
        }
        meta.freeListHead = next;
        metaDirty = true;
        return id;
    }

    /** Writes the meta page if it changed, then forces the file to disk. An fsync failure is final. */
    public void sync() {
        checkUsable();
        lock.lock();
        try {
            syncLocked();
        } finally {
            lock.unlock();
        }
    }

    /** Closes without writing or syncing anything, for a caller whose in-memory state must not reach the file. */
    public void abandon() {
        closeQuietly(channel);
    }

    /** Syncs, then closes. Safe to call twice, and after a failure (it then only closes). */
    @Override
    public void close() {
        if (!channel.isOpen()) {
            return;
        }
        try {
            if (failure == null) {
                sync();
            }
        } finally {
            closeQuietly(channel);
        }
    }

    private void syncLocked() {
        if (metaDirty) {
            ByteBuffer page = ByteBuffer.allocate(pageSize);
            meta.write(page);
            writePage(0, page);
            metaDirty = false;
        }
        try {
            // A longer file means its size changed, and the size is metadata.
            channel.force(grew);
            grew = false;
        } catch (IOException | RuntimeException e) {
            failure = e;
            throw new StorageFailedException("fsync failed; the file must be closed and reopened", e);
        }
    }

    private void writePage(int id, ByteBuffer page) {
        PageHeader.setChecksum(page, checksum(page));
        ByteBuffer src = page.duplicate().clear();
        long position = offset(id);
        try {
            while (src.hasRemaining()) {
                position += channel.write(src, position);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void verify(ByteBuffer page, int id) {
        String which = "page " + Integer.toUnsignedString(id);
        if (isAllZero(page)) {
            throw new CorruptPageException(which + " is all zeros: it was never written completely");
        }
        int stored = PageHeader.checksum(page);
        int actual = checksum(page);
        if (stored != actual) {
            throw new CorruptPageException(which + " fails its checksum (stored " + Integer.toHexString(stored)
                    + ", computed " + Integer.toHexString(actual) + ")");
        }
        if (PageHeader.pageId(page) != id) {
            throw new CorruptPageException(which + " holds page " + Integer.toUnsignedString(PageHeader.pageId(page))
                    + ": a misdirected write or read");
        }
    }

    // CRC32C over everything after the checksum field.
    private static int checksum(ByteBuffer page) {
        var crc = new CRC32C();
        crc.update(page.slice(4, page.capacity() - 4));
        return (int) crc.getValue();
    }

    private static boolean isAllZero(ByteBuffer page) {
        for (int i = 0; i < page.capacity(); i += 8) {
            if (page.getLong(i) != 0) {
                return false;
            }
        }
        return true;
    }

    private void checkPage(int id, ByteBuffer buffer) {
        if (buffer.capacity() != pageSize) {
            throw new IllegalArgumentException("buffer of " + buffer.capacity() + " bytes for " + pageSize
                    + "-byte pages");
        }
        if (Integer.compareUnsigned(id, pageCount) >= 0) {
            throw new IllegalArgumentException("page " + Integer.toUnsignedString(id) + " doesn't exist; the file has "
                    + Integer.toUnsignedString(pageCount) + " pages");
        }
    }

    private void checkUsable() {
        Throwable f = failure;
        if (f != null) {
            throw new StorageFailedException("an earlier fsync failed; close and reopen the file", f);
        }
    }

    private long offset(int id) {
        return Integer.toUnsignedLong(id) * pageSize;
    }

    private static void readFully(FileChannel channel, ByteBuffer dst, long position, String what)
            throws IOException {
        ByteBuffer target = dst.duplicate().clear();
        long at = position;
        while (target.hasRemaining()) {
            int n = channel.read(target, at);
            if (n < 0) {
                throw new CorruptPageException(what + " is cut short: the file ends at " + at);
            }
            at += n;
        }
    }

    private static FileChannel openChannel(Path path, StandardOpenOption... extra) {
        try {
            if (extra.length == 0) {
                return FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
            }
            return FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE, extra[0]);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void closeQuietly(FileChannel channel) {
        try {
            channel.close();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "closing the page file failed", e);
        }
    }
}
