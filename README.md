# btree-storage-engine

An embedded, disk-backed key-value storage engine for the JVM: B+Tree indexes
in fixed-size pages, a buffer pool, a write-ahead log and crash recovery,
built from first principles in Java 21 with no runtime dependencies.

The goal isn't to replace RocksDB or SQLite. It's to build the parts that make
a storage engine hard (page layout, checksums and torn writes, write ordering
against the OS, splits and merges, the WAL rule, recovery from any crash point,
latch coupling) and to write the reasoning down next to the code.

## Status

| Milestone | Scope | State |
|---|---|---|
| M1 | Pages and the page file | **Done (0.1.0)** |
| M2 | Buffer pool: frames, pin/unpin, LRU and Clock | Next |
| M3 | B+Tree: split, merge, range scans, composite keys | Planned |
| M4 | Write-ahead log and crash recovery | Planned |
| M5 | Concurrent B+Tree (latch crabbing) | Planned |
| M6 | Benchmarks (JMH), demo, close-out | Planned |

There is no key-value API yet. What exists today is the bottom layer: a file
of checksummed pages and the slotted-page format every tree node will use.

**Not crash-safe yet.** A crash between growing the file (or changing the
free list) and writing the meta page can leak a page or lose a free-list
entry. Nothing is corrupted silently: a leaked tail is detected and
reported at open. The write-ahead log in M4 makes allocation atomic.

## Build and test

```
./gradlew check     # compile, tests, Checkstyle, SpotBugs, JaCoCo
```

Java 21 (the Gradle toolchain downloads it if needed). Zero runtime
dependencies; tests use JUnit 5.

## Page format

Every page, whatever it holds, starts with the same 32-byte header.
Integers are big-endian.

```
 0          4                  12     13     14       16         20         22         24         26         28         32
 ┌──────────┬──────────────────┬──────┬──────┬────────┬──────────┬──────────┬──────────┬──────────┬──────────┬──────────┐
 │ crc32c   │ pageLSN          │ type │flags │reserved│ pageId   │slotCount │ cellStart│fragmented│ reserved │ aux      │
 └──────────┴──────────────────┴──────┴──────┴────────┴──────────┴──────────┴──────────┴──────────┴──────────┴──────────┘
```

* **crc32c** covers everything after itself. It's stamped when a page is
  written to disk and checked when it's read back, never in between.
* **pageLSN** is reserved for the write-ahead log (M4), so the format won't
  have to change when recovery arrives.
* **pageId** is the page's own number. A page that checksums fine but sits
  at the wrong offset was written or read to the wrong place. That
  failure is real on some storage, and a checksum alone can't see it.
* **aux** depends on the type: a leaf's right sibling, an internal node's
  leftmost child, or a free page's next free page.

Pages are 4, 8, 16 or 32 KiB, 8 KiB by default. Offsets inside a page are
16-bit, which is what rules out 64 KiB.

### Slotted pages

```
 | header | slot 0 | slot 1 | ... →        free        ← ... | cell | cell |
                                                        ↑ cellStart
```

Cells are variable length. Slots (a 16-bit offset and length each) grow
forward from the header and stay in logical order, so slot *i* is the
*i*-th entry and a binary search runs over the slot array. Cells are packed
from the end of the page in whatever order they arrived. A delete leaves a
hole, unless the cell was the lowest one, and compaction happens only when
an insert needs the space the holes hold. A cell can be up to a quarter of
the page minus its slot (2,036 bytes at 8 KiB), so every page holds at
least four.

Every read checks the header and slots against the page bounds first. A
damaged or hostile page throws `CorruptPageException` instead of reading
out of bounds.

## The page file

Page *n* lives at byte *n* × page size. Page 0 is the meta page: the magic
`BTSE`, a format version, the page size, the page count, the head of the
free-page list, the catalog root (M3) and the checkpoint LSN (M4).

```java
try (PageFile file = PageFile.create(Path.of("data.db"), 8192)) {
    int id = file.allocate();             // a freed page if there is one, else a new one
    ByteBuffer page = ByteBuffer.allocateDirect(8192);
    PageHeader.format(page, id, PageType.LEAF);
    SlottedPage.wrap(page).insert(0, bytes);
    file.write(id, page);                 // stamps the checksum
    file.sync();                          // meta page if changed, then fsync
}
```

