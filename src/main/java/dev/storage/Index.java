package dev.storage;

import dev.storage.btree.BTree;
import java.util.Objects;

/**
 * A named, ordered map of unique byte keys to byte values. Keys compare as unsigned bytes; build typed or composite
 * ones with KeyEncoder so that order matches.
 */
public final class Index {

    private final Database db;
    private final String name;
    private final BTree tree;

    Index(Database db, String name, BTree tree) {
        this.db = db;
        this.name = name;
        this.tree = tree;
    }

    public String name() {
        return name;
    }

    /** The value stored under key, or null. */
    public byte[] get(byte[] key) {
        db.checkOpen();
        return tree.get(Objects.requireNonNull(key, "key"));
    }

    /** Stores value under key, replacing any value already there. */
    public void put(byte[] key, byte[] value) {
        db.checkOpen();
        tree.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
    }

    /** Removes key. Returns false if it wasn't there. */
    public boolean delete(byte[] key) {
        db.checkOpen();
        return tree.delete(Objects.requireNonNull(key, "key"));
    }

    /** The entries in [from, to) in key order; a null bound means unbounded. Close the cursor when done. */
    public Cursor scan(byte[] from, byte[] to) {
        db.checkOpen();
        return new Cursor(tree.scan(from, to));
    }
}
