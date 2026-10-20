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
| M2 | Buffer pool: frames, pin/unpin, LRU and Clock | **Done (0.2.0)** |
| M3 | B+Tree: split, merge, range scans, composite keys | **Done (0.3.0)** |
| M4 | Write-ahead log and crash recovery | **Done (0.4.0)** |
| M5 | Concurrent B+Tree (latch crabbing) | Next |
| M6 | Benchmarks (JMH), demo, close-out | Planned |

```java
try (Database db = Database.open(Path.of("data.db"), Options.defaults())) {
    Index users = db.createIndex("users");
    byte[] key = KeyEncoder.create().addString("eu").addLong(42).encode();
    users.put(key, value);
    users.get(key);
    users.delete(key);
    byte[] eu = KeyEncoder.create().addString("eu").encode();
    try (Cursor c = users.scan(eu, Keys.prefixEnd(eu))) {   // every ("eu", *)
        while (c.next()) { c.key(); c.value(); }
    }
}
```

**Crash-safe.** Every `put`, `delete` and `createIndex` is atomic, and
with `Durability.SYNC` (the default) it's on disk when the call returns.
After a crash, `Database.open` recovers from the write-ahead log
(`data.db-wal`): everything acknowledged is there, and a change in flight
is there completely or not at all. `Durability.ASYNC` returns before the
fsync, so a power loss can lose the latest changes, but only a suffix of
them, never half of one. Writers are serialized per index (one lock per
tree) until latch crabbing in M5.

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
* **pageLSN** is the log position of the last change to the page (see
  the write-ahead log below).
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

## The buffer pool

```java
try (BufferPool pool = new BufferPool(file, 1024, new ClockReplacer(1024))) {
    try (PageGuard page = pool.fetch(id)) {      // pinned: stays in its frame
        page.latchShared();                      // the bytes are stable
        SlottedPage.wrap(page.buffer()).cellBytes(0);
    }                                            // unlatched and unpinned
    try (PageGuard page = pool.newPage()) {
        page.latchExclusive();
        PageHeader.format(page.buffer(), page.pageId(), PageType.LEAF);
        page.markDirty();
    }
}
```

**Pins and latches are separate.** A pin keeps a page in its frame, and a
latch (a read-write lock per frame) keeps its bytes consistent. The bytes
may only be read under a latch and changed under the exclusive one. A
B+Tree needs them apart: crabbing pins a child before releasing its
parent's latch, and a split holds pins longer than latches. `PageGuard`
holds one pin, and closing it releases the latch and the pin. A guard
that's never closed is reported by `pool.close()`.

**One pool lock, no I/O under it.** A single lock guards the page table,
the free frames, the pin counts and the replacer, and it's held only for
map and list operations. A page being loaded goes into the table before the
read starts, so a second thread that wants it waits for that read. A page
never has two frames, and is never read twice at once. If the load fails
(a corrupt page), every waiter gets the same exception and the frame goes
back to the pool.

**Writing a dirty victim without losing an update.** The page has to
stay findable while it's being written. Otherwise a concurrent fetch would
read the old version from disk, and a change would be lost. So the victim
stays mapped and pinned, its bytes are copied under the shared latch (and
`dirty` cleared at that moment), and the copy is written with no latch or
lock held. Afterwards the frame is reused only if nobody pinned or changed
it meanwhile. The stress test catches it if this is broken: with the frame
unmapped before the write, updates go missing within seconds.

**Replacers.** `LruReplacer` keeps evictable frames in an intrusive
doubly-linked list over frame indices (O(1), no allocation), ordered by when
they were released. `ClockReplacer` is the second-chance approximation that
PostgreSQL uses. A hit sets a bit instead of relinking a list. Both are
there so M6 can compare them on hit rate and throughput; neither is claimed
to be better before that.

**All frames pinned fails fast** with `BufferPoolFullException`, instead
of waiting. A full pool is a leaked guard or a pool smaller than threads ×
pages held per thread (the tree height, from M3). Waiting would turn a
leak into a hang.

Frames are slices of one direct-memory arena (up to 1 GiB per arena),
allocated once, so the pool's footprint is fixed and outside the heap the
GC scans. The pool doesn't own the `PageFile`: closing the pool flushes
it, and whoever opened the file closes it.

## The B+Tree