* **Free pages** form a list linked through their `aux` field, with the
  head in the meta page. `allocate()` reuses the most recently freed page
  before growing the file.
* **Corruption** is reported, never served: a bad checksum, an all-zero
  page (a valid page never is, because growing the file always writes a
  formatted page), a wrong page id, a truncated file, or a broken free-list
  link all throw `CorruptPageException`. A file that isn't ours, or is a
  newer format, throws `StorageFormatException` before anything else is
  read.
* **fsync failure is final.** If `force()` fails, the `PageFile` refuses
  every later call with `StorageFailedException`. After a failed fsync, the
  OS may already have dropped the dirty pages and can report a retried fsync
  as successful (PostgreSQL's 2018 "fsyncgate"), so the only safe way on is
  to reopen.
* **Concurrency:** reads and writes of different pages run in parallel
  through positional `FileChannel` calls, with no lock. The caller (the
  buffer pool, from M2) guarantees one writer per page. Allocation, free
  and the meta page share one lock.

## Design decisions

**`FileChannel`, not `mmap`.** The engine must decide when a dirty page
reaches disk, because the write-ahead log has to be on disk first. With
`mmap` the OS writes pages back whenever it likes, and I/O errors arrive as
faults instead of exceptions. Reading and writing through the engine's
own buffers costs a copy and more code, and it keeps write ordering under
the engine's control.

**One fixed 32-byte header for every page type.** SQLite uses different
headers per page type and saves a few bytes on some of them. One layout
means one parser, and any page's type, id and checksum can be read without
knowing what it is.

**Lazy compaction.** Compacting on every delete would keep pages tidy, but
it copies up to a whole page per delete. Holes are counted instead, and
reclaimed only when an insert needs them.

**A free list, not a free-space bitmap.** A bitmap (as in InnoDB's extent
descriptors) is better at finding contiguous runs and spotting leaks.
Nothing in Phase 1 needs contiguous pages, and the list needs no extra
page type.

**Big-endian, fixed-width fields.** These are the `ByteBuffer` defaults.
Byte order on disk matters only for portability, and a fixed width keeps
every field at a fixed offset.

## Testing

* Unit tests for the header, the slotted page and the page file.
* A model-based test that runs 20,000 random inserts, deletes and
  compactions per seed against a `List<byte[]>`, on 4, 8 and 32 KiB pages,
  and checks every cell and the exact free space after each step. It found
  a real bug before the first commit. An empty cell at the low edge of the
  cell area was left outside it when its neighbour was reclaimed. Empty
  cells now always point at the page end.
* Corruption tests: a flipped byte, a zeroed page, a misdirected page, a
  truncated file, a wrong magic or version, and a broken free-list link.
* Fault injection through a `FileChannel` wrapper that fails fsync or
  flips a byte on write. M4 adds crashes and torn writes to it.
* A golden file: a fixed sequence of operations must produce the committed
  bytes exactly, and those bytes must still open. A failure means the
  on-disk format changed, which needs a new format version.
* Concurrent reads and writes of different pages from 8 threads.

No mutation testing. That's a scope decision; the model and fault tests
cover the bugs that matter here.

## CI

Every push to `main` runs: compile → tests → Checkstyle (including an
import-control rule that keeps lower layers from depending on higher ones)
and SpotBugs → JaCoCo → package. Pull requests also run GitHub dependency
review.

## Security

The input surface is the database file. Pages are untrusted on read: the
checksum first, then every offset and length is bounds-checked before use.
There's no Java deserialization anywhere. The file is not encrypted, and
access control is the file system's. Zero runtime dependencies.

## Performance

Nothing has been measured yet, so this README makes no performance claims.
JMH benchmarks (point reads, inserts, range scans, LRU vs. Clock, durability
modes, 1 to 16 threads, against `TreeMap`/`ConcurrentSkipListMap` baselines)
are planned for M6, with the machine and JDK they ran on.

## Containers

None. It's an embedded library, so there's nothing to run in a container.

## Contributing

This is a personal portfolio project, but issues and suggestions are
welcome. Run `./gradlew check` before sending anything; it must pass clean.

## License

MIT
