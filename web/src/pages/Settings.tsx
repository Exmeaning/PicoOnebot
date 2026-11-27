import { useCallback, useEffect, useId, useMemo, useRef, useState, type FormEvent } from "react";
import {
  Palette,
  Check,
  Save,
  RotateCcw,
  SlidersHorizontal,
  ShieldCheck,
  Wand2,
  ChevronDown,
  AlertTriangle,
  RefreshCw,
} from "lucide-react";
import { Button, Card, CardHeader, Field, Input, PageHeader, Select, Toggle, useToast, Badge } from "../components/ui";
import { THEME_PRESETS, normalizeHexColor, useTheme } from "../store/theme";
import {
  ApiError,
  api,
  getBaseUrl,
  getErrorMessage,
  getStoredBaseUrl,
  isAbortError,
  isCrossOriginBaseUrl,
  normalizeBaseUrl,
  setBaseUrl,
} from "../lib/api";
import type { AppConfig, ForwardMode, GeneralConfig, LogLevel, MessageFormat, WebuiConfig } from "../lib/types";
import { cn } from "../utils/cn";

type SettingsSnapshot = {
  general: GeneralConfig;
  webui: { host: string; port: number };
};

const LOG_LEVELS: readonly LogLevel[] = ["debug", "info", "warn", "error"];

function isLogLevel(value: string): value is LogLevel {
  return value === "debug" || value === "info" || value === "warn" || value === "error";
}

function isForwardMode(value: string): value is ForwardMode {
  return value === "merge_one" || value === "separate" || value === "silent";
}

function isMessageFormat(value: string): value is MessageFormat {
  return value === "array" || value === "string";
}

function extractSnapshot(c: AppConfig): SettingsSnapshot {
  return {
    general: structuredClone(c.general),
    webui: { host: c.webui.host, port: c.webui.port },
  };
}

function validateSettings(cfg: AppConfig): string {
  const g = cfg.general;
  if (!cfg.webui.host.trim()) return "WebUI 监听地址不能为空";
  if (!Number.isInteger(cfg.webui.port) || cfg.webui.port < 1 || cfg.webui.port > 65535) {
    return "WebUI 端口必须是 1-65535 之间的整数";
  }
  if (!Number.isInteger(g.sendRatePerSec) || g.sendRatePerSec <= 0) {
    return "发送速率上限必须是大于 0 的整数";
  }
  if (!Number.isInteger(g.kernelCallTimeoutMs) || g.kernelCallTimeoutMs <= 0) {
    return "内核调用超时必须是大于 0 的整数";
  }
  if (!Number.isInteger(g.mediaBase64Limit) || g.mediaBase64Limit < 0) {
    return "base64 内联上限必须是非负整数";
  }
  if (!Number.isInteger(g.heartbeat) || g.heartbeat < 0 || (g.heartbeat > 0 && g.heartbeat < 1000)) {
    return "心跳间隔须为 0（关闭）或不小于 1000 毫秒的整数";
  }
  const mediaBase = g.mediaBaseUrl.trim();
  if (mediaBase) {
    try {
      const u = new URL(mediaBase);
      if ((u.protocol !== "http:" && u.protocol !== "https:") || !u.hostname) {
        return "媒体外链前缀必须是合法的 http:// 或 https:// 地址";
      }
    } catch {
      return "媒体外链前缀必须是合法的 http:// 或 https:// 地址";
    }
  }
  return "";
}

