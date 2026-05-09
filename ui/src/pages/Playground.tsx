import { useEffect, useState } from "react";
import { api } from "../api";
import type { CollectionInfo, QueryHit } from "../types";

export default function Playground() {
  const [collections, setCollections] = useState<string[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [info, setInfo] = useState<CollectionInfo | null>(null);
  const [error, setError] = useState<string | null>(null);

  // Create-collection form state
  const [newName, setNewName] = useState("");
  const [newDim, setNewDim] = useState(4);
  const [newMetric, setNewMetric] = useState("cosine");
  const [newQuantized, setNewQuantized] = useState(false);

  // Query form state
  const [queryVec, setQueryVec] = useState("[1.0, 0.0, 0.0, 0.0]");
  const [queryK, setQueryK] = useState(5);
  const [queryEf, setQueryEf] = useState<number | "">("");
  const [queryFilter, setQueryFilter] = useState("");
  const [results, setResults] = useState<QueryHit[] | null>(null);
  const [querying, setQuerying] = useState(false);

  // Quick upsert form
  const [upId, setUpId] = useState(1);
  const [upVec, setUpVec] = useState("[1.0, 0.0, 0.0, 0.0]");
  const [upPayload, setUpPayload] = useState('{"category": "books"}');

  const refreshCollections = async () => {
    try {
      const list = await api.listCollections();
      setCollections(list);
      if (selected && !list.includes(selected)) setSelected(null);
    } catch (e) {
      setError(asMessage(e));
    }
  };

  useEffect(() => {
    refreshCollections();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (!selected) {
      setInfo(null);
      return;
    }
    api
      .getCollection(selected)
      .then(setInfo)
      .catch((e) => setError(asMessage(e)));
  }, [selected]);

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    try {
      await api.createCollection({
        name: newName,
        dim: newDim,
        metric: newMetric,
        quantization: newQuantized ? "scalar" : "none",
      });
      setNewName("");
      await refreshCollections();
      setSelected(newName);
    } catch (err) {
      setError(asMessage(err));
    }
  };

  const handleDrop = async (name: string) => {
    if (!confirm(`Drop collection "${name}"? This deletes its data dir.`))
      return;
    try {
      await api.dropCollection(name);
      await refreshCollections();
    } catch (e) {
      setError(asMessage(e));
    }
  };

  const handleUpsert = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selected) return;
    setError(null);
    try {
      await api.upsert(selected, {
        id: upId,
        vector: parseVector(upVec),
        payload: upPayload.trim() ? JSON.parse(upPayload) : {},
      });
      setUpId((n) => n + 1);
      const next = await api.getCollection(selected);
      setInfo(next);
    } catch (e) {
      setError(asMessage(e));
    }
  };

  const handleQuery = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selected) return;
    setError(null);
    setResults(null);
    setQuerying(true);
    try {
      const hits = await api.query(selected, {
        vector: parseVector(queryVec),
        k: queryK,
        efSearch: queryEf === "" ? undefined : Number(queryEf),
        filter: queryFilter || undefined,
      });
      setResults(hits);
    } catch (e) {
      setError(asMessage(e));
    } finally {
      setQuerying(false);
    }
  };

  return (
    <div className="space-y-8">
      <div>
        <h1 className="text-3xl font-mono font-bold mb-2">Playground</h1>
        <p className="text-zinc-400 text-sm">
          Create a collection, upsert a few vectors with payloads, run a
          filtered query. Everything talks to the same Vex server that's
          serving this page.
        </p>
      </div>

      {error && (
        <div className="rounded-md border border-rose-700 bg-rose-950/40 px-4 py-3 text-sm text-rose-200">
          <span className="font-mono mr-2">error</span>
          {error}
          <button
            onClick={() => setError(null)}
            className="float-right text-rose-400 hover:text-rose-200"
            aria-label="dismiss"
          >
            ×
          </button>
        </div>
      )}

      <div className="grid lg:grid-cols-[300px_1fr] gap-6">
        {/* Left: collections */}
        <aside className="space-y-4">
          <Panel title="Collections">
            {collections.length === 0 ? (
              <p className="text-sm text-zinc-500 italic">none yet</p>
            ) : (
              <ul className="space-y-1">
                {collections.map((c) => (
                  <li key={c} className="flex items-center gap-2 group">
                    <button
                      onClick={() => setSelected(c)}
                      className={`flex-1 text-left text-sm font-mono px-2 py-1 rounded transition-colors ${
                        selected === c
                          ? "bg-amber-400/10 text-amber-300"
                          : "text-zinc-300 hover:bg-zinc-900"
                      }`}
                    >
                      {c}
                    </button>
                    <button
                      onClick={() => handleDrop(c)}
                      title="drop"
                      className="opacity-0 group-hover:opacity-100 text-zinc-500 hover:text-rose-400 text-sm px-1"
                    >
                      ✕
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </Panel>

          <Panel title="Create collection">
            <form className="space-y-3" onSubmit={handleCreate}>
              <Field label="name">
                <input
                  required
                  value={newName}
                  onChange={(e) => setNewName(e.target.value)}
                  pattern="[a-zA-Z0-9_\-]+"
                  className="input"
                  placeholder="my-collection"
                />
              </Field>
              <Field label="dim">
                <input
                  type="number"
                  min={1}
                  required
                  value={newDim}
                  onChange={(e) => setNewDim(Number(e.target.value))}
                  className="input"
                />
              </Field>
              <Field label="metric">
                <select
                  value={newMetric}
                  onChange={(e) => setNewMetric(e.target.value)}
                  className="input"
                >
                  <option value="cosine">cosine</option>
                  <option value="l2">l2</option>
                  <option value="dot">dot</option>
                </select>
              </Field>
              <label className="flex items-center gap-2 text-sm text-zinc-300">
                <input
                  type="checkbox"
                  checked={newQuantized}
                  onChange={(e) => setNewQuantized(e.target.checked)}
                />
                int8 quantization
              </label>
              <button className="btn-primary w-full" type="submit">
                Create
              </button>
            </form>
          </Panel>
        </aside>

        {/* Right: info + upsert + query */}
        <section className="space-y-4">
          {selected && info ? (
            <>
              <Panel
                title={`${info.name}`}
                right={
                  <span className="text-xs text-zinc-500 font-mono">
                    {info.size} vector{info.size === 1 ? "" : "s"}
                  </span>
                }
              >
                <dl className="grid grid-cols-2 sm:grid-cols-4 gap-4 text-sm">
                  <Meta label="dim" value={String(info.dim)} />
                  <Meta label="metric" value={info.metric} />
                  <Meta label="M" value={String(info.M)} />
                  <Meta label="efC" value={String(info.efConstruction)} />
                </dl>
                {info.quantized && (
                  <div className="mt-3 inline-flex items-center gap-1 text-xs px-2 py-0.5 rounded-full border border-amber-500/40 bg-amber-500/10 text-amber-300 font-mono">
                    int8 quantized
                  </div>
                )}
              </Panel>

              <Panel title="Upsert a vector">
                <form className="space-y-3" onSubmit={handleUpsert}>
                  <div className="grid sm:grid-cols-[100px_1fr] gap-3">
                    <Field label="id">
                      <input
                        type="number"
                        value={upId}
                        onChange={(e) => setUpId(Number(e.target.value))}
                        className="input"
                      />
                    </Field>
                    <Field label="vector (JSON array)">
                      <input
                        value={upVec}
                        onChange={(e) => setUpVec(e.target.value)}
                        className="input font-mono"
                        placeholder={`[1.0, ${"0.0, ".repeat(
                          Math.min(info.dim - 1, 3)
                        )}...]`}
                      />
                    </Field>
                  </div>
                  <Field label="payload (JSON object, optional)">
                    <input
                      value={upPayload}
                      onChange={(e) => setUpPayload(e.target.value)}
                      className="input font-mono"
                      placeholder='{"category": "books"}'
                    />
                  </Field>
                  <button className="btn-secondary" type="submit">
                    Upsert
                  </button>
                </form>
              </Panel>

              <Panel title="Query">
                <form className="space-y-3" onSubmit={handleQuery}>
                  <Field label="query vector">
                    <input
                      value={queryVec}
                      onChange={(e) => setQueryVec(e.target.value)}
                      className="input font-mono"
                    />
                  </Field>
                  <div className="grid grid-cols-2 sm:grid-cols-3 gap-3">
                    <Field label="k">
                      <input
                        type="number"
                        min={1}
                        value={queryK}
                        onChange={(e) => setQueryK(Number(e.target.value))}
                        className="input"
                      />
                    </Field>
                    <Field label="efSearch (optional)">
                      <input
                        type="number"
                        min={1}
                        value={queryEf}
                        onChange={(e) =>
                          setQueryEf(
                            e.target.value === ""
                              ? ""
                              : Number(e.target.value)
                          )
                        }
                        className="input"
                        placeholder="50"
                      />
                    </Field>
                  </div>
                  <Field label="filter (optional)">
                    <input
                      value={queryFilter}
                      onChange={(e) => setQueryFilter(e.target.value)}
                      className="input font-mono"
                      placeholder='category = "books" AND year > 2020'
                    />
                  </Field>
                  <button
                    className="btn-primary"
                    type="submit"
                    disabled={querying}
                  >
                    {querying ? "querying…" : "Query"}
                  </button>
                </form>

                {results && (
                  <div className="mt-5">
                    <div className="text-xs text-zinc-500 uppercase tracking-wider mb-2">
                      {results.length} result{results.length === 1 ? "" : "s"}
                    </div>
                    {results.length === 0 ? (
                      <p className="text-sm text-zinc-500 italic">
                        no matches
                      </p>
                    ) : (
                      <div className="overflow-x-auto rounded border border-zinc-800">
                        <table className="w-full text-sm">
                          <thead className="bg-zinc-900 text-zinc-400">
                            <tr>
                              <th className="text-left px-3 py-2 font-medium">
                                id
                              </th>
                              <th className="text-left px-3 py-2 font-medium">
                                distance
                              </th>
                              <th className="text-left px-3 py-2 font-medium">
                                payload
                              </th>
                            </tr>
                          </thead>
                          <tbody>
                            {results.map((h) => (
                              <tr
                                key={h.id}
                                className="border-t border-zinc-800 font-mono"
                              >
                                <td className="px-3 py-2">{h.id}</td>
                                <td className="px-3 py-2 text-zinc-400">
                                  {h.distance.toFixed(4)}
                                </td>
                                <td className="px-3 py-2 text-zinc-400">
                                  {Object.keys(h.payload).length === 0
                                    ? "—"
                                    : JSON.stringify(h.payload)}
                                </td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      </div>
                    )}
                  </div>
                )}
              </Panel>
            </>
          ) : (
            <Panel title="Pick or create a collection">
              <p className="text-sm text-zinc-500">
                Create a new collection on the left, or click an existing one
                to start querying.
              </p>
            </Panel>
          )}
        </section>
      </div>
    </div>
  );
}

function Panel({
  title,
  right,
  children,
}: {
  title: string;
  right?: React.ReactNode;
  children: React.ReactNode;
}) {
  return (
    <div className="rounded-lg border border-zinc-800 bg-zinc-900/40 p-4">
      <div className="flex items-center justify-between mb-3">
        <h3 className="text-sm font-mono font-semibold text-zinc-100">
          {title}
        </h3>
        {right}
      </div>
      {children}
    </div>
  );
}

function Field({
  label,
  children,
}: {
  label: string;
  children: React.ReactNode;
}) {
  return (
    <label className="block">
      <div className="text-xs text-zinc-500 uppercase tracking-wider mb-1">
        {label}
      </div>
      {children}
    </label>
  );
}

function Meta({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <div className="text-xs text-zinc-500 uppercase tracking-wider">
        {label}
      </div>
      <div className="font-mono text-zinc-100 mt-0.5">{value}</div>
    </div>
  );
}

function parseVector(input: string): number[] {
  const parsed = JSON.parse(input);
  if (!Array.isArray(parsed)) throw new Error("vector must be a JSON array");
  return parsed.map((n) => Number(n));
}

function asMessage(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}
