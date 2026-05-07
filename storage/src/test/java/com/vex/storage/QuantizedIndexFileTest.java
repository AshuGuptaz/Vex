package com.vex.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.vex.core.HnswConfig;
import com.vex.core.L2Distance;
import com.vex.core.QuantizedHnswIndex;
import com.vex.core.ScalarQuantizer;
import com.vex.core.SearchResult;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QuantizedIndexFileTest {

  @Test
  void writeReadRoundTrip(@TempDir Path tmp) throws Exception {
    int dim = 32;
    int n = 500;
    long seed = 11L;

    Random r = new Random(seed);
    float[][] data = new float[n][dim];
    for (int i = 0; i < n; i++) {
      for (int j = 0; j < dim; j++) {
        data[i][j] = (float) r.nextGaussian();
      }
    }
    ScalarQuantizer q = ScalarQuantizer.train(data);
    HnswConfig cfg = new HnswConfig(16, 200, 100, dim, new L2Distance(), seed);
    QuantizedHnswIndex original = new QuantizedHnswIndex(cfg, q);
    for (int i = 0; i < n; i++) {
      original.insert(i, data[i]);
    }
    List<SearchResult> originalQuery = original.query(data[0], 10);

    Path file = tmp.resolve("q.vex");
    IndexFile.writeQuantized(file, original);
    assertThat(IndexFile.isQuantized(file)).isTrue();

    QuantizedHnswIndex read = IndexFile.readQuantized(file);
    assertThat(read.size()).isEqualTo(n);
    List<SearchResult> readQuery = read.query(data[0], 10);
    assertThat(readQuery).hasSize(originalQuery.size());
    for (int i = 0; i < readQuery.size(); i++) {
      assertThat(readQuery.get(i).id()).isEqualTo(originalQuery.get(i).id());
    }
  }

  @Test
  void floatFileIsNotMarkedQuantized(@TempDir Path tmp) throws Exception {
    HnswConfig cfg = HnswConfig.defaults(4, new L2Distance());
    com.vex.core.HnswIndex floatIndex = new com.vex.core.HnswIndex(cfg);
    floatIndex.insert(1L, new float[] {1, 0, 0, 0});
    Path file = tmp.resolve("f.vex");
    IndexFile.write(file, floatIndex);
    assertThat(IndexFile.isQuantized(file)).isFalse();
  }
}
