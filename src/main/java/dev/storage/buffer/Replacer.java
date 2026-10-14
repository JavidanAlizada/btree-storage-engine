package dev.storage.buffer;

/**
 * Chooses which frame to evict. Only frames marked evictable (pin count 0) can be chosen. The pool calls every method
 * under its lock, so implementations need no synchronization of their own.
 */
public interface Replacer {

    /** The frame's page was just fetched. */
    void recordAccess(int frame);

    /** Called when the frame's pin count goes 1 → 0 (true) or 0 → 1 (false). */
    void setEvictable(int frame, boolean evictable);

    /** Removes and returns a frame to evict, or -1 if no frame is evictable. */
    int evict();

    /** The frame no longer holds a page; forget it. */
    void remove(int frame);

    /** Frames that could be evicted right now. */
    int evictableCount();
}
