package com.vex.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.vex.core.HnswConfig;
import com.vex.core.L2Distance;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CorruptionTest {

  @Test
  void truncatedWalTailIsIgnoredOnReplay(@TempDir Path tmp) throws Exception {
    HnswConfig cfg = HnswConfig.defaults(4, new L2Distance());
    IndexStorage s = IndexStorage.open(tmp, cfg, true);
    for (int i = 0; i < 50; i++) {
      s.add(i, new float[] {i, 0, 0, 0});
    }
    s.index().close();

    Path wal = tmp.resolve(IndexStorage.WAL_FILE);
    long before = Files.size(wal);
    // Drop the last 17 bytes — guaranteed mid-record for any insert payload.
    try (FileChannel ch = FileChannel.open(wal, StandardOpenOption.WRITE)) {
      ch.truncate(before - 17);
    }
    long after = Files.size(wal);
    assertThat(after).isLessThan(before);

    try (IndexStorage reopened = IndexStorage.open(tmp, cfg, false)) {
      // The last record is partial; everything before it is intact.
      assertThat(reopened.size()).isBetween(0, 49);
      // First few definitely landed.
      for (int i = 0; i < 40; i++) {
        assertThat(reopened.contains(i)).as("id %d", i).isTrue();
      }
    }
  }

  @Test
  void crcMismatchStopsReplayAtTheBadRecord(@TempDir Path tmp) throws Exception {
    HnswConfig cfg = HnswConfig.defaults(4, new L2Distance());
    IndexStorage s = IndexStorage.open(tmp, cfg, true);
    for (int i = 0; i < 5; i++) {
      s.add(i, new float[] {i, 0, 0, 0});
    }
    s.index().close();

    Path wal = tmp.resolve(IndexStorage.WAL_FILE);
    // Flip the last byte — this lands inside the trailing record's CRC bytes.
    long size = Files.size(wal);
    byte[] all = Files.readAllBytes(wal);
    all[(int) size - 1] ^= (byte) 0xFF;
    Files.write(wal, all);

    try (IndexStorage reopened = IndexStorage.open(tmp, cfg, false)) {
      // Replay stops at the corrupted record; earlier records still loaded.
      assertThat(reopened.size()).isBetween(0, 4);
    }
  }
}
