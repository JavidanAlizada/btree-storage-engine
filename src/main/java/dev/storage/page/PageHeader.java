package dev.storage.page;

import java.nio.ByteBuffer;

/**
 * The 32 bytes every page starts with, read and written in place with absolute gets and puts, so a buffer's
 * position never matters. Big-endian.
 *
 * <pre>
 *  0 crc32c u32 | 4 pageLSN i64 | 12 type u8 | 13 flags u8 | 14 reserved u16 | 16 pageId u32
 * 20 slotCount u16 | 22 cellStart u16 | 24 fragmented u16 | 26 reserved u16 | 28 aux u32
 * </pre>
 *
 * The checksum covers bytes 4 to the end of the page. aux means what the page type says: a leaf's right sibling, an
 * internal node's leftmost child, a free page's next free page.
 */
public final class PageHeader {

    public static final int SIZE = 32;
    public static final int MIN_PAGE_SIZE = 4 * 1024;
    // Offsets are u16 and an empty page's cellStart is the page size, so 64 KiB doesn't fit.
    public static final int MAX_PAGE_SIZE = 32 * 1024;

    static final int CHECKSUM = 0;
    static final int PAGE_LSN = 4;
    static final int TYPE = 12;
    static final int PAGE_ID = 16;
    static final int SLOT_COUNT = 20;
    static final int CELL_START = 22;
    static final int FRAGMENTED = 24;
    static final int AUX = 28;

    private PageHeader() {
    }

    public static boolean isValidPageSize(int size) {
        return size >= MIN_PAGE_SIZE && size <= MAX_PAGE_SIZE && Integer.bitCount(size) == 1;
    }

    /** Zeroes the whole page and writes an empty header for pageId. */
    public static void format(ByteBuffer page, int pageId, PageType type) {
        checkPageSize(page.capacity());
        for (int i = 0; i < page.capacity(); i += 8) {
            page.putLong(i, 0L);
        }
        page.put(TYPE, (byte) type.code());
        page.putInt(PAGE_ID, pageId);
        putU16(page, CELL_START, page.capacity());
    }

    public static PageType type(ByteBuffer page) {
        return PageType.of(Byte.toUnsignedInt(page.get(TYPE)));
    }

    public static void setType(ByteBuffer page, PageType type) {
        page.put(TYPE, (byte) type.code());
    }

    /** The page's own number, unsigned. */
    public static int pageId(ByteBuffer page) {
        return page.getInt(PAGE_ID);
    }

    public static long pageLsn(ByteBuffer page) {
        return page.getLong(PAGE_LSN);
    }

    public static void setPageLsn(ByteBuffer page, long lsn) {
        page.putLong(PAGE_LSN, lsn);
    }

    public static int aux(ByteBuffer page) {
        return page.getInt(AUX);
    }

    public static void setAux(ByteBuffer page, int value) {
        page.putInt(AUX, value);
    }

    public static int checksum(ByteBuffer page) {
        return page.getInt(CHECKSUM);
    }

    public static void setChecksum(ByteBuffer page, int crc) {
        page.putInt(CHECKSUM, crc);
    }

    static int getU16(ByteBuffer page, int offset) {
        return page.getChar(offset);
    }

    static void putU16(ByteBuffer page, int offset, int value) {
        page.putChar(offset, (char) value);
    }

    static void checkPageSize(int size) {
        if (!isValidPageSize(size)) {
            throw new IllegalArgumentException("page size must be a power of two from " + MIN_PAGE_SIZE + " to "
                    + MAX_PAGE_SIZE + ": " + size);
        }
    }
}
