package com.vex.bench;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FvecsTest {

  @Test
  void roundTripPreservesValues(@TempDir Path tmp) throws IOException {
    float[][] data = {
      {1.0f, 2.0f, 3.0f},
      {-1.0f, 0.5f, 0f},
      {1e-3f, 1e3f, 1e6f},
    };
    Path p = tmp.resolve("roundtrip.fvecs");
    Files.write(p, Fvecs.encodeFvecs(data));
    float[][] back = Fvecs.readFvecs(p);
    assertThat(back.length).isEqualTo(data.length);
    for (int i = 0; i < data.length; i++) {
      assertThat(back[i]).containsExactly(data[i]);
    }
  }

  @Test
  void variableDimensionFileIsRejected(@TempDir Path tmp) throws IOException {
    // Hand-craft a file with two different per-record dims.
    java.nio.ByteBuffer buf =
        java.nio.ByteBuffer.allocate(4 + 8 + 4 + 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
    buf.putInt(2);
    buf.putFloat(1f);
    buf.putFloat(2f);
    buf.putInt(1);
    buf.putFloat(3f);
    Path p = tmp.resolve("mixed.fvecs");
    Files.write(p, buf.array());
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> Fvecs.readFvecs(p))
        .isInstanceOf(IOException.class);
  }
}
