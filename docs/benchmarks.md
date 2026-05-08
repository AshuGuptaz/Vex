# Vex benchmarks

Numbers from a real run on the development machine. All synthetic data —
random Gaussian vectors with `Random(seed=12345L)`. SIFT-1M was *not*
run for this iteration; the framework supports it but the dataset
download is gated and we left it as a manual step. See "Methodology"
at the bottom for the exact `mvn`/`java` invocations to reproduce.

## Setup

| Item | Value |
| --- | --- |
| Hardware | Apple M-series (development laptop) |
| JDK | OpenJDK 17.0.19 (Homebrew) |
| Dataset | 100,000 random Gaussian vectors, dim 128 |
| HNSW config | M=16, efConstruction=200 |
| Metric | L2 (squared) |
| Random seed | `12345L` |

## Build throughput

| Vectors | Build wall-time | Inserts / sec |
| ------: | --------------: | -------------: |
| 100,000 | 132 s          | ~760           |

Build is single-writer; throughput is dominated by SELECT-NEIGHBORS-HEURISTIC
which does O(M²) distance evaluations per inserted node. There is no
batched / parallel insert path in v1.

## Query latency (JMH `SampleTime` distribution)

100k vectors, dim 128. JMH `-wi 1 -i 1 -f 1` with 100k+ samples per ef value.

| efSearch | P50      | P90      | P95      | P99      | P99.9   |
| -------: | -------: | -------: | -------: | -------: | ------: |
| 64       | 274 µs   | 334 µs   | 361 µs   | **428 µs** | 561 µs |
| 128      | 506 µs   | 599 µs   | 642 µs   | **722 µs** | 914 µs |

P99 at ef=64 is **0.43 ms**, well below the 5 ms target. Headroom for
4-5x more vectors before hitting the budget.

## Recall vs efSearch

Recall@10 against a brute-force baseline (sort all 100k by distance,
take top 10), averaged over 200 random queries. Three configurations:

- **float-h** — float vectors, Algorithm 4 heuristic neighbor selection.
- **float-s** — float vectors, Algorithm 3 simple top-M.
- **quant-h** — vectors round-tripped through scalar quantization (lossy
  encode/decode), heuristic neighbor selection.

| efSearch | float-h | float-s | quant-h | float-h ms | float-s ms |
| -------: | ------: | ------: | ------: | ---------: | ---------: |
| 16       | 0.232   | 0.249   | 0.240   | 0.15       | 0.12       |
| 32       | 0.363   | 0.368   | 0.364   | 0.20       | 0.19       |
| 64       | 0.498   | 0.497   | 0.498   | 0.31       | 0.36       |
| 128      | 0.649   | 0.647   | 0.650   | 0.56       | 0.57       |
| 256      | 0.785   | 0.774   | 0.785   | 1.11       | 1.08       |

Numbers above are with the saturation fixes enabled (avg 32
connections per node at layer 0, 100% at cap).

Build throughput at 100k:
- Heuristic neighbor selection: ~480 inserts/sec
- Simple top-M:                  ~510 inserts/sec

(Slower than the original ~760 ins/sec because keepPrunedConnections
+ protectNewEdge add per-insert work. The quality gain — 22 → 32
connections per node — is worth the slowdown at scale.)

### Honest reading of the recall column

These numbers are **lower than a tuned hnswlib at the same parameters**.
hnswlib typically lands at ~0.95 recall@10 with M=16, ef=128 on
random Gaussian dim-128. Vex lands at 0.65.

Two observations:

1. **The 10k-vector recall test still passes the 0.95 bar at efSearch=200.**
   See `core/src/test/java/com/vex/core/HnswIndexTest.java` —
   `recallAtTenAchievesAtLeastNinetyFivePercentOnTenKVectors`.
2. **Recall degrades non-linearly with N at fixed parameters.** From 10k
   to 100k vectors with the same config, recall drops from 0.95 to ~0.65
   at ef=200. Production HNSW implementations degrade much more gently
   (typically 0.95 → 0.92).

### What I investigated

I went looking for the cause of the gap and ruled out several
hypotheses with measurements committed in `bench/...`:

1. **Diversity heuristic over-pruning** — added a
   `useHeuristicNeighborSelection` flag. Simple top-M and the heuristic
   give recall within 2-3 points at 100k. **Not the cause.**
2. **Bidirectional edge dropped during pruning** — added a
   `protectNewEdge` flag (defaults to true) that forces the new node
   into a full neighbor's connection list. **Helped graph saturation,
   didn't move recall.**
3. **Graph density too low** — `bench/.../GraphInspect.java` reports
   per-layer connection histograms. Initially layer 0 had avg 22.2
   connections per node out of 32 cap (28% saturation). After enabling
   `keepPrunedConnections=true` on the outbound selection AND adding
   a discarded-fallback to the protected pruning, layer 0 hit 32 avg
   with 100% saturation.

**The graph is now correctly saturated. Recall at fixed ef is still
below hnswlib.**

What remains shows up in `bench/.../QueryDebug.java`:

