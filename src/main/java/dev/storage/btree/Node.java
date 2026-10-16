package dev.storage.btree;

import dev.storage.page.CorruptPageException;
import dev.storage.page.PageHeader;
import dev.storage.page.PageType;
import dev.storage.page.SlottedPage;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * A tree node read and written in place in its page. Both kinds are slotted pages with cells sorted by key.
 *
 * <pre>
 * leaf cell      keyLength u16 | key | value       aux = right sibling (0 = last leaf)
 * internal cell  keyLength u16 | key | child u32   aux = leftmost child
 * </pre>
 *
 * In an internal node, child i (counting the leftmost as -1) holds the keys from separator i up to separator i + 1.
 * Keys compare as unsigned bytes.
 */
final class Node {

    private final ByteBuffer buffer;
    private final SlottedPage page;

    private Node(ByteBuffer buffer) {
        this.buffer = buffer;
        this.page = SlottedPage.wrap(buffer);
    }

    static Node of(ByteBuffer buffer) {
        return new Node(buffer);
    }

    static byte[] leafCell(byte[] key, byte[] value) {
        byte[] cell = new byte[2 + key.length + value.length];
        cell[0] = (byte) (key.length >> 8);
        cell[1] = (byte) key.length;
        System.arraycopy(key, 0, cell, 2, key.length);
        System.arraycopy(value, 0, cell, 2 + key.length, value.length);
        return cell;
    }

    static byte[] internalCell(byte[] key, int child) {
        byte[] cell = new byte[2 + key.length + 4];
        cell[0] = (byte) (key.length >> 8);
        cell[1] = (byte) key.length;
        System.arraycopy(key, 0, cell, 2, key.length);
        int at = 2 + key.length;
        cell[at] = (byte) (child >> 24);
        cell[at + 1] = (byte) (child >> 16);
        cell[at + 2] = (byte) (child >> 8);
        cell[at + 3] = (byte) child;
        return cell;
    }

    /** The key of a cell built by leafCell or internalCell. */
    static byte[] cellKey(byte[] cell) {
        int length = ((cell[0] & 0xFF) << 8) | (cell[1] & 0xFF);
        byte[] key = new byte[length];
        System.arraycopy(cell, 2, key, 0, length);
        return key;
    }

    /** The child of a cell built by internalCell. */
    static int cellChild(byte[] cell) {
        int at = cell.length - 4;
        return ((cell[at] & 0xFF) << 24) | ((cell[at + 1] & 0xFF) << 16) | ((cell[at + 2] & 0xFF) << 8)
                | (cell[at + 3] & 0xFF);
    }

    /** Formats the page as a node holding cells, in order. They must fit. */
    static void rewrite(ByteBuffer buffer, int pageId, PageType type, int aux, List<byte[]> cells) {
        PageHeader.format(buffer, pageId, type);
        PageHeader.setAux(buffer, aux);
        SlottedPage page = SlottedPage.wrap(buffer);
        for (int i = 0; i < cells.size(); i++) {
            if (!page.insert(i, cells.get(i))) {
                throw new IllegalStateException("cells don't fit in page " + Integer.toUnsignedString(pageId));
            }
        }
    }

    boolean isLeaf() {
        PageType type = PageHeader.type(buffer);
        if (type != PageType.LEAF && type != PageType.INTERNAL) {
            throw new CorruptPageException("page " + Integer.toUnsignedString(PageHeader.pageId(buffer))
                    + " is a " + type + " page inside a tree");
        }
        return type == PageType.LEAF;
    }

    int count() {
        return page.slotCount();
    }

    int aux() {
        return PageHeader.aux(buffer);
    }

    void setAux(int value) {
        PageHeader.setAux(buffer, value);
    }

    int usedBytes() {
        return page.usedBytes();
    }

    int capacity() {
        return page.capacity();
    }

    SlottedPage page() {
        return page;
    }

    byte[] cell(int i) {
        return page.cellBytes(i);
    }

    byte[] key(int i) {
        return slice(keySlice(i));
    }

    byte[] value(int i) {
        ByteBuffer cell = page.cell(i);
        int keyLength = keyLength(cell, i);
        return slice(cell.slice(2 + keyLength, cell.remaining() - 2 - keyLength));
    }

    /** The child at position i, where -1 is the leftmost child. */
    int child(int i) {
        if (i == -1) {
            return aux();
        }
        ByteBuffer cell = page.cell(i);
        return cell.getInt(cell.remaining() - 4);
    }

    /** The slot holding key, or -(insertion point + 1) if it isn't here. */
    int search(byte[] key) {
        ByteBuffer target = ByteBuffer.wrap(key);
        int lo = 0;
        int hi = count() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int c = compare(keySlice(mid), target);
            if (c < 0) {
                lo = mid + 1;
            } else if (c > 0) {
                hi = mid - 1;
            } else {
                return mid;
            }
        }
        return -(lo + 1);
    }

    /** In an internal node: the position of the child whose range holds key (-1 for the leftmost). */
    int childPosition(byte[] key) {
        int i = search(key);
        return i >= 0 ? i : -(i + 1) - 1;
    }

    // Unsigned lexicographic, like Arrays.compareUnsigned.
    static int compare(ByteBuffer a, ByteBuffer b) {
        int i = a.mismatch(b);
        if (i == -1) {
            return 0;
        }
        if (i == a.remaining() || i == b.remaining()) {
            return a.remaining() - b.remaining();
        }
        return Byte.toUnsignedInt(a.get(a.position() + i)) - Byte.toUnsignedInt(b.get(b.position() + i));
    }

    private ByteBuffer keySlice(int i) {
        ByteBuffer cell = page.cell(i);
        return cell.slice(2, keyLength(cell, i));
    }

    private int keyLength(ByteBuffer cell, int i) {
        int length = Short.toUnsignedInt(cell.getShort(0));
        int room = cell.remaining() - 2 - (isLeaf() ? 0 : 4);
        if (length > room) {
            throw new CorruptPageException("page " + Integer.toUnsignedString(PageHeader.pageId(buffer))
                    + " slot " + i + ": key length " + length + " exceeds its cell");
        }
        return length;
    }

    private static byte[] slice(ByteBuffer b) {
        byte[] out = new byte[b.remaining()];
        b.get(0, out);
        return out;
    }
}
