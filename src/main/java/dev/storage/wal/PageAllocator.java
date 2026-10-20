package dev.storage.wal;

import java.util.concurrent.locks.ReentrantLock;

/**
 * The page count, free-list head and catalog root, kept in memory and changed only inside a mini-transaction, which
 * logs them in a META record. The meta page records them at checkpoints only.
 *
 * A mini-transaction takes the lock at its first allocation or free and keeps it until it commits, so the order of
 * META records in the log is the order the changes were made in. Without that, two trees allocating at once could
 * commit out of order, and redo would end with the wrong free-list head.
 */
final class PageAllocator {

    final ReentrantLock lock = new ReentrantLock();
    // Guarded by lock.
    int pageCount;
    int freeHead;
    int catalogRoot;

    PageAllocator(int pageCount, int freeHead, int catalogRoot) {
        this.pageCount = pageCount;
        this.freeHead = freeHead;
        this.catalogRoot = catalogRoot;
    }

    LogRecord.Meta state() {
        return new LogRecord.Meta(pageCount, freeHead, catalogRoot);
    }

    void restore(LogRecord.Meta meta) {
        pageCount = meta.pageCount();
        freeHead = meta.freeHead();
        catalogRoot = meta.catalogRoot();
    }
}
