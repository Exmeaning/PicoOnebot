<div align="center">

<img src="docs/logo.png" alt="PicoOnebot" width="360" />

# PicoOnebot

**基于watch NT QQ 的 Android 原生 OneBot v11 协议端。**  
免 Root、免 Xposed，内置现代 WebUI 管理控制台。

[![Documentation](https://img.shields.io/badge/docs-GitHub_Pages-blue?logo=github)](https://exmeaning.github.io/PicoOnebot/)
[![License](https://img.shields.io/badge/license-GPL--3.0-green.svg)](LICENSE)

[官方文档站](https://exmeaning.github.io/PicoOnebot/) | [产物下载](../../releases)

</div>

---

## 获取产物

**请直接前往 [Releases](../../releases) 页面下载最新构建的 `PicoOnebot_<version>.apk` 安装包。**


---

## 核心功能

- **OneBot v11 协议实现**：
  - 支持正向 / 反向 WebSocket 连接；
  - 支持 HTTP API 与 HTTP POST 事件上报；
  - 兼容 NoneBot2、Koishi、AstrBot 等主流 OneBot 框架。
- **消息收发与处理**：
  - 私聊与群聊文本消息收发；
  - 图片、语音、视频、文件收发；
  - 合并转发读取与解析；
  - 基础好友 / 群 / 成员 / 陌生人信息查询。
- **开箱即用的内置 WebUI**：
  - 内置独立 Web 管理控制台（默认端口 `6099`）；
  - 浏览器内查看登录二维码并完成登录；
  - 可视化配置 WebSocket / HTTP 连接与 Access Token；
  - 实时日志流查看与系统运行状态监控；
  - 浏览、查看和编辑 PicoOnebot 数据目录中的文本配置文件。


---

本项目目前处于**极早期开发阶段**。相比 **NapCat**、**LLOneBot** 等成熟且经过长期生产检验的 PC 桌面方案，本项目存在较多缺陷与不足：

1. **协议支持较为有限**：
   - 手表 QQ 原生能力经过高度精简，缺乏桌面端庞大的交互体系；
   - **群管理功能暂未支持 / 待开发验证**：禁言、移出群聊、设置管理员、修改群名片、群公告等功能暂未支持；
   - **合并转发消息发送采用降级处理**：支持接收与解析合并转发，但发送合并转发消息受手表端协议限制会自动降级（降级为单条文本或连续消息发送）；
   - **不支持**特种动画表情、戳一戳、频道、红包等高级扩展功能。
2. **运行环境门槛**：
   - 依赖 Android 运行环境（真机、模拟器、Waydroid 或 redroid 容器）；
   - 手表 QQ 底包仅提供 32 位（`armeabi-v7a`）原生库，纯 64 位且厂商未内置转译层的手机（如 Pixel 7 及之后）无法直接安装；
   - 在 x86 Linux 上需要 32 位 ARM 转译层（如 libndk）及 binder 支持，配置门槛高于开箱即用的 PC 桌面无头方案。
3. **稳定性与验证尚浅**：
   - 缺少大规模高并发群聊与长期无人值守验证，网络异常重连与极端掉线场景仍在打磨。

---

## 快速上手

### 方式一：Android 设备 / 模拟器（APK 安装）

1. 前往 [Releases](../../releases) 下载最新 APK 安装至 Android 设备或模拟器；
   > 设备须支持运行 32 位应用。若安装报错 `INSTALL_FAILED_NO_MATCHING_ABIS`，说明该机型不支持 32 位，可改用光速虚拟机 / VMOS Pro 等自带转译的手机虚拟机、电脑模拟器或下方的 Docker 方案，详见 [纯 64 位设备的备选方案](https://exmeaning.github.io/PicoOnebot/deploy/apk#无法安装-纯-64-位设备的备选方案)。
2. 启动应用，使用同一局域网下的浏览器访问 `http://<设备IP>:6099` 进入 WebUI（初始密码 `picopico`，首次登录需修改）；
3. 在 WebUI 查看二维码，手机 QQ 扫码登录；
4. 在「网络配置」中添加反向 WebSocket 或 HTTP 上报地址，即可连接机器人框架。

### 方式二：Docker 容器（无头服务器）

基于 Redroid (Android 13) 构建的一体化镜像，已内置 ARM 转译层与 PicoOnebot APK。

> **注意**：容器依赖宿主机内核提供 **Binder IPC** 支持。不支持 Docker Desktop (Windows / macOS)、原生 WSL2、OpenVZ/LXC VPS 等未集成 Binder 的环境。

部署前可检测宿主机环境：

```bash
bash docker/scripts/pico-host-check.sh
```

启动容器：

```bash
docker run -d --name pico-onebot --privileged --shm-size 1g \
  -p 6099:6099 -p 3001:3001 -v pico-data:/data \
  --restart unless-stopped ghcr.io/exmeaning/pico-onebot:latest
```

首次启动约需 2–5 分钟完成系统初始化与 APK 安装，`docker ps` 显示 `healthy` 状态后即可访问 WebUI。

- **官方完整文档站**：[https://exmeaning.github.io/PicoOnebot/](https://exmeaning.github.io/PicoOnebot/)
- 详细部署与运维说明：[docker/README.md](docker/README.md)
- Binder 内核配置与排错：[docs/deploy/binder.md](docs/deploy/binder.md)

---

## 许可证

- 遵循 **GPL-3.0** 开源许可证。
- 本项目仅供技术研究与交流使用，**不包含、不分发任何腾讯专有二进制代码**。
