# Deploying Vex

Vex ships as a single JAR plus a data directory. There is no external
database, no message queue, no clustering. Anything that can run a
JVM and mount a writable volume can run Vex.

## Local: docker compose

Fastest way to try it:

```bash
docker compose up --build
```

This builds the image from the included `Dockerfile`, exposes Vex on
`http://localhost:8080`, and persists state to a Docker volume named
`vex-data`. Health check polls `GET /health` every 10 s.

Sanity check:

```bash
curl -s http://localhost:8080/health   # → {"status":"ok"}
bash examples/curl-quickstart.sh        # creates a collection, upserts, queries
```

## Local: Jib (no Dockerfile, no Docker daemon)

Jib builds the image straight to a daemon, registry, or tar:

```bash
mvn -pl server -am package jib:dockerBuild        # to local Docker daemon
mvn -pl server -am package jib:buildTar           # to ./server/target/jib-image.tar
mvn -pl server -am package -Djib.to.image=ghcr.io/<you>/vex jib:build   # to GHCR
```

The image starts at `eclipse-temurin:17-jre`, exposes 8080, and
declares `/data` as a volume.

## Railway (recommended for the demo)

[Railway](https://railway.app) is the path of least resistance:

1. Push the repo to GitHub.
2. In Railway, create a new project from the GitHub repo.
3. Add a volume mount at `/data`.
4. Set environment variables:
   - `VEX_DATA_DIR=/data`
   - `VEX_WAL_FSYNC=per-write`
5. Deploy. Railway detects the Dockerfile and builds.

Cost note: with persistent volume + always-on, expect ~$5/mo.

## Fly.io

```bash
flyctl launch --copy-config --no-deploy        # answer the prompts
flyctl volumes create vex_data --size 1
```

Edit `fly.toml`:

```toml
[mounts]
  source = "vex_data"
  destination = "/data"

[env]
  VEX_DATA_DIR = "/data"
  VEX_WAL_FSYNC = "per-write"
```

Then:

```bash
flyctl deploy
```

Fly's autoscaler will idle the machine when there's no traffic; that's
fine for a demo but means the first request after idle pays cold-start
(~3 s for Spring Boot).

## Render

Render's blueprint flow works:

1. Connect the GitHub repo.
2. Choose "Web Service", Docker runtime.
3. Add a Disk at `/data` (1 GB is plenty for a demo).
4. Environment:
   - `VEX_DATA_DIR=/data`
   - `VEX_WAL_FSYNC=per-write`
5. Deploy.

Render's free tier sleeps after 15 min idle — same cold-start
caveat as Fly.

## Operational notes

### Backups

The on-disk format is a directory tree under `${VEX_DATA_DIR}`. A
filesystem snapshot is a backup. Preferred sequence:

1. Trigger a flush via a process restart, or wait until traffic is idle.
2. `tar czf vex-backup-$(date +%Y%m%d).tar.gz $VEX_DATA_DIR`.
3. Ship to S3 / B2 / wherever.

There is no online "make a consistent snapshot now" API in v1; the
checkpointing is opportunistic on shutdown.

### Tuning fsync

`VEX_WAL_FSYNC` knobs:

- `per-write` (default) — every insert/delete fsyncs the WAL. Most
  durable; ~1-3 ms write latency overhead per op.
- `never` — no fsync; OS flushes on its own schedule. Fastest;
  acknowledged writes can be lost on a hard crash.

For bulk-loading a large dataset once, set `never`, then bring it back
to `per-write` for steady-state.

### Memory sizing

With default config (M=16, dim 128), expect ~1.9 KB per vector of heap
overhead. Some headroom guidance:

| Vectors | Heap to allocate |
| ------: | ---------------: |
|     10k | 1 GB             |
|    100k | 2 GB             |
|     1M  | 4 GB             |
|    10M  | 16 GB            |

Set `-Xmx` accordingly. Future quantized integration (ADR 005) will
cut these by ~25-30%.

### Observability

The image emits SLF4J logs at INFO. Plug into whatever your platform
gives you (Railway logs, Fly logs, stdout to Datadog).

There is no metrics endpoint in v1. Spring Boot Actuator would add
`/actuator/prometheus` for free; out of scope for the portfolio
release.

### Authentication

There is no authentication layer in v1. Treat any deployed Vex
instance as private — bind to localhost, expose via SSH tunnel or
private network only, or front it with a reverse proxy that handles
auth. Documented as a deliberate gap in ADR 007.
