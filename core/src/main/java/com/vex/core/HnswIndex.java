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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hierarchical Navigable Small World index, implemented from Malkov & Yashunin (2016).
 *
 * <p>Inserts are single-writer; queries are concurrent-safe via a {@link ReentrantReadWriteLock}.
 * Soft deletes mark a node as tombstoned without rewiring the graph; tombstoned nodes still serve
 * as connectivity hops but never appear in query results.
 *
 * <p>Neighbor selection uses Algorithm 4 (the diversity heuristic) with default {@code
 * extendCandidates=false} and {@code keepPrunedConnections=false}.
 */
public final class HnswIndex implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(HnswIndex.class);
  private static final int INITIAL_CAPACITY = 1024;
  private static final int MAX_LEVEL = 32;

  private final HnswConfig config;
  private final int mMax;
  private final int mMax0;
  private final double mL;
  private final Random rng;

  private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
  private final Map<Long, Integer> idToIndex = new HashMap<>();

  private float[][] vectors;
  private int[] levels;
  private long[] ids;
  private boolean[] deleted;
  private int[][][] connections;

  private int size = 0;
  private int liveCount = 0;
  private int entryPoint = -1;
  private int topLayer = -1;

  /** Creates an empty in-memory index. */
  public HnswIndex(HnswConfig config) {
    this.config = config;
    this.mMax = config.M();
    this.mMax0 = 2 * config.M();
    this.mL = 1.0 / Math.log(config.M());
    this.rng = new Random(config.randomSeed());
    this.vectors = new float[INITIAL_CAPACITY][];
    this.levels = new int[INITIAL_CAPACITY];
    this.ids = new long[INITIAL_CAPACITY];
    this.deleted = new boolean[INITIAL_CAPACITY];
    this.connections = new int[INITIAL_CAPACITY][][];
  }

  public HnswConfig config() {
    return config;
  }

  /** Returns the number of live (non-deleted) vectors in the index. */
  public int size() {
    lock.readLock().lock();
    try {
      return liveCount;
    } finally {
      lock.readLock().unlock();
    }
  }

  /** Returns true if the id is present and not soft-deleted. */
  public boolean contains(long id) {
    lock.readLock().lock();
    try {
      Integer idx = idToIndex.get(id);
      return idx != null && !deleted[idx];
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * Inserts a vector with the given id. Throws if the id is already present and live.
   *
   * @throws IllegalArgumentException if the dimension mismatches or the id already exists.
   */
  public void add(long id, float[] vector) {
    if (vector.length != config.dimension()) {
      throw new IllegalArgumentException(
          "Expected dim " + config.dimension() + " but got " + vector.length);
    }
    lock.writeLock().lock();
    try {
      Integer existing = idToIndex.get(id);
      if (existing != null && !deleted[existing]) {
        throw new IllegalArgumentException("Duplicate id: " + id);
      }
      int level = randomLevel();
      int newIdx = appendSlot();
      vectors[newIdx] = vector.clone();
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

      // Greedy descent through layers above the new node's max layer.
      for (int lc = currLayer; lc > level; lc--) {
        currObj = greedyClosest(vector, currObj, lc);
      }

      List<Candidate> ep = new ArrayList<>();
      ep.add(new Candidate(currObj, distance(vector, vectors[currObj])));

      // Insert connections at each layer the new node lives in.
      for (int lc = Math.min(currLayer, level); lc >= 0; lc--) {
        List<Candidate> w = searchLayer(vector, ep, config.efConstruction(), lc);
        int mAtLayer = (lc == 0) ? mMax0 : mMax;
        List<Integer> neighbors = selectNeighborsHeuristic(vector, w, mAtLayer, lc, false, false);
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
            all.add(new Candidate(newIdx, distance(vectors[neighborIdx], vectors[newIdx])));
            for (int c : nConn) {
              all.add(new Candidate(c, distance(vectors[neighborIdx], vectors[c])));
            }
            List<Integer> pruned =
                selectNeighborsHeuristic(vectors[neighborIdx], all, mPrune, lc, false, false);
            connections[neighborIdx][lc] = toIntArray(pruned);
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

  /** Soft-deletes the given id. Returns true if a live node was tombstoned. */
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

  /** Returns the stored vector for the given id, or null if absent or tombstoned. */
  public float[] getVector(long id) {
    lock.readLock().lock();
    try {
      Integer idx = idToIndex.get(id);
      if (idx == null || deleted[idx]) {
        return null;
      }
      return vectors[idx].clone();
    } finally {
      lock.readLock().unlock();
    }
  }

  /** Top-k query using the configured efSearch. */
  public List<SearchResult> query(float[] vector, int k) {
    return query(vector, k, config.efSearch());
  }

  /** Top-k query with explicit efSearch override. */
  public List<SearchResult> query(float[] vector, int k, int efSearch) {
    if (vector.length != config.dimension()) {
      throw new IllegalArgumentException(
          "Expected dim " + config.dimension() + " but got " + vector.length);
    }
    if (k < 1) {
      throw new IllegalArgumentException("k must be >= 1");
    }
    int ef = Math.max(efSearch, k);

    lock.readLock().lock();
    try {
      if (entryPoint == -1 || liveCount == 0) {
        return List.of();
      }

      int currObj = entryPoint;
      for (int lc = topLayer; lc > 0; lc--) {
        currObj = greedyClosest(vector, currObj, lc);
      }

      List<Candidate> ep = new ArrayList<>();
      ep.add(new Candidate(currObj, distance(vector, vectors[currObj])));
      List<Candidate> w = searchLayer(vector, ep, ef, 0);

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

  @Override
  public void close() {
    // In-memory only; storage layer wraps and provides flush/close semantics.
  }

  /** Returns a deep copy of the index's internal state for serialization. */
  public Snapshot snapshot() {
    lock.readLock().lock();
    try {
      long[] idsCopy = Arrays.copyOf(ids, size);
      int[] levelsCopy = Arrays.copyOf(levels, size);
      boolean[] deletedCopy = Arrays.copyOf(deleted, size);
      float[][] vectorsCopy = new float[size][];
      int[][][] connectionsCopy = new int[size][][];
      for (int i = 0; i < size; i++) {
        vectorsCopy[i] = vectors[i].clone();
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

  /** Rebuilds an index from a previously-taken snapshot. The arrays in {@code s} are not copied. */
  public static HnswIndex restore(HnswConfig config, Snapshot s) {
    HnswIndex idx = new HnswIndex(config);
    idx.lock.writeLock().lock();
    try {
      int cap = Math.max(INITIAL_CAPACITY, Integer.highestOneBit(Math.max(1, s.size)) << 1);
      if (cap < s.size) {
        cap = s.size;
      }
      idx.vectors = new float[cap][];
      idx.levels = new int[cap];
      idx.ids = new long[cap];
      idx.deleted = new boolean[cap];
      idx.connections = new int[cap][][];
      System.arraycopy(s.vectors, 0, idx.vectors, 0, s.size);
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

  /** Serializable snapshot of the index. Internal index ids are NOT stable across restores. */
  public record Snapshot(
      int size,
      int liveCount,
      int entryPoint,
      int topLayer,
      long[] ids,
      int[] levels,
      boolean[] deleted,
      float[][] vectors,
      int[][][] connections) {}

  // ---- internals ----

  private record Candidate(int index, float distance) {}

  private int greedyClosest(float[] q, int start, int layer) {
    int curr = start;
    float currDist = distance(q, vectors[curr]);
    boolean changed = true;
    while (changed) {
      changed = false;
      int[] conn = connections[curr][layer];
      for (int n : conn) {
        float d = distance(q, vectors[n]);
        if (d < currDist) {
          currDist = d;
          curr = n;
          changed = true;
        }
      }
    }
    return curr;
  }

  private List<Candidate> searchLayer(float[] q, Collection<Candidate> ep, int ef, int layer) {
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
        float d = distance(q, vectors[n]);
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

  private List<Integer> selectNeighborsHeuristic(
      float[] q,
      Collection<Candidate> candidates,
      int M,
      int layer,
      boolean extendCandidates,
      boolean keepPrunedConnections) {
    PriorityQueue<Candidate> w = new PriorityQueue<>(Comparator.comparingDouble(c -> c.distance));
    Set<Integer> seen = new HashSet<>();
    for (Candidate c : candidates) {
      if (seen.add(c.index)) {
        w.add(c);
      }
    }

    if (extendCandidates) {
      List<Candidate> snapshot = new ArrayList<>(candidates);
      for (Candidate c : snapshot) {
        int[] conn = connections[c.index][layer];
        for (int n : conn) {
          if (seen.add(n)) {
            w.add(new Candidate(n, distance(q, vectors[n])));
          }
        }
      }
    }

    List<Integer> result = new ArrayList<>(M);
    PriorityQueue<Candidate> discarded =
        new PriorityQueue<>(Comparator.comparingDouble(c -> c.distance));

    while (!w.isEmpty() && result.size() < M) {
      Candidate e = w.poll();
      boolean closerToQuery = true;
      for (int r : result) {
        float dToR = distance(vectors[e.index], vectors[r]);
        if (dToR < e.distance) {
          closerToQuery = false;
          break;
        }
      }
      if (closerToQuery) {
        result.add(e.index);
      } else if (keepPrunedConnections) {
        discarded.add(e);
      }
    }

    if (keepPrunedConnections) {
      while (!discarded.isEmpty() && result.size() < M) {
        result.add(discarded.poll().index);
      }
    }
    return result;
  }

  private int randomLevel() {
    double u = rng.nextDouble();
    if (u <= 0.0) {
      u = Double.MIN_VALUE;
    }
    int lvl = (int) Math.floor(-Math.log(u) * mL);
    return Math.min(lvl, MAX_LEVEL);
  }

  private float distance(float[] a, float[] b) {
    return config.metric().distance(a, b);
  }

  private int appendSlot() {
    int idx = size;
    if (idx >= vectors.length) {
      int newCap = vectors.length * 2;
      vectors = Arrays.copyOf(vectors, newCap);
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
