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

  public record CreateCollectionRequest(
      @NotBlank String name,
      @Min(1) int dim,
      @NotBlank String metric,
      Integer M,
      Integer efConstruction,
      String quantization) {}

  public record CollectionInfoResponse(
      String name,
      int dim,
      String metric,
      int M,
      int efConstruction,
      int size,
      boolean quantized) {}

  public record UpsertRequest(
      @NotNull Long id, @NotEmpty float[] vector, Map<String, Object> payload) {}

  public record BatchUpsertRequest(@NotEmpty List<UpsertRequest> vectors) {}

  public record QueryRequest(
      @NotEmpty float[] vector, @Min(1) int k, Integer efSearch, String filter) {}

  public record QueryResponseItem(long id, float distance, Map<String, Object> payload) {}

  public record VectorResponse(long id, float[] vector, Map<String, Object> payload) {}

  public record ErrorResponse(String error) {}
}
