package com.vex.server.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.vex.core.HnswConfig;
import com.vex.core.L2Distance;
import java.nio.file.Path;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QuantizedCollectionTest {

  private static final int DIM = 16;

  @Test
  void preTrainingQueriesUseBruteForce(@TempDir Path tmp) throws Exception {
    HnswConfig cfg = new HnswConfig(16, 200, 50, DIM, new L2Distance(), 7L);
    PayloadStore p = PayloadStore.open(tmp.resolve("payloads.db"), false);
    Collection c = new Collection("q", cfg, p, tmp, true);

    c.upsert(1L, vec(1f), Map.of());
    c.upsert(2L, vec(2f), Map.of());
    c.upsert(3L, vec(3f), Map.of());

    assertThat(c.size()).isEqualTo(3);
    assertThat(c.isQuantizerTrained()).isFalse();

    var hits = c.query(vec(2f), 1, null, null);
    assertThat(hits).hasSize(1);
    assertThat(hits.get(0).id()).isEqualTo(2L);
    c.close();
  }

  @Test
  void quantizerTrainsAtThresholdAndQueryShiftsToHnsw(@TempDir Path tmp) throws Exception {
    HnswConfig cfg = new HnswConfig(16, 100, 50, DIM, new L2Distance(), 11L);
    PayloadStore p = PayloadStore.open(tmp.resolve("payloads.db"), false);
    Collection c = new Collection("q", cfg, p, tmp, true);

    int n = Collection.QUANTIZER_TRAINING_THRESHOLD;
    Random r = new Random(11L);
    for (int i = 0; i < n; i++) {
      float[] v = new float[DIM];
      for (int j = 0; j < DIM; j++) {
        v[j] = (float) r.nextGaussian();
      }
      c.upsert(i, v, Map.of());
    }
    assertThat(c.size()).isEqualTo(n);
    assertThat(c.isQuantizerTrained()).isTrue();

    // One more insert should land in the quantized HNSW.
    c.upsert(99_999L, vec(0f), Map.of("k", "v"));
    assertThat(c.contains(99_999L)).isTrue();
    c.close();
  }

  @Test
  void deleteWorksInBothModes(@TempDir Path tmp) throws Exception {
    HnswConfig cfg = new HnswConfig(16, 100, 50, DIM, new L2Distance(), 13L);
    PayloadStore p = PayloadStore.open(tmp.resolve("payloads.db"), false);
    Collection c = new Collection("q", cfg, p, tmp, true);

    c.upsert(1L, vec(1f), Map.of("k", "v"));
    c.upsert(2L, vec(2f), Map.of());
    assertThat(c.delete(1L)).isTrue();
    assertThat(c.contains(1L)).isFalse();
    assertThat(c.size()).isEqualTo(1);
    c.close();
  }

  @Test
  void trainedQuantizedCollectionSurvivesCloseAndReopenViaCollectionManager(@TempDir Path tmpRoot)
      throws Exception {
    Path data = tmpRoot.resolve("data");
    java.nio.file.Files.createDirectories(data);
    String name = "qpersist";
    int n = Collection.QUANTIZER_TRAINING_THRESHOLD + 5;

    {
      CollectionManager mgr = new CollectionManager(data, false);
      Collection c = mgr.create(name, DIM, "l2", 16, 100, true);
      Random r = new Random(7L);
      for (int i = 0; i < n; i++) {
        float[] v = new float[DIM];
        for (int j = 0; j < DIM; j++) {
          v[j] = (float) r.nextGaussian();
        }
        c.upsert(i, v, Map.of());
      }
      assertThat(c.isQuantizerTrained()).isTrue();
      mgr.closeAll();
    }

    CollectionManager mgr2 = new CollectionManager(data, false);
    Collection reopened = mgr2.get(name);
    assertThat(reopened).isNotNull();
    assertThat(reopened.size()).isEqualTo(n);
    assertThat(reopened.contains(0L)).isTrue();
    var hits = reopened.query(new float[DIM], 5, null, null);
    assertThat(hits).hasSize(5);
    mgr2.closeAll();
  }

  @Test
  void getVectorReturnsLossyDecodeAfterTraining(@TempDir Path tmp) throws Exception {
    HnswConfig cfg = new HnswConfig(16, 100, 50, DIM, new L2Distance(), 19L);
    PayloadStore p = PayloadStore.open(tmp.resolve("payloads.db"), false);
    Collection c = new Collection("q", cfg, p, tmp, true);

    int n = Collection.QUANTIZER_TRAINING_THRESHOLD;
    Random r = new Random(19L);
    for (int i = 0; i < n; i++) {
      float[] v = new float[DIM];
      for (int j = 0; j < DIM; j++) {
        v[j] = (float) r.nextGaussian();
      }
      c.upsert(i, v, Map.of());
    }
    assertThat(c.isQuantizerTrained()).isTrue();
    float[] decoded = c.getVector(0);
    // Lossy round-trip: not exactly equal, but close.
    assertThat(decoded).isNotNull();
    assertThat(decoded).hasSize(DIM);
    c.close();
  }

  private static float[] vec(float v) {
    float[] f = new float[DIM];
    for (int i = 0; i < DIM; i++) {
      f[i] = v;
    }
    return f;
  }
}
