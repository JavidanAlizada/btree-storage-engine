package dev.storage.file;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.storage.page.PageHeader;
import dev.storage.page.PageType;
import dev.storage.page.SlottedPage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The on-disk format, pinned: a fixed sequence of operations must produce exactly the committed bytes, and the
 * committed file must still open and read back. If this fails, the format changed. That needs a new format version,
 * not a regenerated golden file.
 */
class GoldenFileTest {

    static final String RESOURCE = "/golden/format-v1-4k.db";

    @TempDir
    Path dir;

    /** The fixed sequence. Changing it means regenerating the resource, which is only right with a version bump. */
    static void writeGolden(Path path) {
        try (PageFile file = PageFile.create(path, 4096)) {
            int leaf = file.allocate();
            int internal = file.allocate();
            int spare = file.allocate();

            ByteBuffer page = ByteBuffer.allocate(4096);
            PageHeader.format(page, leaf, PageType.LEAF);
            PageHeader.setAux(page, 0);
            SlottedPage cells = SlottedPage.wrap(page);
            cells.insert(0, "alpha".getBytes(StandardCharsets.UTF_8));
            cells.insert(1, "beta".getBytes(StandardCharsets.UTF_8));
            file.write(leaf, page);

            PageHeader.format(page, internal, PageType.INTERNAL);
            PageHeader.setAux(page, leaf);
            SlottedPage.wrap(page).insert(0, "separator".getBytes(StandardCharsets.UTF_8));
            file.write(internal, page);

            file.free(spare);
        }
    }

    @Test
    void theSameOperationsProduceTheSameBytes() throws IOException {
        Path path = dir.resolve("db");
        writeGolden(path);
        assertArrayEquals(golden(), Files.readAllBytes(path));
    }

    @Test
    void theCommittedFileStillReads() throws IOException {
        Path path = dir.resolve("db");
        Files.write(path, golden());
        try (PageFile file = PageFile.open(path)) {
            assertEquals(4, file.pageCount());
            ByteBuffer page = ByteBuffer.allocate(4096);
            file.read(1, page);
            assertArrayEquals("beta".getBytes(StandardCharsets.UTF_8), SlottedPage.wrap(page).cellBytes(1));
            file.read(2, page);
            assertEquals(PageType.INTERNAL, PageHeader.type(page));
            assertEquals(1, PageHeader.aux(page));
            assertEquals(3, file.allocate()); // the freed page comes back first
        }
    }

    private static byte[] golden() throws IOException {
        try (InputStream in = GoldenFileTest.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is missing");
            }
            return in.readAllBytes();
        }
    }
}
