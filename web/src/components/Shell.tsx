import { useEffect, useState, type ReactNode } from "react";
import { LayoutDashboard, Network, ScrollText, FolderCog, Settings2, Info, LogOut, Menu, X, Settings, RefreshCw, QrCode, Star } from "lucide-react";
import { Logo } from "./Logo";
import { Badge, Button, HeartDot, useToast } from "./ui";
import { cn } from "../utils/cn";
import { api } from "../lib/api";
import type { BotStatus } from "../lib/types";

/** `qr` 不进侧栏导航:QQ 离线时由 App 自动带过去,顶栏离线徽标也能点过去。 */
export type PageKey = "dashboard" | "qr" | "network" | "logs" | "files" | "settings" | "about";

export const NAV: { key: PageKey; label: string; desc: string; icon: typeof LayoutDashboard }[] = [
  { key: "dashboard", label: "总览", desc: "运行状态一目了然", icon: LayoutDashboard },
  { key: "network", label: "网络配置", desc: "WS / HTTP 连接", icon: Network },
  { key: "logs", label: "实时日志", desc: "看看她在做什么", icon: ScrollText },
  { key: "files", label: "文件管理", desc: "浏览与编辑配置文件", icon: FolderCog },
  { key: "settings", label: "偏好设置", desc: "主题与运行参数", icon: Settings2 },
  { key: "about", label: "关于", desc: "PICOPICO 与鸣谢", icon: Info },
];

