package com.vex.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class DistanceMetricTest {

  @Nested
  class L2 {

    private final DistanceMetric m = new L2Distance();

    @Test
    void identicalVectorsHaveZeroDistance() {
      assertThat(m.distance(new float[] {1, 2, 3}, new float[] {1, 2, 3})).isZero();
    }

    @Test
    void axisAlignedVectorsHaveSquaredDistance() {
      assertThat(m.distance(new float[] {1, 0, 0}, new float[] {0, 1, 0})).isEqualTo(2f);
    }

    @Test
    void oppositeVectorsHaveDistanceFour() {
      assertThat(m.distance(new float[] {1, 0}, new float[] {-1, 0})).isEqualTo(4f);
    }

    @Test
    void scaledVectorsHaveProportionalDistance() {
      assertThat(m.distance(new float[] {2, 0}, new float[] {0, 0})).isEqualTo(4f);
      assertThat(m.distance(new float[] {3, 0}, new float[] {0, 0})).isEqualTo(9f);
    }

    @Test
    void mismatchedDimensionsThrow() {
      assertThatThrownBy(() -> m.distance(new float[] {1, 2}, new float[] {1, 2, 3}))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hasStableIdAndName() {
      assertThat(m.id()).isEqualTo((byte) 0);
      assertThat(m.name()).isEqualTo("l2");
    }
  }

  @Nested
  class Cosine {

    private final DistanceMetric m = new CosineDistance();

    @Test
    void identicalDirectionsHaveZeroDistance() {
      assertThat(m.distance(new float[] {1, 2, 3}, new float[] {1, 2, 3}))
          .isCloseTo(0f, within(1e-6f));
    }

    @Test
    void scaledIdenticalDirectionsHaveZeroDistance() {
      assertThat(m.distance(new float[] {1, 0}, new float[] {5, 0})).isCloseTo(0f, within(1e-6f));
    }

    @Test
    void orthogonalVectorsHaveDistanceOne() {
      assertThat(m.distance(new float[] {1, 0}, new float[] {0, 1})).isCloseTo(1f, within(1e-6f));
    }

    @Test
    void oppositeVectorsHaveDistanceTwo() {
      assertThat(m.distance(new float[] {1, 0}, new float[] {-1, 0})).isCloseTo(2f, within(1e-6f));
    }

    @Test
    void zeroVectorYieldsDefinedFallback() {
      assertThat(m.distance(new float[] {0, 0}, new float[] {1, 0})).isEqualTo(1f);
    }

    @Test
    void mismatchedDimensionsThrow() {
      assertThatThrownBy(() -> m.distance(new float[] {1}, new float[] {1, 2}))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  class DotProduct {

    private final DistanceMetric m = new DotProductDistance();

    @Test
    void identicalVectorsHaveNegativeNormSquared() {
      assertThat(m.distance(new float[] {1, 2}, new float[] {1, 2})).isEqualTo(-(1f + 4f));
    }

    @Test
    void orthogonalVectorsHaveZeroDistance() {
      assertThat(m.distance(new float[] {1, 0}, new float[] {0, 1})).isZero();
    }

    @Test
    void oppositeVectorsHavePositiveDistance() {
      assertThat(m.distance(new float[] {1, 0}, new float[] {-1, 0})).isEqualTo(1f);
    }

    @Test
    void largerInnerProductMeansSmallerDistance() {
      float close = m.distance(new float[] {1, 1}, new float[] {1, 1});
      float far = m.distance(new float[] {1, 1}, new float[] {-1, -1});
      assertThat(close).isLessThan(far);
    }

    @Test
    void mismatchedDimensionsThrow() {
      assertThatThrownBy(() -> m.distance(new float[] {1, 2}, new float[] {1}))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  class Lookup {

    @Test
    void byIdReturnsCorrectMetric() {
      assertThat(DistanceMetric.forId((byte) 0)).isInstanceOf(L2Distance.class);
      assertThat(DistanceMetric.forId((byte) 1)).isInstanceOf(CosineDistance.class);
      assertThat(DistanceMetric.forId((byte) 2)).isInstanceOf(DotProductDistance.class);
    }

    @Test
    void unknownIdThrows() {
      assertThatThrownBy(() -> DistanceMetric.forId((byte) 99))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void byNameIsCaseInsensitive() {
      assertThat(DistanceMetric.named("L2")).isInstanceOf(L2Distance.class);
      assertThat(DistanceMetric.named("Cosine")).isInstanceOf(CosineDistance.class);
      assertThat(DistanceMetric.named("DOT")).isInstanceOf(DotProductDistance.class);
      assertThat(DistanceMetric.named("euclidean")).isInstanceOf(L2Distance.class);
    }

    @Test
    void unknownNameThrows() {
      assertThatThrownBy(() -> DistanceMetric.named("hamming"))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  private static org.assertj.core.data.Offset<Float> within(float v) {
    return org.assertj.core.data.Offset.offset(v);
  }
}
