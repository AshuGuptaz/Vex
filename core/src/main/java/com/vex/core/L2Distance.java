package com.vex.core;

/**
 * Squared Euclidean distance. The square root is omitted because it preserves ordering and saves
 * compute on the hot path.
 */
public final class L2Distance implements DistanceMetric {

  @Override
  public float distance(float[] a, float[] b) {
    if (a.length != b.length) {
      throw new IllegalArgumentException("dim mismatch: " + a.length + " vs " + b.length);
    }
    float sum = 0f;
    for (int i = 0; i < a.length; i++) {
      float d = a[i] - b[i];
      sum += d * d;
    }
    return sum;
  }

  @Override
  public byte id() {
    return 0;
  }

  @Override
  public String name() {
    return "l2";
  }

  @Override
  public String toString() {
    return name();
  }
}
