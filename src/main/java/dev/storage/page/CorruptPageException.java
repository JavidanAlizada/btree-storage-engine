package dev.storage.page;

/**
 * A page's bytes don't make sense: a bad checksum, the wrong page id, or a header or slot that points outside the
 * page. Thrown instead of reading out of bounds, so a damaged or hostile file can't serve wrong data.
 */
public final class CorruptPageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CorruptPageException(String message) {
        super(message);
    }
}
