package dev.storage.buffer;

/**
 * The write-ahead rule, as the pool sees it: before a page is written back, the log must be durable up to the page's
 * LSN. The pool calls this with that LSN; the log decides whether it needs an fsync.
 */
@FunctionalInterface
public interface LogFlusher {

    /** For a pool with no log: nothing to wait for. */
    LogFlusher NONE = lsn -> { };

    void flushTo(long lsn);
}
