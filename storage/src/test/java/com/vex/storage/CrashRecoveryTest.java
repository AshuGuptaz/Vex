package com.vex.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.vex.core.HnswConfig;
import com.vex.core.L2Distance;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spawns a child JVM that inserts vectors into a fresh storage directory and then immediately halts
 * (Runtime.halt) without flushing. The parent then opens the directory and verifies that the
 * inserts that the child reported as acknowledged are present after WAL replay.
 */
class CrashRecoveryTest {

  @Test
  void acknowledgedInsertsSurviveHardCrash(@TempDir Path tmp) throws Exception {
    int n = 200;
    Path dataDir = tmp.resolve("idx");
    HnswConfig cfg = HnswConfig.defaults(4, new L2Distance());

    String javaBin =
        System.getProperty("java.home")
            + java.io.File.separator
            + "bin"
            + java.io.File.separator
            + "java";
    String classpath = System.getProperty("java.class.path");

    ProcessBuilder pb =
        new ProcessBuilder(
            javaBin,
            "-cp",
            classpath,
            CrashChild.class.getName(),
            dataDir.toString(),
            String.valueOf(n));
    pb.redirectErrorStream(true);
    Process p = pb.start();

    List<Long> acked = new ArrayList<>();
    try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
      String line;
      while ((line = br.readLine()) != null) {
        if (line.startsWith("ACK ")) {
          acked.add(Long.parseLong(line.substring(4).trim()));
        }
      }
    }
    int exitCode = p.waitFor();
    // The child halts with exit code 137 (1).
    assertThat(exitCode).as("child exit code").isNotZero();
    assertThat(acked).as("acked ids").hasSize(n);

    try (IndexStorage reopened = IndexStorage.open(dataDir, cfg, false)) {
      for (long id : acked) {
        assertThat(reopened.contains(id)).as("id %d", id).isTrue();
      }
      assertThat(reopened.size()).isEqualTo(acked.size());
    }
  }

  /** Child entry point: writes inserts to the WAL, prints ACK per write, then halts. */
  public static final class CrashChild {
    public static void main(String[] args) throws Exception {
      Path dir = Path.of(args[0]);
      int n = Integer.parseInt(args[1]);
      HnswConfig cfg = HnswConfig.defaults(4, new L2Distance());
      IndexStorage s = IndexStorage.open(dir, cfg, true);
      for (int i = 0; i < n; i++) {
        s.add(i, new float[] {i, i, i, i});
        System.out.println("ACK " + i);
        System.out.flush();
      }
      // Hard halt — bypass shutdown hooks, simulate process kill.
      Runtime.getRuntime().halt(137);
    }
  }
}
