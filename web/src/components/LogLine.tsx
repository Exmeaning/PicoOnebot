import type { LogEntry, LogLevel } from "../lib/types";
import { fmtTime } from "../lib/format";
import { cn } from "../utils/cn";

export function LevelPill({ level }: { level: LogLevel }) {
  const map: Record<LogLevel, string> = {
    debug: "bg-slate-100 text-slate-500",
    info: "bg-sky-100 text-sky-700",
    warn: "bg-amber-100 text-amber-700",
    error: "bg-rose-100 text-rose-600",
  };
  return <span className={cn("inline-flex w-12 shrink-0 justify-center rounded-md px-1.5 py-0.5 font-mono text-[10px] font-extrabold uppercase", map[level])}>{level}</span>;
}

export function LogLine({ entry, highlight }: { entry: LogEntry; highlight?: string }) {
  const bg: Record<LogLevel, string> = {
    debug: "",
    info: "",
    warn: "bg-amber-50/70",
    error: "bg-rose-50/80",
  };
  return (
    <div className={cn("flex items-start gap-3 rounded-xl px-3 py-1.5 font-mono text-[12px] leading-relaxed transition hover:bg-pico-softer", bg[entry.level])}>
      <span className="shrink-0 text-pico-muted">{fmtTime(entry.ts)}</span>
      <LevelPill level={entry.level} />
      <span className="shrink-0 font-bold text-pico-deep">[{entry.tag}]</span>
      <span className="min-w-0 flex-1 break-all text-pico-ink">{highlight ? <Mark text={entry.msg} q={highlight} /> : entry.msg}</span>
    </div>
  );
}

function Mark({ text, q }: { text: string; q: string }) {
  const idx = text.toLowerCase().indexOf(q.toLowerCase());
  if (idx < 0) return <>{text}</>;
  return (
    <>
      {text.slice(0, idx)}
      <mark className="rounded bg-pico/30 px-0.5 text-pico-ink">{text.slice(idx, idx + q.length)}</mark>
      {text.slice(idx + q.length)}
    </>
  );
}
