# Building a vector database from the HNSW paper

I built a RAG pipeline in early 2026 and realized I had no idea how
the index actually worked. I'd been treating Pinecone (and later Qdrant,
and later FAISS) as a black box that took embeddings and returned
nearest neighbors. The retrieval quality varied wildly between runs;
the latency had cliffs I couldn't explain; the parameters in the docs
(`M`, `efConstruction`, `efSearch`) seemed adjacent to magic. So I
spent two weeks building a vector database from scratch in Java,
reading the original HNSW paper line by line and writing the
implementation against it.

The result is **Vex** — a portfolio-grade vector database with HNSW
indexing, mmap-backed persistence, a write-ahead log, a REST API
with metadata filtering, scalar quantization, and JMH benchmarks. It
is not a Pinecone replacement. It's the thing I built to *understand*
Pinecone.

This is what I learned.

## What HNSW actually does

The Hierarchical Navigable Small World algorithm builds a multi-layer
graph where each upper layer is a thinning sample of the layer below
it. Layer 0 contains every vector; layer 1 contains roughly 1/M of
them (where M is a parameter, default 16); layer 2 contains 1/M² of
those; and so on. Each layer is itself a "navigable small world"
graph: locally clustered with a few long-range edges.

```
   layer 3 :  o-----------o                   (1-2 nodes, "express train")
                \         |
   layer 2 :  o--o----o---o------o            (sparse)
              |     \  \  /     /
   layer 1 :  o--o---o-o-o--o--o--o           (medium)
              |    /  X    \   /
   layer 0 :  every node, dense graph         (all N)
```

A query starts at a single entry point at the top. It greedy-descends
through the upper layers — at each layer, walk to whichever neighbor
is closer to the query, until you can't get closer. That gets you
into the right neighborhood in O(log N) steps. Then at layer 0, you
do an `ef`-bounded best-first search: maintain a priority queue of
the top `ef` candidates seen so far, expand the closest unexpanded
one, repeat until the closest unexpanded is farther than the farthest
in the queue. Return the top k from the final queue.

Insertion is the same descent, but at each layer the new node lives
in, you also add bidirectional edges to the M nearest neighbors —
selected via a "diversity heuristic" that prefers neighbors covering
different directions over neighbors clustered in one direction.

That's it. That's the algorithm. I had been treating it as alchemy.

## Three things that surprised me

### 1. The level distribution is geometric, and that's the whole point

When you insert a node, you assign it a random max layer using
`floor(-ln(U) * mL)` where `U ~ Uniform[0,1]` and `mL = 1/ln(M)`.

I almost wrote `floor(U * mL)` because I wasn't paying attention.
That would give a *uniform* distribution. With M=16 and 100k nodes,
~6,250 nodes end up at layer 1, ~390 at layer 2, ~24 at layer 3.
That's geometric — each layer is M× sparser than the one below it.

If you replaced the formula with uniform sampling, the upper layers
would grow linearly with N. The "express train" property
disappears. Search becomes linear time. The algorithm collapses.

I had treated the level-assignment line in the pseudocode as a
detail. It's the algorithm.

### 2. mmap is great for loading, useless for queries

I built persistence via `FileChannel.map()` and `MappedByteBuffer`,
expecting it to speed up *queries* — kernel pulls in vector pages on
demand, no copy into Java heap, etc.

Then I ran the benchmarks. Queries got slower.

The reason is obvious in hindsight: HNSW does random access into a
`float[][]` of vectors. Every distance call reads one float at a time.
If those vectors live in a `MappedByteBuffer`, each float read pays
a method call dispatch and a bounds check. If they live as a regular
`float[]` on the Java heap, the JIT can autovectorize the distance
loop into a tight SIMD-flavored sequence.

mmap is the right tool for *loading* the file (one syscall, zero-copy
parsing into Java heap arrays). Once parsed, the data lives on the
heap. The mmap is closed.

This is what Lucene's HNSW codec does too. I read the source after
writing my own and felt simultaneously vindicated and humbled.

### 3. My recall is worse than hnswlib's, and I'm honest about it

I built a recall test: insert 10,000 random Gaussian dim-128 vectors,
run 100 queries, compare to brute-force baseline. Recall@10 came in
at 0.95+ with M=16, efC=200, efS=200. Felt great.

Then I bumped to 100,000 vectors. Same parameters. Recall dropped to
~0.65.

