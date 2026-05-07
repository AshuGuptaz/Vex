package com.vex.bench;

import com.vex.core.HnswConfig;
import com.vex.core.HnswIndex;
import com.vex.core.L2Distance;
import com.vex.core.QuantizedHnswIndex;
import com.vex.core.ScalarQuantizer;

/**
 * Builds the same index twice — once as {@link HnswIndex} (float storage), once as {@link
 * QuantizedHnswIndex} (int8 storage) — and reports the heap delta of the vector storage.
 *
 * <p>Methodology: snapshot {@code Runtime.totalMemory() - Runtime.freeMemory()} after build with a
 * preceding {@code System.gc()} hint. The numbers are noisy (depend on GC state, JIT decisions,
 * concurrent allocations) but the *ratio* between the two builds is stable.
 *
 * <p>Numbers from a recorded run feed into docs/benchmarks.md.
 */
public final class MemoryComparison {

  private static final int N = 50_000;
  private static final int DIM = 128;
  private static final long SEED = 7L;

  public static void main(String[] args) {
    BenchOut.info("== generating " + N + " random Gaussian dim-" + DIM + " vectors ==");
    float[][] data = Datasets.randomGaussian(N, DIM, SEED);

    BenchOut.info();
    BenchOut.info("== float HnswIndex ==");
    long floatHeap =
        measure(
            () -> {
              HnswConfig cfg = new HnswConfig(16, 200, 50, DIM, new L2Distance(), SEED);
              HnswIndex idx = new HnswIndex(cfg);
              for (int i = 0; i < N; i++) {
                idx.add(i, data[i]);
              }
              return idx;
            });

    BenchOut.info();
    BenchOut.info("== QuantizedHnswIndex (int8) ==");
    long quantHeap =
        measure(
            () -> {
              ScalarQuantizer q = ScalarQuantizer.train(data);
              HnswConfig cfg = new HnswConfig(16, 200, 50, DIM, new L2Distance(), SEED);
              QuantizedHnswIndex idx = new QuantizedHnswIndex(cfg, q);
              for (int i = 0; i < N; i++) {
                idx.add(i, data[i]);
              }
              return idx;
            });

    long vecOnlyFloat = (long) N * DIM * 4;
    long vecOnlyByte = (long) N * DIM;
    BenchOut.info();
    BenchOut.infof(
        "vector storage alone: float = %s, int8 = %s (%.0f%% reduction)",
        humanize(vecOnlyFloat),
        humanize(vecOnlyByte),
        100.0 * (vecOnlyFloat - vecOnlyByte) / vecOnlyFloat);
    BenchOut.infof(
        "total heap after build: float = %s, int8 = %s (%.0f%% reduction)",
        humanize(floatHeap),
        humanize(quantHeap),
        100.0 * (floatHeap - quantHeap) / Math.max(1.0, floatHeap));
  }

  /** Forces a GC, runs the supplier, measures heap, returns the delta. */
  private static long measure(java.util.function.Supplier<Object> supplier) {
    System.gc();
    sleepShort();
    System.gc();
    sleepShort();
    long before = used();
    Object held = supplier.get();
    System.gc();
    sleepShort();
    long after = used();
    BenchOut.infof("  built %s", held.getClass().getSimpleName());
    BenchOut.infof(
        "  heap before: %s   heap after: %s   delta: %s",
        humanize(before), humanize(after), humanize(after - before));
    return after - before;
  }

  private static long used() {
    Runtime r = Runtime.getRuntime();
    return r.totalMemory() - r.freeMemory();
  }

  private static String humanize(long bytes) {
    if (bytes < 0) return "-" + humanize(-bytes);
    if (bytes < 1024) return bytes + " B";
    if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
    if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
    return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
  }

  private static void sleepShort() {
    try {
      Thread.sleep(50);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
