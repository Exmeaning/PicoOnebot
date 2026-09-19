import { useEffect, useMemo, useState } from "react";
import { Plus, Pencil, Trash2, Save, RotateCcw, Radio, Cable, Globe, SendHorizontal, Copy } from "lucide-react";
import { Badge, Button, Card, Empty, Field, Input, Modal, PageHeader, Select, Toggle, useToast } from "../components/ui";
import { api } from "../lib/api";
import type { AppConfig, HttpClient, HttpServer, NetworkConfig, WsClient, WsServer } from "../lib/types";
import { fmtApplied } from "../lib/format";
import { cn } from "../utils/cn";

type Kind = keyof NetworkConfig;
type AnyConn = WsServer | WsClient | HttpServer | HttpClient;

const KINDS: { key: Kind; label: string; desc: string; icon: typeof Radio }[] = [
  { key: "wsServers", label: "正向 WebSocket", desc: "PicoOnebot 作为服务端，等待框架连接", icon: Radio },
  { key: "wsClients", label: "反向 WebSocket", desc: "PicoOnebot 主动连接到框架地址", icon: Cable },
  { key: "httpServers", label: "HTTP 服务端", desc: "提供 HTTP API 调用", icon: Globe },
  { key: "httpClients", label: "HTTP 上报", desc: "把事件 POST 到指定地址", icon: SendHorizontal },
];

const uid = () => Math.random().toString(36).slice(2, 10);

function blank(kind: Kind): AnyConn {
  const base = { id: uid(), name: "", enabled: true, messageFormat: "array" as const, debug: false };
  switch (kind) {
    case "wsServers":
      return { ...base, name: "新的正向 WS", host: "", port: 3001, token: "", heartbeat: 30000, reportSelfMessage: false };
    case "wsClients":
      return { ...base, name: "新的反向 WS", url: "", token: "", heartbeat: 30000, reconnectInterval: 5000, reportSelfMessage: false };
    case "httpServers":
      return { ...base, name: "新的 HTTP 服务", host: "", port: 3000, token: "", cors: true, websocket: false };
    case "httpClients":
      return { ...base, name: "新的 HTTP 上报", url: "", token: "", secret: "", heartbeat: 30000, reportSelfMessage: false };
  }
}

function isLoopbackHost(value: string) {
  const host = value.trim().replace(/^\[|\]$/g, "").toLowerCase();
  if (host === "localhost" || host.endsWith(".localhost") || host === "::1" || host === "0:0:0:0:0:0:0:1") return true;
  if (host.startsWith("127.")) {
    const parts = host.split(".").map(Number);
    return parts.length === 4 && parts.every((part) => Number.isInteger(part) && part >= 0 && part <= 255);
  }
  return host.startsWith("::ffff:127.");
}

function connectionError(kind: Kind, item: AnyConn) {
  if (!item.enabled) return "";
  let host = "";
  if ("host" in item) host = item.host.trim();
  else {
    try {
      host = new URL(item.url).hostname;
    } catch {
      return "请输入有效的目标地址";
    }
  }
  if (!host) return kind === "wsServers" || kind === "httpServers" ? "监听地址不能为空" : "请输入有效的目标地址";
  if (!isLoopbackHost(host) && "token" in item && !item.token.trim()) return "非回环地址必须填写 Access Token";
  return "";
}

function endpoint(kind: Kind, c: AnyConn) {
  if (kind === "wsServers") return `ws://${(c as WsServer).host}:${(c as WsServer).port}`;
  if (kind === "httpServers") return `http://${(c as HttpServer).host}:${(c as HttpServer).port}`;
  return (c as WsClient | HttpClient).url;
}

