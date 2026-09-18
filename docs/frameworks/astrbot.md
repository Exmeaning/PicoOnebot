# 对接 AstrBot

[AstrBot](https://github.com/Soulter/AstrBot) 是由 Soulter 开源的现代多平台 AI 智能聊天机器人助理框架。本文介绍如何通过 OneBot v11 反向 WebSocket 协议将 PicoOnebot 接入 AstrBot。

---

## 1. 在 AstrBot 中创建 OneBot v11 实例

1. 打开并登录 AstrBot 的 WebUI 管理后台；
2. 在左侧菜单栏点击 **「机器人」**；
3. 点击 **「+ 创建机器人」**，在弹出窗口的协议选择中选择 **「OneBot v11」**；
4. 填写配置项：
   - **ID**：自定义标识名称（例如 `pico_bot`）；
   - **启用**：勾选启用；
   - **反向 WebSocket 主机地址**：填写 `0.0.0.0`（允许来自外部及容器的连接）；
   - **反向 WebSocket 端口**：默认为 `6199`（确保该端口未被占用，若使用 Docker 部署需映射该端口）；
   - **反向 WebSocket Token**：可选设置。若填写，后续需在 PicoOnebot 中填入完全一致的 Token；
5. 点击 **「保存」**。

---

## 2. 在 PicoOnebot 中配置反向 WebSocket

1. 登录 PicoOnebot WebUI 控制台（`http://<IP>:6099`）；
2. 导航至左侧 **「网络配置」**；
3. 点击 **「添加连接」**，选择连接类型为 **反向 WebSocket**：
   - **URL (目标地址)**：`ws://<AstrBot所在IP>:6199/ws`
     - 若两者在同一宿主机的 Docker 环境中，建议通过 Docker 内部网络使用容器名互通，例如 `ws://astrbot:6199/ws`；
     - 若在不同机器或宿主机直接运行，请填写 AstrBot 所在的宿主机内网 IP（例如 `ws://192.168.1.100:6199/ws`）；请勿直接在容器内填写 `127.0.0.1`；
   - **Access Token**：若在 AstrBot 中设置了 Token，在此处填写完全相同的值；若未设置则留空；
4. 保存配置并确认该连接项处于启用状态。

---

## 3. 验证连接

1. 查看 AstrBot 终端或 Web 控制台日志，若输出类似如下信息：
   ```text
   [INFO] aiocqhttp(OneBot v11) 适配器已连接
   ```
   即表示通信连接成功建立；
2. 在 QQ 群聊或私聊中向机器人发送唤醒词，测试 AstrBot 消息响应与对话是否正常。
