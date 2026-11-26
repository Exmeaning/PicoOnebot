import type {
  AppConfig,
  AppliedResult,
  BotStatus,
  ConnectionInfo,
  FileDocument,
  FileEntry,
  FileListing,
  ForwardMode,
  GeneralConfig,
  HttpClient,
  HttpServer,
  LogEntry,
  LogLevel,
  LoginResult,
  MessageFormat,
  NetworkConfig,
  QrInfo,
  SaveConfigResult,
  SaveFileResult,
  WebuiConfig,
  WsClient,
  WsServer,
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
    return Object.prototype.hasOwnProperty.call(memoryStorage, key) ? (memoryStorage[key] ?? null) : null;
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

export function getErrorMessage(error: unknown, fallback: string): string {
  if (error instanceof Error && error.message) return error.message;
  return fallback;
}

export class ApiError extends Error {
  status: number;

  constructor(message: string, status = 0) {
    super(message);
    this.status = status;
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function strField(obj: Record<string, unknown>, key: string, fallback = ""): string {
  const v = obj[key];
  return typeof v === "string" ? v : fallback;
}

function optStrField(obj: Record<string, unknown>, key: string): string | undefined {
  const v = obj[key];
  return typeof v === "string" ? v : undefined;
}

function numField(obj: Record<string, unknown>, key: string, fallback = 0): number {
  const v = obj[key];
  return typeof v === "number" && Number.isFinite(v) ? v : fallback;
}

function optNumField(obj: Record<string, unknown>, key: string): number | undefined {
  const v = obj[key];
  return typeof v === "number" && Number.isFinite(v) ? v : undefined;
}

function boolField(obj: Record<string, unknown>, key: string, fallback = false): boolean {
  const v = obj[key];
  return typeof v === "boolean" ? v : fallback;
}

function optBoolField(obj: Record<string, unknown>, key: string): boolean | undefined {
  const v = obj[key];
  return typeof v === "boolean" ? v : undefined;
}

function isLogLevel(value: unknown): value is LogLevel {
  return value === "debug" || value === "info" || value === "warn" || value === "error";
}

function isForwardMode(value: unknown): value is ForwardMode {
  return value === "merge_one" || value === "separate" || value === "silent";
}

function isMessageFormat(value: unknown): value is MessageFormat {
  return value === "array" || value === "string";
}

function isConnectionKind(value: unknown): value is ConnectionInfo["kind"] {
  return value === "ws-server" || value === "ws-client" || value === "http-server" || value === "http-client";
}

function isQrStatus(value: unknown): value is QrInfo["status"] {
  return value === "online" || value === "ok" || value === "expired" || value === "waiting_qr";
}

function isValidLogEntry(value: unknown): value is LogEntry {
  if (!isRecord(value)) return false;
  return (
    typeof value.id === "number" &&
    Number.isFinite(value.id) &&
    typeof value.ts === "number" &&
    Number.isFinite(value.ts) &&
    isLogLevel(value.level) &&
    typeof value.tag === "string" &&
    typeof value.msg === "string"
  );
}

function parseConnectionInfo(value: unknown): ConnectionInfo | null {
  if (!isRecord(value) || !isConnectionKind(value.kind)) return null;
  if (typeof value.id !== "string" || typeof value.name !== "string" || typeof value.peer !== "string") return null;
  if (typeof value.enabled !== "boolean" || typeof value.connected !== "boolean") return null;
  return {
    id: value.id,
    kind: value.kind,
    name: value.name,
    peer: value.peer,
    enabled: value.enabled,
    connected: value.connected,
    peers: numField(value, "peers", 0),
    since: numField(value, "since", 0),
    detail: strField(value, "detail"),
  };
}

function parseJsonResponse<T>(data: unknown, validator: (value: unknown) => T | null, status: number): T {
  const parsed = validator(data);
  if (parsed === null) {
    throw new ApiError("后端返回了格式异常的响应数据", status);
  }
  return parsed;
}

function parseLoginResult(value: unknown): LoginResult | null {
  if (!isRecord(value) || typeof value.ok !== "boolean" || typeof value.token !== "string") return null;
  if (value.mustChangePassword !== undefined && typeof value.mustChangePassword !== "boolean") return null;
  return {
    ok: value.ok,
    token: value.token,
    mustChangePassword: value.mustChangePassword,
  };
}

function parseBotStatus(value: unknown): BotStatus | null {
  if (!isRecord(value) || typeof value.online !== "boolean") return null;
  return {
    online: value.online,
    selfId: strField(value, "selfId"),
    nickname: strField(value, "nickname"),
    avatar: strField(value, "avatar"),
    uptime: numField(value, "uptime"),
    version: strField(value, "version"),
    qqVersion: strField(value, "qqVersion"),
    platform: strField(value, "platform"),
    protocol: strField(value, "protocol"),
    msgSent: numField(value, "msgSent"),
    msgRecv: numField(value, "msgRecv"),
    lastRecvAt: optNumField(value, "lastRecvAt"),
    silenceMs: optNumField(value, "silenceMs"),
    readReportsSent: optNumField(value, "readReportsSent"),
    readReportsFailed: optNumField(value, "readReportsFailed"),
    syncTriggered: optNumField(value, "syncTriggered"),
    lastSyncAt: optNumField(value, "lastSyncAt"),
    memoryMb: numField(value, "memoryMb"),
    cpu: numField(value, "cpu"),
    friends: numField(value, "friends"),
    groups: numField(value, "groups"),
    connections: numField(value, "connections"),
    lastHeartbeat: numField(value, "lastHeartbeat"),
    webuiUrl: optStrField(value, "webuiUrl"),
    lanIp: optStrField(value, "lanIp"),
    canRestartQq: optBoolField(value, "canRestartQq"),
  };
}

function parseConnections(value: unknown): ConnectionInfo[] | null {
  if (!Array.isArray(value)) return null;
  const out: ConnectionInfo[] = [];
  for (const item of value) {
    const parsed = parseConnectionInfo(item);
    if (parsed) out.push(parsed);
  }
  return out;
}

function parseServerConn(value: unknown, defaultPort: number, defaultWebsocket: boolean): WsServer | null {
  if (!isRecord(value) || typeof value.id !== "string") return null;
  return {
    id: value.id,
    name: strField(value, "name"),
    enabled: boolField(value, "enabled", true),
    host: strField(value, "host", "0.0.0.0"),
    port: numField(value, "port", defaultPort),
    token: strField(value, "token"),
    cors: boolField(value, "cors", true),
    websocket: boolField(value, "websocket", defaultWebsocket),
  };
}

function parseWsClient(value: unknown): WsClient | null {
  if (!isRecord(value) || typeof value.id !== "string") return null;
  return {
    id: value.id,
    name: strField(value, "name"),
    enabled: boolField(value, "enabled", true),
    url: strField(value, "url"),
    token: strField(value, "token"),
    reconnectInterval: numField(value, "reconnectInterval", 5000),
  };
}

function parseHttpClient(value: unknown): HttpClient | null {
  if (!isRecord(value) || typeof value.id !== "string") return null;
  return {
    id: value.id,
    name: strField(value, "name"),
    enabled: boolField(value, "enabled", true),
    url: strField(value, "url"),
    token: strField(value, "token"),
    secret: strField(value, "secret"),
  };
}

function parseArrayItems<T>(raw: unknown, parseItem: (item: unknown) => T | null): T[] {
  if (!Array.isArray(raw)) return [];
  const out: T[] = [];
  for (const item of raw) {
    const parsed = parseItem(item);
    if (parsed !== null) out.push(parsed);
  }
  return out;
}

function parseWebuiConfig(value: unknown): WebuiConfig | null {
  if (!isRecord(value)) return null;
  return {
    host: strField(value, "host", "0.0.0.0"),
    port: numField(value, "port", 6099),
    passwordInitialized: boolField(value, "passwordInitialized", false),
  };
}

function parseNetworkConfig(value: unknown): NetworkConfig | null {
  if (!isRecord(value)) return null;
  const wsServers: WsServer[] = parseArrayItems(value.wsServers, (x) => parseServerConn(x, 3001, true));
  const httpServers: HttpServer[] = parseArrayItems(value.httpServers, (x) => parseServerConn(x, 3000, true));
  const wsClients: WsClient[] = parseArrayItems(value.wsClients, parseWsClient);
  const httpClients: HttpClient[] = parseArrayItems(value.httpClients, parseHttpClient);
  return { wsServers, wsClients, httpServers, httpClients };
}

function parseGeneralConfig(value: unknown): GeneralConfig | null {
  if (!isRecord(value)) return null;
  const general: GeneralConfig = {
    logLevel: isLogLevel(value.logLevel) ? value.logLevel : "info",
    forwardMode: isForwardMode(value.forwardMode) ? value.forwardMode : "merge_one",
    forwardMergeHeader: strField(value, "forwardMergeHeader", "〔合并转发〕"),
    mediaBaseUrl: strField(value, "mediaBaseUrl"),
    mediaBase64Limit: numField(value, "mediaBase64Limit", 4 * 1024 * 1024),
    sendRatePerSec: numField(value, "sendRatePerSec", 5),
    kernelCallTimeoutMs: numField(value, "kernelCallTimeoutMs", 30000),
    heartbeat: numField(value, "heartbeat", 5000),
    messageFormat: isMessageFormat(value.messageFormat) ? value.messageFormat : "array",
    reportSelfMessage: boolField(value, "reportSelfMessage", true),
  };

  const autoMarkRead = optBoolField(value, "autoMarkRead");
  if (autoMarkRead !== undefined) general.autoMarkRead = autoMarkRead;
  const readReportBatch = optNumField(value, "readReportBatch");
  if (readReportBatch !== undefined) general.readReportBatch = readReportBatch;
  const readReportIntervalSec = optNumField(value, "readReportIntervalSec");
  if (readReportIntervalSec !== undefined) general.readReportIntervalSec = readReportIntervalSec;
  const readReportMinGapMs = optNumField(value, "readReportMinGapMs");
  if (readReportMinGapMs !== undefined) general.readReportMinGapMs = readReportMinGapMs;
  const readReportMaxPerMin = optNumField(value, "readReportMaxPerMin");
  if (readReportMaxPerMin !== undefined) general.readReportMaxPerMin = readReportMaxPerMin;
  const silenceSyncThresholdSec = optNumField(value, "silenceSyncThresholdSec");
  if (silenceSyncThresholdSec !== undefined) general.silenceSyncThresholdSec = silenceSyncThresholdSec;
  const silenceSyncMaxAttempts = optNumField(value, "silenceSyncMaxAttempts");
  if (silenceSyncMaxAttempts !== undefined) general.silenceSyncMaxAttempts = silenceSyncMaxAttempts;
  const reverseMin = optNumField(value, "reverse_reconnect_min_ms");
  if (reverseMin !== undefined) general.reverse_reconnect_min_ms = reverseMin;
  const reverseMax = optNumField(value, "reverse_reconnect_max_ms");
  if (reverseMax !== undefined) general.reverse_reconnect_max_ms = reverseMax;
  const postTimeout = optNumField(value, "post_timeout_ms");
  if (postTimeout !== undefined) general.post_timeout_ms = postTimeout;
  const postQueue = optNumField(value, "post_queue_size");
  if (postQueue !== undefined) general.post_queue_size = postQueue;
  const eventQueue = optNumField(value, "event_queue_size");
  if (eventQueue !== undefined) general.event_queue_size = eventQueue;
  const probeDelay = optNumField(value, "kernel_probe_delay_ms");
  if (probeDelay !== undefined) general.kernel_probe_delay_ms = probeDelay;
  const webuiDir = optStrField(value, "webui_dir");
  if (webuiDir !== undefined) general.webui_dir = webuiDir;

  return general;
}

function parseAppConfig(value: unknown): AppConfig | null {
  if (!isRecord(value)) return null;
  const webui = parseWebuiConfig(value.webui);
  const network = parseNetworkConfig(value.network);
  const general = parseGeneralConfig(value.general);
  if (!webui || !network || !general) return null;
  return { webui, network, general };
}

function parseFileEntry(value: unknown): FileEntry | null {
  if (!isRecord(value)) return null;
  if (typeof value.name !== "string" || typeof value.path !== "string") return null;
  if (value.type !== "directory" && value.type !== "file") return null;
  return {
    name: value.name,
    path: value.path,
    type: value.type,
    size: numField(value, "size"),
    modifiedAt: numField(value, "modifiedAt"),
    editable: boolField(value, "editable"),
  };
}

function parseFileListing(value: unknown): FileListing | null {
  if (!isRecord(value) || typeof value.root !== "string" || typeof value.path !== "string" || !Array.isArray(value.entries)) {
    return null;
  }
  const parent = typeof value.parent === "string" ? value.parent : null;
  const activeConfig = value.activeConfig ? parseFileEntry(value.activeConfig) : null;
  return {
    root: value.root,
    path: value.path,
    parent,
    activeConfig,
    entries: parseArrayItems(value.entries, parseFileEntry),
  };
}

function parseFileDocument(value: unknown): FileDocument | null {
  if (
    !isRecord(value) ||
    typeof value.path !== "string" ||
    typeof value.name !== "string" ||
    typeof value.content !== "string"
  ) {
    return null;
  }
  return {
    path: value.path,
    name: value.name,
    content: value.content,
    size: numField(value, "size"),
    modifiedAt: numField(value, "modifiedAt"),
  };
}

function parseAppliedResult(value: unknown): AppliedResult | undefined {
  if (!isRecord(value)) return undefined;
  return {
    reloaded: boolField(value, "reloaded"),
    listeners: numField(value, "listeners"),
    reverse: numField(value, "reverse"),
    reporters: numField(value, "reporters"),
    appliedAt: numField(value, "appliedAt"),
  };
}

function parseStringArray(value: unknown): string[] | undefined {
  if (!Array.isArray(value)) return undefined;
  return value.filter((item): item is string => typeof item === "string");
}

function parseSaveConfigResult(value: unknown): SaveConfigResult | null {
  if (!isRecord(value) || typeof value.ok !== "boolean") return null;
  return {
    ok: value.ok,
    restartRequired: optBoolField(value, "restartRequired"),
    warnings: parseStringArray(value.warnings),
    applied: parseAppliedResult(value.applied),
  };
}

function parseSaveFileResult(value: unknown): SaveFileResult | null {
  if (!isRecord(value) || typeof value.ok !== "boolean") return null;
  return {
    ok: value.ok,
    size: numField(value, "size"),
    modifiedAt: numField(value, "modifiedAt"),
    restartRequired: boolField(value, "restartRequired"),
    warnings: parseStringArray(value.warnings),
    applied: parseAppliedResult(value.applied),
  };
}

function parseLogEntries(value: unknown): LogEntry[] | null {
  if (!Array.isArray(value)) return null;
  return value.filter(isValidLogEntry);
}

function parseQrInfo(value: unknown): QrInfo | null {
  if (!isRecord(value) || !isQrStatus(value.status)) return null;
  return {
    status: value.status,
    online: optBoolField(value, "online"),
    url: strField(value, "url"),
    image: strField(value, "image"),
    ageSeconds: numField(value, "ageSeconds"),
    expiresIn: numField(value, "expiresIn"),
    scanned: boolField(value, "scanned"),
    hasFragment: boolField(value, "hasFragment"),
    hasStateFragment: boolField(value, "hasStateFragment"),
    user_id: optNumField(value, "user_id"),
  };
}

function parseOkOnly(value: unknown): { ok: boolean } | null {
  if (!isRecord(value) || typeof value.ok !== "boolean") return null;
  return { ok: value.ok };
}

function parseRestartResult(value: unknown): { ok: boolean; wording?: string } | null {
  if (!isRecord(value) || typeof value.ok !== "boolean") return null;
  return { ok: value.ok, wording: optStrField(value, "wording") };
}

function parseInitPasswordResult(value: unknown): { ok: boolean; message?: string; token?: string } | null {
  if (!isRecord(value) || typeof value.ok !== "boolean") return null;
  return {
    ok: value.ok,
    message: optStrField(value, "message"),
    token: optStrField(value, "token"),
  };
}

function parseChangePasswordResult(value: unknown): { ok: boolean; message?: string; sessionsRevoked?: boolean } | null {
  if (!isRecord(value) || typeof value.ok !== "boolean") return null;
  return {
    ok: value.ok,
    message: optStrField(value, "message"),
    sessionsRevoked: optBoolField(value, "sessionsRevoked"),
  };
}

type HttpOptions<T> = {
  skipAuth?: boolean;
  suppressUnauthorized?: boolean;
  /** Optional base URL override: "" uses same-origin, non-empty uses that URL, undefined uses stored baseUrl. */
  baseUrlOverride?: string;
  parse: (value: unknown) => T | null;
};

async function http<T>(path: string, init: RequestInit, options: HttpOptions<T>): Promise<T> {
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
      const body: unknown = await response.json();
      if (isRecord(body) && typeof body.message === "string") {
        errorMessage = body.message;
      }
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

  let data: unknown;
  try {
    data = await response.json();
  } catch {
    throw new ApiError("后端返回了无效的 JSON 响应", response.status);
  }
  return parseJsonResponse(data, options.parse, response.status);
}

export const api = {
  login: (token: string, baseUrlOverride?: string) =>
    http<LoginResult>(
      "/api/auth/login",
      { method: "POST", body: JSON.stringify({ token }) },
      { skipAuth: true, baseUrlOverride, parse: parseLoginResult }
    ),
  logout: async () => {
    try {
      await http<{ ok: boolean }>(
        "/api/logout",
        { method: "POST" },
        { suppressUnauthorized: true, parse: parseOkOnly }
      );
    } catch {
      // Clear the local session even when the backend is unavailable.
    }
    setToken("");
  },
  status: (signal?: AbortSignal) =>
    http<BotStatus>("/api/status", { signal }, { parse: parseBotStatus }),
  connections: (signal?: AbortSignal) =>
    http<ConnectionInfo[]>("/api/connections", { signal }, { parse: parseConnections }),
  getConfig: (signal?: AbortSignal) =>
    http<AppConfig>("/api/config", { signal }, { parse: parseAppConfig }),
  saveConfig: (config: Partial<AppConfig>) =>
    http<SaveConfigResult>(
      "/api/config",
      {
        method: "PUT",
        body: JSON.stringify(config),
      },
      { parse: parseSaveConfigResult }
    ),
  files: (path = "", signal?: AbortSignal) =>
    http<FileListing>(`/api/files?path=${encodeURIComponent(path)}`, { signal }, { parse: parseFileListing }),
  file: (path: string, signal?: AbortSignal) =>
    http<FileDocument>(`/api/files/content?path=${encodeURIComponent(path)}`, { signal }, { parse: parseFileDocument }),
  saveFile: (path: string, content: string, expectedModifiedAt: number) =>
    http<SaveFileResult>(
      "/api/files/content",
      {
        method: "PUT",
        body: JSON.stringify({ path, content, expectedModifiedAt }),
      },
      { parse: parseSaveFileResult }
    ),
  logs: (limit = 200, signal?: AbortSignal) =>
    http<LogEntry[]>(`/api/logs?limit=${limit}`, { signal }, { parse: parseLogEntries }),
  restart: () =>
    http<{ ok: boolean; wording?: string }>("/api/restart", { method: "POST" }, { parse: parseRestartResult }),

  /** 初始化 WebUI 密码（后端会清空预初始化会话并下发轮换后的新会话 token）。 */
  initializeWebuiPassword: (token: string) =>
    http<{ ok: boolean; message?: string; token?: string }>(
      "/api/webui/token",
      {
        method: "PUT",
        body: JSON.stringify({ token }),
      },
      { parse: parseInitPasswordResult }
    ),

  /** 修改已初始化的控制台密码。成功后所有会话失效,需要重新登录。 */
  changeWebuiPassword: (current: string, token: string) =>
    http<{ ok: boolean; message?: string; sessionsRevoked?: boolean }>(
      "/api/webui/token",
      {
        method: "PUT",
        body: JSON.stringify({ current, token }),
      },
      { parse: parseChangePasswordResult }
    ),

  /** 让 QQ 退出登录、回到扫码页(与 WebUI 会话登出不同)。 */
  accountLogout: () =>
    http<{ ok: boolean }>("/api/account/logout", { method: "POST" }, { parse: parseOkOnly }),

  /** 登录二维码:GET 只读;refresh 主动向 QQ 要新码(停在账号状态页时会先替用户点"切换账号")。 */
  qr: (signal?: AbortSignal) => http<QrInfo>("/api/qr", { signal }, { parse: parseQrInfo }),
  qrRefresh: (signal?: AbortSignal) => http<QrInfo>("/api/qr/refresh", { method: "POST", signal }, { parse: parseQrInfo }),

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
        if (typeof event.data !== "string") return;
        try {
          const parsed: unknown = JSON.parse(event.data);
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
      if (retry !== null) {
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
