# ADR 002: Memory-mapped read for the index file

**Date:** 2026-05-07
**Status:** Accepted

## Context

After a crash or restart, Vex needs to load `index.vex` (potentially
hundreds of megabytes for large collections) and reconstruct the
in-memory HNSW state. The options:

1. **Buffered stream read.** Open the file with `BufferedInputStream`,
   parse sequentially, allocate Java arrays as we go.
2. **`FileChannel` + manual buffering.** Read fixed-size chunks into
   `ByteBuffer`s, parse from there.
3. **Memory-mapped via `FileChannel.map`.** Map the file into the JVM's
   address space, parse off the resulting `MappedByteBuffer`.

## Decision

Use option 3 — memory-mapped read via `FileChannel.map(MapMode.READ_ONLY,
0, fileSize)`.

## Why

- **One syscall to bring the file in scope.** Subsequent reads happen
  against memory, not the kernel — fewer context switches, no manual
  buffer management.
- **Lazy paging.** The kernel pulls pages in on first touch. Cold-restart
  cost is amortized across the parse, not paid upfront.
- **Direct decoding.** `MappedByteBuffer.getInt()`, `getFloat()`, etc.
  decode in place without intermediate `byte[]` copies.
- **Same code path scales.** Whether `index.vex` is 50 MB or 5 GB, the
  read code doesn't change.

## What we explicitly do *not* claim

We do NOT memory-map the file for *queries*. The HNSW algorithm needs
random access into a `float[][]` of vectors and an `int[][][]` of
graph connections — these live on the Java heap. The mmap is purely
a load-time optimization.

A future v2 could keep vectors in an off-heap mmap'd region (similar to
Lucene's vectors codec) to eliminate the heap copy and shrink GC
pressure. Out of scope for this iteration.

## Consequences

- Reads are simple and fast.
- Files larger than `Integer.MAX_VALUE` bytes (2 GiB) need chunked
  mapping. We accept that 2 GiB-per-collection limit for now and
  document it; larger collections require a follow-up to map regions
  on demand.
- The mapped buffer remains valid only while the `FileChannel` is open.
  We close the channel after parsing — the data is already in the
  Java heap by then.

## Write path

Writes use `FileChannel.write(ByteBuffer)` with explicit `ch.force(true)`.
We do not mmap for writes because (a) the file size is unknown until
serialization completes, and (b) mmap'd writes need careful
truncation/extension dance that buys nothing here.

The write path uses temp file + atomic rename so the previous valid
file remains visible to readers if the write fails midway.
