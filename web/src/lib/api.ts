import type { AppConfig, BotStatus, ConnectionInfo, FileDocument, FileListing, LogEntry, LoginResult, QrInfo, SaveFileResult } from "./types";

const STORAGE = {
  token: "picoonebot.token",
  baseUrl: "picoonebot.baseUrl",
};

export function getBaseUrl() {
  return localStorage.getItem(STORAGE.baseUrl) || window.location.origin;
}

export function setBaseUrl(url: string) {
  localStorage.setItem(STORAGE.baseUrl, url.replace(/\/+$/, ""));
}

export function getToken() {
  return localStorage.getItem(STORAGE.token) || "";
}

export function setToken(token: string) {
  if (token) localStorage.setItem(STORAGE.token, token);
  else localStorage.removeItem(STORAGE.token);
}

async function http<T>(path: string, init: RequestInit = {}): Promise<T> {
  let response: Response;
  try {
    response = await fetch(getBaseUrl() + path, {
      ...init,
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${getToken()}`,
        ...(init.headers || {}),
      },
    });
  } catch {
    throw new ApiError("无法连接 PicoOnebot 后端，请检查后端地址和服务状态");
  }

  let errorMessage = "";
  if (!response.ok) {
    try {
      const body = (await response.json()) as { message?: string };
      errorMessage = body.message || "";
    } catch {
      // Keep the status-based fallback below.
    }
  }
  if (response.status === 401) {
    setToken("");
    throw new ApiError(errorMessage || "登录已过期，请重新登录", 401);
  }
  if (!response.ok) throw new ApiError(errorMessage || `请求失败 (${response.status})`, response.status);
  return (await response.json()) as T;
}

export class ApiError extends Error {
  status: number;

  constructor(message: string, status = 0) {
    super(message);
    this.status = status;
  }
}

function defaultConfig(): AppConfig {
  return {
    webui: { host: "0.0.0.0", port: 6099, passwordInitialized: false },
    network: {
      wsServers: [],
      wsClients: [],
      httpServers: [],
      httpClients: [],
    },
    general: {
      logLevel: "info", forwardMode: "merge_one",
      forwardMergeHeader: "〔合并转发〕", mediaBaseUrl: "", mediaBase64Limit: 4194304,
      sendRatePerSec: 5, kernelCallTimeoutMs: 30000,
    },
  };
}

export const api = {
  login: (token: string) =>
    http<LoginResult>("/api/auth/login", { method: "POST", body: JSON.stringify({ token }) }),
  logout: async () => {
    try {
      await http("/api/logout", { method: "POST" });
    } catch {
      // Clear the local session even when the backend is unavailable.
    }
    setToken("");
  },
  status: () => http<BotStatus>("/api/status"),
  connections: () => http<ConnectionInfo[]>("/api/connections"),
  getConfig: () => http<AppConfig>("/api/config"),
  saveConfig: (config: AppConfig) =>
    http<{ ok: boolean; restartRequired?: boolean }>("/api/config", {
      method: "PUT", body: JSON.stringify(config),
    }),
  files: (path = "") => http<FileListing>(`/api/files?path=${encodeURIComponent(path)}`),
  file: (path: string) => http<FileDocument>(`/api/files/content?path=${encodeURIComponent(path)}`),
  saveFile: (path: string, content: string, expectedModifiedAt: number) =>
    http<SaveFileResult>("/api/files/content", {
      method: "PUT", body: JSON.stringify({ path, content, expectedModifiedAt }),
    }),
  logs: (limit = 200) => http<LogEntry[]>(`/api/logs?limit=${limit}`),
  restart: () => http<{ ok: boolean; wording?: string }>("/api/restart", { method: "POST" }),

  /** 重置 WebUI 登录 Token(成功后前端需用新 token 重登)。 */
  initializeWebuiPassword: (token: string) =>
    http<{ ok: boolean; message?: string }>("/api/webui/token", {
      method: "PUT", body: JSON.stringify({ token }),
    }),

  /** 让 QQ 退出登录、回到扫码页(与 WebUI 会话登出不同)。 */
  accountLogout: () => http<{ ok: boolean }>("/api/account/logout", { method: "POST" }),

  /** 登录二维码:GET 只读;refresh 主动向 QQ 要新码(停在账号状态页时会先替用户点"切换账号")。 */
  qr: () => http<QrInfo>("/api/qr"),
  qrRefresh: () => http<QrInfo>("/api/qr/refresh", { method: "POST" }),

  /** Subscribe to realtime log stream. Returns an unsubscribe fn. */
  subscribeLogs(onLog: (log: LogEntry) => void, onState?: (connected: boolean) => void) {
    let ws: WebSocket | null = null;
    let closed = false;
    let retry: ReturnType<typeof setTimeout> | null = null;
    const connect = () => {
      const base = getBaseUrl().replace(/^http/, "ws");
      try {
        ws = new WebSocket(`${base}/api/logs/stream?token=${encodeURIComponent(getToken())}`);
      } catch {
        onState?.(false);
        return;
      }
      ws.onopen = () => onState?.(true);
      ws.onmessage = (event) => {
        try {
          onLog(JSON.parse(event.data));
        } catch {
          // Ignore malformed frames and keep the stream alive.
        }
      };
      ws.onclose = () => {
        onState?.(false);
        if (!closed) retry = setTimeout(connect, 3000);
      };
      ws.onerror = () => ws?.close();
    };
    connect();
    return () => {
      closed = true;
      if (retry) clearTimeout(retry);
      ws?.close();
    };
  },
};

export const defaultAppConfig = defaultConfig;
