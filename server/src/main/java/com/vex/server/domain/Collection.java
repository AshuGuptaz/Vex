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

  public String name() {
    return name;
  }

  public HnswConfig config() {
    return storage.config();
  }

  public int size() {
    return storage.size();
  }

  public boolean quantized() {
    return quantized;
  }

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

  public synchronized boolean delete(long id) throws IOException {
    boolean removed = storage.delete(id);
    payloads.remove(id);
    return removed;
  }

  public boolean contains(long id) {
    return storage.contains(id);
  }

  public float[] getVector(long id) {
    return storage.getVector(id);
  }

  public Map<String, Object> getPayload(long id) {
    return payloads.get(id);
  }

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
