package com.vex.server.domain;

import com.vex.core.HnswConfig;
import com.vex.core.HnswIndex;
import com.vex.core.QuantizedHnswIndex;
import com.vex.core.ScalarQuantizer;
import com.vex.core.SearchResult;
import com.vex.server.filter.FilterCompiler;
import com.vex.server.filter.FilterPredicate;
import com.vex.storage.IndexStorage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A single named collection: vector index + payload store.
 *
 * <p>Two modes:
 *
 * <ul>
 *   <li><b>Float (default)</b> — vectors are stored in an {@link IndexStorage}-backed {@link
 *       HnswIndex}. Persists fully across restarts.
 *   <li><b>Quantized</b> — when created with {@code quantization=scalar}. The collection buffers
 *       the first {@link #QUANTIZER_TRAINING_THRESHOLD} inserts in a small float buffer, then
 *       trains a {@link ScalarQuantizer} on that sample and rebuilds the buffered vectors into a
 *       {@link QuantizedHnswIndex} that stores int8 internally. Queries before training fall back
 *       to brute-force scan over the buffer; after training they go through the quantized HNSW.
 *       Quantized collections are in-memory only across restarts (see ADR 005).
 * </ul>
 */
public final class Collection implements AutoCloseable {

  /** Number of float inserts buffered before the quantizer is trained. */
  public static final int QUANTIZER_TRAINING_THRESHOLD = 10_000;

  private final String name;
  private final HnswConfig config;
  private final boolean quantized;
  private final PayloadStore payloads;

  // Float-mode state.
  private final IndexStorage floatStorage;

  // Quantized-mode state.
  private final LinkedHashMap<Long, float[]> trainingBuffer = new LinkedHashMap<>();
  private QuantizedHnswIndex qIndex;
  private ScalarQuantizer quantizer;

  /** Float-mode constructor. */
  public Collection(String name, IndexStorage storage, PayloadStore payloads) {
    this.name = name;
    this.config = storage.config();
    this.quantized = false;
    this.payloads = payloads;
    this.floatStorage = storage;
  }

  /** Quantized-mode constructor. {@code config} is captured for later QuantizedHnswIndex build. */
  public Collection(String name, HnswConfig config, PayloadStore payloads, boolean quantized) {
    this.name = name;
    this.config = config;
    this.quantized = quantized;
    this.payloads = payloads;
    this.floatStorage = null;
  }

  /** Returns the collection's user-facing name. */
  public String name() {
    return name;
  }

  /** Returns the index configuration. */
  public HnswConfig config() {
    return config;
  }

  /** Returns the number of live (non-tombstoned) vectors. */
  public synchronized int size() {
    if (!quantized) {
      return floatStorage.size();
    }
    return qIndex != null ? qIndex.size() : trainingBuffer.size();
  }

  /** Returns true if this collection was created with scalar quantization enabled. */
  public boolean quantized() {
    return quantized;
  }

  /** Returns true if the quantized index has been trained and built. */
  public synchronized boolean isQuantizerTrained() {
    return quantized && qIndex != null;
  }

  /**
   * Inserts or replaces a vector by id. Replaces the payload entirely; pass an empty or null map to
   * clear it.
   */
  public synchronized void upsert(long id, float[] vector, Map<String, Object> payload)
      throws IOException {
    if (!quantized) {
      if (floatStorage.contains(id)) {
        floatStorage.delete(id);
      }
      floatStorage.add(id, vector);
    } else {
      upsertQuantized(id, vector);
    }
    if (payload == null || payload.isEmpty()) {
      payloads.remove(id);
    } else {
      payloads.put(id, payload);
    }
  }

  private void upsertQuantized(long id, float[] vector) {
    if (vector.length != config.dimension()) {
      throw new IllegalArgumentException(
          "Expected dim " + config.dimension() + " but got " + vector.length);
    }
    if (qIndex != null) {
      if (qIndex.contains(id)) {
        qIndex.delete(id);
      }
      qIndex.insert(id, vector);
      return;
    }
    trainingBuffer.remove(id);
    trainingBuffer.put(id, vector.clone());
    if (trainingBuffer.size() >= QUANTIZER_TRAINING_THRESHOLD) {
      trainAndPromote();
    }
  }

  private void trainAndPromote() {
    float[][] sample = trainingBuffer.values().toArray(new float[0][]);
    quantizer = ScalarQuantizer.train(sample);
    qIndex = new QuantizedHnswIndex(config, quantizer);
    for (Map.Entry<Long, float[]> e : trainingBuffer.entrySet()) {
      qIndex.insert(e.getKey(), e.getValue());
    }
    trainingBuffer.clear();
  }

  /** Soft-deletes a vector and removes its payload. Returns true iff a live record was removed. */
  public synchronized boolean delete(long id) throws IOException {
    boolean removed;
    if (!quantized) {
      removed = floatStorage.delete(id);
    } else if (qIndex != null) {
      removed = qIndex.delete(id);
    } else {
      removed = trainingBuffer.remove(id) != null;
    }
    payloads.remove(id);
    return removed;
  }

  /** Returns true if the id is present and not tombstoned. */
  public synchronized boolean contains(long id) {
    if (!quantized) {
      return floatStorage.contains(id);
    }
    if (qIndex != null) {
      return qIndex.contains(id);
    }
    return trainingBuffer.containsKey(id);
  }

  /**
   * Returns the stored vector, or null if absent or tombstoned. Lossy for quantized collections.
   */
  public synchronized float[] getVector(long id) {
    if (!quantized) {
      return floatStorage.getVector(id);
    }
    if (qIndex != null) {
      return qIndex.getVector(id);
    }
    float[] v = trainingBuffer.get(id);
    return v == null ? null : v.clone();
  }

  /** Returns the payload map, or null if no payload was stored for this id. */
  public Map<String, Object> getPayload(long id) {
    return payloads.get(id);
  }

  /**
   * Top-k filtered query.
   *
   * @param vector query vector
   * @param k number of results to return
   * @param efSearchOverride if non-null, overrides the index's default efSearch
   * @param filterExpr optional filter expression; applied post-retrieval. When non-blank, the index
   *     fetches max(k*4, ef) candidates so the filtered result still reaches k matches.
   */
  public synchronized List<QueryHit> query(
      float[] vector, int k, Integer efSearchOverride, String filterExpr) {
    FilterPredicate pred = FilterCompiler.compile(filterExpr);
    int ef = efSearchOverride != null ? efSearchOverride : config.efSearch();
    int fetch =
        (filterExpr == null || filterExpr.isBlank()) ? Math.max(k, ef) : Math.max(k * 4, ef);

    List<SearchResult> raw;
    if (!quantized) {
      raw = floatStorage.query(vector, fetch, ef);
    } else if (qIndex != null) {
      raw = qIndex.query(vector, fetch, ef);
    } else {
      raw = bruteForceBuffer(vector, fetch);
    }

    List<QueryHit> out = new ArrayList<>(Math.min(raw.size(), k));
    for (SearchResult r : raw) {
      Map<String, Object> p = payloads.get(r.id());
      Map<String, Object> safePayload = p != null ? p : Map.of();
      if (!pred.test(safePayload)) {
        continue;
      }
      out.add(new QueryHit(r.id(), r.distance(), p == null ? new HashMap<>() : p));
      if (out.size() == k) {
        break;
      }
    }
    return out;
  }

  /** Brute-force top-k over the training buffer (used while the quantizer is still training). */
  private List<SearchResult> bruteForceBuffer(float[] q, int k) {
    if (q.length != config.dimension()) {
      throw new IllegalArgumentException(
          "Expected dim " + config.dimension() + " but got " + q.length);
    }
    List<SearchResult> results = new ArrayList<>(trainingBuffer.size());
    for (Map.Entry<Long, float[]> e : trainingBuffer.entrySet()) {
      results.add(new SearchResult(e.getKey(), config.metric().distance(q, e.getValue())));
    }
    results.sort(java.util.Comparator.comparingDouble(SearchResult::distance));
    return results.subList(0, Math.min(k, results.size()));
  }

  @Override
  public synchronized void close() throws IOException {
    if (!quantized) {
      floatStorage.close();
    } else if (qIndex != null) {
      qIndex.close();
    }
    payloads.close();
  }

  /** Result of a query: id, distance, and payload (possibly empty). */
  public record QueryHit(long id, float distance, Map<String, Object> payload) {}
}