export function Shell({
  page,
  onNavigate,
  onLogout,
  status,
  children,
}: {
  page: PageKey;
  onNavigate: (p: PageKey) => void;
  onLogout: () => void;
  status: BotStatus | null;
  children: ReactNode;
}) {
  const [open, setOpen] = useState(false);
  const [restarting, setRestarting] = useState(false);
  const toast = useToast();
  const current = NAV.find((n) => n.key === page);
  const title = current?.label ?? "扫码登录";
  const desc = current?.desc ?? "让 QQ 账号上线";

  useEffect(() => {
    setOpen(false);
  }, [page]);

  const restart = async () => {
    if (!status?.canRestartQq || restarting) return;
    if (!confirm("确定重启 QQ 吗？不会重启容器或清除数据。QQ 和 WebUI 会短暂断开，恢复后需要重新登录控制台。")) return;
    setRestarting(true);
    try {
      const r = await api.restart();
      if (r.ok) toast.push("success", r.wording ?? "QQ 重启请求已提交");
      else toast.push("info", r.wording ?? "当前环境不支持重启 QQ");
    } catch (e) {
      toast.push("error", (e as Error).message);
    } finally {
      window.setTimeout(() => setRestarting(false), 30000);
    }
  };

  const sidebar = (
    <aside className="flex h-full w-72 flex-col gap-4 p-4">
      <div className="pico-glass rounded-bubble border border-white px-5 py-4 shadow-cute ring-1 ring-pico-line">
        <Logo className="h-6" />
        <p className="mt-1 truncate text-[11px] font-bold text-pico-muted">PicoOnebot 控制台</p>
      </div>

      <nav className="pico-glass flex-1 space-y-1 overflow-y-auto rounded-bubble border border-white p-3 shadow-cute ring-1 ring-pico-line">
        {NAV.map((n) => {
          const active = n.key === page;
          const Icon = n.icon;
          return (
            <button
              key={n.key}
              onClick={() => onNavigate(n.key)}
              className={cn(
                "pico-ring group relative flex w-full items-center gap-3 rounded-2xl px-3 py-2.5 text-left transition-all duration-200",
                active ? "bg-gradient-to-r from-pico to-pico-deep text-white shadow-cute" : "text-pico-ink hover:bg-pico-soft"
              )}
            >
              <span className={cn("flex h-9 w-9 items-center justify-center rounded-xl transition", active ? "bg-white/20" : "bg-pico-soft text-pico-deep group-hover:bg-white")}>
                <Icon className="h-4.5 w-4.5" />
              </span>
              <span className="min-w-0">
                <span className="block text-sm font-extrabold">{n.label}</span>
                <span className={cn("block truncate text-[11px]", active ? "text-white/80" : "text-pico-muted")}>{n.desc}</span>
              </span>
              {active ? <HeartDot className="ml-auto h-4 w-4 text-white/80" /> : null}
            </button>
          );
        })}
      </nav>

      <div className="pico-glass rounded-bubble border border-white p-4 shadow-cute ring-1 ring-pico-line">
        <div className="flex items-center gap-3">
          <div className="relative">
            <div className="flex h-11 w-11 items-center justify-center overflow-hidden rounded-2xl bg-pico-soft text-pico-deep ring-2 ring-white">
              {status?.avatar ? (
                <img src={status.avatar} alt="" className="h-full w-full object-cover" />
              ) : (
                <span className="text-base font-black">{status?.nickname?.slice(0, 1) || "P"}</span>
              )}
            </div>
            <span className={cn("absolute -bottom-0.5 -right-0.5 h-3.5 w-3.5 rounded-full border-2 border-white", status?.online ? "bg-emerald-400" : "bg-slate-300")} />
          </div>
          <div className="min-w-0 flex-1">
            <p className="truncate text-sm font-extrabold text-pico-ink">{status?.online ? status.nickname || status.selfId : "未登录"}</p>
            <p className="truncate font-mono text-[11px] text-pico-muted">{status?.selfId || "--"}</p>
          </div>
        </div>
        <div className="mt-3 grid grid-cols-2 gap-2">
          {status && !status.online ? (
            <Button variant="soft" size="sm" onClick={() => onNavigate("qr")}>
              <QrCode className="h-3.5 w-3.5" /> 扫码
            </Button>
          ) : null}
          {status?.canRestartQq && (
            <Button variant="soft" size="sm" onClick={restart} loading={restarting}>
              <RefreshCw className="h-3.5 w-3.5" /> 重启 QQ
            </Button>
          )}
          <Button variant="outline" size="sm" onClick={onLogout}>
            <LogOut className="h-3.5 w-3.5" /> 退出
          </Button>
        </div>
      </div>
    </aside>
  );

  return (
    <div className="flex min-h-full">
      {/* desktop sidebar */}
      <div className="sticky top-0 hidden h-screen shrink-0 lg:block">{sidebar}</div>

      {/* mobile drawer */}
      {open ? (
        <div className="fixed inset-0 z-40 lg:hidden">
          <div className="absolute inset-0 bg-pico-deep/25 backdrop-blur-sm" onClick={() => setOpen(false)} />
          <div className="absolute inset-y-0 left-0">{sidebar}</div>
        </div>
      ) : null}

      <div className="flex min-w-0 flex-1 flex-col">
        <header className="sticky top-0 z-30 px-4 pt-4 lg:px-6">
          <div className="pico-glass flex items-center gap-3 rounded-full border border-white px-3 py-2 shadow-cute ring-1 ring-pico-line">
            <Button variant="ghost" size="icon" className="lg:hidden" onClick={() => setOpen((v) => !v)} aria-label="菜单">
              {open ? <X className="h-4 w-4" /> : <Menu className="h-4 w-4" />}
            </Button>
            <Logo className="h-5 lg:hidden" />
            <div className="hidden items-center gap-2 pl-2 lg:flex">
              <span className="text-sm font-extrabold text-pico-ink">{title}</span>
              <span className="text-xs text-pico-muted">/ {desc}</span>
            </div>
            <div className="ml-auto flex items-center gap-2">
              {status && !status.online ? (
                <button onClick={() => onNavigate("qr")} className="pico-ring rounded-full" title="QQ 未登录，去扫码">
                  <Badge tone="amber">
                    <QrCode className="h-3 w-3" /> QQ 离线 · 去扫码
                  </Badge>
                </button>
              ) : (
                <Badge tone={status?.online ? "green" : "gray"}>
                  <span className={cn("h-1.5 w-1.5 rounded-full", status?.online ? "bg-emerald-500" : "bg-slate-400")} />
                  {status?.online ? "在线" : "连接中"}
                </Badge>
              )}
              <Button variant="ghost" size="icon" onClick={() => onNavigate("settings")} aria-label="偏好设置" title="偏好设置">
                <Settings className="h-4 w-4" />
              </Button>
            </div>
          </div>
        </header>

        <main className="flex-1 px-4 py-6 lg:px-6 min-w-0 max-w-full overflow-x-hidden">
          <div key={page} className="mx-auto max-w-6xl min-w-0">
            {children}
          </div>
        </main>

        <footer className="flex flex-wrap items-center justify-center gap-x-3 gap-y-2 px-6 pb-6 text-center text-[11px] text-pico-muted">
          <span>PicoOnebot WebUI · Made with love by PICOPICO</span>
          <a
            href="https://github.com/Exmeaning/PicoOnebot"
            target="_blank"
            rel="noreferrer"
            className="pico-ring inline-flex items-center gap-1 rounded-lg px-2 py-1 font-bold text-pico-deep hover:bg-pico-soft"
          >
            <Star className="h-3.5 w-3.5 fill-current" /> 给 PICOPICO 点一个免费的小星星
          </a>
        </footer>
      </div>
    </div>
  );
}
