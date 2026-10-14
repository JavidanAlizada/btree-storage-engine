package dev.storage.page;

/** What a page holds, one byte in the header. */
public enum PageType {
    FREE(0), META(1), LEAF(2), INTERNAL(3);

    private static final PageType[] BY_CODE = values();

    private final int code;

    PageType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static PageType of(int code) {
        if (code < 0 || code >= BY_CODE.length) {
            throw new CorruptPageException("unknown page type " + code);
        }
        return BY_CODE[code];
    }
}
