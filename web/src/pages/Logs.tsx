import { useEffect, useMemo, useRef, useState } from "react";
import { Search, Pause, Play, Trash2, Download, ArrowDownToLine, Wifi, WifiOff } from "lucide-react";
import { Badge, Button, Card, Input, PageHeader } from "../components/ui";
import { LogLine } from "../components/LogLine";
import { api } from "../lib/api";
import type { LogEntry, LogLevel } from "../lib/types";
import { fmtDateTime } from "../lib/format";
import { cn } from "../utils/cn";

const LEVELS: LogLevel[] = ["debug", "info", "warn", "error"];
const MAX = 2000;

export function Logs() {
  const [logs, setLogs] = useState<LogEntry[]>([]);
  const [paused, setPaused] = useState(false);
  const [levels, setLevels] = useState<Set<LogLevel>>(new Set(LEVELS));
  const [q, setQ] = useState("");
  const [tag, setTag] = useState<string>("");
  const [connected, setConnected] = useState(false);
  const [autoScroll, setAutoScroll] = useState(true);
  const buffer = useRef<LogEntry[]>([]);
  const pausedRef = useRef(false);
  const box = useRef<HTMLDivElement>(null);

  useEffect(() => {
    pausedRef.current = paused;
    if (!paused && buffer.current.length) {
      const b = buffer.current;
      buffer.current = [];
      setLogs((l) => [...l, ...b].slice(-MAX));
    }
  }, [paused]);

  useEffect(() => {
    let alive = true;
    api.logs(300).then((l) => alive && setLogs(l));
    const off = api.subscribeLogs(
      (entry) => {
        if (pausedRef.current) buffer.current.push(entry);
        else setLogs((l) => (l.length >= MAX ? [...l.slice(1), entry] : [...l, entry]));
      },
      (ok) => setConnected(ok)
    );
    return () => {
      alive = false;
      off();
    };
  }, []);

  useEffect(() => {
    if (autoScroll && box.current) box.current.scrollTop = box.current.scrollHeight;
  }, [logs, autoScroll]);

  const tags = useMemo(() => Array.from(new Set(logs.map((l) => l.tag))).sort(), [logs]);
  const filtered = useMemo(
    () => logs.filter((l) => levels.has(l.level) && (!tag || l.tag === tag) && (!q || l.msg.toLowerCase().includes(q.toLowerCase()) || l.tag.toLowerCase().includes(q.toLowerCase()))),
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

  const exportLogs = () => {
    const text = filtered.map((l) => `${fmtDateTime(l.ts)} [${l.level.toUpperCase()}] [${l.tag}] ${l.msg}`).join("\n");
    const blob = new Blob([text], { type: "text/plain;charset=utf-8" });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = `picoonebot-${Date.now()}.log`;
    a.click();
    URL.revokeObjectURL(a.href);
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
              {paused ? `继续 (${buffer.current.length})` : "暂停"}
            </Button>
            <Button variant="ghost" size="icon" aria-label="导出" onClick={exportLogs}>
              <Download className="h-4 w-4" />
            </Button>
            <Button variant="ghost" size="icon" aria-label="清空" onClick={() => setLogs([])}>
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
            显示 {filtered.length} / {logs.length} 条{paused ? ` · 已暂停，缓冲 ${buffer.current.length} 条` : ""}
          </span>
          <span>缓存上限 {MAX} 条</span>
        </div>
      </Card>
    </div>
  );
}
