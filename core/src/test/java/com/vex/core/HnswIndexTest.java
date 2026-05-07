package com.vex.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class HnswIndexTest {

  private static HnswIndex newIndex(int dim, DistanceMetric metric) {
    return new HnswIndex(HnswConfig.defaults(dim, metric));
  }

  @Test
  void emptyIndexReturnsEmptyResults() {
    HnswIndex idx = newIndex(8, new L2Distance());
    assertThat(idx.size()).isZero();
    assertThat(idx.query(new float[8], 5)).isEmpty();
  }

  @Test
  void singleVectorIndexReturnsThatVector() {
    HnswIndex idx = newIndex(4, new L2Distance());
    float[] v = {1f, 2f, 3f, 4f};
    idx.add(42L, v);

    List<SearchResult> r = idx.query(new float[] {1f, 2f, 3f, 4f}, 1);
    assertThat(r).hasSize(1);
    assertThat(r.get(0).id()).isEqualTo(42L);
    assertThat(r.get(0).distance()).isZero();
  }

  @Test
  void containsReflectsLiveState() {
    HnswIndex idx = newIndex(2, new L2Distance());
    idx.add(1L, new float[] {0f, 0f});
    assertThat(idx.contains(1L)).isTrue();
    assertThat(idx.contains(2L)).isFalse();
  }

  @Test
  void duplicateIdThrows() {
    HnswIndex idx = newIndex(2, new L2Distance());
    idx.add(1L, new float[] {0f, 0f});
    assertThatThrownBy(() -> idx.add(1L, new float[] {1f, 1f}))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Duplicate id");
  }

  @Test
  void dimensionMismatchOnAddThrows() {
    HnswIndex idx = newIndex(4, new L2Distance());
    assertThatThrownBy(() -> idx.add(1L, new float[] {0f, 0f}))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void dimensionMismatchOnQueryThrows() {
    HnswIndex idx = newIndex(4, new L2Distance());
    idx.add(1L, new float[] {0f, 0f, 0f, 0f});
    assertThatThrownBy(() -> idx.query(new float[] {0f, 0f}, 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void deletedVectorsDoNotAppearInResults() {
    HnswIndex idx = newIndex(2, new L2Distance());
    idx.add(1L, new float[] {0f, 0f});
    idx.add(2L, new float[] {1f, 1f});
    idx.add(3L, new float[] {2f, 2f});
    assertThat(idx.size()).isEqualTo(3);

    assertThat(idx.delete(2L)).isTrue();
    assertThat(idx.size()).isEqualTo(2);
    assertThat(idx.contains(2L)).isFalse();

    List<SearchResult> r = idx.query(new float[] {1f, 1f}, 3);
    assertThat(r).extracting(SearchResult::id).doesNotContain(2L);
  }

  @Test
  void deleteThenReinsertWithSameIdSucceeds() {
    HnswIndex idx = newIndex(2, new L2Distance());
    idx.add(1L, new float[] {0f, 0f});
    idx.delete(1L);
    idx.add(1L, new float[] {5f, 5f});
    assertThat(idx.contains(1L)).isTrue();
  }

  @Test
  void recallAtTenAchievesAtLeastNinetyFivePercentOnTenKVectors() {
    int dim = 128;
    int n = 10_000;
    int q = 100;
    int k = 10;
    long seed = 12345L;

    HnswConfig cfg = new HnswConfig(16, 200, 200, dim, new L2Distance(), seed);
    HnswIndex idx = new HnswIndex(cfg);

    Random rng = new Random(seed);
    float[][] data = new float[n][dim];
    for (int i = 0; i < n; i++) {
      for (int j = 0; j < dim; j++) {
        data[i][j] = (float) rng.nextGaussian();
      }
      idx.add(i, data[i]);
    }

    Random qrng = new Random(seed + 1);
    DistanceMetric metric = new L2Distance();
    float overlapTotal = 0f;
    for (int qi = 0; qi < q; qi++) {
      float[] query = new float[dim];
      for (int j = 0; j < dim; j++) {
        query[j] = (float) qrng.nextGaussian();
      }
      List<long[]> bf = bruteForceTopK(data, query, metric, k);
      Set<Long> truth = new HashSet<>();
      for (long[] pair : bf) {
        truth.add(pair[0]);
      }
      List<SearchResult> got = idx.query(query, k);
      int hit = 0;
      for (SearchResult r : got) {
        if (truth.contains(r.id())) {
          hit++;
        }
      }
      overlapTotal += hit / (float) k;
    }
    float recall = overlapTotal / q;
    assertThat(recall).as("recall@10").isGreaterThanOrEqualTo(0.95f);
  }

  @Test
  void concurrentQueriesProduceConsistentResults() throws Exception {
    int dim = 16;
    int n = 1_000;
    long seed = 7L;
    HnswConfig cfg = new HnswConfig(16, 100, 50, dim, new L2Distance(), seed);
    HnswIndex idx = new HnswIndex(cfg);

    Random rng = new Random(seed);
    float[][] data = new float[n][dim];
    for (int i = 0; i < n; i++) {
      for (int j = 0; j < dim; j++) {
        data[i][j] = (float) rng.nextGaussian();
      }
      idx.add(i, data[i]);
    }

    int threads = 8;
    int queriesPerThread = 100;
    Random qrng = new Random(seed + 1);
    float[][] queries = new float[queriesPerThread][dim];
    for (int q = 0; q < queriesPerThread; q++) {
      for (int j = 0; j < dim; j++) {
        queries[q][j] = (float) qrng.nextGaussian();
      }
    }

    List<List<SearchResult>> baseline = new ArrayList<>(queriesPerThread);
    for (int q = 0; q < queriesPerThread; q++) {
      baseline.add(idx.query(queries[q], 10));
    }

    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<Future<Boolean>> futures = new ArrayList<>();
      for (int t = 0; t < threads; t++) {
        futures.add(
            pool.submit(
                () -> {
                  for (int q = 0; q < queriesPerThread; q++) {
                    List<SearchResult> r = idx.query(queries[q], 10);
                    if (!sameIds(r, baseline.get(q))) {
                      return false;
                    }
                  }
                  return true;
                }));
      }
      for (Future<Boolean> f : futures) {
        assertThat(f.get(60, TimeUnit.SECONDS)).isTrue();
      }
    } finally {
      pool.shutdown();
      assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  private static boolean sameIds(List<SearchResult> a, List<SearchResult> b) {
    if (a.size() != b.size()) {
      return false;
    }
    for (int i = 0; i < a.size(); i++) {
      if (a.get(i).id() != b.get(i).id()) {
        return false;
      }
    }
    return true;
  }

  private static List<long[]> bruteForceTopK(
      float[][] data, float[] q, DistanceMetric metric, int k) {
    List<long[]> all = new ArrayList<>(data.length);
    for (int i = 0; i < data.length; i++) {
      all.add(new long[] {i, Float.floatToRawIntBits(metric.distance(q, data[i]))});
    }
    all.sort(Comparator.comparingDouble(p -> Float.intBitsToFloat((int) p[1])));
    return all.subList(0, Math.min(k, all.size()));
  }
}
