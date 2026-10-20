package dev.storage.wal;

import dev.storage.file.StorageFailedException;
import dev.storage.file.StorageFormatException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.CRC32C;

/**
 * The write-ahead log: groups of records appended to one file, each framed so a torn or stale tail is recognised.
 *
 * <pre>
 * header (one 512-byte sector)   magic "BTWL" | version u32 | baseLsn i64 | crc32c u32
 * group                          length u32 | crc32c u32 | startLsn i64 | body
 * </pre>
 *
 * An LSN is a byte position: baseLsn + (offset - 512). A group's CRC covers its start LSN and body, so bytes left
 * over from an older generation of the log can't pass as a group at the same offset. The log is the first group
 * that's all zeros, fails its CRC, or doesn't start where it should.
 *
 * The file grows in preallocated chunks of zeros, each forced with its metadata once, so an append inside a chunk
 * only needs force(false). Flushes are group commits: the first thread to ask becomes the leader and fsyncs for
 * every group appended so far, and threads that arrive meanwhile wait for it and check again.
 *
 * A failed fsync is final (as in PageFile): everything after it throws, and the log has to be reopened.
 */
public final class Wal implements AutoCloseable {

    static final int HEADER = 512;
    static final int FRAME = 16;
    static final int DEFAULT_CHUNK = 4 << 20;
    private static final int MAGIC = 0x4254574C; // "BTWL"
    private static final int VERSION = 1;
    private static final int MAX_GROUP = 256 << 20;

    /** One group read back from the log. body() is a fresh read-only view each time. */
    public record Group(long startLsn, long endLsn, ByteBuffer body) {
        public Group {
            body = body.asReadOnlyBuffer();
        }

        @Override
        public ByteBuffer body() {
            return body.asReadOnlyBuffer();
        }
    }

    /** Called with each valid group in order while the log is opened. */
    @FunctionalInterface
    public interface Visitor {
        void visit(Group group);
    }

    private final FileChannel channel;
    private final int chunk;
    private final ReentrantLock appendLock = new ReentrantLock();
    private final ReentrantLock flushLock = new ReentrantLock();
    private final Condition flushed = flushLock.newCondition();
    private final AtomicLong fsyncs = new AtomicLong();

    private long baseLsn; // guarded by appendLock
    private long allocated; // guarded by appendLock: file bytes preallocated and durable
    private volatile long endLsn;
    private long durableLsn; // guarded by flushLock
    private boolean flushing; // guarded by flushLock
    private volatile Throwable failure;

    private Wal(FileChannel channel, int chunk, long baseLsn, long endLsn, long allocated) {
        this.channel = channel;
        this.chunk = chunk;
        this.baseLsn = baseLsn;
        this.endLsn = endLsn;
        this.durableLsn = endLsn;
        this.allocated = allocated;
    }

    /** A new, empty log whose first LSN is baseLsn. */
    public static Wal create(FileChannel channel, long baseLsn) {
        return create(channel, baseLsn, DEFAULT_CHUNK);
    }

    static Wal create(FileChannel channel, long baseLsn, int chunk) {
        var wal = new Wal(channel, chunk, baseLsn, baseLsn, 0);
        wal.appendLock.lock();
        try {
            wal.resetLocked(baseLsn);
        } finally {
            wal.appendLock.unlock();
        }
        return wal;
    }

    /**
     * Opens a log, passing each valid group to visitor in order. Whatever follows the last valid group is zeroed and
     * forced before this returns, so a later recovery can't find stale groups there after new appends.
     */
    public static Wal open(FileChannel channel, Visitor visitor) {
        return open(channel, visitor, DEFAULT_CHUNK);
    }