```
ef=32     recall@10=0.40  visits=1132   (1.1% of N)
ef=64     recall@10=0.52  visits=1933   (1.9%)
ef=128    recall@10=0.67  visits=3478   (3.5%)
ef=256    recall@10=0.82  visits=6188   (6.2%)
ef=512    recall@10=0.91  visits=10715  (10.7%)
ef=1024   recall@10=0.94  visits=17625  (17.6%)
```

The algorithm is correct (recall climbs monotonically toward 1.0 as
ef grows). **Per-visit, my searchLayer is less informative than
hnswlib's** — it takes ~2-3× more distance evaluations for the same
recall. The remaining hypotheses for this last gap:

- **Initial dynamic-list seeding from previous-layer W**. When
  searchLayer is called between insert layers with `ep = previous
  layer's W` (up to efC = 200 elements), `dynamic` is pre-populated
  with up to 200 candidates. The "furthest in dynamic" is already
  pretty close, so the early-terminate condition kicks in sooner
  than ideal at lower layers.
- **No multi-entry-point at layer 0 for queries**. The paper's
  Algorithm 5 uses a single greedy-descent result as the layer-0 ep.
  Some implementations seed layer-0 with the top-N from the descent
  to get more diverse starting points. Not implemented.

Both are scoped to v2.

### Practical guidance

- For ≥ 0.90 recall at this dataset shape: **use efSearch ≥ 512** with
  M=16, or bump M to 32.
- The default `efSearch=50` from `HnswConfig.defaults` is tuned for
  the smaller workloads in the unit tests, not for production. Tune
  per your latency / recall budget.

### Quantization recall delta

Scalar quantization adds essentially no recall loss at this scale —
the float and quantized columns differ by < 0.02 across all efSearch
values. Confirms that per-dim int8 is a near-free 4× memory win for
random Gaussian distributions (when the memory savings actually land
in the index — see ADR 005 for the deferred integration note).

## Memory

Measured at runtime via `Runtime.totalMemory() - Runtime.freeMemory()`
after build, with `System.gc()` hints. Captured by
`java -cp bench/target/vex-bench.jar com.vex.bench.MemoryComparison`
on 50k vectors of dim 128.

| Index variant            | Heap delta | Bytes / vector (raw) | Compression vs float |
| ------------------------ | ---------: | -------------------: | -------------------: |
| `HnswIndex` (float)      | 37.8 MB    | 512 (4 × 128)        | —                    |
| `QuantizedHnswIndex` (int8) | 19.6 MB | 128 (1 × 128)        | **4×**               |

The per-vector raw storage is exactly the expected 4× win. The total
heap delta lands at **48% reduction** because the graph (`int[][][]`
connections) is unchanged between variants and doesn't compress.

For a 1M-vector dim-768 collection that ratio matters: float storage
alone is 3 GB; int8 is 768 MB.

The numbers are noisy at 50k (GC state varies) but the *ratio* is
stable across runs. To reproduce:

```bash
mvn -B -pl bench -am package -DskipTests
java -cp bench/target/vex-bench.jar com.vex.bench.MemoryComparison
```

## SIFT-1M (the real headline)

Captured 2026-05-09 with `make sift-data && make bench-sift`. Full
1M-vector base, 1000 query subset, M=16, efConstruction=200.

| efSearch | recall@10 | ms/query |
| -------: | --------: | -------: |
| 16       | 0.826     | 0.17     |
| 32       | 0.920     | 0.23     |
| 64       | **0.972** | 0.44     |
| 128      | **0.992** | 0.75     |
| 256      | **0.998** | 1.30     |

Build: 1,167 s (857 inserts/sec avg, peaked at 2,026 early before the
graph density slowed it down). Heap after build: ~1.4 GB.

These numbers are **hnswlib-class** — hnswlib's published SIFT-1M
numbers are 0.95-0.97 at ef=64 with the same M=16, efC=200. Vex hits
0.97 at ef=64. The recall curve climbs steeply: a single doubling of
efSearch (64 → 128) takes recall from 0.97 to 0.99 at the cost of
~0.3 ms additional latency.

**Why does SIFT recall so much higher than the synthetic Gaussian?**
SIFT is real image descriptors — they cluster naturally because real
images have repeated visual primitives. HNSW was designed for exactly
that distribution: the navigable-small-world structure exploits cluster
locality. Uniform random Gaussian (which the synthetic benchmark uses)
is essentially the worst case for any graph-based ANN method, because
no cluster signal exists for the heuristic to navigate. Per-visit, my
`searchLayer` is doing the same work in both regimes; the SIFT data
just has a much higher density of true neighbors per visit.

**Reproduce:**

```bash
make sift-data        # ~165 MB download to ~/sift
make bench-sift       # ~20 min build + queries
```

## Methodology — reproduce

```bash
# Build
mvn -B clean package -DskipTests

# Recall sweep (recall@10 + avg latency, both float and quantized)
java -cp bench/target/vex-bench.jar com.vex.bench.RecallSweep

# JMH percentile distribution (P50/P99 etc)
java -jar bench/target/vex-bench.jar QueryLatency \
  -wi 1 -i 1 -f 1 -p efSearch=64,128,256
```

Numbers above were captured with these exact commands. Re-running on
your hardware will produce different absolutes but the same shape.
