package com.vex.bench;

import com.vex.core.DistanceMetric;
import com.vex.core.HnswConfig;
import com.vex.core.HnswIndex;
import com.vex.core.L2Distance;
import com.vex.core.ScalarQuantizer;
import com.vex.core.SearchResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Plain main-method recall sweep. Builds an HNSW index of 100k random Gaussian dim-128 vectors,
 * runs queries at several efSearch values, and prints recall@10 against a brute-force baseline.
 *
 * <p>Also runs the same sweep with vectors round-tripped through {@link ScalarQuantizer} to show
 * the recall cost of quantization. Numbers from this script feed into docs/benchmarks.md.
 */
public final class RecallSweep {

  private static final int N = 100_000;
  private static final int DIM = 128;
  private static final int Q = 200;
  private static final int K = 10;
  private static final long SEED = 12345L;
  private static final int[] EF_VALUES = {16, 32, 64, 128, 256};

  public static void main(String[] args) {
    System.out.println("== generating " + N + " random Gaussian dim-" + DIM + " vectors ==");
    float[][] data = Datasets.randomGaussian(N, DIM, SEED);
    float[][] queries = Datasets.randomGaussian(Q, DIM, SEED + 1);

    System.out.println("== brute-force baseline ==");
    long bfStart = System.nanoTime();
    DistanceMetric metric = new L2Distance();
    List<Set<Long>> truth = new ArrayList<>(Q);
    for (float[] q : queries) {
      truth.add(bruteForceTopK(data, q, metric, K));
    }
    long bfMs = (System.nanoTime() - bfStart) / 1_000_000;
    System.out.printf("brute-force computed %d top-%d sets in %d ms%n", Q, K, bfMs);

    boolean mSweep = "--m-sweep".equals(args.length > 0 ? args[0] : "");
    if (mSweep) {
      System.out.println();
      System.out.println("== M sweep at fixed efC=200, efS sweep ==");
      for (int m : new int[] {16, 24, 32}) {
        System.out.println();
        System.out.println("-- M = " + m + " --");
        runSweep("M=" + m, data, queries, truth, false, true, m, 200);
      }
      return;
    }

    System.out.println();
    System.out.println("== float-precision HNSW (heuristic neighbor selection) ==");
    runSweep("float-h", data, queries, truth, false, true, 16, 200);

    System.out.println();
    System.out.println("== float-precision HNSW (simple top-M neighbor selection) ==");
    runSweep("float-s", data, queries, truth, false, false, 16, 200);

    System.out.println();
    System.out.println("== scalar-quantized round-trip HNSW (heuristic) ==");
    runSweep("quant-h", data, queries, truth, true, true, 16, 200);
  }

  private static void runSweep(
      String label,
      float[][] data,
      float[][] queries,
      List<Set<Long>> truth,
      boolean quantize,
      boolean useHeuristic,
      int m,
      int efConstruction) {
    ScalarQuantizer quantizer = quantize ? ScalarQuantizer.train(data) : null;

    HnswConfig cfg =
        new HnswConfig(m, efConstruction, 50, DIM, new L2Distance(), SEED, useHeuristic);
    HnswIndex idx = new HnswIndex(cfg);

    long buildStart = System.nanoTime();
    for (int i = 0; i < N; i++) {
      float[] v = quantize ? quantizer.decode(quantizer.encode(data[i])) : data[i];
      idx.add(i, v);
    }
    long buildMs = (System.nanoTime() - buildStart) / 1_000_000;
    System.out.printf(
        "[%s] build: %d ms (%.0f inserts/sec)%n", label, buildMs, N * 1000.0 / buildMs);

    System.out.printf("%-8s%-10s%-12s%-12s%n", "label", "efSearch", "recall@10", "ms/query");
    for (int ef : EF_VALUES) {
      double recall = 0.0;
      long start = System.nanoTime();
      for (int qi = 0; qi < Q; qi++) {
        float[] q = quantize ? quantizer.decode(quantizer.encode(queries[qi])) : queries[qi];
        List<SearchResult> got = idx.query(q, K, ef);
        int hit = 0;
        for (SearchResult r : got) {
          if (truth.get(qi).contains(r.id())) {
            hit++;
          }
        }
        recall += hit / (double) K;
      }
      double avgMs = (System.nanoTime() - start) / 1_000_000.0 / Q;
      System.out.printf("%-8s%-10d%-12.4f%-12.3f%n", label, ef, recall / Q, avgMs);
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
