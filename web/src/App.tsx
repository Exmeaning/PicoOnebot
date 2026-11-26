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
  getErrorMessage,
  getToken,
  setToken,
  setUnauthorizedHandler,
  setMustChangePasswordHandler,
  ApiError,
} from "./lib/api";
import type { BotStatus } from "./lib/types";

function pageFromHash(): PageKey {
  const h = window.location.hash.replace(/^#\/?/, "");
  if (h === "qr") return "qr";
  const match = NAV.find((n) => n.key === h);
  return match ? match.key : "dashboard";
}

function isEditableTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false;
  if (target.isContentEditable) return true;
  return Boolean(target.closest("input, textarea, select, [contenteditable='true'], [contenteditable='']"));
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
  // 提升至 App 的脏状态守护：拦截 SPA 切页、快捷键、浏览器回退与断线自动跳转扫码页
  const dirtyGuardRef = useRef<{ page: PageKey; message: string } | null>(null);
  // QQ 未登录时只自动带去扫码页一次;用户手动离开就不再打扰
  const qrRedirected = useRef(false);
  const qrDeferredNotified = useRef(false);

  const setPageDirty = useCallback((sourcePage: PageKey, isDirty: boolean, message: string) => {
    if (isDirty) {
      dirtyGuardRef.current = { page: sourcePage, message };
    } else if (dirtyGuardRef.current?.page === sourcePage) {
      dirtyGuardRef.current = null;
    }
  }, []);

  const onNetworkDirtyChange = useCallback(
    (dirty: boolean) => setPageDirty("network", dirty, "当前网络配置有未保存的修改，确定放弃并离开吗？"),
    [setPageDirty]
  );

  const onFilesDirtyChange = useCallback(
    (dirty: boolean) => setPageDirty("files", dirty, "当前文件有未保存的修改，确定放弃并离开吗？"),
    [setPageDirty]
  );

  const onSettingsDirtyChange = useCallback(
    (dirty: boolean) => setPageDirty("settings", dirty, "当前偏好设置有未保存的修改，确定放弃并离开吗？"),
    [setPageDirty]
  );

  const isActivePageDirty = useCallback(() => {
    const guard = dirtyGuardRef.current;
    return guard !== null && guard.page === pageRef.current;
  }, []);

  const confirmLeaveIfDirty = useCallback(() => {
    const guard = dirtyGuardRef.current;
    if (!guard || guard.page !== pageRef.current) return true;
    if (!window.confirm(guard.message)) return false;
    dirtyGuardRef.current = null;
    return true;
  }, []);

  const resetToLogin = useCallback(
    (notice?: { kind: "info" | "success" | "error"; text: string }) => {
      authedRef.current = false;
      mustChangeRef.current = false;
      dirtyGuardRef.current = null;
      qrRedirected.current = false;
      qrDeferredNotified.current = false;
      setToken("");
      setAuthed(false);
      setMustChangePassword(false);
      setStatus(null);
      if (notice) toast.push(notice.kind, notice.text);
    },
    [toast]
  );

  useEffect(() => {
    const onBeforeUnload = (event: BeforeUnloadEvent) => {
      if (!isActivePageDirty()) return;
      event.preventDefault();
      event.returnValue = "";
    };
    window.addEventListener("beforeunload", onBeforeUnload);
    return () => window.removeEventListener("beforeunload", onBeforeUnload);
  }, [isActivePageDirty]);

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
        dirtyGuardRef.current = null;
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
          toast.push("error", getErrorMessage(err, "无法连接 PicoOnebot 后端"));
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

  const navigate = useCallback(
    (p: PageKey, options?: { force?: boolean }) => {
      if (p === pageRef.current) return;
      if (!options?.force && !confirmLeaveIfDirty()) return;
      if (options?.force) dirtyGuardRef.current = null;
      pageRef.current = p;
      window.location.hash = `/${p}`;
      setPage(p);
      window.scrollTo({ top: 0, behavior: "smooth" });
    },
    [confirmLeaveIfDirty]
  );

  const navigateInteractive = useCallback(
    (p: PageKey) => {
      navigate(p);
    },
    [navigate]
  );

  useEffect(() => {
    const onHash = () => {
      const next = pageFromHash();
      if (next === pageRef.current) return;
      if (!confirmLeaveIfDirty()) {
        window.location.hash = `/${pageRef.current}`;
        return;
      }
      pageRef.current = next;
      setPage(next);
    };
    window.addEventListener("hashchange", onHash);
    return () => window.removeEventListener("hashchange", onHash);
  }, [confirmLeaveIfDirty]);

  // status polling(+ QQ 离线时自动带去扫码页；存在未保存编辑时暂缓自动跳转)
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
          qrDeferredNotified.current = false;
          if (pageRef.current === "qr") navigate("dashboard", { force: true });
        } else if (!qrRedirected.current && pageRef.current !== "qr") {
          if (isActivePageDirty()) {
            // 有未保存修改时不打断编辑，待保存或放弃后的下次轮询再跳转
            if (!qrDeferredNotified.current) {
              const q = await api.qr(controller.signal).catch(() => null);
              if (!alive || controller.signal.aborted || !q) return;
              if (q.status !== "online" && (q.hasFragment || q.hasStateFragment || q.url)) {
                qrDeferredNotified.current = true;
                toast.push("info", "QQ 已离线，检测到未保存修改，已暂缓自动跳转扫码页");
              }
            }
            return;
          }
          // 先确认登录页确实在(不是内核还在热身),再跳,免得刚启动就来回蹦
          const q = await api.qr(controller.signal).catch(() => null);
          if (!alive || controller.signal.aborted || !q) return;
          if (q.status !== "online" && (q.hasFragment || q.hasStateFragment || q.url)) {
            if (isActivePageDirty()) {
              if (!qrDeferredNotified.current) {
                qrDeferredNotified.current = true;
                toast.push("info", "QQ 已离线，检测到未保存修改，已暂缓自动跳转扫码页");
              }
              return;
            }
            qrRedirected.current = true;
            qrDeferredNotified.current = false;
            toast.push("info", "QQ 还没登录，先扫个码吧");
            navigate("qr", { force: true });
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
  }, [authed, toast, navigate, isActivePageDirty]);

  // keyboard shortcuts: g then 1-6
  useEffect(() => {
    if (!authed) return;
    const clearG = () => {
      gPressed.current = false;
      if (gTimer.current !== null) {
        clearTimeout(gTimer.current);
        gTimer.current = null;
      }
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.ctrlKey || e.metaKey || e.altKey) {
        clearG();
        return;
      }
      if (e.isComposing || e.repeat || e.defaultPrevented) return;
      if (document.querySelector('[role="dialog"][aria-modal="true"]')) {
        clearG();
        return;
      }
      if (isEditableTarget(e.target)) return;
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
        const dest = NAV[Number(e.key) - 1];
        clearG();
        if (dest) navigate(dest.key);
        return;
      }
      if (gPressed.current && e.key !== "Shift") {
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
    if (!confirmLeaveIfDirty()) return;
    await api.logout();
    resetToLogin({ kind: "info", text: "已退出登录，下次见" });
  }, [confirmLeaveIfDirty, resetToLogin]);

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
          navigate("dashboard", { force: true });
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
            navigate("dashboard", { force: true });
          }
        }}
      />
    );
  }

  return (
    <Shell page={page} onNavigate={navigateInteractive} onLogout={() => void logout()} status={status}>
      {page === "dashboard" && <Dashboard status={status} onNavigate={navigateInteractive} />}
      {page === "qr" && <QrLogin onNavigate={navigateInteractive} />}
      {page === "network" && <Network onDirtyChange={onNetworkDirtyChange} />}
      {page === "logs" && <Logs />}
      {page === "files" && <FileManager onDirtyChange={onFilesDirtyChange} />}
      {page === "settings" && <Settings onRequireRelogin={requireRelogin} onDirtyChange={onSettingsDirtyChange} />}
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
