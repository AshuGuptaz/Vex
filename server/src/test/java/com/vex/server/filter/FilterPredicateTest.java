package com.vex.server.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class FilterPredicateTest {

  @Test
  void alwaysTruePassesEverything() {
    assertThat(FilterPredicate.ALWAYS_TRUE.test(Map.of())).isTrue();
    assertThat(FilterPredicate.ALWAYS_TRUE.test(Map.of("a", 1, "b", "c"))).isTrue();
  }

  @Test
  void andCombinesBooleansShortCircuiting() {
    FilterPredicate truthy = p -> true;
    FilterPredicate falsy = p -> false;
    assertThat(truthy.and(truthy).test(Map.of())).isTrue();
    assertThat(truthy.and(falsy).test(Map.of())).isFalse();
    assertThat(falsy.and(truthy).test(Map.of())).isFalse();
  }

  @Test
  void orShortCircuitsOnTrue() {
    FilterPredicate truthy = p -> true;
    FilterPredicate falsy = p -> false;
    assertThat(truthy.or(falsy).test(Map.of())).isTrue();
    assertThat(falsy.or(truthy).test(Map.of())).isTrue();
    assertThat(falsy.or(falsy).test(Map.of())).isFalse();
  }

  @Test
  void negateFlipsResult() {
    FilterPredicate truthy = p -> true;
    assertThat(truthy.negate().test(Map.of())).isFalse();
    assertThat(truthy.negate().negate().test(Map.of())).isTrue();
  }
}
