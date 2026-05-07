package com.vex.core;

/**
 * Negative dot product. Larger inner product means more similar; we negate so smaller is closer and
 * HNSW's ordering logic is uniform across metrics.
 */
public final class DotProductDistance implements DistanceMetric {

  @Override
  public float distance(float[] a, float[] b) {
    if (a.length != b.length) {
      throw new IllegalArgumentException("dim mismatch: " + a.length + " vs " + b.length);
    }
    float dot = 0f;
    for (int i = 0; i < a.length; i++) {
      dot += a[i] * b[i];
    }
    return -dot;
  }

  @Override
  public byte id() {
    return 2;
  }

  @Override
  public String name() {
    return "dot";
  }

  @Override
  public String toString() {
    return name();
  }
}
