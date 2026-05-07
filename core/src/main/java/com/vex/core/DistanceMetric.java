package com.vex.core;

/**
 * A distance function over float vectors. All implementations follow the convention that smaller
 * values indicate a closer (more similar) pair, so HNSW's nearest-neighbor logic can be metric-
 * agnostic.
 */
public interface DistanceMetric {

  /**
   * Returns a distance between {@code a} and {@code b}. Smaller is closer.
   *
   * @throws IllegalArgumentException if dimensions differ.
   */
  float distance(float[] a, float[] b);

  /** Stable byte identifier used for on-disk serialization. */
  byte id();

  /** Stable lowercase name used in the REST API and config. */
  String name();

  /** Returns the metric matching the given byte id. */
  static DistanceMetric forId(byte id) {
    return switch (id) {
      case 0 -> new L2Distance();
      case 1 -> new CosineDistance();
      case 2 -> new DotProductDistance();
      default -> throw new IllegalArgumentException("Unknown metric id: " + id);
    };
  }

  /** Returns the metric matching the given name (case-insensitive). */
  static DistanceMetric named(String name) {
    if (name == null) {
      throw new IllegalArgumentException("metric name required");
    }
    return switch (name.toLowerCase()) {
      case "l2", "euclidean" -> new L2Distance();
      case "cosine" -> new CosineDistance();
      case "dot", "dotproduct", "dot_product", "ip" -> new DotProductDistance();
      default -> throw new IllegalArgumentException("Unknown metric name: " + name);
    };
  }
}
