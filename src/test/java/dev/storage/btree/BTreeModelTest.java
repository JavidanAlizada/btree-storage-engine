package dev.storage.btree;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.storage.buffer.ClockReplacer;
import dev.storage.buffer.LruReplacer;
import dev.storage.file.PageFile;
import dev.storage.wal.Durability;
import dev.storage.wal.PageStore;
import dev.storage.key.KeyEncoder;
import dev.storage.page.PageHeader;
import dev.storage.page.PageType;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Random puts, deletes, gets and scans checked against a TreeMap, with the full invariant check after every
 * operation. 4 KiB pages, keys of up to ~300 bytes and values of up to ~900, so trees get deep with few keys and
 * every split, merge, redistribute and root collapse path runs. The pool is small, so a leaked pin fails within a
 * few operations, and pages are evicted and read back all the time.
 */
class BTreeModelTest {

    private static final int PAGE = 4096;
    private static final int FRAMES = 24; // a merge holds about three pages per level until it commits
    private static final int KEY_SPACE = 1_500;
    private static final int OPS = 4_000;

    @TempDir
    Path dir;

    // A stable key per id, padded to a length that varies with the id, so separators vary in size too.
    private static byte[] key(int id) {
        return KeyEncoder.create().addInt(id).addBytes(new byte[(id * 7_919) % 300]).encode();
    }

    @Test
    void seed1() {
        run(1, true);
    }

    @Test
    void seed2() {
        run(2, false);
    }

    @Test
    void seed3() {
        run(3, true);
    }

    private void run(long seed, boolean clock) {
        Random random = new Random(seed);
        Path path = dir.resolve("db-" + seed);
        NavigableMap<byte[], byte[]> model = new TreeMap<>(Arrays::compareUnsigned);
        int root;
        int maxEntry = BTree.maxEntrySize(PAGE);
        try (PageStore store = PageStore.open(path, config(clock))) {
            BTree tree = BTree.create(store);
            root = tree.rootPage();
            int maxHeight = 0;
            for (int op = 0; op < OPS; op++) {
                // Grow for the first half, then mostly shrink, so the tree also collapses back down.
                int putWeight = op < OPS / 2 ? 60 : 25;
                int roll = random.nextInt(100);
                byte[] k = key(random.nextInt(KEY_SPACE));
                String what;
                if (roll < putWeight) {
                    byte[] v = new byte[random.nextInt(Math.min(900, maxEntry - k.length) + 1)];
                    random.nextBytes(v);
                    tree.put(k, v);
                    model.put(k, v);
                    what = "put";
                } else if (roll < 75) {
                    assertEquals(model.remove(k) != null, tree.delete(k), "delete at op " + op);
                    what = "delete";
                } else if (roll < 90) {
                    assertArrayEquals(model.get(k), tree.get(k), "get at op " + op);
                    what = "get";
                } else {
                    byte[] from = random.nextInt(5) == 0 ? null : key(random.nextInt(KEY_SPACE));
                    byte[] to = random.nextInt(5) == 0 ? null : key(random.nextInt(KEY_SPACE));
                    assertScan(tree, model, from, to, "scan at op " + op);
                    what = "scan";
                }
                var result = check(tree, what, op);
                assertEquals(model.size(), result.keys(), what + " at op " + op);
                maxHeight = Math.max(maxHeight, result.height());
                assertEquals(root, tree.rootPage());
            }
            assertTrue(maxHeight >= 4, "max height " + maxHeight);
            assertNoPageLost(path, store, tree);

            // Empty it: everything but the root goes back to the free list.
            for (byte[] k : new ArrayList<>(model.keySet())) {
                assertTrue(tree.delete(k));
                model.remove(k);
                check(tree, "drain", -1);
            }
            assertEquals(new TreeChecker.Result(0, 1, 1), TreeChecker.check(tree));
            assertNoPageLost(path, store, tree);

            // Refill a little for the reopen check below.
            for (int id = 0; id < 400; id++) {
                byte[] v = new byte[random.nextInt(Math.min(600, maxEntry - key(id).length) + 1)];
                random.nextBytes(v);
                tree.put(key(id), v);
                model.put(key(id), v);
            }
        }

        try (PageStore store = PageStore.open(path, config(false))) {
            BTree tree = BTree.open(store, root);
            assertEquals(model.size(), TreeChecker.check(tree).keys());
            assertScan(tree, model, null, null, "after reopen");
        }
    }

    private static TreeChecker.Result check(BTree tree, String what, int op) {
        try {
            return TreeChecker.check(tree);
        } catch (AssertionError e) {
            throw new AssertionError("invariant broken after " + what + " at op " + op + ": " + e.getMessage(), e);
        }
    }

    private static void assertScan(BTree tree, NavigableMap<byte[], byte[]> model, byte[] from, byte[] to,
            String what) {
        NavigableMap<byte[], byte[]> expected = model;
        if (from != null && to != null && Arrays.compareUnsigned(from, to) > 0) {
            expected = new TreeMap<>(model.comparator());
        } else if (from != null && to != null) {
            expected = model.subMap(from, true, to, false);
        } else if (from != null) {
            expected = model.tailMap(from, true);
        } else if (to != null) {
            expected = model.headMap(to, false);
        }
        List<Map.Entry<byte[], byte[]>> want = new ArrayList<>(expected.entrySet());
        int i = 0;
        try (TreeCursor c = tree.scan(from, to)) {
            while (c.next()) {
                assertTrue(i < want.size(), what + ": more entries than expected");
                assertArrayEquals(want.get(i).getKey(), c.key(), what + ": key " + i);
                assertArrayEquals(want.get(i).getValue(), c.value(), what + ": value " + i);
                i++;
            }
        }
        assertEquals(want.size(), i, what + ": entry count");
    }

    // Every page but the meta page is either in the tree or free. Catches merges and collapses that drop a page.
    private static PageStore.Config config(boolean clock) {
        return new PageStore.Config(PAGE, FRAMES, clock ? ClockReplacer::new : LruReplacer::new, Durability.ASYNC,
                Long.MAX_VALUE);
    }

    // After a checkpoint, read straight from the file.
    private static void assertNoPageLost(Path path, PageStore store, BTree tree) {
        store.checkpoint();
        int inTree = TreeChecker.check(tree).pages();
        int free = 0;
        try (PageFile file = PageFile.open(path)) {
            ByteBuffer page = ByteBuffer.allocate(PAGE);
            for (int id = 1; id < file.pageCount(); id++) {
                page.clear();
                file.read(id, page);
                if (PageHeader.type(page) == PageType.FREE) {
                    free++;
                }
            }
            assertEquals(file.pageCount() - 1, inTree + free, "pages lost: " + inTree + " in the tree, " + free
                    + " free, " + file.pageCount() + " in the file");
        }
    }
}
