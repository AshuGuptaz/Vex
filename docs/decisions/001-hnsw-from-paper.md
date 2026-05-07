# ADR 001: Implement HNSW from the paper, not via a library

**Date:** 2026-05-07
**Status:** Accepted

## Context

Vex needs an approximate nearest-neighbor index. The realistic options were:

- **HNSW** — graph-based, the de-facto winner on most realistic dense-vector benchmarks.
- **IVF (Inverted File)** — clusters first, searches a subset of clusters.
- **LSH** — locality-sensitive hashing into multiple hash tables.
- **KD-tree / Ball-tree** — recursive space partitioning.

For the index implementation specifically, two further choices:

- Pull in a battle-tested library (`hnswlib-jna`, Lucene's `KnnVectorsWriter`, JVector).
- Write HNSW from the original paper.

## Decision

Implement HNSW from Malkov & Yashunin (2016), no third-party ANN dependency.

## Why HNSW over the alternatives

- **High-dim friendliness.** KD-trees and ball-trees collapse to brute force above
  ~20 dimensions. The 128-dim and 768-dim cases that motivate vector DBs land
  squarely in the territory where HNSW's graph topology dominates.
- **Two intuitive knobs.** M and efSearch trade memory/latency for recall in
  predictable ways. LSH has 4+ knobs and they don't compose cleanly.
- **No clustering pre-pass.** IVF needs k-means up front; recall is bounded by
  how well the clustering matches query distribution. HNSW has no such pre-pass
  and no such ceiling.
- **Insert is online.** No need to batch up vectors before training a clusterer.

## Why neighbor-selection heuristic (Algorithm 4) over simple top-M

Two neighbor-selection strategies appear in the paper:

- **Simple:** pick the M nearest candidates as neighbors.
- **Heuristic (Algorithm 4):** add a candidate `e` to the result only if `e` is
  closer to the query than to any already-selected neighbor.

The heuristic is slightly more expensive (`O(M²)` distance computations during
selection) but produces a more diverse, more navigable graph. It earns its keep
on clustered or low-intrinsic-dimension data, where simple top-M produces tight
locally-redundant balls that hurt long-range navigation.

We use heuristic with `extendCandidates=false` and `keepPrunedConnections=false`
as defaults — the paper's recommended settings for general workloads.

## Why from-scratch over a library

This is a portfolio project. Pulling in `hnswlib-jna` and shipping a wrapper is
a Tuesday afternoon. Implementing the paper end-to-end — including the diversity
heuristic, the level distribution, the bidirectional connection logic, soft
deletes, and concurrency — exercises the algorithm at a level a wrapper never
would. The educational value is the point.

A secondary reason: a from-scratch implementation lets the storage layer
serialize the graph in a format we control, rather than fighting an opaque
library binary blob.

## Defaults

- `M = 16` — standard choice for dim 64–768. Doubles to 32 at layer 0.
- `efConstruction = 200` — high enough to give >0.95 recall on 10k random
  Gaussian dim-128 with efSearch=200.
- `efSearch = 50` — query-time default; users can override per-query for a
  recall/latency tradeoff.
- `randomSeed = 42` — fixed by default so behavior is reproducible across runs;
  override for production.

## Consequences

- We own correctness. If the paper has a corner case we miss, recall suffers.
- The recall test (10k random Gaussian dim 128, brute-force baseline) is the
  primary correctness check.
- We are NOT competitive with hnswlib on absolute query latency — we use no
  SIMD, no off-heap memory, no vectorized distance kernels. That's a stretch
  goal documented under roadmap.
