package com.vex.storage;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Append-only write-ahead log of insert/delete operations.
 *
 * <p>Record layout (little-endian):
 *
 * <pre>
 *   length:     u32   (number of bytes that follow this field, including crc)
 *   op:         u8    (0 = insert, 1 = delete)
 *   id:         i64
 *   vector:     f32[dim]   (only for op=insert; absent for delete)
 *   crc32:      u32   (computed over op, id, vector)
 * </pre>
 *
 * <p>On replay we read length-prefixed records. A record is treated as the end of the log if (a) we
 * cannot read the full length+payload, or (b) the CRC mismatches. Either is interpreted as a
 * partial / corrupted tail and replay stops there cleanly.
 */
public final class WriteAheadLog implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(WriteAheadLog.class);

  public static final byte OP_INSERT = 0;
  public static final byte OP_DELETE = 1;

  private final Path path;
  private final FileChannel channel;
  private final boolean fsyncOnAppend;
  private final int dimension;

  private WriteAheadLog(Path path, FileChannel channel, boolean fsyncOnAppend, int dimension) {
    this.path = path;
    this.channel = channel;
    this.fsyncOnAppend = fsyncOnAppend;
    this.dimension = dimension;
  }

  /** Opens (creating if missing) the WAL at {@code path} for appends. */
  public static WriteAheadLog openForWrite(Path path, int dimension, boolean fsyncOnAppend)
      throws IOException {
    FileChannel ch =
        FileChannel.open(
            path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ);
    ch.position(ch.size());
    return new WriteAheadLog(path, ch, fsyncOnAppend, dimension);
  }

  public Path path() {
    return path;
  }

  /** Appends an insert record. */
  public void appendInsert(long id, float[] vector) throws IOException {
    if (vector.length != dimension) {
      throw new IllegalArgumentException("Expected dim " + dimension + " but got " + vector.length);
    }
    int payloadLen = 1 + 8 + 4 * dimension;
    ByteBuffer payload = ByteBuffer.allocate(payloadLen).order(ByteOrder.LITTLE_ENDIAN);
    payload.put(OP_INSERT);
    payload.putLong(id);
    for (float f : vector) {
      payload.putFloat(f);
    }
    writeRecord(payload);
  }

  /** Appends a delete record. */
  public void appendDelete(long id) throws IOException {
    ByteBuffer payload = ByteBuffer.allocate(1 + 8).order(ByteOrder.LITTLE_ENDIAN);
    payload.put(OP_DELETE);
    payload.putLong(id);
    writeRecord(payload);
  }

  private void writeRecord(ByteBuffer payload) throws IOException {
    payload.flip();
    CRC32 crc = new CRC32();
    crc.update(payload.duplicate());
    int crcVal = (int) crc.getValue();
    int len = payload.remaining() + 4;

    ByteBuffer out = ByteBuffer.allocate(4 + len).order(ByteOrder.LITTLE_ENDIAN);
    out.putInt(len);
    out.put(payload);
    out.putInt(crcVal);
    out.flip();
    while (out.hasRemaining()) {
      channel.write(out);
    }
    if (fsyncOnAppend) {
      channel.force(false);
    }
  }

  /** Truncates the WAL to zero bytes and fsyncs. Used after a successful checkpoint. */
  public void truncate() throws IOException {
    channel.truncate(0);
    channel.position(0);
    channel.force(true);
  }

  @Override
  public void close() throws IOException {
    channel.close();
  }

  /**
   * Replays the WAL at {@code path} and returns the records found. Stops cleanly on a partial tail.
   */
  public static List<WalRecord> replay(Path path, int dimension) throws IOException {
    List<WalRecord> records = new ArrayList<>();
    if (!java.nio.file.Files.exists(path)) {
      return records;
    }
    try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
      long fileSize = ch.size();
      long pos = 0;
      ByteBuffer lenBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
      while (pos + 4 <= fileSize) {
        lenBuf.clear();
        if (readFully(ch, lenBuf) < 4) {
          break;
        }
        lenBuf.flip();
        int len = lenBuf.getInt();
        if (len <= 4 || pos + 4 + len > fileSize || len > 64 * 1024 * 1024) {
          // Bogus length or truncated tail; stop.
          break;
        }
        ByteBuffer payload = ByteBuffer.allocate(len - 4).order(ByteOrder.LITTLE_ENDIAN);
        if (readFully(ch, payload) < len - 4) {
          break;
        }
        ByteBuffer crcBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        if (readFully(ch, crcBuf) < 4) {
          break;
        }
        crcBuf.flip();
        int storedCrc = crcBuf.getInt();
        payload.flip();

        CRC32 crc = new CRC32();
        crc.update(payload.duplicate());
        if ((int) crc.getValue() != storedCrc) {
          LOG.warn("WAL CRC mismatch at offset {}; stopping replay.", pos);
          break;
        }
        byte op = payload.get();
        long id = payload.getLong();
        if (op == OP_INSERT) {
          float[] v = new float[dimension];
          for (int i = 0; i < dimension; i++) {
            v[i] = payload.getFloat();
          }
          records.add(new WalRecord(op, id, v));
        } else if (op == OP_DELETE) {
          records.add(new WalRecord(op, id, null));
        } else {
          LOG.warn("WAL unknown op {} at offset {}; stopping replay.", op, pos);
          break;
        }
        pos += 4 + len;
      }
    } catch (EOFException eof) {
      // Acceptable: tail of a partial write.
    }
    return records;
  }

  private static int readFully(FileChannel ch, ByteBuffer buf) throws IOException {
    int total = 0;
    while (buf.hasRemaining()) {
      int n = ch.read(buf);
      if (n < 0) {
        return total;
      }
      total += n;
    }
    return total;
  }

  /** A decoded WAL record. {@code vector} is null for delete ops. */
  public record WalRecord(byte op, long id, float[] vector) {}
}
