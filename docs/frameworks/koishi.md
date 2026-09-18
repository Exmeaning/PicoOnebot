# 对接 Koishi

[Koishi](https://koishi.chat/) 是一款基于 Node.js / TypeScript 开发的跨平台、模块化机器人框架。通过官方提供的 `@koishijs/plugin-adapter-onebot` 插件，可以方便地接入 PicoOnebot。

---

## 1. 安装适配器插件

1. 打开并进入 Koishi Web 管理控制台（默认端口通常为 `5140`）；
2. 在左侧菜单点击 **「插件市场」**；
3. 搜索并安装 **`adapter-onebot`**（即 `@koishijs/plugin-adapter-onebot`）；
4. 安装完成后进入该插件的配置页面。

---

## 2. 配置反向 WebSocket（推荐）

在生产环境中推荐使用反向 WebSocket：Koishi 作为服务端监听路径，PicoOnebot 作为客户端主动建立长连接。

### 1. Koishi 端配置
在 `adapter-onebot` 插件配置界面中进行如下设置：
- **通信协议 (protocol)**：选择 `ws-reverse`；
- **机器人账号 (selfId)**：填入已登录机器人的 QQ 号；
- **路径 (path)**：默认为 `/onebot`（两端路径必须保持一致）；
- **访问令牌 (token)**：可选。若设置了密钥，需在 PicoOnebot 端配置相同的 Token；若未设置请保持为空；
- 配置完成后点击 **「保存配置」** 并启用插件。

### 2. PicoOnebot 端配置
1. 打开浏览器进入 PicoOnebot 控制台（`http://<IP>:6099`）；
2. 进入 **「网络配置」** 页面，点击 **「添加连接」**；
3. 选择协议类型为 **反向 WebSocket**：
   - **URL (目标地址)**：`ws://<Koishi所在IP>:5140/onebot`
     - 若两者在同一宿主机的 Docker 环境中，可使用容器服务名或 Docker 桥接网关互联（如 `ws://koishi:5140/onebot` 或 `ws://172.17.0.1:5140/onebot`）；
     - 请勿在容器环境内将目标地址填写为 `127.0.0.1`，否则容器会试图连接其自身的端口；
   - **Access Token**：填写在 Koishi 中设置的 token，若未设置则留空；
4. 保存配置并确认启用。

---

## 3. 验证与排查

1. 观察 Koishi 控制台右下角的状态栏，若 OneBot 适配器状态点变为绿色并正确展示了机器人的头像和昵称，即表示长连接建立成功；
2. 在 QQ 好友或群聊中发送消息，验证 Koishi 插件的消息捕获与响应流程；
3. 若提示连接失败，请确认：
   - Koishi 的服务端口（默认 `5140`）是否已在宿主机防火墙放行；
   - 反向 WebSocket 地址中的路径是否与 Koishi 插件中填写的 `path`（默认为 `/onebot`）严格一致。
