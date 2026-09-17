import { useEffect, useMemo, useState } from "react";
import { Palette, Check, Save, RotateCcw, SlidersHorizontal, ShieldCheck, Wand2, ChevronDown } from "lucide-react";
import { Button, Card, CardHeader, Field, Input, PageHeader, Select, useToast, Badge } from "../components/ui";
import { THEME_PRESETS, useTheme } from "../store/theme";
import { api, getBaseUrl, setBaseUrl } from "../lib/api";
import type { AppConfig, ForwardMode, GeneralConfig, LogLevel, WebuiConfig } from "../lib/types";
import { cn } from "../utils/cn";

export function Settings() {
  const theme = useTheme();
  const toast = useToast();
  const [themeCollapsed, setThemeCollapsed] = useState(true);
  const [cfg, setCfg] = useState<AppConfig | null>(null);
  const [saved, setSaved] = useState("");
  const [saving, setSaving] = useState(false);
  const [base, setBase] = useState(getBaseUrl());

  const snap = (c: AppConfig) => JSON.stringify({ general: c.general, webui: { host: c.webui.host, port: c.webui.port } });

  useEffect(() => {
    api.getConfig().then((c) => {
      setCfg(c);
      setSaved(snap(c));
    });
  }, []);

  const dirty = useMemo(() => !!cfg && snap(cfg) !== saved, [cfg, saved]);
  const set = <K extends keyof GeneralConfig>(k: K, v: GeneralConfig[K]) => setCfg((c) => (c ? { ...c, general: { ...c.general, [k]: v } } : c));
  const setWeb = <K extends keyof WebuiConfig>(k: K, v: WebuiConfig[K]) => setCfg((c) => (c ? { ...c, webui: { ...c.webui, [k]: v } } : c));

  const save = async () => {
    if (!cfg) return;
    setSaving(true);
    try {
      const r = await api.saveConfig(cfg);
      setSaved(snap(cfg));
      toast.push("success", (r as { restartRequired?: boolean }).restartRequired ? "已保存,网络配置改动需重启 QQ 进程后生效" : "设置已保存");
    } catch (e) {
      toast.push("error", (e as Error).message);
    } finally {
      setSaving(false);
    }
  };

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
                variant="ghost"
                onClick={() => {
                  if (!cfg) return;
                  const s = JSON.parse(saved) as { general: GeneralConfig; webui: { host: string; port: number } };
                  setCfg({ ...cfg, general: s.general, webui: { ...cfg.webui, host: s.webui.host, port: s.webui.port } });
                }}
              >
                <RotateCcw className="h-4 w-4" /> 撤销
              </Button>
            ) : null}
            <Button onClick={save} loading={saving} disabled={!dirty}>
              <Save className="h-4 w-4" /> 保存设置
            </Button>
          </div>
        }
      />

      <div className="grid gap-6 lg:grid-cols-2 min-w-0">
        {/* Theme */}
        <Card className="lg:col-span-2 min-w-0" decorated>
          <div
            onClick={() => setThemeCollapsed((v) => !v)}
            className="cursor-pointer select-none"
          >
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
                    value={theme.color}
                    onChange={(e) => /^#[0-9a-fA-F]{6}$/.test(e.target.value) && theme.setCustomColor(e.target.value)}
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
            {!g ? (
              <div className="h-40 rounded-3xl bg-pico-softer" />
            ) : (
              <>
                <div className="grid gap-4 sm:grid-cols-2">
                  <Field label="控制台日志等级">
                    <Select value={g.logLevel} onChange={(e) => set("logLevel", e.target.value as LogLevel)}>
                      {["debug", "info", "warn", "error"].map((l) => (
                        <option key={l} value={l}>
                          {l}
                        </option>
                      ))}
                    </Select>
                  </Field>
                  <Field label="合并转发降级方式" hint="手表协议发不了真合并转发，只能降级">
                    <Select value={g.forwardMode} onChange={(e) => set("forwardMode", e.target.value as ForwardMode)}>
                      <option value="merge_one">merge_one · 拼成一条</option>
                      <option value="separate">separate · 逐条发</option>
                      <option value="silent">silent · 丢弃</option>
                    </Select>
                  </Field>
                </div>
                <Field label="合并转发抬头" hint="merge_one 时拼接在开头的提示文字">
                  <Input value={g.forwardMergeHeader} onChange={(e) => set("forwardMergeHeader", e.target.value)} placeholder="〔合并转发〕" />
                </Field>
                <div className="grid gap-4 sm:grid-cols-2">
                  <Field label="发送速率上限（条/秒）">
                    <Input type="number" value={g.sendRatePerSec} onChange={(e) => set("sendRatePerSec", Number(e.target.value))} />
                  </Field>
                  <Field label="内核调用超时（毫秒）">
                    <Input type="number" value={g.kernelCallTimeoutMs} onChange={(e) => set("kernelCallTimeoutMs", Number(e.target.value))} />
                  </Field>
                </div>
                <Field label="媒体外链前缀" hint="留空则用 /media 相对路径；跨机时填 http://<本机IP>:端口">
                  <Input className="font-mono" value={g.mediaBaseUrl} onChange={(e) => set("mediaBaseUrl", e.target.value)} placeholder="http://192.168.1.10:3001" />
                </Field>
                <Field label="base64 内联上限（字节）" hint="超过则改用外链，不塞进事件">
                  <Input type="number" className="font-mono" value={g.mediaBase64Limit} onChange={(e) => set("mediaBase64Limit", Number(e.target.value))} />
                </Field>
              </>
            )}
          </div>
        </Card>

        <div className="space-y-6 min-w-0">
          {/* WebUI */}
          <Card className="min-w-0">
            <CardHeader title="WebUI 与安全" desc="控制台访问相关" icon={<ShieldCheck className="h-4 w-4" />} />
            <div className="space-y-4 p-4 pt-3 sm:p-6 sm:pt-4 min-w-0">
              {cfg ? (
                <div className="grid gap-4 sm:grid-cols-2">
                  <Field label="WebUI 监听地址" hint="改动需重启 QQ 进程后生效">
                    <Input className="font-mono" value={cfg.webui.host} onChange={(e) => setWeb("host", e.target.value)} />
                  </Field>
                  <Field label="WebUI 端口" hint="改动需重启 QQ 进程后生效">
                    <Input className="font-mono" type="number" value={cfg.webui.port} onChange={(e) => setWeb("port", Number(e.target.value))} />
                  </Field>
                </div>
              ) : null}
              <Field label="后端地址（仅本浏览器）" hint="控制台连接的 PicoOnebot API 地址">
                <div className="flex gap-2 min-w-0">
                  <Input className="font-mono text-xs min-w-0 flex-1" value={base} onChange={(e) => setBase(e.target.value)} />
                  <Button
                    variant="soft"
                    className="shrink-0"
                    onClick={() => {
                      setBaseUrl(base.trim());
                      toast.push("success", "已保存，刷新页面后生效");
                    }}
                  >
                    保存
                  </Button>
                </div>
              </Field>
            </div>
          </Card>

        </div>
      </div>
    </div>
  );
}
