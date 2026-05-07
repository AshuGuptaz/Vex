package com.vex.core;

import java.util.Arrays;

/**
 * Per-dimension symmetric int8 scalar quantizer.
 *
 * <p>Training scans a sample of vectors and records the per-dimension {@code [min, max]} range.
 * Encoding maps each dimension's float linearly into the int8 range {@code [-127, 127]}; decoding
 * inverts the map. Distances on quantized data are computed against the integer representation
 * directly — there is no per-query decompression on the hot path.
 */
public final class ScalarQuantizer {

  private final int dimension;
  private final float[] mins;
  private final float[] scales; // (max - min) / 254 per dimension

  private ScalarQuantizer(int dimension, float[] mins, float[] scales) {
    this.dimension = dimension;
    this.mins = mins;
    this.scales = scales;
  }

  /** Trains a quantizer on the given sample. The sample must be non-empty. */
  public static ScalarQuantizer train(float[][] sample) {
    if (sample == null || sample.length == 0) {
      throw new IllegalArgumentException("sample must be non-empty");
    }
    int dim = sample[0].length;
    float[] mins = new float[dim];
    float[] maxs = new float[dim];
    Arrays.fill(mins, Float.POSITIVE_INFINITY);
    Arrays.fill(maxs, Float.NEGATIVE_INFINITY);
    for (float[] v : sample) {
      if (v.length != dim) {
        throw new IllegalArgumentException("inconsistent dimension in sample");
      }
      for (int i = 0; i < dim; i++) {
        if (v[i] < mins[i]) mins[i] = v[i];
        if (v[i] > maxs[i]) maxs[i] = v[i];
      }
    }
    float[] scales = new float[dim];
    for (int i = 0; i < dim; i++) {
      float range = maxs[i] - mins[i];
      scales[i] = range == 0f ? 1f : range / 254f;
    }
    return new ScalarQuantizer(dim, mins, scales);
  }

  public int dimension() {
    return dimension;
  }

  /** Encodes a float vector into a byte vector. Out-of-range values are clipped to int8. */
  public byte[] encode(float[] v) {
    if (v.length != dimension) {
      throw new IllegalArgumentException("dim mismatch: " + v.length + " vs " + dimension);
    }
    byte[] out = new byte[dimension];
    for (int i = 0; i < dimension; i++) {
      int q = Math.round((v[i] - mins[i]) / scales[i]) - 127;
      if (q < -127) q = -127;
      else if (q > 127) q = 127;
      out[i] = (byte) q;
    }
    return out;
  }

  /** Decodes a byte vector back to floats. Lossy. */
  public float[] decode(byte[] b) {
    if (b.length != dimension) {
      throw new IllegalArgumentException("dim mismatch: " + b.length + " vs " + dimension);
    }
    float[] out = new float[dimension];
    for (int i = 0; i < dimension; i++) {
      out[i] = mins[i] + (b[i] + 127) * scales[i];
    }
    return out;
  }

  /**
   * Squared L2 distance computed directly on encoded vectors. The result is in the original float
   * scale (multiplied by per-dimension scale^2), so ordering matches the float-domain distance.
   */
  public float squaredL2(byte[] a, byte[] b) {
    if (a.length != dimension || b.length != dimension) {
      throw new IllegalArgumentException("dim mismatch");
    }
    double sum = 0.0;
    for (int i = 0; i < dimension; i++) {
      int diff = a[i] - b[i];
      double scaled = diff * scales[i];
      sum += scaled * scaled;
    }
    return (float) sum;
  }

  /** Returns the per-dimension mins (for serialization). */
  public float[] mins() {
    return mins.clone();
  }

  /** Returns the per-dimension scales (for serialization). */
  public float[] scales() {
    return scales.clone();
  }

  /** Reconstructs from previously-saved {@code mins} / {@code scales}. */
  public static ScalarQuantizer of(float[] mins, float[] scales) {
    if (mins.length != scales.length) {
      throw new IllegalArgumentException("mins/scales length mismatch");
    }
    return new ScalarQuantizer(mins.length, mins.clone(), scales.clone());
  }
}