export function Settings({
  onRequireRelogin,
  onDirtyChange,
}: {
  onRequireRelogin: (message: string) => void;
  onDirtyChange?: (dirty: boolean) => void;
}) {
  const theme = useTheme();
  const toast = useToast();
  const idPrefix = useId();
  const [themeCollapsed, setThemeCollapsed] = useState(true);
  const [hexDraft, setHexDraft] = useState(theme.color);
  const [cfg, setCfg] = useState<AppConfig | null>(null);
  const [savedSnapshot, setSavedSnapshot] = useState<SettingsSnapshot | null>(null);
  const [savedJson, setSavedJson] = useState("");
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [base, setBase] = useState(() => getStoredBaseUrl());
  const [switchingBase, setSwitchingBase] = useState(false);
  const loadAbort = useRef<AbortController | null>(null);
  const probeAbortRef = useRef<AbortController | null>(null);
  const mountedRef = useRef(true);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      probeAbortRef.current?.abort();
      probeAbortRef.current = null;
    };
  }, []);

  useEffect(() => {
    setHexDraft(theme.color);
  }, [theme.color]);

  const snap = (c: AppConfig) => JSON.stringify({ general: c.general, webui: { host: c.webui.host, port: c.webui.port } });

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
      setSavedSnapshot(extractSnapshot(c));
      setSavedJson(snap(c));
    } catch (err) {
      if (isAbortError(err)) return;
      const msg = getErrorMessage(err, "加载设置失败");
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

  const dirty = useMemo(() => !!cfg && snap(cfg) !== savedJson, [cfg, savedJson]);
  const validationError = useMemo(() => (cfg ? validateSettings(cfg) : ""), [cfg]);

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

  const set = <K extends keyof GeneralConfig>(k: K, v: GeneralConfig[K]) => setCfg((c) => (c ? { ...c, general: { ...c.general, [k]: v } } : c));
  const setWeb = <K extends keyof WebuiConfig>(k: K, v: WebuiConfig[K]) => setCfg((c) => (c ? { ...c, webui: { ...c.webui, [k]: v } } : c));

  const save = async () => {
    if (!cfg) return;
    const err = validateSettings(cfg);
    if (err) {
      toast.push("error", err);
      return;
    }
    setSaving(true);
    try {
      const r = await api.saveConfig({ general: cfg.general, webui: cfg.webui });
      setSavedSnapshot(extractSnapshot(cfg));
      setSavedJson(snap(cfg));
      toast.push("success", r.restartRequired ? "已保存，WebUI 监听地址或启动设置需重启 QQ 生效" : "设置已保存");
    } catch (e) {
      if (!(e instanceof ApiError && (e.status === 401 || e.status === 428))) {
        toast.push("error", getErrorMessage(e, "保存设置失败"));
      }
    } finally {
      setSaving(false);
    }
  };

  const handleSaveBaseUrl = async (event?: FormEvent) => {
    event?.preventDefault();
    if (switchingBase) return;
    const parsed = normalizeBaseUrl(base);
    if (!parsed.ok) {
      toast.push("error", parsed.error);
      return;
    }
    const currentEffective = getBaseUrl();
    const nextEffective = parsed.url || window.location.origin;
    if (nextEffective === currentEffective) {
      setBaseUrl(parsed.url);
      setBase(parsed.url);
      toast.push("info", parsed.url ? "后端地址未变化" : "已恢复默认同源地址");
      return;
    }
    probeAbortRef.current?.abort();
    const controller = new AbortController();
    probeAbortRef.current = controller;
    let timedOut = false;
    setSwitchingBase(true);
    try {
      // 先探测目标后端连通性与 CORS 预检，避免切到不可达或未放行跨域的地址后被迫退出并卡在登录页。
      const timer = setTimeout(() => {
        timedOut = true;
        controller.abort();
      }, 6000);
      let probe;
      try {
        probe = await api.probeBaseUrl(parsed.url, controller.signal);
      } finally {
        clearTimeout(timer);
      }
      // 切页、卸载或退出登录时中止探测，防止旧回调延迟弹出 toast / confirm 或误触发登出。
      if (!mountedRef.current || probeAbortRef.current !== controller || (controller.signal.aborted && !timedOut)) {
        return;
      }
      if (!probe.ok) {
        toast.push("error", probe.message);
        return;
      }
      if (!confirm(`已确认目标后端 ${nextEffective} 可达。切换后端地址将退出当前会话并需要重新登录，以避免将当前凭据发送到新主机。确定切换吗？`)) {
        return;
      }
      if (!mountedRef.current || probeAbortRef.current !== controller) {
        return;
      }
      // Log out on the old host BEFORE switching baseUrl so the old token is never sent to the new host.
      await api.logout();
      if (!mountedRef.current || probeAbortRef.current !== controller) {
        return;
      }
      setBaseUrl(parsed.url);
      onRequireRelogin("后端地址已更新，请重新登录");
    } finally {
      if (probeAbortRef.current === controller) {
        probeAbortRef.current = null;
      }
      if (mountedRef.current) {
        setSwitchingBase(false);
      }
    }
  };

  const baseIsCrossOrigin = useMemo(() => isCrossOriginBaseUrl(base), [base]);

  const g = cfg?.general;

  return (
    <div className="min-w-0">
      <PageHeader
        title="偏好设置"
        desc="外观主题保存在浏览器；运行参数会写入 PicoOnebot 配置文件"
        action={
          <div className="flex items-center gap-2">
            {dirty ? (
              <Button
                type="button"
                variant="ghost"
                onClick={() => {
                  if (!cfg || !savedSnapshot) return;
                  const restored = structuredClone(savedSnapshot);
                  setCfg({
                    ...cfg,
                    general: restored.general,
                    webui: { ...cfg.webui, host: restored.webui.host, port: restored.webui.port },
                  });
                }}
              >
                <RotateCcw className="h-4 w-4" /> 撤销
              </Button>
            ) : null}
            <Button type="button" onClick={() => void save()} loading={saving} disabled={!dirty || !!validationError}>
              <Save className="h-4 w-4" /> 保存设置
            </Button>
          </div>
        }
      />

      {validationError ? (
        <div className="mb-4 flex items-center gap-2 rounded-2xl border border-rose-200 bg-rose-50 px-4 py-2.5 text-xs font-bold text-rose-600">
          <AlertTriangle className="h-4 w-4 shrink-0" />
          {validationError}
        </div>
      ) : null}

      <div className="grid gap-6 lg:grid-cols-2 min-w-0">
        {/* Theme */}
        <Card className="lg:col-span-2 min-w-0" decorated>
          <div onClick={() => setThemeCollapsed((v) => !v)} className="cursor-pointer select-none">
            <CardHeader
              title={
                <div className="flex flex-wrap items-center gap-2">
                  <span>主题配色</span>
                  <span className="inline-flex items-center gap-1.5 rounded-full bg-pico-soft px-2.5 py-0.5 text-xs font-extrabold text-pico-deep">
                    <span className="h-2.5 w-2.5 rounded-full ring-1 ring-white" style={{ background: theme.color }} />
                    {theme.customColor ? "自定义" : (THEME_PRESETS.find((p) => p.id === theme.presetId)?.name ?? "樱花粉")}
                  </span>
                </div>
              }
              desc="挑一个喜欢的颜色，整个控制台都会跟着变"
              icon={<Palette className="h-4 w-4" />}
              action={
                <Button
                  variant="ghost"
                  size="sm"
                  type="button"
                  aria-expanded={!themeCollapsed}
                  onClick={(e) => {
                    e.stopPropagation();
                    setThemeCollapsed((v) => !v);
                  }}
                  className="gap-1 text-xs text-pico-muted shrink-0"
                >
                  <span>{themeCollapsed ? "展开配色" : "收起配色"}</span>
                  <ChevronDown className={cn("h-4 w-4", !themeCollapsed && "rotate-180")} />
                </Button>
              }
            />
          </div>
          {!themeCollapsed && (
            <div className="p-4 pt-3 sm:p-6 sm:pt-4 min-w-0">
              <div className="grid grid-cols-3 min-[440px]:grid-cols-4 lg:grid-cols-7 gap-2.5 sm:gap-3">
                {THEME_PRESETS.map((p) => {
                  const active = !theme.customColor && theme.presetId === p.id;
                  return (
                    <button
                      key={p.id}
                      type="button"
                      onClick={() => theme.setPreset(p.id)}
                      className={cn(
                        "pico-ring group relative flex flex-col items-center gap-1.5 sm:gap-2 rounded-2xl sm:rounded-3xl border-2 bg-white/70 p-2 sm:p-3",
                        active ? "border-pico shadow-cute" : "border-transparent hover:border-pico-line"
                      )}
                    >
                      <span
                        className="relative flex h-10 w-10 sm:h-12 sm:w-12 items-center justify-center rounded-full shadow-inner ring-2 sm:ring-4 ring-white"
                        style={{ background: `linear-gradient(135deg, ${p.color}, ${p.color}aa)` }}
                      >
                        {active ? <Check className="h-4 w-4 sm:h-5 sm:w-5 text-white drop-shadow" /> : null}
                      </span>
                      <span className="text-xs font-extrabold text-pico-ink truncate">{p.name}</span>
                    </button>
                  );
                })}
              </div>

              <div className="mt-4 flex flex-col sm:flex-row flex-wrap items-start sm:items-center justify-between gap-3 rounded-2xl sm:rounded-3xl bg-pico-softer p-3.5 sm:p-4">
                <div className="flex items-center gap-3">
                  <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-2xl bg-white shadow-cute">
                    <Wand2 className="h-4 w-4 sm:h-5 sm:w-5 text-pico" />
                  </span>
                  <div>
                    <p className="text-sm font-extrabold text-pico-ink">自定义主题色</p>
                    <p className="text-[11px] text-pico-muted">Logo 与强调色会自动跟随</p>
                  </div>
                </div>
                <div className="flex items-center gap-2">
                  <label className="relative h-9 w-9 shrink-0 cursor-pointer overflow-hidden rounded-xl ring-2 ring-white shadow-cute" style={{ background: theme.color }}>
                    <input type="color" value={theme.color} onChange={(e) => theme.setCustomColor(e.target.value)} className="absolute inset-0 h-full w-full cursor-pointer opacity-0" aria-label="选择颜色" />
                  </label>
                  <Input
                    className="h-9 w-28 sm:w-32 font-mono uppercase text-xs"
                    value={hexDraft}
                    maxLength={7}
                    placeholder="#ff85a2"
                    aria-label="自定义十六进制颜色"
                    onChange={(e) => {
                      const raw = e.target.value;
                      setHexDraft(raw);
                      const candidate = raw.trim().startsWith("#") ? raw.trim() : `#${raw.trim()}`;
                      if (/^#[0-9a-fA-F]{6}$/.test(candidate)) {
                        theme.setCustomColor(candidate);
                      }
                    }}
                    onBlur={() => {
                      const candidate = hexDraft.trim().startsWith("#") ? hexDraft.trim() : `#${hexDraft.trim()}`;
                      const normalized = normalizeHexColor(candidate);
                      if (normalized) {
                        theme.setCustomColor(normalized);
                        setHexDraft(normalized);
                      } else {
                        setHexDraft(theme.color);
                      }
                    }}
                  />
                  {theme.customColor ? <Badge tone="pink" className="shrink-0">自定义中</Badge> : null}
                </div>
              </div>
            </div>
          )}
        </Card>

        {/* Runtime */}
        <Card className="min-w-0">
          <CardHeader title="运行参数" desc="PicoOnebot 核心行为" icon={<SlidersHorizontal className="h-4 w-4" />} />
          <div className="space-y-4 p-4 pt-3 sm:p-6 sm:pt-4 min-w-0">
            {loading && !g ? (
              <div className="h-40 animate-pulse rounded-3xl bg-pico-softer" />
            ) : loadError && !g ? (
              <div className="flex flex-col items-center justify-center rounded-3xl border-2 border-dashed border-rose-200 bg-rose-50/40 px-6 py-10 text-center">
                <AlertTriangle className="mb-2 h-7 w-7 text-rose-500" />
                <p className="text-sm font-extrabold text-pico-ink">加载运行参数失败</p>
                <p className="mt-1 text-xs text-pico-muted">{loadError}</p>
                <Button type="button" className="mt-4" size="sm" variant="soft" onClick={() => void loadConfig()} loading={loading}>
                  <RefreshCw className="h-3.5 w-3.5" /> 重试
                </Button>
              </div>
            ) : g ? (
              <>
                <div className="grid gap-4 sm:grid-cols-2">
                  <Field label="控制台日志等级" htmlFor={`${idPrefix}-log-level`}>
                    <Select
                      id={`${idPrefix}-log-level`}
                      value={g.logLevel}
                      onChange={(e) => {
                        if (isLogLevel(e.target.value)) set("logLevel", e.target.value);
                      }}
                    >
                      {LOG_LEVELS.map((l) => (
                        <option key={l} value={l}>
                          {l}
                        </option>
                      ))}
                    </Select>
                  </Field>
                  <Field label="合并转发降级方式" htmlFor={`${idPrefix}-forward-mode`} hint="手表协议发不了真合并转发，只能降级">
                    <Select
                      id={`${idPrefix}-forward-mode`}
                      value={g.forwardMode}
                      onChange={(e) => {
                        if (isForwardMode(e.target.value)) set("forwardMode", e.target.value);
                      }}
                    >
                      <option value="merge_one">merge_one · 拼成一条</option>
                      <option value="separate">separate · 逐条发</option>
                      <option value="silent">silent · 丢弃</option>
                    </Select>
                  </Field>
                </div>
                <Field label="合并转发抬头" htmlFor={`${idPrefix}-forward-header`} hint="merge_one 时拼接在开头的提示文字">
                  <Input id={`${idPrefix}-forward-header`} value={g.forwardMergeHeader} onChange={(e) => set("forwardMergeHeader", e.target.value)} placeholder="〔合并转发〕" />
                </Field>
                <div className="grid gap-4 sm:grid-cols-2">
                  <Field label="发送速率上限（条/秒）" htmlFor={`${idPrefix}-send-rate`} hint="正整数">
                    <Input
                      id={`${idPrefix}-send-rate`}
                      type="number"
                      min={1}
                      value={Number.isFinite(g.sendRatePerSec) ? g.sendRatePerSec : ""}
                      onChange={(e) => set("sendRatePerSec", e.target.value === "" ? Number.NaN : Number(e.target.value))}
                    />
                  </Field>
                  <Field label="内核调用超时（毫秒）" htmlFor={`${idPrefix}-kernel-timeout`} hint="正整数">
                    <Input
                      id={`${idPrefix}-kernel-timeout`}
                      type="number"
                      min={1}
                      value={Number.isFinite(g.kernelCallTimeoutMs) ? g.kernelCallTimeoutMs : ""}
                      onChange={(e) => set("kernelCallTimeoutMs", e.target.value === "" ? Number.NaN : Number(e.target.value))}
                    />
                  </Field>
                </div>
                <Field label="媒体外链前缀" htmlFor={`${idPrefix}-media-url`} hint="留空则用 /media 相对路径；跨机时填 http://<本机IP>:端口">
                  <Input id={`${idPrefix}-media-url`} className="font-mono" value={g.mediaBaseUrl} onChange={(e) => set("mediaBaseUrl", e.target.value)} placeholder="http://192.168.1.10:3001" />
                </Field>
                <Field label="base64 内联上限（字节）" htmlFor={`${idPrefix}-media-limit`} hint="超过则改用外链，0 表示不内联">
                  <Input
                    id={`${idPrefix}-media-limit`}
                    type="number"
                    min={0}
                    className="font-mono"
                    value={Number.isFinite(g.mediaBase64Limit) ? g.mediaBase64Limit : ""}
                    onChange={(e) => set("mediaBase64Limit", e.target.value === "" ? Number.NaN : Number(e.target.value))}
                  />
                </Field>
                <div className="grid gap-4 sm:grid-cols-2">
                  <Field label="心跳间隔（毫秒）" htmlFor={`${idPrefix}-heartbeat`} hint="0 为关闭，启用时至少 1000">
                    <Input
                      id={`${idPrefix}-heartbeat`}
                      type="number"
                      min={0}
                      step={1000}
                      value={Number.isFinite(g.heartbeat) ? g.heartbeat : ""}
                      onChange={(e) => set("heartbeat", e.target.value === "" ? Number.NaN : Number(e.target.value))}
                    />
                  </Field>
                  <Field label="消息格式" htmlFor={`${idPrefix}-message-format`} hint="对所有连接生效">
                    <Select
                      id={`${idPrefix}-message-format`}
                      value={g.messageFormat}
                      onChange={(e) => {
                        if (isMessageFormat(e.target.value)) set("messageFormat", e.target.value);
                      }}
                    >
                      <option value="array">消息段数组 (array)</option>
                      <option value="string">CQ 码字符串 (string)</option>
                    </Select>
                  </Field>
                </div>
                <Toggle label="上报自身消息" desc="把 Bot 自己发送的消息也作为事件推送；对所有连接生效" checked={Boolean(g.reportSelfMessage)} onChange={(v) => set("reportSelfMessage", v)} />
              </>
            ) : null}
          </div>
        </Card>

        <div className="space-y-6 min-w-0">
          {/* WebUI */}
          <Card className="min-w-0">
            <CardHeader title="WebUI 与安全" desc="控制台访问相关" icon={<ShieldCheck className="h-4 w-4" />} />
            <div className="space-y-4 p-4 pt-3 sm:p-6 sm:pt-4 min-w-0">
              {cfg ? (
                <>
                  <div className="grid gap-4 sm:grid-cols-2">
                    <Field label="WebUI 监听地址" htmlFor={`${idPrefix}-webui-host`} hint="改动需重启 QQ 进程后生效">
                      <Input id={`${idPrefix}-webui-host`} className="font-mono" value={cfg.webui.host} onChange={(e) => setWeb("host", e.target.value)} />
                    </Field>
                    <Field label="WebUI 端口" htmlFor={`${idPrefix}-webui-port`} hint="1 - 65535，改动需重启 QQ 生效；容器部署需同步更新端口映射与探针">
                      <Input
                        id={`${idPrefix}-webui-port`}
                        className="font-mono"
                        type="number"
                        min={1}
                        max={65535}
                        value={Number.isFinite(cfg.webui.port) ? cfg.webui.port : ""}
                        onChange={(e) => setWeb("port", e.target.value === "" ? Number.NaN : Number(e.target.value))}
                      />
                    </Field>
                  </div>
                  {Number.isInteger(cfg.webui.port) && cfg.webui.port !== 6099 ? (
                    <div className="flex items-start gap-2 rounded-2xl border border-amber-200 bg-amber-50 px-3.5 py-2.5 text-[11px] font-bold text-amber-800">
                      <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0 text-amber-500" />
                      <span>
                        容器部署提示：若使用 Docker / K8s，修改默认端口 6099 后请同步更新容器端口映射（compose.yml / Service）以及 K8s 探针与 NetworkPolicy 放行端口。
                      </span>
                    </div>
                  ) : null}
                </>
              ) : null}
              {cfg?.webui.passwordInitialized ? <ChangePasswordInline onRequireRelogin={onRequireRelogin} /> : null}
              <form onSubmit={(e) => void handleSaveBaseUrl(e)}>
                <Field label="后端地址（仅本浏览器）" htmlFor={`${idPrefix}-base-url`} hint="留空默认与当前页面同源；切换前会先探测连通性与跨域权限，跨源需目标后端配置 webui.cors_allowed_origins">
                  <div className="flex gap-2 min-w-0">
                    <Input
                      id={`${idPrefix}-base-url`}
                      className="font-mono text-xs min-w-0 flex-1"
                      value={base}
                      onChange={(e) => setBase(e.target.value)}
                      placeholder={window.location.origin}
                    />
                    {base.trim() ? (
                      <Button variant="ghost" type="button" className="shrink-0" onClick={() => setBase("")} disabled={switchingBase}>
                        恢复同源
                      </Button>
                    ) : null}
                    <Button variant="soft" type="submit" className="shrink-0" loading={switchingBase}>
                      保存
                    </Button>
                  </div>
                </Field>
                {baseIsCrossOrigin ? (
                  <p className="mt-2 rounded-xl border border-amber-200 bg-amber-50 px-3 py-2 text-[11px] font-bold text-amber-800">
                    跨源提示：WebUI 后端默认仅允许同源请求。请确认目标后端 <code className="font-mono">config.json</code> 的 <code className="font-mono">webui.cors_allowed_origins</code> 包含 <code className="font-mono">{window.location.origin}</code>，或使用同源反向代理。
                  </p>
                ) : null}
              </form>
            </div>
          </Card>
        </div>
      </div>
    </div>
  );
}

