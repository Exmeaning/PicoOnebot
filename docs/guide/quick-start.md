# 快速入门

PicoOnebot 提供了两种核心使用形态：
1. **APK 直接安装**：适合在闲置 Android 手机、平板、机顶盒或电脑 Android 模拟器上快速测试运行；
2. **Docker 容器部署**：适合在 Linux 云服务器或物理机上长期无头（Headless）运行。

---

## 5 分钟上手

::: code-group

```bash [Docker 部署（推荐服务器）]
# 1. 在宿主机检查内核 Binder 支持
bash docker/scripts/pico-host-check.sh

# 2. 单命令启动容器
docker run -d --name pico-onebot --privileged --shm-size 1g \
  -p 6099:6099 -p 3001:3001 -v pico-data:/data \
  --restart unless-stopped \
  ghcr.io/exmeaning/pico-onebot:latest

# 3. 等待约 2-3 分钟系统初始化，当 docker ps 显示 healthy 时完成就绪
```

```text [APK 直装（推荐个人设备/模拟器）]
1. 前往 GitHub Releases 下载最新版本的 PicoOnebot.apk；
2. 安装至 Android 设备或常见模拟器（MuMu、雷电、逍遥等）；
3. 打开应用并保持后台运行。
```

:::

---

## 访问与配置

### 1. 登录 WebUI 控制台

在浏览器中打开：
```text
http://<设备或服务器IP>:6099
```

- **默认初始密码**：`picopico`
- 首次登录后系统会强制提示修改管理密码，请妥善保存新密码。

### 2. 扫码登录 QQ

1. 登录 Web 控制台后，主页面将展示登录二维码；
2. 打开手机 QQ 客户端，使用「扫一扫」扫描屏幕上的二维码并确认登录；
3. 网页状态将刷新为「已登录」，并显示当前机器人的昵称与 QQ 账号。

### 3. 配置上报地址与对接框架

进入控制台的「网络配置」界面，添加机器人框架连接端点：

- **反向 WebSocket（最常用）**：
  - 目标地址：`ws://<机器人框架IP>:<端口>/onebot/v11/ws`
  - Access Token：如果框架配置了 Token，请在此填写一致的密钥。
- **正向 WebSocket**：
  - 监听端口：默认为 `3001`
  - 框架主动连接：`ws://<PicoOnebotIP>:3001`
- **HTTP 上报**：
  - POST 上报地址与 HTTP API 监听端口。

保存配置后立即生效，框架即可开始接收消息事件并调用 API 发送消息。

---

## 下一步

- 详细查看全平台部署指引：[部署方案总览](/deploy/)
- 与主流机器人框架对接实操：
  - [对接 NoneBot2](/frameworks/nonebot2)
  - [对接 Koishi](/frameworks/koishi)
  - [对接 AstrBot](/frameworks/astrbot)