**Keys are bytes, compared unsigned.** Typed and composite keys go through
`KeyEncoder`, which writes them so that byte order is the same as logical
order. Integers are big-endian with the sign bit flipped. Strings and byte
strings are escaped (`0x00` becomes `0x00 0xFF`) and end in `0x00 0x00`, so
`"ab"` sorts before `"abc"`, and a later field can't affect how an earlier
string compares. Null is a tag that sorts first. `Keys.prefixEnd(prefix)` is
the exclusive upper bound of every key starting with `prefix`. Strings sort
by code point, not by any locale's collation. Keys are unique; a secondary
index appends the primary key to make them so.

**Nodes are slotted pages.** A leaf cell is `keyLength · key · value`, and
the page's `aux` word links to the right sibling. An internal cell is
`keyLength · key · child`, and `aux` is the leftmost child. A cell is at
most a quarter page, so key + value is at most 2,034 bytes on 8 KiB pages
(1,010 on 4 KiB). Larger entries are rejected, since overflow pages are
Phase 2.

**The root never moves.** When the root splits, its two halves go to new
pages and it becomes their parent. When an internal root is down to one
child, it takes that child's contents and the child is freed. The catalog
can then store a root id once, and M5's crabbing never sees the root
pointer change. The cost is one extra page copy per root split or collapse.

**Splits cut by bytes, not by count.** The cut is the one that makes the
larger half as small as possible. A leaf's separator is the first key of the
right half; in an internal node the middle cell moves up. A split can
propagate up to the root.

**Deletes rebalance under 40%.** An underfull node is merged with a sibling
if both fit in one page. Otherwise the two are redistributed, and the
parent's separator is replaced. The new separator can be longer than the
old one, so replacing it can split the parent, even during a delete. The
fill floor that actually holds is a quarter page (a cut can leave a node at
about 37.5%), and the invariant checker asserts that floor.

**Cursors hold nothing between calls.** A cursor copies the rest of one
leaf, and then finds the next leaf by descending from the root again, for
the first key after the last one it returned. Inside that one call it may
follow the sibling link, while it holds the tree's read lock. A slow
consumer can't pin pages or block writers, and a leaf freed by a merge is
never followed. The cost is one descent per leaf. It isn't a snapshot:
an entry is read as it is when the cursor reaches its leaf.

**The catalog is a B+Tree too.** It maps index names (UTF-8) to root page
ids, and its own fixed root is stored in the meta page. `Database` keeps a
single tree object per index, because the per-tree lock lives on that
object.

## The write-ahead log

```java
Options.defaults()
       .withDurability(Options.Durability.SYNC)   // the default
       .withCheckpointLogBytes(64L << 20);        // checkpoint every 64 MiB of log
```

**Redo-only, physiological.** A log record names a page and says what
changed inside it as a slotted-page operation: insert this cell at slot
k, delete slot k, set `aux`, or a whole page image. Redo calls the same
`SlottedPage` code that made the change. That code depends only on the
page's bytes, compaction included, so replaying a page's records rebuilds
it byte for byte. Test builds check that on every commit: each commit
replays its own records and compares the result with the page in memory.

**Mini-transactions.** A split touches three or more pages. All of a
change's records go to the log as one group, framed by a length, a
CRC32C and the group's start LSN. Recovery redoes whole groups or
nothing, so there's no undo pass. Pages a mini-transaction changes stay
latched until it commits. That way no eviction can write a page whose
log record doesn't exist yet. If anything fails part-way through, the
store stops for good (fail-stop): the pages in memory are then ahead of
the log, so nothing more is written, and reopening recovers.

**The WAL rule.** The buffer pool calls the log before it writes a page
back: the log must be on disk up to the page's LSN first. **Group
commit**: the first thread that needs a flush runs the fsync for
everyone who has appended so far. Threads that arrive while it runs wait
for it, then check again.

**Torn pages.** The first change to a page after a checkpoint logs a
full image of it, without the unused gap between slots and cells.
Recovery overwrites the page with that image and never reads the copy on
disk, so a page torn by a crash is rebuilt. A page whose deltas would
take more room than its image also gets the image; that covers splits
and merges, which rewrite whole pages.

**Allocation is logged too.** The page count, the free-list head and the
catalog root change only inside a mini-transaction, which logs them. The
meta page is written only by checkpoints. A crash can't leave a freed page
in the free list while its parent still points at it.

