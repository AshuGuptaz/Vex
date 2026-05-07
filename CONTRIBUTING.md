# Contributing to Vex

Thanks for your interest. This is a portfolio project, so the bar is
"code I'd be happy to defend in a review" — not "ship to production
immediately." Feedback that points out implementation gaps,
algorithmic mistakes, or better Java patterns is the most useful
kind.

## Setup

```bash
brew install openjdk@17
make verify        # full reactor: format check + tests
```

JDK 17 is non-negotiable: `google-java-format` is fussy about JVMs,
and the project's `release` is set to 17.

## Branching

`main` is the trunk. Feature work goes through topic branches with
PRs. The CI workflow (`.github/workflows/ci.yml`) runs format check,
`mvn verify`, Trivy filesystem scan, and CodeQL on every push.

## Commit conventions

[Conventional Commits](https://www.conventionalcommits.org/). Types
in active use:

- `feat(scope): ...` — new functionality
- `fix(scope): ...`  — bug fix
- `refactor(scope): ...`
- `test(scope): ...`
- `docs(scope): ...`
- `perf(scope): ...`
- `chore: ...`
- `build: ...` — build/CI/Maven changes

Scopes are usually module names: `core`, `storage`, `server`, `bench`.

## Code style

- Google Java Format, checked in CI via `fmt-maven-plugin`.
  `make format` applies it locally.
- 2-space indent. 100-char line wrap.
- Public methods get Javadoc; trivial getters can skip it.
- No `System.out.println` in production code. SLF4J for logs;
  `System.out` in benchmarks is fine.
- No TODO / FIXME / commented-out code in committed files. If a
  follow-up is needed, file an ADR or open an issue.

## Tests

- JUnit 5, AssertJ, Mockito.
- Spring tests use MockMvc; no embedded servlet container in tests.
- Running just one module:
  ```bash
  mvn -B -pl core test
  mvn -B -pl server -am test
  ```

## Benchmarks

`make bench-recall`, `make bench-memory`, `make bench-jmh`. The
`bench-sift` target needs the SIFT-1M dataset; download it with
`make sift-data`.

## Where to look first

- HNSW algorithm questions → `core/src/main/java/com/vex/core/HnswIndex.java`
  and the paper summary at `docs/papers/hnsw-summary.md`.
- Persistence questions → `docs/storage-format.md`, then
  `storage/src/main/java/com/vex/storage/`.
- Filter / REST questions → `server/src/main/java/com/vex/server/`.
- Why was X done that way? → `docs/decisions/`. Eight ADRs and
  growing.

## What's deliberately not built

See [ADR 007](docs/decisions/007-whats-not-built.md). Sharding,
replication, product quantization, SIMD, hybrid search, and several
other features are *intentionally* out of scope for v1. PRs adding
them would be welcome but expect a "does this match the v1 scope?"
discussion first.
