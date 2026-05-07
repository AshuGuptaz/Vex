package com.vex.server.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Append-only log of (id, payload) tuples backed by a single file. The in-memory map holds the
 * latest payload per id; on startup we replay the file to rebuild it.
 *
 * <p>Record layout (little-endian):
 *
 * <pre>
 *   length:  u32  (size of the JSON bytes, or 0 for delete)
 *   id:      i64
 *   json:    UTF-8[length]
 * </pre>
 */
public final class PayloadStore implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(PayloadStore.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Path path;
  private final FileChannel channel;
  private final boolean fsyncOnAppend;
  private final Map<Long, Map<String, Object>> payloads = new ConcurrentHashMap<>();

  private PayloadStore(Path path, FileChannel channel, boolean fsyncOnAppend) {
    this.path = path;
    this.channel = channel;
    this.fsyncOnAppend = fsyncOnAppend;
  }

  public static PayloadStore open(Path path, boolean fsyncOnAppend) throws IOException {
    Files.createDirectories(path.getParent());
    PayloadStore store =
        new PayloadStore(
            path,
            FileChannel.open(
                path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE),
            fsyncOnAppend);
    store.replay();
    store.channel.position(store.channel.size());
    return store;
  }

  @SuppressWarnings("unchecked")
  private void replay() throws IOException {
    long fileSize = channel.size();
    if (fileSize == 0) {
      return;
    }
    ByteBuffer header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
    long pos = 0;
    while (pos < fileSize) {
      header.clear();
      int read = readFully(channel, header);
      if (read < 12) {
        LOG.warn("Truncated payload header at {} ({} of 12 bytes); stopping replay.", pos, read);
        break;
      }
      header.flip();
      int len = header.getInt();
      long id = header.getLong();
      if (len < 0 || pos + 12 + len > fileSize) {
        LOG.warn("Truncated payload record at {}; stopping replay.", pos);
        break;
      }
      if (len == 0) {
        payloads.remove(id);
      } else {
        ByteBuffer body = ByteBuffer.allocate(len);
        if (readFully(channel, body) < len) {
          LOG.warn("Truncated payload body at {}; stopping replay.", pos);
          break;
        }
        body.flip();
        byte[] bytes = new byte[len];
        body.get(bytes);
        try {
          Map<String, Object> obj =
              MAPPER.readValue(new String(bytes, StandardCharsets.UTF_8), Map.class);
          payloads.put(id, obj);
        } catch (IOException e) {
          LOG.warn("Bad payload JSON at offset {}: {}; stopping replay.", pos, e.getMessage());
          break;
        }
      }
      pos += 12 + len;
    }
  }

  public Map<String, Object> get(long id) {
    Map<String, Object> p = payloads.get(id);
    return p == null ? null : new HashMap<>(p);
  }

  public boolean has(long id) {
    return payloads.containsKey(id);
  }

  public synchronized void put(long id, Map<String, Object> payload) throws IOException {
    if (payload == null || payload.isEmpty()) {
      payloads.remove(id);
      append(id, null);
      return;
    }
    String json;
    try {
      json = MAPPER.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      throw new IOException("Failed to serialize payload for id " + id, e);
    }
    payloads.put(id, new HashMap<>(payload));
    append(id, json.getBytes(StandardCharsets.UTF_8));
  }

  public synchronized void remove(long id) throws IOException {
    if (payloads.remove(id) != null) {
      append(id, null);
    }
  }

  private void append(long id, byte[] body) throws IOException {
    int len = (body == null) ? 0 : body.length;
    ByteBuffer buf = ByteBuffer.allocate(12 + len).order(ByteOrder.LITTLE_ENDIAN);
    buf.putInt(len);
    buf.putLong(id);
    if (body != null) {
      buf.put(body);
    }
    buf.flip();
    while (buf.hasRemaining()) {
      channel.write(buf);
    }
    if (fsyncOnAppend) {
      channel.force(false);
    }
  }

  public Path path() {
    return path;
  }

  @Override
  public synchronized void close() throws IOException {
    channel.force(true);
    channel.close();
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
}
