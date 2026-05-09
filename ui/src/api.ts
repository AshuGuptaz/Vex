// Tiny fetch wrapper. The UI is bundled into Spring Boot's static dir so it
// runs same-origin against the API in production. In dev (`npm run dev`),
// vite.config.ts proxies these paths to localhost:8080.

import type {
  CollectionInfo,
  CreateCollectionRequest,
  QueryHit,
  QueryRequest,
  UpsertRequest,
} from "./types";

async function request<T>(
  method: string,
  path: string,
  body?: unknown
): Promise<T> {
  const res = await fetch(path, {
    method,
    headers: body ? { "Content-Type": "application/json" } : {},
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!res.ok) {
    let msg = `${res.status} ${res.statusText}`;
    try {
      const data = await res.json();
      if (data?.error) msg = `${res.status} ${data.error}`;
    } catch {
      /* ignore — body wasn't JSON */
    }
    throw new Error(msg);
  }
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  return text ? (JSON.parse(text) as T) : (undefined as T);
}

export const api = {
  health: () => request<{ status: string }>("GET", "/health"),

  listCollections: () => request<string[]>("GET", "/collections"),

  getCollection: (name: string) =>
    request<CollectionInfo>("GET", `/collections/${encodeURIComponent(name)}`),

  createCollection: (req: CreateCollectionRequest) =>
    request<CollectionInfo>("POST", "/collections", req),

  dropCollection: (name: string) =>
    request<void>("DELETE", `/collections/${encodeURIComponent(name)}`),

  upsert: (name: string, req: UpsertRequest) =>
    request<void>(
      "POST",
      `/collections/${encodeURIComponent(name)}/upsert`,
      req
    ),

  query: (name: string, req: QueryRequest) =>
    request<QueryHit[]>(
      "POST",
      `/collections/${encodeURIComponent(name)}/query`,
      req
    ),
};
