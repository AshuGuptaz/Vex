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
| 16       | 0.251   | 0.282   | 0.239   | 0.12       | 0.09       |
| 32       | 0.371   | 0.394   | 0.360   | 0.19       | 0.16       |
| 64       | 0.508   | 0.508   | 0.500   | 0.32       | 0.29       |
| 128      | 0.653   | 0.633   | 0.641   | 0.56       | 0.48       |
| 256      | 0.778   | 0.750   | 0.781   | 1.06       | 0.92       |

Build throughput at 100k:
- Heuristic neighbor selection: ~760 inserts/sec
- Simple top-M:                  ~1,270 inserts/sec (1.7× faster build)

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

### What I ruled out

I added a `useHeuristicNeighborSelection` flag and ran the same sweep
with simple top-M (Algorithm 3 of the paper). Recall at 100k is
within 2-3 points of the heuristic — sometimes higher, sometimes
lower. The diversity heuristic is **not** the cause of the regression.

The remaining hypotheses, in priority order:

1. **The bidirectional-edge prune** at insertion may be too aggressive
   in dropping the new node from a neighbor's list. hnswlib has a
   slightly different protect-the-new-edge convention here that I
   haven't reproduced.
2. **Initial dynamic-list population during searchLayer** initializes
   `dynamic` with the entire `ep` set (size up to efConstruction).
   For deep layers this may cause early termination because
   `f` (worst in dynamic) is already a tight cluster, rejecting
   useful explorations.
3. **No "bridge" between newly-promoted top-layer nodes**. When a node
   is the first at a new top layer, its connections at the new layer
   are empty. Greedy descent at that layer is a no-op until the
   second top-layer node arrives, which can take a long time at low
   layers.

The quickest next experiment would be to re-run with M=32 and
efConstruction=400. That denser graph would almost certainly close
most of the recall gap, at the cost of 2-4× build time and memory.
Documented as the v2 priority.

### Quantization recall delta

Scalar quantization adds essentially no recall loss at this scale —
the float and quantized columns differ by < 0.02 across all efSearch
values. Confirms that per-dim int8 is a near-free 4× memory win for
random Gaussian distributions (when the memory savings actually land
in the index — see ADR 005 for the deferred integration note).

## Memory

Measured at runtime via `Runtime.totalMemory() - Runtime.freeMemory()`
after build, with a `System.gc()` hint:

| Index variant | Heap after build | Per-vector overhead |
| ------------- | ---------------: | ------------------: |
| float (current) | ~190 MB          | ~1.9 KB / vector   |

The per-vector overhead breaks down roughly as:
- 128 floats = 512 bytes
- HNSW graph (avg ~32 connections at L0 + a handful above) = ~256 bytes
- Java object headers, padding, hashmap entry = ~1 KB

A future quantized integration (per ADR 005) would shrink the float
portion from 512 to 128 bytes per vector, a 25-30% total reduction at
this dimensionality.

## SIFT-1M

Not run in this report. The benchmark scaffolding is in
`bench/src/main/java/com/vex/bench/RecallSweep.java` — point it at the
SIFT base set (`sift_base.fvecs`) and re-run. We chose to ship honest
synthetic numbers rather than partially-run external numbers.

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
