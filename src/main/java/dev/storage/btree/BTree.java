package dev.storage.btree;

import dev.storage.buffer.BufferPool;
import dev.storage.buffer.PageGuard;
import dev.storage.page.PageType;
import dev.storage.page.SlottedPage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * A B+Tree of unique byte keys and byte values, stored in buffer pool pages. Keys compare as unsigned bytes.
 *
 * The root page never moves: a root split moves its contents into two new children, and a root left with one child
 * takes that child's contents back. So the root id recorded when the tree was created stays valid for its whole life.
 *
 * Concurrency for now: many readers or one writer, through one lock per tree. Page latches are still taken as the
 * buffer pool requires. Latch crabbing replaces the tree lock in M5.
 */
public final class BTree {

    private final BufferPool pool;
    private final int root;
    private final int maxCell;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private BTree(BufferPool pool, int root, int pageSize) {
        this.pool = pool;
        this.root = root;
        this.maxCell = SlottedPage.maxCellSize(pageSize);
    }

    /** A new, empty tree: one root leaf. */
    public static BTree create(BufferPool pool, int pageSize) {
        try (PageGuard page = pool.newPage()) {
            page.latchExclusive();
            Node.rewrite(page.buffer(), page.pageId(), PageType.LEAF, 0, List.of());
            page.markDirty();
            return new BTree(pool, page.pageId(), pageSize);
        }
    }

    /** The tree whose root is page root. */
    public static BTree open(BufferPool pool, int root, int pageSize) {
        return new BTree(pool, root, pageSize);
    }

    public int rootPage() {
        return root;
    }

    /** The longest key a tree on pages of this size accepts. */
    public static int maxKeySize(int pageSize) {
        return SlottedPage.maxCellSize(pageSize) - 6; // the key in an internal cell, with its length and child
    }

    /** The longest key + value a tree on pages of this size accepts. */
    public static int maxEntrySize(int pageSize) {
        return SlottedPage.maxCellSize(pageSize) - 2;
    }