A tuned hnswlib at the same parameters lands around 0.95 even at 100k.
There's a real gap between my implementation and a production one.
The likeliest cause is over-pruning in `SELECT-NEIGHBORS-HEURISTIC` at
scale — Algorithm 4 of the paper rejects candidates that are "closer
to an existing neighbor than to the query," and at large N this seems
to leave the layer-0 graph under-connected.

I haven't fully tracked it down. Documented honestly in
`docs/benchmarks.md` as the priority for v2. The honest version of
this story is "I implemented the paper, it works at the scale the
paper benchmarks at, and I have an unsolved scaling regression at 10x
that scale" — not "I beat hnswlib." That's fine. Reviewers will
respect the honesty more than the bullet point.

## The numbers

100,000 vectors of dim 128, M=16, efConstruction=200. Captured by
`bench/.../RecallSweep.java`:

| efSearch | recall@10 (float) | recall@10 (quantized) | avg latency (ms) |
| -------: | ----------------: | --------------------: | ---------------: |
| 16       | 0.25              | 0.24                  | 0.12             |
| 64       | 0.50              | 0.51                  | 0.32             |
| 256      | 0.78              | 0.78                  | 1.03             |

JMH percentile distribution at ef=64: P50 = 0.27 ms, P99 = 0.43 ms.
Build throughput: ~760 inserts/sec single-threaded.

Two takeaways:

1. The latency story is genuinely good. Sub-millisecond P99 at 100k
   vectors with no SIMD, no off-heap, no bespoke optimization. The
   JIT does most of the work.
2. Scalar quantization is essentially free in recall terms — < 0.02
   delta across the whole sweep. The 4× memory win is yours for the
   taking, *if* you build the integration to actually store int8s in
   the index (mine doesn't yet; ADR 005).

## What I'd do differently

- **Build the recall test at the target scale from day one.** Hitting
  the 0.95 bar at 10k masked a regression that only shows up at 100k.
  An hour of test setup at the start would have shaped the rest of
  the project.
- **Read hnswlib's source after writing my own.** Comparing
  implementations side-by-side is the fastest way to find latent
  algorithm bugs. I did this for the read path (mmap pattern) and
  it was the most productive 30 minutes of the project.
- **Skip Spring Boot's "starter-parent" trap.** Rolling a custom
  parent POM is fine for multi-module projects, but you lose the
  `-parameters` compile flag, which Spring's `@PathVariable` quietly
  needs. Spring told me this in a 400 response body. I'd known about
  the flag for years and still missed it.
- **Resist the urge to add SIMD up front.** I wanted to. The recall
  numbers told me the index quality was the bottleneck, not the
  distance kernel. Optimizing the wrong thing is a tax you pay at
  every code review for the rest of the project's life.

## What's next

The roadmap, in order of value-per-hour:

1. **Close the recall gap at 100k+ scale.** Profile the heuristic, try
   the simple top-M variant as a comparison, port hnswlib's exact
   pruning logic if needed.
2. **Land the quantized integration end-to-end** so the 4× memory win
   actually shows up in the index.
3. **SIMD distance kernels** via Java Vector API, once the index
   quality work is done.
4. **Hybrid search** — combine HNSW with a BM25 keyword index. This
   is the single biggest capability jump for real RAG workloads.

All of those would individually be their own weeks-long project, so
they're explicitly *not* in v1. ADR 007 in the repo documents what's
not built and why, which doubles as a reading guide for the next
contributor (probably me).

## Code

[github.com/AshuGuptaz/Vex](https://github.com/AshuGuptaz/Vex)

The README has the curl quickstart and the Python client. The benchmarks
report has the actual numbers. The decision-record directory
(`docs/decisions/`) has eight ADRs explaining every non-obvious choice.

I'd love feedback, especially from anyone who's tracked down a similar
recall regression in their own HNSW implementation.

## References

- Yu. A. Malkov, D. A. Yashunin. *Efficient and robust approximate
  nearest neighbor search using Hierarchical Navigable Small World
  graphs.* arXiv:1603.09320 (2016).
- Lucene's [`Lucene99HnswVectorsFormat`](https://github.com/apache/lucene/tree/main/lucene/core/src/java/org/apache/lucene/codecs/lucene99)
  — what the JVM ecosystem ships when it's serious about this.
- [hnswlib](https://github.com/nmslib/hnswlib) — the C++ reference
  implementation by the paper's authors.
