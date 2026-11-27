import { useCallback, useEffect, useId, useMemo, useRef, useState, type FormEvent } from "react";
import {
  Plus,
  Pencil,
  Trash2,
  Save,
  RotateCcw,
  Radio,
  Cable,
  Globe,
  SendHorizontal,
  Copy,
  Eye,
  EyeOff,
  AlertTriangle,
  RefreshCw,
} from "lucide-react";
import { Badge, Button, Card, Empty, Field, Input, Modal, PageHeader, Toggle, useToast } from "../components/ui";
import { ApiError, api, getErrorMessage, isAbortError } from "../lib/api";
import { copyText } from "../lib/clipboard";
import type { AppConfig, HttpClient, HttpServer, NetworkConfig, WsClient, WsServer } from "../lib/types";
import { fmtApplied } from "../lib/format";
import { cn } from "../utils/cn";

type Kind = keyof NetworkConfig;
type AnyConn = WsServer | WsClient | HttpServer | HttpClient;

type EditingConn =
  | { kind: "wsServers"; item: WsServer }
  | { kind: "wsClients"; item: WsClient }
  | { kind: "httpServers"; item: HttpServer }
  | { kind: "httpClients"; item: HttpClient };

type KindMeta = {
  key: Kind;
  label: string;
  desc: string;
  icon: typeof Radio;
};

const KIND_META: Record<Kind, KindMeta> = {
  wsServers: { key: "wsServers", label: "正向 WebSocket", desc: "PicoOnebot 作为服务端，等待框架连接", icon: Radio },
  wsClients: { key: "wsClients", label: "反向 WebSocket", desc: "PicoOnebot 主动连接到框架地址", icon: Cable },
  httpServers: { key: "httpServers", label: "HTTP 服务端", desc: "提供 HTTP API 调用", icon: Globe },
  httpClients: { key: "httpClients", label: "HTTP 上报", desc: "把事件 POST 到指定地址", icon: SendHorizontal },
};

const KINDS: readonly KindMeta[] = [
  KIND_META.wsServers,
  KIND_META.wsClients,
  KIND_META.httpServers,
  KIND_META.httpClients,
];

const uid = () => Math.random().toString(36).slice(2, 10);

function blankEntry(kind: Kind): EditingConn {
  const base = { id: uid(), name: "", enabled: true };
  switch (kind) {
    case "wsServers":
      return { kind, item: { ...base, name: "新的正向 WS", host: "", port: 3001, token: "", cors: false, websocket: true } };
    case "wsClients":
      return { kind, item: { ...base, name: "新的反向 WS", url: "", token: "", reconnectInterval: 5000 } };
    case "httpServers":
      return { kind, item: { ...base, name: "新的 HTTP 服务", host: "", port: 3000, token: "", cors: false, websocket: false } };
    case "httpClients":
      return { kind, item: { ...base, name: "新的 HTTP 上报", url: "", token: "", secret: "" } };
  }
}

function editingEntryFromList(network: NetworkConfig, kind: Kind, id: string): EditingConn | null {
  switch (kind) {
    case "wsServers": {
      const found = network.wsServers.find((x) => x.id === id);
      return found ? { kind, item: structuredClone(found) } : null;
    }
    case "wsClients": {
      const found = network.wsClients.find((x) => x.id === id);
      return found ? { kind, item: structuredClone(found) } : null;
    }
    case "httpServers": {
      const found = network.httpServers.find((x) => x.id === id);
      return found ? { kind, item: structuredClone(found) } : null;
    }
    case "httpClients": {
      const found = network.httpClients.find((x) => x.id === id);
      return found ? { kind, item: structuredClone(found) } : null;
    }
  }
}

function listForKind(network: NetworkConfig | undefined, kind: Kind): readonly AnyConn[] {
  if (!network) return [];
  switch (kind) {
    case "wsServers":
      return network.wsServers;
    case "wsClients":
      return network.wsClients;
    case "httpServers":
      return network.httpServers;
    case "httpClients":
      return network.httpClients;
  }
}

