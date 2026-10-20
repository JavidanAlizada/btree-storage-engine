package dev.storage.wal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.storage.buffer.LruReplacer;
import dev.storage.buffer.PageGuard;
import dev.storage.file.PageFile;
import dev.storage.file.StorageFailedException;
import dev.storage.page.CorruptPageException;
import dev.storage.page.PageHeader;
import dev.storage.page.PageType;
import dev.storage.page.SlottedPage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MtrTest {

    private static final int PAGE = 4096;

    @TempDir
    Path dir;

    private PageStore store;

    static PageStore.Config config(int frames, Durability durability) {
        return new PageStore.Config(PAGE, frames, LruReplacer::new, durability, Long.MAX_VALUE);
    }

    @BeforeEach
    void open() {
        store = PageStore.open(dir.resolve("db"), config(16, Durability.ASYNC));
    }

    @AfterEach
    void close() {
        store.close();
    }

    @Test
    void theRedoCheckIsOnInTests() {
        assertTrue(Mtr.REDO_CHECK, "run tests through Gradle, which sets dev.storage.redoCheck");
    }

    private int newLeaf() {
        try (Mtr mtr = store.begin()) {
            int id;
            try (PageGuard page = mtr.allocate()) {
                id = page.pageId();
                PageHeader.format(page.buffer(), id, PageType.LEAF);
                mtr.rewritten(page);
            }
            mtr.commit();
            return id;
        }
    }

    private List<List<LogRecord>> groups() throws IOException {
        store.wal().flush();
        List<List<LogRecord>> groups = new ArrayList<>();
        try (FileChannel c = FileChannel.open(PageStore.logPath(dir.resolve("db")), StandardOpenOption.READ,
                StandardOpenOption.WRITE)) {
            Wal.open(c, g -> groups.add(LogRecord.decode(g.body())));
        }
        return groups;
    }

    @Test
    void randomSlotChangesReplayFromTheLogToTheSameBytes() throws IOException {
        Random random = new Random(7);
        List<Integer> leaves = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            leaves.add(newLeaf());
        }
        for (int op = 0; op < 3_000; op++) {
            try (Mtr mtr = store.begin()) {
                for (int touch = 0; touch < 1 + random.nextInt(3); touch++) {
                    try (PageGuard page = mtr.fetchExclusive(leaves.get(random.nextInt(leaves.size())))) {
                        SlottedPage slotted = SlottedPage.wrap(page.buffer());
                        int count = slotted.slotCount();
                        if (count > 0 && random.nextInt(3) == 0) {
                            mtr.delete(page, random.nextInt(count));
                        } else if (random.nextInt(10) == 0) {
                            mtr.setAux(page, random.nextInt());
                        } else {
                            byte[] cell = new byte[1 + random.nextInt(300)];
                            random.nextBytes(cell);
                            if (!mtr.insert(page, random.nextInt(count + 1), cell)) {
                                mtr.delete(page, 0);
                            }
                        }
                    }
                }
                mtr.commit(); // the redo check runs here
            }
        }
        // Replay the whole log from blank pages, as recovery from an empty file would.
        Map<Integer, ByteBuffer> rebuilt = new HashMap<>();
        long lastLsn = 0;
        store.wal().flush();
        List<Long> ends = new ArrayList<>();
        try (FileChannel c = FileChannel.open(PageStore.logPath(dir.resolve("db")), StandardOpenOption.READ,
                StandardOpenOption.WRITE)) {
            Wal.open(c, g -> {
                ends.add(g.endLsn());
                for (LogRecord r : LogRecord.decode(g.body())) {
                    if (r instanceof LogRecord.PageRecord p) {
                        ByteBuffer page = rebuilt.computeIfAbsent(p.pageId(), id -> {
                            assertInstanceOf(LogRecord.Image.class, p, "page " + id + " starts with an image");
                            return ByteBuffer.allocate(PAGE);
                        });
                        p.apply(page);
                        PageHeader.setPageLsn(page, g.endLsn());
                    }
                }
            });
        }
        lastLsn = ends.get(ends.size() - 1);
        assertTrue(lastLsn > 0);
        for (int id : leaves) {
            try (PageGuard page = store.pool().fetch(id)) {
                page.latchShared();
                ByteBuffer live = page.buffer();
                ByteBuffer replayed = rebuilt.get(id);
                assertEquals(-1, replayed.slice(4, PAGE - 4).mismatch(live.slice(4, PAGE - 4)), "page " + id);
            }
        }
    }

    @Test
    void aPageGetsAnImageOnceThenDeltas() throws IOException {
        int leaf = newLeaf();
        try (Mtr mtr = store.begin()) {
            try (PageGuard page = mtr.fetchExclusive(leaf)) {
                mtr.insert(page, 0, new byte[] {1, 2, 3});
            }
            mtr.commit();
        }
        List<List<LogRecord>> groups = groups();
        List<LogRecord> first = groups.get(0);
        assertInstanceOf(LogRecord.Meta.class, first.get(0));
        assertInstanceOf(LogRecord.Image.class, first.get(1));
        List<LogRecord> second = groups.get(1);
        assertEquals(1, second.size());
        assertInstanceOf(LogRecord.Insert.class, second.get(0));
    }

    @Test
    void freedPagesAreReusedLastFreedFirstAndTheFileGrowsOnlyWhenTheListIsEmpty() {
        int a = newLeaf();
        int b = newLeaf();
        try (Mtr mtr = store.begin()) {
            try (PageGuard pa = mtr.fetchExclusive(a); PageGuard pb = mtr.fetchExclusive(b)) {
                mtr.free(pa);
                mtr.free(pb);
            }
            mtr.commit();
        }
        assertEquals(b, newLeaf());
        assertEquals(a, newLeaf());
        int c = newLeaf();
        assertEquals(Math.max(a, b) + 1, c);
        assertEquals(c + 1, store.file().pageCount());
    }

    @Test
    void anUncommittedChangeFailsTheStore() {
        int leaf = newLeaf();
        try (Mtr mtr = store.begin()) {
            try (PageGuard page = mtr.fetchExclusive(leaf)) {
                mtr.insert(page, 0, new byte[] {9});
            }
        }
        assertThrows(StorageFailedException.class, store::begin);
        // Nothing reaches the file after the failure.
        assertThrows(IllegalStateException.class, () -> store.pool().flushAll());
    }

    @Test
    void aMiniTransactionThatChangedNothingNeedsNoCommit() {
        int leaf = newLeaf();
        try (Mtr mtr = store.begin()) {
            mtr.fetchExclusive(leaf).close();
        }
        try (Mtr mtr = store.begin()) {
            assertEquals(-1, mtr.commit());
        }
        store.begin().close();
    }

    @Test
    void onlyPagesFetchedThroughTheMiniTransactionCanChange() {
        int leaf = newLeaf();
        try (Mtr mtr = store.begin(); PageGuard page = store.pool().fetch(leaf)) {
            page.latchExclusive();
            assertThrows(IllegalStateException.class, () -> mtr.insert(page, 0, new byte[] {1}));
            assertFalse(store.wal().size() == 0);
        }
    }

    @Test
    void evictionForcesTheLogBeforeAPageIsWritten() {
        // ASYNC, and nothing flushes the log explicitly: only the write-ahead rule on eviction can.
        List<Integer> leaves = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            leaves.add(newLeaf()); // more pages than the pool's 16 frames
        }
        long durable = store.wal().durableLsn();
        assertTrue(durable > 0, "eviction never flushed the log");
        PageFile file = store.file();
        ByteBuffer page = ByteBuffer.allocate(PAGE);
        int written = 0;
        for (int id : leaves) {
            if (Integer.toUnsignedLong(id) * PAGE < fileSize()) {
                page.clear();
                try {
                    file.read(id, page);
                } catch (CorruptPageException notWrittenYet) {
                    continue; // a hole: a later page was written first
                }
                assertTrue(PageHeader.pageLsn(page) <= durable, "page " + id + " is on disk ahead of its log");
                written++;
            }
        }
        assertTrue(written >= 20, "pages written " + written);
    }

    private long fileSize() {
        try {
            return java.nio.file.Files.size(dir.resolve("db"));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
