package com.vex.storage;

import com.vex.core.HnswConfig;
import com.vex.core.HnswIndex;
import com.vex.core.SearchResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Disk-persistent wrapper around an in-memory {@link HnswIndex}. Mutations are first appended to a
 * write-ahead log, then applied to the index. {@link #flush()} checkpoints the index to a file and
 * truncates the WAL.
 */
public final class IndexStorage implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(IndexStorage.class);

  public static final String INDEX_FILE = "index.vex";
  public static final String WAL_FILE = "wal.log";

  private final Path dir;
  private final HnswIndex index;
  private final WriteAheadLog wal;
  private final boolean fsyncOnAppend;

  private IndexStorage(Path dir, HnswIndex index, WriteAheadLog wal, boolean fsyncOnAppend) {
    this.dir = dir;
    this.index = index;
    this.wal = wal;
    this.fsyncOnAppend = fsyncOnAppend;
  }

  /**
   * Opens an existing storage directory or creates a new one with {@code config}. If the directory
   * already contains an index file, {@code config} is ignored and the on-disk config is used.
   */
  public static IndexStorage open(Path dir, HnswConfig config) throws IOException {
    return open(dir, config, true);
  }

  public static IndexStorage open(Path dir, HnswConfig config, boolean fsyncOnAppend)
      throws IOException {
    Files.createDirectories(dir);
    Path indexPath = dir.resolve(INDEX_FILE);
    Path walPath = dir.resolve(WAL_FILE);

    HnswIndex index;
    if (Files.exists(indexPath)) {
      index = IndexFile.read(indexPath);
    } else {
      index = new HnswIndex(config);
    }
    int dim = index.config().dimension();

    List<WriteAheadLog.WalRecord> records = WriteAheadLog.replay(walPath, dim);
    int applied = 0;
    for (WriteAheadLog.WalRecord r : records) {
      if (r.op() == WriteAheadLog.OP_INSERT) {
        if (!index.contains(r.id())) {
          try {
            index.add(r.id(), r.vector());
            applied++;
          } catch (IllegalArgumentException ignored) {
            // Treat duplicate-id-during-replay as already applied.
          }
        }
      } else if (r.op() == WriteAheadLog.OP_DELETE) {
        if (index.delete(r.id())) {
          applied++;
        }
      }
    }
    if (!records.isEmpty()) {
      LOG.info("Replayed {} WAL records ({} applied) from {}", records.size(), applied, walPath);
    }

    WriteAheadLog wal = WriteAheadLog.openForWrite(walPath, dim, fsyncOnAppend);
    return new IndexStorage(dir, index, wal, fsyncOnAppend);
  }

  /** Returns the on-disk directory holding {@code index.vex} and {@code wal.log}. */
  public Path directory() {
    return dir;
  }

  /** Returns the wrapped in-memory index. Use cautiously — bypasses WAL durability. */
  public HnswIndex index() {
    return index;
  }

  /** Returns the index's configuration. */
  public HnswConfig config() {
    return index.config();
  }

  /** Returns the number of live (non-tombstoned) vectors. */
  public int size() {
    return index.size();
  }

  /** Returns true if the id is present and not tombstoned. */
  public boolean contains(long id) {
    return index.contains(id);
  }

  /** Returns the stored vector, or null if absent or tombstoned. */
  public float[] getVector(long id) {
    return index.getVector(id);
  }

  /** Inserts a vector. Throws if the id already exists and is live. */
  public synchronized void add(long id, float[] vector) throws IOException {
    wal.appendInsert(id, vector);
    index.add(id, vector);
  }

  /** Deletes a vector by id. Returns true if a live record was tombstoned. */
  public synchronized boolean delete(long id) throws IOException {
    if (!index.contains(id)) {
      return false;
    }
    wal.appendDelete(id);
    return index.delete(id);
  }

  /** Top-k query using the configured efSearch. */
  public List<SearchResult> query(float[] vector, int k) {
    return index.query(vector, k);
  }

  /** Top-k query with explicit efSearch override. */
  public List<SearchResult> query(float[] vector, int k, int efSearch) {
    return index.query(vector, k, efSearch);
  }

  /**
   * Atomically writes the current index state to disk and truncates the WAL. Uses a temp file +
   * rename so a crash mid-flush leaves the previous valid index intact.
   */
  public synchronized void flush() throws IOException {
    Path tmp = dir.resolve(INDEX_FILE + ".tmp");
    Path target = dir.resolve(INDEX_FILE);
    IndexFile.write(tmp, index);
    Files.move(
        tmp,
        target,
        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    wal.truncate();
  }

  @Override
  public synchronized void close() throws IOException {
    flush();
    wal.close();
  }
}
