import { Star, BookOpen, Heart, Watch, Waypoints, Package, Cpu, ExternalLink } from "lucide-react";
import { Badge, Card, CardHeader, HeartDot, Kbd, PageHeader } from "../components/ui";
import { Logo } from "../components/Logo";
import type { BotStatus } from "../lib/types";

export function About({ status }: { status: BotStatus | null }) {
  return (
    <div>
      <PageHeader title="关于 PICOPICO" desc="一个可爱又可靠的 OneBot 11 实现" />

      <div className="grid gap-6 lg:grid-cols-3">
        <Card className="relative overflow-hidden lg:col-span-2" decorated>
          <div className="pointer-events-none absolute -right-6 -top-6 opacity-[0.07]">
            <Logo className="h-64" />
          </div>
          <div className="relative p-8">
            <div className="flex items-center gap-4">
              <div>
                <Logo className="h-12" />
                <p className="mt-2 text-sm font-bold text-pico-muted">PicoOnebot · 项目代号 PICOPICO</p>
              </div>
            </div>
            <p className="mt-6 max-w-xl text-sm leading-relaxed text-pico-ink">
              PicoOnebot 是基于手表版 NT QQ 深度魔改的 OneBot 协议实现。她直接挂载在 NT 内核之上，
              以极低的资源占用提供完整的 OneBot 11 API 与事件上报，支持正向 / 反向 WebSocket 与 HTTP，
              可以无缝对接 NoneBot、Koishi、Yunzai 等主流框架。
            </p>
            <div className="mt-6 flex flex-wrap gap-2">
              <Badge tone="pink">
                <Watch className="h-3 w-3" /> Watch NT QQ
              </Badge>
              <Badge tone="pink">
                <Waypoints className="h-3 w-3" /> OneBot 11
              </Badge>
              <Badge tone="pink">
                <Package className="h-3 w-3" /> 单文件 WebUI
              </Badge>
              <Badge tone="pink">
                <Cpu className="h-3 w-3" /> 低占用
              </Badge>
            </div>
            <div className="mt-8 flex flex-wrap gap-3">
              <a
                href="https://github.com/Exmeaning/PicoOnebot"
                target="_blank"
                rel="noreferrer"
                className="inline-flex h-10 items-center gap-2 rounded-2xl bg-gradient-to-br from-pico to-pico-deep px-4 text-sm font-bold text-white shadow-cute"
              >
                <Star className="h-4 w-4 fill-current" /> 给 PICOPICO 点一个免费的小星星 <ExternalLink className="h-3 w-3" />
              </a>
              <a
                href="https://11.onebot.dev/"
                target="_blank"
                rel="noreferrer"
                className="inline-flex h-10 items-center gap-2 rounded-2xl bg-pico-soft px-4 text-sm font-bold text-pico-deep hover:bg-pico/25"
              >
                <BookOpen className="h-4 w-4" /> OneBot 11 标准 <ExternalLink className="h-3 w-3" />
              </a>
            </div>
          </div>
        </Card>

        <Card>
          <CardHeader title="版本信息" icon={<Package className="h-4 w-4" />} />
          <dl className="space-y-3 p-6 pt-4 text-sm">
            {[
              ["PicoOnebot", status ? `v${status.version}` : "--"],
              ["QQ 内核", status?.qqVersion ?? "--"],
              ["运行平台", status?.platform ?? "--"],
              ["协议版本", status?.protocol ?? "--"],
              ["WebUI", "v1.0.0"],
            ].map(([k, v]) => (
              <div key={k} className="flex items-center justify-between gap-3 border-b border-dashed border-pico-line pb-2 last:border-0">
                <dt className="font-bold text-pico-muted">{k}</dt>
                <dd className="truncate font-mono text-xs text-pico-ink">{v}</dd>
              </div>
            ))}
          </dl>
          <div className="px-6 pb-6">
            <div className="rounded-2xl bg-pico-softer p-4 text-xs text-pico-muted">
              快捷键：<Kbd>G</Kbd> 然后 <Kbd>1-6</Kbd> 快速切换页面
            </div>
          </div>
        </Card>

        <Card className="lg:col-span-3">
          <div className="flex flex-wrap items-center justify-center gap-2 px-6 py-5 text-sm font-bold text-pico-muted">
            Made with <Heart className="h-4 w-4 fill-pico text-pico" /> by PICOPICO Team
            <span className="mx-2 hidden sm:inline">·</span>
            致谢 NapCat、LLOneBot 等先行者
            <HeartDot className="h-4 w-4 text-pico/60" />
          </div>
        </Card>
      </div>
    </div>
  );
}
