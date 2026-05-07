package com.vex.bench;

import com.vex.core.DistanceMetric;
import com.vex.core.HnswConfig;
import com.vex.core.HnswIndex;
import com.vex.core.L2Distance;
import com.vex.core.SearchResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * For one query, prints how many nodes were visited at layer 0 and how many of the brute-force
 * true-top-10 are in the HNSW top-256.
 */
public final class QueryDebug {

  public static void main(String[] args) {
    int n = 100_000;
    int dim = 128;
    long seed = 12345L;

    Random r = new Random(seed);
    float[][] data = new float[n][dim];
    for (int i = 0; i < n; i++) {
      for (int j = 0; j < dim; j++) {
        data[i][j] = (float) r.nextGaussian();
      }
    }

    HnswConfig cfg = new HnswConfig(16, 200, 50, dim, new L2Distance(), seed);
    HnswIndex idx = new HnswIndex(cfg);
    BenchOut.infof("building %d vectors...", n);
    long t = System.nanoTime();
    for (int i = 0; i < n; i++) {
      idx.add(i, data[i]);
    }
    BenchOut.infof("build: %d ms", (System.nanoTime() - t) / 1_000_000);

    Random qrng = new Random(seed + 1);
    DistanceMetric metric = new L2Distance();

    int[] efs = {32, 64, 128, 256, 512, 1024};
    int sampleQueries = 20;
    for (int ef : efs) {
      double recallSum = 0;
      long visitsSum = 0;
      qrng.setSeed(seed + 1);
      for (int qi = 0; qi < sampleQueries; qi++) {
        float[] q = new float[dim];
        for (int j = 0; j < dim; j++) {
          q[j] = (float) qrng.nextGaussian();
        }
        Set<Long> truth = bruteForceTopK(data, q, metric, 10);
        List<SearchResult> got = idx.query(q, 10, ef);
        int hit = 0;
        for (SearchResult sr : got) {
          if (truth.contains(sr.id())) hit++;
        }
        recallSum += hit / 10.0;
        visitsSum += HnswIndex.lastSearchLayerVisits;
      }
      BenchOut.infof(
          "ef=%-5d  recall@10=%.4f  avg-visits=%-7d  visits/N=%.4f",
          ef,
          recallSum / sampleQueries,
          visitsSum / sampleQueries,
          visitsSum / (double) sampleQueries / n);
    }
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
