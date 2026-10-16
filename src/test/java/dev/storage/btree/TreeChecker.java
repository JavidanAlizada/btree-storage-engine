package dev.storage.btree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.storage.buffer.BufferPool;
import dev.storage.buffer.PageGuard;
import dev.storage.key.Keys;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Walks a whole tree and checks the B+Tree invariants (doc 05, AP): cells sorted; every key inside its parent's
 * separator bounds; all leaves at one depth; the leaf chain visits the leaves in key order and ends at 0; non-root
 * nodes are non-empty and at least a quarter full; no page is reachable twice.
 */
final class TreeChecker {

    record Result(int keys, int height, int pages) { }

    private final BufferPool pool;
    private final int root;
    private final Set<Integer> seen = new HashSet<>();
    private final List<Integer> leaves = new ArrayList<>();
    private int leafDepth = -1;
    private int keys;

    private TreeChecker(BTree tree) {
        this.pool = tree.pool();
        this.root = tree.rootPage();
    }

    static Result check(BTree tree) {
        var checker = new TreeChecker(tree);
        checker.visit(checker.root, null, null, 0);
        checker.checkLeafChain();
        return new Result(checker.keys, checker.leafDepth + 1, checker.seen.size());
    }

    private void visit(int id, byte[] low, byte[] high, int depth) {
        assertTrue(seen.add(id), "page " + id + " is reachable twice");
        List<int[]> children = new ArrayList<>();
        List<byte[]> bounds = new ArrayList<>();
        boolean leaf;
        try (PageGuard page = pool.fetch(id)) {
            page.latchShared();
            Node node = Node.of(page.buffer());
            leaf = node.isLeaf();
            int n = node.count();
            if (id != root) {
                assertTrue(n > 0, "non-root page " + id + " is empty");
                assertTrue(node.usedBytes() * 4 >= node.capacity(),
                        "page " + id + " is under a quarter full: " + node.usedBytes() + " of " + node.capacity());
            } else if (!leaf) {
                assertTrue(n > 0, "internal root has no separator");
            }
            byte[] previous = null;
            for (int i = 0; i < n; i++) {
                byte[] key = node.key(i);
                if (previous != null) {
                    assertTrue(Keys.compare(previous, key) < 0, "page " + id + " keys out of order at slot " + i);
                }
                if (low != null) {
                    assertTrue(Keys.compare(low, key) <= 0, "page " + id + " slot " + i + " is below its bound");
                }
                if (high != null) {
                    assertTrue(Keys.compare(key, high) < 0, "page " + id + " slot " + i + " is above its bound");
                }
                previous = key;
            }
            if (leaf) {
                keys += n;
            } else {
                children.add(new int[] {node.child(-1)});
                bounds.add(low);
                for (int i = 0; i < n; i++) {
                    children.add(new int[] {node.child(i)});
                    bounds.add(node.key(i));
                }
            }
        }
        if (leaf) {
            if (leafDepth == -1) {
                leafDepth = depth;
            }
            assertEquals(leafDepth, depth, "leaf " + id + " is at a different depth");
            leaves.add(id);
            return;
        }
        for (int c = 0; c < children.size(); c++) {
            byte[] childHigh = c + 1 < bounds.size() ? bounds.get(c + 1) : high;
            visit(children.get(c)[0], bounds.get(c), childHigh, depth + 1);
        }
    }

    private void checkLeafChain() {
        for (int i = 0; i < leaves.size(); i++) {
            int expectedNext = i + 1 < leaves.size() ? leaves.get(i + 1) : 0;
            try (PageGuard page = pool.fetch(leaves.get(i))) {
                page.latchShared();
                assertEquals(expectedNext, Node.of(page.buffer()).aux(), "leaf " + leaves.get(i) + " links wrongly");
            }
        }
    }
}
