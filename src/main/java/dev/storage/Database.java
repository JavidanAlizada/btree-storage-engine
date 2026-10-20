package dev.storage;

import dev.storage.btree.BTree;
import dev.storage.buffer.BufferPool;
import dev.storage.buffer.ClockReplacer;
import dev.storage.buffer.LruReplacer;
import dev.storage.buffer.Replacer;
import dev.storage.file.PageFile;
import dev.storage.page.CorruptPageException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * One database file and its indexes. The catalog, mapping index names to root pages, is itself a B+Tree whose root
 * is recorded in the meta page.
 *
 * Not crash-safe yet: changes reach the file when pages are evicted, at checkpoint() and at close(), and a crash in
 * between can leave the file inconsistent. The write-ahead log fixes that in M4.
 */
public final class Database implements AutoCloseable {

    private final PageFile file;
    private final BufferPool pool;
    private final BTree catalog;
    // One BTree object per index: its lock is what keeps writers apart, so there must never be two.
    private final Map<String, Index> indexes = new HashMap<>();
    private volatile boolean closed;

    private Database(PageFile file, BufferPool pool, BTree catalog) {
        this.file = file;
        this.pool = pool;
        this.catalog = catalog;
    }

    /** Opens the file at path, creating it if it's missing. */
    public static Database open(Path path, Options options) {
        PageFile file = Files.exists(path) ? PageFile.open(path) : PageFile.create(path, options.pageSize());
        BufferPool pool = null;
        try {
            pool = new BufferPool(file, options.poolFrames(), replacer(options));
            BTree catalog;
            if (file.catalogRoot() == 0) {
                catalog = BTree.create(pool, file.pageSize());
                file.setCatalogRoot(catalog.rootPage());
                pool.flushAll();
            } else {
                catalog = BTree.open(pool, file.catalogRoot(), file.pageSize());
            }
            return new Database(file, pool, catalog);
        } catch (RuntimeException e) {
            if (pool != null) {
                try {
                    pool.close();
                } catch (RuntimeException suppressed) {
                    e.addSuppressed(suppressed);
                }
            }
            file.close();
            throw e;
        }
    }

    private static Replacer replacer(Options options) {
        return switch (options.replacer()) {
            case LRU -> new LruReplacer(options.poolFrames());
            case CLOCK -> new ClockReplacer(options.poolFrames());
        };
    }

    /** A new, empty index. Fails if one with this name exists. */
    public synchronized Index createIndex(String name) {
        checkOpen();
        byte[] key = nameKey(name);
        if (catalog.get(key) != null) {
            throw new IllegalArgumentException("index " + name + " already exists");
        }
        BTree tree = BTree.create(pool, file.pageSize());
        catalog.put(key, u32(tree.rootPage()));
        Index index = new Index(this, name, tree);
        indexes.put(name, index);
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
        index = new Index(this, name, BTree.open(pool, readU32(root), file.pageSize()));
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

    /** Writes every changed page and syncs. Not atomic: a crash during it can still leave a mix. */
    public void checkpoint() {
        checkOpen();
        pool.flushAll();
    }

    /** Checkpoints and closes the file. Safe to call twice. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            pool.close();
        } finally {
            file.close();
        }
    }

    void checkOpen() {
        if (closed) {
            throw new IllegalStateException("database is closed");
        }
    }

    private byte[] nameKey(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("index name is empty");
        }
        byte[] key = name.getBytes(StandardCharsets.UTF_8);
        int max = Math.min(BTree.maxKeySize(file.pageSize()), BTree.maxEntrySize(file.pageSize()) - 4);
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
