package dev.storage.buffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.storage.file.PageFile;
import dev.storage.page.CorruptPageException;
import dev.storage.page.PageHeader;
import dev.storage.page.PageType;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.function.IntFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class BufferPoolConcurrencyTest {

    private static final int PAGE = 4096;
    private static final int PAGES = 64;
    private static final int THREADS = 8;
    private static final int OPS = 3_000;

    @TempDir
    Path dir;

    static Stream<Arguments> replacers() {
        return Stream.of(
                Arguments.of("lru", (IntFunction<Replacer>) LruReplacer::new),
                Arguments.of("clock", (IntFunction<Replacer>) ClockReplacer::new));
    }

    /**
     * Many more pages than frames, so nearly every fetch evicts and most victims are dirty. Each page's aux word is a
     * counter bumped under its exclusive latch. A lost update (a fetch reading the old version from disk while the
     * dirty frame is still being written) or a torn copy shows up as a wrong total.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("replacers")
    void countersAddUpUnderConstantEviction(String name, IntFunction<Replacer> replacer) throws Exception {
        Path path = dir.resolve("db-" + name);
        int[] ids = new int[PAGES];
        var expected = new AtomicIntegerArray(PAGES);
        try (PageFile file = PageFile.create(path, PAGE)) {
            BufferPool pool = new BufferPool(file, 10, replacer.apply(10));
            for (int i = 0; i < PAGES; i++) {
                try (PageGuard page = pool.newPage()) {
                    page.latchExclusive();
                    PageHeader.format(page.buffer(), page.pageId(), PageType.LEAF);
                    page.markDirty();
                    ids[i] = page.pageId();
                }
            }
            try (ExecutorService threads = Executors.newFixedThreadPool(THREADS)) {
                var start = new CountDownLatch(1);
                List<Future<?>> tasks = new ArrayList<>();
                for (int t = 0; t < THREADS; t++) {
                    long seed = t;
                    tasks.add(threads.submit(() -> {
                        var random = new Random(seed);
                        start.await();
                        for (int op = 0; op < OPS; op++) {
                            int i = random.nextInt(PAGES);
                            try (PageGuard page = pool.fetch(ids[i])) {
                                if (random.nextInt(4) == 0) {
                                    page.latchShared();
                                    PageHeader.aux(page.buffer());
                                } else {
                                    page.latchExclusive();
                                    PageHeader.setAux(page.buffer(), PageHeader.aux(page.buffer()) + 1);
                                    page.markDirty();
                                    expected.incrementAndGet(i);
                                }
                            }
                        }
                        return null;
                    }));
                }
                start.countDown();
                for (Future<?> task : tasks) {
                    task.get(120, TimeUnit.SECONDS);
                }
            }
            for (int i = 0; i < PAGES; i++) {
                assertEquals(expected.get(i), counter(pool, ids[i]), name + ": page " + ids[i] + " in the pool");
            }
            pool.close();
        }
        try (PageFile file = PageFile.open(path)) {
            BufferPool pool = new BufferPool(file, 4, replacer.apply(4));
            for (int i = 0; i < PAGES; i++) {
                assertEquals(expected.get(i), counter(pool, ids[i]), name + ": page " + ids[i] + " after reopen");
            }
            pool.close();
        }
    }

    @Test
    void everyThreadWaitingOnACorruptPageGetsTheErrorAndNoPinIsLeft() throws Exception {
        Path path = dir.resolve("db");
        int bad;
        int good;
        try (PageFile file = PageFile.create(path, PAGE)) {
            try (BufferPool pool = new BufferPool(file, 4, new LruReplacer(4))) {
                bad = newLeaf(pool);
                good = newLeaf(pool);
            }
        }
        try (var raf = new RandomAccessFile(path.toFile(), "rw")) {
            raf.seek((long) bad * PAGE + 2000);
            raf.write(0x55);
        }
        try (PageFile file = PageFile.open(path);
                ExecutorService threads = Executors.newFixedThreadPool(THREADS)) {
            BufferPool pool = new BufferPool(file, 4, new LruReplacer(4));
            var start = new CountDownLatch(1);
            List<Future<?>> tasks = new ArrayList<>();
            for (int t = 0; t < THREADS; t++) {
                tasks.add(threads.submit(() -> {
                    start.await();
                    try (PageGuard page = pool.fetch(bad)) {
                        return null;
                    }
                }));
            }
            start.countDown();
            for (Future<?> task : tasks) {
                var e = assertThrows(ExecutionException.class,
                        () -> task.get(30, TimeUnit.SECONDS));
                assertInstanceOf(CorruptPageException.class, e.getCause());
            }
            // The failed frame went back to the pool: every frame can still be used.
            assertEquals(0, counter(pool, good));
            pool.close(); // throws if a waiter leaked its pin
        }
    }

    private static int newLeaf(BufferPool pool) {
        try (PageGuard page = pool.newPage()) {
            page.latchExclusive();
            PageHeader.format(page.buffer(), page.pageId(), PageType.LEAF);
            page.markDirty();
            return page.pageId();
        }
    }

    private static int counter(BufferPool pool, int id) {
        try (PageGuard page = pool.fetch(id)) {
            page.latchShared();
            return PageHeader.aux(page.buffer());
        }
    }
}
