# ADR 004: Post-retrieval filtering

**Date:** 2026-05-07
**Status:** Accepted

## Context

The REST query endpoint accepts an optional `filter` expression — a boolean
predicate over the payload (e.g. `category = "books" AND year > 2019`).
Returning the top-k vectors that *also* satisfy the filter requires
deciding when in the pipeline to evaluate the predicate.

Three options exist in the literature:

1. **Pre-filtering.** Build a posting list of ids that satisfy the
   predicate, then run HNSW search restricted to those ids.
2. **In-graph filtering.** Modify HNSW traversal so neighbors that fail
   the filter are pruned during the walk.
3. **Post-filtering.** Run HNSW unconstrained, fetch more than `k`
   results, then drop those that fail the filter.

## Decision

Option 3 — post-filtering, with an inflation factor for filtered queries.

The filter parser (`FilterCompiler`) compiles a predicate and the
collection's `query` method:

- Detects whether a non-trivial filter is present.
- If yes, fetches `max(k * 4, efSearch)` from HNSW.
- Applies the predicate.
- Returns the first `k` matches in distance order.

If no filter is present, the fetch size is `max(k, efSearch)` — exactly
what HNSW would return anyway.

## Why post-filtering

- **Implementation simplicity.** The HNSW algorithm stays untouched.
  No new graph structure, no per-id metadata in the graph layer, no
  changes to the on-disk format. The whole feature is one extra method
  on `Collection`.
- **Filter language flexibility.** Any predicate the user writes — string
  comparisons, range checks, boolean combinators — works with no
  per-operator support in the index. Pre-filtering would require per-operator
  posting lists.
- **No degradation when no filter is present.** Pre-filtering pays a
  cost for every query (consult the inverted index, intersect with
  graph traversal). Post-filtering only pays when a filter is supplied.

## Why this hurts and when

The known downside: highly selective filters (e.g. `id_in_a_million_id_list`
that matches 0.01% of vectors) waste most of the HNSW work and may
return fewer than `k` results because the inflated fetch wasn't enough.

For a filter that matches 1 in N vectors and we want the top-k, the
correct fetch size is roughly `k * N` to be confident. We use `k * 4` as
a heuristic that works well for typical filters (selectivity in the
1-50% range) and degrades acceptably for tighter filters.

A user with a known-selective filter can bump `efSearch` per request
to compensate.

## What we explicitly defer

- **Adaptive inflation.** Track recent filter selectivity per
  collection and inflate accordingly. Useful but adds state we don't
  need yet.
- **Pre-filter posting lists.** A future enhancement for known
  high-selectivity filters. The filter parser already has the structure
  to enumerate referenced fields; a future codec could index them.
- **Filter-aware HNSW.** Approaches like ACORN modify the graph traversal
  to weight neighbors by filter compatibility. Out of scope for v1.

## Consequences

- The filter parser is fully decoupled from HNSW.
- Test coverage is straightforward: filter unit tests live in
  `FilterCompilerTest`; end-to-end coverage lives in the server
  integration tests.
- Selectivity-related performance pitfalls are documented in the
  README's "what's not built and why" section.
