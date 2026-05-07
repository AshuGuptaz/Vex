package com.vex.bench;

import com.vex.core.HnswConfig;
import com.vex.core.HnswIndex;
import com.vex.core.L2Distance;
import java.lang.reflect.Field;
import java.util.Random;

/**
 * Inspects the actual connection count distribution at each layer of a freshly-built index. The
 * paper's expectation is that nodes at layer 0 have close to {@code 2*M} connections after enough
 * inserts. If the diversity heuristic is rejecting heavily, the average will fall well short of
 * that, which would explain a recall regression.
 */
public final class GraphInspect {

  public static void main(String[] args) throws Exception {
    int n = 50_000;
    int dim = 128;
    long seed = 12345L;

    Random r = new Random(seed);
    float[][] data = new float[n][dim];
    for (int i = 0; i < n; i++) {
      for (int j = 0; j < dim; j++) {
        data[i][j] = (float) r.nextGaussian();
      }
    }

    int M = 16;
    HnswConfig cfg = new HnswConfig(M, 200, 50, dim, new L2Distance(), seed);
    HnswIndex idx = new HnswIndex(cfg);
    BenchOut.infof("building %d vectors at M=%d...", n, M);
    long start = System.nanoTime();
    for (int i = 0; i < n; i++) {
      idx.add(i, data[i]);
    }
    long buildMs = (System.nanoTime() - start) / 1_000_000;
    BenchOut.infof("built in %d ms", buildMs);

    // Pull internal arrays via reflection — internal API, but we own both modules.
    Field connectionsField = HnswIndex.class.getDeclaredField("connections");
    connectionsField.setAccessible(true);
    int[][][] connections = (int[][][]) connectionsField.get(idx);
    Field sizeField = HnswIndex.class.getDeclaredField("size");
    sizeField.setAccessible(true);
    int size = sizeField.getInt(idx);
    Field topLayerField = HnswIndex.class.getDeclaredField("topLayer");
    topLayerField.setAccessible(true);
    int topLayer = topLayerField.getInt(idx);

    BenchOut.info();
    BenchOut.infof("topLayer = %d   size = %d", topLayer, size);
    BenchOut.infof("expected layer 0 cap (2*M)   = %d", 2 * M);
    BenchOut.infof("expected layer >0 cap (M)    = %d", M);
    BenchOut.info();

    for (int lc = 0; lc <= topLayer; lc++) {
      long count = 0;
      long sum = 0;
      int min = Integer.MAX_VALUE;
      int max = 0;
      int saturatedAtCap = 0;
      int cap = (lc == 0) ? 2 * M : M;
      for (int i = 0; i < size; i++) {
        int[][] perLayer = connections[i];
        if (perLayer == null || lc >= perLayer.length) continue;
        int len = perLayer[lc].length;
        sum += len;
        if (len < min) min = len;
        if (len > max) max = len;
        if (len == cap) saturatedAtCap++;
        count++;
      }
      double avg = count > 0 ? (double) sum / count : 0.0;
      double saturatedPct = count > 0 ? 100.0 * saturatedAtCap / count : 0.0;
      BenchOut.infof(
          "layer %d  nodes=%d  avg=%.1f  min=%d  max=%d  at-cap=%.1f%%",
          lc, count, avg, min, max, saturatedPct);
    }
  }
}
