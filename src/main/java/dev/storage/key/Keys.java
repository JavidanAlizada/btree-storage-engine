package dev.storage.key;

import java.util.Arrays;

/** Helpers for raw keys, which the tree orders as unsigned bytes. */
public final class Keys {

    private Keys() {
    }

    public static int compare(byte[] a, byte[] b) {
        return Arrays.compareUnsigned(a, b);
    }

    /**
     * The smallest key greater than every key that starts with prefix, for use as an exclusive scan bound; null if
     * there is none (the prefix is empty or all 0xFF), meaning "scan to the end".
     */
    public static byte[] prefixEnd(byte[] prefix) {
        for (int i = prefix.length - 1; i >= 0; i--) {
            if (prefix[i] != (byte) 0xFF) {
                byte[] end = Arrays.copyOf(prefix, i + 1);
                end[i]++;
                return end;
            }
        }
        return null;
    }
}
