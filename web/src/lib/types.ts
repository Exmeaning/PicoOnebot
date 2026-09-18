export type LogLevel = "debug" | "info" | "warn" | "error";

export interface LogEntry {
  id: number;
  ts: number;
  level: LogLevel;
  tag: string;
  msg: string;
}

export interface BotStatus {
  online: boolean;
  selfId: string;
  nickname: string;
  avatar: string;
  uptime: number;
  version: string;
  qqVersion: string;
  platform: string;
  protocol: string;
  msgSent: number;
  msgRecv: number;
  memoryMb: number;
  cpu: number;
  friends: number;
  groups: number;
  connections: number;
  lastHeartbeat: number;
  webuiUrl?: string;
  lanIp?: string;
  canRestartQq?: boolean;
}

export interface WsServer {
  id: string;
  name: string;
  enabled: boolean;
  host: string;
  port: number;
  token: string;
  heartbeat: number;
  messageFormat: "array" | "string";
  reportSelfMessage: boolean;
  debug: boolean;
}

export interface WsClient {
  id: string;
  name: string;
  enabled: boolean;
  url: string;
  token: string;
  heartbeat: number;
  reconnectInterval: number;
  messageFormat: "array" | "string";
  reportSelfMessage: boolean;
  debug: boolean;
}

export interface HttpServer {
  id: string;
  name: string;
  enabled: boolean;
  host: string;
  port: number;
  token: string;
  cors: boolean;
  websocket: boolean;
  messageFormat: "array" | "string";
  debug: boolean;
}

export interface HttpClient {
  id: string;
  name: string;
  enabled: boolean;
  url: string;
  token: string;
  secret: string;
  heartbeat: number;
  messageFormat: "array" | "string";
  reportSelfMessage: boolean;
  debug: boolean;
}

export interface NetworkConfig {
  wsServers: WsServer[];
  wsClients: WsClient[];
  httpServers: HttpServer[];
  httpClients: HttpClient[];
}

export type ForwardMode = "merge_one" | "separate" | "silent";

export interface GeneralConfig {
  logLevel: LogLevel;
  forwardMode: ForwardMode;
  forwardMergeHeader: string;
  mediaBaseUrl: string;
  mediaBase64Limit: number;
  sendRatePerSec: number;
  kernelCallTimeoutMs: number;
}

export interface WebuiConfig {
  host: string;
  port: number;
  passwordInitialized: boolean;
}

export interface AppConfig {
  webui: WebuiConfig;
  network: NetworkConfig;
  general: GeneralConfig;
}

export interface LoginResult {
  ok: boolean;
  token: string;
  mustChangePassword?: boolean;
}

export interface FileEntry {
  name: string;
  path: string;
  type: "directory" | "file";
  size: number;
  modifiedAt: number;
  editable: boolean;
}

export interface FileListing {
  root: string;
  path: string;
  parent: string | null;
  activeConfig: FileEntry | null;
  entries: FileEntry[];
}

export interface FileDocument {
  path: string;
  name: string;
  content: string;
  size: number;
  modifiedAt: number;
}

export interface SaveFileResult {
  ok: boolean;
  size: number;
  modifiedAt: number;
  restartRequired: boolean;
  warnings?: string[];
}

export interface ConnectionInfo {
  id: string;
  kind: "ws-server" | "ws-client" | "http-server" | "http-client";
  name: string;
  peer: string;
  connected: boolean;
  since: number;
  sent: number;
  recv: number;
}

/** `GET /api/qr` / `POST /api/qr/refresh`:image 是 QQ 登录 SDK 给宿主的那张图(data URL),没有时看 url。 */
export interface QrInfo {
  status: "online" | "ok" | "expired" | "waiting_qr";
  online?: boolean;
  url: string;
  image: string;
  ageSeconds: number;
  expiresIn: number;
  scanned: boolean;
  hasFragment: boolean;
  hasStateFragment: boolean;
  user_id?: number;
}