function upsertArray<T extends { id: string }>(arr: T[], item: T): T[] {
  const idx = arr.findIndex((x) => x.id === item.id);
  if (idx >= 0) {
    const next = [...arr];
    next[idx] = item;
    return next;
  }
  return [...arr, item];
}

function upsertInNetwork(network: NetworkConfig, entry: EditingConn): NetworkConfig {
  switch (entry.kind) {
    case "wsServers":
      return { ...network, wsServers: upsertArray(network.wsServers, entry.item) };
    case "wsClients":
      return { ...network, wsClients: upsertArray(network.wsClients, entry.item) };
    case "httpServers":
      return { ...network, httpServers: upsertArray(network.httpServers, entry.item) };
    case "httpClients":
      return { ...network, httpClients: upsertArray(network.httpClients, entry.item) };
  }
}

function toggleInNetwork(network: NetworkConfig, kind: Kind, id: string, enabled: boolean): NetworkConfig {
  switch (kind) {
    case "wsServers":
      return { ...network, wsServers: network.wsServers.map((x) => (x.id === id ? { ...x, enabled } : x)) };
    case "wsClients":
      return { ...network, wsClients: network.wsClients.map((x) => (x.id === id ? { ...x, enabled } : x)) };
    case "httpServers":
      return { ...network, httpServers: network.httpServers.map((x) => (x.id === id ? { ...x, enabled } : x)) };
    case "httpClients":
      return { ...network, httpClients: network.httpClients.map((x) => (x.id === id ? { ...x, enabled } : x)) };
  }
}

