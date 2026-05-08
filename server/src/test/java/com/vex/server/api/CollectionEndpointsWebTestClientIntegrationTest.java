package com.vex.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Parallel integration suite using {@link WebTestClient} (the spec named WebTestClient
 * specifically). Covers the same endpoint surface as {@link CollectionEndpointsIntegrationTest} via
 * a real-port WebTestClient, since spring-mvc apps can't be driven by {@code
 * bindToApplicationContext} the way webflux apps can.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CollectionEndpointsWebTestClientIntegrationTest {

  @LocalServerPort private int port;
  private WebTestClient webTestClient;

  @BeforeEach
  void setUp() {
    this.webTestClient =
        WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .responseTimeout(Duration.ofSeconds(30))
            .build();
  }

  @Test
  void healthEndpointReturnsOk() {
    webTestClient
        .get()
        .uri("/health")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("ok");
  }

  @Test
  void openApiDocsAvailable() {
    webTestClient
        .get()
        .uri("/v3/api-docs")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.openapi")
        .exists()
        .jsonPath("$.paths")
        .exists();
  }

  @Test
  void createListInfoDeleteRoundTrip() {
    String name = "wtc-" + System.nanoTime();

    webTestClient
        .post()
        .uri("/collections")
        .bodyValue(Map.of("name", name, "dim", 4, "metric", "l2"))
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody()
        .jsonPath("$.name")
        .isEqualTo(name)
        .jsonPath("$.dim")
        .isEqualTo(4);

    webTestClient
        .get()
        .uri("/collections")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .value(body -> assertThat(body).contains(name));

    webTestClient
        .get()
        .uri("/collections/" + name)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.size")
        .isEqualTo(0);

    webTestClient.delete().uri("/collections/" + name).exchange().expectStatus().isNoContent();

    webTestClient.get().uri("/collections/" + name).exchange().expectStatus().isNotFound();
  }

  @Test
  void upsertQueryFilterDeleteVector() {
    String name = "wtcq-" + System.nanoTime();
    webTestClient
        .post()
        .uri("/collections")
        .bodyValue(Map.of("name", name, "dim", 2, "metric", "l2"))
        .exchange()
        .expectStatus()
        .isCreated();

    upsert(name, 1L, new float[] {0f, 0f}, Map.of("category", "books"));
    upsert(name, 2L, new float[] {1f, 1f}, Map.of("category", "movies"));
    upsert(name, 3L, new float[] {2f, 2f}, Map.of("category", "books"));

    // Unfiltered query: all 3 in distance order.
    webTestClient
        .post()
        .uri("/collections/" + name + "/query")
        .bodyValue(Map.of("vector", new float[] {0f, 0f}, "k", 3))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.length()")
        .isEqualTo(3)
        .jsonPath("$[0].id")
        .isEqualTo(1);

    // Filtered query: only books.
    webTestClient
        .post()
        .uri("/collections/" + name + "/query")
        .bodyValue(Map.of("vector", new float[] {0f, 0f}, "k", 5, "filter", "category = \"books\""))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.length()")
        .isEqualTo(2);

    // Get vector by id.
    webTestClient
        .get()
        .uri("/collections/" + name + "/vectors/1")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.id")
        .isEqualTo(1)
        .jsonPath("$.payload.category")
        .isEqualTo("books");

    // Delete a vector.
    webTestClient
        .delete()
        .uri("/collections/" + name + "/vectors/2")
        .exchange()
        .expectStatus()
        .isNoContent();

    // Drop the collection.
    webTestClient.delete().uri("/collections/" + name).exchange().expectStatus().isNoContent();
  }

  @Test
  void batchUpsertEndpoint() {
    String name = "wtcb-" + System.nanoTime();
    webTestClient
        .post()
        .uri("/collections")
        .bodyValue(Map.of("name", name, "dim", 2, "metric", "l2"))
        .exchange()
        .expectStatus()
        .isCreated();

    var batch =
        Map.of(
            "vectors",
            List.of(
                Map.of("id", 10L, "vector", new float[] {0f, 0f}),
                Map.of("id", 11L, "vector", new float[] {1f, 1f}),
                Map.of("id", 12L, "vector", new float[] {2f, 2f})));
    webTestClient
        .post()
        .uri("/collections/" + name + "/upsert/batch")
        .bodyValue(batch)
        .exchange()
        .expectStatus()
        .isAccepted();

    webTestClient
        .get()
        .uri("/collections/" + name)
        .exchange()
        .expectBody()
        .jsonPath("$.size")
        .isEqualTo(3);
  }

  @Test
  void invalidFilterReturnsBadRequest() {
    String name = "wtcbad-" + System.nanoTime();
    webTestClient
        .post()
        .uri("/collections")
        .bodyValue(Map.of("name", name, "dim", 2, "metric", "l2"))
        .exchange()
        .expectStatus()
        .isCreated();
    upsert(name, 1L, new float[] {0f, 0f}, Map.of());

    webTestClient
        .post()
        .uri("/collections/" + name + "/query")
        .bodyValue(Map.of("vector", new float[] {0f, 0f}, "k", 1, "filter", "((bad"))
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  @Test
  void unknownCollectionGivesNotFound() {
    webTestClient.get().uri("/collections/does-not-exist").exchange().expectStatus().isNotFound();
  }

  private void upsert(String name, long id, float[] vec, Map<String, Object> payload) {
    var body = new java.util.HashMap<String, Object>();
    body.put("id", id);
    body.put("vector", vec);
    body.put("payload", payload);
    webTestClient
        .post()
        .uri("/collections/" + name + "/upsert")
        .bodyValue(body)
        .exchange()
        .expectStatus()
        .isAccepted();
  }
}
