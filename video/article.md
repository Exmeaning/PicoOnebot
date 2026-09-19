# 免 Root 手机端挂 AstrBot 的方法

## 1. 方案定位

PicoOnebot 是基于官方手表 QQ 的 Android 原生 OneBot v11 协议端，免 Root、免 Xposed。它适合把一台仍支持 32 位应用的 Android 手机变成 QQ 协议端，再通过反向 WebSocket 连接运行在电脑或服务器上的 AstrBot。与成熟的桌面端 NapCat 相比，PicoOnebot 功能更少，但不需要在手机上准备 Root 或 Xposed，也不需要为协议端保留一套 PC QQ 桌面环境。

## 2. 手机端安装

从 GitHub Releases 下载最新 PicoOnebot APK 并安装。手表 QQ 底包只提供 `armeabi-v7a` 原生库，因此设备必须支持 32 位应用；纯 64 位且没有厂商转译层的机型无法直接安装。启动应用并授予必要权限后，在同一局域网的浏览器访问 `http://<手机IP>:6099`。初始密码是 `picopico`，首次登录后需要修改。进入扫码页，用另一个已登录 QQ 的设备扫码，让机器人账号上线。

## 3. AstrBot 配置

在 AstrBot WebUI 打开“机器人”，创建 OneBot v11 实例。自定义 ID，开启实例，把反向 WebSocket 主机设为 `0.0.0.0`，端口设为 `6199`。Token 可以自定义，但 PicoOnebot 两边必须完全一致。若 AstrBot 运行在 Docker 中，还要把 6199 端口映射到宿主机。

## 4. PicoOnebot 配置

回到 PicoOnebot WebUI 的“网络配置”，添加“反向 WebSocket”。目标地址填写 `ws://<AstrBot所在IP>:6199/ws`，并填入同一个 Token。手机与 AstrBot 不在同一容器或进程里时，不要填写 `127.0.0.1`；局域网部署应填写 AstrBot 电脑的局域网 IP。保存后网络配置会热重载，不需要重启 QQ。

## 5. 验证与边界

AstrBot 日志出现 `aiocqhttp(OneBot v11) 适配器已连接` 后，在私聊或群聊中发送 AstrBot 唤醒词测试。PicoOnebot 支持基础私聊、群聊和常用媒体消息，但群禁言、踢人、群名片、群公告等能力暂未支持或仍待验证；它不是 NapCat 的全功能复刻，更适合免 Root 手机、备用 Android 设备和轻量 OneBot 接入场景。
