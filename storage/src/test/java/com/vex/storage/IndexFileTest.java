package com.vex.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vex.core.HnswConfig;
import com.vex.core.HnswIndex;
import com.vex.core.L2Distance;
import com.vex.core.SearchResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexFileTest {

  @Test
  void writeThenReadProducesEquivalentIndex(@TempDir Path tmp) throws Exception {
    HnswConfig cfg = HnswConfig.defaults(4, new L2Distance());
    HnswIndex original = new HnswIndex(cfg);
    original.add(1L, new float[] {1f, 0f, 0f, 0f});
    original.add(2L, new float[] {0f, 1f, 0f, 0f});
    original.add(3L, new float[] {0f, 0f, 1f, 0f});

    Path file = tmp.resolve("index.vex");
    IndexFile.write(file, original);
    assertThat(Files.size(file)).isPositive();

    HnswIndex read = IndexFile.read(file);
    assertThat(read.size()).isEqualTo(3);
    List<SearchResult> r = read.query(new float[] {1f, 0f, 0f, 0f}, 3);
    assertThat(r).extracting(SearchResult::id).containsExactlyInAnyOrder(1L, 2L, 3L);
  }

  @Test
  void readingMissingFileThrows() {
    assertThatThrownBy(() -> IndexFile.read(Path.of("/this/does/not/exist.vex")))
        .isInstanceOf(java.nio.file.NoSuchFileException.class);
  }

  @Test
  void readingNonVexFileThrowsRecognizableMessage(@TempDir Path tmp) throws Exception {
    Path bogus = tmp.resolve("bogus.vex");
    Files.write(bogus, new byte[] {'B', 'O', 'G', 'O', 1, 2, 3, 4});
    assertThatThrownBy(() -> IndexFile.read(bogus))
        .isInstanceOf(java.io.IOException.class)
        .hasMessageContaining("magic");
  }

  @Test
  void readIdsReturnsAllStoredIds(@TempDir Path tmp) throws Exception {
    HnswConfig cfg = HnswConfig.defaults(2, new L2Distance());
    HnswIndex idx = new HnswIndex(cfg);
    for (long id : new long[] {10L, 20L, 30L, 40L}) {
      idx.add(id, new float[] {(float) id, 0f});
    }
    Path file = tmp.resolve("ids.vex");
    IndexFile.write(file, idx);
    assertThat(IndexFile.readIds(file)).containsExactlyInAnyOrder(10L, 20L, 30L, 40L);
  }
}
