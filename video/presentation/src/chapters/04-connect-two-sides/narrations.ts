import type { Narration } from "../../registry/types";

export const narrations: Narration[] = [
  "回到 PicoOnebot。打开网络配置。添加一条反向 WebSocket。",
  "目标地址填机器人真正提供的 ws 或 wss 地址。路径也要和对方一致。",
  "机器人在局域网，就填它的内网 IP。有公网入口，也可以直接填公网域名。",
  "注意别混淆。浏览器里的 127.0.0.1 指 Pico 控制台。反向 WebSocket 目标如果也填 127.0.0.1，连到的还是手机自己。",
  "对方如果设了 Token，PicoOnebot 里填同一个。没设就留空。",
  "保存配置就会热重载。不用重启 QQ。断线也会自动重连。",
  "去机器人端看连接状态。OneBot v11 连接建立后，再发个唤醒词测试。能正常回复，这台手机就挂好了。",
];
