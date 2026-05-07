# ADR 005: Scalar quantization with per-dimension int8 mapping

**Date:** 2026-05-07
**Status:** Accepted (with deferred integration)

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

## Status of integration

The quantizer (`ScalarQuantizer`) is implemented, fully tested, and
exposed to the server: collection creation accepts `"quantization":
"scalar"` and the flag is persisted as a marker file alongside the
index.

**However, the HNSW index does not yet use the quantizer to store
vectors as int8 internally.** Doing so requires either:

- Parameterizing `HnswIndex` over vector type (float[] vs byte[]), or
- Adding a parallel `QuantizedHnswIndex` class.

Both are mechanical refactors. For v1 we land the quantizer +
end-to-end recall test (which lossily round-trips floats through the
quantizer) and document the memory-savings gap as a known limitation.

The recall test (`QuantizedRecallTest`) inserts lossily-decoded floats
into HNSW, queries with a lossily-decoded query vector, and verifies
recall@10 stays >= 0.90 on 10k random Gaussian dim-128 vectors. This
captures the recall cost of quantization fully; the memory cost is
deferred to v2.

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
- **Implemented and tested:** end-to-end recall through a quantized
  encode/decode hot path stays above 0.90.
- **Not yet implemented:** memory savings — vectors still stored as
  float[] in HnswIndex. The full integration is the next chunk of
  work after the v1 portfolio milestone.
