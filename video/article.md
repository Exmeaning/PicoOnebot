# PicoOnebot 项目介绍素材

## 1. 项目定位

PicoOnebot 是基于官方手表 QQ（Watch QQ / QQ Lite）的 Android 原生 OneBot v11 协议端。项目通过字节码注入与 Native Hook，在手表 QQ 进程内构建 OneBot 通信服务；不要求 Root 或 Xposed，并提供独立 WebUI。项目当前仍处于极早期开发阶段。当前仓库最新发布版为 v0.13，主体语言为 Kotlin，采用 GPL-3.0 许可证；项目仅供技术研究与交流，不包含或分发腾讯专有二进制文件。

## 2. 为什么选择手表 QQ

常见桌面 NT QQ Hook 方案功能更完整，但依赖桌面环境和较重的运行时。在 Linux 无头服务器上，通常还要准备虚拟桌面、Wine 或 Xvfb，内存占用约 500MB 至 1.5GB。手表 QQ 为穿戴设备裁掉了大量富媒体、空间和小程序能力，核心进程通常占用约 100MB 至 250MB。PicoOnebot 用更轻的能力边界换取较低资源占用、扫码登录和容器化部署。

## 3. 核心能力

PicoOnebot 支持 OneBot v11 的正向 WebSocket、反向 WebSocket、HTTP API 与 HTTP POST 事件上报，可连接 NoneBot2、Koishi、AstrBot 等框架。它支持私聊与群聊的文本、图片、语音、视频和文件收发，可读取与解析合并转发，并能查询基础好友、群、成员与陌生人信息。

内置 WebUI 默认监听 6099 端口。管理员可以在浏览器中查看登录二维码、配置 WebSocket 或 HTTP 连接与 Access Token、观察实时日志和运行状态，并浏览或编辑文本配置文件。网络配置保存后会热重载，不需要重启 QQ。

## 4. 两种部署路径

个人设备可以从 Releases 下载 APK，安装到支持 32 位应用的 Android 真机、虚拟机或模拟器。服务器可以运行一体化 Docker 镜像。镜像包含 Redroid Android 13、ARM 转译层、PicoOnebot APK 和守护服务；默认暴露 WebUI 的 6099 端口与 OneBot 的 3001 端口。Docker 宿主机必须提供 Binder IPC，并允许特权容器。首次启动一般需要 2 至 5 分钟，健康状态变为 healthy 后再访问 WebUI。

## 5. 已知边界

手表 QQ 的原生能力经过高度精简。群禁言、移出群聊、设置管理员、修改群名片与群公告等群管理能力暂未支持或仍待验证。合并转发可以接收和解析，但发送时会降级为单条文本或连续消息。项目也不支持特种动画表情、戳一戳、频道和红包等扩展能力。

APK 依赖 32 位运行环境。纯 64 位且没有转译层的手机无法直接安装。Docker 方案不支持缺少 Binder 的 Docker Desktop、原生 WSL2、OpenVZ 或 LXC 环境。项目尚缺少大规模高并发与长期无人值守验证，生产使用前应阅读文档并评估风险。