    static Wal open(FileChannel channel, Visitor visitor, int chunk) {
        try {
            ByteBuffer header = ByteBuffer.allocate(HEADER);
            if (!readFully(channel, header, 0)) {
                throw new StorageFormatException("log is shorter than its header");
            }
            long base = readHeader(header);
            long size = channel.size();
            long offset = HEADER;
            ByteBuffer frame = ByteBuffer.allocate(FRAME);
            while (offset + FRAME <= size) {
                frame.clear();
                readFully(channel, frame, offset);
                int length = frame.getInt(0);
                int crc = frame.getInt(4);
                long start = frame.getLong(8);
                long expectedStart = base + offset - HEADER;
                if (length <= 0 || length > MAX_GROUP || start != expectedStart || offset + FRAME + length > size) {
                    break;
                }
                ByteBuffer body = ByteBuffer.allocate(length);
                readFully(channel, body, offset + FRAME);
                body.flip();
                if (crc(start, body) != crc) {
                    break;
                }
                long end = start + FRAME + length;
                visitor.visit(new Group(start, end, body));
                offset += FRAME + length;
            }
            long end = base + offset - HEADER;
            zero(channel, offset, size);
            channel.force(true);
            return new Wal(channel, chunk, base, end, size);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Appends body as one group and returns its end LSN. It isn't durable until flushTo reaches that LSN. */
    public long append(ByteBuffer body) {
        checkUsable();
        int length = body.remaining();
        if (length == 0 || length > MAX_GROUP) {
            throw new IllegalArgumentException("group of " + length + " bytes");
        }
        appendLock.lock();
        try {
            long start = endLsn;
            long offset = HEADER + (start - baseLsn);
            long needed = offset + FRAME + length;
            if (needed > allocated) {
                preallocate(needed);
            }
            ByteBuffer frame = ByteBuffer.allocate(FRAME + length);
            frame.putInt(length).putInt(crc(start, body)).putLong(start).put(body.duplicate()).flip();
            writeFully(frame, offset);
            long end = start + FRAME + length;
            endLsn = end;
            return end;
        } catch (IOException e) {
            failure = e;
            throw new StorageFailedException("log write failed; the log must be reopened", e);
        } finally {
            appendLock.unlock();
        }
    }

    /** Makes the log durable at least up to lsn. Concurrent callers share one fsync. */
    public void flushTo(long lsn) {
        checkUsable();
        if (lsn > endLsn) {
            throw new IllegalArgumentException("LSN " + lsn + " is past the end of the log, " + endLsn);
        }
        flushLock.lock();
        try {
            while (durableLsn < lsn) {
                if (flushing) {
                    flushed.awaitUninterruptibly();
                    checkUsable();
                    continue;
                }
                flushing = true;
                long target = endLsn;
                flushLock.unlock();
                try {
                    channel.force(false);
                    fsyncs.incrementAndGet();
                } catch (IOException | RuntimeException e) {
                    failure = e;
                } finally {
                    flushLock.lock();
                    flushing = false;
                    if (failure == null) {
                        durableLsn = Math.max(durableLsn, target);
                    }
                    flushed.signalAll();
                }
                checkUsable();
            }
        } finally {
            flushLock.unlock();
        }
    }

    /** Flushes everything appended so far. */
    public void flush() {
        flushTo(endLsn);
    }

    /**
     * Empties the log, starting it again at newBase (at least the current end, so LSNs never repeat). The caller makes
     * sure nothing appends meanwhile and that nothing in the log is needed any more.
     */
    public void reset(long newBase) {
        checkUsable();
        appendLock.lock();
        try {
            if (newBase < endLsn) {
                throw new IllegalArgumentException("new base " + newBase + " is before the end " + endLsn);
            }
            resetLocked(newBase);
        } finally {
            appendLock.unlock();
        }
    }

    // Truncate, new header, first chunk, force. Any crash in between leaves either the old log or an empty one.
    private void resetLocked(long newBase) {
        try {
            channel.truncate(HEADER);
            ByteBuffer header = ByteBuffer.allocate(HEADER);
            header.putInt(MAGIC).putInt(VERSION).putLong(newBase);
            var crc = new CRC32C();
            crc.update(header.array(), 0, 16);
            header.putInt((int) crc.getValue()).clear();
            writeFully(header, 0);
            allocated = HEADER;
            baseLsn = newBase;
            endLsn = newBase;
            preallocate(HEADER + (long) chunk);
            flushLock.lock();
            try {
                durableLsn = newBase;
            } finally {
                flushLock.unlock();
            }
        } catch (IOException e) {
            failure = e;
            throw new StorageFailedException("log reset failed; the log must be reopened", e);
        }
    }

    // Grows the file with zeros to cover needed, in whole chunks, and forces the new size.
    private void preallocate(long needed) throws IOException {
        long target = allocated;
        while (target < needed) {
            target += chunk;
        }
        zero(channel, allocated, target);
        channel.force(true);
        allocated = target;
    }

    public long endLsn() {
        return endLsn;
    }

    public long baseLsn() {
        appendLock.lock();
        try {
            return baseLsn;
        } finally {
            appendLock.unlock();
        }
    }

    public long durableLsn() {
        flushLock.lock();
        try {
            return durableLsn;
        } finally {
            flushLock.unlock();
        }
    }

    /** Bytes of groups since the last reset. */
    public long size() {
        return endLsn - baseLsn();
    }

    /** fsyncs done by flushes, for tests that check group commit. */
    long fsyncCount() {
        return fsyncs.get();
    }

    @Override
    public void close() {
        if (!channel.isOpen()) {
            return;
        }
        try {
            if (failure == null) {
                flush();
            }
        } finally {
            try {
                channel.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** Closes without flushing, as a killed process would leave it: what was written stays in the OS's hands. */
    void abandon() {
        try {
            channel.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void checkUsable() {
        if (failure != null) {
            throw new StorageFailedException("an earlier log write or fsync failed; the log must be reopened", failure);
        }
        if (!channel.isOpen()) {
            throw new IllegalStateException("log is closed");
        }
    }

    private static long readHeader(ByteBuffer header) {
        var crc = new CRC32C();
        crc.update(header.array(), 0, 16);
        if (header.getInt(0) != MAGIC) {
            throw new StorageFormatException("not a log file: bad magic");
        }
        if (header.getInt(4) != VERSION) {
            throw new StorageFormatException("log format version " + header.getInt(4) + " isn't supported");
        }
        if (header.getInt(16) != (int) crc.getValue()) {
            throw new StorageFormatException("log header checksum mismatch");
        }
        return header.getLong(8);
    }

    private static int crc(long start, ByteBuffer body) {
        var crc = new CRC32C();
        ByteBuffer lsn = ByteBuffer.allocate(8).putLong(0, start);
        crc.update(lsn);
        crc.update(body.duplicate());
        return (int) crc.getValue();
    }

    private static void zero(FileChannel channel, long from, long to) throws IOException {
        ByteBuffer zeros = ByteBuffer.allocate(64 * 1024);
        for (long at = from; at < to; ) {
            zeros.clear().limit((int) Math.min(zeros.capacity(), to - at));
            while (zeros.hasRemaining()) {
                at += channel.write(zeros, at);
            }
        }
    }

    private void writeFully(ByteBuffer src, long position) throws IOException {
        long at = position;
        while (src.hasRemaining()) {
            at += channel.write(src, at);
        }
    }

    // Returns false if the file ends first.
    private static boolean readFully(FileChannel channel, ByteBuffer dst, long position) throws IOException {
        long at = position;
        while (dst.hasRemaining()) {
            int n = channel.read(dst, at);
            if (n < 0) {
                return false;
            }
            at += n;
        }
        return true;
    }
}
