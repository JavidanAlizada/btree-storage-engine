package dev.storage.file;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.storage.page.CorruptPageException;
import dev.storage.page.PageHeader;
import dev.storage.page.PageType;
import dev.storage.page.SlottedPage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PageFileTest {

    private static final int PAGE = 8192;

    @TempDir
    Path dir;

    private static ByteBuffer leaf(int id, String text) {
        ByteBuffer page = ByteBuffer.allocate(PAGE);
        PageHeader.format(page, id, PageType.LEAF);
        SlottedPage.wrap(page).insert(0, text.getBytes(StandardCharsets.UTF_8));
        return page;
    }

    private static String text(ByteBuffer page) {
        return new String(SlottedPage.wrap(page).cellBytes(0), StandardCharsets.UTF_8);
    }

    private void corrupt(Path path, long position, byte[] bytes) throws IOException {
        try (var raf = new RandomAccessFile(path.toFile(), "rw")) {
            raf.seek(position);
            raf.write(bytes);
        }
    }

    @Test
    void pagesSurviveCloseAndReopen() {
        Path path = dir.resolve("db");
        try (PageFile file = PageFile.create(path, PAGE)) {
            assertEquals(1, file.pageCount());
            int a = file.allocate();
            int b = file.allocate();
            assertEquals(1, a);
            assertEquals(2, b);
            file.write(a, leaf(a, "alpha"));
            file.write(b, leaf(b, "beta"));
        }
        try (PageFile file = PageFile.open(path)) {
            assertEquals(PAGE, file.pageSize());
            assertEquals(3, file.pageCount());
            ByteBuffer page = ByteBuffer.allocateDirect(PAGE);
            file.read(2, page);
            assertEquals("beta", text(page));
            file.read(1, page);
            assertEquals("alpha", text(page));
        }
    }

    @Test
    void aNewPageReadsBackAsAFormattedFreePage() {
        try (PageFile file = PageFile.create(dir.resolve("db"), 4096)) {
            int id = file.allocate();
            ByteBuffer page = ByteBuffer.allocate(4096);
            file.read(id, page);
            assertEquals(PageType.FREE, PageHeader.type(page));
            assertEquals(id, PageHeader.pageId(page));
        }
    }

    @Test
    void createRefusesAnExistingFile() throws IOException {
        Path path = Files.createFile(dir.resolve("db"));
        assertThrows(UncheckedIOException.class, () -> PageFile.create(path, PAGE));
        assertThrows(IllegalArgumentException.class, () -> PageFile.create(dir.resolve("x"), 1000));
    }

    @Test
    void aFlippedByteFailsTheChecksum() throws IOException {
        Path path = dir.resolve("db");
        try (PageFile file = PageFile.create(path, PAGE)) {
            int id = file.allocate();
            file.write(id, leaf(id, "hello"));
        }
        corrupt(path, PAGE + 5000L, new byte[] {0x7F});
        try (PageFile file = PageFile.open(path)) {
            var e = assertThrows(CorruptPageException.class, () -> file.read(1, ByteBuffer.allocate(PAGE)));
            assertTrue(e.getMessage().contains("checksum"), e.getMessage());
        }
    }

    @Test
    void anAllZeroPageIsCorrupt() throws IOException {
        Path path = dir.resolve("db");
        try (PageFile file = PageFile.create(path, PAGE)) {
            file.allocate();
        }
        corrupt(path, PAGE, new byte[PAGE]);
        try (PageFile file = PageFile.open(path)) {
            var e = assertThrows(CorruptPageException.class, () -> file.read(1, ByteBuffer.allocate(PAGE)));
            assertTrue(e.getMessage().contains("all zeros"), e.getMessage());
        }
    }

    @Test
    void aPageWrittenToTheWrongPlaceIsCaughtByItsId() throws IOException {
        Path path = dir.resolve("db");
        try (PageFile file = PageFile.create(path, PAGE)) {
            int a = file.allocate();
            int b = file.allocate();
            file.write(a, leaf(a, "a"));
            file.write(b, leaf(b, "b"));
        }
        byte[] pageTwo = new byte[PAGE];
        try (var raf = new RandomAccessFile(path.toFile(), "r")) {
            raf.seek(2L * PAGE);
            raf.readFully(pageTwo);
        }
        corrupt(path, PAGE, pageTwo); // a valid page, in the wrong slot
        try (PageFile file = PageFile.open(path)) {
            var e = assertThrows(CorruptPageException.class, () -> file.read(1, ByteBuffer.allocate(PAGE)));
            assertTrue(e.getMessage().contains("misdirected"), e.getMessage());
        }
    }

    @Test
    void openChecksMagicVersionPageSizeAndLength() throws IOException {
        Path path = dir.resolve("db");
        try (PageFile file = PageFile.create(path, PAGE)) {
            file.allocate();
            file.allocate();
        }
        byte[] original = Files.readAllBytes(path);

        corrupt(path, 32, "NOPE".getBytes(StandardCharsets.US_ASCII));
        assertThrows(StorageFormatException.class, () -> PageFile.open(path));

        Files.write(path, original);
        corrupt(path, 36, new byte[] {0, 2});
        var e = assertThrows(StorageFormatException.class, () -> PageFile.open(path));
        assertTrue(e.getMessage().contains("version 2"), e.getMessage());

        Files.write(path, original);
        corrupt(path, 40, new byte[] {0, 0, 0x30, 0}); // 12 KiB, not a power of two
        assertThrows(StorageFormatException.class, () -> PageFile.open(path));

        Files.write(path, original);
        corrupt(path, 48, new byte[] {1}); // a meta field changed without a new checksum
        assertThrows(CorruptPageException.class, () -> PageFile.open(path));

        Files.write(path, java.util.Arrays.copyOf(original, original.length - 100));
        assertThrows(CorruptPageException.class, () -> PageFile.open(path));

        Files.write(path, java.util.Arrays.copyOf(original, original.length + PAGE)); // leaked tail: opens
        try (PageFile file = PageFile.open(path)) {
            assertEquals(3, file.pageCount());
        }
    }

    @Test
    void aFileThatIsNotADatabaseIsRejected() throws IOException {
        Path path = dir.resolve("notes.txt");
        Files.writeString(path, "GET / HTTP/1.1\r\n".repeat(10));
        assertThrows(StorageFormatException.class, () -> PageFile.open(path));
        Files.writeString(path, "tiny");
        assertThrows(CorruptPageException.class, () -> PageFile.open(path));
    }

    @Test
    void badArgumentsAreRejected() {
        try (PageFile file = PageFile.create(dir.resolve("db"), PAGE)) {
            int id = file.allocate();
            assertThrows(IllegalArgumentException.class, () -> file.write(0, leaf(0, "meta")));
            assertThrows(IllegalArgumentException.class, () -> file.read(2, ByteBuffer.allocate(PAGE)));
            assertThrows(IllegalArgumentException.class, () -> file.read(-1, ByteBuffer.allocate(PAGE)));
            assertThrows(IllegalArgumentException.class, () -> file.read(id, ByteBuffer.allocate(4096)));
        }
    }

    @Test
    void differentPagesCanBeReadAndWrittenConcurrently() throws Exception {
        int pages = 64;
        try (PageFile file = PageFile.create(dir.resolve("db"), 4096);
                ExecutorService pool = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < pages; i++) {
                file.allocate();
            }
            List<Future<?>> tasks = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                int thread = t;
                tasks.add(pool.submit(() -> {
                    ByteBuffer page = ByteBuffer.allocateDirect(4096);
                    // Each thread owns the pages congruent to its number, so every page has one writer.
                    for (int round = 0; round < 50; round++) {
                        for (int id = 1 + thread; id <= pages; id += 8) {
                            PageHeader.format(page, id, PageType.LEAF);
                            SlottedPage.wrap(page).insert(0, (id + ":" + round).getBytes(StandardCharsets.UTF_8));
                            file.write(id, page);
                            file.read(id, page);
                            assertEquals(id + ":" + round,
                                    new String(SlottedPage.wrap(page).cellBytes(0), StandardCharsets.UTF_8));
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> task : tasks) {
                task.get(60, TimeUnit.SECONDS);
            }
            ByteBuffer page = ByteBuffer.allocate(4096);
            for (int id = 1; id <= pages; id++) {
                file.read(id, page);
                assertArrayEquals((id + ":49").getBytes(StandardCharsets.UTF_8),
                        SlottedPage.wrap(page).cellBytes(0));
            }
        }
    }
}
