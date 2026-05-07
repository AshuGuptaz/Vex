package com.vex.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * HNSW index that stores vectors as int8 via {@link ScalarQuantizer}. The graph algorithm matches
 * {@link HnswIndex} exactly; only the per-vector storage and the distance kernel differ:
 *
 * <ul>
 *   <li>Vectors are stored as {@code byte[][]}, 4× smaller than the {@code float[][]} HnswIndex
 *       uses.
 *   <li>Distance computations call {@link ScalarQuantizer#squaredL2(byte[], byte[])} directly on
 *       the int8 representation; the quantizer multiplies integer differences by per-dimension
 *       scale² so the ordering matches the float-domain L2.
 *   <li>Query vectors are encoded once at the API boundary; subsequent distance calls during the
 *       search work entirely on bytes.
 * </ul>
 *
 * <p>Currently only L2 is supported as the underlying metric. Cosine and dot product would need
 * separate int8 kernels; out of scope for v1.
 */
public final class QuantizedHnswIndex implements AutoCloseable {

  private static final int INITIAL_CAPACITY = 1024;
  private static final int MAX_LEVEL = 32;

  private final HnswConfig config;
  private final ScalarQuantizer quantizer;
  private final int mMax;
  private final int mMax0;
  private final double mL;
  private final Random rng;

  private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
  private final Map<Long, Integer> idToIndex = new HashMap<>();

  private byte[][] qVectors;
  private int[] levels;
  private long[] ids;
  private boolean[] deleted;
  private int[][][] connections;

  private int size = 0;
  private int liveCount = 0;
  private int entryPoint = -1;
  private int topLayer = -1;

  /**
   * Creates an empty quantized index. The quantizer must already be trained — typically by calling
   * {@link ScalarQuantizer#train(float[][])} on a representative sample of the data.
   */
  public QuantizedHnswIndex(HnswConfig config, ScalarQuantizer quantizer) {
    if (quantizer.dimension() != config.dimension()) {
      throw new IllegalArgumentException(
          "quantizer dim " + quantizer.dimension() + " != config dim " + config.dimension());
    }
    if (!(config.metric() instanceof L2Distance)) {
      throw new IllegalArgumentException(
          "QuantizedHnswIndex currently supports L2 only; got " + config.metric().name());
    }
    this.config = config;
    this.quantizer = quantizer;
    this.mMax = config.M();
    this.mMax0 = 2 * config.M();
    this.mL = 1.0 / Math.log(config.M());
    this.rng = new Random(config.randomSeed());
    this.qVectors = new byte[INITIAL_CAPACITY][];
    this.levels = new int[INITIAL_CAPACITY];
    this.ids = new long[INITIAL_CAPACITY];
    this.deleted = new boolean[INITIAL_CAPACITY];
    this.connections = new int[INITIAL_CAPACITY][][];
  }

  public HnswConfig config() {
    return config;
  }

  public ScalarQuantizer quantizer() {
    return quantizer;
  }

  public int size() {
    lock.readLock().lock();
    try {
      return liveCount;
    } finally {
      lock.readLock().unlock();
    }
  }

  public boolean contains(long id) {
    lock.readLock().lock();
    try {
      Integer idx = idToIndex.get(id);
      return idx != null && !deleted[idx];
    } finally {
      lock.readLock().unlock();
    }
  }

  /** Inserts a vector. Alias for {@link #insert(long, float[])}. */
  public void add(long id, float[] vector) {
    insert(id, vector);
  }

  /**
   * Inserts a float vector by encoding it through the quantizer. Throws if the id is already
   * present and live. This is the canonical name from the paper's Algorithm 1.
   */
  public void insert(long id, float[] vector) {
    if (vector.length != config.dimension()) {
      throw new IllegalArgumentException(
          "Expected dim " + config.dimension() + " but got " + vector.length);
    }
    byte[] encoded = quantizer.encode(vector);
    lock.writeLock().lock();
    try {
      Integer existing = idToIndex.get(id);
      if (existing != null && !deleted[existing]) {
        throw new IllegalArgumentException("Duplicate id: " + id);
      }
      int level = randomLevel();
      int newIdx = appendSlot();
      qVectors[newIdx] = encoded;
      levels[newIdx] = level;
      ids[newIdx] = id;
      deleted[newIdx] = false;
      connections[newIdx] = new int[level + 1][];
      for (int lc = 0; lc <= level; lc++) {
        connections[newIdx][lc] = new int[0];
      }
      idToIndex.put(id, newIdx);
      size++;
      liveCount++;

      if (entryPoint == -1) {
        entryPoint = newIdx;
        topLayer = level;
        return;
      }

      int currObj = entryPoint;
      int currLayer = topLayer;

      for (int lc = currLayer; lc > level; lc--) {
        currObj = greedyClosest(encoded, currObj, lc);
      }

      List<Candidate> ep = new ArrayList<>();
      ep.add(new Candidate(currObj, distance(encoded, qVectors[currObj])));

      for (int lc = Math.min(currLayer, level); lc >= 0; lc--) {
        List<Candidate> w = searchLayer(encoded, ep, config.efConstruction(), lc);
        int mAtLayer = (lc == 0) ? mMax0 : mMax;
        List<Integer> neighbors = selectNeighbors(encoded, w, mAtLayer, lc);
        connections[newIdx][lc] = toIntArray(neighbors);

        for (int neighborIdx : neighbors) {
          int[] nConn = connections[neighborIdx][lc];
          int mPrune = (lc == 0) ? mMax0 : mMax;
          if (nConn.length < mPrune) {
            int[] grown = Arrays.copyOf(nConn, nConn.length + 1);
            grown[nConn.length] = newIdx;
            connections[neighborIdx][lc] = grown;
          } else {
            List<Candidate> all = new ArrayList<>(nConn.length + 1);
            all.add(new Candidate(newIdx, distance(qVectors[neighborIdx], qVectors[newIdx])));
            for (int c : nConn) {
              all.add(new Candidate(c, distance(qVectors[neighborIdx], qVectors[c])));
            }
            connections[neighborIdx][lc] =
                toIntArray(selectNeighbors(qVectors[neighborIdx], all, mPrune, lc));
          }
        }
        ep = w;
      }

      if (level > topLayer) {
        topLayer = level;
        entryPoint = newIdx;
      }
    } finally {
      lock.writeLock().unlock();
    }
  }

  public boolean delete(long id) {
    lock.writeLock().lock();
    try {
      Integer idx = idToIndex.get(id);
      if (idx == null || deleted[idx]) {
        return false;
      }
      deleted[idx] = true;
      liveCount--;
      return true;
    } finally {
      lock.writeLock().unlock();
    }
  }

  /** Returns the decoded float vector, or null if absent or tombstoned. Lossy. */
  public float[] getVector(long id) {
    lock.readLock().lock();
    try {
      Integer idx = idToIndex.get(id);
      if (idx == null || deleted[idx]) {
        return null;
      }
      return quantizer.decode(qVectors[idx]);
    } finally {
      lock.readLock().unlock();
    }
  }

  public List<SearchResult> query(float[] vector, int k) {
    return query(vector, k, config.efSearch());
  }

  public List<SearchResult> query(float[] vector, int k, int efSearch) {
    if (vector.length != config.dimension()) {
      throw new IllegalArgumentException(
          "Expected dim " + config.dimension() + " but got " + vector.length);
    }
    if (k < 1) {
      throw new IllegalArgumentException("k must be >= 1");
    }
    int ef = Math.max(efSearch, k);
    byte[] encoded = quantizer.encode(vector);

    lock.readLock().lock();
    try {
      if (entryPoint == -1 || liveCount == 0) {
        return List.of();
      }
      int currObj = entryPoint;
      for (int lc = topLayer; lc > 0; lc--) {
        currObj = greedyClosest(encoded, currObj, lc);
      }
      List<Candidate> ep = new ArrayList<>();
      ep.add(new Candidate(currObj, distance(encoded, qVectors[currObj])));
      List<Candidate> w = searchLayer(encoded, ep, ef, 0);
      w.sort(Comparator.comparingDouble(c -> c.distance));
      List<SearchResult> out = new ArrayList<>(Math.min(w.size(), k));
      for (Candidate c : w) {
        if (deleted[c.index]) {
          continue;
        }
        out.add(new SearchResult(ids[c.index], c.distance));
        if (out.size() == k) {
          break;
        }
      }
      return out;
    } finally {
      lock.readLock().unlock();
    }
  }

  /** Approximate per-vector heap footprint of the encoded byte storage, in bytes. */
  public long bytesPerVector() {
    return config.dimension();
  }

  @Override
  public void close() {
    // in-memory only
  }

  /** Returns a deep copy of the index's internal state for serialization. */
  public Snapshot snapshot() {
    lock.readLock().lock();
    try {
      long[] idsCopy = Arrays.copyOf(ids, size);
      int[] levelsCopy = Arrays.copyOf(levels, size);
      boolean[] deletedCopy = Arrays.copyOf(deleted, size);
      byte[][] vectorsCopy = new byte[size][];
      int[][][] connectionsCopy = new int[size][][];
      for (int i = 0; i < size; i++) {
        vectorsCopy[i] = qVectors[i].clone();
        int[][] perLayer = connections[i];
        int[][] perLayerCopy = new int[perLayer.length][];
        for (int lc = 0; lc < perLayer.length; lc++) {
          perLayerCopy[lc] = perLayer[lc].clone();
        }
        connectionsCopy[i] = perLayerCopy;
      }
      return new Snapshot(
          size,
          liveCount,
          entryPoint,
          topLayer,
          idsCopy,
          levelsCopy,
          deletedCopy,
          vectorsCopy,
          connectionsCopy);
    } finally {
      lock.readLock().unlock();
    }
  }

  /** Rebuilds a quantized index from a previously-taken snapshot. */
  public static QuantizedHnswIndex restore(HnswConfig config, ScalarQuantizer q, Snapshot s) {
    QuantizedHnswIndex idx = new QuantizedHnswIndex(config, q);
    idx.lock.writeLock().lock();
    try {
      int cap = Math.max(INITIAL_CAPACITY, Integer.highestOneBit(Math.max(1, s.size)) << 1);
      if (cap < s.size) {
        cap = s.size;
      }
      idx.qVectors = new byte[cap][];
      idx.levels = new int[cap];
      idx.ids = new long[cap];
      idx.deleted = new boolean[cap];
      idx.connections = new int[cap][][];
      System.arraycopy(s.qVectors, 0, idx.qVectors, 0, s.size);
      System.arraycopy(s.levels, 0, idx.levels, 0, s.size);
      System.arraycopy(s.ids, 0, idx.ids, 0, s.size);
      System.arraycopy(s.deleted, 0, idx.deleted, 0, s.size);
      System.arraycopy(s.connections, 0, idx.connections, 0, s.size);
      idx.size = s.size;
      idx.liveCount = s.liveCount;
      idx.entryPoint = s.entryPoint;
      idx.topLayer = s.topLayer;
      for (int i = 0; i < s.size; i++) {
        idx.idToIndex.put(s.ids[i], i);
      }
    } finally {
      idx.lock.writeLock().unlock();
    }
    return idx;
  }

  /** Serializable snapshot of a quantized index. */
  public record Snapshot(
      int size,
      int liveCount,
      int entryPoint,
      int topLayer,
      long[] ids,
      int[] levels,
      boolean[] deleted,
      byte[][] qVectors,
      int[][][] connections) {}

  // ---- internals ----

  private record Candidate(int index, float distance) {}

  private float distance(byte[] a, byte[] b) {
    return quantizer.squaredL2(a, b);
  }

  private int greedyClosest(byte[] q, int start, int layer) {
    int curr = start;
    float currDist = distance(q, qVectors[curr]);
    boolean changed = true;
    while (changed) {
      changed = false;
      int[] conn = connections[curr][layer];
      for (int n : conn) {
        float d = distance(q, qVectors[n]);
        if (d < currDist) {
          currDist = d;
          curr = n;
          changed = true;
        }
      }
    }
    return curr;
  }

  private List<Candidate> searchLayer(byte[] q, Collection<Candidate> ep, int ef, int layer) {
    BitSet visited = new BitSet(size);
    PriorityQueue<Candidate> candidates =
        new PriorityQueue<>(Comparator.comparingDouble(c -> c.distance));
    PriorityQueue<Candidate> dynamic =
        new PriorityQueue<>((a, b) -> Float.compare(b.distance, a.distance));
    for (Candidate c : ep) {
      visited.set(c.index);
      candidates.add(c);
      dynamic.add(c);
    }
    while (!candidates.isEmpty()) {
      Candidate c = candidates.poll();
      Candidate f = dynamic.peek();
      if (f != null && c.distance > f.distance) {
        break;
      }
      int[] conn = connections[c.index][layer];
      for (int n : conn) {
        if (visited.get(n)) {
          continue;
        }
        visited.set(n);
        float d = distance(q, qVectors[n]);
        Candidate fNow = dynamic.peek();
        if (dynamic.size() < ef || (fNow != null && d < fNow.distance)) {
          Candidate cand = new Candidate(n, d);
          candidates.add(cand);
          dynamic.add(cand);
          if (dynamic.size() > ef) {
            dynamic.poll();
          }
        }
      }
    }
    return new ArrayList<>(dynamic);
  }

  private List<Integer> selectNeighbors(
      byte[] q, Collection<Candidate> candidates, int M, int layer) {
    if (!config.useHeuristicNeighborSelection()) {
      return selectSimple(candidates, M);
    }
    PriorityQueue<Candidate> w = new PriorityQueue<>(Comparator.comparingDouble(c -> c.distance));
    Set<Integer> seen = new HashSet<>();
    for (Candidate c : candidates) {
      if (seen.add(c.index)) {
        w.add(c);
      }
    }
    List<Integer> result = new ArrayList<>(M);
    while (!w.isEmpty() && result.size() < M) {
      Candidate e = w.poll();
      boolean ok = true;
      for (int r : result) {
        if (distance(qVectors[e.index], qVectors[r]) < e.distance) {
          ok = false;
          break;
        }
      }
      if (ok) {
        result.add(e.index);
      }
    }
    return result;
  }

  private List<Integer> selectSimple(Collection<Candidate> candidates, int M) {
    PriorityQueue<Candidate> w = new PriorityQueue<>(Comparator.comparingDouble(c -> c.distance));
    Set<Integer> seen = new HashSet<>();
    for (Candidate c : candidates) {
      if (seen.add(c.index)) {
        w.add(c);
      }
    }
    List<Integer> out = new ArrayList<>(M);
    while (!w.isEmpty() && out.size() < M) {
      out.add(w.poll().index);
    }
    return out;
  }

  private int randomLevel() {
    double u = rng.nextDouble();
    if (u <= 0.0) {
      u = Double.MIN_VALUE;
    }
    int lvl = (int) Math.floor(-Math.log(u) * mL);
    return Math.min(lvl, MAX_LEVEL);
  }

  private int appendSlot() {
    int idx = size;
    if (idx >= qVectors.length) {
      int newCap = qVectors.length * 2;
      qVectors = Arrays.copyOf(qVectors, newCap);
      levels = Arrays.copyOf(levels, newCap);
      ids = Arrays.copyOf(ids, newCap);
      deleted = Arrays.copyOf(deleted, newCap);
      connections = Arrays.copyOf(connections, newCap);
    }
    return idx;
  }

  private int[] toIntArray(List<Integer> list) {
    int[] out = new int[list.size()];
    for (int i = 0; i < list.size(); i++) {
      out[i] = list.get(i);
    }
    return out;
  }
}
