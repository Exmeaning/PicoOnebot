import { useCallback, useEffect, useRef, useState } from "react";
import { ThemeProvider } from "./store/theme";
import { ToastProvider, useToast } from "./components/ui";
import { Shell, NAV, type PageKey } from "./components/Shell";
import { Login } from "./pages/Login";
import { Dashboard } from "./pages/Dashboard";
import { Network } from "./pages/Network";
import { Logs } from "./pages/Logs";
import { FileManager } from "./pages/FileManager";
import { Settings } from "./pages/Settings";
import { About } from "./pages/About";
import { QrLogin } from "./pages/QrLogin";
import { ChangePassword } from "./pages/ChangePassword";
import {
  api,
  getToken,
  setToken,
  setUnauthorizedHandler,
  setMustChangePasswordHandler,
  ApiError,
} from "./lib/api";
import type { BotStatus } from "./lib/types";

function pageFromHash(): PageKey {
  const h = window.location.hash.replace("#/", "").replace("#", "") as PageKey;
  if (h === "qr") return "qr";
  return NAV.some((n) => n.key === h) ? h : "dashboard";
}

function Root() {
  const [authed, setAuthed] = useState(false);
  const [mustChangePassword, setMustChangePassword] = useState(false);
  const [checkingAuth, setCheckingAuth] = useState(true);
  const [page, setPage] = useState<PageKey>(pageFromHash);
  const [status, setStatus] = useState<BotStatus | null>(null);
  const toast = useToast();
  const gPressed = useRef(false);
  const gTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const pageRef = useRef(page);
  const authedRef = useRef(authed);
  const mustChangeRef = useRef(mustChangePassword);
  // QQ 未登录时只自动带去扫码页一次;用户手动离开就不再打扰
  const qrRedirected = useRef(false);

  const resetToLogin = useCallback(
    (notice?: { kind: "info" | "success" | "error"; text: string }) => {
      authedRef.current = false;
      mustChangeRef.current = false;
      qrRedirected.current = false;
      setToken("");
      setAuthed(false);
      setMustChangePassword(false);
      setStatus(null);
      if (notice) toast.push(notice.kind, notice.text);
    },
    [toast]
  );

  useEffect(() => {
    setUnauthorizedHandler(() => {
      if (authedRef.current || mustChangeRef.current) {
        resetToLogin({ kind: "error", text: "登录已过期，请重新登录" });
      }
    });
    setMustChangePasswordHandler(() => {
      if (authedRef.current) {
        authedRef.current = false;
        mustChangeRef.current = true;
        setAuthed(false);
        setMustChangePassword(true);
      }
    });
    return () => {
      setUnauthorizedHandler(null);
      setMustChangePasswordHandler(null);
    };
  }, [resetToLogin]);

  useEffect(() => {
    let alive = true;
    const saved = getToken();
    if (!saved) {
      setCheckingAuth(false);
      return;
    }
    api
      .login(saved)
      .then((result) => {
        if (!alive) return;
        if (!result.token) throw new ApiError("后端未返回会话令牌", 401);
        setToken(result.token);
        if (result.mustChangePassword) {
          mustChangeRef.current = true;
          setMustChangePassword(true);
        } else {
          authedRef.current = true;
          setAuthed(true);
        }
      })
      .catch((err) => {
        if (!alive) return;
        if (err instanceof ApiError && err.status === 401) {
          setToken("");
        } else {
          toast.push("error", (err as Error).message || "无法连接 PicoOnebot 后端");
        }
      })
      .finally(() => {
        if (alive) setCheckingAuth(false);
      });
    return () => {
      alive = false;
    };
  }, [toast]);

  useEffect(() => {
    pageRef.current = page;
  }, [page]);

  const navigate = useCallback((p: PageKey) => {
    window.location.hash = `/${p}`;
    setPage(p);
    window.scrollTo({ top: 0, behavior: "smooth" });
  }, []);

  useEffect(() => {
    const onHash = () => setPage(pageFromHash());
    window.addEventListener("hashchange", onHash);
    return () => window.removeEventListener("hashchange", onHash);
  }, []);

  // status polling(+ QQ 离线时自动带去扫码页)
  useEffect(() => {
    if (!authed) return;
    let alive = true;
    let ac: AbortController | null = null;
    const tick = async () => {
      ac?.abort();
      const controller = new AbortController();
      ac = controller;
      try {
        const s = await api.status(controller.signal);
        if (!alive || controller.signal.aborted) return;
        setStatus(s);
        if (s.online) {
          qrRedirected.current = false;
          if (pageRef.current === "qr") navigate("dashboard");
        } else if (!qrRedirected.current && pageRef.current !== "qr") {
          // 先确认登录页确实在(不是内核还在热身),再跳,免得刚启动就来回蹦
          const q = await api.qr(controller.signal).catch(() => null);
          if (!alive || controller.signal.aborted || !q) return;
          if (q.status !== "online" && (q.hasFragment || q.hasStateFragment || q.url)) {
            qrRedirected.current = true;
            toast.push("info", "QQ 还没登录，先扫个码吧");
            navigate("qr");
          }
        }
      } catch {
        // 401 / 428 transitions are handled centrally by api.ts handlers; transient network errors retry on next tick.
      }
    };
    void tick();
    const t = setInterval(() => void tick(), 5000);
    return () => {
      alive = false;
      ac?.abort();
      clearInterval(t);
    };
  }, [authed, toast, navigate]);

  // keyboard shortcuts: g then 1-6
  useEffect(() => {
    if (!authed) return;
    const clearG = () => {
      gPressed.current = false;
      if (gTimer.current) {
        clearTimeout(gTimer.current);
        gTimer.current = null;
      }
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.ctrlKey || e.metaKey || e.altKey) return;
      const target = e.target as HTMLElement | null;
      if (target && (["INPUT", "TEXTAREA", "SELECT"].includes(target.tagName) || target.isContentEditable)) return;
      if (e.key.toLowerCase() === "g") {
        clearG();
        gPressed.current = true;
        gTimer.current = setTimeout(() => {
          gPressed.current = false;
          gTimer.current = null;
        }, 1200);
        return;
      }
      if (gPressed.current && /^[1-6]$/.test(e.key)) {
        navigate(NAV[Number(e.key) - 1].key);
        clearG();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => {
      window.removeEventListener("keydown", onKey);
      clearG();
    };
  }, [authed, navigate]);

  const logout = useCallback(async () => {
    await api.logout();
    resetToLogin({ kind: "info", text: "已退出登录，下次见" });
  }, [resetToLogin]);

  const cancelPasswordSetup = useCallback(async () => {
    await api.logout();
    resetToLogin({ kind: "info", text: "已返回登录页" });
  }, [resetToLogin]);

  const requireRelogin = useCallback(
    (message: string) => {
      resetToLogin({ kind: "success", text: message });
    },
    [resetToLogin]
  );

  if (checkingAuth) {
    return <div className="flex min-h-full items-center justify-center text-sm font-bold text-pico-muted">正在验证控制台会话...</div>;
  }

  if (mustChangePassword) {
    return (
      <ChangePassword
        onSuccess={() => {
          mustChangeRef.current = false;
          authedRef.current = true;
          setMustChangePassword(false);
          setAuthed(true);
          navigate("dashboard");
        }}
        onCancel={() => void cancelPasswordSetup()}
      />
    );
  }

  if (!authed) {
    return (
      <Login
        onSuccess={(mustChange) => {
          if (mustChange) {
            mustChangeRef.current = true;
            authedRef.current = false;
            setMustChangePassword(true);
          } else {
            mustChangeRef.current = false;
            authedRef.current = true;
            setAuthed(true);
            navigate("dashboard");
          }
        }}
      />
    );
  }

  return (
    <Shell page={page} onNavigate={navigate} onLogout={() => void logout()} status={status}>
      {page === "dashboard" && <Dashboard status={status} onNavigate={navigate} />}
      {page === "qr" && <QrLogin onNavigate={navigate} />}
      {page === "network" && <Network />}
      {page === "logs" && <Logs />}
      {page === "files" && <FileManager />}
      {page === "settings" && <Settings onRequireRelogin={requireRelogin} />}
      {page === "about" && <About status={status} />}
    </Shell>
  );
}

export default function App() {
  return (
    <ThemeProvider>
      <ToastProvider>
        <Root />
      </ToastProvider>
    </ThemeProvider>
  );
}
