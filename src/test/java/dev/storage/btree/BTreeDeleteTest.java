package dev.storage.btree;

import static dev.storage.btree.BTreeInsertTest.key;
import static dev.storage.btree.BTreeInsertTest.value;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.storage.buffer.ClockReplacer;
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

class BTreeDeleteTest {

    private static final int PAGE = BTreeInsertTest.PAGE;

    @TempDir
    Path dir;

    private PageStore store;

    @BeforeEach
    void open() {
        store = PageStore.open(dir.resolve("db"), new PageStore.Config(PAGE, 32, ClockReplacer::new, Durability.ASYNC,
                Long.MAX_VALUE));
    }

    @AfterEach
    void close() {
        store.close();
    }

    private BTree filled(int n, int valueSize) {
        BTree tree = BTree.create(store);
        for (long k = 0; k < n; k++) {
            tree.put(key(k), value(k, valueSize));
        }
        return tree;
    }

    @Test
    void deletingAMissingKeyChangesNothing() {
        BTree tree = filled(100, 50);
        assertFalse(tree.delete(key(1_000)));
        assertEquals(100, TreeChecker.check(tree).keys());
    }

    @Test
    void deletingEverythingInOrderCollapsesBackToARootLeaf() {
        BTree tree = filled(1_500, 300);
        int root = tree.rootPage();
        assertTrue(TreeChecker.check(tree).height() >= 3);
        for (long k = 0; k < 1_500; k++) {
            assertTrue(tree.delete(key(k)), "key " + k);
            if (k % 50 == 0) {
                assertEquals(1_500 - k - 1, TreeChecker.check(tree).keys());
            }
        }
        assertEquals(new TreeChecker.Result(0, 1, 1), TreeChecker.check(tree));
        assertEquals(root, tree.rootPage());
    }

    @Test
    void deletingInReverseAndAtRandomKeepsTheInvariants() {
        BTree tree = filled(1_000, 300);
        for (long k = 999; k >= 500; k--) {
            tree.delete(key(k));
        }
        assertEquals(500, TreeChecker.check(tree).keys());
        List<Long> rest = new ArrayList<>();
        for (long k = 0; k < 500; k++) {
            rest.add(k);
        }
        Collections.shuffle(rest, new Random(1));
        for (int i = 0; i < rest.size(); i++) {
            tree.delete(key(rest.get(i)));
            var result = TreeChecker.check(tree);
            assertEquals(rest.size() - i - 1, result.keys());
        }
        for (long k = 0; k < 1_000; k++) {
            assertNull(tree.get(key(k)));
        }
    }

    @Test
    void freedPagesAreReusedSoTheFileDoesNotGrowOnRefill() {
        BTree tree = filled(1_000, 300);
        int pages = store.pageCount();
        for (long k = 0; k < 1_000; k++) {
            tree.delete(key(k));
        }
        for (long k = 0; k < 1_000; k++) {
            tree.put(key(k), value(k, 300));
        }
        assertTrue(store.pageCount() <= pages + 2, "file grew from " + pages + " to " + store.pageCount() + " pages");
        for (long k = 0; k < 1_000; k++) {
            assertArrayEquals(value(k, 300), tree.get(key(k)));
        }
    }

    @Test
    void variableLengthKeysSurviveMergesAndSeparatorChanges() {
        // Keys from 1 to 400 bytes: replacement separators are often longer than the ones they replace, which is the
        // path where a delete splits a parent.
        BTree tree = BTree.create(store);
        var random = new Random(21);
        List<byte[]> keys = new ArrayList<>();
        for (int n = 0; n < 1_500; n++) {
            byte[] k = new byte[1 + random.nextInt(400)];
            random.nextBytes(k);
            keys.add(k);
            tree.put(k, new byte[random.nextInt(200)]);
        }
        TreeChecker.check(tree);
        Collections.shuffle(keys, random);
        for (int i = 0; i < keys.size(); i++) {
            tree.delete(keys.get(i));
            if (i % 10 == 0) {
                TreeChecker.check(tree);
            }
        }
        assertEquals(0, TreeChecker.check(tree).keys());
    }
}
