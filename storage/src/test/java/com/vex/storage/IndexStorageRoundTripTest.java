package com.vex.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.vex.core.HnswConfig;
import com.vex.core.L2Distance;
import com.vex.core.SearchResult;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexStorageRoundTripTest {

  @Test
  void writeFlushReopenAndQueryReturnsSameResults(@TempDir Path tmp) throws Exception {
    int dim = 32;
    int n = 1_000;
    long seed = 99L;
    HnswConfig cfg = new HnswConfig(16, 200, 100, dim, new L2Distance(), seed);

    float[][] data = new float[n][dim];
    Random r = new Random(seed);
    for (int i = 0; i < n; i++) {
      for (int j = 0; j < dim; j++) {
        data[i][j] = (float) r.nextGaussian();
      }
    }

    List<SearchResult> firstResults;
    try (IndexStorage s = IndexStorage.open(tmp, cfg, false)) {
      for (int i = 0; i < n; i++) {
        s.add(i, data[i]);
      }
      assertThat(s.size()).isEqualTo(n);
      firstResults = s.query(data[0], 10);
      s.flush();
    }

    try (IndexStorage s = IndexStorage.open(tmp, cfg, false)) {
      assertThat(s.size()).isEqualTo(n);
      for (int i = 0; i < n; i++) {
        assertThat(s.contains(i)).as("id %d", i).isTrue();
      }
      List<SearchResult> reopened = s.query(data[0], 10);
      assertThat(reopened).hasSize(firstResults.size());
      for (int i = 0; i < reopened.size(); i++) {
        assertThat(reopened.get(i).id()).isEqualTo(firstResults.get(i).id());
        assertThat(reopened.get(i).distance())
            .isCloseTo(firstResults.get(i).distance(), org.assertj.core.data.Offset.offset(1e-4f));
      }
    }
  }

  @Test
  void deleteSurvivesRestart(@TempDir Path tmp) throws Exception {
    HnswConfig cfg = HnswConfig.defaults(4, new L2Distance());
    try (IndexStorage s = IndexStorage.open(tmp, cfg, false)) {
      s.add(1L, new float[] {1, 0, 0, 0});
      s.add(2L, new float[] {0, 1, 0, 0});
      s.add(3L, new float[] {0, 0, 1, 0});
      s.delete(2L);
      s.flush();
    }
    try (IndexStorage s = IndexStorage.open(tmp, cfg, false)) {
      assertThat(s.contains(1L)).isTrue();
      assertThat(s.contains(2L)).isFalse();
      assertThat(s.contains(3L)).isTrue();
      assertThat(s.size()).isEqualTo(2);
    }
  }

  @Test
  void getVectorReturnsExactBytesAfterReopen(@TempDir Path tmp) throws Exception {
    HnswConfig cfg = HnswConfig.defaults(8, new L2Distance());
    float[] v = {1.5f, -2.5f, 3.25f, 4.75f, 5f, 6f, 7f, 8f};
    try (IndexStorage s = IndexStorage.open(tmp, cfg, false)) {
      s.add(42L, v);
      s.flush();
    }
    try (IndexStorage s = IndexStorage.open(tmp, cfg, false)) {
      assertThat(s.getVector(42L)).containsExactly(v);
    }
  }

  @Test
  void writingEmptyIndexAndReopeningWorks(@TempDir Path tmp) throws Exception {
    HnswConfig cfg = HnswConfig.defaults(4, new L2Distance());
    try (IndexStorage s = IndexStorage.open(tmp, cfg, false)) {
      s.flush();
    }
    try (IndexStorage s = IndexStorage.open(tmp, cfg, false)) {
      assertThat(s.size()).isZero();
      assertThat(s.query(new float[] {1, 2, 3, 4}, 3)).isEmpty();
    }
  }
}
