# ADR 003: Append-only WAL with CRC32, fsync per write

**Date:** 2026-05-07
**Status:** Accepted

## Context

The HNSW index is a complex in-memory structure. Re-serializing it on
every mutation would be prohibitively slow (each insert touches the
graph at multiple layers; serialization is ~100 ms for a 10k-node
index). We need a way to make individual writes durable without
flushing the whole index every time.

## Decision

A standard append-only write-ahead log:

- Each mutation appends a record to `wal.log` before being applied to
  the in-memory index.
- Records carry their own length prefix and a CRC32 checksum so partial
  / corrupt tails can be detected on replay.
- `fsync` is called after every record write by default. Configurable
  per server, but the default is durable-on-write.
- A checkpoint (`IndexStorage.flush()`) writes the in-memory index to
  `index.vex` and truncates the WAL.

## Why CRC32 (and not just length)

Length alone catches torn writes (the OS wrote N of M bytes). It does
NOT catch:

- Bit rot on disk between fsync and read.
- A misbehaving filesystem returning stale-but-consistent-looking
  bytes after a crash.

CRC32 is cheap (~1 ns per byte on modern CPUs) and catches both. We
verify CRC on every replay; mismatch is treated as end-of-log and
replay stops. This matches what RocksDB and SQLite do.

## Why fsync per write (default)

A vector database is rarely a hot loop. The expected workload is:

- Bulk indexing at startup (where fsync-per-write is fine because
  there are at most thousands of inserts and we batch-load anyway).
- Query-heavy steady state with occasional inserts and deletes.

In both cases, the latency hit from `fsync` (~1-3 ms on commodity SSD)
is acceptable and the durability guarantee (acknowledged write =
on-disk) is what most users actually want.

For the bulk-indexing case where fsync-per-write *is* a bottleneck, the
config option `fsyncOnAppend=false` exists. The user accepts that a
crash will lose the most recent few writes.

## Why a length prefix

The length lets the replayer skip records cheaply. It also enables
forward iteration without parsing the variable-length payload.

The size cap (we reject `length > 64 MiB`) is a sanity check against
random bytes accidentally looking like a valid length field.

## What we explicitly chose NOT to do

- **Group commit.** Modern WAL implementations batch fsyncs across
  threads to amortize the syscall cost. Vex is single-writer (the
  `synchronized` on `IndexStorage.add`/`delete` enforces this), so
  group commit doesn't apply. If we add a multi-writer path later, we
  revisit.
- **Compression.** WAL records are small, the format is fixed-width per
  vector, and compression on the hot insert path adds latency for
  little benefit. The checkpoint file (`index.vex`) is already
  compactly-encoded floats; zstd would shave maybe 30% but break the
  mmap-friendly layout.
- **A separate manifest file.** Some systems (LSM, Bigtable) use a
  manifest to track multiple checkpoint files. Vex has exactly one
  active checkpoint and one active WAL, so a manifest adds no value.

## Trade-offs

- Single-writer means we can't scale insert throughput by adding
  threads. For most vector DB workloads (build-once-query-many) this
  is fine. For high-write workloads, this becomes a bottleneck and
  motivates batched writes / sharding.
- Tombstones are durably recorded but not compacted. Long-lived
  collections accumulate dead slots. Compaction is a roadmap item.
- Replay is O(WAL size). A pathological case (millions of writes between
  checkpoints) makes startup slow. Periodic background flushes would
  bound this; not implemented in v1.
