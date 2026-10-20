package dev.storage.btree;

import static dev.storage.btree.BTreeInsertTest.key;
import static dev.storage.btree.BTreeInsertTest.value;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.storage.buffer.BufferPool;
import dev.storage.buffer.ClockReplacer;
import dev.storage.file.PageFile;
import dev.storage.key.KeyDecoder;
import dev.storage.key.KeyEncoder;
import dev.storage.key.Keys;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BTreeCursorTest {

    private static final int PAGE = BTreeInsertTest.PAGE;

    @TempDir
    Path dir;

    private PageFile file;
    private BufferPool pool;

    @BeforeEach
    void open() {
        file = PageFile.create(dir.resolve("db"), PAGE);
        pool = new BufferPool(file, 32, new ClockReplacer(32));
    }

    @AfterEach
    void close() {
        pool.close();
        file.close();
    }

    // Keys 0, 2, 4, ... so bounds can fall between keys.
    private BTree evenKeys(int count) {
        BTree tree = BTree.create(pool, PAGE);
        for (long n = 0; n < count; n++) {
            tree.put(key(2 * n), value(2 * n, 300));
        }
        return tree;
    }

    private static List<Long> scan(BTree tree, byte[] from, byte[] to) {
        List<Long> seen = new ArrayList<>();
        try (TreeCursor c = tree.scan(from, to)) {
            while (c.next()) {
                long n = new KeyDecoder(c.key()).readLong();
                assertArrayEquals(value(n, 300), c.value(), "value of " + n);
                seen.add(n);
            }
        }
        return seen;
    }

    private static List<Long> evens(long fromInclusive, long toExclusive) {
        return LongStream.range(fromInclusive, toExclusive).filter(n -> n % 2 == 0).boxed().toList();
    }

    @Test
    void anEmptyTreeScansNothing() {
        BTree tree = BTree.create(pool, PAGE);
        assertEquals(List.of(), scan(tree, null, null));
    }

    @Test
    void anUnboundedScanVisitsEveryKeyInOrderAcrossLeaves() {
        BTree tree = evenKeys(3_000);
        assertTrue(TreeChecker.check(tree).height() >= 3);
        assertEquals(evens(0, 6_000), scan(tree, null, null));
    }

    @Test
    void boundsAreInclusiveFromAndExclusiveTo() {
        BTree tree = evenKeys(3_000);
        assertEquals(evens(100, 2_000), scan(tree, key(100), key(2_000)));
        assertEquals(evens(102, 2_000), scan(tree, key(101), key(1_999)));
        assertEquals(evens(0, 50), scan(tree, null, key(50)));
        assertEquals(evens(5_900, 6_000), scan(tree, key(5_900), null));
    }

    @Test
    void emptyAndInvertedRangesScanNothing() {
        BTree tree = evenKeys(500);
        assertEquals(List.of(), scan(tree, key(10), key(10)));
        assertEquals(List.of(), scan(tree, key(11), key(12)));
        assertEquals(List.of(), scan(tree, key(400), key(100)));
        assertEquals(List.of(), scan(tree, key(1_000), null));
    }

    @Test
    void aPrefixScanFindsEveryKeyOfOneComposite() {
        BTree tree = BTree.create(pool, PAGE);
        for (String region : List.of("as", "eu", "us")) {
            for (long id = 0; id < 400; id++) {
                tree.put(KeyEncoder.create().addString(region).addLong(id).encode(), value(id, 200));
            }
        }
        byte[] eu = KeyEncoder.create().addString("eu").encode();
        int count = 0;
        try (TreeCursor c = tree.scan(eu, Keys.prefixEnd(eu))) {
            while (c.next()) {
                assertArrayEquals(KeyEncoder.create().addString("eu").addLong(count).encode(), c.key());
                count++;
            }
        }
        assertEquals(400, count);
    }

    @Test
    void aCursorHoldsNoPinsBetweenCalls() {
        BTree tree = evenKeys(3_000);
        List<TreeCursor> open = new ArrayList<>();
        for (int i = 0; i < 100; i++) { // more open cursors than the pool has frames
            TreeCursor c = tree.scan(key(i * 50L), null);
            assertTrue(c.next());
            open.add(c);
        }
        for (long n = 0; n < 6_000; n += 2) {
            assertArrayEquals(value(n, 300), tree.get(key(n)));
        }
        for (TreeCursor c : open) {
            assertTrue(c.next());
            c.close();
        }
    }

    @Test
    void writesBetweenCallsAreSeenAheadAndDoNotBreakTheScan() {
        BTree tree = evenKeys(2_000);
        List<Long> seen = new ArrayList<>();
        try (TreeCursor c = tree.scan(null, null)) {
            while (c.next()) {
                long n = new KeyDecoder(c.key()).readLong();
                seen.add(n);
                if (n == 1_000) {
                    for (long k = 1_002; k < 3_000; k += 2) {
                        tree.delete(key(k)); // merges and frees the leaves ahead of the cursor
                    }
                    tree.put(key(3_001), value(3_001, 300));
                }
            }
        }
        TreeChecker.check(tree);
        List<Long> expected = new ArrayList<>(evens(0, 1_002));
        expected.add(3_001L);
        expected.addAll(evens(3_000, 4_000));
        expected.sort(null);
        // The batch already copied when the deletes ran may still hold a few of the deleted keys.
        List<Long> stale = seen.stream().filter(n -> n > 1_000 && n < 3_000).toList();
        assertTrue(stale.size() < 20, "stale keys " + stale);
        List<Long> rest = new ArrayList<>(seen);
        rest.removeAll(stale);
        assertEquals(expected, rest);
    }

    @Test
    void misuseIsAnError() {
        BTree tree = evenKeys(10);
        TreeCursor c = tree.scan(null, null);
        assertThrows(IllegalStateException.class, c::key);
        assertTrue(c.next());
        c.close();
        assertThrows(IllegalStateException.class, c::next);
        assertThrows(IllegalStateException.class, c::value);

        TreeCursor done = tree.scan(key(1_000), null);
        assertFalse(done.next());
        assertFalse(done.next());
        assertThrows(IllegalStateException.class, done::key);
    }
}
