package dev.storage.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.storage.page.CorruptPageException;
import dev.storage.page.PageHeader;
import dev.storage.page.PageType;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FaultInjectionTest {

    private static final int PAGE = 4096;

    @TempDir
    Path dir;

    @Test
    void afterAFailedFsyncEveryCallFailsUntilReopened() throws Exception {
        Path path = dir.resolve("db");
        FaultyFile channel = FaultyFile.open(path, true);
        PageFile file = PageFile.create(channel, PAGE);
        int id = file.allocate();

        channel.failFsync = true;
        var first = assertThrows(StorageFailedException.class, file::sync);

        channel.failFsync = false; // a retry might "succeed", which is exactly what must not be trusted
        var again = assertThrows(StorageFailedException.class, file::sync);
        assertSame(first.getCause(), again.getCause());
        assertThrows(StorageFailedException.class, () -> file.read(id, ByteBuffer.allocate(PAGE)));
        assertThrows(StorageFailedException.class, () -> file.write(id, ByteBuffer.allocate(PAGE)));
        assertThrows(StorageFailedException.class, file::allocate);
        int calls = channel.fsyncCalls.get();
        file.close(); // closes without another fsync
        assertEquals(calls, channel.fsyncCalls.get());

        try (PageFile reopened = PageFile.open(path)) {
            reopened.read(0, ByteBuffer.allocate(PAGE));
        }
    }

    @Test
    void aWriteThatLandsWrongIsCaughtOnTheNextRead() throws Exception {
        Path path = dir.resolve("db");
        FaultyFile channel = FaultyFile.open(path, true);
        try (PageFile file = PageFile.create(channel, PAGE)) {
            int id = file.allocate();
            ByteBuffer page = ByteBuffer.allocate(PAGE);
            PageHeader.format(page, id, PageType.LEAF);
            channel.flipNextWrite = true;
            file.write(id, page);
            assertThrows(CorruptPageException.class, () -> file.read(id, ByteBuffer.allocate(PAGE)));
        }
    }

    @Test
    void createSyncsTheNewFileIncludingItsSize() throws Exception {
        FaultyFile channel = FaultyFile.open(dir.resolve("db"), true);
        channel.failFsync = true;
        assertThrows(StorageFailedException.class, () -> PageFile.create(channel, PAGE));
        assertEquals(1, channel.fsyncCalls.get());
    }
}
