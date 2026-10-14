# Changelog

All notable changes to this project are documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.0.0/); entries are grouped
per milestone, not per commit.

## [Unreleased]

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
