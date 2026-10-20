package dev.storage.wal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.storage.file.StorageFormatException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WalTest {

    private static final int CHUNK = 64 * 1024; // small, so tests cross chunk boundaries

    @TempDir
    Path dir;

    private FileChannel channel(boolean create) throws IOException {
        Path path = dir.resolve("log");
        return create
                ? FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE,
                        StandardOpenOption.CREATE_NEW)
                : FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
    }

    private static ByteBuffer body(String s) {
        return ByteBuffer.wrap(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String text(Wal.Group g) {
        byte[] b = new byte[g.body().remaining()];
        g.body().duplicate().get(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    private List<Wal.Group> reopen(List<Wal.Group> into) throws IOException {
        try (Wal wal = Wal.open(channel(false), into::add, CHUNK)) {
            return into;
        }
    }

    @Test
    void groupsRoundTripWithByteLsns() throws IOException {
        List<Long> ends = new ArrayList<>();
        try (Wal wal = Wal.create(channel(true), 1_000, CHUNK)) {
            assertEquals(1_000, wal.endLsn());
            for (int i = 0; i < 50; i++) {
                ends.add(wal.append(body("group " + i + " " + "x".repeat(i * 100))));
            }
            wal.flush();
            assertEquals(ends.get(49), wal.durableLsn());
        }
        List<Wal.Group> groups = reopen(new ArrayList<>());
        assertEquals(50, groups.size());
        long start = 1_000;
        for (int i = 0; i < 50; i++) {
            Wal.Group g = groups.get(i);
            assertEquals(start, g.startLsn());
            assertEquals(ends.get(i), g.endLsn());
            assertEquals("group " + i + " " + "x".repeat(i * 100), text(g));
            start = g.endLsn();
        }
    }

    @Test
    void groupsLargerThanAChunkWork() throws IOException {
        byte[] big = new byte[3 * CHUNK + 17];
        big[big.length - 1] = 42;
        try (Wal wal = Wal.create(channel(true), 0, CHUNK)) {
            wal.append(ByteBuffer.wrap(big));
            wal.append(body("after"));
        }
        List<Wal.Group> groups = reopen(new ArrayList<>());
        assertEquals(2, groups.size());
        assertEquals(big.length, groups.get(0).body().remaining());
        assertEquals(42, groups.get(0).body().get(big.length - 1));
        assertEquals("after", text(groups.get(1)));
    }

    @Test
    void aTornTailEndsTheLogAndIsZeroedSoLaterAppendsAreClean() throws IOException {
        long secondStart;
        try (Wal wal = Wal.create(channel(true), 0, CHUNK)) {
            secondStart = wal.append(body("one"));
            wal.append(body("two, which gets torn"));
            wal.append(body("three, valid but after the tear"));
        }
        // Flip a byte in group two's body.
        try (FileChannel c = channel(false)) {
            long at = Wal.HEADER + secondStart + Wal.FRAME + 2;
            ByteBuffer b = ByteBuffer.allocate(1);
            c.read(b, at);
            b.put(0, (byte) (b.get(0) ^ 0x10)).clear();
            c.write(b, at);
        }
        List<Wal.Group> groups = new ArrayList<>();
        try (Wal wal = Wal.open(channel(false), groups::add, CHUNK)) {
            assertEquals(List.of("one"), groups.stream().map(WalTest::text).toList());
            assertEquals(secondStart, wal.endLsn());
            wal.append(body("two again"));
        }
        // Group three's bytes were zeroed, so it can't come back behind the new group two.
        assertEquals(List.of("one", "two again"), reopen(new ArrayList<>()).stream().map(WalTest::text).toList());
    }

    @Test
    void aResetStartsANewGenerationAndOldBytesDontParse() throws IOException {
        long end;
        try (Wal wal = Wal.create(channel(true), 0, CHUNK)) {
            wal.append(body("old one"));
            end = wal.append(body("old two"));
            wal.flush();
            assertThrows(IllegalArgumentException.class, () -> wal.reset(end - 1));
            wal.reset(end);
            assertEquals(end, wal.baseLsn());
            assertEquals(0, wal.size());
            wal.append(body("new"));
        }
        List<Wal.Group> groups = reopen(new ArrayList<>());
        assertEquals(List.of("new"), groups.stream().map(WalTest::text).toList());
        assertEquals(end, groups.get(0).startLsn());
    }

    @Test
    void anOldGroupUnderANewHeaderIsNotAGroup() throws IOException {
        try (Wal wal = Wal.create(channel(true), 0, CHUNK)) {
            wal.append(body("from generation one"));
        }
        // As if a crash hit a reset after the new header but before the truncate: a new base over old bytes.
        Path other = dir.resolve("other");
        Wal.create(FileChannel.open(other, StandardOpenOption.READ, StandardOpenOption.WRITE,
                StandardOpenOption.CREATE_NEW), 5_000, CHUNK).close();
        ByteBuffer newHeader = ByteBuffer.allocate(Wal.HEADER);
        try (FileChannel from = FileChannel.open(other, StandardOpenOption.READ); FileChannel to = channel(false)) {
            from.read(newHeader, 0);
            to.write(newHeader.flip(), 0);
        }
        assertEquals(List.of(), reopen(new ArrayList<>()));
    }

    @Test
    void aDamagedHeaderIsAFormatError() throws IOException {
        try (Wal wal = Wal.create(channel(true), 0, CHUNK)) {
            wal.append(body("x"));
        }
        try (FileChannel c = channel(false)) {
            c.write(ByteBuffer.wrap(new byte[] {7}), 9);
        }
        FileChannel c = channel(false);
        assertThrows(StorageFormatException.class, () -> Wal.open(c, g -> { }, CHUNK));
        c.close();
    }

    @Test
    void concurrentCommittersShareFsyncs() throws Exception {
        int threads = 8;
        int each = 200;
        ConcurrentLinkedQueue<String> acked = new ConcurrentLinkedQueue<>();
        try (Wal wal = Wal.create(channel(true), 0, CHUNK)) {
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int id = t;
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < each; i++) {
                        String s = id + ":" + i;
                        long lsn = wal.append(body(s));
                        wal.flushTo(lsn);
                        assertTrue(wal.durableLsn() >= lsn);
                        acked.add(s);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
            pool.shutdown();
            assertTrue(wal.fsyncCount() < threads * each, "fsyncs " + wal.fsyncCount());
            assertThrows(IllegalArgumentException.class, () -> wal.flushTo(wal.endLsn() + 1));
        }
        List<String> logged = reopen(new ArrayList<>()).stream().map(WalTest::text).toList();
        assertEquals(threads * each, logged.size());
        assertTrue(logged.containsAll(acked));
        // Each thread's groups are in its own order.
        for (int t = 0; t < threads; t++) {
            String prefix = t + ":";
            List<String> mine = logged.stream().filter(s -> s.startsWith(prefix)).toList();
            for (int i = 0; i < each; i++) {
                assertEquals(prefix + i, mine.get(i));
            }
        }
    }

    @Test
    void anEmptyGroupIsRejected() throws IOException {
        try (Wal wal = Wal.create(channel(true), 0, CHUNK)) {
            assertThrows(IllegalArgumentException.class, () -> wal.append(ByteBuffer.allocate(0)));
            assertArrayEquals(new long[] {0, 0}, new long[] {wal.endLsn(), wal.size()});
        }
    }
}
