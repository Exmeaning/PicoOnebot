import { useEffect, useState } from "react";
import { QrCode, MessageSquareText, Send, Clock3, Cpu, Users, UsersRound, Plug, Activity, ArrowRight, HeartPulse } from "lucide-react";
import { Badge, Button, Card, CardHeader, Stat } from "../components/ui";
import { api } from "../lib/api";
import type { BotStatus, ConnectionInfo, LogEntry } from "../lib/types";
import type { PageKey } from "../components/Shell";
import { fmtDuration, fmtNumber, fmtTime, greeting } from "../lib/format";
import { LevelPill } from "../components/LogLine";

export function Dashboard({ status, onNavigate }: { status: BotStatus | null; onNavigate: (p: PageKey) => void }) {
  const [conns, setConns] = useState<ConnectionInfo[]>([]);
  const [recent, setRecent] = useState<LogEntry[]>([]);
  const [history, setHistory] = useState<number[]>(() => Array.from({ length: 24 }, () => 0));

  useEffect(() => {
    let alive = true;
    const load = async () => {
      const [c, l] = await Promise.all([api.connections(), api.logs(8)]);
      if (!alive) return;
      setConns(c);
      setRecent(l.slice(-8).reverse());
    };
    load();
    const t = setInterval(load, 8000);
    return () => {
      alive = false;
      clearInterval(t);
    };
  }, []);

  useEffect(() => {
    if (!status) return;
    setHistory((h) => [...h.slice(1), status.msgRecv + status.msgSent]);
  }, [status]);

  const deltas = history.map((v, i) => (i === 0 ? 0 : Math.max(0, v - history[i - 1])));
  const max = Math.max(1, ...deltas);

  return (
    <div className="space-y-6">
      {/* hero */}
      <Card className="relative p-6 sm:p-8" decorated>
        <div className="relative max-w-xl">
          <Badge tone={status?.online ? "green" : "gray"}>
            <span className="h-1.5 w-1.5 rounded-full bg-current" />
            {status?.online ? "运行中" : "未连接"}
          </Badge>
          <h2 className="mt-3 text-2xl font-black tracking-tight text-pico-ink sm:text-3xl">
            {greeting()}，{status?.nickname ?? "主人"}
          </h2>
          <p className="mt-2 text-sm text-pico-muted">
            PicoOnebot 已稳定运行 <span className="font-extrabold text-pico-deep">{fmtDuration(status?.uptime ?? 0)}</span>
            ，期间共处理 <span className="font-extrabold text-pico-deep">{fmtNumber((status?.msgRecv ?? 0) + (status?.msgSent ?? 0))}</span> 条消息。
          </p>
          <div className="mt-5 flex flex-wrap gap-2">
            {status && !status.online ? (
              <Button onClick={() => onNavigate("qr")}>
                <QrCode className="h-4 w-4" /> 扫码登录 QQ
              </Button>
            ) : null}
            <Button variant={status && !status.online ? "soft" : "primary"} onClick={() => onNavigate("network")}>
              <Plug className="h-4 w-4" /> 管理连接
            </Button>
            <Button variant="soft" onClick={() => onNavigate("logs")}>
              查看日志 <ArrowRight className="h-4 w-4" />
            </Button>
          </div>
        </div>
      </Card>

      {/* stats */}
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <Stat label="收到消息" value={fmtNumber(status?.msgRecv ?? 0)} sub="自启动以来" icon={<MessageSquareText className="h-5 w-5" />} />
        <Stat label="发送消息" value={fmtNumber(status?.msgSent ?? 0)} sub="自启动以来" icon={<Send className="h-5 w-5" />} />
        <Stat label="运行时长" value={fmtDuration(status?.uptime ?? 0, true)} sub={`心跳 ${status ? fmtTime(status.lastHeartbeat) : "--"}`} icon={<Clock3 className="h-5 w-5" />} />
        <Stat label="资源占用" value={`${status?.memoryMb ?? 0} MB`} sub={`CPU ${status?.cpu ?? 0}%`} icon={<Cpu className="h-5 w-5" />} />
      </div>

      <div className="grid gap-6 lg:grid-cols-3">
        {/* account */}
        <Card className="lg:col-span-1">
          <CardHeader title="账号信息" desc="当前登录的 QQ 账号" icon={<HeartPulse className="h-4 w-4" />} />
          <div className="p-6 pt-4">
            <div className="flex items-center gap-4 rounded-3xl bg-pico-softer p-4">
              <div className="flex h-16 w-16 shrink-0 items-center justify-center overflow-hidden rounded-3xl bg-gradient-to-br from-pico to-pico-deep text-2xl font-black text-white shadow-cute ring-4 ring-white">
                {status?.avatar ? <img src={status.avatar} alt="" className="h-full w-full object-cover" /> : status?.nickname?.slice(0, 1) ?? "P"}
              </div>
              <div className="min-w-0">
                <p className="truncate text-lg font-black text-pico-ink">{status?.nickname ?? "--"}</p>
                <p className="font-mono text-xs text-pico-muted">{status?.selfId ?? "--"}</p>
                <Badge tone="pink" className="mt-1.5">{status?.platform ?? "--"}</Badge>
              </div>
            </div>
            <dl className="mt-4 grid grid-cols-2 gap-3">
              {[
                { k: "好友", v: fmtNumber(status?.friends ?? 0), i: Users },
                { k: "群聊", v: fmtNumber(status?.groups ?? 0), i: UsersRound },
              ].map(({ k, v, i: Icon }) => (
                <div key={k} className="flex items-center gap-3 rounded-2xl border border-pico-line bg-white/70 p-3">
                  <span className="flex h-9 w-9 items-center justify-center rounded-xl bg-pico-soft text-pico-deep">
                    <Icon className="h-4 w-4" />
                  </span>
                  <div>
                    <dt className="text-[11px] font-bold text-pico-muted">{k}</dt>
                    <dd className="text-base font-black text-pico-ink">{v}</dd>
                  </div>
                </div>
              ))}
            </dl>
            <div className="mt-4 space-y-2 text-xs">
              <Row k="PicoOnebot" v={`v${status?.version ?? "--"}`} />
              <Row k="内核" v={status?.qqVersion ?? "--"} />
              <Row k="协议" v={status?.protocol ?? "--"} />
            </div>
          </div>
        </Card>

        {/* activity */}
        <Card className="lg:col-span-2">
          <CardHeader
            title="消息活动"
            desc="最近数分钟的消息吞吐（每 5 秒采样）"
            icon={<Activity className="h-4 w-4" />}
            action={<Badge tone="pink">{conns.filter((c) => c.connected).length} 个连接在线</Badge>}
          />
          <div className="p-6 pt-4">
            <div className="flex h-36 items-end gap-1.5 rounded-3xl bg-pico-softer p-4">
              {deltas.map((d, i) => (
                <div key={i} className="group relative flex-1">
                  <div
                    className="w-full rounded-full bg-gradient-to-t from-pico to-pico-deep/80 transition-all duration-500"
                    style={{ height: `${Math.max(6, (d / max) * 100)}%`, opacity: 0.35 + (i / deltas.length) * 0.65 }}
                  />
                  <span className="pointer-events-none absolute -top-6 left-1/2 -translate-x-1/2 rounded-md bg-pico-ink px-1.5 py-0.5 text-[10px] font-bold text-white opacity-0 transition group-hover:opacity-100">
                    {d}
                  </span>
                </div>
              ))}
            </div>

            <div className="mt-5 grid gap-3 sm:grid-cols-2">
              {conns.length === 0 ? (
                <p className="text-xs text-pico-muted">还没有配置任何连接，去「网络配置」添加一个吧。</p>
              ) : (
                conns.map((c) => (
                  <div key={c.id} className="flex items-center gap-3 rounded-2xl border border-pico-line bg-white/70 p-3">
                    <span className={`h-2.5 w-2.5 shrink-0 rounded-full ${c.connected ? "bg-emerald-400" : "bg-slate-300"}`} />
                    <div className="min-w-0 flex-1">
                      <p className="truncate text-sm font-extrabold text-pico-ink">{c.name}</p>
                      <p className="truncate font-mono text-[11px] text-pico-muted">{c.peer}</p>
                    </div>
                    <Badge tone="gray">{kindLabel(c.kind)}</Badge>
                  </div>
                ))
              )}
            </div>
          </div>
        </Card>
      </div>

      {/* recent logs */}
      <Card>
        <CardHeader
          title="最近日志"
          desc="最新 8 条记录"
          icon={<Activity className="h-4 w-4" />}
          action={
            <Button variant="ghost" size="sm" onClick={() => onNavigate("logs")}>
              全部日志 <ArrowRight className="h-3.5 w-3.5" />
            </Button>
          }
        />
        <div className="p-6 pt-4">
          <ul className="divide-y divide-pico-line/70">
            {recent.map((l) => (
              <li key={l.id} className="flex items-start gap-3 py-2.5 text-xs">
                <span className="w-16 shrink-0 font-mono text-pico-muted">{fmtTime(l.ts)}</span>
                <LevelPill level={l.level} />
                <span className="shrink-0 font-bold text-pico-deep">[{l.tag}]</span>
                <span className="min-w-0 flex-1 truncate text-pico-ink">{l.msg}</span>
              </li>
            ))}
          </ul>
        </div>
      </Card>
    </div>
  );
}

function Row({ k, v }: { k: string; v: string }) {
  return (
    <div className="flex items-center justify-between gap-3 border-b border-dashed border-pico-line pb-2 last:border-0">
      <span className="font-bold text-pico-muted">{k}</span>
      <span className="truncate font-mono text-pico-ink">{v}</span>
    </div>
  );
}

export function kindLabel(kind: ConnectionInfo["kind"]) {
  return { "ws-server": "正向 WS", "ws-client": "反向 WS", "http-server": "HTTP 服务", "http-client": "HTTP 上报" }[kind];
}
