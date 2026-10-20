package dev.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.storage.btree.TreeChecker;
import dev.storage.file.PageFile;
import dev.storage.key.KeyEncoder;
import dev.storage.page.PageHeader;
import dev.storage.page.PageType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs a fixed random workload through Database on files that model the OS page cache, crashes it at the Nth write
 * or fsync for every N the workload reaches, lets the "disk" keep a random subset of the unforced sectors, then
 * recovers on the real files and checks:
 * <ul>
 * <li>SYNC: the contents are exactly the model after every acknowledged operation, plus the one in flight, wholly
 * or not at all;</li>
 * <li>ASYNC: the contents are the model after some prefix of the operations, which includes every operation before
 * the last explicit checkpoint;</li>
 * <li>every tree passes the invariant checker, and every page is either in a tree or free;</li>
 * <li>for a sample of crash points, recovery is crashed too, and recovering again gives a valid result.</li>
 * </ul>
 */
class DatabaseCrashTest {

    @TempDir
    Path dir;

    private sealed interface Op permits Create, Put, Delete, Checkpoint { }

    private record Create(String index) implements Op { }

    private record Put(String index, byte[] key, byte[] value) implements Op { }

    private record Delete(String index, byte[] key) implements Op { }

    private record Checkpoint() implements Op { }

    /** The shape of a workload: how many keys, how many puts, and how much log before a checkpoint. */
    private record Shape(int keys, int putPercent, int minValue, long checkpointLogBytes) {
        // Splits, merges and automatic checkpoints, on trees that fit in the pool.
        static final Shape CHURN = new Shape(90, 60, 0, 48 * 1024);
        // Trees over twice the pool, so dirty pages are evicted between checkpoints. The log limit stays under one
        // 64 KiB log chunk (the tests' size): growing into a new chunk forces the log, which would make the image
        // logged at each page's first change durable, and recovery rebuilds a page from its image whatever the
        // file holds. Only with the log unforced can a page written ahead of its log show, which is what the
        // write-ahead rule prevents.
        static final Shape EVICTING = new Shape(400, 85, 300, 56 * 1024);
    }

    private static Options options(Options.Durability durability, Shape shape) {
        // Small pages and a small pool, so a short workload goes through all of it.
        return Options.defaults().withPageSize(4096).withPoolFrames(16).withDurability(durability)
                .withCheckpointLogBytes(shape.checkpointLogBytes());
    }

    private static List<Op> workload(long seed, int count, Shape shape) {
        Random random = new Random(seed);
        List<Op> ops = new ArrayList<>(List.of(new Create("a"), new Create("b")));
        while (ops.size() < count) {
            String index = random.nextBoolean() ? "a" : "b";
            byte[] key = KeyEncoder.create().addLong(random.nextInt(shape.keys())).encode();
            int roll = random.nextInt(100);
            if (roll < shape.putPercent()) {
                byte[] value = new byte[shape.minValue() + random.nextInt(700 - shape.minValue())];
                random.nextBytes(value);
                ops.add(new Put(index, key, value));
            } else if (roll < 96) {
                ops.add(new Delete(index, key));
            } else {
                ops.add(new Checkpoint());
            }
        }
        return ops;
    }

    // digests.get(k) = the state after the first k operations.
    private static List<String> digests(List<Op> ops) {
        Map<String, NavigableMap<byte[], byte[]>> state = new TreeMap<>();
        List<String> digests = new ArrayList<>(List.of(digest(state)));
        for (Op op : ops) {
            switch (op) {
                case Create c -> state.put(c.index(), new TreeMap<>(Arrays::compareUnsigned));
                case Put p -> state.get(p.index()).put(p.key(), p.value());
                case Delete d -> state.get(d.index()).remove(d.key());
                case Checkpoint c -> { }
            }
            digests.add(digest(state));
        }
        return digests;
    }

    private static String digest(Map<String, NavigableMap<byte[], byte[]>> state) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (Map.Entry<String, NavigableMap<byte[], byte[]>> index : state.entrySet()) {
                sha.update(index.getKey().getBytes(StandardCharsets.UTF_8));
                sha.update((byte) 0);
                for (Map.Entry<byte[], byte[]> e : index.getValue().entrySet()) {
                    sha.update(ByteBuffer.allocate(8).putInt(e.getKey().length).putInt(e.getValue().length).array());
                    sha.update(e.getKey());
                    sha.update(e.getValue());
                }
                sha.update((byte) 1);
            }
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String digest(Database db) {
        Map<String, NavigableMap<byte[], byte[]>> state = new TreeMap<>();
        for (String name : db.indexNames()) {
            NavigableMap<byte[], byte[]> entries = new TreeMap<>(Arrays::compareUnsigned);
            try (Cursor c = db.index(name).scan(null, null)) {
                while (c.next()) {
                    entries.put(c.key(), c.value());
                }
            }
            state.put(name, entries);
        }
        return digest(state);
    }

    /** What a run got through before it crashed. */
    private record Outcome(boolean crashed, int acked, int durableFloor) { }

