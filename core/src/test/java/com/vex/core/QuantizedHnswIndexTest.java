package com.vex.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class QuantizedHnswIndexTest {

  private static QuantizedHnswIndex newIndex(int dim, ScalarQuantizer q) {
    return new QuantizedHnswIndex(HnswConfig.defaults(dim, new L2Distance()), q);
  }

  @Test
  void emptyIndexReturnsEmptyResults() {
    ScalarQuantizer q = ScalarQuantizer.train(new float[][] {{0f, 0f, 0f, 0f}, {1f, 1f, 1f, 1f}});
    QuantizedHnswIndex idx = newIndex(4, q);
    assertThat(idx.size()).isZero();
    assertThat(idx.query(new float[] {0.5f, 0.5f, 0.5f, 0.5f}, 5)).isEmpty();
  }

  @Test
  void singleVectorIsReturnedExactly() {
    ScalarQuantizer q = ScalarQuantizer.train(new float[][] {{0f, 0f, 0f}, {1f, 1f, 1f}});
    QuantizedHnswIndex idx = newIndex(3, q);
    idx.add(99L, new float[] {0.5f, 0.5f, 0.5f});
    List<SearchResult> r = idx.query(new float[] {0.5f, 0.5f, 0.5f}, 1);
    assertThat(r).hasSize(1);
    assertThat(r.get(0).id()).isEqualTo(99L);
  }

  @Test
  void duplicateIdThrows() {
    ScalarQuantizer q = ScalarQuantizer.train(new float[][] {{0f, 0f}, {1f, 1f}});
    QuantizedHnswIndex idx = newIndex(2, q);
    idx.add(1L, new float[] {0f, 0f});
    assertThatThrownBy(() -> idx.add(1L, new float[] {1f, 1f}))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void deletedNodesAreSkipped() {
    ScalarQuantizer q = ScalarQuantizer.train(new float[][] {{0f, 0f}, {1f, 1f}});
    QuantizedHnswIndex idx = newIndex(2, q);
    idx.add(1L, new float[] {0f, 0f});
    idx.add(2L, new float[] {1f, 1f});
    assertThat(idx.delete(1L)).isTrue();
    var r = idx.query(new float[] {0f, 0f}, 5);
    assertThat(r).extracting(SearchResult::id).doesNotContain(1L);
  }

  @Test
  void nonL2MetricThrows() {
    ScalarQuantizer q = ScalarQuantizer.train(new float[][] {{0f, 0f}, {1f, 1f}});
    HnswConfig cfg = HnswConfig.defaults(2, new CosineDistance());
    assertThatThrownBy(() -> new QuantizedHnswIndex(cfg, q))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void quantizerDimMismatchThrows() {
    ScalarQuantizer q = ScalarQuantizer.train(new float[][] {{0f, 0f, 0f}});
    HnswConfig cfg = HnswConfig.defaults(2, new L2Distance());
    assertThatThrownBy(() -> new QuantizedHnswIndex(cfg, q))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void recallAtTenStaysAboveNinetyPercentOnTenKVectors() {
    int dim = 128;
    int n = 10_000;
    int qCount = 100;
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
    QuantizedHnswIndex idx = new QuantizedHnswIndex(cfg, quantizer);
    for (int i = 0; i < n; i++) {
      idx.add(i, data[i]);
    }

    Random qrng = new Random(seed + 1);
    DistanceMetric metric = new L2Distance();
    float overlap = 0f;
    for (int qi = 0; qi < qCount; qi++) {
      float[] query = new float[dim];
      for (int j = 0; j < dim; j++) {
        query[j] = (float) qrng.nextGaussian();
      }
      Set<Long> truth = bruteForceTopK(data, query, metric, k);
      List<SearchResult> got = idx.query(query, k);
      int hit = 0;
      for (SearchResult r : got) {
        if (truth.contains(r.id())) {
          hit++;
        }
      }
      overlap += hit / (float) k;
    }
    float recall = overlap / qCount;
    assertThat(recall)
        .as("recall@10 with QuantizedHnswIndex (int8 storage + int8 distance)")
        .isGreaterThanOrEqualTo(0.90f);
  }

  @Test
  void encodedStorageIsFourTimesSmallerThanFloatStorage() {
    int dim = 128;
    int n = 1000;
    Random r = new Random(1L);
    float[][] data = new float[n][dim];
    for (int i = 0; i < n; i++) {
      for (int j = 0; j < dim; j++) {
        data[i][j] = (float) r.nextGaussian();
      }
    }
    ScalarQuantizer q = ScalarQuantizer.train(data);
    QuantizedHnswIndex idx = new QuantizedHnswIndex(HnswConfig.defaults(dim, new L2Distance()), q);
    for (int i = 0; i < n; i++) {
      idx.add(i, data[i]);
    }
    long perVectorBytes = idx.bytesPerVector();
    long perVectorFloatBytes = (long) dim * 4;
    assertThat(perVectorBytes).isEqualTo(dim);
    assertThat(perVectorBytes * 4).isEqualTo(perVectorFloatBytes);
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
