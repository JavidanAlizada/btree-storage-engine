package dev.storage.file;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** How the engine opens its files. A seam for tests that model crashes; everything else uses DEFAULT. */
@FunctionalInterface
public interface ChannelOpener {

    ChannelOpener DEFAULT = (path, create) -> create
            ? FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW)
            : FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);

    /** Opens path for reading and writing; with create, makes a new file and fails if it exists. */
    FileChannel open(Path path, boolean create) throws IOException;
}
