package dev.storage.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

class PageHeaderTest {

    @Test
    void formatWritesAnEmptyHeaderAndZeroesTheRest() {
        ByteBuffer page = ByteBuffer.allocate(8192);
        for (int i = 0; i < page.capacity(); i++) {
            page.put(i, (byte) 0x5A);
        }

        PageHeader.format(page, 7, PageType.LEAF);

        assertEquals(PageType.LEAF, PageHeader.type(page));
        assertEquals(7, PageHeader.pageId(page));
        assertEquals(0, PageHeader.pageLsn(page));
        assertEquals(0, PageHeader.aux(page));
        assertEquals(0, PageHeader.checksum(page));
        assertEquals(8192, PageHeader.getU16(page, PageHeader.CELL_START));
        for (int i = PageHeader.SIZE; i < page.capacity(); i++) {
            assertEquals(0, page.get(i), "byte " + i);
        }
    }

    @Test
    void fieldsRoundTripAtTheirDocumentedOffsets() {
        ByteBuffer page = ByteBuffer.allocate(4096);
        PageHeader.format(page, -1, PageType.FREE); // 2^32 - 1, the largest page id

        PageHeader.setPageLsn(page, 0x0102030405060708L);
        PageHeader.setAux(page, 0xCAFEBABE);
        PageHeader.setChecksum(page, 0x11223344);
        PageHeader.setType(page, PageType.INTERNAL);

        assertEquals(0x11223344, page.getInt(0));
        assertEquals(0x0102030405060708L, page.getLong(4));
        assertEquals(3, page.get(12));
        assertEquals(-1, page.getInt(16));
        assertEquals(0xCAFEBABE, page.getInt(28));
        assertEquals("4294967295", Integer.toUnsignedString(PageHeader.pageId(page)));
    }

    @Test
    void anUnknownTypeIsCorrupt() {
        ByteBuffer page = ByteBuffer.allocate(4096);
        PageHeader.format(page, 1, PageType.LEAF);
        page.put(PageHeader.TYPE, (byte) 9);
        assertThrows(CorruptPageException.class, () -> PageHeader.type(page));
    }

    @Test
    void pageSizesArePowersOfTwoFrom4To32KiB() {
        assertTrue(PageHeader.isValidPageSize(4096));
        assertTrue(PageHeader.isValidPageSize(8192));
        assertTrue(PageHeader.isValidPageSize(32768));
        assertFalse(PageHeader.isValidPageSize(2048));
        assertFalse(PageHeader.isValidPageSize(65536));
        assertFalse(PageHeader.isValidPageSize(12288));
        assertThrows(IllegalArgumentException.class,
                () -> PageHeader.format(ByteBuffer.allocate(65536), 1, PageType.LEAF));
    }
}
