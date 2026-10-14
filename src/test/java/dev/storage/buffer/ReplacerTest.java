package dev.storage.buffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ReplacerTest {

    private final Random random = new Random(11);

    static Stream<Arguments> replacers() {
        return Stream.of(
                Arguments.of("lru", (IntFunction<Replacer>) LruReplacer::new),
                Arguments.of("clock", (IntFunction<Replacer>) ClockReplacer::new));
    }

    @Test
    void lruEvictsInReleaseOrder() {
        Replacer lru = new LruReplacer(4);
        lru.setEvictable(2, true);
        lru.setEvictable(0, true);
        lru.setEvictable(3, true);
        lru.setEvictable(2, false); // pinned again, then released last
        lru.setEvictable(2, true);

        assertEquals(0, lru.evict());
        assertEquals(3, lru.evict());
        assertEquals(2, lru.evict());
        assertEquals(-1, lru.evict());
    }

    @Test
    void clockGivesReferencedFramesASecondChance() {
        Replacer clock = new ClockReplacer(3);
        for (int f = 0; f < 3; f++) {
            clock.recordAccess(f);
            clock.setEvictable(f, true);
        }
        clock.recordAccess(0); // still referenced after the first sweep clears everything once

        // The first sweep clears all bits; frame 0 is then the first unreferenced frame the hand reaches.
        assertEquals(0, clock.evict());
        clock.recordAccess(1);
        assertEquals(2, clock.evict()); // 1 was referenced again, so 2 goes first
        assertEquals(1, clock.evict());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("replacers")
    void pinnedAndRemovedFramesAreNeverChosen(String name, IntFunction<Replacer> make) {
        Replacer r = make.apply(5);
        for (int f = 0; f < 5; f++) {
            r.recordAccess(f);
            r.setEvictable(f, true);
        }
        r.setEvictable(1, false);
        r.remove(3);
        assertEquals(3, r.evictableCount());

        Set<Integer> evicted = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            evicted.add(r.evict());
        }
        assertEquals(Set.of(0, 2, 4), evicted);
        assertEquals(-1, r.evict());
        assertEquals(0, r.evictableCount());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("replacers")
    void randomUseNeverEvictsAPinnedFrameOrLosesCount(String name, IntFunction<Replacer> make) {
        int frames = 16;
        Replacer r = make.apply(frames);
        boolean[] evictable = new boolean[frames];
        for (int step = 0; step < 50_000; step++) {
            int f = random.nextInt(frames);
            switch (random.nextInt(4)) {
                case 0 -> r.recordAccess(f);
                case 1 -> {
                    r.setEvictable(f, true);
                    evictable[f] = true;
                }
                case 2 -> {
                    r.setEvictable(f, false);
                    evictable[f] = false;
                }
                default -> {
                    int victim = r.evict();
                    if (victim == -1) {
                        for (boolean e : evictable) {
                            assertTrue(!e, "evict found nothing although a frame was evictable");
                        }
                    } else {
                        assertTrue(evictable[victim], "evicted pinned frame " + victim + " at step " + step);
                        evictable[victim] = false;
                    }
                }
            }
            int expected = 0;
            for (boolean e : evictable) {
                expected += e ? 1 : 0;
            }
            assertEquals(expected, r.evictableCount(), name + " step " + step);
        }
    }
}
