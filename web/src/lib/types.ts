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

/** 保存配置后后端**实际**做了什么。前端的提示语只许复述它,不许自己宣布已生效。 */
export interface AppliedResult {
  /** 网络段有变化、确实重载过才是 true;只是写盘就是 false。 */
  reloaded: boolean;
  listeners: number;
  reverse: number;
  reporters: number;
  appliedAt: number;
}

export interface SaveConfigResult {
  ok: boolean;
  restartRequired?: boolean;
  warnings?: string[];
  applied?: AppliedResult;
}

export interface SaveFileResult {
  ok: boolean;
  size: number;
  modifiedAt: number;
  restartRequired: boolean;
  warnings?: string[];
  applied?: AppliedResult;
}

export interface ConnectionInfo {
  id: string;
  kind: "ws-server" | "ws-client" | "http-server" | "http-client";
  name: string;
  peer: string;
  /** 配置里勾没勾上。**不等于**连上了,别拿它当在线灯。 */
  enabled: boolean;
  /** 监听项:端口真的起来了;反向连接:握手真的完成了。 */
  connected: boolean;
  /** 监听项当前挂着的对端数量。 */
  peers: number;
  /** connected 为真时的起始时刻,否则 0。 */
  since: number;
  /** 后端给的人话状态,例如「监听中 · 暂无客户端」「重连中…」。 */
  detail: string;
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
