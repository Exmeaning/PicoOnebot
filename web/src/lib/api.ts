import type {
  AppConfig,
  BotStatus,
  ConnectionInfo,
  FileDocument,
  FileListing,
  LogEntry,
  LogLevel,
  LoginResult,
  QrInfo,
  SaveConfigResult,
  SaveFileResult,
} from "./types";

const STORAGE = {
  token: "picoonebot.token",
  baseUrl: "picoonebot.baseUrl",
};

const memoryStorage: Record<string, string> = {};

function storageGet(key: string): string | null {
  try {
    return localStorage.getItem(key);
  } catch {
    return Object.prototype.hasOwnProperty.call(memoryStorage, key) ? memoryStorage[key] : null;
  }
}

function storageSet(key: string, value: string): void {
  memoryStorage[key] = value;
  try {
    localStorage.setItem(key, value);
  } catch {
    // Fallback to in-memory storage when localStorage is unavailable.
  }
}

function storageRemove(key: string): void {
  delete memoryStorage[key];
  try {
    localStorage.removeItem(key);
  } catch {
    // Ignore storage removal errors.
  }
}

export type BaseUrlValidation =
  | { ok: true; url: string }
  | { ok: false; error: string };

/**
 * Validate and normalize a custom backend URL.
 * An empty string clears the override and restores same-origin mode.
 */
export function normalizeBaseUrl(raw: string): BaseUrlValidation {
  const trimmed = raw.trim();
  if (!trimmed) return { ok: true, url: "" };
  let parsed: URL;
  try {
    parsed = new URL(trimmed);
  } catch {
    return { ok: false, error: "请输入合法的后端地址，例如 http://127.0.0.1:6099" };
  }
  if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
    return { ok: false, error: "后端地址仅支持 http:// 或 https:// 协议" };
  }
  if (!parsed.hostname) {
    return { ok: false, error: "后端地址缺少主机名" };
  }
  if (parsed.username || parsed.password) {
    return { ok: false, error: "后端地址不能包含内嵌凭据" };
  }
  if (parsed.search || parsed.hash) {
    return { ok: false, error: "后端地址不能包含查询参数或锚点" };
  }
  const cleanPath = parsed.pathname.replace(/\/+$/, "");
  return { ok: true, url: `${parsed.origin}${cleanPath}` };
}

export function getStoredBaseUrl(): string {
  const raw = storageGet(STORAGE.baseUrl);
  if (!raw) return "";
  const checked = normalizeBaseUrl(raw);
  return checked.ok ? checked.url : "";
}

export function getBaseUrl(): string {
  return getStoredBaseUrl() || window.location.origin;
}

export function setBaseUrl(url: string): void {
  const checked = normalizeBaseUrl(url);
  if (!checked.ok) throw new Error(checked.error);
  if (checked.url) storageSet(STORAGE.baseUrl, checked.url);
  else storageRemove(STORAGE.baseUrl);
}

export function getToken(): string {
  return storageGet(STORAGE.token) || "";
}

export function setToken(token: string): void {
  if (token) storageSet(STORAGE.token, token);
  else storageRemove(STORAGE.token);
}

let unauthorizedHandler: (() => void) | null = null;
let mustChangePasswordHandler: (() => void) | null = null;

export function setUnauthorizedHandler(handler: (() => void) | null): void {
  unauthorizedHandler = handler;
}

export function setMustChangePasswordHandler(handler: (() => void) | null): void {
  mustChangePasswordHandler = handler;
}

export function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === "AbortError";
}

type HttpOptions = {
  skipAuth?: boolean;
  suppressUnauthorized?: boolean;
  /** Optional base URL override: "" uses same-origin, non-empty uses that URL, undefined uses stored baseUrl. */
  baseUrlOverride?: string;
};

