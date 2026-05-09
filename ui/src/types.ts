// TypeScript shapes mirroring com.vex.server.api.dto.Dtos.

export interface CollectionInfo {
  name: string;
  dim: number;
  metric: string;
  M: number;
  efConstruction: number;
  size: number;
  quantized: boolean;
}

export interface CreateCollectionRequest {
  name: string;
  dim: number;
  metric: string;
  M?: number;
  efConstruction?: number;
  quantization?: "scalar" | "none";
}

export interface UpsertRequest {
  id: number;
  vector: number[];
  payload?: Record<string, unknown>;
}

export interface QueryRequest {
  vector: number[];
  k: number;
  efSearch?: number;
  filter?: string;
}

export interface QueryHit {
  id: number;
  distance: number;
  payload: Record<string, unknown>;
}

export interface ApiError {
  error: string;
}
