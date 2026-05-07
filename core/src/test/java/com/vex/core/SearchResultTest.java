package com.vex.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class SearchResultTest {

  @Test
  void naturalOrderingIsByDistanceAscending() {
    List<SearchResult> rs = new ArrayList<>();
    rs.add(new SearchResult(1L, 5.0f));
    rs.add(new SearchResult(2L, 1.0f));
    rs.add(new SearchResult(3L, 3.0f));
    Collections.sort(rs);
    assertThat(rs).extracting(SearchResult::id).containsExactly(2L, 3L, 1L);
  }

  @Test
  void equalDistancesCompareEqual() {
    SearchResult a = new SearchResult(1L, 2.5f);
    SearchResult b = new SearchResult(2L, 2.5f);
    assertThat(a).isEqualByComparingTo(b);
  }

  @Test
  void recordEqualsRespectsBothFields() {
    assertThat(new SearchResult(1L, 0.5f)).isEqualTo(new SearchResult(1L, 0.5f));
    assertThat(new SearchResult(1L, 0.5f)).isNotEqualTo(new SearchResult(2L, 0.5f));
    assertThat(new SearchResult(1L, 0.5f)).isNotEqualTo(new SearchResult(1L, 0.6f));
  }
}
