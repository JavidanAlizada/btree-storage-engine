package dev.storage.page;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Random inserts, deletes and compactions checked against a List after every step. Seeded, so a failure names the
 * seed and step to replay.
 */
class SlottedPageModelTest {

    private static final int STEPS = 20_000;

    @ParameterizedTest
    @ValueSource(ints = {4096, 8192, 32768})
    void matchesAListModel(int pageSize) {
        for (long seed = 1; seed <= 5; seed++) {
            run(pageSize, seed);
        }
    }

    private static void run(int pageSize, long seed) {
        var random = new Random(seed);
        ByteBuffer buffer = ByteBuffer.allocateDirect(pageSize);
        PageHeader.format(buffer, 1, PageType.LEAF);
        SlottedPage page = SlottedPage.wrap(buffer);
        List<byte[]> model = new ArrayList<>();
        int maxCell = SlottedPage.maxCellSize(pageSize);

        for (int step = 0; step < STEPS; step++) {
            String where = "page " + pageSize + ", seed " + seed + ", step " + step;
            int op = random.nextInt(10);
            if (op < 6) {
                // Mostly small cells, sometimes large ones, so the page fills and fragments.
                int length = random.nextInt(8) == 0 ? random.nextInt(maxCell + 1) : random.nextInt(64);
                byte[] cell = new byte[length];
                random.nextBytes(cell);
                int index = random.nextInt(model.size() + 1);
                boolean fits = usedBytes(model) + length + SlottedPage.SLOT_SIZE
                        <= pageSize - PageHeader.SIZE;
                assertEquals(fits, page.insert(index, cell), where);
                if (fits) {
                    model.add(index, cell);
                }
            } else if (op < 9) {
                if (!model.isEmpty()) {
                    int index = random.nextInt(model.size());
                    page.delete(index);
                    model.remove(index);
                }
            } else {
                page.compact();
            }
            check(page, model, pageSize, where);
        }
    }

    private static void check(SlottedPage page, List<byte[]> model, int pageSize, String where) {
        assertEquals(model.size(), page.slotCount(), where);
        for (int i = 0; i < model.size(); i++) {
            assertArrayEquals(model.get(i), page.cellBytes(i), where + ", slot " + i);
        }
        // Free space is exact: lazy compaction may defer reclaiming holes, never lose them.
        int expected = Math.max(0, pageSize - PageHeader.SIZE - usedBytes(model) - SlottedPage.SLOT_SIZE);
        assertEquals(expected, page.freeSpace(), where);
        assertTrue(page.slotCount() * SlottedPage.SLOT_SIZE + PageHeader.SIZE <= pageSize, where);
        assertFalse(page.freeSpace() < 0, where);
    }

    private static int usedBytes(List<byte[]> model) {
        int used = 0;
        for (byte[] cell : model) {
            used += cell.length + SlottedPage.SLOT_SIZE;
        }
        return used;
    }
}
