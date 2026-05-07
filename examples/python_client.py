"""Minimal Python client for the Vex REST API.

Run a Vex server locally (`mvn -pl server spring-boot:run`) and then:

    python3 examples/python_client.py
"""

from __future__ import annotations

import json
import urllib.request
from typing import Any


class VexClient:
    """Tiny synchronous client built on urllib so it has no third-party deps."""

    def __init__(self, base_url: str = "http://localhost:8080") -> None:
        self.base = base_url.rstrip("/")

    # --- collections ---

    def create(
        self,
        name: str,
        dim: int,
        metric: str = "l2",
        m: int = 16,
        ef_construction: int = 200,
        quantization: str = "none",
    ) -> dict[str, Any]:
        return self._post(
            "/collections",
            {
                "name": name,
                "dim": dim,
                "metric": metric,
                "M": m,
                "efConstruction": ef_construction,
                "quantization": quantization,
            },
        )

    def info(self, name: str) -> dict[str, Any]:
        return self._get(f"/collections/{name}")

    def drop(self, name: str) -> None:
        self._request("DELETE", f"/collections/{name}", expect_body=False)

    # --- vectors ---

    def upsert(
        self, name: str, vec_id: int, vector: list[float], payload: dict | None = None
    ) -> None:
        self._post(
            f"/collections/{name}/upsert",
            {"id": vec_id, "vector": vector, "payload": payload or {}},
            expect_body=False,
        )

    def upsert_batch(self, name: str, items: list[dict[str, Any]]) -> None:
        self._post(
            f"/collections/{name}/upsert/batch", {"vectors": items}, expect_body=False
        )

    def query(
        self,
        name: str,
        vector: list[float],
        k: int = 10,
        ef_search: int | None = None,
        filter_expr: str | None = None,
    ) -> list[dict[str, Any]]:
        body: dict[str, Any] = {"vector": vector, "k": k}
        if ef_search is not None:
            body["efSearch"] = ef_search
        if filter_expr:
            body["filter"] = filter_expr
        return self._post(f"/collections/{name}/query", body)

    def get_vector(self, name: str, vec_id: int) -> dict[str, Any]:
        return self._get(f"/collections/{name}/vectors/{vec_id}")

    def delete_vector(self, name: str, vec_id: int) -> None:
        self._request(
            "DELETE", f"/collections/{name}/vectors/{vec_id}", expect_body=False
        )

    # --- internals ---

    def _get(self, path: str) -> Any:
        return self._request("GET", path)

    def _post(self, path: str, body: dict, expect_body: bool = True) -> Any:
        return self._request("POST", path, body, expect_body=expect_body)

    def _request(
        self,
        method: str,
        path: str,
        body: dict | None = None,
        expect_body: bool = True,
    ) -> Any:
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(  # noqa: S310 (server URL is user-supplied)
            self.base + path,
            data=data,
            method=method,
            headers={"Content-Type": "application/json"} if data else {},
        )
        with urllib.request.urlopen(req) as resp:  # noqa: S310
            payload = resp.read()
        if not expect_body or not payload:
            return None
        return json.loads(payload)


def main() -> None:
    vex = VexClient()
    name = "py-quickstart"

    try:
        vex.drop(name)
    except Exception:
        pass

    print("creating collection...")
    print(vex.create(name, dim=4, metric="cosine"))

    print("\nupserting 4 vectors...")
    for vec_id, vector, payload in (
        (1, [1.0, 0.0, 0.0, 0.0], {"category": "books", "year": 2020}),
        (2, [0.9, 0.1, 0.0, 0.0], {"category": "books", "year": 2021}),
        (3, [0.0, 1.0, 0.0, 0.0], {"category": "movies", "year": 2022}),
        (4, [0.0, 0.0, 1.0, 0.0], {"category": "music", "year": 2023}),
    ):
        vex.upsert(name, vec_id, vector, payload)

    print("\ntop-3 nearest to [1,0,0,0]:")
    for hit in vex.query(name, [1.0, 0.0, 0.0, 0.0], k=3):
        print(f"  id={hit['id']} distance={hit['distance']:.4f} payload={hit['payload']}")

    print('\ntop-3 with filter category = "books":')
    for hit in vex.query(
        name, [1.0, 0.0, 0.0, 0.0], k=3, filter_expr='category = "books"'
    ):
        print(f"  id={hit['id']} distance={hit['distance']:.4f} payload={hit['payload']}")

    print("\ncleaning up...")
    vex.drop(name)


if __name__ == "__main__":
    main()
