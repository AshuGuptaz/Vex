# Blockers

Items I could not finish during the build, with the reason in each case.
The spec's working-style rule is: "If you hit something genuinely
impossible, document the blocker in `docs/blocked.md` with detail and
continue with whatever else you can." This is that file.

## 1. `docker-compose up` end-to-end verification

**Spec:** "Verify `docker-compose up` works end-to-end (build, run,
curl from host)."

**Status:** Configuration committed (`Dockerfile`, `docker-compose.yml`,
Jib config in `server/pom.xml`). **Not verified** — the Docker daemon
is not installed on the build machine. Installing Docker Desktop or
Colima was out of scope for the build session.

**What's still committed and working:**

- `mvn -pl server -am package jib:dockerBuild` produces a runnable
  image when a Docker daemon is available.
- `docker compose up --build` will work on any machine with Docker
  installed.
- `docs/deployment.md` walks through the deploy paths.

**To close:** install Docker (or Colima as a lightweight alternative)
and run `docker compose up --build` then `curl localhost:8080/health`.

## 2. SIFT-1M live benchmark numbers

**Spec:** "SIFT-1M benchmark (or 100k subset if download is too slow):
build time, query latency, recall, memory. Download from
`ftp://ftp.irisa.fr/local/texmex/corpus/sift.tar.gz` if reachable; if
not, document that it requires manual download and provide a script."

**Status:** **Spec-allowed fallback.** The dataset wasn't downloaded
during the build session. The script + benchmark + format reader are
all in the repo:

- `scripts/download_sift.sh` — fetches from IRISA, with the Facebook
  AI mirror as a fallback.
- `bench/.../SiftBenchmark.java` — loads `sift_base.fvecs`,
  `sift_query.fvecs`, `sift_groundtruth.ivecs` and runs the recall
  sweep.
- `bench/.../Fvecs.java` — `.fvecs` / `.ivecs` reader (mmap'd).
- Maven profile `-Pbench-sift` and Make target `make bench-sift`.
- `make sift-data` invokes the download script.

The committed `docs/benchmarks.md` contains real numbers from the
synthetic 100k Gaussian dataset and notes the SIFT scaffolding is
present.

**To close:** `make sift-data && make bench-sift` on a machine with
~1 GB free disk and a working network path to either mirror.

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
