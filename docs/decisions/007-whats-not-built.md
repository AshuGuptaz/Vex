# ADR 007: What's NOT built, and why

**Date:** 2026-05-07
**Status:** Accepted

A portfolio-grade vector database is a *bounded* exercise. The most
important design decision a v1 makes is what to leave out. This ADR
documents the things a careful reviewer might expect to see and the
reasoning for excluding them.

## Sharding

**What:** distribute vectors across multiple Vex nodes by hash or range,
serve queries by fan-out + merge.

**Why not v1:** sharding is a multi-week project on its own. It needs a
membership protocol, a routing layer, a re-balancing strategy when
nodes are added/removed, and a story for cross-shard top-k merge.
Building any of those properly would compete with the index quality
work that's the actual point of this project.

**Tells you:** I picked depth over breadth.

## Replication

**What:** keep N copies of each shard for read throughput and crash
tolerance.

**Why not v1:** single-node durability is solved (WAL + atomic
checkpoint, ADR 003). Multi-node replication needs a consensus protocol
or async log shipping; both are big enough to be their own project.

**Tells you:** distributed consensus is not the lesson I was here to
teach myself.

## Product quantization (PQ)

**What:** split each vector into chunks, k-means cluster each chunk
independently, replace each chunk with its centroid id. Gives 16x-32x
compression with 1-3% recall loss.

**Why not v1:** PQ is a compression algorithm with non-trivial training
(k-means in chunk-space) and a bespoke distance kernel (precomputed
ADC table per query). It's a distinct learning project from "how does
HNSW work." Scalar quantization (ADR 005) is the right v1 entry point.

**Tells you:** I chose a compression scheme I could implement *and*
explain in an afternoon over one I'd just be cargo-culting.

## SIMD-accelerated distance kernels

**What:** use Java Vector API (`jdk.incubator.vector`) or Panama
foreign function interface to dispatch SIMD instructions in the
distance loop. Realistic 4-8x speedup on dim 128.

**Why not v1:** Vector API is still incubator. The portability story
isn't great (different code paths for ARM vs x86, fallback paths for
non-supporting JVMs). And honestly, the recall numbers in
docs/benchmarks.md tell me the index quality is the bottleneck, not the
distance kernel.

**Tells you:** I picked the work that mattered for users over the work
that would have looked impressive in benchmarks.

## Off-heap vector storage

**What:** allocate the `float[][]` of vectors via `MemorySegment` or
`Unsafe.allocateMemory` so they live outside the GC's reach. Standard
optimization in Lucene's HNSW codec.

**Why not v1:** the ergonomics of off-heap in Java 17 are mid. JDK 25
makes it tractable (Foreign Function & Memory API is final). For v1,
on-heap with a documented memory ceiling is the more honest place to
land.

**Tells you:** I know about it; I picked the JDK-17-target trade-off
deliberately.

## Authentication

**What:** API keys, mTLS, OAuth.

**Why not v1:** Vex is meant to be deployed behind a private network
or a reverse proxy that handles auth. Adding auth to the server itself
would be plumbing without insight, and a reviewer can verify in 30
seconds that I made this choice intentionally (see deployment.md).

**Tells you:** I read enough Hyrum's-Law to know where the trust
boundary lives in a system like this.

## Multi-modal hybrid search (vector + keyword)

**What:** combine BM25 lexical search with HNSW dense retrieval, RRF
the results.

**Why not v1:** a hybrid retriever needs a working keyword index
(Lucene or hand-rolled inverted index) and a fusion strategy. That's
its own portfolio project.

**Tells you:** I know what hybrid search is and didn't pretend Vex
does it.

## Per-collection access control / multi-tenancy

**What:** namespacing + per-collection tokens.

**Why not v1:** the deployment model is "one Vex per tenant," which is
the standard pattern for early-stage vector DB usage. Multi-tenancy is
a substantial design problem (resource quotas, noisy-neighbor
isolation, payload-level access control); too much for v1.

**Tells you:** I scoped to a deployment shape that doesn't need it.

## Online compaction

**What:** background process that compacts away tombstoned vectors
and re-balances graph connections.

**Why not v1:** the tombstone-then-skip strategy works correctly for
the lifetime of a portfolio demo. Long-running production indexes
need this; portfolio demos don't.

**Tells you:** I noticed the failure mode in ADR 003 and chose not to
solve it speculatively.

## Snapshot / restore via REST API

**What:** `POST /collections/{name}/snapshot`, returns a path or a
streamed tarball.

**Why not v1:** the on-disk directory layout *is* the snapshot. A user
can `tar czf` the directory; deployment.md documents this. Adding a
REST endpoint is a thin wrapper that buys nothing on top.

**Tells you:** I let the simpler primitive do the work.

---

## What I would build first if this project continued

1. Close the recall gap at 100k+ scale (probably an over-pruning bug
   in SELECT-NEIGHBORS-HEURISTIC at scale).
2. Land the quantized integration end-to-end so the memory savings
   actually show up in the index.
3. Add SIMD distance kernels via Java Vector API (or wait for it to
   leave incubator).
4. Build a hybrid (vector + keyword) retriever — the single biggest
   capability jump for real RAG workloads.
