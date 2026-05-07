package com.vex.bench;

import com.vex.core.HnswConfig;
import com.vex.core.HnswIndex;
import com.vex.core.L2Distance;
import com.vex.core.SearchResult;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Top-k query latency at varying efSearch. Index size is fixed at 100k vectors of dim 128. The
 * benchmark uses {@link Mode#SampleTime} so JMH reports a percentile distribution (P50, P99, max).
 */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 3)
@Measurement(iterations = 3, time = 5)
@Fork(value = 1, warmups = 0)
@State(Scope.Benchmark)
public class QueryLatencyBenchmark {

  @Param({"16", "32", "64", "128", "256"})
  public int efSearch;

  private HnswIndex index;
  private float[][] queries;
  private int qIdx;

  @Setup(Level.Trial)
  public void setup() {
    int n = 100_000;
    int dim = 128;
    long seed = 42L;
    HnswConfig cfg = new HnswConfig(16, 200, 50, dim, new L2Distance(), seed);
    index = new HnswIndex(cfg);

    float[][] data = Datasets.randomGaussian(n, dim, seed);
    for (int i = 0; i < n; i++) {
      index.add(i, data[i]);
    }

    int qCount = 1024;
    Random r = new Random(seed + 1);
    queries = new float[qCount][dim];
    for (int q = 0; q < qCount; q++) {
      for (int j = 0; j < dim; j++) {
        queries[q][j] = (float) r.nextGaussian();
      }
    }
    qIdx = 0;
  }

  @Benchmark
  public List<SearchResult> queryTopTen() {
    int q = (qIdx++) & (queries.length - 1);
    return index.query(queries[q], 10, efSearch);
  }
}
