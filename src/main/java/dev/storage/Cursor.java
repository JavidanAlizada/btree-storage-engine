package dev.storage;

import dev.storage.btree.TreeCursor;

/**
 * Entries of a range in key order. Holds no pages between calls, so it's cheap to keep open, but it isn't a
 * snapshot: an entry is read as it is when the cursor reaches it.
 */
public final class Cursor implements AutoCloseable {

    private final TreeCursor cursor;

    Cursor(TreeCursor cursor) {
        this.cursor = cursor;
    }

    /** Moves to the next entry. Returns false once there are none left. */
    public boolean next() {
        return cursor.next();
    }

    public byte[] key() {
        return cursor.key();
    }

    public byte[] value() {
        return cursor.value();
    }

    @Override
    public void close() {
        cursor.close();
    }
}
