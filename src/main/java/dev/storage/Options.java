package dev.storage;

import dev.storage.page.PageHeader;

/**
 * How a database is opened. The page size only applies when the file is created; an existing file keeps its own.
 */
public record Options(int pageSize, int poolFrames, ReplacerPolicy replacer, Durability durability,
        long checkpointLogBytes) {

    public enum ReplacerPolicy { LRU, CLOCK }

    /** When a put or delete counts as done. */
    public enum Durability {
        /** On disk before the call returns. Concurrent writers share fsyncs (group commit). */
        SYNC,
        /**
         * In the log's OS buffer when the call returns. A power loss can lose the latest changes: always a suffix
         * of them, never half of one. A killed process loses nothing.
         */
        ASYNC
    }

    public Options {
        if (!PageHeader.isValidPageSize(pageSize)) {
            throw new IllegalArgumentException("page size must be a power of two from " + PageHeader.MIN_PAGE_SIZE
                    + " to " + PageHeader.MAX_PAGE_SIZE + ": " + pageSize);
        }
        // A change holds its pages until it commits: about three per tree level for a merge.
        if (poolFrames < 16) {
            throw new IllegalArgumentException("the pool needs at least 16 frames: " + poolFrames);
        }
        if (replacer == null || durability == null) {
            throw new IllegalArgumentException("replacer and durability are required");
        }
        if (checkpointLogBytes < 1) {
            throw new IllegalArgumentException("checkpointLogBytes must be positive: " + checkpointLogBytes);
        }
    }

    /** 8 KiB pages, 1,024 frames (8 MiB), Clock, SYNC, a checkpoint every 64 MiB of log. */
    public static Options defaults() {
        return new Options(8 * 1024, 1_024, ReplacerPolicy.CLOCK, Durability.SYNC, 64L << 20);
    }

    public Options withPageSize(int size) {
        return new Options(size, poolFrames, replacer, durability, checkpointLogBytes);
    }

    public Options withPoolFrames(int frames) {
        return new Options(pageSize, frames, replacer, durability, checkpointLogBytes);
    }

    public Options withReplacer(ReplacerPolicy policy) {
        return new Options(pageSize, poolFrames, policy, durability, checkpointLogBytes);
    }

    public Options withDurability(Durability mode) {
        return new Options(pageSize, poolFrames, replacer, mode, checkpointLogBytes);
    }

    public Options withCheckpointLogBytes(long bytes) {
        return new Options(pageSize, poolFrames, replacer, durability, bytes);
    }
}
