import { Link } from "react-router-dom";

const SIFT = [
  { ef: 16, recall: 0.826, ms: 0.17 },
  { ef: 32, recall: 0.92, ms: 0.23 },
  { ef: 64, recall: 0.972, ms: 0.44 },
  { ef: 128, recall: 0.992, ms: 0.75 },
  { ef: 256, recall: 0.998, ms: 1.3 },
];

export default function Home() {
  return (
    <div className="space-y-16">
      <section className="space-y-6">
        <h1 className="text-5xl sm:text-6xl font-mono font-bold tracking-tight leading-none">
          A vector database,
          <br />
          <span className="bg-gradient-to-r from-amber-300 to-amber-500 bg-clip-text text-transparent">
            from the paper.
          </span>
        </h1>
        <p className="text-zinc-400 text-lg max-w-2xl leading-relaxed">
          HNSW indexing, mmap persistence, write-ahead log, scalar quantization,
          REST API with metadata-filtered search — all in Java 17, all
          implemented from{" "}
          <a
            className="text-zinc-200 underline-offset-4 hover:underline"
            href="https://arxiv.org/abs/1603.09320"
            target="_blank"
            rel="noreferrer"
          >
            Malkov &amp; Yashunin (2016)
          </a>
          . No third-party ANN library.
        </p>
        <div className="flex flex-wrap gap-3">
          <Link
            to="/playground"
            className="inline-flex items-center gap-2 px-4 py-2 rounded-md bg-amber-400 text-zinc-950 font-medium text-sm hover:bg-amber-300 transition-colors"
          >
            Try it →
          </Link>
          <a
            href="https://github.com/AshuGuptaz/Vex"
            target="_blank"
            rel="noreferrer"
            className="inline-flex items-center gap-2 px-4 py-2 rounded-md border border-zinc-700 text-sm text-zinc-200 hover:bg-zinc-900 transition-colors"
          >
            View on GitHub
          </a>
        </div>
      </section>

      <section className="grid sm:grid-cols-3 gap-4">
        <Stat label="Recall@10 on SIFT-1M, ef=64" value="0.972" />
        <Stat label="P99 query latency, 100k vec" value="428 µs" />
        <Stat label="Memory compression (int8)" value="4×" />
      </section>

      <section className="space-y-4">
        <h2 className="text-2xl font-mono font-semibold">SIFT-1M benchmark</h2>
        <p className="text-zinc-400 text-sm max-w-2xl">
          Built the full 1,000,000-vector base, ran 1,000 queries from the
          official query set, scored against the published ground truth. M=16,
          efConstruction=200. Build wall-time: ~19 minutes.
        </p>
        <div className="overflow-x-auto rounded-lg border border-zinc-800">
          <table className="w-full text-sm">
            <thead className="bg-zinc-900 text-zinc-400">
              <tr>
                <th className="text-left px-4 py-2 font-medium">efSearch</th>
                <th className="text-left px-4 py-2 font-medium">recall@10</th>
                <th className="text-left px-4 py-2 font-medium">ms / query</th>
              </tr>
            </thead>
            <tbody>
              {SIFT.map((row) => (
                <tr
                  key={row.ef}
                  className="border-t border-zinc-800 font-mono"
                >
                  <td className="px-4 py-2">{row.ef}</td>
                  <td
                    className={`px-4 py-2 ${
                      row.recall >= 0.97 ? "text-emerald-400" : ""
                    }`}
                  >
                    {row.recall.toFixed(3)}
                  </td>
                  <td className="px-4 py-2 text-zinc-400">{row.ms}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <section className="space-y-4">
        <h2 className="text-2xl font-mono font-semibold">Architecture</h2>
        <div className="grid md:grid-cols-3 gap-4">
          <Layer
            title="core/"
            body="HnswIndex implementing Algorithms 1-5 of the paper. Distance metrics, scalar quantization, multi-layer graph, single-writer multi-reader concurrency."
          />
          <Layer
            title="storage/"
            body="IndexStorage = HnswIndex + mmap'd checkpoint + append-only WAL with CRC32 per record + atomic checkpoint rename. Crash-tested via child JVM Runtime.halt."
          />
          <Layer
            title="server/"
            body="Spring Boot 3 REST API. Hand-rolled recursive-descent filter parser (no ANTLR). Per-collection persistence and lifecycle. This UI."
          />
        </div>
      </section>
    </div>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-lg border border-zinc-800 p-4 bg-zinc-900/40">
      <div className="text-xs text-zinc-500 uppercase tracking-wider mb-2">
        {label}
      </div>
      <div className="text-3xl font-mono font-semibold">{value}</div>
    </div>
  );
}

function Layer({ title, body }: { title: string; body: string }) {
  return (
    <div className="rounded-lg border border-zinc-800 p-4 bg-zinc-900/40">
      <div className="font-mono text-amber-300 text-sm mb-2">{title}</div>
      <p className="text-zinc-400 text-sm leading-relaxed">{body}</p>
    </div>
  );
}
