# Video Outline

> **标题**：免 Root 手机端挂 AstrBot 的方法
> **主题**：`pico-pop`（Pico 萌系科技）—— 暖白画布、Pico 品牌粉与淡点阵网格，兼顾 B 站科技教程的清晰度和项目自身的萌系气质
> **总时长**：约 3 分钟
> **章节数**：5 章 / 21 步

---

## 1. mobile-route — 手机端的 NapCat 替代思路（4 steps · ~31s）

**信息池**：
- 视频命题：免 Root、免 Xposed，用旧 Android 手机给机器人框架提供 QQ 接入 —— 来源 article §1
- 项目定位：PicoOnebot 基于官方手表 QQ，对外提供 OneBot v11 —— 来源 article §1
- 对照关系：NapCat 功能更完整、更适合桌面端；PicoOnebot 聚焦手机与轻量场景 —— 来源 article §1 / §5

**开发计划**：
- step 1 — 旧 Android 手机与标题“免 Root 手机端挂 AstrBot 的方法”占据主舞台
- step 2 — Root、Xposed 两项传统门槛被划去，PicoOnebot Logo 成为焦点
- step 3 — 手表 QQ、PicoOnebot、OneBot v11 三层关系剖面
- step 4 — NapCat 桌面路线与 PicoOnebot 手机路线的适用场景对照

---

## 2. phone-setup — APK 安装与手机内登录（6 steps · ~50s）

**信息池**：
- 下载入口：GitHub Releases 的最新 PicoOnebot APK —— 来源 article §2
- 架构前提：手表 QQ 底包依赖 `armeabi-v7a`，设备必须支持 32 位应用 —— 来源 article §2
- 扩展平台：Windows / macOS 可使用 Android 模拟器；Linux / 服务器可使用内置 Android 环境的 Docker，代价是资源开销更大 —— 来源 article §2
- 本机 WebUI：直接在运行 PicoOnebot 的手机浏览器访问 `http://127.0.0.1:6099` —— 来源 article §2
- 跨设备 WebUI：同一局域网的其它设备使用 `http://<手机IP>:6099` —— 来源 article §2
- 初始认证：默认密码 `picopico`，首次登录后修改；随后扫码登录 QQ —— 来源 article §2

**开发计划**：
- step 1 — GitHub Releases、PicoOnebot APK 与“安装 / 打开”操作路径
- step 2 — 32 位支持条件与纯 64 位机型的兼容分界
- step 3 — 真机 APK、Windows / macOS Android 模拟器、Linux Docker 三种运行路线及开销对照
- step 4 — 基础权限与“手机内直接访问 / 其它设备局域网访问”两种控制台入口
- step 5 — WebUI 登录画面、本机 `127.0.0.1:6099`、初始密码与首次改密提示
- step 6 — 本地采集的扫码登录页面与 QQ 上线状态

---

## 3. bot-endpoint — 机器人端只准备一个入口（3 steps · ~25s）

**信息池**：
- 角色分工：PicoOnebot 在手机上提供 QQ 协议能力，机器人框架可位于电脑或服务器 —— 来源 article §3
- 最小要求：机器人框架提供手机可访问的 OneBot v11 反向 WebSocket 服务端入口 —— 来源 article §3
- 网络范围：目标既可以是局域网地址，也可以是公网可访问地址 —— 来源 article §3
- 产品特性：PicoOnebot 主动向外连接，不要求手机对外开放 OneBot 端口，断线会自动重连 —— 来源 article §3 / §4
- 内容边界：AstrBot 仅作为热门接入示例，不展开其安装和具体字段配置 —— 用户反馈

**开发计划**：
- step 1 — 手机端 PicoOnebot 与外部机器人框架的职责分区图
- step 2 — 机器人端只需提供“可访问的反向 WebSocket 入口”，不展开 AstrBot UI
- step 3 — 局域网与公网两条可达路径，突出手机主动外连与无需开放入站端口

---

## 4. connect-test — PicoOnebot 回连与验证（5 steps · ~40s）

**信息池**：
- PicoOnebot 入口：WebUI → 网络配置 → 添加反向 WebSocket —— 来源 article §4
- 目标模板：机器人实际提供的 `ws://` 或 `wss://` 地址；局域网 IP 或公网域名 / IP 均可，外部机器人不要写 `127.0.0.1` —— 来源 article §4
- 鉴权规则：对方设置 Token 时两端一致；未设置则留空 —— 来源 article §4
- 保存行为：配置保存后热重载，无需重启 QQ，并自动重连 —— 来源 article §4
- 成功标志：机器人端显示 OneBot v11 连接建立，消息测试可正常回复 —— 来源 article §5

**开发计划**：
- step 1 — 本地采集的 PicoOnebot“网络配置”页面与反向 WebSocket 入口
- step 2 — 一屏带过目标地址：内网 / 公网均可，外部机器人不要写 `127.0.0.1`
- step 3 — Token 的一致性检查
- step 4 — 保存后热重载、无需重启 QQ、断线自动重连
- step 5 — OneBot v11 已连接状态与群聊唤醒词正常回复

---

## 5. fit-and-limit — 它适合什么场景（3 steps · ~22s）

**信息池**：
- 定位边界：PicoOnebot 不是 NapCat 的全功能复刻 —— 来源 article §5
- 已知缺口：群禁言、踢人、群名片、群公告等能力暂未支持或待验证 —— 来源 article §5
- 推荐场景：免 Root 手机、备用 Android 设备、轻量 OneBot 接入 —— 来源 article §5
- 项目入口：github.com/Exmeaning/PicoOnebot —— 来源 article §2 / 项目地址

**开发计划**：
- step 1 — NapCat 全功能桌面路线与 PicoOnebot 轻量手机路线的最终选择
- step 2 — “群管理未齐 / 基础聊天可用”的能力边界
- step 3 — 旧手机连接机器人框架的完整链路、项目地址与安装文档收尾

---

## 素材清单

### 1. mobile-route
- ✓ PicoOnebot Logo 与应用图标
- ✓ 项目定位与 NapCat 对照事实（README / article）

### 2. phone-setup
- ✓ APK 安装、32 位兼容与替代部署信息（docs/deploy/apk.md / docs/deploy/docker.md）
- ✓ 脱敏采集的真实 WebUI 登录页与扫码页

### 3. bot-endpoint
- ✓ OneBot v11 反向 WebSocket、主动外连与自动重连事实（docs/config/network.md / WsClient.kt）
- ✓ AstrBot 仅保留名称与接入角色，不制作其具体配置页

### 4. connect-test
- ✓ 脱敏采集的 PicoOnebot 网络配置页面
- ✓ URL、Token、热重载与成功验证规则来自项目文档
- ✓ 地址全部使用模板或保留用途示例，不展示真实 Token

### 5. fit-and-limit
- ✓ 能力边界来自 README 与项目文档
- ✓ GitHub 项目地址、现有 Logo 与安装文档入口
