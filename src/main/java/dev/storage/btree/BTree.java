package dev.storage.btree;

import dev.storage.buffer.BufferPool;
import dev.storage.buffer.PageGuard;
import dev.storage.page.PageType;
import dev.storage.page.SlottedPage;
import dev.storage.wal.Mtr;
import dev.storage.wal.PageStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * A B+Tree of unique byte keys and byte values, stored in logged pages. Keys compare as unsigned bytes. Every change
 * is one mini-transaction, so a split or merge reaches the log, and recovery, whole or not at all.
 *
 * The root page never moves: a root split moves its contents into two new children, and a root left with one child
 * takes that child's contents back. So the root id recorded when the tree was created stays valid for its whole life.
 *
 * Concurrency for now: many readers or one writer, through one lock per tree. Page latches are still taken as the
 * buffer pool requires. Latch crabbing replaces the tree lock in M5. A writer begins its mini-transaction before it
 * takes the tree lock, the order PageStore requires.
 */
public final class BTree {

    private final PageStore store;
    private final BufferPool pool;
    private final int root;
    private final int maxCell;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private BTree(PageStore store, int root) {
        this.store = store;
        this.pool = store.pool();
        this.root = root;
        this.maxCell = SlottedPage.maxCellSize(store.pageSize());
    }

    /** A new, empty tree: one root leaf. */
    public static BTree create(PageStore store) {
        try (Mtr mtr = store.begin()) {
            BTree tree = create(store, mtr);
            mtr.commit();
            return tree;
        }
    }

    /** A new, empty tree, made inside the caller's mini-transaction. */
    public static BTree create(PageStore store, Mtr mtr) {
        try (PageGuard page = mtr.allocate()) {
            rewrite(mtr, page, PageType.LEAF, 0, List.of());
            return new BTree(store, page.pageId());
        }
    }

    /** The tree whose root is page root. */
    public static BTree open(PageStore store, int root) {
        return new BTree(store, root);
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
        checkSizes(key, value);
        try (Mtr mtr = store.begin()) {
            put(mtr, key, value);
            mtr.commit();
        }
    }

