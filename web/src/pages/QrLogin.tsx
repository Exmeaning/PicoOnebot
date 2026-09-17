import { useCallback, useEffect, useRef, useState } from "react";
import { QrCode, RefreshCw, Smartphone, Copy, CheckCircle2, Loader2, Clock3, TerminalSquare, ArrowRight } from "lucide-react";
import { Badge, Button, Card, useToast } from "../components/ui";
import { api } from "../lib/api";
import type { QrInfo } from "../lib/types";
import type { PageKey } from "../components/Shell";

/**
 * QQ 账号扫码登录页。后端 `GET /api/qr` 只读、2s 轮询;过期 / 还停在账号状态页时自动 `POST /api/qr/refresh`。
 * 二维码图片就是 QQ 登录 SDK 回调给宿主的那张(不是截屏),状态机 ONLINE 后自动跳总览。
 */
export function QrLogin({ onNavigate }: { onNavigate: (p: PageKey) => void }) {
  const [info, setInfo] = useState<QrInfo | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [failed, setFailed] = useState(0);
  const toast = useToast();
  const autoRefreshedFor = useRef("");
  const switched = useRef(false);
  const done = useRef(false);

  const refresh = useCallback(
    async (silent = false) => {
      setRefreshing(true);
      try {
        const q = await api.qrRefresh();
        setInfo(q);
        if (!silent) toast.push("success", q.status === "ok" ? "二维码已刷新" : "已向 QQ 请求新的二维码，稍等片刻");
      } catch (e) {
        if (!silent) toast.push("error", (e as Error).message);
      } finally {
        setRefreshing(false);
      }
    },
    [toast]
  );

  useEffect(() => {
    let alive = true;
    const tick = async () => {
      try {
        const q = await api.qr();
        if (!alive) return;
        setInfo(q);
        setFailed(0);
        if (q.status === "online") {
          if (!done.current) {
            done.current = true;
            toast.push("success", "QQ 已上线，欢迎回来");
            onNavigate("dashboard");
          }
          return;
        }
        // 过期:同一张码只自动刷一次,避免和服务端互相追着刷
        if (q.status === "expired" && autoRefreshedFor.current !== q.url) {
          autoRefreshedFor.current = q.url;
          void refresh(true);
        }
        // 还停在"账号状态页"(有上次登录的账号):替用户点一次"切换账号"进扫码页
        if (q.status === "waiting_qr" && !q.hasFragment && q.hasStateFragment && !switched.current) {
          switched.current = true;
          void refresh(true);
        }
      } catch {
        if (alive) setFailed((n) => n + 1);
      }
    };
    void tick();
    const t = setInterval(tick, 2000);
    return () => {
      alive = false;
      clearInterval(t);
    };
  }, [onNavigate, refresh, toast]);

  const copy = async () => {
    if (!info?.url) return;
    try {
      await navigator.clipboard.writeText(info.url);
      toast.push("success", "已复制二维码链接");
    } catch {
      toast.push("error", "复制失败，请手动选择下方链接");
    }
  };

  const st = info?.status ?? "waiting_qr";
  const scanned = !!info?.scanned;
  const tone: "green" | "blue" | "amber" | "gray" = st === "ok" ? (scanned ? "blue" : "green") : st === "expired" ? "amber" : "gray";
  const label =
    st === "ok"
      ? scanned
        ? "已扫码，请在手机上确认"
        : "请用手机 QQ 扫码"
      : st === "expired"
        ? "二维码已过期，正在刷新"
        : info?.hasFragment
          ? "正在向 QQ 请求二维码"
          : info?.hasStateFragment
            ? "正在切换到扫码页"
            : "等待 QQ 登录页就绪";
  const dimmed = !!info?.image && (st !== "ok" || scanned);

  return (
    <div className="mx-auto max-w-4xl space-y-6">
      <Card className="p-6 sm:p-8" decorated>
        <div className="relative grid gap-8 md:grid-cols-[auto_1fr] md:items-center">
          {/* 二维码 */}
          <div className="mx-auto">
            <div className="relative flex h-72 w-72 items-center justify-center rounded-3xl bg-white p-4 shadow-cute ring-1 ring-pico-line">
              {info?.image ? (
                <img src={info.image} alt="QQ 登录二维码" className={`h-full w-full rounded-2xl object-contain transition ${dimmed ? "opacity-30 blur-[2px]" : ""}`} />
              ) : (
                <div className="flex flex-col items-center gap-3 text-pico-muted">
                  <Loader2 className="h-8 w-8 animate-spin text-pico" />
                  <p className="px-6 text-center text-xs font-bold">{label}</p>
                </div>
              )}
              {info?.image && scanned && st === "ok" ? (
                <div className="absolute inset-0 flex flex-col items-center justify-center gap-2 rounded-3xl text-center">
                  <span className="flex h-14 w-14 items-center justify-center rounded-full bg-emerald-100 text-emerald-600 shadow-cute">
                    <CheckCircle2 className="h-8 w-8" />
                  </span>
                  <p className="text-sm font-black text-pico-ink">已扫码</p>
                  <p className="text-xs font-bold text-pico-muted">在手机上点「确认登录」</p>
                </div>
              ) : null}
              {info?.image && st === "expired" ? (
                <div className="absolute inset-0 flex flex-col items-center justify-center gap-3 rounded-3xl text-center">
                  <p className="text-sm font-black text-pico-ink">二维码已过期</p>
                  <Button size="sm" onClick={() => refresh()} loading={refreshing}>
                    <RefreshCw className="h-3.5 w-3.5" /> 换一张
                  </Button>
                </div>
              ) : null}
            </div>
            {info?.url ? (
              <p className="mt-3 w-72 truncate text-center font-mono text-[11px] text-pico-muted" title={info.url}>
                {info.url}
              </p>
            ) : null}
          </div>

          {/* 说明与操作 */}
          <div className="min-w-0">
            <Badge tone={tone}>
              <span className="h-1.5 w-1.5 rounded-full bg-current" />
              {label}
            </Badge>
            <h2 className="mt-3 flex items-center gap-2 text-2xl font-black tracking-tight text-pico-ink">
              <QrCode className="h-6 w-6 text-pico" /> 扫码登录 QQ
            </h2>
            <p className="mt-2 text-sm leading-relaxed text-pico-muted">
              PicoOnebot 寄生在手表 QQ 里，QQ 账号上线之后协议端才会开始收发消息。用手机 QQ 扫左边的二维码，登录成功会自动跳到总览。
            </p>

            <ol className="mt-5 space-y-2.5 text-sm">
              {[
                { i: Smartphone, t: "打开手机 QQ，右上角「+」→ 扫一扫" },
                { i: QrCode, t: "对准左侧二维码，在手机上确认登录" },
                { i: CheckCircle2, t: "状态变为「在线」后自动进入控制台" },
              ].map(({ i: Icon, t }, idx) => (
                <li key={t} className="flex items-center gap-3 text-pico-ink">
                  <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-xl bg-pico-soft text-pico-deep">
                    <Icon className="h-3.5 w-3.5" />
                  </span>
                  <span className="font-bold">
                    <span className="mr-1.5 text-pico-muted">{idx + 1}.</span>
                    {t}
                  </span>
                </li>
              ))}
            </ol>

            <div className="mt-6 flex flex-wrap items-center gap-2">
              <Button onClick={() => refresh()} loading={refreshing}>
                <RefreshCw className="h-4 w-4" /> 刷新二维码
              </Button>
              <Button variant="soft" onClick={copy} disabled={!info?.url}>
                <Copy className="h-4 w-4" /> 复制链接
              </Button>
              {st === "ok" && info ? (
                <span className="inline-flex items-center gap-1.5 text-xs font-bold text-pico-muted">
                  <Clock3 className="h-3.5 w-3.5" /> {info.expiresIn}s 后过期，会自动换新
                </span>
              ) : null}
            </div>

            {failed >= 3 ? <p className="mt-3 text-xs font-bold text-rose-500">连接不到后端，请确认手表 QQ 正在运行、WebUI 端口可达。</p> : null}

            <p className="mt-5 flex items-start gap-2 text-[11px] leading-relaxed text-pico-muted">
              <TerminalSquare className="mt-0.5 h-3.5 w-3.5 shrink-0" />
              <span>
                Linux（Waydroid）形态下也可以在终端用 <span className="font-mono">picoctl qr</span> 看同一张码；两边任选其一扫即可。
              </span>
            </p>
          </div>
        </div>
      </Card>

      <Card className="flex flex-wrap items-center justify-between gap-3 px-6 py-4">
        <p className="text-xs text-pico-muted">
          账号已经在线却还看到这页？可能是内核还在热身，稍等几秒；或者直接去总览看看。
        </p>
        <Button variant="ghost" size="sm" onClick={() => onNavigate("dashboard")}>
          去总览 <ArrowRight className="h-3.5 w-3.5" />
        </Button>
      </Card>
    </div>
  );
}