    /** The value stored under key, or null. */
    public byte[] get(byte[] key) {
        lock.readLock().lock();
        try {
            int id = root;
            while (true) {
                try (PageGuard page = pool.fetch(id)) {
                    page.latchShared();
                    Node node = Node.of(page.buffer());
                    if (node.isLeaf()) {
                        int i = node.search(key);
                        return i >= 0 ? node.value(i) : null;
                    }
                    id = node.child(node.childPosition(key));
                }
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Stores value under key, replacing any value already there. */
    public void put(byte[] key, byte[] value) {
        if (key.length > maxCell - 6) {
            throw new IllegalArgumentException("key of " + key.length + " bytes is over the " + (maxCell - 6)
                    + "-byte limit");
        }
        if (2 + key.length + value.length > maxCell) {
            throw new IllegalArgumentException("key + value of " + (key.length + value.length)
                    + " bytes is over the " + (maxCell - 2) + "-byte limit; overflow pages aren't supported yet");
        }
        lock.writeLock().lock();
        try {
            Path path = new Path();
            int leaf = descend(key, path);
            try (PageGuard page = pool.fetch(leaf)) {
                page.latchExclusive();
                Node node = Node.of(page.buffer());
                int i = node.search(key);
                int at = i >= 0 ? i : -(i + 1);
                if (i >= 0) {
                    node.page().delete(i);
                }
                byte[] cell = Node.leafCell(key, value);
                page.markDirty();
                if (node.page().insert(at, cell)) {
                    return;
                }
                List<byte[]> cells = cells(node);
                cells.add(at, cell);
                split(page, true, cells, path);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Removes key. Returns false if it wasn't there. */
    public boolean delete(byte[] key) {
        lock.writeLock().lock();
        try {
            Path path = new Path();
            int leaf = descend(key, path);
            boolean rebalance;
            try (PageGuard page = pool.fetch(leaf)) {
                page.latchExclusive();
                Node node = Node.of(page.buffer());
                int i = node.search(key);
                if (i < 0) {
                    return false;
                }
                node.page().delete(i);
                page.markDirty();
                rebalance = leaf != root && underfull(node);
            }
            if (rebalance) {
                rebalance(path);
            }
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ---- merge and redistribute ----

    /**
     * The node at the end of path (the child the last step took) is underfull. Merges it with a sibling under the same
     * parent if both fit in one page, otherwise redistributes their cells; then repeats for the parent if that left it
     * underfull.
     */
    private void rebalance(Path path) {
        while (!path.isEmpty()) {
            int parentId = path.lastPage();
            int position = path.lastPosition();
            path.pop();
            boolean parentUnderfull;
            try (PageGuard parent = pool.fetch(parentId)) {
                parent.latchExclusive();
                Node p = Node.of(parent.buffer());
                // The right sibling if there is one, else the left: separator s sits between children s - 1 and s.
                int separator = position < p.count() - 1 ? position + 1 : position;
                int leftId = p.child(separator - 1);
                int rightId = p.child(separator);
                byte[] separatorKey = p.key(separator);
                parent.markDirty();
                try (PageGuard left = pool.fetch(leftId); PageGuard right = pool.fetch(rightId)) {
                    left.latchExclusive();
                    right.latchExclusive();
                    Node l = Node.of(left.buffer());
                    Node r = Node.of(right.buffer());
                    boolean leaf = l.isLeaf();
                    List<byte[]> combined = cells(l);
                    if (!leaf) {
                        combined.add(Node.internalCell(separatorKey, r.aux())); // the separator comes down
                    }
                    combined.addAll(cells(r));
                    left.markDirty();
                    right.markDirty();
                    if (fits(combined, l.capacity())) {
                        Node.rewrite(left.buffer(), leftId, leaf ? PageType.LEAF : PageType.INTERNAL,
                                leaf ? r.aux() : l.aux(), combined);
                        pool.free(right);
                        p.page().delete(separator);
                        if (parentId == root) {
                            if (p.count() == 0) {
                                collapseRoot(parent, left);
                            }
                            return;
                        }
                        parentUnderfull = underfull(p);
                    } else {
                        byte[] newSeparator = redistribute(left, right, leaf, l.aux(), r.aux(), combined);
                        p.page().delete(separator);
                        byte[] cell = Node.internalCell(newSeparator, rightId);
                        if (!p.page().insert(separator, cell)) {
                            // The new separator is longer and the parent was full: split it, as an insert would.
                            List<byte[]> parentCells = cells(p);
                            parentCells.add(separator, cell);
                            left.close();
                            right.close();
                            split(parent, false, parentCells, path);
                            return;
                        }
                        parentUnderfull = parentId != root && underfull(p);
                    }
                }
            }
            if (!parentUnderfull) {
                return;
            }
        }
    }

    // Re-splits two siblings' cells by bytes and returns the separator that now goes between them.
    private byte[] redistribute(PageGuard left, PageGuard right, boolean leaf, int leftAux, int rightAux,
            List<byte[]> combined) {
        if (leaf) {
            int cut = leafCut(combined);
            Node.rewrite(left.buffer(), left.pageId(), PageType.LEAF, right.pageId(), combined.subList(0, cut));
            Node.rewrite(right.buffer(), right.pageId(), PageType.LEAF, rightAux,
                    combined.subList(cut, combined.size()));
            return Node.cellKey(combined.get(cut));
        }
        int middle = internalCut(combined);
        byte[] up = combined.get(middle);
        Node.rewrite(left.buffer(), left.pageId(), PageType.INTERNAL, leftAux, combined.subList(0, middle));
        Node.rewrite(right.buffer(), right.pageId(), PageType.INTERNAL, Node.cellChild(up),
                combined.subList(middle + 1, combined.size()));
        return Node.cellKey(up);
    }

    // An internal root with no separators left has one child: the root takes over its contents, keeping its id.
    private void collapseRoot(PageGuard rootPage, PageGuard onlyChild) {
        Node child = Node.of(onlyChild.buffer());
        Node.rewrite(rootPage.buffer(), root, child.isLeaf() ? PageType.LEAF : PageType.INTERNAL, child.aux(),
                cells(child));
        rootPage.markDirty();
        pool.free(onlyChild);
    }

    // ---- descent ----

    /** The pages from the root down to (not including) the leaf, and which child was taken at each. */
    static final class Path {
        final List<Integer> pages = new ArrayList<>();
        final List<Integer> positions = new ArrayList<>();

        void push(int page, int position) {
            pages.add(page);
            positions.add(position);
        }

        boolean isEmpty() {
            return pages.isEmpty();
        }

        int lastPage() {
            return pages.get(pages.size() - 1);
        }

        int lastPosition() {
            return positions.get(positions.size() - 1);
        }

        void pop() {
            pages.remove(pages.size() - 1);
            positions.remove(positions.size() - 1);
        }
    }

    /** Walks to the leaf for key, recording the path. No pins are kept: the tree lock keeps the pages still. */
    int descend(byte[] key, Path path) {
        int id = root;
        while (true) {
            try (PageGuard page = pool.fetch(id)) {
                page.latchShared();
                Node node = Node.of(page.buffer());
                if (node.isLeaf()) {
                    return id;
                }
                int position = node.childPosition(key);
                path.push(id, position);
                id = node.child(position);
            }
        }
    }

    // ---- splits ----

    /**
     * page overflowed: cells (in order) are what it should hold. Splits it by bytes and pushes the separator up the
     * path, splitting parents as needed. page is latched exclusively by the caller.
     */
    void split(PageGuard page, boolean leaf, List<byte[]> cells, Path path) {
        if (page.pageId() == root) {
            splitRoot(page, leaf, cells);
            return;
        }
        Node node = Node.of(page.buffer());
        byte[] separator;
        int rightId;
        try (PageGuard right = pool.newPage()) {
            right.latchExclusive();
            rightId = right.pageId();
            if (leaf) {
                int cut = leafCut(cells);
                Node.rewrite(right.buffer(), rightId, PageType.LEAF, node.aux(), cells.subList(cut, cells.size()));
                Node.rewrite(page.buffer(), page.pageId(), PageType.LEAF, rightId, cells.subList(0, cut));
                separator = Node.cellKey(cells.get(cut));
            } else {
                int middle = internalCut(cells);
                byte[] up = cells.get(middle);
                Node.rewrite(right.buffer(), rightId, PageType.INTERNAL, Node.cellChild(up),
                        cells.subList(middle + 1, cells.size()));
                Node.rewrite(page.buffer(), page.pageId(), PageType.INTERNAL, node.aux(), cells.subList(0, middle));
                separator = Node.cellKey(up);
            }
            right.markDirty();
            page.markDirty();
        }
        page.close();
        insertIntoParent(path, separator, rightId);
    }

    /** Adds separator → child just after the position the path took in the last parent on it. */
    void insertIntoParent(Path path, byte[] separator, int child) {
        int parentId = path.lastPage();
        int at = path.lastPosition() + 1;
        path.pop();
        try (PageGuard parent = pool.fetch(parentId)) {
            parent.latchExclusive();
            Node node = Node.of(parent.buffer());
            byte[] cell = Node.internalCell(separator, child);
            parent.markDirty();
            if (node.page().insert(at, cell)) {
                return;
            }
            List<byte[]> cells = cells(node);
            cells.add(at, cell);
            split(parent, false, cells, path);
        }
    }

    // The root keeps its page id: its two halves move to new pages and it becomes their parent.
    private void splitRoot(PageGuard rootPage, boolean leaf, List<byte[]> cells) {
        Node node = Node.of(rootPage.buffer());
        try (PageGuard left = pool.newPage(); PageGuard right = pool.newPage()) {
            left.latchExclusive();
            right.latchExclusive();
            byte[] separator;
            if (leaf) {
                int cut = leafCut(cells);
                Node.rewrite(left.buffer(), left.pageId(), PageType.LEAF, right.pageId(), cells.subList(0, cut));
                Node.rewrite(right.buffer(), right.pageId(), PageType.LEAF, 0, cells.subList(cut, cells.size()));
                separator = Node.cellKey(cells.get(cut));
            } else {
                int middle = internalCut(cells);
                byte[] up = cells.get(middle);
                Node.rewrite(left.buffer(), left.pageId(), PageType.INTERNAL, node.aux(), cells.subList(0, middle));
                Node.rewrite(right.buffer(), right.pageId(), PageType.INTERNAL, Node.cellChild(up),
                        cells.subList(middle + 1, cells.size()));
                separator = Node.cellKey(up);
            }
            Node.rewrite(rootPage.buffer(), root, PageType.INTERNAL, left.pageId(),
                    List.of(Node.internalCell(separator, right.pageId())));
            left.markDirty();
            right.markDirty();
            rootPage.markDirty();
        }
    }

    /**
     * The first index of the right half, chosen so the larger half is as small as possible. With cells of at most a
     * quarter page, the halves then differ by at most one cell: both fit, and each is over a quarter full.
     */
    static int leafCut(List<byte[]> cells) {
        long[] prefix = prefixSizes(cells);
        long total = prefix[cells.size()];
        int best = 1;
        long bestMax = Long.MAX_VALUE;
        for (int k = 1; k < cells.size(); k++) {
            long max = Math.max(prefix[k], total - prefix[k]);
            if (max < bestMax) {
                bestMax = max;
                best = k;
            }
        }
        return best;
    }

    /** The cell that moves up, chosen so the larger of the halves either side of it is as small as possible. */
    static int internalCut(List<byte[]> cells) {
        long[] prefix = prefixSizes(cells);
        long total = prefix[cells.size()];
        int best = 1;
        long bestMax = Long.MAX_VALUE;
        for (int m = 1; m < cells.size() - 1; m++) {
            long max = Math.max(prefix[m], total - prefix[m + 1]);
            if (max < bestMax) {
                bestMax = max;
                best = m;
            }
        }
        return best;
    }

    private static long[] prefixSizes(List<byte[]> cells) {
        long[] prefix = new long[cells.size() + 1];
        for (int i = 0; i < cells.size(); i++) {
            prefix[i + 1] = prefix[i] + cells.get(i).length + SlottedPage.SLOT_SIZE;
        }
        return prefix;
    }

    private static boolean fits(List<byte[]> cells, int capacity) {
        return prefixSizes(cells)[cells.size()] <= capacity;
    }

    private static boolean underfull(Node node) {
        return node.usedBytes() * 10L < node.capacity() * 4L;
    }

    static List<byte[]> cells(Node node) {
        List<byte[]> cells = new ArrayList<>(node.count() + 1);
        for (int i = 0; i < node.count(); i++) {
            cells.add(node.cell(i));
        }
        return cells;
    }

    BufferPool pool() {
        return pool;
    }
}