    private static Outcome run(Path path, Options options, List<Op> ops, CrashFile.Crash crash) {
        int acked = 0;
        int floor = 0;
        try {
            Database db = Database.open(path, options, crash.opener());
            for (Op op : ops) {
                switch (op) {
                    case Create c -> db.createIndex(c.index());
                    case Put p -> db.index(p.index()).put(p.key(), p.value());
                    case Delete d -> db.index(d.index()).delete(d.key());
                    case Checkpoint c -> db.checkpoint();
                }
                acked++;
                if (op instanceof Checkpoint) {
                    floor = acked;
                }
            }
            db.close();
            floor = acked;
        } catch (RuntimeException e) {
            if (!crash.crashed()) {
                throw e;
            }
        }
        return new Outcome(crash.crashed(), acked, floor);
    }

    // Opens with real files, checks the contents against the allowed snapshots and the structure, and closes.
    private static void verify(Path path, Options options, List<String> digests, Outcome outcome, String what) {
        try (Database db = Database.open(path, options)) {
            String got = digest(db);
            int low = options.durability() == Options.Durability.SYNC ? outcome.acked() : outcome.durableFloor();
            int high = Math.min(outcome.acked() + 1, digests.size() - 1);
            boolean match = false;
            for (int k = low; k <= high && !match; k++) {
                match = digests.get(k).equals(got);
            }
            if (!match) {
                int actual = digests.indexOf(got);
                fail(what + ": recovered state isn't one of snapshots " + low + ".." + high + " (acked "
                        + outcome.acked() + "); it matches snapshot " + actual);
            }
            assertStructure(db, path, what);
        }
    }

    private static void assertStructure(Database db, Path path, String what) {
        int inTrees = TreeChecker.check(db.catalogTree()).pages();
        for (String name : db.indexNames()) {
            inTrees += TreeChecker.check(db.index(name).tree()).pages();
        }
        db.checkpoint();
        int free = 0;
        int count;
        try (PageFile file = PageFile.open(path)) {
            count = file.pageCount();
            ByteBuffer page = ByteBuffer.allocate(file.pageSize());
            for (int id = 1; id < count; id++) {
                page.clear();
                file.read(id, page);
                if (PageHeader.type(page) == PageType.FREE) {
                    free++;
                }
            }
        }
        assertEquals(count - 1, inTrees + free, what + ": pages lost (" + inTrees + " in trees, " + free + " free, "
                + count + " in the file)");
    }

    private void crashEverywhere(Options.Durability durability, long seed, int opCount, Shape shape, int stride)
            throws IOException {
        Options options = options(durability, shape);
        List<Op> ops = workload(seed, opCount, shape);
        List<String> digests = digests(ops);
        int crashes = 0;
        int recoveryCrashes = 0;
        for (long n = 1; ; n += stride) {
            Path runDir = Files.createDirectory(dir.resolve(durability + "-" + seed + "-" + n));
            Path path = runDir.resolve("db");
            CrashFile.Crash crash = new CrashFile.Crash(n, seed * 1_000_003 + n);
            Outcome outcome = run(path, options, ops, crash);
            String what = durability + " seed " + seed + " crash at " + n;
            if (!outcome.crashed()) {
                verify(path, options, digests, outcome, what + " (no crash)");
                break; // the workload finished before the Nth write: every crash point has been tried
            }
            crash.materialize();
            crashes++;
            if (crashes % 7 == 0) {
                // Crash the recovery too, somewhere in its own writes, then recover again.
                CrashFile.Crash second = new CrashFile.Crash(1 + (n * 31) % 40, seed + n);
                try {
                    Database.open(path, options, second.opener()).close();
                } catch (RuntimeException e) {
                    if (!second.crashed()) {
                        throw e;
                    }
                }
                if (second.crashed()) {
                    second.materialize();
                    recoveryCrashes++;
                }
            }
            verify(path, options, digests, outcome, what);
            deleteTree(runDir);
        }
        assertTrue(crashes * stride > opCount, durability + ": only " + crashes + " crash points");
        assertTrue(recoveryCrashes > 0, "no recovery was crashed");
    }

    @Test
    void syncCrashAtEveryWrite() throws IOException {
        crashEverywhere(Options.Durability.SYNC, 1, 120, Shape.CHURN, 1);
    }

    @Test
    void syncCrashAtEveryWriteAnotherWorkload() throws IOException {
        crashEverywhere(Options.Durability.SYNC, 2, 120, Shape.CHURN, 1);
    }

    @Test
    void asyncCrashAtEveryWrite() throws IOException {
        crashEverywhere(Options.Durability.ASYNC, 3, 160, Shape.CHURN, 1);
    }

    @Test
    void asyncCrashWhileEvictingPagesAheadOfACheckpoint() throws IOException {
        crashEverywhere(Options.Durability.ASYNC, 4, 260, Shape.EVICTING, 2);
    }

    @Test
    void syncCrashWhileEvictingPagesAheadOfACheckpoint() throws IOException {
        crashEverywhere(Options.Durability.SYNC, 5, 200, Shape.EVICTING, 3);
    }

    private static void deleteTree(Path runDir) {
        try (var files = Files.list(runDir)) {
            for (Path f : files.toList()) {
                Files.delete(f);
            }
            Files.delete(runDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
