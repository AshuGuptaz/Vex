package com.vex.storage;

import com.vex.core.DistanceMetric;
import com.vex.core.HnswConfig;
import com.vex.core.HnswIndex;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;

/**
 * Reads and writes the canonical Vex on-disk index format.
 *
 * <p>Layout (little-endian throughout):
 *
 * <pre>
 *   magic:        4 bytes "VEX1"
 *   version:      u32
 *   dimension:    u32
 *   M:            u32
 *   efConstruction: u32
 *   efSearch:     u32
 *   metric:       u8
 *   _pad:         u8[3]
 *   randomSeed:   i64
 *   count:        u64        (number of slots, including tombstoned)
 *   liveCount:    u64
 *   entryPointId: i64        (-1 if empty)
 *   topLayer:     i32
 *
 *   per-node block (count entries, in slot order):
 *     id:        i64
 *     level:     u32
 *     deleted:   u32  (0 or 1)
 *     vector:    f32[dimension]
 *
 *   per-node graph block (count entries, in slot order):
 *     for each layer 0..level (variable per node):
 *       neighborCount: u32
 *       neighborIds:   i64[neighborCount]
 * </pre>
 *
 * <p>Reading is done via mmap; writing via streaming {@link FileChannel#write(ByteBuffer)} so we
 * can grow the file without pre-sizing.
 */
public final class IndexFile {

  public static final byte[] MAGIC = new byte[] {'V', 'E', 'X', '1'};
  public static final int VERSION = 1;

  private IndexFile() {}

  /** Writes the snapshot of {@code index} to {@code path} (overwriting any existing file). */
  public static void write(Path path, HnswIndex index) throws IOException {
    HnswIndex.Snapshot s = index.snapshot();
    HnswConfig cfg = index.config();
    long entryPointId =
        (s.entryPoint() >= 0 && s.entryPoint() < s.size()) ? s.ids()[s.entryPoint()] : -1L;

    try (FileChannel ch =
        FileChannel.open(
            path,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING)) {

      // Header
      ByteBuffer header = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
      header.put(MAGIC);
      header.putInt(VERSION);
      header.putInt(cfg.dimension());
      header.putInt(cfg.M());
      header.putInt(cfg.efConstruction());
      header.putInt(cfg.efSearch());
      header.put(cfg.metric().id());
      header.put((byte) 0);
      header.put((byte) 0);
      header.put((byte) 0);
      header.putLong(cfg.randomSeed());
      header.putLong(s.size());
      header.putLong(s.liveCount());
      header.putLong(entryPointId);
      header.putInt(s.topLayer());
      header.flip();
      writeFully(ch, header);

      int dim = cfg.dimension();
      ByteBuffer node = ByteBuffer.allocate(8 + 4 + 4 + 4 * dim).order(ByteOrder.LITTLE_ENDIAN);
      for (int i = 0; i < s.size(); i++) {
        node.clear();
        node.putLong(s.ids()[i]);
        node.putInt(s.levels()[i]);
        node.putInt(s.deleted()[i] ? 1 : 0);
        for (float f : s.vectors()[i]) {
          node.putFloat(f);
        }
        node.flip();
        writeFully(ch, node);
      }

      // Graph block: each node's per-layer neighbor lists, stored as ids.
      for (int i = 0; i < s.size(); i++) {
        int[][] perLayer = s.connections()[i];
        for (int[] neighbors : perLayer) {
          ByteBuffer buf =
              ByteBuffer.allocate(4 + 8 * neighbors.length).order(ByteOrder.LITTLE_ENDIAN);
          buf.putInt(neighbors.length);
          for (int slot : neighbors) {
            buf.putLong(s.ids()[slot]);
          }
          buf.flip();
          writeFully(ch, buf);
        }
      }

      ch.force(true);
    }
  }

