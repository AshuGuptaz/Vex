# Vex storage format

Vex persists each collection as a directory with two files:

```
<collection>/
├── index.vex   # checkpointed snapshot of the index
└── wal.log     # append-only write-ahead log
```

A reader builds the in-memory state by (1) reading `index.vex` and then
(2) replaying any records still in `wal.log`. Both files are little-endian.

## index.vex

```
Offset  Size  Field             Notes
─────── ───── ───────────────── ────────────────────────────────────
0x00    4     magic             ASCII "VEX1"
0x04    4     version           u32, currently 1
0x08    4     dimension         u32, vector length
0x0C    4     M                 u32, target out-degree at upper layers
0x10    4     efConstruction    u32, build-time candidate list size
0x14    4     efSearch          u32, default query candidate list size
0x18    1     metric            u8, see metrics table below
0x19    3     _padding          three zero bytes
0x1C    8     randomSeed        i64, level-assignment PRNG seed
0x24    8     count             u64, number of slots (live + tombstoned)
0x2C    8     liveCount         u64, count minus tombstones
0x34    8     entryPointId      i64, -1 if empty
0x3C    4     topLayer          i32, max layer present in the graph

vector block (count entries, in slot order):
  ─────── ───── ───────────────
  0       8     id              i64, user-provided
  8       4     level           u32, max layer this node lives at
  12      4     deleted         u32, 0 = live, 1 = tombstoned
  16    4·dim   vector          f32[dimension]

graph block (count entries, in slot order; per-layer lists):
  for each layer 0..level (variable per node):
    0       4     neighborCount  u32
    4    8·neighborCount  neighborIds  i64[neighborCount]
```

### metric byte values

| value | metric        |
| ----: | ------------- |
| 0     | l2 (squared)  |
| 1     | cosine        |
| 2     | dot product   |

### sizing

For a collection with `N` vectors of dimension `D` and average level `L`
(typically 1/(M-1) for HNSW), the on-disk size is roughly:

```
header:            ~64 bytes
vector block:      N · (16 + 4D) bytes
graph block:       N · (M + 2M·avgLayers) · 8 bytes  (very approximate)
```

For 100k vectors of dim 128 with M=16, that's ~52 MB of vectors plus ~14 MB
of graph. Tombstoned slots still occupy space until the next compaction
(currently a v2 concern).

## wal.log

Append-only file of insert and delete operations. Used to durably
capture writes between checkpoints.

```
record layout (repeats):
  Offset  Size       Field   Notes
  ─────── ──────     ─────── ───────────────────────────────
  0       4          length  u32, bytes that follow this field (including crc)
  4       1          op      u8, 0 = insert, 1 = delete
  5       8          id      i64
  13    [4·dim|0]    vector  f32[dim] for op=insert; absent for op=delete
  ...     4          crc32   u32, computed over [op, id, vector]
```

### invariants

- WAL records are written before the corresponding mutation is applied
  to the in-memory index. A successful `add()` therefore guarantees the
  vector is durable on disk.
- `fsync` mode is configurable per server. The default is fsync-per-write,
  which is the slowest but most durable option.
- Records are NEVER edited or rewritten — only appended. After a
  successful checkpoint, the WAL is truncated to zero bytes.

### corruption / truncation behavior

The replayer treats any of the following as the end of the log:

1. The 4-byte `length` field cannot be read fully.
2. The declared length is implausible (`<= 4`, larger than the remaining
   file, or larger than 64 MiB).
3. The payload + crc cannot be read fully.
4. The recorded crc doesn't match a freshly-computed CRC32 over the payload.
5. The `op` byte is unknown.

In all cases the replay stops cleanly and the index is opened with
whatever records were valid up to that point. This matches the
semantics tested by `CorruptionTest` and `CrashRecoveryTest`.

## checkpointing

`IndexStorage.flush()`:

1. Take a deep snapshot of the in-memory index.
2. Write the snapshot to `index.vex.tmp`.
3. `fsync` the tmp file.
4. Atomically rename `index.vex.tmp` → `index.vex` (`Files.move` with
   `ATOMIC_MOVE`).
5. Truncate `wal.log` to zero bytes and fsync the directory.

A crash at any step leaves a recoverable state: either the previous
`index.vex` is intact and the WAL still contains the post-checkpoint
records, or the new `index.vex` is in place and the WAL is empty.
