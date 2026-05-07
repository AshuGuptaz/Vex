package com.vex.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Random;
import org.junit.jupiter.api.Test;

class ScalarQuantizerTest {

  @Test
  void trainsOnUniformDataAndDecodesBackCloseToOriginal() {
    Random r = new Random(1L);
    int dim = 32;
    int n = 1000;
    float[][] sample = new float[n][dim];
    for (int i = 0; i < n; i++) {
      for (int j = 0; j < dim; j++) {
        sample[i][j] = r.nextFloat() * 2f - 1f;
      }
    }
    ScalarQuantizer q = ScalarQuantizer.train(sample);

    float maxErr = 0f;
    for (float[] v : sample) {
      byte[] enc = q.encode(v);
      float[] dec = q.decode(enc);
      for (int i = 0; i < dim; i++) {
        maxErr = Math.max(maxErr, Math.abs(v[i] - dec[i]));
      }
    }
    // Worst-case quantization error per dim is range/254 ≈ 2/254 ≈ 0.0079.
    assertThat(maxErr).isLessThan(0.02f);
  }

  @Test
  void encodingFollowsLinearMapAndIsBoundedByInt8() {
    // Per-dim range must span [-1, 1] for the test to exercise both ends.
    float[][] sample = {
      new float[] {-1f, -1f, -1f},
      new float[] {1f, 1f, 1f},
    };
    ScalarQuantizer q = ScalarQuantizer.train(sample);
    byte[] enc = q.encode(new float[] {-1f, 0f, 1f});
    assertThat(enc[0]).isEqualTo((byte) -127);
    assertThat(enc[2]).isEqualTo((byte) 127);
    assertThat((int) enc[1]).isCloseTo(0, org.assertj.core.data.Offset.offset(1));
  }

  @Test
  void degenerateRangeYieldsConstantEncoding() {
    float[][] sample = {new float[] {1f, 1f, 1f}};
    ScalarQuantizer q = ScalarQuantizer.train(sample);
    byte[] enc = q.encode(new float[] {1f, 1f, 1f});
    // No range to map; encoder should not throw.
    assertThat(enc).hasSize(3);
  }

  @Test
  void squaredL2OrderingMatchesFloatDomain() {
    Random r = new Random(7L);
    int dim = 16;
    float[][] sample = new float[200][dim];
    for (int i = 0; i < sample.length; i++) {
      for (int j = 0; j < dim; j++) {
        sample[i][j] = (float) r.nextGaussian();
      }
    }
    ScalarQuantizer q = ScalarQuantizer.train(sample);
    DistanceMetric metric = new L2Distance();

    float[] qVec = sample[0];
    int n = 50;
    int matches = 0;
    for (int i = 0; i < n; i++) {
      for (int j = 0; j < n; j++) {
        if (i == j) continue;
        float floatA = metric.distance(qVec, sample[i]);
        float floatB = metric.distance(qVec, sample[j]);
        float byteA = q.squaredL2(q.encode(qVec), q.encode(sample[i]));
        float byteB = q.squaredL2(q.encode(qVec), q.encode(sample[j]));
        if ((floatA < floatB) == (byteA < byteB)) {
          matches++;
        }
      }
    }
    int totalPairs = n * (n - 1);
    // Quantization preserves ordering on most pairs; expect >= 90%.
    assertThat(matches / (double) totalPairs).isGreaterThanOrEqualTo(0.9);
  }

  @Test
  void roundTripThroughOfPreservesEncoding() {
    float[][] sample = {{-2f, 0f, 2f}, {-2f, 0f, 2f}};
    ScalarQuantizer q1 = ScalarQuantizer.train(sample);
    ScalarQuantizer q2 = ScalarQuantizer.of(q1.mins(), q1.scales());
    float[] v = {1f, 0.5f, -1.5f};
    assertThat(q2.encode(v)).containsExactly(q1.encode(v));
  }

  @Test
  void emptySampleThrows() {
    assertThatThrownBy(() -> ScalarQuantizer.train(new float[0][]))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void inconsistentDimensionThrows() {
    float[][] sample = {new float[] {1f, 2f}, new float[] {1f, 2f, 3f}};
    assertThatThrownBy(() -> ScalarQuantizer.train(sample))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void encodeDimensionMismatchThrows() {
    ScalarQuantizer q = ScalarQuantizer.train(new float[][] {{0f, 1f}});
    assertThatThrownBy(() -> q.encode(new float[] {1f}))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void encodedSizeIsOneByteSinceDimension() {
    ScalarQuantizer q = ScalarQuantizer.train(new float[][] {new float[64]});
    byte[] enc = q.encode(new float[64]);
    // 64 floats (256 bytes) -> 64 bytes, 4x compression.
    assertThat(enc).hasSize(64);
  }
}
