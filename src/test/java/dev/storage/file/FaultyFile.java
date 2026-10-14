package dev.storage.file;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A FileChannel that does what the real one does until told to fail: fsync throws, or the next write lands with
 * one byte flipped. M4's crash tests add "stop at the Nth write" and torn writes here.
 */
final class FaultyFile extends FileChannel {

    private final FileChannel real;
    volatile boolean failFsync;
    volatile boolean flipNextWrite;
    final AtomicInteger fsyncCalls = new AtomicInteger();

    private FaultyFile(FileChannel real) {
        this.real = real;
    }

    static FaultyFile open(Path path, boolean create) throws IOException {
        return create
                ? new FaultyFile(FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE,
                        StandardOpenOption.CREATE_NEW))
                : new FaultyFile(FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE));
    }

    @Override
    public void force(boolean metaData) throws IOException {
        fsyncCalls.incrementAndGet();
        if (failFsync) {
            throw new IOException("injected fsync failure");
        }
        real.force(metaData);
    }

    @Override
    public int write(ByteBuffer src, long position) throws IOException {
        if (flipNextWrite && src.remaining() > 100) {
            flipNextWrite = false;
            ByteBuffer copy = ByteBuffer.allocate(src.remaining());
            copy.put(src.duplicate()).flip();
            copy.put(100, (byte) (copy.get(100) ^ 0x01));
            int n = real.write(copy, position);
            src.position(src.position() + n);
            return n;
        }
        return real.write(src, position);
    }

    @Override
    public int read(ByteBuffer dst, long position) throws IOException {
        return real.read(dst, position);
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        return real.read(dst);
    }

    @Override
    public long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
        return real.read(dsts, offset, length);
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
        return real.write(src);
    }

    @Override
    public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
        return real.write(srcs, offset, length);
    }

    @Override
    public long position() throws IOException {
        return real.position();
    }

    @Override
    public FileChannel position(long newPosition) throws IOException {
        real.position(newPosition);
        return this;
    }

    @Override
    public long size() throws IOException {
        return real.size();
    }

    @Override
    public FileChannel truncate(long size) throws IOException {
        real.truncate(size);
        return this;
    }

    @Override
    public long transferTo(long position, long count, WritableByteChannel target) throws IOException {
        return real.transferTo(position, count, target);
    }

    @Override
    public long transferFrom(ReadableByteChannel src, long position, long count) throws IOException {
        return real.transferFrom(src, position, count);
    }

    @Override
    public MappedByteBuffer map(MapMode mode, long position, long size) throws IOException {
        throw new UnsupportedOperationException("PageFile never maps the file");
    }

    @Override
    public FileLock lock(long position, long size, boolean shared) throws IOException {
        return real.lock(position, size, shared);
    }

    @Override
    public FileLock tryLock(long position, long size, boolean shared) throws IOException {
        return real.tryLock(position, size, shared);
    }

    @Override
    protected void implCloseChannel() throws IOException {
        real.close();
    }
}
