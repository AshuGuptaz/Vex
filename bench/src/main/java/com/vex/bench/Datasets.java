package com.vex.bench;

import java.util.Random;

/** Random Gaussian vector generator with a fixed seed for reproducible benchmarks. */
final class Datasets {

  private Datasets() {}

  static float[][] randomGaussian(int n, int dim, long seed) {
    Random r = new Random(seed);
    float[][] data = new float[n][dim];
    for (int i = 0; i < n; i++) {
      for (int j = 0; j < dim; j++) {
        data[i][j] = (float) r.nextGaussian();
      }
    }
    return data;
  }
}
