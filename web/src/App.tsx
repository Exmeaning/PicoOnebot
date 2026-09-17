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
import { api, getToken, setToken, ApiError } from "./lib/api";
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
  const pageRef = useRef(page);
  // QQ 未登录时只自动带去扫码页一次;用户手动离开就不再打扰
  const qrRedirected = useRef(false);

  useEffect(() => {
    const saved = getToken();
    if (!saved) {
      setCheckingAuth(false);
      return;
    }
    api.login(saved)
      .then((result) => {
        setToken(result.token);
        if (result.mustChangePassword) setMustChangePassword(true);
        else setAuthed(true);
      })
      .catch(() => setToken(""))
      .finally(() => setCheckingAuth(false));
  }, []);

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
    const tick = async () => {
      try {
        const s = await api.status();
        if (!alive) return;
        setStatus(s);
        if (s.online) {
          qrRedirected.current = false;
          if (pageRef.current === "qr") navigate("dashboard");
        } else if (!qrRedirected.current && pageRef.current !== "qr") {
          // 先确认登录页确实在(不是内核还在热身),再跳,免得刚启动就来回蹦
          const q = await api.qr().catch(() => null);
          if (!alive || !q) return;
          if (q.status !== "online" && (q.hasFragment || q.hasStateFragment || q.url)) {
            qrRedirected.current = true;
            toast.push("info", "QQ 还没登录，先扫个码吧");
            navigate("qr");
          }
        }
      } catch (e) {
        if (e instanceof ApiError && e.status === 401) {
          setAuthed(false);
          toast.push("error", "登录已过期，请重新登录");
        } else if (e instanceof ApiError && e.status === 428) {
          setAuthed(false);
          setMustChangePassword(true);
        }
      }
    };
    tick();
    const t = setInterval(tick, 5000);
    return () => {
      alive = false;
      clearInterval(t);
    };
  }, [authed, toast, navigate]);

  // keyboard shortcuts: g then 1-6
  useEffect(() => {
    if (!authed) return;
    const onKey = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement;
      if (["INPUT", "TEXTAREA", "SELECT"].includes(target.tagName)) return;
      if (e.key.toLowerCase() === "g") {
        gPressed.current = true;
        setTimeout(() => (gPressed.current = false), 1200);
        return;
      }
      if (gPressed.current && /^[1-6]$/.test(e.key)) {
        navigate(NAV[Number(e.key) - 1].key);
        gPressed.current = false;
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [authed, navigate]);

  const logout = async () => {
    await api.logout();
    setAuthed(false);
    setMustChangePassword(false);
    setStatus(null);
    toast.push("info", "已退出登录，下次见");
  };

  if (checkingAuth) {
    return <div className="flex min-h-full items-center justify-center text-sm font-bold text-pico-muted">正在验证控制台会话...</div>;
  }

  if (mustChangePassword) {
    return (
      <ChangePassword
        onSuccess={() => {
          setMustChangePassword(false);
          setAuthed(true);
          navigate("dashboard");
        }}
      />
    );
  }

  if (!authed) {
    return (
      <Login
        onSuccess={(mustChange) => {
          if (mustChange) setMustChangePassword(true);
          else {
            setAuthed(true);
            navigate("dashboard");
          }
        }}
      />
    );
  }

  return (
    <Shell page={page} onNavigate={navigate} onLogout={logout} status={status}>
      {page === "dashboard" && <Dashboard status={status} onNavigate={navigate} />}
      {page === "qr" && <QrLogin onNavigate={navigate} />}
      {page === "network" && <Network />}
      {page === "logs" && <Logs />}
      {page === "files" && <FileManager />}
      {page === "settings" && <Settings />}
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