/** 修改控制台密码:需要当前密码,成功后所有会话失效需重新登录。 */
function ChangePasswordInline({ onRequireRelogin }: { onRequireRelogin: (message: string) => void }) {
  const toast = useToast();
  const idPrefix = useId();
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [busy, setBusy] = useState(false);

  const hasEdgeWhitespace = next !== next.trim();
  const message =
    next.length > 0 && hasEdgeWhitespace
      ? "新密码首尾不能包含空格"
      : next.length > 0 && next.length < 8
        ? "新密码至少 8 位"
        : next === "picopico"
          ? "不能继续使用初始密码"
          : next.length > 0 && next !== confirmPassword
            ? "两次输入的新密码不一致"
            : "";

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (!current) {
      toast.push("error", "请输入当前密码");
      return;
    }
    if (!next) {
      toast.push("error", "请输入新密码");
      return;
    }
    if (message) {
      toast.push("error", message);
      return;
    }
    setBusy(true);
    try {
      const r = await api.changeWebuiPassword(current, next);
      if (!r.ok) throw new Error(r.message || "修改失败");
      setCurrent("");
      setNext("");
      setConfirmPassword("");
      // 后端已吊销所有会话：直接通过父组件干净重置状态回到登录页，不触发 401 弹窗和强刷。
      onRequireRelogin("密码已修改，请用新密码重新登录");
    } catch (e) {
      if (!(e instanceof ApiError && (e.status === 401 || e.status === 428))) {
        toast.push("error", getErrorMessage(e, "修改密码失败"));
      }
    } finally {
      setBusy(false);
    }
  };

  return (
    <form onSubmit={submit} className="space-y-3 rounded-2xl bg-pico-softer p-4">
      <p className="text-xs font-bold text-pico-ink">修改控制台密码</p>
      <div className="grid gap-3 sm:grid-cols-3">
        <Field label="当前密码" htmlFor={`${idPrefix}-current`}>
          <Input id={`${idPrefix}-current`} type="password" autoComplete="current-password" value={current} onChange={(e) => setCurrent(e.target.value)} />
        </Field>
        <Field label="新密码" htmlFor={`${idPrefix}-next`} hint={message && next.length > 0 ? message : "至少 8 位，首尾不含空格"}>
          <Input id={`${idPrefix}-next`} type="password" autoComplete="new-password" value={next} onChange={(e) => setNext(e.target.value)} />
        </Field>
        <Field label="确认新密码" htmlFor={`${idPrefix}-confirm`}>
          <Input id={`${idPrefix}-confirm`} type="password" autoComplete="new-password" value={confirmPassword} onChange={(e) => setConfirmPassword(e.target.value)} />
        </Field>
      </div>
      <Button type="submit" variant="soft" loading={busy} disabled={!current || !next || !!message}>
        修改密码
      </Button>
    </form>
  );
}
