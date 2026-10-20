# Changelog

All notable changes to this project are documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.0.0/); entries are grouped
per milestone, not per commit.

## [Unreleased]

## [0.4.0] — 2026-10-20

### Milestone 4 — Write-ahead log and crash recovery

- `dev.storage.wal.Wal`: one log file with a sector-sized header (base
  LSN). Groups are framed by length, CRC32C and start LSN; an LSN is a
  byte position. The file is preallocated in 4 MiB chunks so appends need
  only `force(false)`. Group commit: one fsync covers every waiting
  thread. Torn and stale tails are detected and zeroed.
- `LogRecord`: physiological records META, IMAGE (without the free gap),
  INSERT, DELETE and SET_AUX. Redo runs the same `SlottedPage` code.
- `Mtr` (mini-transaction): a change's records go out as one group. Its
  pages stay latched until commit. A page's first change after a
  checkpoint logs a full image (torn-page repair). An uncommitted change
  fails the store for good. A test-only redo check replays every commit
  and compares the bytes.
- Logged page allocation: the page count, free-list head and catalog root
  are logged, and the meta page is written only at checkpoints.
- `PageStore`: the page file, buffer pool, log and allocator. Sharp
  checkpoints, automatic once the log passes `checkpointLogBytes`. Redo
  recovery on open from the last checkpoint, idempotent by page LSN,
  forcing the log before any redo.
- `BufferPool`: a `LogFlusher` hook enforces the WAL rule on every
  write-back; `pinBlank`; `fail` stops all write-back.
- B+Tree: every change goes through a mini-transaction. `createIndex`
  commits the new root and its catalog entry together.
- `Options`: `Durability.SYNC` (default) / `ASYNC`, and
  `checkpointLogBytes`. The minimum pool is now 16 frames.
- New databases are created atomically (temporary name, then rename).
- Fixed: `PageFile.sync()` wrote the meta page together with the data
  pages it counts. It now forces the data first, so a crash can't leave
  the meta page counting pages that never reached the file.
- Tests: log, mini-transaction and recovery tests, plus crash tests on a
  page-cache-modelling `FileChannel`. Those crash at every write and
  fsync, tear pages at sector boundaries, crash recovery itself, and check
  SYNC/ASYNC durability, atomicity, tree invariants and lost pages.
  Hand mutations (no WAL rule, no page images, the old sync order) are
  all caught.
- Not yet: concurrent writers in one tree (M5), fuzzy checkpoints and
  log archiving (Phase 2), no performance claims before M6.

## [0.3.0] — 2026-10-20

### Milestone 3 — B+Tree

- `dev.storage.key`: `KeyEncoder` / `KeyDecoder`, an order-preserving
  encoding for null, int, long, string and bytes, and composite keys built
  from them; `Keys.compare` and `Keys.prefixEnd`.
- `dev.storage.btree.BTree`: unique byte keys in slotted-page leaves and
  internal nodes. `get`, `put` (upsert), `delete` and `scan`. Splits
  are byte-balanced and propagate to the root. Deletes merge or
  redistribute under 40% full. The root page never moves (split and
  collapse copy into it).
- Range cursors over [from, to): they copy one leaf at a time and
  re-descend for the next, holding no pins between calls. Not snapshots.
- `dev.storage`: `Database` (open/create, `createIndex`, `index`,
  `indexNames`, `checkpoint`, `close`), `Options`, `Index` and `Cursor`.
  The catalog is a B+Tree rooted at the meta page's `catalogRoot`.
- `PageFile.catalogRoot()` / `setCatalogRoot()`.
- Concurrency: one read-write lock per tree; page latches as in M2.
- Tests: key ordering and round trips, split/merge/redistribute/collapse
  cases, cursors, a `TreeMap` model test with a full invariant check and a
  lost-page check after every operation, and persistence through
  `Database`.
- Not yet: crash safety (M4), concurrent writers (M5), `dropIndex`,
  overflow values, prefix compression and suffix truncation (Phase 2).

## [0.2.0] — 2026-10-15

### Milestone 2 — Buffer pool

- `dev.storage.buffer.BufferPool`: a fixed set of frames over one
  `PageFile`. `fetch`, `newPage` (formatted in memory, no read), `free`,
  `flush`, `flushAll`, `stats`, and `close`, which reports leaked pins.
- `PageGuard`: one pin per guard, with a separate shared/exclusive latch
  per frame. The bytes are readable only under a latch, and `markDirty`
  needs the exclusive one. `close()` releases both.
- One pool lock for the table, pins and replacer, with no I/O under it.
  A page being loaded is in the table before its read, so concurrent
  fetchers wait for that one read. A failed load reaches every waiter, and
  the frame returns to the pool.
- Dirty eviction keeps the victim mapped and pinned, copies it under the
  shared latch, writes the copy with no latch or lock held, and reuses the
  frame only if nobody touched it meanwhile.
- `Replacer` with `LruReplacer` (an intrusive list, by release order) and
  `ClockReplacer` (second chance).
- `BufferPoolFullException` when every frame is pinned (fail fast, no wait).
- Frames are slices of direct-memory arenas allocated once.
- Tests: replacer unit and model tests, pool unit tests, an 8-thread
  eviction stress test with counters (it fails at once if the eviction
  protocol is broken on purpose), and a corrupt page fetched by many
  threads at once.
- Not yet: the WAL rule on eviction (M4); no performance claims before
  the M6 benchmarks.

## [0.1.0] — 2026-10-15

### Milestone 1 — Pages and the page file

- Project skeleton: Gradle, Java 21, Checkstyle (with layer import control),
  SpotBugs, JaCoCo, CI on every push. Zero runtime dependencies.
- `dev.storage.page`:
  - `PageHeader`: the 32-byte header every page starts with (CRC32C,
    pageLSN reserved for the WAL, type, self-identifying page id, slotted
    fields, a type-defined `aux` word). Pages are 4–32 KiB, powers of two.
  - `SlottedPage`: variable-length cells in slot order, packed from the
    page end. Deletes leave holes, and compaction runs only when an insert
    needs them. Max cell is a quarter page minus its slot (at least 4 per
    page). Every read is bounds-checked and throws `CorruptPageException`.
  - `PageType`: FREE, META, LEAF, INTERNAL.
- `dev.storage.file`:
  - `PageFile`: create/open, positional read/write, CRC32C stamped on write
    and verified on read, all-zero and misdirected pages detected, `sync()`
    with `force(true)` only after the file grew.
  - Meta page 0: magic `BTSE`, format version 1, page size, page count,
    free-list head, catalog root and checkpoint LSN (both reserved).
  - A free-page list linked through `aux`. `allocate()` reuses the most
    recently freed page before growing the file.
  - Fail-stop after an fsync error (`StorageFailedException`); wrong or
    newer files give `StorageFormatException`.
- Tests: unit tests, a seeded model test of the slotted page against a
  `List`, corruption tests, fault injection (failed fsync, flipped byte),
  a golden file pinning the on-disk format, and concurrent page I/O.
- Known limitation: not crash-safe for allocation until the WAL (M4).
- Scope notes: no mutation testing; `module-info` arrives in M6; no
  benchmarks before M6, so no performance claims.
