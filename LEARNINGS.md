# What I learned building Vex

Notes from building a vector database from scratch. Each entry is one
non-obvious thing I figured out by writing the code, not something I
already knew going in.

## 2026-05-07 — HNSW level assignment is geometric, not uniform

Each new node in HNSW gets assigned a max layer. The textbook formula
`floor(-ln(U) * mL)` where `mL = 1/ln(M)` is dropped in the paper as
if it's obvious; it isn't. `-ln(U)` is exponentially distributed with
mean 1. Multiplying by `mL` gives an exponential with mean `mL`.
Taking the floor turns that into a geometric distribution.

For M=16: P(level >= 1) = 1/16, P(level >= 2) = 1/256. So out of 100k
nodes, only ~6,250 reach layer 1 and only ~390 reach layer 2. The
upper layers stay sparse no matter how big the index gets, which is
what makes log-N descent possible.

The mistake I almost made was uniformly sampling `floor(U * mL)` —
that gives a uniform distribution, the upper layers grow with N, and
you lose the entire log-N property.

## 2026-05-07 — The "diversity" heuristic in SELECT-NEIGHBORS-HEURISTIC

The paper's Algorithm 4 selects each candidate `e` only if `e` is
closer to the query `q` than to any element already in the result set
`R`. Translation: don't add `e` if some `r` in `R` is roughly in `e`'s
direction from `q`, because `r` already covers that direction.

The simple alternative — pick the M nearest candidates — works but
produces dense balls of redundant neighbors near clusters. The
heuristic spreads neighbors out in direction-space, which is what
makes long-range navigation work.

What surprised me: the heuristic costs O(M²) per insertion, but it's
the difference between a graph that scales and a graph that doesn't.

## 2026-05-07 — Why mmap doesn't help queries even though it helps loads

I assumed mmap would speed up queries — kernel pulls in vector pages on
demand, no Java-heap copy, etc. Built it that way, then noticed: the
HNSW algorithm needs random access into a `float[][]` of vectors. If
those vectors live in a `MappedByteBuffer`, every distance computation
pays a per-element decode. If they live on the Java heap as `float[]`,
the JIT can vectorize the inner loop.

mmap is great for *loading* the file (one syscall to scope, zero-copy
parsing into Java heap), but the actual hot-path vectors should live
on-heap. Lucene's HNSW codec confirms this.

## 2026-05-07 — fsync per write is fast enough until it isn't

The WAL fsyncs every record by default. On commodity SSD that's
~1-3 ms per write. For 99% of vector-DB workloads (build a corpus
once, query forever), this is irrelevant. For bulk loading 10M
vectors at ~500 inserts/sec, fsync becomes the bottleneck.

The right escape valve is a config knob (`vex.wal-fsync: never`) that
the user flips during the bulk load and flips back for steady state.
What I almost did: build a fancy "group commit" path that batches
fsyncs across writers. Wrong call — Vex is single-writer (the WAL is
behind a `synchronized`), so there's nobody to batch with.

## 2026-05-07 — Recall vs efSearch is a logarithmic curve, not a knob

I expected recall to look like `recall = 1 - exp(-ef / N)` or some
similar saturation curve. It's actually closer to logarithmic: each
doubling of efSearch buys ~10-15 percentage points of recall, with
strongly diminishing returns above ~ef = 256.

For users this matters because the latency *also* doubles per ef
doubling. So the sweet spot is dataset-dependent: pick the lowest ef
where recall is acceptable, not the highest ef you can afford.
docs/benchmarks.md shows the curve.

## 2026-05-07 — Scalar quantization barely costs recall

I expected per-dim int8 to lose 5-10% recall for a 4× memory win. On
random Gaussian data it's *0.5%* recall loss at the same efSearch.
This is because per-dim min/max calibration captures most of the
useful resolution; the int8 step is much smaller than the within-dim
variance HNSW relies on for navigation.

