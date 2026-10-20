package dev.storage;

import dev.storage.btree.BTree;
import dev.storage.buffer.ClockReplacer;
import dev.storage.buffer.LruReplacer;
import dev.storage.buffer.Replacer;
import dev.storage.file.ChannelOpener;
import dev.storage.page.CorruptPageException;
import dev.storage.wal.Durability;
import dev.storage.wal.Mtr;
import dev.storage.wal.PageStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.IntFunction;

/**
 * One database file, its write-ahead log (the same name plus "-wal") and its indexes. The catalog, mapping index
 * names to root pages, is itself a B+Tree whose root is in the meta page.
 *
 * Every put, delete and createIndex is atomic, and with SYNC durability (the default) it's on disk when the call
 * returns. Opening a database after a crash recovers it from the log first.
 */
public final class Database implements AutoCloseable {

    private final PageStore store;
    private final BTree catalog;
    // One BTree object per index: its lock is what keeps writers apart, so there must never be two.
    private final Map<String, Index> indexes = new HashMap<>();
    private volatile boolean closed;

    private Database(PageStore store, BTree catalog) {
        this.store = store;
        this.catalog = catalog;
    }

    /** Opens the database at path, creating it if it's missing, and recovering it if it wasn't closed. */
    public static Database open(Path path, Options options) {
        return open(path, options, ChannelOpener.DEFAULT);
    }

    // Tests pass their own opener to model crashes.
    static Database open(Path path, Options options, ChannelOpener opener) {
        PageStore store = PageStore.open(path, config(options), opener);
        try {
            BTree catalog;
            if (store.catalogRoot() == 0) {
                try (Mtr mtr = store.begin()) {
                    catalog = BTree.create(store, mtr);
                    mtr.setCatalogRoot(catalog.rootPage());
                    mtr.commit();
                }
            } else {
                catalog = BTree.open(store, store.catalogRoot());
            }
            return new Database(store, catalog);
        } catch (RuntimeException e) {
            try {
                store.close();
            } catch (RuntimeException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }

    private static PageStore.Config config(Options options) {
        IntFunction<Replacer> replacer = switch (options.replacer()) {
            case LRU -> LruReplacer::new;
            case CLOCK -> ClockReplacer::new;
        };
        Durability durability = switch (options.durability()) {
            case SYNC -> Durability.SYNC;
            case ASYNC -> Durability.ASYNC;
        };
        return new PageStore.Config(options.pageSize(), options.poolFrames(), replacer, durability,
                options.checkpointLogBytes());
    }

    /** A new, empty index. Fails if one with this name exists. */
    public synchronized Index createIndex(String name) {
        checkOpen();
        byte[] key = nameKey(name);
        if (catalog.get(key) != null) {
            throw new IllegalArgumentException("index " + name + " already exists");
        }
        BTree tree;
        // The new root and its catalog entry commit together, so a crash can't leave one without the other.
        try (Mtr mtr = store.begin()) {
            tree = BTree.create(store, mtr);
            catalog.put(mtr, key, u32(tree.rootPage()));
            mtr.commit();
        }
        Index index = new Index(this, name, tree);
        indexes.put(name, index);
        afterWrite();
        return index;
    }

    /** The index called name. Fails if there's none. */
    public synchronized Index index(String name) {
        checkOpen();
        Index index = indexes.get(name);
        if (index != null) {
            return index;
        }
        byte[] root = catalog.get(nameKey(name));
        if (root == null) {
            throw new NoSuchElementException("no index called " + name);
        }
        if (root.length != 4) {
            throw new CorruptPageException("catalog entry for " + name + " is " + root.length + " bytes, not 4");
        }
        index = new Index(this, name, BTree.open(store, readU32(root)));
        indexes.put(name, index);
        return index;
    }

    /** The names of every index, in byte order of their UTF-8 encoding. */
    public synchronized List<String> indexNames() {
        checkOpen();
        List<String> names = new ArrayList<>();
        try (var c = catalog.scan(null, null)) {
            while (c.next()) {
                names.add(new String(c.key(), StandardCharsets.UTF_8));
            }
        }
        return names;
    }

    /**
     * Writes every changed page, records the file as the new starting point for recovery, and empties the log.
     * Changes wait meanwhile. It also happens on its own whenever the log passes Options.checkpointLogBytes.
     */
    public void checkpoint() {
        checkOpen();
        store.checkpoint();
    }

    /** Checkpoints and closes. Safe to call twice. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        store.close();
    }

    void checkOpen() {
        if (closed) {
            throw new IllegalStateException("database is closed");
        }
        store.checkUsable();
    }

    // Between operations, with no tree lock held: the only safe place for an automatic checkpoint.
    void afterWrite() {
        store.maybeCheckpoint();
    }

    private byte[] nameKey(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("index name is empty");
        }
        byte[] key = name.getBytes(StandardCharsets.UTF_8);
        int max = Math.min(BTree.maxKeySize(store.pageSize()), BTree.maxEntrySize(store.pageSize()) - 4);
        if (key.length > max) {
            throw new IllegalArgumentException("index name is " + key.length + " UTF-8 bytes; the limit is " + max);
        }
        return key;
    }

    private static byte[] u32(int v) {
        return new byte[] {(byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8), (byte) v};
    }

    private static int readU32(byte[] b) {
        return ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
    }
}
