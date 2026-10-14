package dev.storage.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.storage.page.CorruptPageException;
import dev.storage.page.PageHeader;
import dev.storage.page.PageType;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FreeListTest {

    private static final int PAGE = 4096;

    @TempDir
    Path dir;

    @Test
    void freedPagesAreReusedMostRecentFirstBeforeTheFileGrows() {
        try (PageFile file = PageFile.create(dir.resolve("db"), PAGE)) {
            for (int i = 0; i < 4; i++) {
                file.allocate();
            }
            file.free(2);
            file.free(4);

            assertEquals(4, file.allocate());
            assertEquals(2, file.allocate());
            assertEquals(5, file.allocate());
            assertEquals(6, file.pageCount());
        }
    }

    @Test
    void aFreedPageReadsBackAsFreeAndLinksToTheNext() {
        try (PageFile file = PageFile.create(dir.resolve("db"), PAGE)) {
            file.allocate();
            file.allocate();
            file.free(1);
            file.free(2);
            ByteBuffer page = ByteBuffer.allocate(PAGE);
            file.read(2, page);
            assertEquals(PageType.FREE, PageHeader.type(page));
            assertEquals(1, PageHeader.aux(page));
            file.read(1, page);
            assertEquals(0, PageHeader.aux(page));
        }
    }

    @Test
    void theFreeListSurvivesReopen() {
        Path path = dir.resolve("db");
        try (PageFile file = PageFile.create(path, PAGE)) {
            for (int i = 0; i < 3; i++) {
                file.allocate();
            }
            file.free(1);
            file.free(3);
        }
        try (PageFile file = PageFile.open(path)) {
            assertEquals(3, file.allocate());
            assertEquals(1, file.allocate());
            assertEquals(4, file.allocate());
        }
    }

    @Test
    void theMetaPageAndMissingPagesCantBeFreed() {
        try (PageFile file = PageFile.create(dir.resolve("db"), PAGE)) {
            file.allocate();
            assertThrows(IllegalArgumentException.class, () -> file.free(0));
            assertThrows(IllegalArgumentException.class, () -> file.free(2));
        }
    }

    @Test
    void aBrokenLinkIsCorruptNotFollowed() throws IOException {
        Path path = dir.resolve("db");
        try (PageFile file = PageFile.create(path, PAGE)) {
            file.allocate();
            file.allocate();
            file.free(2);
        }
        // Overwrite page 2 with a validly checksummed page that isn't free.
        try (PageFile file = PageFile.open(path)) {
            ByteBuffer leaf = ByteBuffer.allocate(PAGE);
            PageHeader.format(leaf, 2, PageType.LEAF);
            file.write(2, leaf);
            var e = assertThrows(CorruptPageException.class, file::allocate);
            assertTrue(e.getMessage().contains("not a free one"), e.getMessage());
        }
        // A head pointing past the end of the file.
        try (var raf = new RandomAccessFile(path.toFile(), "rw")) {
            raf.seek(48);
            raf.writeInt(99);
        }
        assertThrows(CorruptPageException.class, () -> PageFile.open(path)); // the meta checksum catches it first
    }

    @Test
    void randomAllocateAndFreeNeverHandsOutAPageTwice() {
        Path path = dir.resolve("db");
        var random = new Random(7);
        Set<Integer> inUse = new HashSet<>();
        List<Integer> order = new ArrayList<>();
        try (PageFile file = PageFile.create(path, PAGE)) {
            for (int step = 0; step < 3_000; step++) {
                if (inUse.isEmpty() || random.nextInt(3) > 0) {
                    int id = file.allocate();
                    assertTrue(id != 0 && inUse.add(id), "page " + id + " handed out twice at step " + step);
                    order.add(id);
                } else {
                    Integer id = order.remove(random.nextInt(order.size()));
                    inUse.remove(id);
                    file.free(id);
                }
            }
            // Every page is either in use or on the free list: the file never grew past what was needed.
            int free = file.pageCount() - 1 - inUse.size();
            for (int i = 0; i < free; i++) {
                assertTrue(inUse.add(file.allocate()));
            }
            assertEquals(file.pageCount() - 1, inUse.size());
            int next = file.allocate();
            assertEquals(file.pageCount() - 1, next);
        }
    }
}
