package dev.storage.page;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SlottedPageTest {

    private static final int PAGE = 8192;

    private static SlottedPage empty(int size) {
        ByteBuffer buffer = ByteBuffer.allocate(size);
        PageHeader.format(buffer, 3, PageType.LEAF);
        return SlottedPage.wrap(buffer);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] filled(int length, int value) {
        byte[] b = new byte[length];
        java.util.Arrays.fill(b, (byte) value);
        return b;
    }

    @Test
    void anEmptyPageHasEverythingButTheHeaderAndOneSlotFree() {
        SlottedPage page = empty(PAGE);
        assertEquals(0, page.slotCount());
        assertEquals(PAGE - PageHeader.SIZE - SlottedPage.SLOT_SIZE, page.freeSpace());
    }

    @Test
    void insertsKeepSlotOrderWhereverTheCellsLand() {
        SlottedPage page = empty(PAGE);
        assertTrue(page.insert(0, bytes("b")));
        assertTrue(page.insert(0, bytes("a")));
        assertTrue(page.insert(2, bytes("d")));
        assertTrue(page.insert(2, bytes("c")));

        assertEquals(4, page.slotCount());
        for (int i = 0; i < 4; i++) {
            assertArrayEquals(new byte[] {(byte) ('a' + i)}, page.cellBytes(i));
        }
        assertEquals(1, page.cellLength(2));
    }

    @Test
    void deleteShiftsLaterEntriesDown() {
        SlottedPage page = empty(PAGE);
        page.insert(0, bytes("one"));
        page.insert(1, bytes("two"));
        page.insert(2, bytes("three"));

        page.delete(1);

        assertEquals(2, page.slotCount());
        assertArrayEquals(bytes("one"), page.cellBytes(0));
        assertArrayEquals(bytes("three"), page.cellBytes(1));
    }

    @Test
    void deletingTheLowestCellReclaimsItWithoutFragmenting() {
        SlottedPage page = empty(PAGE);
        page.insert(0, filled(100, 1));
        int before = page.freeSpace();
        page.insert(1, filled(50, 2)); // lowest cell
        page.delete(1);
        assertEquals(before, page.freeSpace());
        assertEquals(0, PageHeader.getU16(page.buffer(), PageHeader.FRAGMENTED));
    }

    @Test
    void aFullPageRefusesTheInsertAndStaysUnchanged() {
        SlottedPage page = empty(4096);
        int max = SlottedPage.maxCellSize(4096);
        int n = 0;
        while (page.insert(n, filled(max, n))) {
            n++;
        }
        assertTrue(n >= 4, "a page holds at least 4 maximum-size cells, got " + n);
        byte[] snapshot = new byte[4096];
        page.buffer().get(0, snapshot);

        assertFalse(page.insert(0, filled(max, 99)));

        byte[] after = new byte[4096];
        page.buffer().get(0, after);
        assertArrayEquals(snapshot, after);
    }

    @Test
    void anInsertThatOnlyFitsInTheHolesCompactsFirst() {
        SlottedPage page = empty(4096);
        int max = SlottedPage.maxCellSize(4096);
        for (int i = 0; i < 4; i++) {
            assertTrue(page.insert(i, filled(max, i)));
        }
        page.delete(0);
        page.delete(1); // slot 1 is now the third cell: both are holes, not the lowest cell
        assertTrue(page.freeSpace() >= max);

        assertTrue(page.insert(0, filled(max, 7)));

        assertEquals(3, page.slotCount());
        assertArrayEquals(filled(max, 7), page.cellBytes(0));
        assertArrayEquals(filled(max, 1), page.cellBytes(1));
        assertArrayEquals(filled(max, 3), page.cellBytes(2));
    }

    @Test
    void compactionPreservesEveryCell() {
        SlottedPage page = empty(PAGE);
        for (int i = 0; i < 20; i++) {
            page.insert(i, filled(10 + i, i));
        }
        for (int i = 18; i >= 0; i -= 3) {
            page.delete(i);
        }
        int free = page.freeSpace();
        int count = page.slotCount();
        byte[][] cells = new byte[count][];
        for (int i = 0; i < count; i++) {
            cells[i] = page.cellBytes(i);
        }

        page.compact();

        assertEquals(free, page.freeSpace());
        assertEquals(0, PageHeader.getU16(page.buffer(), PageHeader.FRAGMENTED));
        for (int i = 0; i < count; i++) {
            assertArrayEquals(cells[i], page.cellBytes(i));
        }
    }

    @Test
    void emptyCellsAreAllowed() {
        SlottedPage page = empty(PAGE);
        assertTrue(page.insert(0, new byte[0]));
        assertEquals(0, page.cellLength(0));
        page.delete(0);
        assertEquals(0, page.slotCount());
    }

    @Test
    void anEmptyCellSurvivesItsNeighbourBeingReclaimed() {
        // Found by the model test: an empty cell at cellStart was left below it when the cell next to it went.
        SlottedPage page = empty(PAGE);
        page.insert(0, bytes("abc"));
        page.insert(1, new byte[0]);
        page.delete(0);
        assertEquals(0, page.cellLength(0));
        assertEquals(0, page.cell(0).remaining());
        page.compact();
        assertEquals(0, page.cellBytes(0).length);
    }

    @Test
    void cellsOverTheLimitAndBadIndexesAreRejected() {
        SlottedPage page = empty(PAGE);
        assertEquals(2036, SlottedPage.maxCellSize(PAGE));
        assertThrows(IllegalArgumentException.class, () -> page.insert(0, new byte[2037]));
        assertThrows(IndexOutOfBoundsException.class, () -> page.insert(1, bytes("x")));
        assertThrows(IndexOutOfBoundsException.class, () -> page.cell(0));
        assertThrows(IndexOutOfBoundsException.class, () -> page.delete(-1));
    }

    @Test
    void aSlotCountThatOverlapsTheCellsIsCorrupt() {
        SlottedPage page = empty(4096);
        page.insert(0, bytes("x"));
        PageHeader.putU16(page.buffer(), PageHeader.SLOT_COUNT, 2000);
        assertThrows(CorruptPageException.class, page::slotCount);
    }

    @Test
    void aSlotPointingOutsideTheCellAreaIsCorrupt() {
        SlottedPage page = empty(4096);
        page.insert(0, bytes("hello"));
        PageHeader.putU16(page.buffer(), PageHeader.SIZE, 4094); // offset 4094 + length 5 > 4096
        assertThrows(CorruptPageException.class, () -> page.cell(0));
        PageHeader.putU16(page.buffer(), PageHeader.SIZE, 10); // inside the header
        assertThrows(CorruptPageException.class, () -> page.cell(0));
    }

    @Test
    void aCellStartOutsideThePageIsCorrupt() {
        SlottedPage page = empty(4096);
        PageHeader.putU16(page.buffer(), PageHeader.CELL_START, 5000);
        assertThrows(CorruptPageException.class, page::freeSpace);
        PageHeader.putU16(page.buffer(), PageHeader.CELL_START, 8);
        assertThrows(CorruptPageException.class, page::slotCount);
    }

    @Test
    void aFragmentedCountLargerThanThePageIsCorrupt() {
        SlottedPage page = empty(4096);
        PageHeader.putU16(page.buffer(), PageHeader.FRAGMENTED, 60_000);
        assertThrows(CorruptPageException.class, page::freeSpace);
    }

    @Test
    void wrapRejectsBuffersThatAreNotAValidPageSize() {
        assertThrows(IllegalArgumentException.class, () -> SlottedPage.wrap(ByteBuffer.allocate(1000)));
    }
}
