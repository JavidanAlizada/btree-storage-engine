package dev.storage.file;

/** The file isn't a database this version can open: the wrong magic, a newer format, or a bad page size. */
public final class StorageFormatException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public StorageFormatException(String message) {
        super(message);
    }
}
