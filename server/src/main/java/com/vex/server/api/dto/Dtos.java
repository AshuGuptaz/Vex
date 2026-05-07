package com.vex.server.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

/** Request and response DTOs used by the Vex REST API. */
public final class Dtos {

  private Dtos() {}

  /**
   * Body of {@code POST /collections}. {@code quantization} accepts {@code "scalar"} or omitted.
   */
  public record CreateCollectionRequest(
      @NotBlank String name,
      @Min(1) int dim,
      @NotBlank String metric,
      Integer M,
      Integer efConstruction,
      String quantization) {}

  /** Returned by {@code GET /collections/{name}} and {@code POST /collections}. */
  public record CollectionInfoResponse(
      String name,
      int dim,
      String metric,
      int M,
      int efConstruction,
      int size,
      boolean quantized) {}

  /** Body of {@code POST /collections/{name}/upsert}. {@code payload} may be null/empty. */
  public record UpsertRequest(
      @NotNull Long id, @NotEmpty float[] vector, Map<String, Object> payload) {}

  /** Body of {@code POST /collections/{name}/upsert/batch}. */
  public record BatchUpsertRequest(@NotEmpty List<UpsertRequest> vectors) {}

  /**
   * Body of {@code POST /collections/{name}/query}. {@code filter} is an optional boolean
   * expression over payload fields; see {@code FilterCompiler}'s grammar.
   */
  public record QueryRequest(
      @NotEmpty float[] vector, @Min(1) int k, Integer efSearch, String filter) {}

  /** A single hit returned by the query endpoint. */
  public record QueryResponseItem(long id, float distance, Map<String, Object> payload) {}

  /** Returned by {@code GET /collections/{name}/vectors/{id}}. */
  public record VectorResponse(long id, float[] vector, Map<String, Object> payload) {}

  /** Standard error body for non-2xx responses. */
  public record ErrorResponse(String error) {}
}
