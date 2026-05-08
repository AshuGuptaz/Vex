# Blockers

Items I could not finish during the build, with the reason in each case.
The spec's working-style rule is: "If you hit something genuinely
impossible, document the blocker in `docs/blocked.md` with detail and
continue with whatever else you can." This is that file.

## 1. `docker-compose up` end-to-end verification — ✅ CLOSED 2026-05-09

**Spec:** "Verify `docker-compose up` works end-to-end (build, run,
curl from host)."

**Status:** Verified end-to-end on 2026-05-09 with Colima as the
Docker daemon on macOS arm64.

**Verified path (transcript):**

```bash
brew install colima docker docker-compose lima
colima start --cpu 4 --memory 6 --disk 20

# Build the image directly into the daemon (single-arch for the
# local platform — see Jib config note below).
mvn -B install -DskipTests
mvn -B -pl server jib:dockerBuild -DskipTests -Djib.platform.arch=arm64

# Bring up the stack.
docker compose up -d
docker compose ps
# vex   vex-server:latest   "java -cp @/app/jib-…"   Up (healthy)   0.0.0.0:8080->8080/tcp

# Smoke test from the host:
curl http://localhost:8080/health
# {"status":"ok"}

curl -X POST http://localhost:8080/collections \
  -H 'Content-Type: application/json' \
  -d '{"name":"smoke","dim":4,"metric":"cosine"}'
# {"name":"smoke","dim":4,"metric":"cosine","M":16,"efConstruction":200,"size":0,"quantized":false}

# Three upserts, then query, filter, get, delete, drop — all 2xx.
```

The container ran continuously for 22+ hours during the verification
session. Image size is 449 MB (eclipse-temurin:17-jre + the Vex
classpath laid out by Jib).

**Jib note:** the parent POM exposes `${jib.platform.arch}` (default
`amd64`). Override with `-Djib.platform.arch=arm64` for builds on
ARM hardware (Apple Silicon). CI runs on Linux x86 and uses the
default.

## 2. SIFT-1M live benchmark numbers — ✅ CLOSED 2026-05-09

**Spec:** "SIFT-1M benchmark (or 100k subset if download is too slow):
build time, query latency, recall, memory."

**Status:** Done. Real SIFT-1M numbers committed to
`docs/benchmarks.md`:

| efSearch | recall@10 | ms/query |
| -------: | --------: | -------: |
| 64       | **0.972** | 0.44     |
| 128      | **0.992** | 0.75     |

Build time: 1,167 s (857 ins/sec avg). Heap: ~1.4 GB.

These match published hnswlib SIFT-1M numbers at the same M=16 /
efC=200 parameters. The "recall gap to hnswlib" hypothesis from the
synthetic Gaussian benchmark was wrong: my impl is fully competitive
on real data. Uniform random Gaussian is pathologically hard for any
graph-based ANN method (no cluster signal). Documented in
`docs/benchmarks.md`.

**Reproduce:** `make sift-data && make bench-sift`.

## 3. CI workflow green on push

**Spec:** "CI workflow green on push" / "CI workflow committed and
(locally simulatable) passing all checks."

**Status:** Workflow committed at `.github/workflows/ci.yml`. Runs
format check, `mvn clean verify`, Trivy filesystem scan, CodeQL Java
analysis. **Not pushed yet** at the user's explicit request — they
asked me to keep commits local until they're ready to push. CI cannot
go green on a remote that hasn't been written to.

**To close:** `git push origin main` from the local repo. CI will run
on the first push.

## 4. Recall gap to hnswlib at 100k

**Spec:** "Recall@10 >= 0.95 on 10k synthetic vector test, default
config." (10k spec test passes ≥ 0.95 at efSearch=200.)

**Beyond the spec, but a real quality finding:** at **100k** vectors
under the same `M=16, efC=200` config, my implementation lands at:

- ef=64 → 0.50, ef=128 → 0.65, ef=256 → 0.82, ef=512 → 0.91, ef=1024 → 0.94

A tuned hnswlib at the same parameters lands ~0.95 at ef=200. So my
`searchLayer` takes ~2-3× more visits per unit of recall.

**Investigated, partially fixed:** three small bugs in the graph-build
path were leaving layer 0 under-saturated (avg 22 connections of 32
cap). Fixed all three (see commit `cf777dd`); layer 0 now hits 100%
saturation. Recall lift from those fixes was modest (+3-4 points at
each ef).

**Hypotheses ruled out (with measurements committed):** the diversity
heuristic (verified empirically with `useHeuristicNeighborSelection`
flag), the bidirectional edge dropout (verified with `protectNewEdge`
flag), upper-layer routing (verified by replacing greedy with
ef=8 search). Documented in detail in `docs/benchmarks.md`.

**Hypotheses NOT ruled out:** initial dynamic-list seeding from the
previous-layer W in `searchLayer` may pre-fill `dynamic` to ef which
triggers the early-terminate condition too soon at lower layers. This
is a real refactor of the search algorithm and was scoped to v2.

**To close:** profile a single query against hnswlib's reference and
identify the per-iteration difference; refactor `searchLayer` based
on what's found.
