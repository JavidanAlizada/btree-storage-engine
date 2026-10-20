package dev.storage.wal;

/** When a change counts as done. */
public enum Durability {
    /** The change's log group is on disk before the call returns. Concurrent changes share one fsync. */
    SYNC,
    /**
     * The change is in the log's OS buffer when the call returns. A crash can lose the latest changes, always a suffix
     * of them and never half of one.
     */
    ASYNC
}
