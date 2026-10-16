package dev.storage.key;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Builds a key from typed fields so that comparing the bytes (unsigned, lexicographic) gives the same order as
 * comparing the fields one by one. That lets the tree compare bytes only, whatever the key is made of.
 *
 * <pre>
 * null        0x00                              (sorts before any value)
 * int, long   0x01, big-endian, sign bit flipped (so negatives sort first)
 * string      0x01, UTF-8 with 0x00 escaped as 0x00 0xFF, ended by 0x00 0x00
 * bytes       as string, without the UTF-8 step
 * </pre>
 *
 * The terminator makes "ab" sort before "abc" and keeps a later field from deciding the comparison of an earlier
 * string. Strings sort by code point, not by any locale's collation.
 */
public final class KeyEncoder {

    static final byte NULL = 0x00;
    static final byte VALUE = 0x01;

    private byte[] buf = new byte[32];
    private int size;

    private KeyEncoder() {
    }

    public static KeyEncoder create() {
        return new KeyEncoder();
    }

    public KeyEncoder addNull() {
        put(NULL);
        return this;
    }

    public KeyEncoder addInt(int value) {
        put(VALUE);
        int v = value ^ Integer.MIN_VALUE;
        for (int shift = 24; shift >= 0; shift -= 8) {
            put((byte) (v >>> shift));
        }
        return this;
    }

    public KeyEncoder addLong(long value) {
        put(VALUE);
        long v = value ^ Long.MIN_VALUE;
        for (int shift = 56; shift >= 0; shift -= 8) {
            put((byte) (v >>> shift));
        }
        return this;
    }

    /** A string field; null is encoded as a null field. */
    public KeyEncoder addString(String value) {
        return value == null ? addNull() : addEscaped(value.getBytes(StandardCharsets.UTF_8));
    }

    /** A byte-string field; null is encoded as a null field. */
    public KeyEncoder addBytes(byte[] value) {
        return value == null ? addNull() : addEscaped(value);
    }

    public byte[] encode() {
        return Arrays.copyOf(buf, size);
    }

    private KeyEncoder addEscaped(byte[] bytes) {
        put(VALUE);
        for (byte b : bytes) {
            put(b);
            if (b == 0) {
                put((byte) 0xFF);
            }
        }
        put((byte) 0);
        put((byte) 0);
        return this;
    }

    private void put(byte b) {
        if (size == buf.length) {
            buf = Arrays.copyOf(buf, size * 2);
        }
        buf[size++] = b;
    }
}
