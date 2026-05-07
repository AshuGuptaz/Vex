package com.vex.server.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PayloadStoreTest {

  @Test
  void putThenGetReturnsSameMap(@TempDir Path tmp) throws Exception {
    try (PayloadStore s = PayloadStore.open(tmp.resolve("p.db"), false)) {
      s.put(1L, Map.of("category", "books", "year", 2026));
      assertThat(s.get(1L)).containsEntry("category", "books").containsEntry("year", 2026);
    }
  }

  @Test
  void payloadSurvivesReopen(@TempDir Path tmp) throws Exception {
    try (PayloadStore s = PayloadStore.open(tmp.resolve("p.db"), true)) {
      s.put(1L, Map.of("k", "v"));
      s.put(2L, Map.of("k", "v2"));
    }
    try (PayloadStore s = PayloadStore.open(tmp.resolve("p.db"), false)) {
      assertThat(s.get(1L)).containsEntry("k", "v");
      assertThat(s.get(2L)).containsEntry("k", "v2");
    }
  }

  @Test
  void laterPutWinsOnReopen(@TempDir Path tmp) throws Exception {
    try (PayloadStore s = PayloadStore.open(tmp.resolve("p.db"), true)) {
      s.put(1L, Map.of("v", 1));
      s.put(1L, Map.of("v", 2));
      s.put(1L, Map.of("v", 3));
    }
    try (PayloadStore s = PayloadStore.open(tmp.resolve("p.db"), false)) {
      assertThat(s.get(1L)).containsEntry("v", 3);
    }
  }

  @Test
  void removeMakesGetReturnNull(@TempDir Path tmp) throws Exception {
    try (PayloadStore s = PayloadStore.open(tmp.resolve("p.db"), true)) {
      s.put(1L, Map.of("k", "v"));
      s.remove(1L);
      assertThat(s.get(1L)).isNull();
    }
    try (PayloadStore s = PayloadStore.open(tmp.resolve("p.db"), false)) {
      assertThat(s.get(1L)).isNull();
    }
  }

  @Test
  void hasReflectsPresence(@TempDir Path tmp) throws Exception {
    try (PayloadStore s = PayloadStore.open(tmp.resolve("p.db"), false)) {
      assertThat(s.has(1L)).isFalse();
      s.put(1L, Map.of("k", "v"));
      assertThat(s.has(1L)).isTrue();
      s.remove(1L);
      assertThat(s.has(1L)).isFalse();
    }
  }
}
