# HNSW: a one-page summary

**Paper:** Yu. A. Malkov & D. A. Yashunin, *Efficient and robust approximate nearest
neighbor search using Hierarchical Navigable Small World graphs* (2016).
[arXiv:1603.09320](https://arxiv.org/abs/1603.09320).

## What it does

Approximate nearest-neighbor search on dense vectors with sub-linear query time and
recall close to brute force. Beats KD-trees on high-dimensional data and beats LSH and
IVF on most realistic datasets.

## How it works, in one breath

Build a multi-layer graph where layer 0 contains every vector and each higher layer
is a geometrically-thinning sample. Search starts from a single entry point at the
top, greedy-descends layer by layer, then runs an ef-bounded best-first search at
layer 0. Insert places a new node at a random level and connects it to its M nearest
existing neighbors at every layer it appears in.

## The layered graph

```
   layer 3 :  o-----------o                   (1-2 nodes)
                \         |
   layer 2 :  o--o----o---o------o            (sparse)
              |     \  \  /     /
   layer 1 :  o--o---o-o-o--o--o--o           (medium)
              |    /  X    \   /
   layer 0 :  every node, dense graph         (all N)
```

Each layer is a Navigable Small World (NSW): a graph with local clustering plus a
few long-range "express" edges. Higher layers act like express trains, lower layers
like local stops. Search descends the express network, then walks locally.

## Two parameters that matter

- **M** — target out-degree at upper layers (layer 0 uses 2M). Controls graph density.
  Bigger M → more neighbors per node → better recall, slower build, more memory.
- **efConstruction / efSearch** — size of the dynamic candidate list during build /
  query. Bigger ef → better recall, slower. Build typically uses efConstruction
  ~200; query typically uses efSearch ~50–200 depending on the recall target.

## Three ideas that make it work

1. **Probabilistic level assignment.** A node's max layer is `floor(-ln(U) / ln(M))`
   where U ~ Uniform[0,1]. This gives an exponentially-decaying distribution: for
   M=16, ~94% of nodes live at layer 0, ~6% reach layer 1, and so on. Higher layers
   stay sparse no matter how many vectors you insert.

2. **Greedy descent with ef-search at the bottom.** From the top entry point, do
   greedy nearest-neighbor at each layer (ef=1) until layer 1. Then at layer 0, do
   an ef-bounded best-first search and return the top-k. The descent is O(log N);
   the bottom search is O(ef · log(ef)).

3. **Diversity heuristic for neighbor selection (Algorithm 4).** Instead of picking
   the M nearest candidates as neighbors, we pick a *diverse* set: a candidate `e`
   only earns a slot if it is closer to the query than to any already-selected
   neighbor. This avoids building dense balls around clusters and is what makes
   HNSW robust across data distributions.

## Why beats the alternatives

- **KD-trees** degrade on high-dim data — they pick splitting planes that quickly
  become useless. HNSW's graph topology is high-dim native.
- **LSH** needs many hash tables for comparable recall, and the hyperparameter
  tuning is awful. HNSW has two knobs and they're intuitive.
- **IVF** clusters first, then searches a few clusters — recall is bounded by how
  well the clustering matches the query distribution. HNSW has no clustering bias.
- **Annoy / NSW** without hierarchy degrades to O(N) traversal at insert time.

## Costs and quirks

- **Build time is non-trivial** — each insert is roughly O(M · log N · efConstruction)
  distance evaluations. For a million 128-dim vectors, that's minutes, not seconds.
- **Memory overhead is real** — on top of the raw vectors, you store ~M·8 bytes per
  node per layer (ints to neighbor indices). For 100K vectors at M=16 that's roughly
  10–15 MB of graph alone.
- **Deletes are awkward** — the paper doesn't really specify them. Most implementations
  (including Vex) tombstone-then-skip rather than rewire. Long-running indexes need
  periodic compaction.
- **Bidirectional edge symmetry** is not strictly enforced — when pruning a neighbor's
  list, the new node may be dropped even though the new node still points to the
  old one. The graph stays navigable in practice.

## How Vex implements it

`com.vex.core.HnswIndex` follows Algorithms 1–5 of the paper:

| Paper | Vex method |
| --- | --- |
| Alg 1 INSERT | `add(long, float[])` |
| Alg 2 SEARCH-LAYER | `searchLayer(...)` |
| Alg 4 SELECT-NEIGHBORS-HEURISTIC | `selectNeighborsHeuristic(...)` |
| Alg 5 K-NN-SEARCH | `query(float[], int)` |

Single-writer, multi-reader concurrency via `ReentrantReadWriteLock`. Soft delete via
a per-node tombstone bit. Default config: M=16, efConstruction=200, efSearch=50.
