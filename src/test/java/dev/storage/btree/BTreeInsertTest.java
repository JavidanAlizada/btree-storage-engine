package dev.storage.btree;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.storage.buffer.LruReplacer;
import dev.storage.key.KeyEncoder;
import java.nio.charset.StandardCharsets;
import dev.storage.wal.Durability;
import dev.storage.wal.PageStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BTreeInsertTest {

    static final int PAGE = 4096;

    @TempDir
    Path dir;

    private PageStore store;

    @BeforeEach
    void open() {
        store = PageStore.open(dir.resolve("db"), new PageStore.Config(PAGE, 32, LruReplacer::new, Durability.ASYNC,
                Long.MAX_VALUE));
    }

    @AfterEach
    void close() {
        store.close();
    }

    static byte[] key(long n) {
        return KeyEncoder.create().addLong(n).encode();
    }

    static byte[] value(long n, int size) {
        byte[] v = new byte[size];
        byte[] tag = Long.toString(n).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(tag, 0, v, 0, Math.min(tag.length, size));
        return v;
    }

    @Test
    void anEmptyTreeHasNoKeys() {
        BTree tree = BTree.create(store);
        assertNull(tree.get(key(1)));
        assertEquals(new TreeChecker.Result(0, 1, 1), TreeChecker.check(tree));
    }

    @Test
    void ascendingInsertsSplitAllTheWayUpAndKeepTheRoot() {
        BTree tree = BTree.create(store);
        int root = tree.rootPage();
        for (long n = 0; n < 5_000; n++) {
            tree.put(key(n), value(n, 300)); // about 6 a leaf after half-full splits: ~800 leaves, so 3 levels
        }
        var result = TreeChecker.check(tree);
        assertEquals(5_000, result.keys());
        assertTrue(result.height() >= 3, "height " + result.height());
        assertEquals(root, tree.rootPage());
        for (long n = 0; n < 5_000; n++) {
            assertArrayEquals(value(n, 300), tree.get(key(n)), "key " + n);
        }
        assertNull(tree.get(key(5_000)));
    }

    @Test
    void randomInsertsWithLargeValuesStayBalanced() {
        BTree tree = BTree.create(store);
        List<Long> order = new ArrayList<>();
        for (long n = 0; n < 2_000; n++) {
            order.add(n);
        }
        Collections.shuffle(order, new Random(5));
        for (long n : order) {
            tree.put(key(n), value(n, 300)); // 3 entries a page: splits on nearly every few puts
        }
        assertEquals(2_000, TreeChecker.check(tree).keys());
        for (long n : order) {
            assertArrayEquals(value(n, 300), tree.get(key(n)));
        }
    }

    @Test
    void putReplacesAndALargerReplacementCanSplit() {
        BTree tree = BTree.create(store);
        for (long n = 0; n < 12; n++) {
            tree.put(key(n), value(n, 100));
        }
        tree.put(key(5), value(55, 900)); // too big to fit next to the others
        assertArrayEquals(value(55, 900), tree.get(key(5)));
        assertEquals(12, TreeChecker.check(tree).keys());
        tree.put(key(5), value(5, 1));
        assertArrayEquals(value(5, 1), tree.get(key(5)));
    }

    @Test
    void maximumSizeKeysAndEntriesWork() {
        BTree tree = BTree.create(store);
        int maxKey = BTree.maxKeySize(PAGE);
        int maxEntry = BTree.maxEntrySize(PAGE);
        var random = new Random(9);
        for (int n = 0; n < 200; n++) {
            byte[] k = new byte[maxKey];
            random.nextBytes(k);
            tree.put(k, new byte[maxEntry - maxKey]);
        }
        assertEquals(200, TreeChecker.check(tree).keys());
        assertThrows(IllegalArgumentException.class, () -> tree.put(new byte[maxKey + 1], new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> tree.put(new byte[10], new byte[maxEntry - 9]));
    }

    @Test
    void emptyKeysAndValuesAreOrdinaryEntries() {
        BTree tree = BTree.create(store);
        tree.put(new byte[0], new byte[0]);
        tree.put(new byte[] {0}, new byte[] {1});
        assertArrayEquals(new byte[0], tree.get(new byte[0]));
        assertArrayEquals(new byte[] {1}, tree.get(new byte[] {0}));
    }
}