**Checkpoints** are sharp: they wait for running changes and hold new
ones back, flush the log, write every dirty page, force them, then write
the meta page (with the log position it covers) and force again. Then
they empty the log. They run on `checkpoint()`, `close()`, at the end of
recovery, and on their own once the log passes `checkpointLogBytes`.

**Recovery** starts from the meta page, the last checkpoint. It forces
the log, then redoes every group after the checkpoint, in order. The log
ends at the first group whose CRC or start LSN doesn't match. That tail
is zeroed, so stale groups past a torn one can't come back after new
appends. Then it checkpoints, and the log is empty again.

**Files.** The log grows in preallocated 4 MiB chunks, each forced with
its size once, so appends inside a chunk only need `force(false)`. A new
database is built as `<name>.creating`, then renamed into place, so a
crash during creation leaves nothing half made. Two assumptions,
documented rather than worked around: a 512-byte sector write is atomic
(the log header and the meta page's fields each fit in one, as with
PostgreSQL's `pg_control`). And on macOS, `force` is `fsync`, not
`F_FULLFSYNC`, so a drive's own cache can still lose data on power loss.

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

**One pool lock, not a lock-free page table.** A `ConcurrentHashMap`
table with per-frame state machines would let hits avoid a shared lock.
Eviction then races lookups, and it needs CAS transitions that are much
harder to get right. Without a benchmark showing the lock is contended,
that's an optimization without evidence. It's the first candidate if
M6's numbers point there.

**Copy, then write, on eviction.** Writing straight from the frame would
save an 8 KiB copy, but it would mean holding the latch across disk I/O, or
stamping the checksum into a page that readers are looking at.

**Byte keys and an order-preserving encoding, not comparators.** Typed
comparators in the tree would be more flexible, but they'd put type
dispatch in the innermost loop, and every page would need its schema.
With byte keys, the tree only ever calls `ByteBuffer.mismatch`.

**A fixed root, not a root pointer.** PostgreSQL keeps the root id in a
metapage and updates it on every root split. A fixed root (as in InnoDB)
costs one page copy per root split or collapse, about log n times over a
tree's life. In exchange the catalog never changes, and there's no root
pointer for concurrent readers to race.

**Re-descending cursors, not sibling-walking ones.** Following the
sibling link is one fetch per leaf instead of a descent. But the cursor
would have to keep the leaf pinned between calls, or follow a link to a
page that may have been freed meanwhile. Descending again keeps the cursor
stateless. M6 measures what it costs; the upper levels are usually pool hits.

**A tree lock for now.** M3 lets many readers or one writer into a tree.
It's the simplest scheme that's correct, and the tree code doesn't need
it to change: page latches are already taken as M5's crabbing will need.
A writer starts its mini-transaction before it takes the tree lock. The
other order deadlocks as soon as a checkpoint is waiting.

**Physiological redo, not ARIES and not page images.** Full ARIES
(redo, undo and compensation records) is what real transactions need, and
that's Project 08's job. Logging a page image on every change is
simpler, but it writes about a page of log per `put`. Logical records
("insert k into tree t") are smallest, but redoing them means redoing
splits on a tree that may not be consistent yet.

**Sharp checkpoints, not fuzzy ones.** A fuzzy checkpoint (begin/end
records, a dirty page table, a background writer) doesn't stop writers.
A sharp one is much simpler to get right. It's the brief's Phase 1
choice; fuzzy checkpoints are Phase 2.

**One log file, reset at each checkpoint.** PostgreSQL recycles 16 MiB
segment files, which turns truncation into a file delete. With the log
emptied at every checkpoint, one file is enough. LSNs keep growing
across resets (the new base is the old end), and each group carries its
start LSN, so old bytes can't pass as new groups.

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
  flips a byte on write.
* A golden file: a fixed sequence of operations must produce the committed
  bytes exactly, and those bytes must still open. A failure means the
  on-disk format changed, which needs a new format version.
* Concurrent reads and writes of different pages from 8 threads.
* Replacer tests: LRU order, Clock's second chance, and a random model that
  checks no pinned frame is ever chosen.
* Buffer pool tests: hits and misses, dirty pages surviving eviction and
  reopen, LRU order seen through misses, pool full, a leaked pin at close,
  `free` refused while others hold pins.
* A stress test: 8 threads on 64 pages through 10 frames. Nearly every
  fetch evicts a dirty page, and every page is a counter bumped under its
  exclusive latch. The totals must match in the pool and after reopening.
  Breaking the eviction protocol on purpose makes it fail at once.
* A corrupt page fetched by 8 threads at once: all of them get the error,
  and no pin is left behind.
* Key encoding: the byte order of random encoded tuples matches a tuple
  comparator, and every tuple decodes back to itself.
* B+Tree cases: ascending and random inserts up to height 3 and more,
  upserts that split, maximum-size and empty keys and values; deletes in
  order, in reverse and at random down to a root leaf, with variable-length
  keys so replacement separators are often longer; freed pages reused on
  refill; and range scans with every kind of bound.
* A model test against `TreeMap`: per seed, 4,000 random puts, deletes,
  gets and scans with variable-size keys and values, on 4 KiB pages and a
  24-frame pool. The tree grows to height 4 or more and shrinks back. After
  every operation, a checker walks the whole tree: sorted cells, keys inside
  their separators, all leaves at one depth, the leaf chain, every non-root
  node at least a quarter full, no page reachable twice. It also checks
  that no page is lost: every page is either in the tree or free. Breaking
  merge or redistribute on purpose makes it fail at once.
* Database tests: indexes and data survive reopen, a catalog big enough to
  split, cursors that hold no pins (100 open on a 32-frame pool), and
  writes between cursor calls.

* Log tests: groups round-trip with byte LSNs, groups larger than a chunk,
  a torn tail (zeroed, so a valid group after it doesn't come back), stale
  bytes under a new header, a damaged header, and 8 threads committing at
  once with fewer fsyncs than commits.
* Mini-transaction tests: 3,000 random slot changes replayed from the log
  onto blank pages give the same bytes; an image, then deltas; free-list
  reuse; an uncommitted change fails the store; eviction forces the log
  first.
* Recovery tests: redo after a crash with a checkpoint in the middle, the
  free list and page count recovered, a torn page rebuilt from its image,
  an automatic checkpoint, a missing log after a clean close, and a log
  that starts after the checkpoint rejected.
* **Crash tests.** A `FileChannel` that models the OS page cache in
  512-byte sectors. Writes stay in memory until `force`. At a power cut,
  each unforced sector reaches the disk or not, at random, which also
  tears pages. A random workload through `Database` (index creations,
  puts, deletes, checkpoints) is crashed at every write and fsync it
  reaches, then recovered on the real files. With SYNC the result must
  equal the model after every acknowledged operation, plus the in-flight
  one either wholly or not at all. With ASYNC it must be a prefix of the
  operations. Every tree must pass the invariant checker, and every page
  must be in a tree or free. One crash in seven also crashes recovery,
  and recovers again. One workload is sized to evict dirty pages between
  checkpoints, because otherwise the WAL rule is never exercised.
* The crash tests found a real bug from M1: `PageFile.sync()` wrote the
  meta page in the same unforced batch as the pages it counts, so a crash
  could leave a file shorter than its meta page says. Each of these
  breaks makes the crash tests fail: removing the WAL rule on eviction,
  logging no page images after a checkpoint, or reverting that fix.

No mutation testing tool. That's a scope decision. The mutations above
were done by hand, to show the tests catch what they're meant to.

## CI

Every push to `main` runs: compile → tests → Checkstyle (including an
import-control rule that keeps lower layers from depending on higher ones)
and SpotBugs → JaCoCo → package. Pull requests also run GitHub dependency
review.

## Security

The input surface is the database file and its log. Pages are untrusted
on read: the checksum first, then every offset and length is
bounds-checked before use. Log groups are checked by CRC, start LSN and
length before they're decoded.
There's no Java deserialization anywhere. The file is not encrypted, and
access control is the file system's. Zero runtime dependencies.

## Performance

Nothing has been measured yet, so this README makes no performance claims.
JMH benchmarks (point reads, inserts, range scans, LRU vs. Clock, durability
modes, 1 to 16 threads, against `TreeMap`/`ConcurrentSkipListMap` baselines)
are planned for M6, with the machine and JDK they ran on. Known cost
until then: with SYNC, one index's writers each wait for their own fsync,
because the commit flushes under the tree lock. Writers on different
indexes share fsyncs.

## Containers

None. It's an embedded library, so there's nothing to run in a container.

## Contributing

This is a personal portfolio project, but issues and suggestions are
welcome. Run `./gradlew check` before sending anything; it must pass clean.

## License

MIT
