import { NavLink, Outlet } from "react-router-dom";
import { useEffect, useState } from "react";

export default function App() {
  const [healthy, setHealthy] = useState<boolean | null>(null);

  useEffect(() => {
    let cancelled = false;
    const tick = async () => {
      try {
        const r = await fetch("/health");
        if (!cancelled) setHealthy(r.ok);
      } catch {
        if (!cancelled) setHealthy(false);
      }
    };
    tick();
    const id = setInterval(tick, 10_000);
    return () => {
      cancelled = true;
      clearInterval(id);
    };
  }, []);

  return (
    <div className="min-h-screen flex flex-col">
      <header className="border-b border-zinc-800 bg-zinc-950/80 backdrop-blur sticky top-0 z-10">
        <div className="max-w-6xl mx-auto px-6 py-4 flex items-center justify-between">
          <NavLink to="/" className="flex items-center gap-3 group">
            <span className="text-2xl font-mono font-bold tracking-tight bg-gradient-to-r from-amber-300 to-amber-500 bg-clip-text text-transparent">
              Vex
            </span>
            <span className="text-zinc-500 text-sm hidden sm:inline">
              vector database
            </span>
          </NavLink>

          <nav className="flex items-center gap-6">
            <NavLink
              to="/"
              end
              className={({ isActive }) =>
                `text-sm transition-colors ${
                  isActive
                    ? "text-amber-300"
                    : "text-zinc-400 hover:text-zinc-100"
                }`
              }
            >
              Overview
            </NavLink>
            <NavLink
              to="/playground"
              className={({ isActive }) =>
                `text-sm transition-colors ${
                  isActive
                    ? "text-amber-300"
                    : "text-zinc-400 hover:text-zinc-100"
                }`
              }
            >
              Playground
            </NavLink>
            <a
              href="https://github.com/AshuGuptaz/Vex"
              target="_blank"
              rel="noreferrer"
              className="text-sm text-zinc-400 hover:text-zinc-100 transition-colors"
            >
              GitHub ↗
            </a>
            <span
              title={
                healthy === null
                  ? "checking..."
                  : healthy
                    ? "API healthy"
                    : "API unreachable"
              }
              className={`inline-block w-2 h-2 rounded-full ${
                healthy === null
                  ? "bg-zinc-500"
                  : healthy
                    ? "bg-emerald-500"
                    : "bg-rose-500"
              }`}
            />
          </nav>
        </div>
      </header>

      <main className="flex-1 max-w-6xl mx-auto w-full px-6 py-10">
        <Outlet />
      </main>

      <footer className="border-t border-zinc-800 mt-16">
        <div className="max-w-6xl mx-auto px-6 py-6 text-xs text-zinc-500 flex items-center justify-between">
          <span>
            HNSW from{" "}
            <a
              className="hover:text-zinc-300 underline-offset-2 hover:underline"
              href="https://arxiv.org/abs/1603.09320"
              target="_blank"
              rel="noreferrer"
            >
              Malkov & Yashunin (2016)
            </a>
          </span>
          <span className="font-mono">
            ui v{__BUILD_ID__} · MIT
          </span>
        </div>
      </footer>
    </div>
  );
}
