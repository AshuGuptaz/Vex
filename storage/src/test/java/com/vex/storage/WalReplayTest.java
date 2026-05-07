package com.vex.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.vex.core.HnswConfig;
import com.vex.core.L2Distance;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WalReplayTest {

  @Test
  void insertsWithoutFlushSurviveReopen(@TempDir Path tmp) throws Exception {
    int dim = 16;
    int n = 500;
    long seed = 1L;
    HnswConfig cfg = HnswConfig.defaults(dim, new L2Distance());

    Random r = new Random(seed);
    float[][] data = new float[n][dim];
    for (int i = 0; i < n; i++) {
      for (int j = 0; j < dim; j++) {
        data[i][j] = (float) r.nextGaussian();
      }
    }

    IndexStorage s = IndexStorage.open(tmp, cfg, true);
    for (int i = 0; i < n; i++) {
      s.add(i, data[i]);
    }
    // Note: NO flush. Simulate a process exit by leaking the storage handle.
    // The WAL has been written; the index file is still empty.
    s.index().close();

    Path indexFile = tmp.resolve(IndexStorage.INDEX_FILE);
    assertThat(Files.exists(indexFile)).isFalse();
    Path walFile = tmp.resolve(IndexStorage.WAL_FILE);
    assertThat(Files.exists(walFile)).isTrue();
    assertThat(Files.size(walFile)).isPositive();

    try (IndexStorage reopened = IndexStorage.open(tmp, cfg, false)) {
      assertThat(reopened.size()).isEqualTo(n);
      for (int i = 0; i < n; i++) {
        assertThat(reopened.contains(i)).as("id %d", i).isTrue();
      }
    }
  }

  @Test
  void replayedDeleteIsApplied(@TempDir Path tmp) throws Exception {
    HnswConfig cfg = HnswConfig.defaults(4, new L2Distance());
    IndexStorage s = IndexStorage.open(tmp, cfg, true);
    s.add(1L, new float[] {0, 0, 0, 0});
    s.add(2L, new float[] {1, 1, 1, 1});
    s.delete(1L);
    s.index().close();

    try (IndexStorage reopened = IndexStorage.open(tmp, cfg, false)) {
      assertThat(reopened.size()).isEqualTo(1);
      assertThat(reopened.contains(1L)).isFalse();
      assertThat(reopened.contains(2L)).isTrue();
    }
  }

  @Test
  void mixedFlushAndPostFlushWalIsApplied(@TempDir Path tmp) throws Exception {
    HnswConfig cfg = HnswConfig.defaults(4, new L2Distance());
    IndexStorage s = IndexStorage.open(tmp, cfg, true);
    s.add(1L, new float[] {1, 0, 0, 0});
    s.add(2L, new float[] {0, 1, 0, 0});
    s.flush();
    // Now add more after flush; these are only in the WAL.
    s.add(3L, new float[] {0, 0, 1, 0});
    s.add(4L, new float[] {0, 0, 0, 1});
    s.index().close();

    try (IndexStorage reopened = IndexStorage.open(tmp, cfg, false)) {
      assertThat(reopened.size()).isEqualTo(4);
      for (long id : new long[] {1L, 2L, 3L, 4L}) {
        assertThat(reopened.contains(id)).isTrue();
      }
    }
  }
}
