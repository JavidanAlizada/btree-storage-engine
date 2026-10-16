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

    /** The first index of the right half: where the running size first reaches half of the total. */
    static int leafCut(List<byte[]> cells) {
        long total = 0;
        for (byte[] c : cells) {
            total += c.length + SlottedPage.SLOT_SIZE;
        }
        long running = 0;
        for (int i = 0; i < cells.size(); i++) {
            running += cells.get(i).length + SlottedPage.SLOT_SIZE;
            if (running * 2 >= total) {
                return Math.max(1, Math.min(i + 1, cells.size() - 1));
            }
        }
        return cells.size() - 1;
    }

    /** The index of the cell that moves up: the halves either side of it are about equal in bytes. */
    static int internalCut(List<byte[]> cells) {
        return Math.max(1, Math.min(leafCut(cells), cells.size() - 2));
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
