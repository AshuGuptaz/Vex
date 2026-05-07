package com.vex.core;

/**
 * Cosine distance, defined as {@code 1 - cosine_similarity(a, b)}. Range is [0, 2]: identical
 * vectors give 0, orthogonal give 1, opposite give 2.
 */
public final class CosineDistance implements DistanceMetric {

  @Override
  public float distance(float[] a, float[] b) {
    if (a.length != b.length) {
      throw new IllegalArgumentException("dim mismatch: " + a.length + " vs " + b.length);
    }
    float dot = 0f;
    float na = 0f;
    float nb = 0f;
    for (int i = 0; i < a.length; i++) {
      dot += a[i] * b[i];
      na += a[i] * a[i];
      nb += b[i] * b[i];
    }
    double denom = Math.sqrt(na) * Math.sqrt(nb);
    if (denom == 0.0) {
      // Either side is the zero vector: cosine is undefined; treat as orthogonal (distance 1).
      return 1f;
    }
    float cos = (float) (dot / denom);
    return 1f - cos;
  }

  @Override
  public byte id() {
    return 1;
  }

  @Override
  public String name() {
    return "cosine";
  }

  @Override
  public String toString() {
    return name();
  }
}
