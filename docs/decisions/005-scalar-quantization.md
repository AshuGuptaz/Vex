# ADR 005: Scalar quantization with per-dimension int8 mapping

**Date:** 2026-05-07
**Status:** Accepted

## Context

Vector databases at scale need to keep large indexes in RAM. A 1M-vector
collection of dim 768 floats is 3 GB — too large for many footprints.
Scalar quantization compresses each float dimension into an int8,
giving 4× compression with minimal recall loss for typical embedding
distributions.

## Decision

Implement per-dimension symmetric int8 scalar quantization with the
following properties:

- **Per-dimension calibration.** Each dim has its own [min, max] range
  computed from a training sample. Per-dim quantization is necessary
  because most embedding distributions are heteroskedastic (different
  scales per axis).
- **Train-once, freeze.** The quantizer is trained on the first 10,000
  inserts and then frozen. New vectors that fall outside the trained
  range are clipped.
- **Direct int8 distance.** `squaredL2(byte[], byte[])` computes the
  L2 distance against int8 inputs without decoding to float on the
  hot path. The integer differences are scaled per-dimension so the
  ordering matches the float-domain distance.

## How it's wired

Two cooperating types in `core/`:

- `ScalarQuantizer` — trains on a sample, encodes float[] -> byte[],
  decodes byte[] -> float[], and computes the per-dimension-scaled
  squared-L2 distance directly on byte arrays.
- `QuantizedHnswIndex` — a self-contained HNSW that holds vectors as
  `byte[][]` instead of `float[][]` and routes every distance call
  through the quantizer's int8 kernel. The graph algorithm
  (level assignment, SEARCH-LAYER, neighbor selection) is identical
  to `HnswIndex`; only the storage type and distance function differ.

The user's responsibility is to train the quantizer on representative
data before constructing the index (typically the first ~10k inserts
of a collection). Currently L2-only — cosine and dot-product would
need their own int8 kernels and aren't implemented.

## Measured

`bench/.../MemoryComparison.java` builds 50k random Gaussian dim-128
vectors once into each variant and reports heap deltas:

| Variant                  | Vector bytes / vec | Heap delta |
| ------------------------ | -----------------: | ---------: |
| HnswIndex (float)        | 512                | 37.8 MB    |
| QuantizedHnswIndex (int8) | 128               | 19.6 MB    |

That's a 4× reduction in raw vector storage and a 48% reduction in
total heap (the graph connections, which are unchanged, dominate the
remaining 19 MB).

Recall test (`QuantizedHnswIndexTest.recallAtTenStaysAboveNinetyPercentOnTenKVectors`)
asserts recall@10 ≥ 0.90 on 10k random Gaussian dim-128 vectors with
the int8-storage path — i.e., quantization is in the index hot path,
not just a lossy float round-trip.

## Why per-dimension and not global

A single global [min, max] would conflate dimensions with very
different scales. Embeddings frequently have a few "important"
dimensions with large variance and many sparse dimensions; a global
range would waste resolution on the sparse dims.

Per-dimension calibration costs `2 * dimension * float` of state per
quantizer, which is trivial for any practical dim.

## Why train-once and freeze

Online recalibration would require re-encoding every previously-stored
vector when the range shifts. That's a massive write amplification
problem. Most production embedding workloads have stationary
distributions over the lifetime of a collection (the embedder doesn't
change its outputs without explicit re-indexing), so freezing after a
representative warmup sample is fine.

10k vectors as the training threshold is enough to capture per-dim
variance for typical embedding distributions (768-dim text embeddings
need ~1k samples to converge per-dim min/max within 1%).

## Why int8 (and not int4 or 1-bit)

- **int8 is a sweet spot.** 4× compression, ~1% recall loss on most
  realistic data.
- **int4 / int2 / 1-bit** double or quadruple the compression but
  start hurting recall meaningfully on dim-768 embeddings. They
  matter at billion-vector scale; not at the scale Vex targets in v1.
- **Product quantization (PQ)** would beat scalar at the same bitrate
  but adds a codebook lookup and an ADR's worth of complexity. Out
  of scope for v1; documented under the "what's not built" ADR.

## Consequences

- **Implemented and tested:** ScalarQuantizer correctness (round-trip,
  ordering preservation, edge cases like degenerate ranges).
- **Implemented and tested:** QuantizedHnswIndex with int8 storage and
  int8 distance, recall@10 ≥ 0.90 on the 10k spec test, 4× per-vector
  compression confirmed via heap measurement.
- **Currently L2-only.** Cosine and dot-product on int8 require
  separate kernels (and possibly per-vector norms) and are out of
  scope for v1.
- **Persisted serialization for QuantizedHnswIndex** is not yet
  implemented in IndexFile; quantized collections remain in-memory
  only across server restarts. Float HnswIndex collections still
  persist normally. Closing this gap is straightforward (extend the
  on-disk header to record the quantizer's per-dim mins/scales).
