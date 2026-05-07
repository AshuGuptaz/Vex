package com.vex.bench;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Reader for the .fvecs / .ivecs binary formats used by the SIFT and GIST ANN benchmarks.
 *
 * <p>Per-vector layout:
 *
 * <pre>
 *   dim:    u32 (little-endian)
 *   values: f32[dim]   (or i32[dim] for .ivecs)
 * </pre>
 */
final class Fvecs {

  private Fvecs() {}

  /** Loads all float vectors from a .fvecs file. */
  static float[][] readFvecs(Path path) throws IOException {
    try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
      long size = ch.size();
      MappedByteBuffer mbb = ch.map(FileChannel.MapMode.READ_ONLY, 0, size);
      mbb.order(ByteOrder.LITTLE_ENDIAN);
      // Peek the first record's dim to pre-size the result.
      int dim = mbb.getInt(0);
      long bytesPerRecord = 4L + 4L * dim;
      if (size % bytesPerRecord != 0) {
        throw new IOException(
            "file size " + size + " not divisible by record size " + bytesPerRecord);
      }
      int n = (int) (size / bytesPerRecord);
      float[][] out = new float[n][dim];
      for (int i = 0; i < n; i++) {
        int d = mbb.getInt();
        if (d != dim) {
          throw new IOException("variable-dim file not supported: row " + i + " dim=" + d);
        }
        for (int j = 0; j < dim; j++) {
          out[i][j] = mbb.getFloat();
        }
      }
      return out;
    }
  }

  /** Loads all int vectors from a .ivecs file (e.g. SIFT ground-truth top-k id lists). */
  static int[][] readIvecs(Path path) throws IOException {
    try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
      long size = ch.size();
      MappedByteBuffer mbb = ch.map(FileChannel.MapMode.READ_ONLY, 0, size);
      mbb.order(ByteOrder.LITTLE_ENDIAN);
      int dim = mbb.getInt(0);
      long bytesPerRecord = 4L + 4L * dim;
      int n = (int) (size / bytesPerRecord);
      int[][] out = new int[n][dim];
      for (int i = 0; i < n; i++) {
        int d = mbb.getInt();
        if (d != dim) {
          throw new IOException("variable-dim file not supported: row " + i + " dim=" + d);
        }
        for (int j = 0; j < dim; j++) {
          out[i][j] = mbb.getInt();
        }
      }
      return out;
    }
  }

  /** For unit-test convenience (writes a small in-memory .fvecs). */
  static byte[] encodeFvecs(float[][] data) {
    int dim = data[0].length;
    ByteBuffer buf =
        ByteBuffer.allocate((4 + 4 * dim) * data.length).order(ByteOrder.LITTLE_ENDIAN);
    for (float[] v : data) {
      buf.putInt(dim);
      for (float f : v) {
        buf.putFloat(f);
      }
    }
    return buf.array();
  }
}