async function http<T>(path: string, init: RequestInit = {}, options: HttpOptions = {}): Promise<T> {
  const headers = new Headers(init.headers);
  const hasBody = init.body !== undefined && init.body !== null;
  if (hasBody && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }
  if (!options.skipAuth && !headers.has("Authorization")) {
    const token = getToken();
    if (token) headers.set("Authorization", `Bearer ${token}`);
  }

  const base =
    options.baseUrlOverride === undefined
      ? getBaseUrl()
      : options.baseUrlOverride || window.location.origin;

  let response: Response;
  try {
    response = await fetch(base + path, {
      ...init,
      headers,
    });
  } catch (err) {
    if (isAbortError(err)) throw err;
    throw new ApiError("无法连接 PicoOnebot 后端，请检查后端地址和服务状态");
  }

  let errorMessage = "";
  if (!response.ok) {
    try {
      const body = (await response.json()) as { message?: string };
      errorMessage = typeof body?.message === "string" ? body.message : "";
    } catch {
      // Keep the status-based fallback below.
    }
  }
  if (response.status === 401) {
    if (!options.skipAuth) {
      setToken("");
      if (!options.suppressUnauthorized) unauthorizedHandler?.();
    }
    throw new ApiError(errorMessage || "登录已过期，请重新登录", 401);
  }
  if (response.status === 428) {
    if (!options.skipAuth && !options.suppressUnauthorized) {
      mustChangePasswordHandler?.();
    }
    throw new ApiError(errorMessage || "首次使用必须先修改控制台密码", 428);
  }
  if (!response.ok) {
    throw new ApiError(errorMessage || `请求失败 (${response.status})`, response.status);
  }
  return (await response.json()) as T;
}

export class ApiError extends Error {
  status: number;

  constructor(message: string, status = 0) {
    super(message);
    this.status = status;
  }
}

const VALID_LOG_LEVELS = new Set<LogLevel>(["debug", "info", "warn", "error"]);

function isValidLogEntry(value: unknown): value is LogEntry {
  if (!value || typeof value !== "object") return false;
  const v = value as Record<string, unknown>;
  return (
    typeof v.id === "number" &&
    typeof v.ts === "number" &&
    typeof v.level === "string" &&
    VALID_LOG_LEVELS.has(v.level as LogLevel) &&
    typeof v.tag === "string" &&
    typeof v.msg === "string"
  );
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
      logLevel: "info",
      forwardMode: "merge_one",
      forwardMergeHeader: "〔合并转发〕",
      mediaBaseUrl: "",
      mediaBase64Limit: 4194304,
      sendRatePerSec: 5,
      kernelCallTimeoutMs: 30000,
      heartbeat: 5000,
      messageFormat: "array",
      reportSelfMessage: true,
    },
  };
}

