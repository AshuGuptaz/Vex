package com.vex.server.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class CollectionEndpointsIntegrationTest {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;

  @Test
  void healthEndpointReturnsOk() throws Exception {
    mvc.perform(get("/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ok"));
  }

  @Test
  void openApiDocsEndpointServesValidJson() throws Exception {
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.openapi").exists())
        .andExpect(jsonPath("$.paths").exists());
  }

  @Test
  void swaggerUiEndpointRedirectsOrServesHtml() throws Exception {
    // springdoc serves /swagger-ui.html or redirects to /swagger-ui/index.html.
    int status = mvc.perform(get("/swagger-ui.html")).andReturn().getResponse().getStatus();
    org.assertj.core.api.Assertions.assertThat(status).as("swagger-ui status code").isIn(200, 302);
  }

  @Test
  void listCollectionsEndpointReturnsArrayIncludingNewlyCreated() throws Exception {
    String name = "list-" + System.nanoTime();
    mvc.perform(
            post("/collections")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", name, "dim", 2, "metric", "l2"))))
        .andExpect(status().isCreated());

    mvc.perform(get("/collections"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(
            jsonPath("$[?(@ == '" + name + "')]").value(org.hamcrest.Matchers.hasItem(name)));
  }

  @Test
  void endToEndHundredVectorsWithFilteredQuery() throws Exception {
    String name = "e2e-" + System.nanoTime();
    mvc.perform(
            post("/collections")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", name, "dim", 4, "metric", "l2"))))
        .andExpect(status().isCreated());

    java.util.Random r = new java.util.Random(99L);
    int booksCount = 0;
    for (int i = 1; i <= 100; i++) {
      String category = (i % 3 == 0) ? "books" : "movies";
      if ("books".equals(category)) {
        booksCount++;
      }
      float[] v = {r.nextFloat(), r.nextFloat(), r.nextFloat(), r.nextFloat()};
      upsert(name, i, v, Map.of("category", category, "year", 2020 + (i % 5)));
    }

    var query =
        Map.of(
            "vector",
            new float[] {0.5f, 0.5f, 0.5f, 0.5f},
            "k",
            50,
            "filter",
            "category = \"books\"");
    mvc.perform(
            post("/collections/" + name + "/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(query)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.lessThanOrEqualTo(50)))
        .andExpect(
            jsonPath(
                "$[*].payload.category",
                org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.equalTo("books"))));
    org.assertj.core.api.Assertions.assertThat(booksCount).isGreaterThan(20);
  }

  @Test
  void quantizedCollectionAcceptsUpsertsAndReturnsResults() throws Exception {
    String name = "q-" + System.nanoTime();
    mvc.perform(
            post("/collections")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("name", name, "dim", 4, "metric", "l2", "quantization", "scalar"))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.quantized").value(true));

    upsert(name, 1L, new float[] {1f, 0f, 0f, 0f}, Map.of("c", "a"));
    upsert(name, 2L, new float[] {0f, 1f, 0f, 0f}, Map.of("c", "b"));
    upsert(name, 3L, new float[] {0f, 0f, 1f, 0f}, Map.of("c", "a"));

    mvc.perform(get("/collections/" + name)).andExpect(jsonPath("$.size").value(3));

    mvc.perform(
            post("/collections/" + name + "/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("vector", new float[] {1f, 0f, 0f, 0f}, "k", 3))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].id").value(1));
  }

  @Test
  void createGetDeleteRoundTrip() throws Exception {
    String name = "rt-" + System.nanoTime();
    var create = Map.of("name", name, "dim", 4, "metric", "l2", "M", 16, "efConstruction", 200);
    mvc.perform(
            post("/collections")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(create)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value(name))
        .andExpect(jsonPath("$.dim").value(4));

    mvc.perform(get("/collections/" + name))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.size").value(0));

    mvc.perform(delete("/collections/" + name)).andExpect(status().isNoContent());

    mvc.perform(get("/collections/" + name)).andExpect(status().isNotFound());
  }

  @Test
  void upsertAndQueryReturnsResultsInDistanceOrder() throws Exception {
    String name = "uq-" + System.nanoTime();
    mvc.perform(
            post("/collections")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", name, "dim", 2, "metric", "l2"))))
        .andExpect(status().isCreated());

    upsert(name, 1L, new float[] {0f, 0f}, Map.of("category", "books"));
    upsert(name, 2L, new float[] {1f, 1f}, Map.of("category", "movies"));
    upsert(name, 3L, new float[] {2f, 2f}, Map.of("category", "books"));

    var query = Map.of("vector", new float[] {0f, 0f}, "k", 3);
    mvc.perform(
            post("/collections/" + name + "/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(query)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].id").value(1));
  }

  @Test
  void filteredQueryReturnsOnlyMatchingPayloads() throws Exception {
    String name = "filt-" + System.nanoTime();
    mvc.perform(
            post("/collections")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", name, "dim", 2, "metric", "l2"))))
        .andExpect(status().isCreated());

    upsert(name, 1L, new float[] {0f, 0f}, Map.of("category", "books", "year", 2020));
    upsert(name, 2L, new float[] {1f, 0f}, Map.of("category", "movies", "year", 2021));
    upsert(name, 3L, new float[] {2f, 0f}, Map.of("category", "books", "year", 2022));

    var query =
        Map.of(
            "vector",
            new float[] {0f, 0f},
            "k",
            5,
            "filter",
            "category = \"books\" AND year > 2019");
    mvc.perform(
            post("/collections/" + name + "/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(query)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(
            jsonPath(
                "$[*].payload.category",
                org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.equalTo("books"))));
  }

  @Test
  void batchUpsertInsertsMultipleVectors() throws Exception {
    String name = "batch-" + System.nanoTime();
    mvc.perform(
            post("/collections")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", name, "dim", 2, "metric", "l2"))))
        .andExpect(status().isCreated());

    var batch =
        Map.of(
            "vectors",
            List.of(
                Map.of("id", 10L, "vector", new float[] {0f, 0f}),
                Map.of("id", 11L, "vector", new float[] {1f, 1f}),
                Map.of("id", 12L, "vector", new float[] {2f, 2f})));
    mvc.perform(
            post("/collections/" + name + "/upsert/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(batch)))
        .andExpect(status().isAccepted());

    mvc.perform(get("/collections/" + name)).andExpect(jsonPath("$.size").value(3));
  }

  @Test
  void deleteVectorRemovesItFromQuery() throws Exception {
    String name = "del-" + System.nanoTime();
    mvc.perform(
            post("/collections")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", name, "dim", 2, "metric", "l2"))))
        .andExpect(status().isCreated());
    upsert(name, 1L, new float[] {0f, 0f}, Map.of());
    upsert(name, 2L, new float[] {1f, 1f}, Map.of());

    mvc.perform(delete("/collections/" + name + "/vectors/1")).andExpect(status().isNoContent());

    mvc.perform(
            post("/collections/" + name + "/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("vector", new float[] {0f, 0f}, "k", 5))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].id").value(2));
  }

  @Test
  void getVectorReturnsStoredBytes() throws Exception {
    String name = "gv-" + System.nanoTime();
    mvc.perform(
            post("/collections")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(Map.of("name", name, "dim", 4, "metric", "cosine"))))
        .andExpect(status().isCreated());
    upsert(name, 7L, new float[] {0.5f, 0.5f, 0.5f, 0.5f}, Map.of("k", "v"));

    mvc.perform(get("/collections/" + name + "/vectors/7"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(7))
        .andExpect(jsonPath("$.vector.length()").value(4))
        .andExpect(jsonPath("$.payload.k").value("v"));
  }

  @Test
  void unknownCollectionGivesNotFound() throws Exception {
    mvc.perform(get("/collections/does-not-exist")).andExpect(status().isNotFound());
  }

  @Test
  void invalidFilterReturnsBadRequest() throws Exception {
    String name = "bad-" + System.nanoTime();
    mvc.perform(
            post("/collections")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", name, "dim", 2, "metric", "l2"))))
        .andExpect(status().isCreated());
    upsert(name, 1L, new float[] {0f, 0f}, Map.of());
    var query = Map.of("vector", new float[] {0f, 0f}, "k", 1, "filter", "((bad");
    mvc.perform(
            post("/collections/" + name + "/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(query)))
        .andExpect(status().isBadRequest());
  }

  private void upsert(String name, long id, float[] v, Map<String, Object> payload)
      throws Exception {
    var body = new java.util.HashMap<String, Object>();
    body.put("id", id);
    body.put("vector", v);
    body.put("payload", payload);
    mvc.perform(
            post("/collections/" + name + "/upsert")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isAccepted());
  }
}
