# 免 Root 手机端挂 AstrBot 的方法

## 1. 方案定位

PicoOnebot 是基于官方手表 QQ 的 Android 原生 OneBot v11 协议端，免 Root、免 Xposed。它适合把一台仍支持 32 位应用的 Android 手机变成 QQ 协议端，再通过反向 WebSocket 连接 AstrBot、NoneBot2、Koishi 等机器人框架。与成熟的桌面端 NapCat 相比，PicoOnebot 功能更少，但不需要在手机上准备 Root 或 Xposed，也不需要为协议端保留一套 PC QQ 桌面环境。

## 2. 手机端安装

从 GitHub Releases 下载最新 PicoOnebot APK 并安装。手表 QQ 底包只提供 `armeabi-v7a` 原生库，因此设备必须支持 32 位应用；纯 64 位且没有厂商转译层的机型无法直接安装。启动应用并授予必要权限后，如果直接在运行 PicoOnebot 的手机浏览器操作，可访问 `http://127.0.0.1:6099`；如果从同一局域网的其它设备访问，则使用 `http://<手机IP>:6099`。初始密码是 `picopico`，首次登录后需要修改。进入扫码页，用另一个已登录 QQ 的设备扫码，让机器人账号上线。

## 3. 机器人端只需提供入口

PicoOnebot 是反向 WebSocket 客户端，机器人框架只需提供一个手机能够访问的 OneBot v11 反向 WebSocket 服务端入口。机器人可以位于同一局域网，也可以位于具有公网可访问地址的服务器；PicoOnebot 会主动向该地址建立连接，因此不要求手机对外开放 OneBot 端口。以 AstrBot 为例，只需按其文档准备 OneBot v11 反向 WebSocket 实例；视频不展开 AstrBot 自身的安装和具体字段配置。

## 4. PicoOnebot 配置

在 PicoOnebot WebUI 的“网络配置”中添加“反向 WebSocket”。目标地址填写机器人框架实际提供的 `ws://` 或 `wss://` 地址，可以是局域网 IP，也可以是公网可访问的域名或 IP。这里要区分两个地址：手机浏览器访问本机 PicoOnebot 控制台时可以使用 `127.0.0.1:6099`；但如果机器人框架运行在外部机器，反向 WebSocket 目标不能填写 `127.0.0.1`，因为它仍然指向手机自身。对方若设置了 Token，PicoOnebot 中必须填写完全一致的值；未设置则留空。保存后网络配置会热重载，不需要重启 QQ，并会按配置自动重连。

## 5. 验证与边界

机器人框架显示 OneBot v11 适配器已连接后，在私聊或群聊中发送唤醒词测试。PicoOnebot 支持基础私聊、群聊和常用媒体消息，但群禁言、踢人、群名片、群公告等能力暂未支持或仍待验证；它不是 NapCat 的全功能复刻，更适合免 Root 手机、备用 Android 设备和轻量 OneBot 接入场景。
