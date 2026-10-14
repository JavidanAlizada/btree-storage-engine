# Changelog

All notable changes to this project are documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.0.0/); entries are grouped
per milestone, not per commit.

## [Unreleased]

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
