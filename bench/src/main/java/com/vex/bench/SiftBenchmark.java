package com.vex.bench;

import com.vex.core.HnswConfig;
import com.vex.core.HnswIndex;
import com.vex.core.L2Distance;
import com.vex.core.SearchResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Recall + latency sweep on the SIFT-1M ANN benchmark dataset.
 *
 * <p>Expects three files under the directory passed as {@code argv[0]}:
 *
 * <ul>
 *   <li>{@code sift_base.fvecs} — 1,000,000 base vectors of dim 128.
 *   <li>{@code sift_query.fvecs} — 10,000 query vectors of dim 128.
 *   <li>{@code sift_groundtruth.ivecs} — 10,000 × 100 ground-truth nearest-neighbor ids.
 * </ul>
 *
 * Dataset is not committed to the repo. Use {@code scripts/download_sift.sh} to fetch.
 */
public final class SiftBenchmark {

  public static void main(String[] args) throws Exception {
    if (args.length < 1) {
      System.err.println(
          "usage: SiftBenchmark <dir-with-sift_base.fvecs/sift_query.fvecs/sift_groundtruth.ivecs>");
      System.exit(2);
    }
    Path dir = Path.of(args[0]);
    Path basePath = dir.resolve("sift_base.fvecs");
    Path queryPath = dir.resolve("sift_query.fvecs");
    Path truthPath = dir.resolve("sift_groundtruth.ivecs");

    if (!Files.exists(basePath) || !Files.exists(queryPath) || !Files.exists(truthPath)) {
      System.err.println("Missing one of: " + basePath + ", " + queryPath + ", " + truthPath);
      System.err.println("Run scripts/download_sift.sh to fetch the dataset.");
      System.exit(2);
    }

    System.out.println("== loading SIFT-1M ==");
    long t = System.nanoTime();
    float[][] base = Fvecs.readFvecs(basePath);
    float[][] queries = Fvecs.readFvecs(queryPath);
    int[][] truth = Fvecs.readIvecs(truthPath);
    long loadMs = (System.nanoTime() - t) / 1_000_000;
    System.out.printf(
        "loaded base=%d queries=%d truth=%d dim=%d in %d ms%n",
        base.length, queries.length, truth.length, base[0].length, loadMs);

    int dim = base[0].length;
    long seed = 42L;
    HnswConfig cfg = new HnswConfig(16, 200, 50, dim, new L2Distance(), seed);
    HnswIndex idx = new HnswIndex(cfg);

    System.out.println();
    System.out.println("== building HNSW (single-writer, this takes a while) ==");
    long buildStart = System.nanoTime();
    long lastTick = buildStart;
    for (int i = 0; i < base.length; i++) {
      idx.add(i, base[i]);
      if (i > 0 && i % 50_000 == 0) {
        long now = System.nanoTime();
        double rate = 50_000 * 1e9 / (now - lastTick);
        System.out.printf("  %d / %d  (%.0f ins/sec recent)%n", i, base.length, rate);
        lastTick = now;
      }
    }
    long buildMs = (System.nanoTime() - buildStart) / 1_000_000;
    System.out.printf("build: %d ms (%.0f ins/sec avg)%n", buildMs, base.length * 1000.0 / buildMs);

    int[] efValues = {16, 32, 64, 128, 256};
    int k = 10;
    int qLimit = Math.min(queries.length, 1000);
    System.out.println();
    System.out.printf("%-10s%-12s%-12s%n", "efSearch", "recall@10", "ms/query");
    for (int ef : efValues) {
      double recallSum = 0.0;
      long start = System.nanoTime();
      for (int qi = 0; qi < qLimit; qi++) {
        Set<Integer> truthTopK = new HashSet<>();
        for (int i = 0; i < k && i < truth[qi].length; i++) {
          truthTopK.add(truth[qi][i]);
        }
        List<SearchResult> got = idx.query(queries[qi], k, ef);
        int hit = 0;
        for (SearchResult r : got) {
          if (truthTopK.contains((int) r.id())) {
            hit++;
          }
        }
        recallSum += hit / (double) k;
      }
      double avgMs = (System.nanoTime() - start) / 1_000_000.0 / qLimit;
      System.out.printf("%-10d%-12.4f%-12.3f%n", ef, recallSum / qLimit, avgMs);
    }
  }
}
