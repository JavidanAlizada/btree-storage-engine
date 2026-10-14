package dev.storage.file;

import dev.storage.page.PageHeader;
import dev.storage.page.PageType;
import java.nio.ByteBuffer;

/**
 * Page 0: what the file is and where everything starts. The fields follow the common page header, so they sit in
 * the first 64 bytes, which open() reads before it knows the page size.
 *
 * <pre>
 * 32 magic "BTSE" | 36 format version u16 | 38 reserved u16 | 40 pageSize u32 | 44 pageCount u32
 * 48 freeListHead u32 | 52 catalogRoot u32 | 56 checkpointLsn i64
 * </pre>
 *
 * Mutable, guarded by the PageFile's lock.
 */
final class MetaPage {

    static final int MAGIC = 0x42545345; // "BTSE"
    static final int FORMAT_VERSION = 1;
    static final int PREFIX_SIZE = 64;

    private static final int MAGIC_AT = 32;
    private static final int VERSION_AT = 36;
    private static final int PAGE_SIZE_AT = 40;
    private static final int PAGE_COUNT_AT = 44;
    private static final int FREE_LIST_AT = 48;
    private static final int CATALOG_AT = 52;
    private static final int CHECKPOINT_AT = 56;

    final int pageSize;
    int pageCount;
    int freeListHead;
    int catalogRoot;
    long checkpointLsn;

    MetaPage(int pageSize, int pageCount, int freeListHead, int catalogRoot, long checkpointLsn) {
        this.pageSize = pageSize;
        this.pageCount = pageCount;
        this.freeListHead = freeListHead;
        this.catalogRoot = catalogRoot;
        this.checkpointLsn = checkpointLsn;
    }

    /** Checks the magic, version and page size in the first 64 bytes and returns the page size. */
    static int readPageSize(ByteBuffer prefix) {
        if (prefix.getInt(MAGIC_AT) != MAGIC) {
            throw new StorageFormatException("not a btree-storage-engine file (bad magic)");
        }
        int version = Short.toUnsignedInt(prefix.getShort(VERSION_AT));
        if (version != FORMAT_VERSION) {
            throw new StorageFormatException("file format version " + version + ", this build reads only version "
                    + FORMAT_VERSION);
        }
        int pageSize = prefix.getInt(PAGE_SIZE_AT);
        if (!PageHeader.isValidPageSize(pageSize)) {
            throw new StorageFormatException("invalid page size " + pageSize);
        }
        return pageSize;
    }

    static MetaPage read(ByteBuffer page) {
        return new MetaPage(readPageSize(page), page.getInt(PAGE_COUNT_AT), page.getInt(FREE_LIST_AT),
                page.getInt(CATALOG_AT), page.getLong(CHECKPOINT_AT));
    }

    void write(ByteBuffer page) {
        PageHeader.format(page, 0, PageType.META);
        page.putInt(MAGIC_AT, MAGIC);
        page.putShort(VERSION_AT, (short) FORMAT_VERSION);
        page.putInt(PAGE_SIZE_AT, pageSize);
        page.putInt(PAGE_COUNT_AT, pageCount);
        page.putInt(FREE_LIST_AT, freeListHead);
        page.putInt(CATALOG_AT, catalogRoot);
        page.putLong(CHECKPOINT_AT, checkpointLsn);
    }
}