function removeInNetwork(network: NetworkConfig, kind: Kind, id: string): NetworkConfig {
  switch (kind) {
    case "wsServers":
      return { ...network, wsServers: network.wsServers.filter((x) => x.id !== id) };
    case "wsClients":
      return { ...network, wsClients: network.wsClients.filter((x) => x.id !== id) };
    case "httpServers":
      return { ...network, httpServers: network.httpServers.filter((x) => x.id !== id) };
    case "httpClients":
      return { ...network, httpClients: network.httpClients.filter((x) => x.id !== id) };
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
  if ("host" in item) {
    if (!Number.isInteger(item.port) || item.port < 1 || item.port > 65535) {
      return "端口必须是 1-65535 之间的整数";
    }
    if (!item.enabled) return "";
    const host = item.host.trim();
    if (!host) return "监听地址不能为空";
    if (!isLoopbackHost(host) && !item.token.trim()) return "非回环地址必须填写 Access Token";
    return "";
  }

  if ("reconnectInterval" in item) {
    if (!Number.isFinite(item.reconnectInterval) || item.reconnectInterval < 500) {
      return "重连间隔不能小于 500 毫秒";
    }
  }

  const rawUrl = item.url.trim();
  if (!item.enabled && !rawUrl) return "";
  let parsed: URL;
  try {
    parsed = new URL(rawUrl);
  } catch {
    return "请输入有效的目标地址";
  }
  if (kind === "wsClients" && parsed.protocol !== "ws:" && parsed.protocol !== "wss:") {
    return "反向 WebSocket 地址必须以 ws:// 或 wss:// 开头";
  }
  if (kind === "httpClients" && parsed.protocol !== "http:" && parsed.protocol !== "https:") {
    return "HTTP 上报地址必须以 http:// 或 https:// 开头";
  }
  if (!parsed.hostname) return "请输入有效的目标地址";
  if (item.enabled && !isLoopbackHost(parsed.hostname) && !item.token.trim()) {
    return "非回环地址必须填写 Access Token";
  }
  return "";
}

function formatHost(host: string) {
  const trimmed = host.trim();
  if (trimmed.includes(":") && !trimmed.startsWith("[")) return `[${trimmed}]`;
  return trimmed;
}

function endpoint(kind: Kind, c: AnyConn): string {
  if ("host" in c) {
    const scheme = kind === "wsServers" ? "ws" : "http";
    return `${scheme}://${formatHost(c.host)}:${c.port}`;
  }
  return c.url;
}

export function Network({ onDirtyChange }: { onDirtyChange?: (dirty: boolean) => void }) {
  const [cfg, setCfg] = useState<AppConfig | null>(null);
  const [savedNetwork, setSavedNetwork] = useState<NetworkConfig | null>(null);
  const [savedJson, setSavedJson] = useState<string>("");
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [kind, setKind] = useState<Kind>("wsServers");
  const [editing, setEditing] = useState<EditingConn | null>(null);
  const [isNew, setIsNew] = useState(false);
  const [saving, setSaving] = useState(false);
  const toast = useToast();
  const loadAbort = useRef<AbortController | null>(null);

  const loadConfig = useCallback(async () => {
    loadAbort.current?.abort();
    const controller = new AbortController();
    loadAbort.current = controller;
    setLoading(true);
    setLoadError(null);
    try {
      const c = await api.getConfig(controller.signal);
      if (controller.signal.aborted) return;
      setCfg(c);
      setSavedNetwork(structuredClone(c.network));
      setSavedJson(JSON.stringify(c.network));
    } catch (err) {
      if (isAbortError(err)) return;
      const msg = getErrorMessage(err, "加载网络配置失败");
      setLoadError(msg);
      if (!(err instanceof ApiError && (err.status === 401 || err.status === 428))) {
        toast.push("error", msg);
      }
    } finally {
      if (!controller.signal.aborted) setLoading(false);
    }
  }, [toast]);

  useEffect(() => {
    void loadConfig();
    return () => {
      loadAbort.current?.abort();
    };
  }, [loadConfig]);

  const dirty = useMemo(() => !!cfg && JSON.stringify(cfg.network) !== savedJson, [cfg, savedJson]);

  useEffect(() => {
    onDirtyChange?.(dirty);
    return () => {
      onDirtyChange?.(false);
    };
  }, [dirty, onDirtyChange]);

  useEffect(() => {
    if (onDirtyChange) return;
    const beforeUnload = (event: BeforeUnloadEvent) => {
      if (!dirty) return;
      event.preventDefault();
      event.returnValue = "";
    };
    window.addEventListener("beforeunload", beforeUnload);
    return () => window.removeEventListener("beforeunload", beforeUnload);
  }, [dirty, onDirtyChange]);

  const list = listForKind(cfg?.network, kind);

  const update = (fn: (n: NetworkConfig) => NetworkConfig) =>
    setCfg((c) => (c ? { ...c, network: fn(structuredClone(c.network)) } : c));

  const upsert = (entry: EditingConn) => update((n) => upsertInNetwork(n, entry));

  const toggle = (id: string, enabled: boolean) => update((n) => toggleInNetwork(n, kind, id, enabled));

  const remove = (id: string) => {
    if (!confirm("确定要删除这个连接吗？")) return;
    update((n) => removeInNetwork(n, kind, id));
  };

  const save = async () => {
    if (!cfg) return;
    for (const entry of KINDS) {
      for (const item of listForKind(cfg.network, entry.key)) {
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
      setSavedNetwork(structuredClone(cfg.network));
      setSavedJson(JSON.stringify(cfg.network));
      const applied = fmtApplied(result);
      toast.push(applied.tone, applied.message);
    } catch (e) {
      if (!(e instanceof ApiError && (e.status === 401 || e.status === 428))) {
        toast.push("error", getErrorMessage(e, "保存网络配置失败"));
      }
    } finally {
      setSaving(false);
    }
  };

  const reset = () => {
    if (!cfg || !savedNetwork) return;
    setCfg({ ...cfg, network: structuredClone(savedNetwork) });
    toast.push("info", "已撤销未保存的修改");
  };

  const handleCopyEndpoint = async (value: string) => {
    const ok = await copyText(value);
    toast.push(ok ? "info" : "error", ok ? "地址已复制" : "复制失败，请手动复制");
  };

  const counts = KINDS.map((k) => {
    const items = listForKind(cfg?.network, k.key);
    return { ...k, n: items.length, on: items.filter((x) => x.enabled).length };
  });
  const current = KIND_META[kind];

  return (
    <div>
      <PageHeader
        title="网络配置"
        desc="配置 PicoOnebot 与机器人框架之间的连接方式，可同时启用多条"
        action={
          <div className="flex items-center gap-2">
            {dirty ? (
              <Button type="button" variant="ghost" onClick={reset}>
                <RotateCcw className="h-4 w-4" /> 撤销
              </Button>
            ) : null}
            <Button type="button" onClick={() => void save()} loading={saving} disabled={!dirty}>
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
              type="button"
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
            type="button"
            variant="soft"
            disabled={!cfg}
            onClick={() => {
              setEditing(blankEntry(kind));
              setIsNew(true);
            }}
          >
            <Plus className="h-4 w-4" /> 添加
          </Button>
        </div>

        <div className="p-6">
          {loading && !cfg ? (
            <div className="space-y-3">
              {[0, 1].map((i) => (
                <div key={i} className="h-20 animate-pulse rounded-3xl bg-pico-softer" />
              ))}
            </div>
          ) : loadError && !cfg ? (
            <div className="flex flex-col items-center justify-center rounded-3xl border-2 border-dashed border-rose-200 bg-rose-50/40 px-6 py-12 text-center">
              <AlertTriangle className="mb-3 h-8 w-8 text-rose-500" />
              <p className="text-sm font-extrabold text-pico-ink">加载网络配置失败</p>
              <p className="mt-1 max-w-sm text-xs text-pico-muted">{loadError}</p>
              <Button type="button" className="mt-4" size="sm" variant="soft" onClick={() => void loadConfig()} loading={loading}>
                <RefreshCw className="h-3.5 w-3.5" /> 重试
              </Button>
            </div>
          ) : list.length === 0 ? (
            <Empty
              title={`还没有${current.label}`}
              desc="点击右上角「添加」创建第一条连接，保存后会立刻重启监听；实际连上没有，看「概览」的连接列表。"
              action={
                <Button
                  type="button"
                  size="sm"
                  onClick={() => {
                    setEditing(blankEntry(kind));
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
                  <Toggle checked={c.enabled} onChange={(v) => toggle(c.id, v)} />
                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center gap-2">
                      <p className="text-sm font-extrabold text-pico-ink">{c.name || "未命名"}</p>
                      <Badge tone={c.enabled ? "green" : "gray"}>{c.enabled ? "已启用" : "已停用"}</Badge>
                    </div>
                    <button
                      type="button"
                      className="mt-1 flex items-center gap-1.5 font-mono text-[11px] text-pico-muted hover:text-pico-deep"
                      onClick={() => void handleCopyEndpoint(endpoint(kind, c))}
                    >
                      {endpoint(kind, c)} <Copy className="h-3 w-3 opacity-0 transition group-hover:opacity-100" />
                    </button>
                  </div>
                  <div className="flex items-center gap-1">
                    <Button
                      type="button"
                      variant="ghost"
                      size="icon"
                      aria-label="编辑"
                      onClick={() => {
                        if (!cfg) return;
                        const entry = editingEntryFromList(cfg.network, kind, c.id);
                        if (entry) {
                          setEditing(entry);
                          setIsNew(false);
                        }
                      }}
                    >
                      <Pencil className="h-4 w-4" />
                    </Button>
                    <Button type="button" variant="ghost" size="icon" aria-label="删除" className="hover:bg-rose-100 hover:text-rose-600" onClick={() => remove(c.id)}>
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
            <Button type="button" variant="ghost" size="sm" onClick={reset}>
              撤销
            </Button>
            <Button type="button" size="sm" onClick={() => void save()} loading={saving}>
              <Save className="h-3.5 w-3.5" /> 保存
            </Button>
          </div>
        </div>
      ) : null}

      <ConnEditor
        entry={editing}
        isNew={isNew}
        onClose={() => setEditing(null)}
        onSubmit={(submitted) => {
          upsert(submitted);
          setEditing(null);
        }}
      />
    </div>
  );
}

/* ------------------------------------------------------------------ */

function ConnEditor({
  entry,
  isNew,
  onClose,
  onSubmit,
}: {
  entry: EditingConn | null;
  isNew: boolean;
  onClose: () => void;
  onSubmit: (entry: EditingConn) => void;
}) {
  const [draft, setDraft] = useState<EditingConn | null>(entry);
  const [showToken, setShowToken] = useState(false);
  const [showSecret, setShowSecret] = useState(false);
  const idPrefix = useId();

  useEffect(() => {
    setDraft(entry);
    setShowToken(false);
    setShowSecret(false);
  }, [entry]);

  if (!draft) return null;

  const { kind, item } = draft;
  const title = `${isNew ? "添加" : "编辑"}${KIND_META[kind].label}`;
  const validationError = connectionError(kind, item);
  const canSubmit = Boolean(item.name.trim()) && !validationError;
  const formId = `${idPrefix}-form`;

  const updateCommon = (patch: { name?: string; token?: string; enabled?: boolean }) => {
    setDraft((prev) => {
      if (!prev) return prev;
      switch (prev.kind) {
        case "wsServers":
          return { kind: "wsServers", item: { ...prev.item, ...patch } };
        case "wsClients":
          return { kind: "wsClients", item: { ...prev.item, ...patch } };
        case "httpServers":
          return { kind: "httpServers", item: { ...prev.item, ...patch } };
        case "httpClients":
          return { kind: "httpClients", item: { ...prev.item, ...patch } };
      }
    });
  };

  const updateServer = (patch: Partial<Pick<WsServer, "host" | "port" | "cors" | "websocket">>) => {
    setDraft((prev) => {
      if (!prev) return prev;
      if (prev.kind === "wsServers") return { kind: "wsServers", item: { ...prev.item, ...patch } };
      if (prev.kind === "httpServers") return { kind: "httpServers", item: { ...prev.item, ...patch } };
      return prev;
    });
  };

  const updateClientUrl = (url: string) => {
    setDraft((prev) => {
      if (!prev) return prev;
      if (prev.kind === "wsClients") return { kind: "wsClients", item: { ...prev.item, url } };
      if (prev.kind === "httpClients") return { kind: "httpClients", item: { ...prev.item, url } };
      return prev;
    });
  };

  const updateReconnectInterval = (reconnectInterval: number) => {
    setDraft((prev) =>
      prev && prev.kind === "wsClients"
        ? { kind: "wsClients", item: { ...prev.item, reconnectInterval } }
        : prev
    );
  };

  const updateSecret = (secret: string) => {
    setDraft((prev) =>
      prev && prev.kind === "httpClients"
        ? { kind: "httpClients", item: { ...prev.item, secret } }
        : prev
    );
  };

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault();
    if (!canSubmit) return;
    onSubmit(draft);
  };

  return (
    <Modal
      open={entry !== null}
      onClose={onClose}
      title={title}
      footer={
        <>
          <Button type="button" variant="ghost" onClick={onClose}>
            取消
          </Button>
          <Button type="submit" form={formId} disabled={!canSubmit}>
            {isNew ? "添加" : "保存修改"}
          </Button>
        </>
      }
    >
      <form id={formId} onSubmit={handleSubmit} className="grid gap-4 sm:grid-cols-2">
        <Field label="名称" htmlFor={`${idPrefix}-name`} className="sm:col-span-2">
          <Input
            id={`${idPrefix}-name`}
            value={item.name}
            onChange={(e) => updateCommon({ name: e.target.value })}
            placeholder="给这条连接起个名字"
          />
        </Field>

        {"host" in item ? (
          <>
            <Field label="监听地址" htmlFor={`${idPrefix}-host`} hint="0.0.0.0 表示允许局域网访问">
              <Input
                id={`${idPrefix}-host`}
                className="font-mono"
                value={item.host}
                onChange={(e) => updateServer({ host: e.target.value })}
              />
            </Field>
            <Field label="端口" htmlFor={`${idPrefix}-port`} hint="1 - 65535">
              <Input
                id={`${idPrefix}-port`}
                className="font-mono"
                type="number"
                min={1}
                max={65535}
                value={Number.isFinite(item.port) ? item.port : ""}
                onChange={(e) => updateServer({ port: e.target.value === "" ? Number.NaN : Number(e.target.value) })}
              />
            </Field>
          </>
        ) : (
          <Field
            label="目标地址"
            htmlFor={`${idPrefix}-url`}
            className="sm:col-span-2"
            hint={kind === "wsClients" ? "例如 ws://127.0.0.1:8080/onebot/v11/ws" : "例如 http://127.0.0.1:8080/onebot"}
          >
            <Input
              id={`${idPrefix}-url`}
              className="font-mono"
              value={item.url}
              onChange={(e) => updateClientUrl(e.target.value)}
            />
          </Field>
        )}

        <Field
          label="Access Token"
          htmlFor={`${idPrefix}-token`}
          hint="回环地址可留空，其他地址必须填写"
          className={kind === "httpServers" ? "" : "sm:col-span-2"}
        >
          <div className="relative">
            <Input
              id={`${idPrefix}-token`}
              className="pr-11 font-mono"
              type={showToken ? "text" : "password"}
              autoComplete="off"
              value={item.token}
              onChange={(e) => updateCommon({ token: e.target.value })}
              placeholder="非回环地址必填"
            />
            <button
              type="button"
              onClick={() => setShowToken((v) => !v)}
              className="absolute right-2 top-1/2 flex h-7 w-7 -translate-y-1/2 items-center justify-center rounded-lg text-pico-muted hover:bg-pico-soft"
              aria-label={showToken ? "隐藏 Access Token" : "显示 Access Token"}
            >
              {showToken ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
            </button>
          </div>
        </Field>

        {"secret" in item ? (
          <Field label="上报签名 Secret" htmlFor={`${idPrefix}-secret`} hint="用于 X-Signature 校验，留空不签名">
            <div className="relative">
              <Input
                id={`${idPrefix}-secret`}
                className="pr-11 font-mono"
                type={showSecret ? "text" : "password"}
                autoComplete="off"
                value={item.secret}
                onChange={(e) => updateSecret(e.target.value)}
              />
              <button
                type="button"
                onClick={() => setShowSecret((v) => !v)}
                className="absolute right-2 top-1/2 flex h-7 w-7 -translate-y-1/2 items-center justify-center rounded-lg text-pico-muted hover:bg-pico-soft"
                aria-label={showSecret ? "隐藏签名 Secret" : "显示签名 Secret"}
              >
                {showSecret ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
              </button>
            </div>
          </Field>
        ) : null}

        {"reconnectInterval" in item ? (
          <Field label="重连间隔 (ms)" htmlFor={`${idPrefix}-reconnect`} hint="至少 500 毫秒">
            <Input
              id={`${idPrefix}-reconnect`}
              className="font-mono"
              type="number"
              min={500}
              step={500}
              value={Number.isFinite(item.reconnectInterval) ? item.reconnectInterval : ""}
              onChange={(e) =>
                updateReconnectInterval(e.target.value === "" ? Number.NaN : Number(e.target.value))
              }
            />
          </Field>
        ) : null}

        <div className="space-y-1 rounded-2xl bg-pico-softer p-3 sm:col-span-2">
          {"cors" in item ? (
            <Toggle
              label="允许跨域 (CORS)"
              desc="浏览器直接调用 API 时需要"
              checked={item.cors}
              onChange={(v) => updateServer({ cors: v })}
            />
          ) : null}
          {kind === "httpServers" && "websocket" in item ? (
            <Toggle
              label="同端口启用 WebSocket"
              desc="HTTP 与 WS 共用一个端口"
              checked={item.websocket}
              onChange={(v) => updateServer({ websocket: v })}
            />
          ) : null}
          <Toggle label="启用此连接" checked={item.enabled} onChange={(v) => updateCommon({ enabled: v })} />
        </div>
        {validationError ? <p className="text-xs font-bold text-rose-600 sm:col-span-2">{validationError}</p> : null}
      </form>
    </Modal>
  );
}
