package dev.storage.key;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class KeyEncoderTest {

    private final Random random = new Random(3);

    // A tuple of (Integer, Long, String, byte[]), any of them null.
    record Tuple(Integer i, Long l, String s, byte[] b) {

        byte[] encode() {
            KeyEncoder e = KeyEncoder.create();
            if (i == null) {
                e.addNull();
            } else {
                e.addInt(i);
            }
            if (l == null) {
                e.addNull();
            } else {
                e.addLong(l);
            }
            return e.addString(s).addBytes(b).encode();
        }
    }

    // The order the encoding promises: field by field, null first, numbers signed, strings by code point,
    // bytes unsigned.
    private static int compareTuples(Tuple x, Tuple y) {
        int c = compareNullable(x.i, y.i, Integer::compare);
        if (c == 0) {
            c = compareNullable(x.l, y.l, Long::compare);
        }
        if (c == 0) {
            c = compareNullable(x.s, y.s, (a, b) -> Arrays.compare(a.codePoints().toArray(), b.codePoints().toArray()));
        }
        if (c == 0) {
            c = compareNullable(x.b, y.b, Arrays::compareUnsigned);
        }
        return c;
    }

    private static <T> int compareNullable(T a, T b, java.util.Comparator<T> cmp) {
        if (a == null || b == null) {
            return a == null ? (b == null ? 0 : -1) : 1;
        }
        return cmp.compare(a, b);
    }

    private Tuple randomTuple() {
        // Small values collide often, which tests the later fields; large ones test the sign flip.
        int small = random.nextInt(7) - 3;
        Integer i = random.nextInt(5) == 0 ? null : random.nextBoolean() ? small : random.nextInt();
        Long l = random.nextInt(5) == 0 ? null : random.nextBoolean() ? random.nextLong() : random.nextInt(5) - 2L;
        String s = random.nextInt(5) == 0 ? null : randomString();
        byte[] b = null;
        if (random.nextInt(5) != 0) {
            b = new byte[random.nextInt(4)];
            for (int k = 0; k < b.length; k++) {
                b[k] = (byte) new int[] {0, 1, 0x7F, 0x80, 0xFF}[random.nextInt(5)];
            }
        }
        return new Tuple(i, l, s, b);
    }

    private String randomString() {
        // Short strings over an alphabet chosen to hit the edge cases: NUL, ASCII, 2/3-byte UTF-8 and a surrogate pair.
        String[] alphabet = {"\u0000", "a", "b", "é", "￿", "😀"};
        var sb = new StringBuilder();
        int n = random.nextInt(4);
        for (int k = 0; k < n; k++) {
            sb.append(alphabet[random.nextInt(alphabet.length)]);
        }
        return sb.toString();
    }

    @Test
    void byteOrderMatchesTupleOrderForRandomTuples() {
        List<Tuple> tuples = new ArrayList<>();
        for (int n = 0; n < 600; n++) {
            tuples.add(randomTuple());
        }
        for (Tuple x : tuples) {
            byte[] ex = x.encode();
            for (Tuple y : tuples) {
                int expected = Integer.signum(compareTuples(x, y));
                int actual = Integer.signum(Keys.compare(ex, y.encode()));
                assertEquals(expected, actual, x + " vs " + y);
            }
        }
    }

    @Test
    void fieldsRoundTrip() {
        for (int n = 0; n < 2_000; n++) {
            Tuple t = randomTuple();
            var d = new KeyDecoder(t.encode());
            assertEquals(t.i, d.readNull() ? null : d.readInt());
            assertEquals(t.l, d.readNull() ? null : d.readLong());
            assertEquals(t.s, d.readNull() ? null : d.readString());
            if (t.b == null) {
                assertTrue(d.readNull());
            } else {
                assertFalse(d.readNull());
                assertArrayEquals(t.b, d.readBytes());
            }
            assertFalse(d.hasRemaining());
        }
    }

    @Test
    void edgeValuesOrderCorrectly() {
        int[] ints = {Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE};
        for (int k = 1; k < ints.length; k++) {
            assertTrue(Keys.compare(KeyEncoder.create().addInt(ints[k - 1]).encode(),
                    KeyEncoder.create().addInt(ints[k]).encode()) < 0);
        }
        byte[] ab = KeyEncoder.create().addString("ab").addInt(9).encode();
        byte[] abc = KeyEncoder.create().addString("abc").addInt(0).encode();
        assertTrue(Keys.compare(ab, abc) < 0, "a shorter string sorts first whatever follows it");
        assertTrue(Keys.compare(KeyEncoder.create().addNull().encode(),
                KeyEncoder.create().addLong(Long.MIN_VALUE).encode()) < 0);
    }

    @Test
    void prefixEndBoundsEveryKeyWithThatPrefix() {
        byte[] prefix = KeyEncoder.create().addString("eu").encode();
        byte[] end = Keys.prefixEnd(prefix);
        byte[] inside = KeyEncoder.create().addString("eu").addLong(Long.MAX_VALUE).encode();
        byte[] after = KeyEncoder.create().addString("eu\u0000").encode();
        byte[] next = KeyEncoder.create().addString("ev").encode();
        assertTrue(Keys.compare(prefix, inside) < 0 && Keys.compare(inside, end) < 0);
        assertTrue(Keys.compare(end, after) <= 0);
        assertTrue(Keys.compare(end, next) <= 0);

        assertArrayEquals(new byte[] {1, 3}, Keys.prefixEnd(new byte[] {1, 2, (byte) 0xFF}));
        assertNull(Keys.prefixEnd(new byte[] {(byte) 0xFF, (byte) 0xFF}));
        assertNull(Keys.prefixEnd(new byte[0]));
    }

    @Test
    void malformedKeysAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new KeyDecoder(new byte[] {0}).readInt());
        assertThrows(IllegalArgumentException.class, () -> new KeyDecoder(new byte[] {7}).readInt());
        assertThrows(IllegalArgumentException.class, () -> new KeyDecoder(new byte[] {1, 'a', 0, 5}).readBytes());
        assertThrows(IllegalArgumentException.class, () -> new KeyDecoder(new byte[] {1, 'a'}).readBytes());
    }
}
