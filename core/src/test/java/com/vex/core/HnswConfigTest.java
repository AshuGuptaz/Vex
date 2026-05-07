package com.vex.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class HnswConfigTest {

  @Test
  void mLessThanTwoIsRejected() {
    assertThatThrownBy(() -> new HnswConfig(1, 100, 50, 4, new L2Distance(), 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("M");
  }

  @Test
  void efConstructionLessThanMIsRejected() {
    assertThatThrownBy(() -> new HnswConfig(16, 4, 50, 4, new L2Distance(), 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("efConstruction");
  }

  @Test
  void efSearchBelowOneIsRejected() {
    assertThatThrownBy(() -> new HnswConfig(16, 100, 0, 4, new L2Distance(), 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("efSearch");
  }

  @Test
  void dimensionBelowOneIsRejected() {
    assertThatThrownBy(() -> new HnswConfig(16, 100, 50, 0, new L2Distance(), 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("dimension");
  }

  @Test
  void nullMetricIsRejected() {
    assertThatThrownBy(() -> new HnswConfig(16, 100, 50, 4, null, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("metric");
  }

  @Test
  void backwardsCompatibleConstructorDefaultsHeuristicTrue() {
    HnswConfig cfg = new HnswConfig(16, 200, 50, 4, new L2Distance(), 42L);
    assertThat(cfg.useHeuristicNeighborSelection()).isTrue();
  }

  @Test
  void defaultsHelperUsesReasonableValues() {
    HnswConfig cfg = HnswConfig.defaults(8, new L2Distance());
    assertThat(cfg.M()).isEqualTo(16);
    assertThat(cfg.efConstruction()).isEqualTo(200);
    assertThat(cfg.efSearch()).isEqualTo(50);
    assertThat(cfg.randomSeed()).isEqualTo(42L);
  }
}
