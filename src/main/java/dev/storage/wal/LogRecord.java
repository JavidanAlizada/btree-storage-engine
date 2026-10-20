package dev.storage.wal;

import dev.storage.file.StorageFormatException;
import dev.storage.page.PageHeader;
import dev.storage.page.PageType;
import dev.storage.page.SlottedPage;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * One entry of a log group. Page records are physiological: they name a page, and say what changed inside it as a
 * slotted-page operation. Redo runs the same SlottedPage code that made the change, which is deterministic in the
 * page's bytes, so replaying a page's records on the page as it was gives exactly the page as it became.
 *
 * <pre>
 * META     kind | pageCount u32 | freeHead u32 | catalogRoot u32
 * IMAGE    kind | pageId u32 | headLength u16 | tailLength u16 | head | tail
 * INSERT   kind | pageId u32 | slot u16 | cellLength u16 | cell
 * DELETE   kind | pageId u32 | slot u16
 * SET_AUX  kind | pageId u32 | value u32
 * </pre>
 */
sealed interface LogRecord {

    byte META = 1;
    byte IMAGE = 2;
    byte INSERT = 3;
    byte DELETE = 4;
    byte SET_AUX = 5;

    int size();

    void encode(ByteBuffer out);

    /** A record that changes one page. */
    sealed interface PageRecord extends LogRecord {
        int pageId();

        void apply(ByteBuffer page);
    }

    /** The allocator's state after the group: page count, free-list head, catalog root. */
    record Meta(int pageCount, int freeHead, int catalogRoot) implements LogRecord {
        public int size() {
            return 13;
        }

        public void encode(ByteBuffer out) {
            out.put(META).putInt(pageCount).putInt(freeHead).putInt(catalogRoot);
        }
    }

    /**
     * A whole page, minus the unused gap between its slot array and its cell area: the head is [0, end of slots), the
     * tail [cellStart, pageSize). Redo zeroes the page and puts both back.
     */
    record Image(int pageId, byte[] head, byte[] tail) implements PageRecord {
        static Image of(int pageId, ByteBuffer page) {
            SlottedPage slotted = SlottedPage.wrap(page);
            int headLength = PageHeader.SIZE + slotted.slotCount() * SlottedPage.SLOT_SIZE;
            int cellStart = slotted.cellStart();
            byte[] head = new byte[headLength];
            byte[] tail = new byte[page.capacity() - cellStart];
            page.get(0, head);
            page.get(cellStart, tail);
            return new Image(pageId, head, tail);
        }

        /** Bytes an image of this page would take. */
        static int sizeOf(ByteBuffer page) {
            SlottedPage slotted = SlottedPage.wrap(page);
            return 9 + PageHeader.SIZE + slotted.slotCount() * SlottedPage.SLOT_SIZE + page.capacity()
                    - slotted.cellStart();
        }

        /** Zeroes the gap an image leaves out, so the page in memory and the page redo builds are the same bytes. */
        static void zeroGap(ByteBuffer page) {
            SlottedPage slotted = SlottedPage.wrap(page);
            int from = PageHeader.SIZE + slotted.slotCount() * SlottedPage.SLOT_SIZE;
            int to = slotted.cellStart();
            for (int i = from; i < to; i++) {
                page.put(i, (byte) 0);
            }
        }

        public int size() {
            return 9 + head.length + tail.length;
        }

        public void encode(ByteBuffer out) {
            out.put(IMAGE).putInt(pageId).putChar((char) head.length).putChar((char) tail.length).put(head).put(tail);
        }

        public void apply(ByteBuffer page) {
            if (head.length + tail.length > page.capacity()) {
                throw new StorageFormatException("image of page " + Integer.toUnsignedString(pageId)
                        + " is larger than a page");
            }
            for (int i = 0; i < page.capacity(); i += 8) {
                page.putLong(i, 0L);
            }
            page.put(0, head);
            page.put(page.capacity() - tail.length, tail);
        }
    }

    record Insert(int pageId, int slot, byte[] cell) implements PageRecord {
        public int size() {
            return 9 + cell.length;
        }

        public void encode(ByteBuffer out) {
            out.put(INSERT).putInt(pageId).putChar((char) slot).putChar((char) cell.length).put(cell);
        }

        public void apply(ByteBuffer page) {
            if (!SlottedPage.wrap(page).insert(slot, cell)) {
                throw new IllegalStateException("redo of an insert into page " + Integer.toUnsignedString(pageId)
                        + " slot " + slot + " doesn't fit: the page isn't the one the record was made on");
            }
        }
    }

    record Delete(int pageId, int slot) implements PageRecord {
        public int size() {
            return 7;
        }

        public void encode(ByteBuffer out) {
            out.put(DELETE).putInt(pageId).putChar((char) slot);
        }

        public void apply(ByteBuffer page) {
            SlottedPage.wrap(page).delete(slot);
        }
    }

    record SetAux(int pageId, int value) implements PageRecord {
        public int size() {
            return 9;
        }

        public void encode(ByteBuffer out) {
            out.put(SET_AUX).putInt(pageId).putInt(value);
        }

        public void apply(ByteBuffer page) {
            PageHeader.setAux(page, value);
        }
    }

    static ByteBuffer encode(List<LogRecord> records) {
        int size = 0;
        for (LogRecord r : records) {
            size += r.size();
        }
        ByteBuffer out = ByteBuffer.allocate(size);
        for (LogRecord r : records) {
            r.encode(out);
        }
        return out.flip();
    }

    static List<LogRecord> decode(ByteBuffer body) {
        ByteBuffer in = body.duplicate();
        List<LogRecord> records = new ArrayList<>();
        try {
            while (in.hasRemaining()) {
                byte kind = in.get();
                switch (kind) {
                    case META -> records.add(new Meta(in.getInt(), in.getInt(), in.getInt()));
                    case IMAGE -> {
                        int pageId = in.getInt();
                        byte[] head = new byte[in.getChar()];
                        byte[] tail = new byte[in.getChar()];
                        in.get(head).get(tail);
                        records.add(new Image(pageId, head, tail));
                    }
                    case INSERT -> {
                        int pageId = in.getInt();
                        int slot = in.getChar();
                        byte[] cell = new byte[in.getChar()];
                        in.get(cell);
                        records.add(new Insert(pageId, slot, cell));
                    }
                    case DELETE -> records.add(new Delete(in.getInt(), in.getChar()));
                    case SET_AUX -> records.add(new SetAux(in.getInt(), in.getInt()));
                    default -> throw new StorageFormatException("unknown log record kind " + kind);
                }
            }
        } catch (BufferUnderflowException e) {
            throw new StorageFormatException("log group ends inside a record");
        }
        return records;
    }

    /** A blank FREE page, the starting point for redoing a page whose first record is an image. */
    static void blank(ByteBuffer page, int pageId) {
        PageHeader.format(page, pageId, PageType.FREE);
    }
}
