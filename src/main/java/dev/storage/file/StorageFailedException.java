package dev.storage.file;

/**
 * The file can't be trusted any more because an fsync failed. Whatever the OS had buffered may never reach the disk,
 * and a retried fsync can report success for pages it already dropped, so the only safe way on is to close the
 * file and reopen it.
 */
public final class StorageFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public StorageFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
