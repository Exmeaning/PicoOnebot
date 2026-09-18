# OneBot 网络上报配置

PicoOnebot 严格遵循 **OneBot v11** 协议标准，支持正向 WebSocket、反向 WebSocket 与 HTTP 通信通道。

---

## 通信协议选型

| 模式 | 常用场景 | 数据流向 | 适用框架 |
| --- | --- | --- | --- |
| **反向 WebSocket (推荐)** | 生产部署首选 | PicoOnebot 主动连接框架的 WebSocket 服务端 | NoneBot2, Koishi, AstrBot |
| **正向 WebSocket** | 本地测试 / 调试工具 | 框架作为客户端连接 PicoOnebot 开放的端口 | 部分单机客户端与测试套件 |
| **HTTP 通信** | 无长连接场景 / Webhook | PicoOnebot POST 事件给后端，后端调用 HTTP API | 传统 Web 框架 (Django/Flask/FastAPI) |

---

## 配置方法

所有网络配置项均可在 **WebUI 控制台 (http://\<IP\>:6099) ->「网络配置」** 中直接维护。

### 1. 反向 WebSocket 配置（推荐）

这是绝大多数机器人框架所采用的最佳实践模式：框架充当 WebSocket Server 监听端口，PicoOnebot 充当客户端主动发起连接，断线自动重连。

- **URL (目标地址)**：`ws://<框架所在IP>:<框架端口>/onebot/v11/ws/`
  - 例如与同机部署的 NoneBot2 通信：`ws://127.0.0.1:8080/onebot/v11/ws`
  - 若在 Docker 桥接网络中互联，可使用容器服务名或宿主机局域网 IP；
- **Access Token (访问令牌)**：可选。若框架配置了身份验证密钥，此处填写相同字符串；
- **重连间隔 (Reconnect Interval)**：断开连接后的重试等待毫秒数（默认通常为 `3000` 毫秒）。

### 2. 正向 WebSocket 配置

若机器人框架仅支持作为客户端主动连接协议端：

- **监听端口**：默认为 `3001`（需确保 Docker 启动命令或 compose 文件中已映射该端口，如 `-p 3001:3001`）；
- **Access Token**：可选，客户端连接请求头携带 `Authorization: Bearer <Token>`。

### 3. HTTP 通信配置

- **HTTP API 监听**：接收框架调用的 API（如发送消息）；
- **HTTP POST 上报**：将收到的事件实时通过 HTTP POST 发送给下游 Webhook 接收端。
