package dev.storage.wal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.storage.buffer.PageGuard;
import dev.storage.file.PageFile;
import dev.storage.file.StorageFormatException;
import dev.storage.page.PageHeader;
import dev.storage.page.PageType;
import dev.storage.page.SlottedPage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Pages changed through mini-transactions, a crash (files closed with nothing more written), then recovery. */
class RecoveryTest {

    private static final int PAGE = 4096;

    @TempDir
    Path dir;

    private Path path() {
        return dir.resolve("db");
    }

    private PageStore open(Durability durability) {
        return PageStore.open(path(), MtrTest.config(16, durability));
    }

    // What each page should hold: its cells in slot order, and its aux word.
    private final Map<Integer, List<byte[]>> cells = new HashMap<>();
    private final Map<Integer, Integer> aux = new HashMap<>();

    private int newLeaf(PageStore store) {
        try (Mtr mtr = store.begin()) {
            int id;
            try (PageGuard page = mtr.allocate()) {
                id = page.pageId();
                PageHeader.format(page.buffer(), id, PageType.LEAF);
                mtr.rewritten(page);
            }
            mtr.commit();
            cells.put(id, new ArrayList<>());
            aux.put(id, 0);
            return id;
        }
    }

    private void randomChanges(PageStore store, Random random, List<Integer> pages, int ops) {
        for (int op = 0; op < ops; op++) {
            try (Mtr mtr = store.begin()) {
                for (int touch = 0; touch < 1 + random.nextInt(3); touch++) {
                    int id = pages.get(random.nextInt(pages.size()));
                    List<byte[]> model = cells.get(id);
                    try (PageGuard page = mtr.fetchExclusive(id)) {
                        if (!model.isEmpty() && random.nextInt(3) == 0) {
                            int slot = random.nextInt(model.size());
                            mtr.delete(page, slot);
                            model.remove(slot);
                        } else if (random.nextInt(8) == 0) {
                            int value = random.nextInt();
                            mtr.setAux(page, value);
                            aux.put(id, value);
                        } else {
                            byte[] cell = new byte[1 + random.nextInt(400)];
                            random.nextBytes(cell);
                            int slot = random.nextInt(model.size() + 1);
                            if (mtr.insert(page, slot, cell)) {
                                model.add(slot, cell);
                            }
                        }
                    }
                }
                mtr.commit();
            }
        }
    }

    private void assertPages(PageStore store) {
        for (Map.Entry<Integer, List<byte[]>> e : cells.entrySet()) {
            try (PageGuard page = store.pool().fetch(e.getKey())) {
                page.latchShared();
                SlottedPage slotted = SlottedPage.wrap(page.buffer());
                List<byte[]> want = e.getValue();
                assertEquals(want.size(), slotted.slotCount(), "slots in page " + e.getKey());
                for (int i = 0; i < want.size(); i++) {
                    assertArrayEquals(want.get(i), slotted.cellBytes(i), "page " + e.getKey() + " slot " + i);
                }
                assertEquals(aux.get(e.getKey()), PageHeader.aux(page.buffer()), "aux of page " + e.getKey());
            }
        }
    }

    @Test
    void recoveryRedoesEverythingAfterTheLastCheckpoint() {
        Random random = new Random(11);
        List<Integer> pages = new ArrayList<>();
        PageStore store = open(Durability.SYNC);
        for (int i = 0; i < 30; i++) {
            pages.add(newLeaf(store)); // more than the 16 frames, so evictions write pages ahead of the crash
        }
        randomChanges(store, random, pages, 1_500);
        store.checkpoint();
        assertEquals(0, store.logSize());
        randomChanges(store, random, pages, 1_500);
        assertTrue(store.logSize() > 0);
        store.crash();

        try (PageStore recovered = open(Durability.SYNC)) {
            assertEquals(0, recovered.logSize(), "recovery ends with a checkpoint");
            assertPages(recovered);
            randomChanges(recovered, random, pages, 200); // and it carries on normally
            assertPages(recovered);
        }
        try (PageStore reopened = open(Durability.SYNC)) {
            assertPages(reopened);
        }
    }

    @Test
    void freeListAndPageCountAreRecoveredFromTheLog() {
        PageStore store = open(Durability.SYNC);
        List<Integer> pages = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            pages.add(newLeaf(store));
        }
        store.checkpoint();
        try (Mtr mtr = store.begin()) {
            for (int id : List.of(pages.get(2), pages.get(5))) {
                try (PageGuard page = mtr.fetchExclusive(id)) {
                    mtr.free(page);
                }
                cells.remove(id);
            }
            mtr.commit();
        }
        int grown = newLeaf(store); // reuses page 5's slot, the last freed
        assertEquals(pages.get(5), grown);
        store.crash();

