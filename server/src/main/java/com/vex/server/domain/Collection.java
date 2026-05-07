package com.vex.server.domain;

import com.vex.core.HnswConfig;
import com.vex.core.SearchResult;
import com.vex.server.filter.FilterCompiler;
import com.vex.server.filter.FilterPredicate;
import com.vex.storage.IndexStorage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A single named collection: HNSW index + payload store, both backed by disk. */
public final class Collection implements AutoCloseable {

  private final String name;
  private final IndexStorage storage;
  private final PayloadStore payloads;
  private final boolean quantized;

  public Collection(String name, IndexStorage storage, PayloadStore payloads, boolean quantized) {
    this.name = name;
    this.storage = storage;
    this.payloads = payloads;
    this.quantized = quantized;
  }

  /** Returns the collection's user-facing name. */
  public String name() {
    return name;
  }

  /** Returns the index configuration (M, efConstruction, dimension, metric, etc.). */
  public HnswConfig config() {
    return storage.config();
  }

  /** Returns the number of live (non-tombstoned) vectors. */
  public int size() {
    return storage.size();
  }

  /** Returns true if this collection was created with scalar quantization enabled. */
  public boolean quantized() {
    return quantized;
  }

  /**
   * Inserts or replaces a vector by id. If the id is already present and live, it is soft-deleted
   * before the new vector is added (the new vector gets fresh internal id and graph wiring). The
   * payload is replaced entirely; pass an empty or null map to clear the existing payload.
   */
  public synchronized void upsert(long id, float[] vector, Map<String, Object> payload)
      throws IOException {
    if (storage.contains(id)) {
      storage.delete(id);
    }
    storage.add(id, vector);
    if (payload == null || payload.isEmpty()) {
      payloads.remove(id);
    } else {
      payloads.put(id, payload);
    }
  }

  /** Soft-deletes a vector and removes its payload. Returns true iff a live record was removed. */
  public synchronized boolean delete(long id) throws IOException {
    boolean removed = storage.delete(id);
    payloads.remove(id);
    return removed;
  }

  /** Returns true if the id is present and not tombstoned. */
  public boolean contains(long id) {
    return storage.contains(id);
  }

  /**
   * Returns the stored vector, or null if absent or tombstoned. Lossy for quantized collections.
   */
  public float[] getVector(long id) {
    return storage.getVector(id);
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
   * @param efSearchOverride if non-null, overrides the index's default efSearch for this query
   * @param filterExpr optional filter expression (see {@link FilterCompiler}); applied
   *     post-retrieval. When non-blank, the index fetches max(k*4, ef) candidates so the filtered
   *     result still reaches k matches.
   */
  public List<QueryHit> query(float[] vector, int k, Integer efSearchOverride, String filterExpr) {
    FilterPredicate pred = FilterCompiler.compile(filterExpr);
    int ef = efSearchOverride != null ? efSearchOverride : storage.config().efSearch();
    int fetch =
        (filterExpr == null || filterExpr.isBlank()) ? Math.max(k, ef) : Math.max(k * 4, ef);
    List<SearchResult> raw = storage.query(vector, fetch, ef);
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

  @Override
  public synchronized void close() throws IOException {
    storage.close();
    payloads.close();
  }

  /** Result of a query: id, distance, and payload (possibly empty). */
  public record QueryHit(long id, float distance, Map<String, Object> payload) {}
}
