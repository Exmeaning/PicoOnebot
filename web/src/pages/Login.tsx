import { useState, type FormEvent } from "react";
import { KeyRound, Server, ArrowRight, Eye, EyeOff, Sparkles } from "lucide-react";
import { Logo } from "../components/Logo";
import { Button, Field, Input, HeartDot, useToast, Badge } from "../components/ui";
import { api, getBaseUrl, setBaseUrl, setToken } from "../lib/api";

export function Login({ onSuccess }: { onSuccess: (mustChangePassword: boolean) => void }) {
  const [token, setTok] = useState("");
  const [show, setShow] = useState(false);
  const [advanced, setAdvanced] = useState(false);
  const [base, setBase] = useState(getBaseUrl());
  const [loading, setLoading] = useState(false);
  const toast = useToast();

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setLoading(true);
    try {
      if (base.trim()) setBaseUrl(base.trim());
      const res = await api.login(token);
      setToken(res.token || token);
      toast.push("success", "登录成功，欢迎回来");
      onSuccess(!!res.mustChangePassword);
    } catch (err) {
      toast.push("error", (err as Error).message || "登录失败");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="relative flex min-h-full items-center justify-center overflow-hidden px-4 py-10">
      {/* background decorations */}
      <div className="pointer-events-none absolute inset-0 pico-dots opacity-60" />
      <HeartDot className="pointer-events-none absolute left-[8%] top-[14%] h-12 w-12 text-pico/30" />
      <HeartDot className="pointer-events-none absolute right-[10%] top-[22%] h-8 w-8 text-pico/25" />
      <HeartDot className="pointer-events-none absolute bottom-[14%] left-[16%] h-7 w-7 text-pico/25" />
      <div className="pointer-events-none absolute -right-20 bottom-[-6rem] h-72 w-72 rounded-full bg-pico/20 blur-3xl" />
      <div className="pointer-events-none absolute -left-24 top-[-6rem] h-72 w-72 rounded-full bg-pico/20 blur-3xl" />

      <div className="relative grid w-full max-w-4xl gap-6 lg:grid-cols-[1.1fr_1fr]">
        {/* Left brand panel */}
        <div className="pico-glass relative hidden flex-col justify-between overflow-hidden rounded-bubble border border-white p-8 shadow-cute-lg ring-1 ring-pico-line lg:flex">
          <div className="absolute -right-10 -top-10 h-44 w-44 rounded-full bg-gradient-to-br from-pico/30 to-transparent blur-2xl" />
          <div>
            <Badge tone="pink">
              <Sparkles className="h-3 w-3" /> OneBot 11 · Watch NT QQ
            </Badge>
            <Logo className="mt-6 h-16" />
            <p className="mt-4 text-sm leading-relaxed text-pico-muted">
              PicoOnebot 是基于手表 NT QQ 深度魔改的 OneBot 协议实现。
              轻量、可爱、开箱即用，支持正反向 WebSocket 与 HTTP 上报。
            </p>
          </div>
          <ul className="mt-8 space-y-3 text-sm">
            {["正向 / 反向 WebSocket 多路并存", "HTTP 服务端与事件上报", "热重载配置，无需重启", "实时日志与配置文件管理"].map((t) => (
              <li key={t} className="flex items-center gap-3 text-pico-ink">
                <span className="flex h-6 w-6 items-center justify-center rounded-full bg-pico-soft">
                  <HeartDot className="h-3.5 w-3.5 text-pico" />
                </span>
                <span className="font-bold">{t}</span>
              </li>
            ))}
          </ul>
          <div className="mt-8">
            <div className="inline-block rounded-2xl bg-pico-soft px-4 py-2 text-xs font-bold text-pico-deep">
              早安！今天也请多多指教～
            </div>
          </div>
        </div>

        {/* Right login card */}
        <form onSubmit={submit} className="pico-glass rounded-bubble border border-white p-8 shadow-cute-lg ring-1 ring-pico-line">
          <div className="mb-6 lg:hidden">
            <Logo className="h-8" />
          </div>
          <h2 className="text-2xl font-black tracking-tight text-pico-ink">欢迎回来</h2>
          <p className="mt-1 text-sm text-pico-muted">输入 WebUI Token 进入控制台</p>

          <div className="mt-6 space-y-4">
            <Field label="控制台密码" hint="首次使用初始密码 picopico 登录后，必须立即设置新密码">
              <div className="relative">
                <KeyRound className="pointer-events-none absolute left-3.5 top-1/2 h-4 w-4 -translate-y-1/2 text-pico-muted" />
                <Input
                  className="pl-10 pr-11"
                  type={show ? "text" : "password"}
                  placeholder="输入控制台密码"
                  value={token}
                  onChange={(e) => setTok(e.target.value)}
                  autoFocus
                  autoComplete="current-password"
                />
                <button
                  type="button"
                  onClick={() => setShow((v) => !v)}
                  className="absolute right-2 top-1/2 flex h-7 w-7 -translate-y-1/2 items-center justify-center rounded-lg text-pico-muted hover:bg-pico-soft"
                  aria-label={show ? "隐藏" : "显示"}
                >
                  {show ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                </button>
              </div>
            </Field>

            <button type="button" onClick={() => setAdvanced((v) => !v)} className="text-xs font-bold text-pico-deep underline-offset-4 hover:underline">
              {advanced ? "收起高级选项" : "高级选项：自定义后端地址"}
            </button>

            {advanced ? (
              <Field label="后端地址" hint="默认与当前页面同源，例如 http://127.0.0.1:6099">
                <div className="relative">
                  <Server className="pointer-events-none absolute left-3.5 top-1/2 h-4 w-4 -translate-y-1/2 text-pico-muted" />
                  <Input className="pl-10 font-mono text-xs" value={base} onChange={(e) => setBase(e.target.value)} placeholder="http://127.0.0.1:6099" />
                </div>
              </Field>
            ) : null}

            <Button type="submit" size="lg" className="w-full" loading={loading} disabled={!token}>
              进入控制台 <ArrowRight className="h-4 w-4" />
            </Button>

          </div>
        </form>
      </div>
    </div>
  );
}
