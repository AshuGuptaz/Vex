package com.vex.core;

/**
 * Configuration for an {@link HnswIndex}.
 *
 * @param M target out-degree at non-zero layers; layer 0 uses 2*M
 * @param efConstruction candidate list size during inserts
 * @param efSearch default candidate list size during queries
 * @param dimension expected vector dimension
 * @param metric distance function
 * @param randomSeed seed for the level-assignment PRNG
 * @param useHeuristicNeighborSelection if true (default), use Algorithm 4 (diversity heuristic). If
 *     false, use the simple top-M variant from the paper. The heuristic produces a more navigable
 *     graph on clustered data; the simple variant can give higher recall on uniform random data at
 *     the cost of less navigability.
 */
public record HnswConfig(
    int M,
    int efConstruction,
    int efSearch,
    int dimension,
    DistanceMetric metric,
    long randomSeed,
    boolean useHeuristicNeighborSelection,
    boolean protectNewEdge) {

  public HnswConfig {
    if (M < 2) {
      throw new IllegalArgumentException("M must be >= 2, got " + M);
    }
    if (efConstruction < M) {
      throw new IllegalArgumentException(
          "efConstruction (" + efConstruction + ") must be >= M (" + M + ")");
    }
    if (efSearch < 1) {
      throw new IllegalArgumentException("efSearch must be >= 1, got " + efSearch);
    }
    if (dimension < 1) {
      throw new IllegalArgumentException("dimension must be >= 1, got " + dimension);
    }
    if (metric == null) {
      throw new IllegalArgumentException("metric is required");
    }
  }

  /** 7-arg constructor with protectNewEdge defaulting to true (matches hnswlib behavior). */
  public HnswConfig(
      int M,
      int efConstruction,
      int efSearch,
      int dimension,
      DistanceMetric metric,
      long randomSeed,
      boolean useHeuristicNeighborSelection) {
    this(
        M,
        efConstruction,
        efSearch,
        dimension,
        metric,
        randomSeed,
        useHeuristicNeighborSelection,
        true);
  }

  /** 6-arg backwards-compatible constructor. Defaults to heuristic neighbor selection. */
  public HnswConfig(
      int M,
      int efConstruction,
      int efSearch,
      int dimension,
      DistanceMetric metric,
      long randomSeed) {
    this(M, efConstruction, efSearch, dimension, metric, randomSeed, true, true);
  }

  /** Returns reasonable defaults: M=16, efConstruction=200, efSearch=50, seed=42. */
  public static HnswConfig defaults(int dimension, DistanceMetric metric) {
    return new HnswConfig(16, 200, 50, dimension, metric, 42L);
  }
}
