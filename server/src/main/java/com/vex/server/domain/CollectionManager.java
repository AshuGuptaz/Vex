package com.vex.server.domain;

import com.vex.core.DistanceMetric;
import com.vex.core.HnswConfig;
import com.vex.storage.IndexStorage;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Owns the lifecycle of all collections in a single data directory. */
@Component
public class CollectionManager {

  private static final Logger LOG = LoggerFactory.getLogger(CollectionManager.class);

  private final Path dataDir;
  private final boolean fsyncOnAppend;
  private final Map<String, Collection> collections = new ConcurrentHashMap<>();

  @Autowired
  public CollectionManager(VexProperties props) throws IOException {
    this(Path.of(props.getDataDir()), "per-write".equalsIgnoreCase(props.getWalFsync()));
  }

  public CollectionManager(Path dataDir, boolean fsyncOnAppend) throws IOException {
    this.dataDir = dataDir;
    this.fsyncOnAppend = fsyncOnAppend;
    Files.createDirectories(dataDir);
    loadExisting();
  }

  private void loadExisting() throws IOException {
    if (!Files.exists(dataDir)) {
      return;
    }
    try (var stream = Files.list(dataDir)) {
      var dirs = stream.filter(Files::isDirectory).sorted().toList();
      for (Path dir : dirs) {
        String name = dir.getFileName().toString();
        boolean quantized = Files.exists(dir.resolve(".quantized"));
        if (quantized) {
          // Quantized collections are not persisted in v1 (ADR 005). Skip loading.
          LOG.warn("Skipping load of quantized collection '{}' (no on-disk format yet)", name);
          continue;
        }
        Path indexFile = dir.resolve(IndexStorage.INDEX_FILE);
        if (!Files.exists(indexFile)) {
          continue;
        }
        try {
          IndexStorage storage = IndexStorage.open(dir, null, fsyncOnAppend);
          PayloadStore payloads = PayloadStore.open(dir.resolve("payloads.db"), fsyncOnAppend);
          collections.put(name, new Collection(name, storage, payloads));
          LOG.info("Loaded collection '{}' ({} vectors)", name, storage.size());
        } catch (IOException e) {
          LOG.warn("Failed to load collection at {}: {}", dir, e.getMessage());
        }
      }
    }
  }

  public synchronized Collection create(
      String name, int dim, String metricName, int M, int efConstruction, boolean quantized)
      throws IOException {
    if (collections.containsKey(name)) {
      throw new IllegalArgumentException("Collection already exists: " + name);
    }
    if (!name.matches("[a-zA-Z0-9_\\-]+")) {
      throw new IllegalArgumentException("Invalid collection name: must match [a-zA-Z0-9_-]+");
    }
    Path dir = dataDir.resolve(name);
    Files.createDirectories(dir);
    DistanceMetric metric = DistanceMetric.named(metricName);
    HnswConfig cfg = new HnswConfig(M, efConstruction, 50, dim, metric, 42L);
    PayloadStore payloads = PayloadStore.open(dir.resolve("payloads.db"), fsyncOnAppend);
    Collection c;
    if (quantized) {
      Files.createFile(dir.resolve(".quantized"));
      c = new Collection(name, cfg, payloads, true);
      LOG.info(
          "Created quantized collection '{}' (dim={}, metric={}, M={}, efC={}, "
              + "training threshold={})",
          name,
          dim,
          metricName,
          M,
          efConstruction,
          Collection.QUANTIZER_TRAINING_THRESHOLD);
    } else {
      IndexStorage storage = IndexStorage.open(dir, cfg, fsyncOnAppend);
      storage.flush();
      c = new Collection(name, storage, payloads);
      LOG.info(
          "Created float collection '{}' (dim={}, metric={}, M={}, efC={})",
          name,
          dim,
          metricName,
          M,
          efConstruction);
    }
    collections.put(name, c);
    return c;
  }

  /** Returns the named collection, or null if it doesn't exist. */
  public Collection get(String name) {
    return collections.get(name);
  }

  /**
   * Returns the named collection or throws {@link NoSuchCollectionException}. Intended for use in
   * controllers where the manager handles the 404-translation in {@code GlobalExceptionHandler}.
   */
  public Collection require(String name) {
    Collection c = collections.get(name);
    if (c == null) {
      throw new NoSuchCollectionException(name);
    }
    return c;
  }

  /** Returns the names of all live collections in insertion order is NOT guaranteed. */
  public List<String> names() {
    return new ArrayList<>(collections.keySet());
  }

  /**
   * Closes the named collection and recursively removes its data directory. Returns true if a
   * collection was dropped; false if it didn't exist.
   */
  public synchronized boolean drop(String name) throws IOException {
    Collection c = collections.remove(name);
    if (c == null) {
      return false;
    }
    c.close();
    Path dir = dataDir.resolve(name);
    if (Files.exists(dir)) {
      try (var stream = Files.walk(dir)) {
        var paths = stream.sorted(java.util.Comparator.reverseOrder()).toList();
        for (Path p : paths) {
          Files.deleteIfExists(p);
        }
      }
    }
    return true;
  }

  @PreDestroy
  public synchronized void closeAll() {
    for (Map.Entry<String, Collection> e : collections.entrySet()) {
      try {
        e.getValue().close();
      } catch (IOException ex) {
        LOG.warn("Error closing collection '{}': {}", e.getKey(), ex.getMessage());
      }
    }
    collections.clear();
  }

  /** Raised when a request references an unknown collection. */
  public static final class NoSuchCollectionException extends RuntimeException {
    public NoSuchCollectionException(String name) {
      super("No such collection: " + name);
    }
  }
}