export const api = {
  login: (token: string, baseUrlOverride?: string) =>
    http<LoginResult>(
      "/api/auth/login",
      { method: "POST", body: JSON.stringify({ token }) },
      { skipAuth: true, baseUrlOverride }
    ),
  logout: async () => {
    try {
      await http("/api/logout", { method: "POST" }, { suppressUnauthorized: true });
    } catch {
      // Clear the local session even when the backend is unavailable.
    }
    setToken("");
  },
  status: (signal?: AbortSignal) => http<BotStatus>("/api/status", { signal }),
  connections: (signal?: AbortSignal) => http<ConnectionInfo[]>("/api/connections", { signal }),
  getConfig: (signal?: AbortSignal) => http<AppConfig>("/api/config", { signal }),
  saveConfig: (config: Partial<AppConfig>) =>
    http<SaveConfigResult>("/api/config", {
      method: "PUT",
      body: JSON.stringify(config),
    }),
  files: (path = "", signal?: AbortSignal) =>
    http<FileListing>(`/api/files?path=${encodeURIComponent(path)}`, { signal }),
  file: (path: string, signal?: AbortSignal) =>
    http<FileDocument>(`/api/files/content?path=${encodeURIComponent(path)}`, { signal }),
  saveFile: (path: string, content: string, expectedModifiedAt: number) =>
    http<SaveFileResult>("/api/files/content", {
      method: "PUT",
      body: JSON.stringify({ path, content, expectedModifiedAt }),
    }),
  logs: (limit = 200, signal?: AbortSignal) => http<LogEntry[]>(`/api/logs?limit=${limit}`, { signal }),
  restart: () => http<{ ok: boolean; wording?: string }>("/api/restart", { method: "POST" }),

  /** 初始化 WebUI 密码（后端会清空预初始化会话并下发轮换后的新会话 token）。 */
  initializeWebuiPassword: (token: string) =>
    http<{ ok: boolean; message?: string; token?: string }>("/api/webui/token", {
      method: "PUT",
      body: JSON.stringify({ token }),
    }),

  /** 修改已初始化的控制台密码。成功后所有会话失效,需要重新登录。 */
  changeWebuiPassword: (current: string, token: string) =>
    http<{ ok: boolean; message?: string; sessionsRevoked?: boolean }>("/api/webui/token", {
      method: "PUT",
      body: JSON.stringify({ current, token }),
    }),

  /** 让 QQ 退出登录、回到扫码页(与 WebUI 会话登出不同)。 */
  accountLogout: () => http<{ ok: boolean }>("/api/account/logout", { method: "POST" }),

  /** 登录二维码:GET 只读;refresh 主动向 QQ 要新码(停在账号状态页时会先替用户点"切换账号")。 */
  qr: (signal?: AbortSignal) => http<QrInfo>("/api/qr", { signal }),
  qrRefresh: (signal?: AbortSignal) => http<QrInfo>("/api/qr/refresh", { method: "POST", signal }),

  /** Subscribe to realtime log stream. Returns an unsubscribe fn. */
  subscribeLogs(onLog: (log: LogEntry) => void, onState?: (connected: boolean) => void) {
    let ws: WebSocket | null = null;
    let closed = false;
    let retry: ReturnType<typeof setTimeout> | null = null;
    let attempt = 0;

    const scheduleReconnect = () => {
      if (closed) return;
      if (!getToken()) return;
      const baseDelay = Math.min(30_000, 1000 * Math.pow(2, Math.min(attempt, 5)));
      const jitter = Math.floor(Math.random() * 400);
      attempt += 1;
      retry = setTimeout(connect, baseDelay + jitter);
    };

    const connect = () => {
      if (closed) return;
      const token = getToken();
      if (!token) {
        onState?.(false);
        return;
      }
      const base = getBaseUrl().replace(/^http/i, "ws");
      let socket: WebSocket;
      try {
        socket = new WebSocket(`${base}/api/logs/stream?token=${encodeURIComponent(token)}`);
      } catch {
        if (!closed) {
          onState?.(false);
          scheduleReconnect();
        }
        return;
      }
      ws = socket;
      let openedAt = 0;

      socket.onopen = () => {
        if (closed || ws !== socket) {
          socket.close();
          return;
        }
        openedAt = Date.now();
        onState?.(true);
      };

      socket.onmessage = (event) => {
        if (closed || ws !== socket) return;
        try {
          const parsed: unknown = JSON.parse(String(event.data));
          if (isValidLogEntry(parsed)) onLog(parsed);
        } catch {
          // Ignore malformed frames and keep the stream alive.
        }
      };

      socket.onclose = () => {
        if (ws === socket) ws = null;
        if (closed) return;
        onState?.(false);
        // Only reset backoff after a genuinely healthy connection.
        if (openedAt > 0 && Date.now() - openedAt >= 5000) {
          attempt = 0;
        }
        scheduleReconnect();
      };

      socket.onerror = () => {
        if (closed) return;
        try {
          socket.close();
        } catch {
          // Ignore close errors.
        }
      };
    };

    connect();
    return () => {
      closed = true;
      if (retry) {
        clearTimeout(retry);
        retry = null;
      }
      if (ws) {
        const current = ws;
        ws = null;
        current.onopen = null;
        current.onmessage = null;
        current.onclose = null;
        current.onerror = null;
        try {
          current.close();
        } catch {
          // Ignore close errors on teardown.
        }
      }
    };
  },
};

export const defaultAppConfig = defaultConfig;
