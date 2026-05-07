package com.vex.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * End-to-end recall test with scalar quantization in the loop. We encode each vector through the
 * quantizer (lossy round-trip back to float) before insertion, then query against the quantized
 * index using floats reconstructed from the same quantizer. Recall is measured against a brute-
 * force baseline run on the ORIGINAL float vectors so the test captures the full loss.
 */
class QuantizedRecallTest {

  @Test
  void recallStaysAboveNinetyPercentWithScalarQuantization() {
    int dim = 128;
    int n = 10_000;
    int q = 50;
    int k = 10;
    long seed = 4242L;

    Random rng = new Random(seed);
    float[][] data = new float[n][dim];
    for (int i = 0; i < n; i++) {
      for (int j = 0; j < dim; j++) {
        data[i][j] = (float) rng.nextGaussian();
      }
    }

    ScalarQuantizer quantizer = ScalarQuantizer.train(data);

    HnswConfig cfg = new HnswConfig(16, 200, 200, dim, new L2Distance(), seed);
    HnswIndex idx = new HnswIndex(cfg);
    for (int i = 0; i < n; i++) {
      idx.add(i, quantizer.decode(quantizer.encode(data[i])));
    }

    Random qrng = new Random(seed + 1);
    DistanceMetric metric = new L2Distance();
    float overlap = 0f;
    for (int qi = 0; qi < q; qi++) {
      float[] query = new float[dim];
      for (int j = 0; j < dim; j++) {
        query[j] = (float) qrng.nextGaussian();
      }
      Set<Long> truth = bruteForceTopK(data, query, metric, k);
      List<SearchResult> got = idx.query(quantizer.decode(quantizer.encode(query)), k);
      int hit = 0;
      for (SearchResult r : got) {
        if (truth.contains(r.id())) {
          hit++;
        }
      }
      overlap += hit / (float) k;
    }
    float recall = overlap / q;
    assertThat(recall).as("recall@10 with scalar quantization").isGreaterThanOrEqualTo(0.90f);
  }

  private static Set<Long> bruteForceTopK(float[][] data, float[] q, DistanceMetric metric, int k) {
    List<long[]> all = new ArrayList<>(data.length);
    for (int i = 0; i < data.length; i++) {
      all.add(new long[] {i, Float.floatToRawIntBits(metric.distance(q, data[i]))});
    }
    all.sort(Comparator.comparingDouble(p -> Float.intBitsToFloat((int) p[1])));
    Set<Long> truth = new HashSet<>();
    for (int i = 0; i < Math.min(k, all.size()); i++) {
      truth.add(all.get(i)[0]);
    }
    return truth;
  }
}
