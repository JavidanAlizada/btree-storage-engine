package dev.storage;

import dev.storage.page.PageHeader;

/**
 * How a database is opened. The page size only applies when the file is created; an existing file keeps its own.
 */
public record Options(int pageSize, int poolFrames, ReplacerPolicy replacer) {

    public enum ReplacerPolicy { LRU, CLOCK }

    public Options {
        if (!PageHeader.isValidPageSize(pageSize)) {
            throw new IllegalArgumentException("page size must be a power of two from " + PageHeader.MIN_PAGE_SIZE
                    + " to " + PageHeader.MAX_PAGE_SIZE + ": " + pageSize);
        }
        if (poolFrames < 8) {
            throw new IllegalArgumentException("the pool needs at least 8 frames: " + poolFrames);
        }
        if (replacer == null) {
            throw new IllegalArgumentException("replacer is null");
        }
    }

    /** 8 KiB pages, 1,024 frames (8 MiB), Clock. */
    public static Options defaults() {
        return new Options(8 * 1024, 1_024, ReplacerPolicy.CLOCK);
    }

    public Options withPageSize(int size) {
        return new Options(size, poolFrames, replacer);
    }

    public Options withPoolFrames(int frames) {
        return new Options(pageSize, frames, replacer);
    }

    public Options withReplacer(ReplacerPolicy policy) {
        return new Options(pageSize, poolFrames, policy);
    }
}
