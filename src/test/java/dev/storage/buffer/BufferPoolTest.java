package dev.storage.buffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.storage.file.PageFile;
import dev.storage.page.PageHeader;
import dev.storage.page.PageType;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BufferPoolTest {

    private static final int PAGE = 4096;

    @TempDir
    Path dir;

    private PageFile file;

    @BeforeEach
    void open() {
        file = PageFile.create(dir.resolve("db"), PAGE);
    }

    @AfterEach
    void closeFile() {
        file.close();
    }

    // A new LEAF page whose aux word holds value.
    private static int newLeaf(BufferPool pool, int value) {
        try (PageGuard page = pool.newPage()) {
            page.latchExclusive();
            PageHeader.format(page.buffer(), page.pageId(), PageType.LEAF);
            PageHeader.setAux(page.buffer(), value);
            page.markDirty();
            return page.pageId();
        }
    }

    private static int read(BufferPool pool, int id) {
        try (PageGuard page = pool.fetch(id)) {
            page.latchShared();
            return PageHeader.aux(page.buffer());
        }
    }

    @Test
    void aCachedPageIsAHitAndAnUncachedOneAMiss() {
        try (BufferPool pool = new BufferPool(file, 4, new LruReplacer(4))) {
            int id = newLeaf(pool, 42);
            assertEquals(42, read(pool, id));
            assertEquals(42, read(pool, id));
            BufferPool.Stats stats = pool.stats();
            assertEquals(1, stats.misses()); // newPage
            assertEquals(2, stats.hits());
        }
    }

    @Test
    void dirtyPagesSurviveEvictionAndReopen() {
        int[] ids = new int[20];
        try (BufferPool pool = new BufferPool(file, 3, new ClockReplacer(3))) {
            for (int i = 0; i < ids.length; i++) {
                ids[i] = newLeaf(pool, 1000 + i);
            }
            for (int i = 0; i < ids.length; i++) {
                assertEquals(1000 + i, read(pool, ids[i]));
            }
            assertTrue(pool.stats().writeBacks() >= ids.length - 3);
        }
        file.close();
        file = PageFile.open(dir.resolve("db"));
        try (BufferPool pool = new BufferPool(file, 2, new LruReplacer(2))) {
            for (int i = 0; i < ids.length; i++) {
                assertEquals(1000 + i, read(pool, ids[i]));
            }
        }
    }

    @Test
    void lruEvictsTheLeastRecentlyReleasedPage() {
        try (BufferPool pool = new BufferPool(file, 2, new LruReplacer(2))) {
            int a = newLeaf(pool, 1);
            int b = newLeaf(pool, 2);
            read(pool, a); // b is now least recently released
            int c = newLeaf(pool, 3); // evicts b
            long misses = pool.stats().misses();
            read(pool, a);
            assertEquals(misses, pool.stats().misses(), "a should still be cached");
            read(pool, b);
            assertEquals(misses + 1, pool.stats().misses(), "b should have been evicted");
            assertEquals(3, read(pool, c));
        }
    }

    @Test
    void twoPinsOfOnePageShareItsFrame() {
        try (BufferPool pool = new BufferPool(file, 2, new LruReplacer(2))) {
            int id = newLeaf(pool, 5);
            try (PageGuard one = pool.fetch(id); PageGuard two = pool.fetch(id)) {
                one.latchShared();
                two.latchShared(); // shared latches don't exclude each other
                assertSame(one.buffer(), two.buffer());
            }
        }
    }

    @Test
    void aPoolWithEveryFramePinnedFailsFast() {
        try (BufferPool pool = new BufferPool(file, 2, new LruReplacer(2))) {
            int a = newLeaf(pool, 1);
            int b = newLeaf(pool, 2);
            int c = newLeaf(pool, 3);
            try (PageGuard pa = pool.fetch(a); PageGuard pb = pool.fetch(b)) {
                assertThrows(BufferPoolFullException.class, () -> pool.fetch(c));
            }
            assertEquals(3, read(pool, c));
        }
    }

    @Test
    void closeReportsALeakedPin() {
        BufferPool pool = new BufferPool(file, 2, new LruReplacer(2));
        int id = newLeaf(pool, 1);
        PageGuard leaked = pool.fetch(id);
        var e = assertThrows(IllegalStateException.class, pool::close);
        assertTrue(e.getMessage().contains("leaked"), e.getMessage());
        leaked.close();
        pool.close();
    }

    @Test
    void freeNeedsTheOnlyPinAndHandsThePageBackToTheFile() {
        try (BufferPool pool = new BufferPool(file, 4, new LruReplacer(4))) {
            int id = newLeaf(pool, 9);
            try (PageGuard other = pool.fetch(id)) {
                PageGuard mine = pool.fetch(id);
                assertThrows(IllegalStateException.class, () -> pool.free(mine));
                mine.close();
            }
            PageGuard only = pool.fetch(id);
            pool.free(only);
            only.close(); // already released by free: a no-op
            try (PageGuard reused = pool.newPage()) {
                assertEquals(id, reused.pageId());
                reused.latchShared();
                assertEquals(PageType.FREE, PageHeader.type(reused.buffer()));
            }
        }
    }

    @Test
    void theBytesNeedALatchAndChangesNeedAnExclusiveOne() {
        try (BufferPool pool = new BufferPool(file, 2, new LruReplacer(2))) {
            int id = newLeaf(pool, 1);
            try (PageGuard page = pool.fetch(id)) {
                assertThrows(IllegalStateException.class, page::buffer);
                page.latchShared();
                assertThrows(IllegalStateException.class, page::markDirty);
                assertThrows(IllegalStateException.class, page::latchExclusive);
                page.unlatch();
                page.latchExclusive();
                page.markDirty();
            }
        }
    }

    @Test
    void theMetaPageAndMissingPagesCantBeFetched() {
        try (BufferPool pool = new BufferPool(file, 2, new LruReplacer(2))) {
            assertThrows(IllegalArgumentException.class, () -> pool.fetch(0));
            assertThrows(IllegalArgumentException.class, () -> pool.fetch(5));
        }
    }

    @Test
    void flushWritesWithoutEvicting() {
        try (BufferPool pool = new BufferPool(file, 2, new LruReplacer(2))) {
            int id = newLeaf(pool, 77);
            pool.flush(id);
            ByteBuffer onDisk = ByteBuffer.allocate(PAGE);
            file.read(id, onDisk);
            assertEquals(77, PageHeader.aux(onDisk));
            assertEquals(1, pool.stats().writeBacks());
            pool.flush(id); // clean now: nothing to write
            assertEquals(1, pool.stats().writeBacks());
        }
    }
}
