package dev.storage.buffer;

import java.util.Arrays;

/**
 * Least recently used, where "used" ends when the page is unpinned: a pinned frame is in use by definition, so only
 * evictable frames are kept, in a list ordered by when they became evictable. An intrusive doubly linked list over
 * frame indices makes every call O(1) without allocating.
 */
public final class LruReplacer implements Replacer {

    private static final int NONE = -1;

    private final int[] prev;
    private final int[] next;
    private final boolean[] linked;
    private int head = NONE; // most recently released
    private int tail = NONE; // least recently released: the next victim
    private int count;

    public LruReplacer(int frames) {
        if (frames < 1) {
            throw new IllegalArgumentException("frames must be >= 1: " + frames);
        }
        prev = new int[frames];
        next = new int[frames];
        linked = new boolean[frames];
        Arrays.fill(prev, NONE);
        Arrays.fill(next, NONE);
    }

    @Override
    public void recordAccess(int frame) {
        // Nothing to do: an accessed frame is pinned, and its place is decided when it's released.
    }

    @Override
    public void setEvictable(int frame, boolean evictable) {
        if (evictable && !linked[frame]) {
            pushHead(frame);
        } else if (!evictable && linked[frame]) {
            unlink(frame);
        }
    }

    @Override
    public int evict() {
        int victim = tail;
        if (victim != NONE) {
            unlink(victim);
        }
        return victim;
    }

    @Override
    public void remove(int frame) {
        if (linked[frame]) {
            unlink(frame);
        }
    }

    @Override
    public int evictableCount() {
        return count;
    }

    private void pushHead(int frame) {
        prev[frame] = NONE;
        next[frame] = head;
        if (head != NONE) {
            prev[head] = frame;
        } else {
            tail = frame;
        }
        head = frame;
        linked[frame] = true;
        count++;
    }

    private void unlink(int frame) {
        int p = prev[frame];
        int n = next[frame];
        if (p != NONE) {
            next[p] = n;
        } else {
            head = n;
        }
        if (n != NONE) {
            prev[n] = p;
        } else {
            tail = p;
        }
        prev[frame] = NONE;
        next[frame] = NONE;
        linked[frame] = false;
        count--;
    }
}
