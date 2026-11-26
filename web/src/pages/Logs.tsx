import { useEffect, useMemo, useRef, useState } from "react";
import { Search, Pause, Play, Trash2, Download, ArrowDownToLine, Wifi, WifiOff } from "lucide-react";
import { Badge, Button, Card, Input, PageHeader, useToast } from "../components/ui";
import { LogLine } from "../components/LogLine";
import { ApiError, api, isAbortError } from "../lib/api";
import type { LogEntry, LogLevel } from "../lib/types";
import { fmtDateTime } from "../lib/format";
import { cn } from "../utils/cn";

const LEVELS: LogLevel[] = ["debug", "info", "warn", "error"];
const MAX = 2000;

function mergeLogs(existing: LogEntry[], incoming: LogEntry[]): LogEntry[] {
  if (incoming.length === 0) return existing;
  const byId = new Map<number, LogEntry>();
  for (const item of existing) byId.set(item.id, item);
  for (const item of incoming) byId.set(item.id, item);
  const merged = Array.from(byId.values()).sort((a, b) => a.id - b.id);
  return merged.length > MAX ? merged.slice(-MAX) : merged;
}

export function Logs() {
  const [logs, setLogs] = useState<LogEntry[]>([]);
  const [paused, setPaused] = useState(false);
  const [bufferedCount, setBufferedCount] = useState(0);
  const [levels, setLevels] = useState<Set<LogLevel>>(() => new Set(LEVELS));
  const [q, setQ] = useState("");
  const [tag, setTag] = useState<string>("");
  const [connected, setConnected] = useState(false);
  const [autoScroll, setAutoScroll] = useState(true);
  const toast = useToast();

  const buffer = useRef<Map<number, LogEntry>>(new Map());
  const visibleIds = useRef<Set<number>>(new Set());
  const pausedRef = useRef(false);
  const clearGen = useRef(0);
  const box = useRef<HTMLDivElement>(null);

  useEffect(() => {
    visibleIds.current = new Set(logs.map((l) => l.id));
  }, [logs]);

  useEffect(() => {
    pausedRef.current = paused;
    if (!paused && buffer.current.size > 0) {
      const flushed = Array.from(buffer.current.values());
      buffer.current.clear();
      setBufferedCount(0);
      setLogs((prev) => mergeLogs(prev, flushed));
    }
  }, [paused]);

  useEffect(() => {
    let alive = true;
    const controller = new AbortController();
    const loadGen = clearGen.current;

    const pushToBuffer = (entries: LogEntry[]) => {
      let changed = false;
      for (const entry of entries) {
        if (visibleIds.current.has(entry.id) || buffer.current.has(entry.id)) continue;
        buffer.current.set(entry.id, entry);
        if (buffer.current.size > MAX) {
          const oldestKey = buffer.current.keys().next().value;
          if (oldestKey !== undefined) buffer.current.delete(oldestKey);
        }
        changed = true;
      }
      if (changed) setBufferedCount(buffer.current.size);
    };

    api
      .logs(300, controller.signal)
      .then((snapshot) => {
        if (!alive || controller.signal.aborted || clearGen.current !== loadGen) return;
        if (pausedRef.current) {
          pushToBuffer(snapshot);
        } else {
          setLogs((prev) => mergeLogs(prev, snapshot));
        }
      })
      .catch((err) => {
        if (!alive || isAbortError(err)) return;
        if (err instanceof ApiError && (err.status === 401 || err.status === 428)) return;
        toast.push("error", (err as Error).message || "加载历史日志失败");
      });

    const off = api.subscribeLogs(
      (entry) => {
        if (!alive) return;
        if (pausedRef.current) {
          pushToBuffer([entry]);
          return;
        }
        setLogs((prev) => {
          if (prev.length > 0 && prev[prev.length - 1].id < entry.id) {
            return prev.length >= MAX ? [...prev.slice(prev.length - MAX + 1), entry] : [...prev, entry];
          }
          if (prev.some((x) => x.id === entry.id)) return prev;
          return mergeLogs(prev, [entry]);
        });
      },
      (ok) => {
        if (alive) setConnected(ok);
      }
    );

    return () => {
      alive = false;
      controller.abort();
      off();
    };
  }, [toast]);

  useEffect(() => {
    if (autoScroll && box.current) box.current.scrollTop = box.current.scrollHeight;
  }, [logs, autoScroll]);

  const tags = useMemo(() => Array.from(new Set(logs.map((l) => l.tag))).sort(), [logs]);
  const filtered = useMemo(
    () =>
      logs.filter(
        (l) =>
          levels.has(l.level) &&
          (!tag || l.tag === tag) &&
          (!q || l.msg.toLowerCase().includes(q.toLowerCase()) || l.tag.toLowerCase().includes(q.toLowerCase()))
      ),
    [logs, levels, tag, q]
  );
  const counts = useMemo(() => {
    const c: Record<LogLevel, number> = { debug: 0, info: 0, warn: 0, error: 0 };
    logs.forEach((l) => c[l.level]++);
    return c;
  }, [logs]);

  const toggleLevel = (lv: LogLevel) =>
    setLevels((s) => {
      const n = new Set(s);
      if (n.has(lv)) n.delete(lv);
      else n.add(lv);
      return n;
    });

  const clearLogs = () => {
    clearGen.current += 1;
    buffer.current.clear();
    visibleIds.current.clear();
    setBufferedCount(0);
    setLogs([]);
  };

  const exportLogs = () => {
    const text = filtered.map((l) => `${fmtDateTime(l.ts)} [${l.level.toUpperCase()}] [${l.tag}] ${l.msg}`).join("\n");
    const blob = new Blob([text], { type: "text/plain;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `picoonebot-${Date.now()}.log`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    window.setTimeout(() => URL.revokeObjectURL(url), 1000);
  };

  const onScroll = () => {
    const el = box.current;
    if (!el) return;
    const atBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 40;
    setAutoScroll(atBottom);
  };

  return (
    <div>
      <PageHeader
        title="实时日志"
        desc="通过 WebSocket 实时推送，支持等级过滤、关键字搜索与导出"
        action={
          <Badge tone={connected ? "green" : "gray"}>
            {connected ? <Wifi className="h-3 w-3" /> : <WifiOff className="h-3 w-3" />}
            {connected ? "日志流已连接" : "日志流断开"}
          </Badge>
        }
      />

      <Card className="flex h-[calc(100vh-15rem)] min-h-[520px] flex-col">
        {/* toolbar */}
        <div className="flex flex-wrap items-center gap-2 border-b border-pico-line/70 px-4 py-3">
          <div className="relative min-w-[200px] flex-1">
            <Search className="pointer-events-none absolute left-3.5 top-1/2 h-4 w-4 -translate-y-1/2 text-pico-muted" />
            <Input className="h-9 pl-10" placeholder="搜索关键字…" value={q} onChange={(e) => setQ(e.target.value)} />
          </div>
          <div className="flex items-center gap-1 rounded-2xl bg-pico-softer p-1">
            {LEVELS.map((lv) => (
              <button
                key={lv}
                onClick={() => toggleLevel(lv)}
                className={cn(
                  "pico-ring rounded-xl px-2.5 py-1 font-mono text-[11px] font-extrabold uppercase transition",
                  levels.has(lv)
                    ? lv === "error"
                      ? "bg-rose-100 text-rose-600"
                      : lv === "warn"
                        ? "bg-amber-100 text-amber-700"
                        : lv === "info"
                          ? "bg-sky-100 text-sky-700"
                          : "bg-white text-slate-500"
                    : "text-pico-muted/60 hover:text-pico-muted"
                )}
              >
                {lv} <span className="ml-0.5 opacity-60">{counts[lv]}</span>
              </button>
            ))}
          </div>
          <select
            value={tag}
            onChange={(e) => setTag(e.target.value)}
            className="pico-ring h-9 rounded-2xl border-2 border-pico-line bg-white/80 px-3 text-xs font-bold text-pico-ink"
          >
            <option value="">全部模块</option>
            {tags.map((t) => (
              <option key={t} value={t}>
                {t}
              </option>
            ))}
          </select>
          <div className="ml-auto flex items-center gap-1">
            <Button variant={paused ? "primary" : "soft"} size="sm" onClick={() => setPaused((p) => !p)}>
              {paused ? <Play className="h-3.5 w-3.5" /> : <Pause className="h-3.5 w-3.5" />}
              {paused ? `继续 (${bufferedCount})` : "暂停"}
            </Button>
            <Button variant="ghost" size="icon" aria-label="导出" onClick={exportLogs}>
              <Download className="h-4 w-4" />
            </Button>
            <Button variant="ghost" size="icon" aria-label="清空" onClick={clearLogs}>
              <Trash2 className="h-4 w-4" />
            </Button>
          </div>
        </div>

        {/* log body */}
        <div ref={box} onScroll={onScroll} className="relative flex-1 overflow-y-auto p-3">
          {filtered.length === 0 ? (
            <div className="flex h-full items-center justify-center text-sm text-pico-muted">没有匹配的日志</div>
          ) : (
            filtered.map((l) => <LogLine key={l.id} entry={l} highlight={q} />)
          )}
        </div>

        {!autoScroll ? (
          <button
            onClick={() => {
              setAutoScroll(true);
              if (box.current) box.current.scrollTop = box.current.scrollHeight;
            }}
            className="absolute bottom-14 left-1/2 flex -translate-x-1/2 items-center gap-1.5 rounded-full bg-gradient-to-r from-pico to-pico-deep px-4 py-1.5 text-xs font-extrabold text-white shadow-cute"
          >
            <ArrowDownToLine className="h-3.5 w-3.5" /> 回到底部
          </button>
        ) : null}

        <div className="flex items-center justify-between border-t border-pico-line/70 px-4 py-2 text-[11px] text-pico-muted">
          <span>
            显示 {filtered.length} / {logs.length} 条{paused ? ` · 已暂停，缓冲 ${bufferedCount} 条` : ""}
          </span>
          <span>缓存上限 {MAX} 条</span>
        </div>
      </Card>
    </div>
  );
}
