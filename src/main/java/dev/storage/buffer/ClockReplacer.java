package dev.storage.buffer;

/**
 * The clock (second-chance) approximation of LRU. An access sets the frame's reference bit; the hand sweeps the
 * frames, clearing set bits, and evicts the first evictable frame whose bit is already clear. A hit costs a bit write
 * instead of relinking a list.
 */
public final class ClockReplacer implements Replacer {

    private final boolean[] referenced;
    private final boolean[] evictable;
    private int hand;
    private int count;

    public ClockReplacer(int frames) {
        if (frames < 1) {
            throw new IllegalArgumentException("frames must be >= 1: " + frames);
        }
        referenced = new boolean[frames];
        evictable = new boolean[frames];
    }

    @Override
    public void recordAccess(int frame) {
        referenced[frame] = true;
    }

    @Override
    public void setEvictable(int frame, boolean value) {
        if (evictable[frame] != value) {
            evictable[frame] = value;
            count += value ? 1 : -1;
        }
    }

    @Override
    public int evict() {
        if (count == 0) {
            return -1;
        }
        // Two sweeps at most: the first may only clear reference bits, the second then finds a victim.
        for (int step = 0; step < 2 * evictable.length; step++) {
            int frame = hand;
            hand = (hand + 1) % evictable.length;
            if (!evictable[frame]) {
                continue;
            }
            if (referenced[frame]) {
                referenced[frame] = false;
            } else {
                evictable[frame] = false;
                count--;
                return frame;
            }
        }
        throw new IllegalStateException("clock found no victim among " + count + " evictable frames");
    }

    @Override
    public void remove(int frame) {
        setEvictable(frame, false);
        referenced[frame] = false;
    }

    @Override
    public int evictableCount() {
        return count;
    }
}
