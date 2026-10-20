package dev.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.storage.file.PageFile;
import dev.storage.key.KeyDecoder;
import dev.storage.key.KeyEncoder;
import dev.storage.key.Keys;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseTest {

    @TempDir
    Path dir;

    private static final Options SMALL = Options.defaults().withPageSize(4096).withPoolFrames(16);

    private static byte[] user(String region, long id) {
        return KeyEncoder.create().addString(region).addLong(id).encode();
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void indexesAndTheirDataSurviveReopen() {
        Path path = dir.resolve("data.db");
        try (Database db = Database.open(path, SMALL)) {
            Index users = db.createIndex("users");
            Index orders = db.createIndex("orders");
            for (long id = 0; id < 2_000; id++) {
                users.put(user(id % 2 == 0 ? "eu" : "us", id), bytes("user-" + id));
                orders.put(KeyEncoder.create().addLong(id).encode(), new byte[200]);
            }
            assertTrue(users.delete(user("eu", 0)));
        }
        try (Database db = Database.open(path, SMALL)) {
            assertEquals(List.of("orders", "users"), db.indexNames());
            Index users = db.index("users");
            assertNull(users.get(user("eu", 0)));
            assertArrayEquals(bytes("user-7"), users.get(user("us", 7)));

            byte[] eu = KeyEncoder.create().addString("eu").encode();
            List<Long> ids = new ArrayList<>();
            try (Cursor c = users.scan(eu, Keys.prefixEnd(eu))) {
                while (c.next()) {
                    KeyDecoder d = new KeyDecoder(c.key());
                    assertEquals("eu", d.readString());
                    ids.add(d.readLong());
                }
            }
            assertEquals(999, ids.size());
            assertEquals(2L, ids.get(0));
            assertEquals(1_998L, ids.get(ids.size() - 1));

            int orders = 0;
            try (Cursor c = db.index("orders").scan(null, null)) {
                while (c.next()) {
                    orders++;
                }
            }
            assertEquals(2_000, orders);
        }
    }

    @Test
    void aCatalogOfManyIndexesSplitsAndSurvivesReopen() {
        Path path = dir.resolve("data.db");
        String pad = "x".repeat(200); // long names: the catalog tree needs several levels
        try (Database db = Database.open(path, SMALL)) {
            for (int i = 0; i < 300; i++) {
                db.createIndex(String.format("%03d", i) + pad).put(bytes("k"), bytes("v" + i));
            }
        }
        try (Database db = Database.open(path, SMALL)) {
            List<String> names = db.indexNames();
            assertEquals(300, names.size());
            for (int i = 0; i < 300; i++) {
                assertEquals(String.format("%03d", i) + pad, names.get(i));
                assertArrayEquals(bytes("v" + i), db.index(names.get(i)).get(bytes("k")));
            }
        }
    }

    @Test
    void theSameNameGivesTheSameIndex() {
        try (Database db = Database.open(dir.resolve("data.db"), SMALL)) {
            Index a = db.createIndex("a");
            assertSame(a, db.index("a"));
            assertSame(db.index("a"), db.index("a"));
        }
    }

    @Test
    void badNamesAreRejected() {
        try (Database db = Database.open(dir.resolve("data.db"), SMALL)) {
            db.createIndex("a");
            assertThrows(IllegalArgumentException.class, () -> db.createIndex("a"));
            assertThrows(NoSuchElementException.class, () -> db.index("b"));
            assertThrows(IllegalArgumentException.class, () -> db.createIndex(""));
            assertThrows(IllegalArgumentException.class, () -> db.createIndex("n".repeat(2_000)));
            assertEquals(List.of("a"), db.indexNames());
        }
    }

    @Test
    void theFileKeepsItsPageSizeWhateverTheOptionsSay() {
        Path path = dir.resolve("data.db");
        try (Database db = Database.open(path, SMALL)) {
            db.createIndex("a").put(bytes("k"), bytes("v"));
        }
        try (Database db = Database.open(path, Options.defaults().withPageSize(16 * 1024))) {
            assertArrayEquals(bytes("v"), db.index("a").get(bytes("k")));
        }
        try (PageFile file = PageFile.open(path)) {
            assertEquals(4096, file.pageSize());
        }
    }

    @Test
    void checkpointMakesChangesVisibleInTheFile() {
        Path path = dir.resolve("data.db");
        try (Database db = Database.open(path, SMALL)) {
            Index a = db.createIndex("a");
            for (int i = 0; i < 100; i++) {
                a.put(bytes("k" + i), new byte[500]);
            }
            db.checkpoint();
            // Another reader of the file sees the catalog root the checkpoint wrote.
            try (PageFile file = PageFile.open(path)) {
                assertTrue(file.catalogRoot() != 0);
                assertTrue(file.pageCount() > 10);
            }
        }
    }

    @Test
    void nothingWorksAfterCloseExceptClose() {
        Database db = Database.open(dir.resolve("data.db"), SMALL);
        Index a = db.createIndex("a");
        db.close();
        db.close();
        assertThrows(IllegalStateException.class, () -> a.get(bytes("k")));
        assertThrows(IllegalStateException.class, () -> a.put(bytes("k"), bytes("v")));
        assertThrows(IllegalStateException.class, () -> db.index("a"));
        assertThrows(IllegalStateException.class, db::checkpoint);
    }

    @Test
    void optionsAreValidated() {
        var clock = Options.ReplacerPolicy.CLOCK;
        assertThrows(IllegalArgumentException.class, () -> new Options(5000, 1_024, clock));
        assertThrows(IllegalArgumentException.class, () -> new Options(8192, 2, clock));
        assertThrows(IllegalArgumentException.class, () -> new Options(8192, 1_024, null));
        assertEquals(new Options(4096, 16, clock), SMALL);
    }
}