The corollary: there's almost no excuse not to quantize, *if* the
implementation lands the int8 in the actual storage path. My v1
doesn't (yet) — see ADR 005.

## 2026-05-07 — Post-retrieval filtering is a fetch-size problem

Filtering after the HNSW search seems wasteful — you do all the
graph work, then throw most of it away. The fix isn't to filter
during the search (that's a much harder algorithm); it's to fetch
more than `k` results and trust the inflation factor.

For a filter that matches 10% of vectors, fetching `k * 4` returns
enough survivors. For a 1-in-a-million filter, you need a different
approach (pre-filter posting list). The right design is to make the
inflation factor adaptive based on observed selectivity, but for v1
a static 4× works for typical workloads. ADR 004.

## 2026-05-07 — `-parameters` is required for Spring `@PathVariable`

Cost me 30 minutes. Spring tried to bind `@PathVariable String name`
and failed with "parameter name information not available via
reflection. Ensure that the compiler uses the '-parameters' flag."
Spring Boot Starter Parent enables this by default. I rolled my own
parent POM and didn't, hence the bug.

The lesson is bigger than the bug: when you opt out of Spring Boot
Starter Parent for legitimate reasons (multi-module, custom
dependency management), you also opt out of all the convenience flags
it sets. Read the [Starter Parent's POM](https://github.com/spring-projects/spring-boot/blob/main/buildSrc/src/main/java/org/springframework/boot/build/starters/StarterAutomatedTask.java)
once and copy the parts you actually need.

## 2026-05-07 — Java's `MappedByteBuffer` is awkward but fine if you cope

Several rough edges:
- Bounded by `Integer.MAX_VALUE` (2 GB) per mapping.
- No deterministic close — relies on GC to release. JDK 25's
  `MemorySegment` fixes this; 17 doesn't.
- Always big-endian by default; you need `.order(LITTLE_ENDIAN)` for
  most binary file formats.

For Vex's load-once parse path it's good enough. For a future
"random access during queries" feature, I'd pick `MemorySegment` even
if it means bumping the minimum JDK.

## 2026-05-07 — Recall degrades non-linearly with N at fixed parameters

My 10k-vector recall test passes >= 0.95 at efSearch=200. The same
config at 100k vectors lands at ~0.65. Production HNSW
implementations (hnswlib, Lucene) drop much more gently — typically
0.95 → 0.92.

The first hypothesis was the diversity heuristic over-pruning. I
added a `useHeuristicNeighborSelection` flag, ran the same sweep
with simple top-M, and the recall numbers came in within 2-3 points
of the heuristic. So the heuristic is **not** the cause. The
remaining hypotheses are in `docs/benchmarks.md` (asymmetric
bidirectional edges, initial dynamic-list population, no bridge
between newly-promoted top-layer nodes).

The lesson: a recall test that passes at 10k tells you the algorithm
is approximately right, not that it scales. *Always* run the same
test at the next order of magnitude.

## 2026-05-07 — Storing vectors as int8 in the index path is a clean refactor

The first version of scalar quantization was "implemented but not
integrated" — `ScalarQuantizer` worked, but the index still stored
`float[][]` and decoded floats round-trip through quantize/dequantize
on every insert. ADR 005 documented this as a deferred gap.

Closing the gap was less work than I expected. The HNSW algorithm is
mostly storage-agnostic: graph traversal, level assignment, neighbor
selection are identical. Only the per-vector storage type (`float[]`
vs `byte[]`) and the distance kernel (`metric.distance(float[],
float[])` vs `quantizer.squaredL2(byte[], byte[])`) change.

I shipped it as a sibling class — `QuantizedHnswIndex` next to
`HnswIndex` — rather than parameterizing the existing one. Yes, code
duplication. But the parallel implementations make the
"this-is-the-only-thing-that-changes" learning legible to a future
reader, and it kept `HnswIndex` simple. Real measured numbers (from
`MemoryComparison`): 4× per-vector compression, 48% total heap
reduction at 50k dim 128.
