package dev.storage.page;

import java.nio.ByteBuffer;

/**
 * Variable-length cells in one page, kept in slot order. The slot array grows forward from the header and the cells
 * grow back from the end of the page:
 *
 * <pre>
 * | header | slot 0 | slot 1 | ... -&gt;      free      &lt;- ... | cell | cell |
 *                                                    ^ cellStart
 * </pre>
 *
 * A slot is a u16 offset and a u16 length. Slot i is the i-th entry, wherever its cell sits. A delete leaves a hole
 * (counted in fragmented) unless the cell was the lowest one; compaction runs only when an insert needs the holes.
 * An empty cell takes no bytes and always points at the page end, so moving cellStart can never leave it behind.
 *
 * A view: it holds nothing but the buffer, so wrapping a page costs nothing. Every read checks the header and slots
 * against the page bounds and throws CorruptPageException rather than reading outside the page.
 */
public final class SlottedPage {

    public static final int SLOT_SIZE = 4;

    private final ByteBuffer page;
    private final int pageSize;

    private SlottedPage(ByteBuffer page) {
        this.page = page;
        this.pageSize = page.capacity();
    }

    public static SlottedPage wrap(ByteBuffer page) {
        PageHeader.checkPageSize(page.capacity());
        return new SlottedPage(page);
    }

    /** The largest cell a page of this size accepts. It guarantees at least 4 cells per page. */
    public static int maxCellSize(int pageSize) {
        return (pageSize - PageHeader.SIZE) / 4 - SLOT_SIZE;
    }

    public ByteBuffer buffer() {
        return page;
    }

    public int slotCount() {
        int count = PageHeader.getU16(page, PageHeader.SLOT_COUNT);
        if (PageHeader.SIZE + count * SLOT_SIZE > cellStart()) {
            throw corrupt("slot array of " + count + " slots overlaps the cells");
        }
        return count;
    }

    /** Bytes available for one more cell, after its slot. */
    public int freeSpace() {
        return Math.max(0, totalFree(slotCount()) - SLOT_SIZE);
    }

    public int cellLength(int index) {
        checkIndex(index, slotCount());
        return PageHeader.getU16(page, slotOffset(index) + 2);
    }

    /** A read-write slice over the cell's bytes in the page. Valid until the page changes. */
    public ByteBuffer cell(int index) {
        int count = slotCount();
        checkIndex(index, count);
        int slot = slotOffset(index);
        int offset = PageHeader.getU16(page, slot);
        int length = PageHeader.getU16(page, slot + 2);
        if (offset < cellStart() || offset + length > pageSize) {
            throw corrupt("slot " + index + " points outside the cell area: offset " + offset + ", length "
                    + length);
        }
        return page.slice(offset, length);
    }

    public byte[] cellBytes(int index) {
        ByteBuffer cell = cell(index);
        byte[] bytes = new byte[cell.remaining()];
        cell.get(bytes);
        return bytes;
    }

    /**
     * Inserts cell as entry index, shifting later entries up by one. Returns false, leaving the page unchanged, if
     * the page doesn't have room.
     */
    public boolean insert(int index, byte[] cell) {
        int count = slotCount();
        if (index < 0 || index > count) {
            throw new IndexOutOfBoundsException("insert at " + index + " in a page of " + count + " slots");
        }
        if (cell.length > maxCellSize(pageSize)) {
            throw new IllegalArgumentException("cell of " + cell.length + " bytes is over the "
                    + maxCellSize(pageSize) + "-byte limit for " + pageSize + "-byte pages");
        }
        int needed = cell.length + SLOT_SIZE;
        if (totalFree(count) < needed) {
            return false;
        }
        if (gap(count) < needed) {
            compact();
        }
        for (int i = count - 1; i >= index; i--) {
            page.putInt(slotOffset(i + 1), page.getInt(slotOffset(i)));
        }
        int offset = pageSize;
        if (cell.length > 0) {
            offset = cellStart() - cell.length;
            page.put(offset, cell);
            PageHeader.putU16(page, PageHeader.CELL_START, offset);
        }
        PageHeader.putU16(page, slotOffset(index), offset);
        PageHeader.putU16(page, slotOffset(index) + 2, cell.length);
        PageHeader.putU16(page, PageHeader.SLOT_COUNT, count + 1);
        return true;
    }

    /** Removes entry index, shifting later entries down by one. */
    public void delete(int index) {
        int count = slotCount();
        checkIndex(index, count);
        int offset = PageHeader.getU16(page, slotOffset(index));
        int length = PageHeader.getU16(page, slotOffset(index) + 2);
        for (int i = index; i < count - 1; i++) {
            page.putInt(slotOffset(i), page.getInt(slotOffset(i + 1)));
        }
        page.putInt(slotOffset(count - 1), 0);
        PageHeader.putU16(page, PageHeader.SLOT_COUNT, count - 1);
        if (length > 0 && offset == cellStart()) {
            PageHeader.putU16(page, PageHeader.CELL_START, offset + length);
        } else {
            PageHeader.putU16(page, PageHeader.FRAGMENTED, fragmented() + length);
        }
    }

    /** Rewrites the cells contiguously at the end of the page, so all free space is one gap. */
    public void compact() {
        int count = slotCount();
        byte[] copy = new byte[pageSize];
        page.get(0, copy);
        int end = pageSize;
        for (int i = 0; i < count; i++) {
            int slot = slotOffset(i);
            int offset = PageHeader.getU16(page, slot);
            int length = PageHeader.getU16(page, slot + 2);
            if (offset < cellStart() || offset + length > pageSize) {
                throw corrupt("slot " + i + " points outside the cell area");
            }
            if (length == 0) {
                PageHeader.putU16(page, slot, pageSize);
                continue;
            }
            end -= length;
            page.put(end, copy, offset, length);
            PageHeader.putU16(page, slot, end);
        }
        for (int i = PageHeader.SIZE + count * SLOT_SIZE; i < end; i++) {
            page.put(i, (byte) 0);
        }
        PageHeader.putU16(page, PageHeader.CELL_START, end);
        PageHeader.putU16(page, PageHeader.FRAGMENTED, 0);
    }

    private int cellStart() {
        int start = PageHeader.getU16(page, PageHeader.CELL_START);
        if (start < PageHeader.SIZE || start > pageSize) {
            throw corrupt("cellStart " + start + " is outside the page");
        }
        return start;
    }

    private int fragmented() {
        return PageHeader.getU16(page, PageHeader.FRAGMENTED);
    }

    private int gap(int count) {
        return cellStart() - PageHeader.SIZE - count * SLOT_SIZE;
    }

    private int totalFree(int count) {
        int free = gap(count) + fragmented();
        if (free > pageSize - PageHeader.SIZE) {
            throw corrupt("fragmented byte count " + fragmented() + " is larger than the page allows");
        }
        return free;
    }

    private static int slotOffset(int index) {
        return PageHeader.SIZE + index * SLOT_SIZE;
    }

    private static void checkIndex(int index, int count) {
        if (index < 0 || index >= count) {
            throw new IndexOutOfBoundsException("slot " + index + " in a page of " + count + " slots");
        }
    }

    private CorruptPageException corrupt(String detail) {
        return new CorruptPageException("page " + Integer.toUnsignedString(PageHeader.pageId(page)) + ": " + detail);
    }
}
