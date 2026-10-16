package dev.storage.key;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Reads back the fields of a key built by KeyEncoder, in the order they were added. */
public final class KeyDecoder {

    private final byte[] key;
    private int at;

    public KeyDecoder(byte[] key) {
        this.key = key;
    }

    public boolean hasRemaining() {
        return at < key.length;
    }

    /** True, and consumed, if the next field is null; false (consuming nothing) otherwise. */
    public boolean readNull() {
        if (peek() == KeyEncoder.NULL) {
            at++;
            return true;
        }
        return false;
    }

    public int readInt() {
        value();
        int v = 0;
        for (int i = 0; i < 4; i++) {
            v = (v << 8) | Byte.toUnsignedInt(next());
        }
        return v ^ Integer.MIN_VALUE;
    }

    public long readLong() {
        value();
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | Byte.toUnsignedInt(next());
        }
        return v ^ Long.MIN_VALUE;
    }

    public String readString() {
        return new String(readBytes(), StandardCharsets.UTF_8);
    }

    public byte[] readBytes() {
        value();
        var out = new ByteArrayOutputStream();
        while (true) {
            byte b = next();
            if (b != 0) {
                out.write(b);
                continue;
            }
            byte marker = next();
            if (marker == 0) {
                return out.toByteArray();
            }
            if (marker != (byte) 0xFF) {
                throw new IllegalArgumentException("bad escape 0x00 0x" + Integer.toHexString(marker & 0xFF)
                        + " at byte " + (at - 1));
            }
            out.write(0);
        }
    }

    private void value() {
        byte tag = next();
        if (tag != KeyEncoder.VALUE) {
            throw new IllegalArgumentException(tag == KeyEncoder.NULL
                    ? "field at byte " + (at - 1) + " is null; check readNull() first"
                    : "unknown field tag 0x" + Integer.toHexString(tag & 0xFF) + " at byte " + (at - 1));
        }
    }

    private byte peek() {
        if (at >= key.length) {
            throw new IllegalArgumentException("no fields left");
        }
        return key[at];
    }

    private byte next() {
        byte b = peek();
        at++;
        return b;
    }
}