    /** As put(key, value), inside the caller's mini-transaction, which must commit it. */
    public void put(Mtr mtr, byte[] key, byte[] value) {
        checkSizes(key, value);
        lock.writeLock().lock();
        try {
            Path path = new Path();
            int leaf = descend(key, path);
            try (PageGuard page = mtr.fetchExclusive(leaf)) {
                Node node = Node.of(page.buffer());
                int i = node.search(key);
                int at = i >= 0 ? i : -(i + 1);
                if (i >= 0) {
                    mtr.delete(page, i);
                }
                byte[] cell = Node.leafCell(key, value);
                if (mtr.insert(page, at, cell)) {
                    return;
                }
                List<byte[]> cells = cells(node);
                cells.add(at, cell);
                split(mtr, page, true, cells, path);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void checkSizes(byte[] key, byte[] value) {
        if (key.length > maxCell - 6) {
            throw new IllegalArgumentException("key of " + key.length + " bytes is over the " + (maxCell - 6)
                    + "-byte limit");
        }
        if (2 + key.length + value.length > maxCell) {
            throw new IllegalArgumentException("key + value of " + (key.length + value.length)
                    + " bytes is over the " + (maxCell - 2) + "-byte limit; overflow pages aren't supported yet");
        }
    }

    /** Removes key. Returns false if it wasn't there. */
    public boolean delete(byte[] key) {
        try (Mtr mtr = store.begin()) {
            boolean found;
            lock.writeLock().lock();
            try {
                found = delete(mtr, key);
            } finally {
                lock.writeLock().unlock();
            }
            if (found) {
                mtr.commit();
            }
            return found;
        }
    }

    private boolean delete(Mtr mtr, byte[] key) {
        Path path = new Path();
        int leaf = descend(key, path);
        boolean rebalance;
        try (PageGuard page = mtr.fetchExclusive(leaf)) {
            Node node = Node.of(page.buffer());
            int i = node.search(key);
            if (i < 0) {
                return false;
            }
            mtr.delete(page, i);
            rebalance = leaf != root && underfull(node);
        }
        if (rebalance) {
            rebalance(mtr, path);
        }
        return true;
    }

    /** The entries in [from, to) in key order; null leaves that end unbounded. Close the cursor when done. */
    public TreeCursor scan(byte[] from, byte[] to) {
        return new TreeCursor(this, from, to);
    }

    /**
     * Copies the entries from seek (inclusive) to the end of its leaf, stopping before to. If the leaf has nothing at
     * or after seek, moves along the sibling links until a leaf does; that's safe because the tree lock is held for
     * the whole call. Returns false if nothing can follow this batch.
     */
    boolean loadBatch(byte[] seek, byte[] to, List<byte[]> keys, List<byte[]> values) {
        lock.readLock().lock();
        try {
            int id = descend(seek, new Path());
            int from = -1;
            while (true) {
                try (PageGuard page = pool.fetch(id)) {
                    page.latchShared();
                    Node node = Node.of(page.buffer());
                    if (from < 0) {
                        int i = node.search(seek);
                        from = i >= 0 ? i : -(i + 1);
                    }
                    for (int i = from; i < node.count(); i++) {
                        byte[] key = node.key(i);
                        if (to != null && Arrays.compareUnsigned(key, to) >= 0) {
                            return false;
                        }
                        keys.add(key);
                        values.add(node.value(i));
                    }
                    id = node.aux();
                }
                if (id == 0) {
                    return false;
                }
                if (!keys.isEmpty()) {
                    return true;
                }
                from = 0;
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    // ---- merge and redistribute ----

    /**
     * The node at the end of path (the child the last step took) is underfull. Merges it with a sibling under the same
     * parent if both fit in one page, otherwise redistributes their cells; then repeats for the parent if that left it
     * underfull.
     */
    private void rebalance(Mtr mtr, Path path) {
        while (!path.isEmpty()) {
            int parentId = path.lastPage();
            int position = path.lastPosition();
            path.pop();
            boolean parentUnderfull;
            try (PageGuard parent = mtr.fetchExclusive(parentId)) {
                Node p = Node.of(parent.buffer());
                // The right sibling if there is one, else the left: separator s sits between children s - 1 and s.
                int separator = position < p.count() - 1 ? position + 1 : position;
                int leftId = p.child(separator - 1);
                int rightId = p.child(separator);
                byte[] separatorKey = p.key(separator);
                try (PageGuard left = mtr.fetchExclusive(leftId); PageGuard right = mtr.fetchExclusive(rightId)) {
                    Node l = Node.of(left.buffer());
                    Node r = Node.of(right.buffer());
                    boolean leaf = l.isLeaf();
                    List<byte[]> combined = cells(l);
                    if (!leaf) {
                        combined.add(Node.internalCell(separatorKey, r.aux())); // the separator comes down
                    }
                    combined.addAll(cells(r));
                    if (fits(combined, l.capacity())) {
                        rewrite(mtr, left, leaf ? PageType.LEAF : PageType.INTERNAL, leaf ? r.aux() : l.aux(),
                                combined);
                        mtr.free(right);
                        mtr.delete(parent, separator);
                        if (parentId == root) {
                            if (p.count() == 0) {
                                collapseRoot(mtr, parent, left);
                            }
                            return;
                        }
                        parentUnderfull = underfull(p);
                    } else {
                        byte[] newSeparator = redistribute(mtr, left, right, leaf, l.aux(), r.aux(), combined);
                        mtr.delete(parent, separator);
                        byte[] cell = Node.internalCell(newSeparator, rightId);
                        if (!mtr.insert(parent, separator, cell)) {
                            // The new separator is longer and the parent was full: split it, as an insert would.
                            List<byte[]> parentCells = cells(p);
                            parentCells.add(separator, cell);
                            left.close();
                            right.close();
                            split(mtr, parent, false, parentCells, path);
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
    private static byte[] redistribute(Mtr mtr, PageGuard left, PageGuard right, boolean leaf, int leftAux,
            int rightAux, List<byte[]> combined) {
        if (leaf) {
            int cut = leafCut(combined);
            rewrite(mtr, left, PageType.LEAF, right.pageId(), combined.subList(0, cut));
            rewrite(mtr, right, PageType.LEAF, rightAux, combined.subList(cut, combined.size()));
            return Node.cellKey(combined.get(cut));
        }
        int middle = internalCut(combined);
        byte[] up = combined.get(middle);
        rewrite(mtr, left, PageType.INTERNAL, leftAux, combined.subList(0, middle));
        rewrite(mtr, right, PageType.INTERNAL, Node.cellChild(up), combined.subList(middle + 1, combined.size()));
        return Node.cellKey(up);
    }

    // Formats the page as a node holding cells, logged as an image.
    private static void rewrite(Mtr mtr, PageGuard page, PageType type, int aux, List<byte[]> cells) {
        Node.rewrite(page.buffer(), page.pageId(), type, aux, cells);
        mtr.rewritten(page);
    }

    // An internal root with no separators left has one child: the root takes over its contents, keeping its id.
    private static void collapseRoot(Mtr mtr, PageGuard rootPage, PageGuard onlyChild) {
        Node child = Node.of(onlyChild.buffer());
        rewrite(mtr, rootPage, child.isLeaf() ? PageType.LEAF : PageType.INTERNAL, child.aux(), cells(child));
        mtr.free(onlyChild);
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
    void split(Mtr mtr, PageGuard page, boolean leaf, List<byte[]> cells, Path path) {
        if (page.pageId() == root) {
            splitRoot(mtr, page, leaf, cells);
            return;
        }
        Node node = Node.of(page.buffer());
        byte[] separator;
        int rightId;
        try (PageGuard right = mtr.allocate()) {
            rightId = right.pageId();
            if (leaf) {
                int cut = leafCut(cells);
                rewrite(mtr, right, PageType.LEAF, node.aux(), cells.subList(cut, cells.size()));
                rewrite(mtr, page, PageType.LEAF, rightId, cells.subList(0, cut));
                separator = Node.cellKey(cells.get(cut));
            } else {
                int middle = internalCut(cells);
                byte[] up = cells.get(middle);
                rewrite(mtr, right, PageType.INTERNAL, Node.cellChild(up), cells.subList(middle + 1, cells.size()));
                rewrite(mtr, page, PageType.INTERNAL, node.aux(), cells.subList(0, middle));
                separator = Node.cellKey(up);
            }
        }
        page.close(); // the mini-transaction keeps it latched until commit
        insertIntoParent(mtr, path, separator, rightId);
    }

    /** Adds separator → child just after the position the path took in the last parent on it. */
    void insertIntoParent(Mtr mtr, Path path, byte[] separator, int child) {
        int parentId = path.lastPage();
        int at = path.lastPosition() + 1;
        path.pop();
        try (PageGuard parent = mtr.fetchExclusive(parentId)) {
            Node node = Node.of(parent.buffer());
            byte[] cell = Node.internalCell(separator, child);
            if (mtr.insert(parent, at, cell)) {
                return;
            }
            List<byte[]> cells = cells(node);
            cells.add(at, cell);
            split(mtr, parent, false, cells, path);
        }
    }

    // The root keeps its page id: its two halves move to new pages and it becomes their parent.
    private void splitRoot(Mtr mtr, PageGuard rootPage, boolean leaf, List<byte[]> cells) {
        Node node = Node.of(rootPage.buffer());
        try (PageGuard left = mtr.allocate(); PageGuard right = mtr.allocate()) {
            byte[] separator;
            if (leaf) {
                int cut = leafCut(cells);
                rewrite(mtr, left, PageType.LEAF, right.pageId(), cells.subList(0, cut));
                rewrite(mtr, right, PageType.LEAF, 0, cells.subList(cut, cells.size()));
                separator = Node.cellKey(cells.get(cut));
            } else {
                int middle = internalCut(cells);
                byte[] up = cells.get(middle);
                rewrite(mtr, left, PageType.INTERNAL, node.aux(), cells.subList(0, middle));
                rewrite(mtr, right, PageType.INTERNAL, Node.cellChild(up), cells.subList(middle + 1, cells.size()));
                separator = Node.cellKey(up);
            }
            rewrite(mtr, rootPage, PageType.INTERNAL, left.pageId(),
                    List.of(Node.internalCell(separator, right.pageId())));
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

    PageStore store() {
        return store;
    }
}