  /** Reads the file at {@code path} via mmap and returns a fresh {@link HnswIndex}. */
  public static HnswIndex read(Path path) throws IOException {
    try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
      long fileSize = ch.size();
      MappedByteBuffer mbb = ch.map(MapMode.READ_ONLY, 0, fileSize);
      mbb.order(ByteOrder.LITTLE_ENDIAN);

      byte[] magic = new byte[4];
      mbb.get(magic);
      if (!java.util.Arrays.equals(magic, MAGIC)) {
        throw new IOException("Not a Vex index file (bad magic)");
      }
      int version = mbb.getInt();
      if (version != VERSION) {
        throw new IOException("Unsupported Vex index version: " + version);
      }
      int dimension = mbb.getInt();
      int M = mbb.getInt();
      int efConstruction = mbb.getInt();
      int efSearch = mbb.getInt();
      byte metricId = mbb.get();
      mbb.get();
      mbb.get();
      mbb.get();
      long randomSeed = mbb.getLong();
      long count = mbb.getLong();
      long liveCount = mbb.getLong();
      long entryPointId = mbb.getLong();
      int topLayer = mbb.getInt();

      DistanceMetric metric = DistanceMetric.forId(metricId);
      HnswConfig cfg = new HnswConfig(M, efConstruction, efSearch, dimension, metric, randomSeed);

      int n = (int) count;
      long[] ids = new long[n];
      int[] levels = new int[n];
      boolean[] deleted = new boolean[n];
      float[][] vectors = new float[n][];

      for (int i = 0; i < n; i++) {
        ids[i] = mbb.getLong();
        levels[i] = mbb.getInt();
        deleted[i] = mbb.getInt() != 0;
        float[] v = new float[dimension];
        for (int j = 0; j < dimension; j++) {
          v[j] = mbb.getFloat();
        }
        vectors[i] = v;
      }

      java.util.Map<Long, Integer> idToSlot = new java.util.HashMap<>(n * 2);
      for (int i = 0; i < n; i++) {
        idToSlot.put(ids[i], i);
      }

      int[][][] connections = new int[n][][];
      for (int i = 0; i < n; i++) {
        int level = levels[i];
        int[][] perLayer = new int[level + 1][];
        for (int lc = 0; lc <= level; lc++) {
          int len = mbb.getInt();
          int[] neighbors = new int[len];
          for (int k = 0; k < len; k++) {
            long nid = mbb.getLong();
            Integer slot = idToSlot.get(nid);
            neighbors[k] = (slot == null) ? -1 : slot;
          }
          perLayer[lc] = neighbors;
        }
        connections[i] = perLayer;
      }

      int entryPointSlot = -1;
      if (entryPointId != -1L) {
        Integer slot = idToSlot.get(entryPointId);
        if (slot != null) {
          entryPointSlot = slot;
        }
      }

      HnswIndex.Snapshot snapshot =
          new HnswIndex.Snapshot(
              n,
              (int) liveCount,
              entryPointSlot,
              topLayer,
              ids,
              levels,
              deleted,
              vectors,
              connections);
      return HnswIndex.restore(cfg, snapshot);
    }
  }

  /** Returns the set of ids that exist in the file (live or tombstoned). */
  public static Set<Long> readIds(Path path) throws IOException {
    try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
      MappedByteBuffer mbb = ch.map(MapMode.READ_ONLY, 0, ch.size());
      mbb.order(ByteOrder.LITTLE_ENDIAN);
      byte[] magic = new byte[4];
      mbb.get(magic);
      if (!java.util.Arrays.equals(magic, MAGIC)) {
        throw new IOException("Not a Vex index file (bad magic)");
      }
      mbb.getInt();
      int dim = mbb.getInt();
      mbb.getInt();
      mbb.getInt();
      mbb.getInt();
      mbb.get();
      mbb.get();
      mbb.get();
      mbb.get();
      mbb.getLong();
      long count = mbb.getLong();
      mbb.getLong();
      mbb.getLong();
      mbb.getInt();
      Set<Long> out = new java.util.HashSet<>((int) count);
      for (int i = 0; i < count; i++) {
        out.add(mbb.getLong());
        mbb.getInt();
        mbb.getInt();
        mbb.position(mbb.position() + 4 * dim);
      }
      return out;
    }
  }

  /** Writes the snapshot to a stream (used in tests / non-mmap targets). */
  static void writeStream(OutputStream out, HnswIndex index) throws IOException {
    Path tmp = java.nio.file.Files.createTempFile("vex-idx", ".tmp");
    try {
      write(tmp, index);
      java.nio.file.Files.copy(tmp, out);
    } finally {
      java.nio.file.Files.deleteIfExists(tmp);
    }
  }

  private static void writeFully(FileChannel ch, ByteBuffer buf) throws IOException {
    while (buf.hasRemaining()) {
      ch.write(buf);
    }
  }
}