export function Network() {
  const [cfg, setCfg] = useState<AppConfig | null>(null);
  const [saved, setSaved] = useState<string>("");
  const [kind, setKind] = useState<Kind>("wsServers");
  const [editing, setEditing] = useState<AnyConn | null>(null);
  const [isNew, setIsNew] = useState(false);
  const [saving, setSaving] = useState(false);
  const toast = useToast();

  useEffect(() => {
    api.getConfig().then((c) => {
      setCfg(c);
      setSaved(JSON.stringify(c.network));
    });
  }, []);

  const dirty = useMemo(() => !!cfg && JSON.stringify(cfg.network) !== saved, [cfg, saved]);
  const list = (cfg?.network[kind] ?? []) as AnyConn[];

  const update = (fn: (n: NetworkConfig) => NetworkConfig) => setCfg((c) => (c ? { ...c, network: fn(structuredClone(c.network)) } : c));

  const upsert = (item: AnyConn) =>
    update((n) => {
      const arr = n[kind] as AnyConn[];
      const idx = arr.findIndex((x) => x.id === item.id);
      if (idx >= 0) arr[idx] = item;
      else arr.push(item);
      return n;
    });

  const remove = (id: string) => {
    if (!confirm("确定要删除这个连接吗？")) return;
    update((n) => {
      (n[kind] as AnyConn[]) = (n[kind] as AnyConn[]).filter((x) => x.id !== id);
      return n;
    });
  };

  const save = async () => {
    if (!cfg) return;
    for (const entry of KINDS) {
      for (const item of cfg.network[entry.key] as AnyConn[]) {
        const error = connectionError(entry.key, item);
        if (error) {
          toast.push("error", `${item.name || entry.label}：${error}`);
          return;
        }
      }
    }
    setSaving(true);
    try {
      const result = await api.saveConfig({ network: cfg.network });
      setSaved(JSON.stringify(cfg.network));
      const applied = fmtApplied(result);
      toast.push(applied.tone, applied.message);
    } catch (e) {
      toast.push("error", (e as Error).message);
    } finally {
      setSaving(false);
    }
  };

  const reset = () => {
    if (!cfg) return;
    setCfg({ ...cfg, network: JSON.parse(saved) });
    toast.push("info", "已撤销未保存的修改");
  };

  const counts = KINDS.map((k) => ({ ...k, n: cfg?.network[k.key].length ?? 0, on: cfg?.network[k.key].filter((x) => x.enabled).length ?? 0 }));
  const current = KINDS.find((k) => k.key === kind)!;

  return (
    <div>
      <PageHeader
        title="网络配置"
        desc="配置 PicoOnebot 与机器人框架之间的连接方式，可同时启用多条"
        action={
          <div className="flex items-center gap-2">
            {dirty ? (
              <Button variant="ghost" onClick={reset}>
                <RotateCcw className="h-4 w-4" /> 撤销
              </Button>
            ) : null}
            <Button onClick={save} loading={saving} disabled={!dirty}>
              <Save className="h-4 w-4" /> 保存并应用
            </Button>
          </div>
        }
      />

      {/* kind tabs */}
      <div className="mb-6 grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        {counts.map((k) => {
          const Icon = k.icon;
          const active = k.key === kind;
          return (
            <button
              key={k.key}
              onClick={() => setKind(k.key)}
              className={cn(
                "pico-ring pico-glass flex items-center gap-3 rounded-3xl border p-4 text-left shadow-cute ring-1",
                active ? "border-pico/40 ring-pico/40" : "border-white ring-pico-line"
              )}
            >
              <span className={cn("flex h-11 w-11 shrink-0 items-center justify-center rounded-2xl", active ? "bg-gradient-to-br from-pico to-pico-deep text-white shadow-cute" : "bg-pico-soft text-pico-deep")}>
                <Icon className="h-5 w-5" />
              </span>
              <span className="min-w-0 flex-1">
                <span className="block truncate text-sm font-extrabold text-pico-ink">{k.label}</span>
                <span className="block text-[11px] text-pico-muted">
                  {k.on}/{k.n} 已启用
                </span>
              </span>
            </button>
          );
        })}
      </div>

      <Card>
        <div className="flex flex-wrap items-center justify-between gap-3 border-b border-pico-line/70 px-6 py-4">
          <div>
            <h3 className="text-base font-extrabold text-pico-ink">{current.label}</h3>
            <p className="text-xs text-pico-muted">{current.desc}</p>
          </div>
          <Button
            variant="soft"
            onClick={() => {
              setEditing(blank(kind));
              setIsNew(true);
            }}
          >
            <Plus className="h-4 w-4" /> 添加
          </Button>
        </div>

        <div className="p-6">
          {!cfg ? (
            <div className="space-y-3">
              {[0, 1].map((i) => (
                <div key={i} className="h-20 rounded-3xl bg-pico-softer" />
              ))}
            </div>
          ) : list.length === 0 ? (
            <Empty
              title={`还没有${current.label}`}
              desc="点击右上角「添加」创建第一条连接，保存后会立刻重启监听；实际连上没有，看「概览」的连接列表。"
              action={
                <Button
                  size="sm"
                  onClick={() => {
                    setEditing(blank(kind));
                    setIsNew(true);
                  }}
                >
                  <Plus className="h-4 w-4" /> 立即添加
                </Button>
              }
            />
          ) : (
            <ul className="space-y-3">
              {list.map((c) => (
                <li key={c.id} className="group flex flex-wrap items-center gap-4 rounded-3xl border border-pico-line bg-white/70 p-4 transition hover:border-pico/40 hover:shadow-cute">
                  <Toggle checked={c.enabled} onChange={(v) => upsert({ ...c, enabled: v })} />
                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center gap-2">
                      <p className="text-sm font-extrabold text-pico-ink">{c.name || "未命名"}</p>
                      <Badge tone={c.enabled ? "green" : "gray"}>{c.enabled ? "已启用" : "已停用"}</Badge>
                      <Badge tone="pink">{c.messageFormat === "array" ? "消息段数组" : "CQ 码字符串"}</Badge>
                      {c.debug ? <Badge tone="amber">Debug</Badge> : null}
                    </div>
                    <button
                      className="mt-1 flex items-center gap-1.5 font-mono text-[11px] text-pico-muted hover:text-pico-deep"
                      onClick={() => {
                        navigator.clipboard?.writeText(endpoint(kind, c));
                        toast.push("info", "地址已复制");
                      }}
                    >
                      {endpoint(kind, c)} <Copy className="h-3 w-3 opacity-0 transition group-hover:opacity-100" />
                    </button>
                  </div>
                  <div className="flex items-center gap-1">
                    <Button
                      variant="ghost"
                      size="icon"
                      aria-label="编辑"
                      onClick={() => {
                        setEditing(structuredClone(c));
                        setIsNew(false);
                      }}
                    >
                      <Pencil className="h-4 w-4" />
                    </Button>
                    <Button variant="ghost" size="icon" aria-label="删除" className="hover:bg-rose-100 hover:text-rose-600" onClick={() => remove(c.id)}>
                      <Trash2 className="h-4 w-4" />
                    </Button>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </div>
      </Card>

      {dirty ? (
        <div className="pointer-events-none fixed inset-x-0 bottom-6 z-30 flex justify-center px-4">
          <div className="pico-glass pointer-events-auto flex items-center gap-3 rounded-full border border-white py-2 pl-5 pr-2 shadow-cute-lg ring-1 ring-pico-line">
            <span className="text-sm font-bold text-pico-ink">有未保存的修改</span>
            <Button variant="ghost" size="sm" onClick={reset}>
              撤销
            </Button>
            <Button size="sm" onClick={save} loading={saving}>
              <Save className="h-3.5 w-3.5" /> 保存
            </Button>
          </div>
        </div>
      ) : null}

      <ConnEditor
        kind={kind}
        item={editing}
        isNew={isNew}
        onClose={() => setEditing(null)}
        onSubmit={(item) => {
          upsert(item);
          setEditing(null);
        }}
      />
    </div>
  );
}

/* ------------------------------------------------------------------ */

function ConnEditor({ kind, item, isNew, onClose, onSubmit }: { kind: Kind; item: AnyConn | null; isNew: boolean; onClose: () => void; onSubmit: (i: AnyConn) => void }) {
  const [draft, setDraft] = useState<AnyConn | null>(item);
  useEffect(() => setDraft(item), [item]);
  if (!draft) return null;

  const set = <K extends string>(k: K, v: unknown) => setDraft((d) => (d ? ({ ...d, [k]: v } as AnyConn) : d));
  const d = draft as unknown as Record<string, unknown>;
  const title = `${isNew ? "添加" : "编辑"}${KINDS.find((k) => k.key === kind)?.label}`;
  const validationError = connectionError(kind, draft);

  return (
    <Modal
      open={!!item}
      onClose={onClose}
      title={title}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            取消
          </Button>
          <Button onClick={() => onSubmit(draft)} disabled={!draft.name.trim() || !!validationError}>
            {isNew ? "添加" : "保存修改"}
          </Button>
        </>
      }
    >
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="名称" className="sm:col-span-2">
          <Input value={draft.name} onChange={(e) => set("name", e.target.value)} placeholder="给这条连接起个名字" />
        </Field>

        {"host" in draft ? (
          <>
            <Field label="监听地址" hint="0.0.0.0 表示允许局域网访问">
              <Input className="font-mono" value={String(d.host)} onChange={(e) => set("host", e.target.value)} />
            </Field>
            <Field label="端口">
              <Input className="font-mono" type="number" min={1} max={65535} value={Number(d.port)} onChange={(e) => set("port", Number(e.target.value))} />
            </Field>
          </>
        ) : (
          <Field label="目标地址" className="sm:col-span-2" hint={kind === "wsClients" ? "例如 ws://127.0.0.1:8080/onebot/v11/ws" : "例如 http://127.0.0.1:8080/onebot"}>
            <Input className="font-mono" value={String(d.url)} onChange={(e) => set("url", e.target.value)} />
          </Field>
        )}

        {"token" in draft ? (
          <Field label="Access Token" hint="回环地址可留空，其他地址必须填写" className={kind === "httpServers" ? "" : "sm:col-span-2"}>
            <Input className="font-mono" value={String(d.token)} onChange={(e) => set("token", e.target.value)} placeholder="非回环地址必填" />
          </Field>
        ) : null}
        {"secret" in draft ? (
          <Field label="上报签名 Secret" hint="用于 X-Signature 校验，留空不签名">
            <Input className="font-mono" value={String(d.secret)} onChange={(e) => set("secret", e.target.value)} />
          </Field>
        ) : null}

        {"heartbeat" in draft ? (
          <Field label="心跳间隔 (ms)" hint="0 为关闭心跳">
            <Input className="font-mono" type="number" min={0} step={1000} value={Number(d.heartbeat)} onChange={(e) => set("heartbeat", Number(e.target.value))} />
          </Field>
        ) : null}
        {"reconnectInterval" in draft ? (
          <Field label="重连间隔 (ms)">
            <Input className="font-mono" type="number" min={500} step={500} value={Number(d.reconnectInterval)} onChange={(e) => set("reconnectInterval", Number(e.target.value))} />
          </Field>
        ) : null}

        <Field label="消息格式">
          <Select value={draft.messageFormat} onChange={(e) => set("messageFormat", e.target.value)}>
            <option value="array">消息段数组 (array)</option>
            <option value="string">CQ 码字符串 (string)</option>
          </Select>
        </Field>

        <div className="space-y-1 rounded-2xl bg-pico-softer p-3 sm:col-span-2">
          {"reportSelfMessage" in draft ? <Toggle label="上报自身消息" desc="把 Bot 自己发送的消息也作为事件推送" checked={Boolean(d.reportSelfMessage)} onChange={(v) => set("reportSelfMessage", v)} /> : null}
          {"cors" in draft ? <Toggle label="允许跨域 (CORS)" desc="浏览器直接调用 API 时需要" checked={Boolean(d.cors)} onChange={(v) => set("cors", v)} /> : null}
          {"websocket" in draft ? <Toggle label="同端口启用 WebSocket" desc="HTTP 与 WS 共用一个端口" checked={Boolean(d.websocket)} onChange={(v) => set("websocket", v)} /> : null}
          <Toggle label="调试模式" desc="上报事件附带原始 NT 消息结构" checked={draft.debug} onChange={(v) => set("debug", v)} />
          <Toggle label="启用此连接" checked={draft.enabled} onChange={(v) => set("enabled", v)} />
        </div>
        {validationError ? <p className="text-xs font-bold text-rose-600 sm:col-span-2">{validationError}</p> : null}
      </div>
    </Modal>
  );
}
