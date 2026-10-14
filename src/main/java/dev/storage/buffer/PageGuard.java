package dev.storage.buffer;

import java.nio.ByteBuffer;

/**
 * A pin on one cached page. While it's open the page stays in its frame. The bytes are only safe to read under a
 * latch: shared to read, exclusive to change. close() releases the latch, if any, and the pin.
 *
 * A guard belongs to the thread that got it, like the latch it holds.
 */
public final class PageGuard implements AutoCloseable {

    private enum Latch { NONE, SHARED, EXCLUSIVE }

    private final BufferPool pool;
    private final Frame frame;
    private final int pageId;
    private Latch latch = Latch.NONE;
    private boolean closed;

    PageGuard(BufferPool pool, Frame frame, int pageId) {
        this.pool = pool;
        this.frame = frame;
        this.pageId = pageId;
    }

    public int pageId() {
        return pageId;
    }

    /** The page's bytes, in the pool's memory. Use absolute gets and puts, and only while latched. */
    public ByteBuffer buffer() {
        checkOpen();
        if (latch == Latch.NONE) {
            throw new IllegalStateException("latch page " + Integer.toUnsignedString(pageId) + " before touching it");
        }
        return frame.buffer;
    }

    public void latchShared() {
        checkUnlatched();
        frame.latch.readLock().lock();
        latch = Latch.SHARED;
    }

    public void latchExclusive() {
        checkUnlatched();
        frame.latch.writeLock().lock();
        latch = Latch.EXCLUSIVE;
    }

    public boolean isLatchedExclusive() {
        return latch == Latch.EXCLUSIVE;
    }

    public void unlatch() {
        checkOpen();
        switch (latch) {
            case SHARED -> frame.latch.readLock().unlock();
            case EXCLUSIVE -> frame.latch.writeLock().unlock();
            case NONE -> throw new IllegalStateException("page " + Integer.toUnsignedString(pageId) + " isn't latched");
        }
        latch = Latch.NONE;
    }

    /** Records that the bytes changed, so the page is written before its frame is reused. Needs the exclusive latch. */
    public void markDirty() {
        checkOpen();
        if (latch != Latch.EXCLUSIVE) {
            throw new IllegalStateException("changing page " + Integer.toUnsignedString(pageId)
                    + " needs the exclusive latch");
        }
        frame.dirty = true;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        if (latch != Latch.NONE) {
            unlatch();
        }
        closed = true;
        pool.unpin(frame);
    }

    Frame frame() {
        return frame;
    }

    // free() takes the pin over: the guard must not unpin again.
    void released() {
        if (latch != Latch.NONE) {
            unlatch();
        }
        closed = true;
    }

    private void checkUnlatched() {
        checkOpen();
        if (latch != Latch.NONE) {
            throw new IllegalStateException("page " + Integer.toUnsignedString(pageId) + " is already latched");
        }
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("guard for page " + Integer.toUnsignedString(pageId) + " is closed");
        }
    }
}
