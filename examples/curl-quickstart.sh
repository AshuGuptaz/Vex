#!/usr/bin/env bash
# Minimal end-to-end Vex demo: create collection, upsert vectors, query.
# Requires: jq, curl. Assumes Vex is running on localhost:8080.

set -euo pipefail

VEX="${VEX_URL:-http://localhost:8080}"
COLL="quickstart-$$"

echo "== Creating collection '$COLL' =="
curl -fsS -X POST "$VEX/collections" \
  -H "Content-Type: application/json" \
  -d "{\"name\":\"$COLL\",\"dim\":4,\"metric\":\"cosine\",\"M\":16,\"efConstruction\":200}" \
  | jq .

echo
echo "== Upserting 4 vectors =="
for tuple in \
  '1|[1.0, 0.0, 0.0, 0.0]|{"category":"books","year":2020}' \
  '2|[0.9, 0.1, 0.0, 0.0]|{"category":"books","year":2021}' \
  '3|[0.0, 1.0, 0.0, 0.0]|{"category":"movies","year":2022}' \
  '4|[0.0, 0.0, 1.0, 0.0]|{"category":"music","year":2023}'
do
  IFS='|' read -r id vec payload <<< "$tuple"
  curl -fsS -X POST "$VEX/collections/$COLL/upsert" \
    -H "Content-Type: application/json" \
    -d "{\"id\":$id,\"vector\":$vec,\"payload\":$payload}" \
    -o /dev/null
  echo "  upserted id=$id"
done

echo
echo "== Querying nearest 3 =="
curl -fsS -X POST "$VEX/collections/$COLL/query" \
  -H "Content-Type: application/json" \
  -d '{"vector":[1.0,0.0,0.0,0.0],"k":3}' \
  | jq .

echo
echo "== Filtered query (books only) =="
curl -fsS -X POST "$VEX/collections/$COLL/query" \
  -H "Content-Type: application/json" \
  -d '{"vector":[1.0,0.0,0.0,0.0],"k":5,"filter":"category = \"books\""}' \
  | jq .

echo
echo "== Cleanup =="
curl -fsS -X DELETE "$VEX/collections/$COLL"
echo "deleted '$COLL'."
