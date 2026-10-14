package dev.storage.buffer;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** One slot of the pool. Mapping, pins and the load future are guarded by the pool lock; the bytes by the latch. */
final class Frame {

    final int index;
    final ByteBuffer buffer;
    final ReentrantReadWriteLock latch = new ReentrantReadWriteLock();

    // Guarded by the pool lock. pageId is stable while the frame is pinned.
    boolean mapped;
    int pageId;
    int pins;
    CompletableFuture<Void> loading;

    // Set under the exclusive latch, cleared under the shared latch when the page is copied out for writing.
    volatile boolean dirty;

    Frame(int index, ByteBuffer buffer) {
        this.index = index;
        this.buffer = buffer;
    }
}
