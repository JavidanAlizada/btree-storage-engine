package dev.storage.buffer;

/**
 * Every frame is pinned, so there's nowhere to load another page. Either a guard was never closed (a pin leak) or
 * the pool is too small for the number of threads times the pages each one holds at once.
 */
public final class BufferPoolFullException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public BufferPoolFullException(String message) {
        super(message);
    }
}
