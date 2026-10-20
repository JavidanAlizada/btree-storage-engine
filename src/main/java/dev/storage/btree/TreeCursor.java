package dev.storage.btree;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Walks the keys in [from, to) in order. Each batch is copied out of one leaf, and the next batch is found by
 * descending again from the root, so the cursor holds no pins or latches between calls. Not a snapshot: a key is
 * seen as it is when its leaf is reached.
 */
public final class TreeCursor implements AutoCloseable {

    private final BTree tree;
    private final byte[] to;
    private final List<byte[]> keys = new ArrayList<>();
    private final List<byte[]> values = new ArrayList<>();
    private byte[] seek;
    private int index = -1;
    private boolean last;
    private boolean closed;

    TreeCursor(BTree tree, byte[] from, byte[] to) {
        this.tree = tree;
        this.to = to;
        this.seek = from == null ? new byte[0] : from.clone();
        this.last = to != null && Arrays.compareUnsigned(seek, to) >= 0;
    }

    /** Moves to the next entry. Returns false once there are none left. */
    public boolean next() {
        if (closed) {
            throw new IllegalStateException("cursor is closed");
        }
        if (index + 1 < keys.size()) {
            index++;
            return true;
        }
        if (last) {
            index = keys.size();
            return false;
        }
        keys.clear();
        values.clear();
        last = !tree.loadBatch(seek, to, keys, values);
        if (keys.isEmpty()) {
            last = true;
            index = 0;
            return false;
        }
        seek = successor(keys.get(keys.size() - 1));
        index = 0;
        return true;
    }

    public byte[] key() {
        return keys.get(current());
    }

    public byte[] value() {
        return values.get(current());
    }

    @Override
    public void close() {
        closed = true;
        keys.clear();
        values.clear();
    }

    private int current() {
        if (closed || index < 0 || index >= keys.size()) {
            throw new IllegalStateException("no current entry");
        }
        return index;
    }

    // The smallest key after k in unsigned byte order.
    private static byte[] successor(byte[] k) {
        return Arrays.copyOf(k, k.length + 1);
    }
}