        try (PageStore recovered = open(Durability.SYNC)) {
            assertEquals(pages.get(2), newLeaf(recovered));
            assertEquals(pages.get(9) + 1, newLeaf(recovered)); // the list is empty again: the file grows
            assertPages(recovered);
        }
    }

    @Test
    void aTornPageIsRebuiltFromItsImage() throws IOException {
        PageStore store = open(Durability.SYNC);
        List<Integer> pages = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            pages.add(newLeaf(store));
        }
        randomChanges(store, new Random(3), pages, 50);
        store.checkpoint();
        // The first change after the checkpoint logs an image of the page.
        randomChanges(store, new Random(4), List.of(pages.get(1)), 20);
        store.pool().flushAll(); // the page reaches the file...
        store.crash();
        // ...and that write tore: only the first 512 bytes made it.
        try (FileChannel c = FileChannel.open(path(), StandardOpenOption.WRITE)) {
            c.write(ByteBuffer.allocate(PAGE - 512), (long) pages.get(1) * PAGE + 512);
        }
        try (PageFile file = PageFile.open(path())) {
            ByteBuffer page = ByteBuffer.allocate(PAGE);
            assertThrows(RuntimeException.class, () -> file.read(pages.get(1), page), "the page is torn");
        }
        try (PageStore recovered = open(Durability.SYNC)) {
            assertPages(recovered);
        }
    }

    @Test
    void asyncChangesSurviveAProcessCrashBecauseTheOsKeepsTheLog() {
        // A killed process loses nothing the OS has; a power cut could (the crash tests cover that).
        PageStore store = open(Durability.ASYNC);
        List<Integer> pages = new ArrayList<>(List.of(newLeaf(store), newLeaf(store)));
        randomChanges(store, new Random(5), pages, 300);
        store.crash();
        try (PageStore recovered = open(Durability.ASYNC)) {
            assertPages(recovered);
        }
    }

    @Test
    void aCleanCloseLeavesNothingToRedo() {
        List<Integer> pages = new ArrayList<>();
        try (PageStore store = open(Durability.SYNC)) {
            pages.add(newLeaf(store));
            randomChanges(store, new Random(6), pages, 100);
        }
        try (PageFile file = PageFile.open(path())) {
            assertTrue(file.checkpointLsn() > 0);
        }
        try (PageStore store = open(Durability.SYNC)) {
            assertEquals(0, store.logSize());
            assertPages(store);
        }
    }

    @Test
    void theLogPassingItsLimitTriggersACheckpoint() {
        PageStore.Config small = new PageStore.Config(PAGE, 16, dev.storage.buffer.LruReplacer::new,
                Durability.ASYNC, 32 * 1024);
        try (PageStore store = PageStore.open(path(), small)) {
            List<Integer> pages = new ArrayList<>(List.of(newLeaf(store), newLeaf(store), newLeaf(store)));
            long checkpoints = 0;
            long last = -1;
            Random random = new Random(8);
            for (int i = 0; i < 2_000; i++) {
                randomChanges(store, random, pages, 1);
                store.maybeCheckpoint();
                assertTrue(store.logSize() < 32 * 1024 + 4 * PAGE, "log " + store.logSize());
                long now = store.redoStart();
                if (now != last) {
                    checkpoints++;
                    last = now;
                }
            }
            assertTrue(checkpoints > 5, "checkpoints " + checkpoints);
            assertPages(store);
        }
    }

    @Test
    void aMissingLogAfterACleanCloseIsStartedAgain() throws IOException {
        List<Integer> pages = new ArrayList<>();
        try (PageStore store = open(Durability.SYNC)) {
            pages.add(newLeaf(store));
            randomChanges(store, new Random(9), pages, 50);
        }
        Files.delete(PageStore.logPath(path()));
        try (PageStore store = open(Durability.SYNC)) {
            assertPages(store);
            randomChanges(store, new Random(10), pages, 50);
        }
        try (PageStore store = open(Durability.SYNC)) {
            assertPages(store);
        }
    }

    @Test
    void aLogThatStartsAfterTheCheckpointIsRejected() throws IOException {
        try (PageStore store = open(Durability.SYNC)) {
            newLeaf(store);
        }
        Path log = PageStore.logPath(path());
        Files.delete(log);
        Wal.create(FileChannel.open(log, StandardOpenOption.READ, StandardOpenOption.WRITE,
                StandardOpenOption.CREATE_NEW), 1L << 40).close();
        assertThrows(StorageFormatException.class, () -> open(Durability.SYNC));
    }
}
